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
    [pscustomobject]@{ Name = "node_modules"; Pattern = '(^|/)node_modules/'; EnforceCurrent = $true },
    [pscustomobject]@{ Name = ".codex-tmp"; Pattern = '^\.codex-tmp/'; EnforceCurrent = $true },
    [pscustomobject]@{ Name = ".codex-remote-attachments"; Pattern = '^\.codex-remote-attachments/'; EnforceCurrent = $true },
    [pscustomobject]@{ Name = "mobile/www"; Pattern = '^mobile/www/'; EnforceCurrent = $true },
    [pscustomobject]@{ Name = "archived C26 raw runtime evidence"; Pattern = '^infrastructure/runtime-security/proofs/c26-(?:keycloak|nginx|phpmyadmin)/'; EnforceCurrent = $true },
    # Binary delivery artifacts are recoverable through the reviewed independent
    # archive. Source importer/manifests remain the only generated-assets inputs.
    [pscustomobject]@{ Name = "archived mobile builds"; Pattern = '^mobile/builds/'; EnforceCurrent = $true },
    [pscustomobject]@{ Name = "archived generated assets"; Pattern = '^generated-assets/(?!notification-media-v2/(?:import_to_production\.py|manifest\.json)$|notification-media-received-20260801/manifest\.json$)'; EnforceCurrent = $true },
    [pscustomobject]@{ Name = "build-support generated output"; Pattern = '^backend/build-support/(?:[^/]+/)?target/'; EnforceCurrent = $true },
    [pscustomobject]@{ Name = "backend/target"; Pattern = '^backend/target/'; EnforceCurrent = $true },
    [pscustomobject]@{ Name = "frontend/dist"; Pattern = '^frontend/dist/'; EnforceCurrent = $true },
    [pscustomobject]@{ Name = "sensitive payment capture"; Pattern = '^payment-profile-comment-preview\.png$'; EnforceCurrent = $true },
    [pscustomobject]@{ Name = "external check capture"; Pattern = '^tmp-external-check-.*\.png$'; EnforceCurrent = $true },
    [pscustomobject]@{ Name = "accidental root query artifact"; Pattern = '^(?:CHAR\(50|issue_count|=)$'; EnforceCurrent = $true }
)

# Deletion is allowed only for the exact source blobs in the reviewed migration;
# an offline receipt binding check is mandatory even on a shallow clean clone.
$retainedRecoveryBinaryPattern = '^(?:mobile/builds/.*\.(?:apk|xml)|generated-assets/.*\.(?:png|jpg|tar\.gz))$'

Push-Location $repoRoot
try {
    $trackedFiles = @(Invoke-GitLines -Arguments @("ls-files"))
    Write-Output "Tracked generated/sensitive artifact check:"
    $violations = [System.Collections.Generic.List[string]]::new()
    foreach ($rule in $rules) {
        $matches = @($trackedFiles | Where-Object { $_ -match $rule.Pattern })
        [long]$bytes = 0
        foreach ($path in $matches) {
            if (Test-Path -LiteralPath $path -PathType Leaf) {
                $bytes += (Get-Item -LiteralPath $path).Length
            }
        }
        Write-Output ("  {0}: {1} files, {2:N2} MiB" -f $rule.Name, $matches.Count, ($bytes / 1MB))
        if ($rule.EnforceCurrent) {
            foreach ($match in $matches) {
                $violations.Add("$match (tracked, rule: $($rule.Name))")
            }
        }
    }
    $archivedRecoveryFiles = @{}
    $evidenceOutput = @(& python -B (Join-Path $repoRoot 'infrastructure/scripts/prod/retained_artifacts.py') verify-records --directory (Join-Path $repoRoot 'infrastructure/artifact-recovery'))
    if ($LASTEXITCODE -ne 0) {
        $violations.Add('Retained artifact recovery manifest/receipts are missing or inconsistent')
    } else {
        $evidence = ($evidenceOutput -join '') | ConvertFrom-Json -AsHashtable
        if ($evidence.result -cne 'PASS' -or $evidence.schema -cne 'otziv-retained-artifacts-v1') {
            $violations.Add('Retained artifact recovery check returned an invalid result')
        } else {
            $archivedRecoveryFiles = $evidence.files
            Write-Output ("  independently archived recovery files: {0}" -f $archivedRecoveryFiles.Count)
        }
    }

    if ([string]::IsNullOrWhiteSpace($BaseRevision)) {
        if ($violations.Count -gt 0 -and -not $ReportOnly) {
            Write-Error ("Generated or sensitive artifacts must not be tracked:`n - " + (($violations | Sort-Object -Unique) -join "`n - "))
        }
        elseif ($violations.Count -gt 0) {
            Write-Warning ("Report-only violations:`n - " + (($violations | Sort-Object -Unique) -join "`n - "))
        }
        Write-Output "Repository hygiene current-tree gate passed."
        return
    }

    if (-not (Test-GitCommit -Revision $BaseRevision)) {
        if ($violations.Count -gt 0 -and -not $ReportOnly) {
            Write-Error ("Base revision '$BaseRevision' is unavailable and the current tree contains forbidden tracked artifacts:`n - " + (($violations | Sort-Object -Unique) -join "`n - "))
        }
        Write-Warning "Base revision '$BaseRevision' is unavailable; current-tree enforcement was used."
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
    foreach ($change in $changes) {
        $columns = @($change -split "`t")
        if ($columns.Count -lt 2) {
            continue
        }

        $status = $columns[0]
        $candidate = $columns[$columns.Count - 1]
        if ($status -match '^[DM]' -and $candidate -match $retainedRecoveryBinaryPattern) {
            $archivedDeletion = $false
            if ($status -ceq 'D' -and $archivedRecoveryFiles.ContainsKey($candidate)) {
                $sourceBlob = (@(Invoke-GitLines -Arguments @('rev-parse', "${BaseRevision}:$candidate")) -join '').Trim()
                $archivedDeletion = $sourceBlob -ceq $archivedRecoveryFiles[$candidate]
            }
            if (-not $archivedDeletion) {
                $violations.Add("$candidate ($status, not an exact verified recovery-archive deletion)")
            }
            continue
        }
        if ($status -notmatch '^[ARC]') {
            continue
        }

        foreach ($rule in $rules) {
            if ($candidate -match $rule.Pattern) {
                $violations.Add("$candidate ($status, rule: $($rule.Name))")
                break
            }
        }
    }

    if ($violations.Count -gt 0 -and -not $ReportOnly) {
        Write-Error ("Generated or sensitive artifacts must not be tracked:`n - " + (($violations | Sort-Object -Unique) -join "`n - "))
    }
    elseif ($violations.Count -gt 0) {
        Write-Warning ("Report-only violations:`n - " + (($violations | Sort-Object -Unique) -join "`n - "))
    }

    Write-Output "Repository hygiene diff gate passed against $BaseRevision."
}
finally {
    Pop-Location
}
