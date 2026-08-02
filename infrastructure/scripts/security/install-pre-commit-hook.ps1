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
New-Item -ItemType Directory -Force $hooksDir | Out-Null

function Install-Hook {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$Content
    )

    $hookPath = Join-Path $hooksDir $Name
    if (Test-Path $hookPath) {
        $backupPath = "$hookPath.codex-backup-$(Get-Date -Format 'yyyyMMddHHmmss')"
        Copy-Item -LiteralPath $hookPath -Destination $backupPath
        Write-Host "Existing $Name hook backed up to $backupPath" -ForegroundColor Yellow
    }

    $normalizedHook = $Content.Replace("`r`n", "`n")
    [System.IO.File]::WriteAllText($hookPath, $normalizedHook, [System.Text.Encoding]::ASCII)
    Write-Host "Installed $Name repository safety hook: $hookPath" -ForegroundColor Green
}

$preCommitHook = @'
#!/bin/sh
set -eu

repo_root="$(git rev-parse --show-toplevel)"

if command -v pwsh >/dev/null 2>&1; then
  ps_cmd="pwsh"
elif command -v powershell.exe >/dev/null 2>&1; then
  ps_cmd="powershell.exe"
else
  echo "PowerShell is required to run the otziv repository safety hooks." >&2
  exit 1
fi

"$ps_cmd" -NoProfile -ExecutionPolicy Bypass -File "$repo_root/infrastructure/scripts/security/check-large-git-files.ps1" -Mode staged
"$ps_cmd" -NoProfile -ExecutionPolicy Bypass -File "$repo_root/infrastructure/scripts/security/run-secret-scan.ps1" -Mode staged
"$ps_cmd" -NoProfile -ExecutionPolicy Bypass -File "$repo_root/infrastructure/scripts/security/sql-injection-guard.ps1" -Mode staged
'@

$prePushHook = @'
#!/bin/sh
set -eu

repo_root="$(git rev-parse --show-toplevel)"

if command -v pwsh >/dev/null 2>&1; then
  ps_cmd="pwsh"
elif command -v powershell.exe >/dev/null 2>&1; then
  ps_cmd="powershell.exe"
else
  echo "PowerShell is required to run the otziv repository safety hooks." >&2
  exit 1
fi

while read -r local_ref local_oid remote_ref remote_oid; do
  case "$local_oid" in
    ''|*[!0]*) ;;
    *) continue ;;
  esac

  case "$remote_oid" in
    ''|*[!0]*)
      "$ps_cmd" -NoProfile -ExecutionPolicy Bypass -File "$repo_root/infrastructure/scripts/security/check-large-git-files.ps1" -Mode range -BaseRevision "$remote_oid" -TargetRevision "$local_oid"
      ;;
    *)
      "$ps_cmd" -NoProfile -ExecutionPolicy Bypass -File "$repo_root/infrastructure/scripts/security/check-large-git-files.ps1" -Mode range -TargetRevision "$local_oid"
      ;;
  esac
done
'@

Install-Hook -Name "pre-commit" -Content $preCommitHook
Install-Hook -Name "pre-push" -Content $prePushHook
