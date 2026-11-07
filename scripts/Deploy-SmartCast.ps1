#Requires -Version 5.1
<#
.SYNOPSIS
  Build and install the SmartCast receiver (projector) and sender (phone) apps.

.DESCRIPTION
  Builds both APKs with Gradle, then installs each onto the device you point it at.
  The receiver additionally gets the configuration a projector needs and that, without
  root, is the only thing keeping it alive and discoverable: exemption from Doze, screen
  timeout disabled, and permission to bring its window to the front after a reboot.

  Devices are selected explicitly because the projector and the phone are usually both
  attached at once and installing the wrong APK on the wrong one is confusing to undo.

.PARAMETER ReceiverSerial
  ADB serial of the projector. Skipped when omitted.

.PARAMETER SenderSerial
  ADB serial of the phone. Skipped when omitted.

.PARAMETER BuildType
  debug (default) or release. Release requires signing config in android/local.properties.

.PARAMETER SkipBuild
  Install the APKs already present in the build directories.

.EXAMPLE
  ./scripts/Deploy-SmartCast.ps1 -ReceiverSerial 192.168.0.42:5555

.EXAMPLE
  ./scripts/Deploy-SmartCast.ps1 -ReceiverSerial 192.168.0.42:5555 -SenderSerial ABC123
#>
[CmdletBinding()]
param(
    [string]$ReceiverSerial = "",
    [string]$SenderSerial = "",

    [ValidateSet("debug", "release")]
    [string]$BuildType = "debug",

    [switch]$SkipBuild
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$RepoRoot = Split-Path -Parent $PSScriptRoot
$AndroidRoot = Join-Path $RepoRoot 'android'

# Debug builds carry a .debug application id so they can sit next to a release installed
# from the LAN page, whose signature they could never update.
$PackageSuffix = if ($BuildType -eq 'debug') { '.debug' } else { '' }
$ReceiverPackage = "mx.quili.smartprojector.receiver$PackageSuffix"

function Resolve-Adb {
    $cmd = Get-Command adb -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    $fallback = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
    if (Test-Path $fallback) { return $fallback }
    throw "adb not found. Install Android platform-tools."
}

function Join-NativeArguments {
    # ProcessStartInfo.ArgumentList does not exist on Windows PowerShell 5.1 (.NET
    # Framework), so arguments are quoted by hand using the CommandLineToArgvW rules.
    param([string[]]$Arguments)
    ($Arguments | ForEach-Object {
            if ($_ -and $_ -notmatch '[\s"]') { $_ }
            else { '"' + (($_ -replace '(\\*)"', '$1$1\"') -replace '(\\+)$', '$1$1') + '"' }
        }) -join ' '
}

function Invoke-Adb {
    param(
        [Parameter(Mandatory)][string]$AdbPath,
        [Parameter(Mandatory)][string[]]$Arguments,
        [int]$TimeoutSec = 300
    )

    $psi = [System.Diagnostics.ProcessStartInfo]::new()
    $psi.FileName = $AdbPath
    $psi.Arguments = Join-NativeArguments $Arguments
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true

    $proc = [System.Diagnostics.Process]::Start($psi)
    $stdout = $proc.StandardOutput.ReadToEndAsync()
    $stderr = $proc.StandardError.ReadToEndAsync()

    if (-not $proc.WaitForExit($TimeoutSec * 1000)) {
        try { $proc.Kill() } catch { }
        throw "adb $($Arguments -join ' ') timed out after ${TimeoutSec}s."
    }

    return [pscustomobject]@{
        StdOut   = $stdout.Result.Trim()
        StdErr   = $stderr.Result.Trim()
        ExitCode = $proc.ExitCode
    }
}

function Assert-DeviceAttached {
    param([Parameter(Mandatory)][string]$AdbPath, [Parameter(Mandatory)][string]$Serial)

    $listing = (Invoke-Adb -AdbPath $AdbPath -Arguments @('devices') -TimeoutSec 30).StdOut
    $attached = @(
        $listing -split "`r?`n" |
            Select-Object -Skip 1 |
            Where-Object { $_ -match '^\S+\s+device$' } |
            ForEach-Object { ($_ -split '\s+')[0] }
    )
    if ($attached -notcontains $Serial) {
        throw "Device '$Serial' is not attached. Attached: $($attached -join ', ')"
    }
}

function Invoke-Gradle {
    param([Parameter(Mandatory)][string[]]$Tasks)

    $gradlew = Join-Path $AndroidRoot 'gradlew.bat'
    if (-not (Test-Path $gradlew)) { throw "Gradle wrapper missing at $gradlew" }

    Write-Host "Building: $($Tasks -join ', ')" -ForegroundColor Cyan
    Push-Location $AndroidRoot
    try {
        & $gradlew @Tasks '--console=plain'
        if ($LASTEXITCODE -ne 0) {
            throw "Gradle build failed with exit code $LASTEXITCODE"
        }
    }
    finally {
        Pop-Location
    }
}

function Get-ApkPath {
    param([Parameter(Mandatory)][string]$Module)

    $dir = Join-Path $AndroidRoot "$Module\build\outputs\apk\$BuildType"
    if (-not (Test-Path $dir)) {
        throw "No build output at $dir. Run without -SkipBuild first."
    }

    $apk = Get-ChildItem $dir -Filter '*.apk' -File |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1

    if (-not $apk) { throw "No APK found in $dir" }
    return $apk.FullName
}

function Install-Apk {
    param(
        [Parameter(Mandatory)][string]$AdbPath,
        [Parameter(Mandatory)][string]$Serial,
        [Parameter(Mandatory)][string]$ApkPath,
        [Parameter(Mandatory)][string]$Label
    )

    Write-Host "Installing $Label on $Serial ..." -ForegroundColor Cyan
    # -r keeps data across reinstalls, -g pre-grants runtime permissions,
    # -d tolerates installing an older versionCode during development.
    $result = Invoke-Adb -AdbPath $AdbPath -Arguments @(
        '-s', $Serial, 'install', '-r', '-g', '-d', $ApkPath
    ) -TimeoutSec 600

    $output = ($result.StdOut + ' ' + $result.StdErr).Trim()
    if ($output -notmatch 'Success') {
        throw "Install of $Label failed: $output"
    }
    Write-Host "  installed" -ForegroundColor Green
}

function Set-ReceiverTuning {
    param([Parameter(Mandatory)][string]$AdbPath, [Parameter(Mandatory)][string]$Serial)

    $tweaks = @(
        @{ Label = 'Screen stays on while plugged in'; Command = 'settings put global stay_on_while_plugged_in 7' },
        @{ Label = 'Disable screen timeout'; Command = 'settings put system screen_off_timeout 2147483647' },
        @{ Label = 'Exempt receiver from Doze'; Command = "dumpsys deviceidle whitelist +$ReceiverPackage" },
        # Lets BootReceiver bring the UI forward after a reboot. Android 10+ blocks
        # background activity starts unless the app can draw over other apps.
        @{ Label = 'Allow starting its window from the background'; Command = "appops set $ReceiverPackage SYSTEM_ALERT_WINDOW allow" }
    )

    Write-Host "Configuring the projector ..." -ForegroundColor Cyan
    foreach ($t in $tweaks) {
        $r = Invoke-Adb -AdbPath $AdbPath -Arguments @('-s', $Serial, 'shell', $t.Command) -TimeoutSec 30
        if ($r.ExitCode -eq 0) {
            Write-Host "  [ok]   $($t.Label)" -ForegroundColor DarkGray
        }
        else {
            # Stock firmware differs between production batches; a refused tweak
            # degrades behaviour but must not abort the deployment.
            Write-Host "  [skip] $($t.Label)" -ForegroundColor DarkYellow
        }
    }
}

# --- main -------------------------------------------------------------------

if (-not $ReceiverSerial -and -not $SenderSerial) {
    throw "Nothing to do: pass -ReceiverSerial, -SenderSerial, or both."
}

$adb = Resolve-Adb

if ($ReceiverSerial) { Assert-DeviceAttached -AdbPath $adb -Serial $ReceiverSerial }
if ($SenderSerial) { Assert-DeviceAttached -AdbPath $adb -Serial $SenderSerial }

if (-not $SkipBuild) {
    $tasks = @(':protocol:test')
    $variant = if ($BuildType -eq 'release') { 'assembleRelease' } else { 'assembleDebug' }
    if ($ReceiverSerial) { $tasks += ":receiver:$variant" }
    if ($SenderSerial) { $tasks += ":sender:$variant" }
    Invoke-Gradle -Tasks $tasks
}

if ($ReceiverSerial) {
    Install-Apk -AdbPath $adb -Serial $ReceiverSerial -ApkPath (Get-ApkPath -Module 'receiver') -Label 'receiver'
    Set-ReceiverTuning -AdbPath $adb -Serial $ReceiverSerial

    Write-Host "Launching receiver ..." -ForegroundColor Cyan
    Invoke-Adb -AdbPath $adb -Arguments @(
        '-s', $ReceiverSerial, 'shell',
        "monkey -p $ReceiverPackage -c android.intent.category.LAUNCHER 1"
    ) -TimeoutSec 60 | Out-Null
}

if ($SenderSerial) {
    Install-Apk -AdbPath $adb -Serial $SenderSerial -ApkPath (Get-ApkPath -Module 'sender') -Label 'sender'
}

Write-Host ""
Write-Host "Done." -ForegroundColor Green
if ($ReceiverSerial) {
    Write-Host "The projector now shows a 6-digit pairing code. Open SmartCast on the phone,"
    Write-Host "pick the projector, and type that code once."
    Write-Host ""
    Write-Host "Receiver logs: adb -s $ReceiverSerial logcat -s ReceiverService:V SenderSession:V VideoDecoder:V" -ForegroundColor DarkGray
}
if ($SenderSerial) {
    Write-Host "Sender logs:   adb -s $SenderSerial logcat -s CastService:V ScreenEncoder:V MdnsDiscovery:V" -ForegroundColor DarkGray
}
