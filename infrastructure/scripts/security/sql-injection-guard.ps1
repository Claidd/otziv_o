[CmdletBinding()]
param(
    [ValidateSet("dir", "staged")]
    [string]$Mode = "dir"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = Resolve-Path (Join-Path $scriptRoot "..\..\..")
$sourceRoot = Join-Path $repoRoot "backend\src\main\java"

$rules = @(
    @{
        Id = "SQL001"
        Pattern = '\bcreateNativeQuery\s*\('
        Message = "Native JPA query needs explicit SQLi review; prefer JPQL/repositories or bind all parameters."
    },
    @{
        Id = "SQL002"
        Pattern = '\bcreateStatement\s*\('
        Message = "Raw JDBC Statement is blocked; use PreparedStatement or NamedParameterJdbcTemplate."
    },
    @{
        Id = "SQL003"
        Pattern = '(?i)"[^"]*\b(SELECT|INSERT|UPDATE|DELETE|FROM|WHERE|ORDER\s+BY|GROUP\s+BY|HAVING)\b[^"]*"\s*\+\s*(?!["A-Z0-9_]+\b)[A-Za-z_][A-Za-z0-9_]*(?:\s*\(|\b)'
        Message = "SQL/JPQL string concatenates a non-constant fragment; verify whitelist/identifier validation or use parameters."
    },
    @{
        Id = "SQL004"
        Pattern = '(?i)"[^"]*\bORDER\s+BY\b[^"]*"\s*\+'
        Message = "Dynamic ORDER BY must use a whitelist, not raw request input."
    }
)

$allowList = @(
    @{
        Path = 'backend/src/main/java/com/hunt/otziv/archive/repository/ManagerArchiveRepository.java'
        Pattern = 'ORDER BY sort_at.*\+ direction'
        Reason = "sortDirection is normalized by orderDirection() to ASC/DESC only."
    },
    @{
        Path = 'backend/src/main/java/com/hunt/otziv/archive/repository/OrderArchiveRestoreRepository.java'
        Pattern = 'INSERT INTO|SELECT '
        Reason = "Dynamic table/column fragments are internal and identifiers are validated with safeIdentifier()."
    },
    @{
        Path = 'backend/src/main/java/com/hunt/otziv/archive/repository/OrderArchiveDryRunRepository.java'
        Pattern = 'INSERT IGNORE INTO|SELECT '
        Reason = "Dynamic archive table/column fragments are internal and identifiers are validated with safeIdentifier()."
    },
    @{
        Path = 'backend/src/main/java/com/hunt/otziv/client_messages/service/PublicationHealthMonitorService.java'
        Pattern = 'SELECT COUNT\(\*\).*fromGroupedOrders|HAVING'
        Reason = "HAVING fragments are selected from internal issue definitions; values are bound parameters."
    },
    @{
        Path = 'backend/src/main/java/com/hunt/otziv/r_review/board/service/ReviewBoardQueryService.java'
        Pattern = 'SELECT .* FROM Review r|ORDER BY r\.publishedDate'
        Reason = "JPQL clauses are built from internal predicates; user values are bound via setParameter()."
    }
)

# SQL004 review of the frozen offline maintenance implementation only:
# kind is validated before selecting literal offer_id/assignment_id and their
# literal tables; predicates come only from internal literals/schema booleans.
# Cursor, upper bound and limit remain JDBC parameters. No caller SQL fragment
# reaches either expression. Any source change requires a fresh review: the
# digest is UTF-8 of all scanned lines joined with LF (without a final LF).
# In staged mode both the lines AND this digest come from git's index.
$reviewedSql004 = @{
    Path = 'backend/src/main/java/com/hunt/otziv/performers/maintenance/PerformerLegacyMaintenance.java'
    SourceSha256 = '616900f0e6f31a0f5dc900e4cfa03d57f46f89545a24ff8cbb1760d56070e399'
    Lines = @(
        'var rows=jdbc.query("SELECT "+pk+",("+predicate+") FROM "+table+" WHERE "+pk+">? AND "+pk+"<=? ORDER BY "+pk+" LIMIT ?",',
        'var ids=jdbc.queryForList("SELECT "+pk+" FROM "+table+" WHERE "+pk+">? AND "+pk+"<=? ORDER BY "+pk+" LIMIT ?",Long.class,after,upper,limit);'
    )
}

# A08 archive journal ordering: the repository accepts Sort.Direction and maps
# it locally to the two literal strings ASC/DESC; request text never reaches
# SQL syntax. filterWhereClause() contains fixed predicates and binds values.
# Pin the full source as well as this exact SQL004 line: changing the enum
# mapping or another predicate requires fresh review, including staged mode.
$reviewedArchiveOrder = @{
    Path = 'backend/src/main/java/com/hunt/otziv/payments/repository/PaymentLinkArchiveRepository.java'
    SourceSha256 = '26b2d85fef4b837c40046b6aad3ad0e797dfb13a4ed445a6cf6b7cd9315ecc60'
    Lines = @(
        '""" + filterWhereClause() + " ORDER BY apl.created_at " + order + ", apl.id " + order + """'
    )
}

function Get-ScannedSourceSha256 {
    param([string[]]$Lines)
    $digest = [System.Security.Cryptography.SHA256]::Create()
    try {
        $bytes = [System.Text.Encoding]::UTF8.GetBytes(($Lines -join "`n"))
        return [BitConverter]::ToString($digest.ComputeHash($bytes)).Replace('-', '').ToLowerInvariant()
    } finally {
        $digest.Dispose()
    }
}

function Get-RelativePath {
    param([string]$Path)
    $root = $repoRoot.ProviderPath.TrimEnd("\", "/")
    $fullPath = (Resolve-Path -LiteralPath $Path).ProviderPath
    if ($fullPath.StartsWith($root, [System.StringComparison]::OrdinalIgnoreCase)) {
        return $fullPath.Substring($root.Length).TrimStart("\", "/").Replace("\", "/")
    }
    return $fullPath.Replace("\", "/")
}

function Get-ScanTargets {
    if ($Mode -eq "staged") {
        $files = @(
            git -C $repoRoot diff --cached --name-only --diff-filter=ACMRT -- "backend/src/main/java" |
                    Where-Object { $_ -match '\.java$' }
        )
        return @(
            $files | ForEach-Object {
                [pscustomobject]@{
                    RelativePath = $_.Replace("\", "/")
                    Lines = @(git -C $repoRoot show ":$_")
                }
            }
        )
    }

    if (-not (Test-Path $sourceRoot)) {
        return @()
    }
    return @(
        Get-ChildItem -Path $sourceRoot -Recurse -File -Filter "*.java" | ForEach-Object {
            [pscustomobject]@{
                RelativePath = Get-RelativePath -Path $_.FullName
                Lines = @(Get-Content -LiteralPath $_.FullName)
            }
        }
    )
}

function Test-AllowListed {
    param(
        [string]$RelativePath,
        [string]$Line,
        [string]$RuleId,
        [string]$SourceSha256
    )

    if ($RuleId -ceq 'SQL004' -and
            $RelativePath -ceq $reviewedSql004.Path -and
            $SourceSha256 -ceq $reviewedSql004.SourceSha256 -and
            $reviewedSql004.Lines -ccontains $Line.Trim()) {
        return $true
    }
    if ($RuleId -ceq 'SQL004' -and
            $RelativePath -ceq $reviewedArchiveOrder.Path -and
            $SourceSha256 -ceq $reviewedArchiveOrder.SourceSha256 -and
            $reviewedArchiveOrder.Lines -ccontains $Line.Trim()) {
        return $true
    }
    foreach ($allow in $allowList) {
        if ($RelativePath -eq $allow.Path -and $Line -match $allow.Pattern) {
            return $true
        }
    }
    return $false
}

$findings = New-Object System.Collections.Generic.List[object]
$targets = @(Get-ScanTargets)

foreach ($target in $targets) {
    $relativePath = $target.RelativePath
    $lines = $target.Lines
    $sourceSha256 = if ($relativePath -ceq $reviewedSql004.Path -or $relativePath -ceq $reviewedArchiveOrder.Path) {
        Get-ScannedSourceSha256 -Lines $lines
    } else { '' }

    for ($index = 0; $index -lt $lines.Count; $index++) {
        $line = $lines[$index]
        if ($line -match 'sql-guard:\s*allow') {
            continue
        }

        foreach ($rule in $rules) {
            if ($line -match $rule.Pattern -and -not (Test-AllowListed -RelativePath $relativePath -Line $line -RuleId $rule.Id -SourceSha256 $sourceSha256)) {
                $findings.Add([pscustomobject]@{
                    Rule = $rule.Id
                    Path = $relativePath
                    Line = $index + 1
                    Message = $rule.Message
                    Code = $line.Trim()
                })
            }
        }
    }
}

if ($findings.Count -eq 0) {
    Write-Host "SQL injection guard passed for $($targets.Count) Java files." -ForegroundColor Green
    exit 0
}

Write-Host "SQL injection guard found risky query construction:" -ForegroundColor Red
foreach ($finding in $findings) {
    Write-Host "$($finding.Rule) $($finding.Path):$($finding.Line)" -ForegroundColor Red
    Write-Host "  $($finding.Message)" -ForegroundColor Yellow
    Write-Host "  $($finding.Code)"
}
Write-Host ""
Write-Host "Fix by binding parameters, using Spring Data/Pageable Sort, or mapping request values through a strict whitelist." -ForegroundColor Yellow
Write-Host "For reviewed internal-only dynamic SQL, add a focused allow-list entry in this script or a 'sql-guard: allow' comment." -ForegroundColor Yellow
exit 1
