#Requires -Version 5.1
<#
.SYNOPSIS
  Discover a Magcubic/Allwinner H713 projector on the LAN and attach it over network ADB.

.DESCRIPTION
  The HY300 Pro exposes ADB over TCP (port 5555) once "ADB Debugging" is enabled in
  Developer options. This script resolves the adb binary, optionally sweeps the local
  /24 subnets for listening ADB daemons, connects, and verifies that the attached
  device really is the projector before reporting success.

  See docs/device-setup.md for how to unlock Developer options on the stock launcher.

.PARAMETER Ip
  Projector IP. When omitted the script sweeps every local IPv4 /24 subnet.

.PARAMETER Port
  ADB TCP port. Stock firmware listens on 5555.

.PARAMETER ProbeTimeoutMs
  Per-host TCP connect timeout used during the sweep.

.PARAMETER NoScan
  Fail instead of sweeping when -Ip is not supplied.

.EXAMPLE
  ./scripts/Connect-Projector.ps1 -Ip 192.168.0.42

.EXAMPLE
  ./scripts/Connect-Projector.ps1
#>
[CmdletBinding()]
param(
    [ValidatePattern('^$|^(\d{1,3}\.){3}\d{1,3}$')]
    [string]$Ip = "",

    [ValidateRange(1, 65535)]
    [int]$Port = 5555,

    [ValidateRange(50, 10000)]
    [int]$ProbeTimeoutMs = 500,

    [switch]$NoScan
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

# Device fingerprints we accept as "this is the projector". Matched against
# ro.product.model / ro.product.device, case-insensitively.
$script:KnownModelPatterns = @('HY300', 'HY310', 'HY320', 'L018', 'h713', 'tuna')

function Resolve-Adb {
    <#
        adb is not always on PATH even when the SDK is installed, so fall back to the
        canonical SDK locations before giving up.
    #>
    $cmd = Get-Command adb -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }

    $candidates = @(
        (Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'),
        (Join-Path $env:ProgramFiles 'Android\Android Studio\platform-tools\adb.exe'),
        (Join-Path $env:USERPROFILE 'AppData\Local\Android\Sdk\platform-tools\adb.exe')
    )
    if ($env:ANDROID_HOME) {
        $candidates += (Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe')
    }
    if ($env:ANDROID_SDK_ROOT) {
        $candidates += (Join-Path $env:ANDROID_SDK_ROOT 'platform-tools\adb.exe')
    }

    foreach ($c in $candidates) {
        if ($c -and (Test-Path $c)) { return $c }
    }

    throw "adb not found. Install Android platform-tools: https://developer.android.com/tools/releases/platform-tools"
}

function Invoke-Adb {
    <#
        Runs adb and returns stdout+stderr as a single string plus the exit code.
        adb writes routine progress to stderr, so we must not treat stderr as failure.
    #>
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
    # Read both pipes before waiting so a full pipe buffer cannot deadlock us.
    $stdout = $proc.StandardOutput.ReadToEndAsync()
    $stderr = $proc.StandardError.ReadToEndAsync()
    $proc.WaitForExit()

    $text = ($stdout.Result + $stderr.Result).Trim()
    return [pscustomobject]@{
        Output   = $text
        ExitCode = $proc.ExitCode
    }
}

function Get-LocalSubnet {
    <#
        Returns the distinct /24 prefixes (e.g. "192.168.0.") of every usable local
        IPv4 interface, so we only sweep networks this machine is actually on.
    #>
    $addresses = Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
        Where-Object {
            $_.IPAddress -notlike '127.*' -and
            $_.IPAddress -notlike '169.254.*' -and
            $_.PrefixLength -ge 16 -and
            $_.AddressState -eq 'Preferred'
        }

    $prefixes = foreach ($a in $addresses) {
        $octets = $a.IPAddress.Split('.')
        '{0}.{1}.{2}.' -f $octets[0], $octets[1], $octets[2]
    }

    return @($prefixes | Sort-Object -Unique)
}

function Find-AdbHost {
    <#
        Sweeps a /24 for hosts with the ADB port open. Probes run concurrently via
        async TCP connects; a sequential sweep of 254 hosts would take minutes.
    #>
    param(
        [Parameter(Mandatory)][string]$Prefix,
        [Parameter(Mandatory)][int]$Port,
        [Parameter(Mandatory)][int]$TimeoutMs
    )

    $probes = [System.Collections.Generic.List[object]]::new()
    foreach ($i in 1..254) {
        $address = "$Prefix$i"
        $client = [System.Net.Sockets.TcpClient]::new()
        try {
            $probes.Add([pscustomobject]@{
                Address = $address
                Client  = $client
                Async   = $client.BeginConnect($address, $Port, $null, $null)
            })
        }
        catch {
            $client.Dispose()
        }
    }

    # One shared deadline: every probe was started at roughly the same moment.
    Start-Sleep -Milliseconds $TimeoutMs

    $found = [System.Collections.Generic.List[string]]::new()
    foreach ($p in $probes) {
        try {
            if ($p.Async.IsCompleted -and $p.Client.Connected) {
                $found.Add($p.Address)
            }
        }
        catch {
            # Refused/unreachable hosts are the common case, not an error.
        }
        finally {
            $p.Client.Close()
            $p.Client.Dispose()
        }
    }

    return @($found)
}

function Test-IsProjector {
    <#
        Confirms the connected serial is the projector and not some other ADB device
        (phone, emulator) that happens to be listening on the network.
    #>
    param(
        [Parameter(Mandatory)][string]$AdbPath,
        [Parameter(Mandatory)][string]$Serial
    )

    $model = (Invoke-Adb -AdbPath $AdbPath -Arguments @('-s', $Serial, 'shell', 'getprop', 'ro.product.model')).Output
    $device = (Invoke-Adb -AdbPath $AdbPath -Arguments @('-s', $Serial, 'shell', 'getprop', 'ro.product.device')).Output
    $fingerprint = "$model $device"

    foreach ($pattern in $script:KnownModelPatterns) {
        if ($fingerprint -match [regex]::Escape($pattern)) {
            return [pscustomobject]@{ Match = $true; Model = $model; Device = $device }
        }
    }
    return [pscustomobject]@{ Match = $false; Model = $model; Device = $device }
}

function Connect-Target {
    param(
        [Parameter(Mandatory)][string]$AdbPath,
        [Parameter(Mandatory)][string]$Serial
    )

    $result = Invoke-Adb -AdbPath $AdbPath -Arguments @('connect', $Serial)
    # adb exits 0 even when it prints "failed to connect", so inspect the text.
    if ($result.Output -match 'connected to') { return $true }
    if ($result.Output -match 'already connected') { return $true }

    Write-Verbose "adb connect $Serial -> $($result.Output)"
    return $false
}

# --- main -------------------------------------------------------------------

$adb = Resolve-Adb
Write-Host "adb: $adb" -ForegroundColor DarkGray

$targets = @()
if ($Ip) {
    $targets = @("${Ip}:$Port")
}
elseif ($NoScan) {
    throw "No -Ip supplied and -NoScan was set. Provide the projector IP explicitly."
}
else {
    $subnets = Get-LocalSubnet
    if (-not $subnets) {
        throw "No usable IPv4 interface found. Connect this machine to the same network as the projector."
    }

    $subnetLabels = ($subnets | ForEach-Object { "$_" + "0/24" }) -join ', '
    Write-Host "Scanning for ADB on port $Port across: $subnetLabels" -ForegroundColor Cyan
    foreach ($subnet in $subnets) {
        $hits = Find-AdbHost -Prefix $subnet -Port $Port -TimeoutMs $ProbeTimeoutMs
        foreach ($h in $hits) { $targets += "${h}:$Port" }
    }

    if (-not $targets) {
        Write-Host ""
        Write-Warning "No device is listening on port $Port."
        Write-Host "Checklist:" -ForegroundColor Yellow
        Write-Host "  1. Projector and PC on the same Wi-Fi network (not a guest SSID)."
        Write-Host "  2. Developer options unlocked and 'ADB Debugging' enabled."
        Write-Host "  3. See docs/device-setup.md for the full walkthrough."
        exit 1
    }
}

$connected = $null
foreach ($target in $targets) {
    Write-Host "Connecting to $target ..." -NoNewline
    if (-not (Connect-Target -AdbPath $adb -Serial $target)) {
        Write-Host " unreachable" -ForegroundColor DarkYellow
        continue
    }

    $identity = Test-IsProjector -AdbPath $adb -Serial $target
    if ($identity.Match) {
        Write-Host " OK" -ForegroundColor Green
        $connected = [pscustomobject]@{ Serial = $target; Model = $identity.Model; Device = $identity.Device }
        break
    }

    Write-Host " not the projector (model: $($identity.Model))" -ForegroundColor DarkYellow
    Invoke-Adb -AdbPath $adb -Arguments @('disconnect', $target) | Out-Null
}

if (-not $connected) {
    Write-Error "Found ADB hosts but none identified as a supported projector: $($targets -join ', ')"
    exit 1
}

Write-Host ""
Write-Host "Connected to $($connected.Model) ($($connected.Device)) at $($connected.Serial)" -ForegroundColor Green
Write-Host "Next: ./scripts/Get-DeviceProfile.ps1 -Serial $($connected.Serial)"

# Emit the serial so callers can pipe it into the other scripts.
$connected.Serial
