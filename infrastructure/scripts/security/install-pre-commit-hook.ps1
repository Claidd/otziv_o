[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = Resolve-Path (Join-Path $scriptRoot "..\..\..")
$gitDir = (git -C $repoRoot rev-parse --git-dir).Trim()
if (-not [System.IO.Path]::IsPathRooted($gitDir)) {
    $gitDir = Join-Path $repoRoot $gitDir
}

$hooksDir = Join-Path $gitDir "hooks"
$hookPath = Join-Path $hooksDir "pre-commit"
New-Item -ItemType Directory -Force $hooksDir | Out-Null

if (Test-Path $hookPath) {
    $backupPath = "$hookPath.codex-backup-$(Get-Date -Format 'yyyyMMddHHmmss')"
    Copy-Item -LiteralPath $hookPath -Destination $backupPath
    Write-Host "Existing pre-commit hook backed up to $backupPath" -ForegroundColor Yellow
}

$hook = @'
#!/bin/sh
set -eu

repo_root="$(git rev-parse --show-toplevel)"

if command -v pwsh >/dev/null 2>&1; then
  ps_cmd="pwsh"
elif command -v powershell.exe >/dev/null 2>&1; then
  ps_cmd="powershell.exe"
else
  echo "PowerShell is required to run the otziv secret scan hook." >&2
  exit 1
fi

"$ps_cmd" -NoProfile -ExecutionPolicy Bypass -File "$repo_root/infrastructure/scripts/security/run-secret-scan.ps1" -Mode staged
"$ps_cmd" -NoProfile -ExecutionPolicy Bypass -File "$repo_root/infrastructure/scripts/security/sql-injection-guard.ps1" -Mode staged
'@

$normalizedHook = $hook.Replace("`r`n", "`n")
[System.IO.File]::WriteAllText($hookPath, $normalizedHook, [System.Text.Encoding]::ASCII)

Write-Host "Installed pre-commit secret scan hook: $hookPath" -ForegroundColor Green
