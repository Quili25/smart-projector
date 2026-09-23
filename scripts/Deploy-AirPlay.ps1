#Requires -Version 5.1
<#
.SYNOPSIS
  Install and configure the AirPlay receiver on the projector.

.DESCRIPTION
  Downloads a pinned upstream release of android-airplay-server (or installs a
  locally built APK), verifies its SHA-256, installs it over ADB, and applies the
  device tuning a projector needs: screen never sleeps, the receiver is exempt from
  battery optimization, and it survives on a device with very little free RAM.

  Without root the receiver is an ordinary user app, so these settings are what keep
  it alive. See docs/decisions.md (D5) for the trade-offs this accepts.

.PARAMETER Serial
  ADB serial (e.g. 192.168.0.42:5555). Defaults to the only attached device.

.PARAMETER ApkPath
  Install this local APK instead of downloading the pinned upstream release.

.PARAMETER SkipTuning
  Install only; leave device settings untouched.

.PARAMETER Force
  Reinstall even when the pinned version is already present.

.EXAMPLE
  ./scripts/Deploy-AirPlay.ps1

.EXAMPLE
  ./scripts/Deploy-AirPlay.ps1 -ApkPath ./build/app-release.apk
#>
[CmdletBinding()]
param(
    [string]$Serial = "",
    [string]$ApkPath = "",
    [switch]$SkipTuning,
    [switch]$Force
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

# Pinned upstream release. The digest is published in the GitHub release metadata;
# never install an unverified binary onto the device.
$UpstreamVersion = 'v0.0.31'
$UpstreamUrl = "https://github.com/jqssun/android-airplay-server/releases/download/$UpstreamVersion/app-release.apk"
$UpstreamSha256 = 'c5dce5c29ab52157bdaa406e485f9bfaab7d8d59c523d93622a33b253a28f410'
$PackageName = 'io.github.jqssun.airplay'

$RepoRoot = Split-Path -Parent $PSScriptRoot
$CacheDir = Join-Path $RepoRoot '.cache'

function Resolve-Adb {
    $cmd = Get-Command adb -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    $fallback = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
    if (Test-Path $fallback) { return $fallback }
    throw "adb not found. Install Android platform-tools."
}

function Invoke-Adb {
    param(
        [Parameter(Mandatory)][string]$AdbPath,
        [Parameter(Mandatory)][string[]]$Arguments,
        [int]$TimeoutSec = 300
    )

    $psi = [System.Diagnostics.ProcessStartInfo]::new()
    $psi.FileName = $AdbPath
    foreach ($a in $Arguments) { $psi.ArgumentList.Add($a) }
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true

    $proc = [System.Diagnostics.Process]::Start($psi)
    $stdout = $proc.StandardOutput.ReadToEndAsync()
    $stderr = $proc.StandardError.ReadToEndAsync()

    if (-not $proc.WaitForExit($TimeoutSec * 1000)) {
        try { $proc.Kill($true) } catch { }
        throw "adb $($Arguments -join ' ') timed out after ${TimeoutSec}s."
    }

    return [pscustomobject]@{
        StdOut   = $stdout.Result.Trim()
        StdErr   = $stderr.Result.Trim()
        ExitCode = $proc.ExitCode
    }
}

function Resolve-Serial {
    param([Parameter(Mandatory)][string]$AdbPath, [string]$Requested)

    $listing = (Invoke-Adb -AdbPath $AdbPath -Arguments @('devices') -TimeoutSec 30).StdOut
    $devices = @(
        $listing -split "`r?`n" |
            Select-Object -Skip 1 |
            Where-Object { $_ -match '^\S+\s+device$' } |
            ForEach-Object { ($_ -split '\s+')[0] }
    )

    if ($Requested) {
        if ($devices -contains $Requested) { return $Requested }
        throw "Device '$Requested' is not attached. Attached: $($devices -join ', ')"
    }
    if ($devices.Count -eq 1) { return $devices[0] }
    if ($devices.Count -eq 0) {
        throw "No device attached. Run ./scripts/Connect-Projector.ps1 first."
    }
    throw "Multiple devices attached ($($devices -join ', ')). Pass -Serial to disambiguate."
}

function Get-PinnedApk {
    <#
        Downloads the pinned release into .cache and verifies its digest. A cached
        file with a mismatched digest is discarded and re-fetched.
    #>
    if (-not (Test-Path $CacheDir)) {
        New-Item -ItemType Directory -Force -Path $CacheDir | Out-Null
    }
    $target = Join-Path $CacheDir "airplay-$UpstreamVersion.apk"

    if (Test-Path $target) {
        $existing = (Get-FileHash -Path $target -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($existing -eq $UpstreamSha256) {
            Write-Host "Using cached $UpstreamVersion APK." -ForegroundColor DarkGray
            return $target
        }
        Write-Warning "Cached APK digest mismatch; re-downloading."
        Remove-Item $target -Force
    }

    Write-Host "Downloading android-airplay-server $UpstreamVersion ..." -ForegroundColor Cyan
    $previousProgress = $ProgressPreference
    # Invoke-WebRequest's progress bar makes large downloads dramatically slower.
    $ProgressPreference = 'SilentlyContinue'
    try {
        [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
        Invoke-WebRequest -Uri $UpstreamUrl -OutFile $target -UseBasicParsing
    }
    finally {
        $ProgressPreference = $previousProgress
    }

    $actual = (Get-FileHash -Path $target -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actual -ne $UpstreamSha256) {
        Remove-Item $target -Force
        throw "SHA-256 mismatch for the downloaded APK (expected $UpstreamSha256, got $actual). Aborting."
    }

    Write-Host "Digest verified." -ForegroundColor Green
    return $target
}

function Get-InstalledVersion {
    param([Parameter(Mandatory)][string]$AdbPath, [Parameter(Mandatory)][string]$Serial)

    $out = (Invoke-Adb -AdbPath $AdbPath -Arguments @('-s', $Serial, 'shell', "dumpsys package $PackageName | grep versionName") -TimeoutSec 60).StdOut
    if ($out -match 'versionName=(\S+)') { return $Matches[1] }
    return $null
}

function Set-ProjectorTuning {
    <#
        A projector should never blank the screen, and the receiver must not be
        frozen by Doze while idle waiting for a connection.
    #>
    param([Parameter(Mandatory)][string]$AdbPath, [Parameter(Mandatory)][string]$Serial)

    $tweaks = @(
        @{ Label = 'Screen stays on while plugged in'; Command = 'settings put global stay_on_while_plugged_in 7' },
        @{ Label = 'Disable screen timeout';           Command = 'settings put system screen_off_timeout 2147483647' },
        @{ Label = 'Disable adaptive sleep';           Command = 'settings put secure adaptive_sleep 0' },
        @{ Label = 'Exempt receiver from Doze';        Command = "dumpsys deviceidle whitelist +$PackageName" }
    )

    foreach ($t in $tweaks) {
        $r = Invoke-Adb -AdbPath $AdbPath -Arguments @('-s', $Serial, 'shell', $t.Command) -TimeoutSec 30
        if ($r.ExitCode -eq 0) {
            Write-Host "  [ok]   $($t.Label)" -ForegroundColor DarkGray
        }
        else {
            # Stock firmware varies; a rejected tweak degrades behaviour but is not fatal.
            Write-Host "  [skip] $($t.Label) -> $($r.StdErr)" -ForegroundColor DarkYellow
        }
    }
}

# --- main -------------------------------------------------------------------

$adb = Resolve-Adb
$Serial = Resolve-Serial -AdbPath $adb -Requested $Serial
Write-Host "Target: $Serial" -ForegroundColor Cyan

$abi = (Invoke-Adb -AdbPath $adb -Arguments @('-s', $Serial, 'shell', 'getprop', 'ro.product.cpu.abi') -TimeoutSec 30).StdOut
Write-Host "Device ABI: $abi" -ForegroundColor DarkGray

if ($ApkPath) {
    if (-not (Test-Path $ApkPath)) { throw "APK not found: $ApkPath" }
    $apk = (Resolve-Path $ApkPath).Path
    Write-Host "Installing local APK: $apk"
}
else {
    $installed = Get-InstalledVersion -AdbPath $adb -Serial $Serial
    $pinnedVersion = $UpstreamVersion.TrimStart('v')
    if ($installed -eq $pinnedVersion -and -not $Force) {
        Write-Host "$PackageName $installed is already installed. Use -Force to reinstall." -ForegroundColor Yellow
        if (-not $SkipTuning) {
            Write-Host "`nApplying projector tuning ..." -ForegroundColor Cyan
            Set-ProjectorTuning -AdbPath $adb -Serial $Serial
        }
        return
    }
    $apk = Get-PinnedApk
}

Write-Host "`nInstalling ..." -ForegroundColor Cyan
# -r reinstall keeping data, -g pre-grant runtime permissions, -d allow downgrade.
$install = Invoke-Adb -AdbPath $adb -Arguments @('-s', $Serial, 'install', '-r', '-g', '-d', $apk) -TimeoutSec 600

$installOutput = ($install.StdOut + ' ' + $install.StdErr).Trim()
if ($installOutput -notmatch 'Success') {
    throw "Install failed: $installOutput"
}
Write-Host "Installed." -ForegroundColor Green

$version = Get-InstalledVersion -AdbPath $adb -Serial $Serial
Write-Host "$PackageName version $version" -ForegroundColor Green

if (-not $SkipTuning) {
    Write-Host "`nApplying projector tuning ..." -ForegroundColor Cyan
    Set-ProjectorTuning -AdbPath $adb -Serial $Serial
}

Write-Host "`nLaunching receiver ..." -ForegroundColor Cyan
$launch = Invoke-Adb -AdbPath $adb -Arguments @('-s', $Serial, 'shell', "monkey -p $PackageName -c android.intent.category.LAUNCHER 1") -TimeoutSec 60
if ($launch.ExitCode -ne 0) {
    Write-Warning "Could not launch automatically. Open the app from the projector's app list."
}

Write-Host ""
Write-Host "Done. On your iPhone/Mac open Control Center -> Screen Mirroring;" -ForegroundColor Green
Write-Host "the projector should appear as an AirPlay target."
Write-Host ""
Write-Host "Live logs:  adb -s $Serial logcat -s AirPlay:V UxPlay:V" -ForegroundColor DarkGray
