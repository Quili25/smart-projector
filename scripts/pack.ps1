#Requires -Version 5.1
<#
.SYNOPSIS
  Stub pack — replace with real packaging (PyInstaller, Velopack, etc.).
#>
param(
    [string]$Version = ""
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
if (-not $Version) {
    $Version = (Get-Content (Join-Path $root "VERSION") -Raw).Trim()
}
Write-Host "pack stub: version=$Version (implement scripts/pack.ps1 for this product)"
Write-Output $Version
