[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = (Resolve-Path (Join-Path $scriptRoot "../../..")).Path

$configuredHooksPath = @(& git -C $repoRoot config --path --get core.hooksPath)
$configExitCode = $LASTEXITCODE
if ($configExitCode -eq 0 -and $configuredHooksPath.Count -gt 0 -and -not [string]::IsNullOrWhiteSpace($configuredHooksPath[0])) {
    $hooksDir = $configuredHooksPath[0].Trim()
}
elseif ($configExitCode -eq 1) {
    $hooksDir = (& git -C $repoRoot rev-parse --git-path hooks).Trim()
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($hooksDir)) {
        throw "Unable to resolve the Git hooks directory."
    }
}
else {
    throw "Unable to read core.hooksPath (git exit code $configExitCode)."
}

if (-not [System.IO.Path]::IsPathRooted($hooksDir)) {
    $hooksDir = Join-Path $repoRoot $hooksDir
}
$hooksDir = [System.IO.Path]::GetFullPath($hooksDir)

New-Item -ItemType Directory -Force $hooksDir | Out-Null

function Set-HookExecutable {
    param([Parameter(Mandatory = $true)][string]$Path)

    if ([System.Environment]::OSVersion.Platform -ne [System.PlatformID]::Win32NT) {
        & chmod u+x -- $Path
        if ($LASTEXITCODE -ne 0) {
            throw "Unable to make hook executable: $Path"
        }
    }
}

function Test-ManagedHook {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$Content
    )

    if ($Content.Contains("# otziv-managed-hook-v1") -or $Content.Contains("otziv repository safety hooks.")) {
        return $true
    }

    return $Name -eq "pre-commit" -and
        $Content.Contains("otziv secret scan hook.") -and
        $Content.Contains("run-secret-scan.ps1") -and
        $Content.Contains("sql-injection-guard.ps1")
}

function Install-Hook {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$Content
    )

    $hookPath = Join-Path $hooksDir $Name
    if (Test-Path $hookPath) {
        $existingContent = Get-Content -LiteralPath $hookPath -Raw
        if (-not (Test-ManagedHook -Name $Name -Content $existingContent)) {
            $backupPath = "$hookPath.codex-backup-$(Get-Date -Format 'yyyyMMddHHmmssfff')"
            $preservedHookPath = Join-Path $hooksDir "$Name.otziv-preserved"
            Copy-Item -LiteralPath $hookPath -Destination $backupPath
            Copy-Item -LiteralPath $hookPath -Destination $preservedHookPath -Force
            Set-HookExecutable -Path $preservedHookPath
            Write-Host "Existing $Name hook backed up to $backupPath and preserved in the hook chain." -ForegroundColor Yellow
        }
    }

    $normalizedHook = $Content.Replace("`r`n", "`n")
    [System.IO.File]::WriteAllText($hookPath, $normalizedHook, [System.Text.Encoding]::ASCII)
    Set-HookExecutable -Path $hookPath
    Write-Host "Installed $Name repository safety hook: $hookPath" -ForegroundColor Green
}

$preCommitHook = @'
#!/bin/sh
# otziv-managed-hook-v1
set -eu

repo_root="$(git rev-parse --show-toplevel)"
hook_dir=${0%/*}
if [ "$hook_dir" = "$0" ]; then
  hook_dir=.
fi
preserved_hook="$hook_dir/pre-commit.otziv-preserved"

if [ -x "$preserved_hook" ]; then
  "$preserved_hook" "$@"
fi

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
# otziv-managed-hook-v1
set -eu

repo_root="$(git rev-parse --show-toplevel)"
remote_name="${1:-}"
hook_dir=${0%/*}
if [ "$hook_dir" = "$0" ]; then
  hook_dir=.
fi
preserved_hook="$hook_dir/pre-push.otziv-preserved"
push_lines=""
while IFS= read -r push_line; do
  push_lines="${push_lines}${push_line}
"
done

if [ -x "$preserved_hook" ]; then
  printf '%s' "$push_lines" | "$preserved_hook" "$@"
fi

if command -v pwsh >/dev/null 2>&1; then
  ps_cmd="pwsh"
elif command -v powershell.exe >/dev/null 2>&1; then
  ps_cmd="powershell.exe"
else
  echo "PowerShell is required to run the otziv repository safety hooks." >&2
  exit 1
fi

carriage_return=$(printf '\r')
while read -r local_ref local_oid remote_ref remote_oid; do
  local_oid=${local_oid%"$carriage_return"}
  remote_oid=${remote_oid%"$carriage_return"}

  if [ -z "$local_oid" ]; then
    continue
  fi

  case "$local_oid" in
    *[!0]*) ;;
    *) continue ;;
  esac

  case "$remote_oid" in
    ''|*[!0]*)
      "$ps_cmd" -NoProfile -ExecutionPolicy Bypass -File "$repo_root/infrastructure/scripts/security/check-large-git-files.ps1" -Mode range -BaseRevision "$remote_oid" -TargetRevision "$local_oid" -ExcludeRemote "$remote_name"
      ;;
    *)
      "$ps_cmd" -NoProfile -ExecutionPolicy Bypass -File "$repo_root/infrastructure/scripts/security/check-large-git-files.ps1" -Mode range -TargetRevision "$local_oid" -ExcludeRemote "$remote_name"
      ;;
  esac
done <<OTZIV_PRE_PUSH_INPUT
${push_lines}
OTZIV_PRE_PUSH_INPUT
'@

Install-Hook -Name "pre-commit" -Content $preCommitHook
Install-Hook -Name "pre-push" -Content $prePushHook
