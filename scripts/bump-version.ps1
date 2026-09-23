#Requires -Version 5.1
<#
.SYNOPSIS
  Bump VERSION file (SemVer patch|minor|major).
#>
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet("patch", "minor", "major")]
    [string]$Bump,

    [string]$VersionFile = ""
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
if (-not $VersionFile) { $VersionFile = Join-Path $root "VERSION" }
if (-not (Test-Path $VersionFile)) { throw "VERSION file not found: $VersionFile" }

$raw = (Get-Content $VersionFile -Raw).Trim()
if ($raw -notmatch '^(?<major>\d+)\.(?<minor>\d+)\.(?<patch>\d+)$') {
    throw "Invalid VERSION format (expected X.Y.Z): '$raw'"
}

$major = [int]$Matches.major
$minor = [int]$Matches.minor
$patch = [int]$Matches.patch

switch ($Bump) {
    "major" { $major++; $minor = 0; $patch = 0 }
    "minor" { $minor++; $patch = 0 }
    "patch" { $patch++ }
}

$newVersion = "$major.$minor.$patch"
[System.IO.File]::WriteAllText($VersionFile, $newVersion)
Write-Host "VERSION: $raw -> $newVersion ($Bump)"
Write-Output $newVersion
