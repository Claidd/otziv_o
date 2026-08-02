[CmdletBinding()]
param(
    [ValidateSet("staged", "range")]
    [string]$Mode = "staged",

    [string]$BaseRevision = "",

    [string]$TargetRevision = "HEAD",

    [AllowEmptyString()]
    [string]$ExcludeRemote = "",

    [ValidateRange(1, 10240)]
    [int]$MaxBlobMiB = 95
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

function Get-OversizedBlobs {
    param(
        [Parameter(Mandatory = $true)][AllowEmptyCollection()][string[]]$ObjectLines,
        [Parameter(Mandatory = $true)][long]$MaxBytes
    )

    if ($ObjectLines.Count -eq 0) {
        return @()
    }

    $format = "--batch-check=%(objecttype) %(objectname) %(objectsize) %(rest)"
    $metadata = @($ObjectLines | & git cat-file $format)
    if ($LASTEXITCODE -ne 0) {
        throw "git cat-file --batch-check failed with exit code $LASTEXITCODE"
    }

    $violations = [System.Collections.Generic.List[object]]::new()
    foreach ($line in $metadata) {
        if ($line -notmatch '^(?<type>\S+) (?<oid>[0-9a-f]+) (?<size>\d+)(?: (?<path>.*))?$') {
            throw "Unexpected git cat-file output: $line"
        }

        [long]$size = $Matches.size
        if ($Matches.type -ne "blob" -or $size -le $MaxBytes) {
            continue
        }

        $path = $Matches.path
        if ([string]::IsNullOrWhiteSpace($path)) {
            $path = "<path unavailable>"
        }

        $violations.Add([pscustomobject]@{
            ObjectId = $Matches.oid
            Path = $path
            Size = $size
        })
    }

    return @($violations)
}

function Get-StagedBlobLines {
    $changes = @(Invoke-GitLines -Arguments @(
        "-c",
        "core.quotepath=false",
        "diff",
        "--cached",
        "--raw",
        "--no-abbrev",
        "--diff-filter=ACMRT",
        "--no-renames",
        "--"
    ))

    $objects = [System.Collections.Generic.List[string]]::new()
    foreach ($change in $changes) {
        if ($change -notmatch '^:\d+ \d+ [0-9a-f]+ (?<oid>[0-9a-f]+) [ACMRT]\d*\t(?<path>.*)$') {
            continue
        }

        if ($Matches.oid -match '^0+$') {
            continue
        }

        $objects.Add("$($Matches.oid) $($Matches.path)")
    }

    return @($objects)
}

function Get-RangeBlobLines {
    param(
        [Parameter(Mandatory = $true)][AllowEmptyString()][string]$Base,
        [Parameter(Mandatory = $true)][string]$Target,
        [Parameter(Mandatory = $true)][AllowEmptyString()][string]$ExcludeRemoteName
    )

    if (-not (Test-GitCommit -Revision $Target)) {
        throw "Target revision '$Target' is not an available commit."
    }

    $revisionArguments = [System.Collections.Generic.List[string]]::new()
    $allZeros = $Base -match '^0+$'
    if (-not [string]::IsNullOrWhiteSpace($Base) -and -not $allZeros) {
        if (Test-GitCommit -Revision $Base) {
            $revisionArguments.Add("$Base..$Target")
        }
        else {
            Write-Warning "Base revision '$Base' is unavailable; excluding commits already reachable from remote '$ExcludeRemoteName'."
        }
    }

    if ($revisionArguments.Count -eq 0) {
        $revisionArguments.Add($Target)
        if (-not [string]::IsNullOrWhiteSpace($ExcludeRemoteName)) {
            $revisionArguments.Add("--not")
            $revisionArguments.Add("--remotes=$ExcludeRemoteName")
        }
    }

    $gitArguments = [System.Collections.Generic.List[string]]::new()
    foreach ($argument in @(
        "-c",
        "core.quotepath=false",
        "log",
        "--format=",
        "--raw",
        "--no-abbrev",
        "--diff-filter=ACMRT",
        "--no-renames",
        "--root",
        "-m"
    )) {
        $gitArguments.Add($argument)
    }
    foreach ($argument in $revisionArguments) {
        $gitArguments.Add($argument)
    }
    $gitArguments.Add("--")

    $changes = @(Invoke-GitLines -Arguments @($gitArguments))
    $objects = [System.Collections.Generic.List[string]]::new()
    foreach ($change in $changes) {
        if ($change -notmatch '^:\d+ \d+ [0-9a-f]+ (?<oid>[0-9a-f]+) [ACMRT]\d*\t(?<path>.*)$') {
            continue
        }

        if ($Matches.oid -match '^0+$') {
            continue
        }

        $objects.Add("$($Matches.oid) $($Matches.path)")
    }

    return @($objects | Sort-Object -Unique)
}

$repoRoot = (& git rev-parse --show-toplevel).Trim()
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($repoRoot)) {
    throw "Run this script from inside the repository."
}

[long]$maxBytes = [long]$MaxBlobMiB * 1MB

Push-Location $repoRoot
try {
    if ($Mode -eq "staged") {
        $objectLines = @(Get-StagedBlobLines)
        $scope = "staged changes"
    }
    else {
        $objectLines = @(Get-RangeBlobLines -Base $BaseRevision -Target $TargetRevision -ExcludeRemoteName $ExcludeRemote)
        $scope = "Git range ending at $TargetRevision"
    }

    $violations = @(Get-OversizedBlobs -ObjectLines $objectLines -MaxBytes $maxBytes)
    if ($violations.Count -gt 0) {
        $details = @($violations |
            Sort-Object Path, ObjectId -Unique |
            ForEach-Object {
                $shortObjectId = $_.ObjectId.Substring(0, [Math]::Min(12, $_.ObjectId.Length))
                " - $($_.Path) ($([Math]::Round($_.Size / 1MB, 2)) MiB, blob $shortObjectId)"
            })
        throw "Git blobs larger than $MaxBlobMiB MiB are not allowed in ${scope}:`n$($details -join "`n")"
    }

    Write-Output "Large Git blob gate passed for $scope (limit: $MaxBlobMiB MiB)."
}
finally {
    Pop-Location
}
