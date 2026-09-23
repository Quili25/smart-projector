#Requires -Version 5.1
<#
.SYNOPSIS
  Push staging to LAN quality-gates, wait for Hermes/report, optional promote.
#>
param(
    [string] $SshTarget = "quilee@192.168.0.165",
    [string] $RemoteName = "lan",
    [string] $Branch = "staging",
    [Parameter(Mandatory = $true)]
    [string] $ProjectId,
    [string] $RemoteGateRoot = "/home/quilee/quality-gates",
    [string] $RemotePromoteScript = "/home/quilee/quality-gates/bin/promote-to-github.sh",
    [switch] $SkipPush,
    [switch] $Promote,
    [int] $MaxWaitSeconds = 600,
    [int] $PollSeconds = 5
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

$reportsDir = Join-Path $root ".gate-reports"
$localJson = Join-Path $reportsDir "last-result.json"
$localMd = Join-Path $reportsDir "last-report.md"
$remoteReports = "$RemoteGateRoot/projects/$ProjectId/gate-reports"
$remoteJson = "${SshTarget}:$remoteReports/last-result.json"
$remoteMd = "${SshTarget}:$remoteReports/last-report.md"

function Assert-Command([string] $Name) {
    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "Required command not found in PATH: $Name"
    }
}

function Get-HeadSha {
    $sha = (git rev-parse HEAD).Trim()
    if (-not $sha) { throw "Could not read git HEAD." }
    return $sha
}

function Get-ReportCommit([string] $JsonPath) {
    if (-not (Test-Path $JsonPath)) { return $null }
    try {
        $obj = Get-Content -Path $JsonPath -Raw -Encoding UTF8 | ConvertFrom-Json
        if ($null -eq $obj.commit) { return $null }
        return [string]$obj.commit
    }
    catch { return $null }
}

function Test-CommitMatch([string] $ReportCommit, [string] $HeadSha) {
    if ([string]::IsNullOrWhiteSpace($ReportCommit)) { return $false }
    $r = $ReportCommit.Trim().ToLowerInvariant()
    $h = $HeadSha.Trim().ToLowerInvariant()
    if ($r -eq $h) { return $true }
    if ($h.StartsWith($r) -or $r.StartsWith($h)) { return $true }
    return $false
}

function Write-ReportSummary([string] $JsonPath) {
    $obj = Get-Content -Path $JsonPath -Raw -Encoding UTF8 | ConvertFrom-Json
    $overall = if ($obj.overall) { $obj.overall } else { "?" }
    $allow = $obj.allowed_to_push_github
    $score = $null
    if ($obj.ai_review -and $null -ne $obj.ai_review.score) {
        $score = $obj.ai_review.score
    }
    elseif ($obj.gates -and $obj.gates.ai_review -and $null -ne $obj.gates.ai_review.score) {
        $score = $obj.gates.ai_review.score
    }

    Write-Host ""
    Write-Host "=== Quality gate result ===" -ForegroundColor Cyan
    Write-Host "  project: $ProjectId"
    Write-Host "  commit:  $($obj.commit)"
    Write-Host "  branch:  $($obj.branch)"
    Write-Host "  overall: $overall"
    if ($null -ne $score) { Write-Host "  Hermes/ai score: $score" }
    Write-Host "  allowed_to_push_github: $allow"
    if (Test-Path $localMd) { Write-Host "  report:  $localMd" }
    Write-Host "============================" -ForegroundColor Cyan
    Write-Host ""
    return $obj
}

Assert-Command "git"
Assert-Command "scp"
Assert-Command "ssh"

if (-not (Test-Path $reportsDir)) {
    New-Item -ItemType Directory -Path $reportsDir | Out-Null
}

$headSha = Get-HeadSha
Write-Host "Local HEAD: $headSha"

if (-not $SkipPush) {
    $remotes = git remote
    if ($remotes -notcontains $RemoteName) {
        throw @"
Git remote '$RemoteName' is not configured.
Add it with:
  git remote add $RemoteName ${SshTarget}:$RemoteGateRoot/projects/$ProjectId/git/$ProjectId.git
"@
    }
    Write-Host "Pushing to ${RemoteName}/${Branch}..."
    git push $RemoteName $Branch
    if ($LASTEXITCODE -ne 0) {
        throw "git push $RemoteName $Branch failed (exit $LASTEXITCODE)."
    }
    $headSha = Get-HeadSha
}
else {
    Write-Host "SkipPush: waiting for report for HEAD $headSha"
}

Write-Host "Fetching gate reports from $remoteReports (max ${MaxWaitSeconds}s)..."
$deadline = [DateTime]::UtcNow.AddSeconds($MaxWaitSeconds)
$matched = $false
$attempt = 0

while ([DateTime]::UtcNow -lt $deadline) {
    $attempt++
    try {
        scp -q $remoteJson $localJson 2>$null
        scp -q $remoteMd $localMd 2>$null
    }
    catch { }

    if (Test-Path $localJson) {
        $reportCommit = Get-ReportCommit $localJson
        if (Test-CommitMatch $reportCommit $headSha) {
            $matched = $true
            break
        }
        if ($attempt -eq 1 -or ($attempt % 6 -eq 0)) {
            Write-Host "  attempt $attempt : report commit='$reportCommit' (waiting for $headSha)..."
        }
    }
    elseif ($attempt -eq 1 -or ($attempt % 6 -eq 0)) {
        Write-Host "  attempt $attempt : no last-result.json yet..."
    }
    Start-Sleep -Seconds $PollSeconds
}

if (-not (Test-Path $localJson)) {
    throw "Timed out: could not download last-result.json from Debian."
}
if (-not $matched) {
    Write-Warning "Report may be for a different commit. Showing latest report anyway."
}

$report = Write-ReportSummary $localJson
$overall = [string]$report.overall
$isPass = $overall -eq "pass" -or $overall -eq "PASS"
$allowGh = $false
if ($null -ne $report.allowed_to_push_github) {
    $allowGh = [bool]$report.allowed_to_push_github
}

if ($Promote) {
    if (-not $isPass -or -not $allowGh) {
        throw "Refusing -Promote: overall must be pass and allowed_to_push_github must be true."
    }
    if (-not (Test-CommitMatch ([string]$report.commit) $headSha)) {
        throw "Refusing -Promote: report commit does not match local HEAD."
    }
    Write-Host "Promoting on Debian: $RemotePromoteScript $ProjectId"
    ssh $SshTarget "PROMOTE_CONFIRM=yes $RemotePromoteScript $ProjectId"
    if ($LASTEXITCODE -ne 0) {
        throw "promote-to-github failed (exit $LASTEXITCODE)."
    }
    Write-Host "Promote finished. GitHub Actions owns release." -ForegroundColor Green
}

if ($isPass) {
    Write-Host "PASS" -ForegroundColor Green
    exit 0
}

Write-Host "FAIL" -ForegroundColor Red
Write-Host "Read .gate-reports/last-report.md for findings. Remediate only after human approval."
exit 1
