[CmdletBinding()]
param(
    [string]$BaseRevision = ""
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

function Test-GitCommit {
    param([Parameter(Mandatory = $true)][string]$Revision)

    & git cat-file -e "$Revision^{commit}" 2>$null
    return $LASTEXITCODE -eq 0
}

$repoRoot = (& git rev-parse --show-toplevel).Trim()
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($repoRoot)) {
    throw "Run this script from inside the repository."
}

$migrationRoot = "backend/src/main/resources/db/migration"

Push-Location $repoRoot
try {
    $migrationFiles = @(Get-ChildItem -LiteralPath $migrationRoot -File -Filter "*.sql")
    if ($migrationFiles.Count -eq 0) {
        throw "No Flyway migrations found in $migrationRoot."
    }

    $invalidNames = [System.Collections.Generic.List[string]]::new()
    $versions = @{}
    foreach ($file in $migrationFiles) {
        if ($file.Name -notmatch '^V(?<Version>.+?)__(?<Description>.+)\.sql$') {
            $invalidNames.Add($file.Name)
            continue
        }

        $canonicalVersion = $Matches.Version.Replace('_', '.')
        if (-not $versions.ContainsKey($canonicalVersion)) {
            $versions[$canonicalVersion] = [System.Collections.Generic.List[string]]::new()
        }
        $versions[$canonicalVersion].Add($file.Name)
    }

    if ($invalidNames.Count -gt 0) {
        throw "Invalid versioned Flyway migration names: $($invalidNames -join ', ')"
    }

    $duplicates = @($versions.GetEnumerator() | Where-Object { $_.Value.Count -gt 1 })
    if ($duplicates.Count -gt 0) {
        $details = @($duplicates | ForEach-Object { "$($_.Key): $($_.Value -join ', ')" })
        throw "Duplicate Flyway versions detected: $($details -join '; ')"
    }

    Write-Output "Flyway naming/version uniqueness passed for $($migrationFiles.Count) migrations."

    if ([string]::IsNullOrWhiteSpace($BaseRevision)) {
        Write-Warning "No base revision was supplied; append-only diff enforcement was skipped."
        return
    }

    if (-not (Test-GitCommit -Revision $BaseRevision)) {
        Write-Warning "Base revision '$BaseRevision' is unavailable; append-only diff enforcement was skipped."
        return
    }

    $changes = @(& git diff --name-status --find-renames $BaseRevision HEAD -- $migrationRoot)
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to compare Flyway migrations with $BaseRevision."
    }

    $forbiddenChanges = [System.Collections.Generic.List[string]]::new()
    foreach ($change in $changes) {
        $columns = @($change -split "`t")
        if ($columns.Count -lt 2) {
            continue
        }

        $status = $columns[0]
        if ($status -notmatch '^A') {
            $forbiddenChanges.Add($change)
        }
    }

    if ($forbiddenChanges.Count -gt 0) {
        throw "Published Flyway migrations are append-only. Modified, deleted, copied or renamed files:`n$($forbiddenChanges -join "`n")"
    }

    Write-Output "Flyway append-only diff gate passed against $BaseRevision."
}
finally {
    Pop-Location
}
