#Requires -Version 5.1
<#
.SYNOPSIS
  Detect SemVer bump from commit message. Returns: skip | major | minor | patch
#>
param(
    [Parameter(Mandatory = $true)]
    [string]$Message
)

$ErrorActionPreference = "Stop"
$text = $Message.ToLowerInvariant()

if ($text -match '\[skip\s*release\]' -or $text -match '(?m)^skip\s+release\b' -or $text -match 'version:\s*skip') {
    Write-Output "skip"
    exit 0
}

$isMajor = $text -match 'version:\s*major' -or $text -match '\[major\]'
$isMinor = $text -match 'version:\s*minor' -or $text -match '\[minor\]'
$isPatch = $text -match 'version:\s*patch' -or $text -match '\[patch\]'

if ($isMajor) { Write-Output "major"; exit 0 }
if ($isMinor) { Write-Output "minor"; exit 0 }
if ($isPatch) { Write-Output "patch"; exit 0 }

Write-Output "patch"
