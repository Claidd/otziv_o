[CmdletBinding()]
param(
    [ValidateSet("dir", "git", "staged")]
    [string]$Mode = "dir"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = Resolve-Path (Join-Path $scriptRoot "..\..\..")
$configPath = Join-Path $repoRoot ".gitleaks.toml"

function Assert-NoStagedEnvFiles {
    $staged = git -C $repoRoot diff --cached --name-only --diff-filter=ACMRT
    $blocked = @(
        $staged | Where-Object {
            ($_ -match '(^|/)\.env($|\.)') -and
            ($_ -notmatch '\.example$') -and
            ($_ -ne 'backend/.env.local.example')
        }
    )

    if ($blocked.Count -gt 0) {
        Write-Host "Blocked: real env files must not be committed:" -ForegroundColor Red
        $blocked | ForEach-Object { Write-Host "  $_" -ForegroundColor Red }
        Write-Host "Keep real env files in `$env:USERPROFILE\.otziv\env and commit only .env*.example." -ForegroundColor Yellow
        exit 1
    }
}

function Invoke-GitleaksNative {
    param([string[]]$Arguments)

    $command = Get-Command gitleaks -ErrorAction SilentlyContinue
    if ($command) {
        & $command.Source @Arguments
        return $LASTEXITCODE
    }

    $docker = Get-Command docker -ErrorAction SilentlyContinue
    if ($docker) {
        $translatedArguments = @(
            $Arguments | ForEach-Object {
                $arg = $_
                if ($arg -eq $configPath) {
                    "/repo/.gitleaks.toml"
                } elseif ([System.IO.Path]::IsPathRooted($arg) -and $arg.StartsWith($repoRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
                    $relative = [System.IO.Path]::GetRelativePath($repoRoot, $arg).Replace("\", "/")
                    "/repo/$relative"
                } else {
                    $arg
                }
            }
        )
        $dockerArgs = @(
            "run",
            "--rm",
            "-v",
            "${repoRoot}:/repo",
            "-w",
            "/repo",
            "ghcr.io/gitleaks/gitleaks:latest"
        ) + $translatedArguments
        & $docker.Source @dockerArgs
        return $LASTEXITCODE
    }

    Write-Host "gitleaks is not installed and Docker is not available." -ForegroundColor Red
    Write-Host "Install gitleaks or Docker, then rerun this script." -ForegroundColor Yellow
    Write-Host "Windows install example: winget install Gitleaks.Gitleaks" -ForegroundColor Yellow
    return 127
}

if (-not (Test-Path $configPath)) {
    Write-Host "Missing gitleaks config: $configPath" -ForegroundColor Red
    exit 1
}

Push-Location $repoRoot
try {
    $commonArgs = @("--config", $configPath, "--redact", "--no-banner")

    if ($Mode -eq "git") {
        $exitCode = Invoke-GitleaksNative -Arguments (@("git") + $commonArgs + @("."))
    } elseif ($Mode -eq "staged") {
        Assert-NoStagedEnvFiles
        $stagedFiles = @(git -C $repoRoot diff --cached --name-only --diff-filter=ACMRT)
        if ($stagedFiles.Count -eq 0) {
            Write-Host "No staged files to scan." -ForegroundColor Green
            exit 0
        }
        $tempRoot = Join-Path $repoRoot (".gitleaks-staged-index-" + [System.Guid]::NewGuid())
        New-Item -ItemType Directory -Force $tempRoot | Out-Null
        try {
            foreach ($path in $stagedFiles) {
                git -C $repoRoot checkout-index --force --prefix "$tempRoot/" -- $path
                if ($LASTEXITCODE -ne 0) {
                    exit $LASTEXITCODE
                }
            }
            $exitCode = Invoke-GitleaksNative -Arguments (@("dir") + $commonArgs + @($tempRoot))
        } finally {
            Remove-Item -LiteralPath $tempRoot -Recurse -Force -ErrorAction SilentlyContinue
        }
    } else {
        $exitCode = Invoke-GitleaksNative -Arguments (@("dir") + $commonArgs + @("."))
    }

    if ($exitCode -ne 0) {
        exit $exitCode
    }
} finally {
    Pop-Location
}
