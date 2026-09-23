#Requires -Version 5.1
<#
.SYNOPSIS
  Capture the hardware/software profile of the connected projector.

.DESCRIPTION
  AirPlay mirroring on this class of device is constrained by three things we cannot
  guess from the spec sheet: the real CPU ABI (Hotack builds the H713 kernel in 32-bit
  mode even though the Cortex-A53 is 64-bit capable), which hardware video decoders the
  SoC actually exposes to MediaCodec, and how much RAM is free once the stock launcher
  and services are running.

  This script collects those facts plus display and network details, writes them to
  docs/hardware-profile.md, and prints a summary with warnings for anything that would
  block or degrade AirPlay mirroring.

.PARAMETER Serial
  ADB serial (e.g. 192.168.0.42:5555). Defaults to the only attached device.

.PARAMETER OutFile
  Markdown report destination. Defaults to docs/hardware-profile.md.

.EXAMPLE
  ./scripts/Get-DeviceProfile.ps1 -Serial 192.168.0.42:5555
#>
[CmdletBinding()]
param(
    [string]$Serial = "",
    [string]$OutFile = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$RepoRoot = Split-Path -Parent $PSScriptRoot
if (-not $OutFile) {
    $OutFile = Join-Path $RepoRoot 'docs\hardware-profile.md'
}

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
        [Parameter(Mandatory)][string[]]$Arguments
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
    $proc.WaitForExit()

    return [pscustomobject]@{
        StdOut   = $stdout.Result.Trim()
        StdErr   = $stderr.Result.Trim()
        ExitCode = $proc.ExitCode
    }
}

function Resolve-Serial {
    param([Parameter(Mandatory)][string]$AdbPath, [string]$Requested)

    $listing = (Invoke-Adb -AdbPath $AdbPath -Arguments @('devices')).StdOut
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

function Get-Prop {
    param([Parameter(Mandatory)][string]$AdbPath, [Parameter(Mandatory)][string]$Serial, [Parameter(Mandatory)][string]$Name)
    return (Invoke-Adb -AdbPath $AdbPath -Arguments @('-s', $Serial, 'shell', 'getprop', $Name)).StdOut
}

function Get-Shell {
    param([Parameter(Mandatory)][string]$AdbPath, [Parameter(Mandatory)][string]$Serial, [Parameter(Mandatory)][string]$Command)
    $r = Invoke-Adb -AdbPath $AdbPath -Arguments @('-s', $Serial, 'shell', $Command)
    if ($r.ExitCode -ne 0 -and -not $r.StdOut) { return "" }
    return $r.StdOut
}

# --- collect ----------------------------------------------------------------

$adb = Resolve-Adb
$Serial = Resolve-Serial -AdbPath $adb -Requested $Serial
Write-Host "Profiling $Serial ..." -ForegroundColor Cyan

$props = [ordered]@{}
$propNames = @(
    'ro.product.model', 'ro.product.device', 'ro.product.manufacturer', 'ro.product.board',
    'ro.build.version.release', 'ro.build.version.sdk', 'ro.build.display.id',
    'ro.product.cpu.abi', 'ro.product.cpu.abilist', 'ro.product.cpu.abilist32', 'ro.product.cpu.abilist64',
    'ro.hardware', 'ro.board.platform', 'dalvik.vm.heapgrowthlimit', 'dalvik.vm.heapsize'
)
foreach ($p in $propNames) {
    $props[$p] = Get-Prop -AdbPath $adb -Serial $Serial -Name $p
}

$display = Get-Shell -AdbPath $adb -Serial $Serial -Command 'wm size; wm density'
$memInfo = Get-Shell -AdbPath $adb -Serial $Serial -Command 'cat /proc/meminfo'
$cpuCount = Get-Shell -AdbPath $adb -Serial $Serial -Command 'cat /proc/cpuinfo | grep -c processor'
$storage = Get-Shell -AdbPath $adb -Serial $Serial -Command 'df -h /data'
$wifiIp = Get-Shell -AdbPath $adb -Serial $Serial -Command "ip -f inet addr show wlan0 | grep inet"

# Hardware decoders are the make-or-break detail for mirroring; OMX./c2. entries
# without a ".sw." or "google" marker indicate real hardware acceleration.
$codecs = Get-Shell -AdbPath $adb -Serial $Serial -Command "cat /vendor/etc/media_codecs.xml 2>/dev/null || cat /system/etc/media_codecs.xml 2>/dev/null"

$decoderLines = @()
if ($codecs) {
    $decoderLines = @(
        $codecs -split "`r?`n" |
            Where-Object { $_ -match '<MediaCodec\s+name="([^"]+)"\s+type="video/([^"]+)"' } |
            ForEach-Object {
                if ($_ -match 'name="([^"]+)"\s+type="video/([^"]+)"') {
                    "{0}  ({1})" -f $Matches[1], $Matches[2]
                }
            }
    )
}

$memTotalKb = 0
$memAvailKb = 0
if ($memInfo -match 'MemTotal:\s+(\d+)') { $memTotalKb = [int]$Matches[1] }
if ($memInfo -match 'MemAvailable:\s+(\d+)') { $memAvailKb = [int]$Matches[1] }

# --- report -----------------------------------------------------------------

$abi = $props['ro.product.cpu.abi']
$sdk = $props['ro.build.version.sdk']

$sb = [System.Text.StringBuilder]::new()
[void]$sb.AppendLine("# Hardware profile")
[void]$sb.AppendLine()
[void]$sb.AppendLine("Captured $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss') from ``$Serial``.")
[void]$sb.AppendLine()
[void]$sb.AppendLine("## Identity")
[void]$sb.AppendLine()
[void]$sb.AppendLine("| Property | Value |")
[void]$sb.AppendLine("|---|---|")
foreach ($k in $props.Keys) {
    $v = $props[$k]
    if (-not $v) { $v = '_(unset)_' }
    [void]$sb.AppendLine("| ``$k`` | $v |")
}
[void]$sb.AppendLine()
[void]$sb.AppendLine("## Display")
[void]$sb.AppendLine()
[void]$sb.AppendLine('```')
[void]$sb.AppendLine($display)
[void]$sb.AppendLine('```')
[void]$sb.AppendLine()
[void]$sb.AppendLine("## Memory")
[void]$sb.AppendLine()
[void]$sb.AppendLine("- CPU cores: $cpuCount")
[void]$sb.AppendLine("- MemTotal: $([math]::Round($memTotalKb / 1024)) MB")
[void]$sb.AppendLine("- MemAvailable: $([math]::Round($memAvailKb / 1024)) MB")
[void]$sb.AppendLine()
[void]$sb.AppendLine("## Storage (/data)")
[void]$sb.AppendLine()
[void]$sb.AppendLine('```')
[void]$sb.AppendLine($storage)
[void]$sb.AppendLine('```')
[void]$sb.AppendLine()
[void]$sb.AppendLine("## Video decoders")
[void]$sb.AppendLine()
if ($decoderLines.Count -gt 0) {
    [void]$sb.AppendLine('```')
    foreach ($d in $decoderLines) { [void]$sb.AppendLine($d) }
    [void]$sb.AppendLine('```')
}
else {
    [void]$sb.AppendLine("_media_codecs.xml not readable; query MediaCodec from the app instead._")
}
[void]$sb.AppendLine()
[void]$sb.AppendLine("## Network")
[void]$sb.AppendLine()
[void]$sb.AppendLine('```')
[void]$sb.AppendLine($wifiIp)
[void]$sb.AppendLine('```')

$outDir = Split-Path -Parent $OutFile
if (-not (Test-Path $outDir)) { New-Item -ItemType Directory -Force -Path $outDir | Out-Null }
[System.IO.File]::WriteAllText($OutFile, $sb.ToString())

# --- summary ----------------------------------------------------------------

Write-Host ""
Write-Host "Model:   $($props['ro.product.model'])" -ForegroundColor Green
Write-Host "Android: $($props['ro.build.version.release']) (SDK $sdk)"
Write-Host "ABI:     $abi   [abilist: $($props['ro.product.cpu.abilist'])]"
Write-Host "Memory:  $([math]::Round($memAvailKb / 1024)) MB free of $([math]::Round($memTotalKb / 1024)) MB"
Write-Host "Decoders found: $($decoderLines.Count)"
Write-Host ""
Write-Host "Report written to $OutFile" -ForegroundColor Green

# Surface the conditions that would block or degrade mirroring.
if ($sdk -and [int]$sdk -lt 24) {
    Write-Warning "SDK $sdk is below the AirPlay receiver minimum (24)."
}
if ($abi -eq 'armeabi-v7a') {
    Write-Host ""
    Write-Host "NOTE: 32-bit ABI. Build with abiFilters 'armeabi-v7a' to keep the APK small." -ForegroundColor Yellow
}
if ($memAvailKb -gt 0 -and $memAvailKb -lt 300000) {
    Write-Warning "Under 300 MB free. Expect the system to kill the receiver during mirroring; debloating the stock launcher may be required."
}
$hwDecoders = @($decoderLines | Where-Object { $_ -notmatch 'google|\.sw\.|software' })
if ($decoderLines.Count -gt 0 -and $hwDecoders.Count -eq 0) {
    Write-Warning "No hardware video decoder detected. Software decoding will not sustain 720p mirroring on this SoC."
}
