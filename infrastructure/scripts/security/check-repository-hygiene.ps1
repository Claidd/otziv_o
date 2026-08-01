[CmdletBinding()]
param(
    [string]$BaseRevision = "",
    [switch]$ReportOnly
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

function Invoke-GitLines {
    param([Parameter(Mandatory = $true)][string[]]$Arguments)

    $output = @(& git @Arguments)
    if ($LASTEXITCODE -ne 0) {
        throw "git $($Arguments -join ' ') failed with exit code $LASTEXITCODE"
    }
    return $output
}

function Test-GitCommit {
    param([Parameter(Mandatory = $true)][string]$Revision)

    & git cat-file -e "$Revision^{commit}" 2>$null
    return $LASTEXITCODE -eq 0
}

$repoRoot = (& git rev-parse --show-toplevel).Trim()
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($repoRoot)) {
    throw "Run this script from inside the repository."
}

$rules = @(
    [pscustomobject]@{ Name = "node_modules"; Pattern = '(^|/)node_modules/' },
    [pscustomobject]@{ Name = ".codex-tmp"; Pattern = '^\.codex-tmp/' },
    [pscustomobject]@{ Name = ".codex-remote-attachments"; Pattern = '^\.codex-remote-attachments/' },
    [pscustomobject]@{ Name = "mobile/www"; Pattern = '^mobile/www/' },
    [pscustomobject]@{ Name = "mobile/builds"; Pattern = '^mobile/builds/' },
    [pscustomobject]@{ Name = "generated-assets"; Pattern = '^generated-assets/' },
    [pscustomobject]@{ Name = "backend/target"; Pattern = '^backend/target/' },
    [pscustomobject]@{ Name = "frontend/dist"; Pattern = '^frontend/dist/' }
)

Push-Location $repoRoot
try {
    $trackedFiles = @(Invoke-GitLines -Arguments @("ls-files"))
    Write-Output "Tracked generated-artifact debt (report only for files already in history):"
    foreach ($rule in $rules) {
        $matches = @($trackedFiles | Where-Object { $_ -match $rule.Pattern })
        [long]$bytes = 0
        foreach ($path in $matches) {
            if (Test-Path -LiteralPath $path -PathType Leaf) {
                $bytes += (Get-Item -LiteralPath $path).Length
            }
        }
        Write-Output ("  {0}: {1} files, {2:N2} MiB" -f $rule.Name, $matches.Count, ($bytes / 1MB))
    }

    if ([string]::IsNullOrWhiteSpace($BaseRevision)) {
        Write-Warning "No base revision was supplied; existing debt was reported without diff enforcement."
        return
    }

    if (-not (Test-GitCommit -Revision $BaseRevision)) {
        Write-Warning "Base revision '$BaseRevision' is unavailable; existing debt was reported without diff enforcement."
        return
    }

    $changes = @(Invoke-GitLines -Arguments @(
        "diff",
        "--name-status",
        "--find-renames",
        $BaseRevision,
        "HEAD",
        "--"
    ))
    $violations = [System.Collections.Generic.List[string]]::new()

    foreach ($change in $changes) {
        $columns = @($change -split "`t")
        if ($columns.Count -lt 2) {
            continue
        }

        $status = $columns[0]
        if ($status -notmatch '^[ARC]') {
            continue
        }

        $candidate = $columns[$columns.Count - 1]
        foreach ($rule in $rules) {
            if ($candidate -match $rule.Pattern) {
                $violations.Add("$candidate ($status, rule: $($rule.Name))")
                break
            }
        }
    }

    if ($violations.Count -gt 0 -and -not $ReportOnly) {
        Write-Error ("New generated artifacts must not be tracked:`n - " + (($violations | Sort-Object -Unique) -join "`n - "))
    }
    elseif ($violations.Count -gt 0) {
        Write-Warning ("Report-only violations:`n - " + (($violations | Sort-Object -Unique) -join "`n - "))
    }

    Write-Output "Repository hygiene diff gate passed against $BaseRevision."
}
finally {
    Pop-Location
}
