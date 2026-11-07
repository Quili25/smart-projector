#Requires -Version 5.1
<#
.SYNOPSIS
  Builds both release APKs of staging HEAD and publishes them on the LAN download page
  (http://192.168.0.165:8088/smart-projector/), together with the latest.json each app's
  update check reads.

.DESCRIPTION
  The receiver and the sender speak the same wire protocol, so they are versioned and
  published as one build. Each lands in its own folder with its own manifest, which keeps
  the schema identical to the other LAN-published apps in the house.

  Preconditions: clean tree on staging, HEAD already pushed to lan/staging, and a PASS gate
  report for HEAD (unless -AllowUngated, which labels the page as an ungated test build).
  Both APKs must be signed with the release key, whose certificate fingerprint sits next to
  the keystore in cert.sha256, and the build number must beat the published one, otherwise
  Android would refuse to install it as an update.

.PARAMETER InitKeystore
  Creates the release keystore and keystore.properties, then exits. Run once. Anything
  already installed from a differently signed APK must be uninstalled before the first
  release build can replace it.

.EXAMPLE
  ./scripts/Publish-Lan.ps1 -InitKeystore

.EXAMPLE
  ./scripts/Publish-Lan.ps1

.EXAMPLE
  ./scripts/Publish-Lan.ps1 -AllowUngated
#>
[CmdletBinding(DefaultParameterSetName = 'Publish')]
param(
    [string] $SshTarget = "quilee@192.168.0.165",
    [string] $RemoteDir = "/mnt/Z/lan-apk/public/smart-projector",
    [string] $PageUrl = "http://192.168.0.165:8088/smart-projector/",
    [string] $RemoteGateRoot = "/home/quilee/quality-gates",
    [string] $ProjectId = "smart-projector",
    [string] $RemoteName = "lan",
    [string] $Branch = "staging",

    [ValidateRange(1, 20)]
    [int] $Keep = 5,

    [Parameter(ParameterSetName = 'Publish')]
    [switch] $AllowUngated,

    [Parameter(ParameterSetName = 'InitKeystore', Mandatory = $true)]
    [switch] $InitKeystore
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$RepoRoot = Split-Path -Parent $PSScriptRoot
$AndroidRoot = Join-Path $RepoRoot 'android'
$KeystoreProps = Join-Path $AndroidRoot 'keystore.properties'

$VersionPattern = '^(\d{1,4}\.\d{1,4}\.\d{1,4})(-beta\.\d{1,6})?$'

# Each app gets its own folder and manifest under the project page.
$Apps = @(
    [pscustomobject]@{
        Module  = 'receiver'
        Package = 'mx.quili.smartprojector.receiver'
        Title   = 'SmartCast Receptor'
        Target  = 'Instálalo en el proyector'
    },
    [pscustomobject]@{
        Module  = 'sender'
        Package = 'mx.quili.smartprojector.sender'
        Title   = 'SmartCast'
        Target  = 'Instálalo en el celular Android'
    }
)

function Assert-Command([string] $Name) {
    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "Required command not found in PATH: $Name"
    }
}

# PowerShell turns a native tool's stderr into a terminating error under "Stop" once output
# is redirected; exit codes are what decide success here.
function Invoke-Tolerant([scriptblock] $Command) {
    $previous = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try { return & $Command } finally { $ErrorActionPreference = $previous }
}

function Invoke-Native([string] $What, [scriptblock] $Command) {
    $output = Invoke-Tolerant $Command
    if ($LASTEXITCODE -ne 0) { throw "$What failed (exit $LASTEXITCODE)." }
    return $output
}

function Read-Properties([string] $Path) {
    $props = @{}
    foreach ($line in Get-Content -Path $Path -Encoding UTF8) {
        if ($line -match '^\s*([A-Za-z0-9_.]+)\s*=\s*(.*)$') { $props[$Matches[1]] = $Matches[2].Trim() }
    }
    return $props
}

function Get-SdkDir {
    $local = Join-Path $AndroidRoot 'local.properties'
    if (Test-Path $local) {
        $dir = (Read-Properties $local)["sdk.dir"]
        if ($dir) { return $dir.Replace('\\', '\').Replace('\:', ':') }
    }
    foreach ($candidate in @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, (Join-Path $env:LOCALAPPDATA 'Android\Sdk'))) {
        if ($candidate -and (Test-Path $candidate)) { return $candidate }
    }
    throw "Android SDK not found. Set sdk.dir in android/local.properties or ANDROID_HOME."
}

function Get-BuildTool([string] $SdkDir, [string] $Name) {
    $dirs = Get-ChildItem -Path (Join-Path $SdkDir "build-tools") -Directory -ErrorAction SilentlyContinue |
        Sort-Object { try { [version]$_.Name } catch { [version]"0.0" } } -Descending
    foreach ($dir in $dirs) {
        foreach ($candidate in @("$Name.bat", "$Name.exe", $Name)) {
            $path = Join-Path $dir.FullName $candidate
            if (Test-Path $path) { return $path }
        }
    }
    throw "Android build-tools '$Name' not found under $SdkDir\build-tools."
}

function Write-Utf8([string] $Path, [string] $Text) {
    [IO.File]::WriteAllText($Path, $Text.Replace("`r`n", "`n"), (New-Object Text.UTF8Encoding($false)))
}

function ConvertTo-Html([string] $Text) { return [System.Net.WebUtility]::HtmlEncode($Text) }

function New-ReleaseKeystore {
    if (Test-Path $KeystoreProps) {
        throw "$KeystoreProps already exists. Delete it only if you are ready to lose the ability to update installed apps."
    }

    $keytool = ""
    if ($env:JAVA_HOME) {
        $candidate = Join-Path $env:JAVA_HOME 'bin\keytool.exe'
        if (Test-Path $candidate) { $keytool = $candidate }
    }
    if (-not $keytool) {
        $command = Get-Command keytool -ErrorAction SilentlyContinue
        if ($command) { $keytool = $command.Source }
    }
    if (-not $keytool) { throw "keytool not found. Set JAVA_HOME to a JDK 17 install." }

    $storePath = Join-Path $AndroidRoot 'smartcast-release.jks'
    if (Test-Path $storePath) { throw "$storePath already exists." }

    # Generated, never typed: the password only guards a file that stays on this machine,
    # and a memorable one would be the weakest part of the signing chain.
    $bytes = [byte[]]::new(24)
    [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    $password = [Convert]::ToBase64String($bytes).Replace('/', '_').Replace('+', '-').TrimEnd('=')

    Write-Host "Creating the release keystore ..." -ForegroundColor Cyan
    Invoke-Native "keytool" {
        & $keytool -genkeypair -v -keystore $storePath -alias smartcast `
            -keyalg RSA -keysize 4096 -validity 10950 `
            -storepass $password -keypass $password `
            -dname "CN=SmartCast, OU=Casa, O=Quili, L=Monterrey, C=MX" 2>&1 | Out-Null
    }

    Write-Utf8 $KeystoreProps @"
# Release signing for both SmartCast apps. Never commit this file or the keystore:
# losing them means no installed app can ever be updated again, only reinstalled.
storeFile=smartcast-release.jks
storePassword=$password
keyAlias=smartcast
keyPassword=$password
"@

    $keytoolOut = Invoke-Native "keytool -list" {
        & $keytool -list -v -keystore $storePath -alias smartcast -storepass $password
    }
    $fingerprint = ($keytoolOut | Select-String -Pattern 'SHA256:\s*((?:[0-9A-F]{2}:){31}[0-9A-F]{2})' |
        Select-Object -First 1).Matches.Groups[1].Value
    if (-not $fingerprint) { throw "Could not read the certificate fingerprint from the new keystore." }
    Write-Utf8 (Join-Path $AndroidRoot 'cert.sha256') (($fingerprint -replace ':', '').ToLowerInvariant() + "`n")

    Write-Host ""
    Write-Host "Keystore created." -ForegroundColor Green
    Write-Host "  keystore:    $storePath"
    Write-Host "  properties:  $KeystoreProps"
    Write-Host "  fingerprint: $(($fingerprint -replace ':', '').ToLowerInvariant())"
    Write-Host ""
    Write-Host "Back both files up outside this machine. Without them the apps can never be updated." -ForegroundColor Yellow
    Write-Host "Uninstall any debug build before installing the first release: the signatures differ." -ForegroundColor Yellow
}

# --- main -------------------------------------------------------------------

if ($InitKeystore) {
    New-ReleaseKeystore
    return
}

Assert-Command "git"; Assert-Command "ssh"; Assert-Command "scp"

if ($PageUrl -notmatch '^https?://[A-Za-z0-9.-]+(:\d{1,5})?(/[A-Za-z0-9._-]+)*/$') {
    throw "-PageUrl must be an http(s) URL ending in /."
}

Set-Location $RepoRoot

# --- Source: clean staging HEAD that the LAN hub already has ---------------------------------
$currentBranch = (Invoke-Native "git rev-parse" { git rev-parse --abbrev-ref HEAD }).Trim()
if ($currentBranch -ne $Branch) { throw "Publish from '$Branch' (current branch: $currentBranch)." }
if (Invoke-Native "git status" { git status --porcelain }) { throw "Working tree is not clean; commit or stash first." }
Invoke-Native "git fetch $RemoteName" { git fetch --quiet $RemoteName $Branch } | Out-Null
$head = (Invoke-Native "git rev-parse" { git rev-parse HEAD }).Trim()
$remoteHead = (Invoke-Native "git rev-parse" { git rev-parse "$RemoteName/$Branch" }).Trim()
if ($head -ne $remoteHead) { throw "HEAD ($head) is not $RemoteName/$Branch ($remoteHead); run push-and-wait first." }

$work = Join-Path ([IO.Path]::GetTempPath()) ("sp-publish-" + [Guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Path $work | Out-Null
try {
    # --- Gate result for HEAD -----------------------------------------------------------------
    $gated = $false
    $gateJson = Join-Path $work "last-result.json"
    Invoke-Tolerant { scp -q "${SshTarget}:$RemoteGateRoot/projects/$ProjectId/gate-reports/last-result.json" $gateJson } | Out-Null
    if ($LASTEXITCODE -eq 0 -and (Test-Path $gateJson)) {
        $gate = Get-Content -Path $gateJson -Raw -Encoding UTF8 | ConvertFrom-Json
        $gated = ([string]$gate.commit -eq $head) -and ([string]$gate.overall -eq "pass") -and ([string]$gate.branch -eq $Branch)
    }
    if (-not $gated -and -not $AllowUngated) {
        throw "No PASS gate report for $head on $Branch. Fix the gates, or pass -AllowUngated for a labelled test build."
    }
    if (-not $gated) { Write-Warning "Publishing an UNGATED test build of $head." }

    # --- Signing and SDK ----------------------------------------------------------------------
    if (-not (Test-Path $KeystoreProps)) {
        throw "android/keystore.properties is missing; run with -InitKeystore first. A debug-signed APK cannot update an installed app."
    }
    $storeFile = (Read-Properties $KeystoreProps)["storeFile"]
    if (-not $storeFile) { throw "android/keystore.properties has no storeFile." }
    $storePath = if ([IO.Path]::IsPathRooted($storeFile)) { $storeFile } else { Join-Path $AndroidRoot $storeFile }
    if (-not (Test-Path $storePath)) { throw "Release keystore not found: $storePath" }

    $certFile = Join-Path $AndroidRoot 'cert.sha256'
    if (-not (Test-Path $certFile)) { throw "Release certificate fingerprint not found: $certFile" }
    $expectedCert = (Get-Content -Path $certFile -Raw).Trim().ToLowerInvariant()
    if ($expectedCert -notmatch '^[0-9a-f]{64}$') { throw "Invalid fingerprint in $certFile." }

    $sdkDir = Get-SdkDir
    $apksigner = Get-BuildTool $sdkDir "apksigner"
    $aapt2 = Get-BuildTool $sdkDir "aapt2"

    # --- Version: must beat what the devices already have --------------------------------------
    $gradlew = Join-Path $AndroidRoot 'gradlew.bat'
    if (-not (Test-Path $gradlew)) { throw "Gradle wrapper missing at $gradlew" }

    $versionLines = Invoke-Native "gradlew printVersion" {
        Push-Location $AndroidRoot
        try { & $gradlew 'printVersion' '--quiet' '--console=plain' } finally { Pop-Location }
    }
    $version = @{}
    foreach ($line in $versionLines) { if ("$line" -match '^(name|code)=(\S+)$') { $version[$Matches[1]] = $Matches[2] } }
    if (-not $version.ContainsKey('name') -or -not $version.ContainsKey('code')) {
        throw "printVersion did not report a version."
    }
    $versionName = $version['name']
    if ($versionName -notmatch $VersionPattern) { throw "Computed an invalid version: $versionName" }
    $versionCore = $Matches[1]
    $versionCode = [int]$version['code']

    foreach ($app in $Apps) {
        $published = Invoke-Tolerant { ssh $SshTarget "cat $RemoteDir/$($app.Module)/latest.json 2>/dev/null || true" }
        if ($LASTEXITCODE -ne 0) { throw "Could not read the published latest.json over ssh (exit $LASTEXITCODE)." }
        if ($published) {
            $publishedCode = [int](($published -join "`n") | ConvertFrom-Json).versionCode
            if ($versionCode -le $publishedCode) {
                throw "Build $versionCode does not beat the published $($app.Module) build $publishedCode; Android would not install it as an update."
            }
        }
    }

    # --- Build and verify ------------------------------------------------------------------------
    Write-Host "Building SmartCast $versionName (build $versionCode) ..." -ForegroundColor Cyan
    Push-Location $AndroidRoot
    try {
        Invoke-Native "gradlew" {
            & $gradlew 'test' ':receiver:assembleRelease' ':sender:assembleRelease' '--console=plain' 2>&1 |
                ForEach-Object { "$_" } | Out-Host
        }
    }
    finally {
        Pop-Location
    }

    $publishedAt = [DateTime]::UtcNow.ToString("yyyy-MM-ddTHH:mm:ssZ")
    $artifacts = @()

    foreach ($app in $Apps) {
        $builtApk = Join-Path $AndroidRoot "$($app.Module)\build\outputs\apk\release\$($app.Module)-release.apk"
        if (-not (Test-Path $builtApk)) { throw "Build did not produce $builtApk." }

        $signer = Invoke-Native "apksigner verify" { & $apksigner verify --print-certs $builtApk }
        $actualCert = ($signer | Select-String -Pattern '^Signer #1 certificate SHA-256 digest: ([0-9a-f]{64})$' |
            Select-Object -First 1).Matches.Groups[1].Value
        if ($actualCert -ne $expectedCert) { throw "$($app.Module) is not signed with the release key ($actualCert)." }

        $badging = (Invoke-Native "aapt2 dump badging" { & $aapt2 dump badging $builtApk }) | Select-Object -First 1
        $expected = "package: name='$($app.Package)' versionCode='$versionCode' versionName='$versionName'"
        if (-not $badging.StartsWith($expected)) { throw "APK identity mismatch for $($app.Module): $badging" }

        $apkName = "smartcast-$($app.Module)-$versionName-$versionCode.apk"
        $localApk = Join-Path $work $apkName
        Copy-Item -Path $builtApk -Destination $localApk
        $sha = (Get-FileHash -Path $localApk -Algorithm SHA256).Hash.ToLowerInvariant()
        $size = (Get-Item $localApk).Length

        $manifest = [ordered]@{
            schemaVersion  = 1
            versionCode    = $versionCode
            versionName    = $versionCore
            displayVersion = $versionName
            commit         = $head
            apk            = $apkName
            sha256         = $sha
            sizeBytes      = $size
            publishedAt    = $publishedAt
        }
        $manifestPath = Join-Path $work "$($app.Module)-latest.json"
        Write-Utf8 $manifestPath (($manifest | ConvertTo-Json) + "`n")

        $artifacts += [pscustomobject]@{
            App          = $app
            ApkName      = $apkName
            LocalApk     = $localApk
            ManifestPath = $manifestPath
            Sha256       = $sha
            SizeMb       = ($size / 1MB).ToString("0.0", [Globalization.CultureInfo]::InvariantCulture)
        }
    }

    # --- Pages -----------------------------------------------------------------------------------
    $label = if ($gated) { "staging" } else { "prueba sin gates" }
    $stamp = $publishedAt.Replace("T", " ").Replace("Z", " UTC")
    $style = @"
  body { font-family: system-ui, sans-serif; margin: 0; padding: 24px; max-width: 520px; margin-inline: auto; line-height: 1.5; }
  h1 { color: #4FC3F7; margin-bottom: 4px; }
  .version { font-size: 1.1rem; margin: 0 0 20px; opacity: .8; }
  a.button { display: block; text-align: center; background: #0277BD; color: #fff; padding: 16px; border-radius: 28px;
             font-size: 1.15rem; font-weight: 600; text-decoration: none; margin-bottom: 10px; }
  .note { background: rgba(79, 195, 247, .12); border-radius: 12px; padding: 12px 16px; font-size: .95rem; }
  ol { padding-left: 20px; }
  code { word-break: break-all; font-size: .8rem; }
  .meta { font-size: .85rem; opacity: .75; }
"@

    foreach ($artifact in $artifacts) {
        $page = @"
<!doctype html>
<html lang="es-MX">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="color-scheme" content="light dark">
<title>$(ConvertTo-Html $artifact.App.Title) &middot; descarga</title>
<style>
$style</style>
</head>
<body>
<h1>$(ConvertTo-Html $artifact.App.Title)</h1>
<p class="version">Versi&oacute;n $(ConvertTo-Html $versionName) &middot; build $versionCode &middot; $($artifact.SizeMb) MB &middot; $label</p>
<a class="button" href="$(ConvertTo-Html $artifact.ApkName)" download>Descargar e instalar</a>
<p class="note">$(ConvertTo-Html $artifact.App.Target).</p>
<ol>
  <li>Toca el bot&oacute;n y espera a que termine la descarga.</li>
  <li>Abre el archivo desde la notificaci&oacute;n; la primera vez permite que el navegador instale apps.</li>
  <li>Elige <strong>Actualizar</strong>: el emparejamiento se conserva. La app avisa sola cuando hay una versi&oacute;n nueva.</li>
</ol>
<p class="meta"><a href="../">&larr; Las dos apps de SmartCast</a></p>
<p class="meta">Publicado $stamp &middot; commit <code>$($head.Substring(0, 12))</code></p>
<p class="meta">SHA-256 <code>$($artifact.Sha256)</code></p>
</body>
</html>
"@
        Write-Utf8 (Join-Path $work "$($artifact.App.Module)-index.html") $page
    }

    $cards = ($artifacts | ForEach-Object {
            "<a class=`"button`" href=`"$($_.App.Module)/`">$(ConvertTo-Html $_.App.Title) &middot; $($_.SizeMb) MB</a>" +
            "`n<p class=`"note`">$(ConvertTo-Html $_.App.Target).</p>"
        }) -join "`n"

    $index = @"
<!doctype html>
<html lang="es-MX">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="color-scheme" content="light dark">
<title>SmartCast &middot; descarga</title>
<style>
$style</style>
</head>
<body>
<h1>SmartCast</h1>
<p class="version">Versi&oacute;n $(ConvertTo-Html $versionName) &middot; build $versionCode &middot; $label</p>
$cards
<p class="note">Los iPhone no necesitan app: usan AirPlay contra el receptor del proyector.</p>
<p class="meta">Publicado $stamp &middot; commit <code>$($head.Substring(0, 12))</code></p>
</body>
</html>
"@
    Write-Utf8 (Join-Path $work "index.html") $index

    # --- Upload: dot-temp files (hidden by nginx), verify, then swap ------------------------------
    Invoke-Native "remote mkdir" {
        ssh $SshTarget "set -eu; mkdir -p $RemoteDir/receiver $RemoteDir/sender"
    } | Out-Null

    foreach ($artifact in $artifacts) {
        $module = $artifact.App.Module
        $dir = "$RemoteDir/$module"
        Write-Host "Uploading $($artifact.ApkName) ($($artifact.SizeMb) MB) ..." -ForegroundColor Cyan
        Invoke-Native "scp apk" { scp -q $artifact.LocalApk "${SshTarget}:$dir/.$($artifact.ApkName).tmp" } | Out-Null
        Invoke-Native "scp page" { scp -q (Join-Path $work "$module-index.html") "${SshTarget}:$dir/.index.html.tmp" } | Out-Null
        Invoke-Native "scp manifest" { scp -q $artifact.ManifestPath "${SshTarget}:$dir/.latest.json.tmp" } | Out-Null

        $prune = $Keep + 1
        $swap = "set -eu; cd $dir; " +
            "test `$(sha256sum .$($artifact.ApkName).tmp | cut -c1-64) = $($artifact.Sha256); " +
            "chmod 644 .$($artifact.ApkName).tmp .index.html.tmp .latest.json.tmp; " +
            "mv -f .$($artifact.ApkName).tmp $($artifact.ApkName); " +
            "mv -f .index.html.tmp index.html; " +
            # The manifest goes last: it is what points the installed apps at the new APK.
            "mv -f .latest.json.tmp latest.json; " +
            "cp $($artifact.ApkName) .latest.apk.tmp; mv -f .latest.apk.tmp latest.apk; " +
            "ls -1t smartcast-$module-*.apk | tail -n +$prune | xargs -r rm -f --"
        Invoke-Native "remote swap" { ssh $SshTarget $swap } | Out-Null
    }

    Invoke-Native "scp index" { scp -q (Join-Path $work "index.html") "${SshTarget}:$RemoteDir/.index.html.tmp" } | Out-Null
    Invoke-Native "remote swap index" {
        ssh $SshTarget "set -eu; cd $RemoteDir; chmod 644 .index.html.tmp; mv -f .index.html.tmp index.html"
    } | Out-Null

    Write-Host ""
    Write-Host "Published SmartCast $versionName (build $versionCode)" -ForegroundColor Green
    Write-Host "  page:   $PageUrl"
    foreach ($artifact in $artifacts) {
        Write-Host "  $($artifact.App.Module.PadRight(8)) $($artifact.Sha256)"
    }
    Write-Host "  gated:  $gated"
}
finally {
    Remove-Item -Recurse -Force -Path $work -ErrorAction SilentlyContinue
}
