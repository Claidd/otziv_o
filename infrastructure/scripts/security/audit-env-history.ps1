[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = Resolve-Path (Join-Path $scriptRoot "..\..\..")

Push-Location $repoRoot
try {
    Write-Host "Tracked real env files in current index:" -ForegroundColor Cyan
    $tracked = @(
        git ls-files |
                Where-Object {
                    ($_ -match '(^|/)\.env($|\.)') -and
                    ($_ -notmatch '\.example$') -and
                    ($_ -ne 'backend/.env.local.example')
                }
    )
    if ($tracked.Count -eq 0) {
        Write-Host "  none" -ForegroundColor Green
    } else {
        $tracked | ForEach-Object { Write-Host "  $_" -ForegroundColor Red }
    }

    Write-Host ""
    Write-Host "Env file history, metadata only:" -ForegroundColor Cyan
    git log --all --name-status --pretty=format:"COMMIT %H %ad %D %s" --date=iso-strict -- .env .env.prod .env.prod-local ".env*"

    Write-Host ""
    Write-Host "Branches containing first known .env commit:" -ForegroundColor Cyan
    git branch --all --contains 08f7e8ad6168f3c475417ca2d4cc92330147aefd

    Write-Host ""
    Write-Host "Tags containing first known .env commit:" -ForegroundColor Cyan
    $tags = @(git tag --contains 08f7e8ad6168f3c475417ca2d4cc92330147aefd)
    if ($tags.Count -eq 0) {
        Write-Host "  none" -ForegroundColor Green
    } else {
        $tags | ForEach-Object { Write-Host "  $_" }
    }
} finally {
    Pop-Location
}
