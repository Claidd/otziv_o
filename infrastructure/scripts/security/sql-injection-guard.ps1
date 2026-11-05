[CmdletBinding()]
param(
    [ValidateSet("dir", "staged", "selftest")]
    [string]$Mode = "dir"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = Resolve-Path (Join-Path $scriptRoot "..\..\..")
$sourceRoot = Join-Path $repoRoot "backend\src\main\java"

# Every rule is matched case-SENSITIVELY ([regex]::Match default). A global (?i)
# once turned the UPPER_CASE-constant exclusion into "any identifier", so SQL003
# never fired. Only SQL keywords are case-insensitive, and then only in
# multi-word SQL shapes, so prose such as "failed to update " + id stays quiet.
$sqlKeyword = '(?:\b(?:SELECT|INSERT|UPDATE|DELETE|FROM|WHERE|ORDER\s+BY|GROUP\s+BY|HAVING)\b|(?i:\bselect\b[^"]*?\bfrom\b|\binsert\s+(?:ignore\s+)?into\b|\bdelete\s+from\b|\bupdate\s+[\w.`]+\s+set\b|\bwhere\s+[\w.`]+\s*(?:[=<>!]|\b(?:in|like|is|between)\b)|\border\s+by\b|\bgroup\s+by\b))'
# A string literal or an UPPER_CASE constant (optionally Type.QUALIFIED) that is
# not called or dereferenced further. Anything else is a dynamic fragment.
$constantOperand = '(?:"|(?:[A-Z][A-Za-z0-9_]*\.)*[A-Z][A-Z0-9_]*\b(?!\s*[.(\[]))'
$dynamicOperand = '(?!' + $constantOperand + ')[A-Za-z_]'
$constantArgument = '^(?:"(?:[^"\\]|\\.)*"|(?:[A-Z][A-Za-z0-9_]*\.)*[A-Z][A-Z0-9_]*|-?\d+[lL]?)$'
$sqlLiteral = '"[^"]*' + $sqlKeyword + '[^"]*"'
$sqlFormatLiteral = '"(?=[^"]*' + $sqlKeyword + ')(?=[^"]*%(?:\d+\$)?s)[^"]*"'
$formatSpecifier = '%(?:\d+\$)?s'

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
        Pattern = $sqlLiteral + '\s*\+\s*' + $dynamicOperand
        Message = "SQL/JPQL string concatenates a non-constant fragment; verify whitelist/identifier validation or use parameters."
    },
    @{
        Id = "SQL004"
        Pattern = '"[^"]*\b(?i:ORDER\s+BY)\b[^"]*"\s*\+'
        Message = "Dynamic ORDER BY must use a whitelist, not raw request input."
    },
    @{
        Id = "SQL005"
        Pattern = '\bString\.format\s*\(\s*(?:[\w.]+\s*,\s*)?' + $sqlFormatLiteral
        Format = "call"
        Message = "SQL/JPQL built with String.format/formatted or a concatenated text block; bind values or validate identifiers."
    },
    @{
        Id = "SQL005"
        Pattern = $sqlFormatLiteral + '\s*\.formatted\s*\('
        Format = "formatted"
        Message = "SQL/JPQL built with String.format/formatted or a concatenated text block; bind values or validate identifiers."
    }
)
$textBlockMessage = "SQL/JPQL built with String.format/formatted or a concatenated text block; bind values or validate identifiers."
$ruleMessages = @{}
foreach ($rule in $rules) { $ruleMessages[$rule.Id] = $rule.Message }

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
        Pattern = 'INSERT IGNORE INTO|SELECT |^\s*"""\s*\+\s*(?:archiveColumns|selectColumns)\s*\+\s*"""\s*$|^\s*(?:AND NOT|OR) """\s*\+\s*retainedOperationalEvidence\("[\w.]+"\)\s*\+\s*"""\s*$|^\s*"""\.formatted\(orderId, orderId, orderId\);\s*$'
        Reason = "Dynamic archive table/column fragments are internal and identifiers are validated with safeIdentifier(); retainedOperationalEvidence() only receives literal column references."
    },
    @{
        Path = 'backend/src/main/java/com/hunt/otziv/archive/repository/ManagerArchiveRepository.java'
        Pattern = '^\s*"""\s*\+\s*(?:archiveOrderIdsSelect|archiveOrderPageKeysSelect|archiveOrdersSelectFromPage|archiveOrdersFromWhere|liveClosedOrdersFromWhere|archiveVisibleKeywordTokensFilter|liveVisibleKeywordTokensFilter|scopeFilter|liveScopeFilter|managerFilter|orderBy)\b'
        Reason = "Text-block fragments are fixed predicates (scope/mode switch literals, ASC/DESC) and keyword placeholders; keyword values are bound parameters."
    },
    @{
        Path = 'backend/src/main/java/com/hunt/otziv/client_messages/service/PublicationHealthMonitorService.java'
        Pattern = 'SELECT COUNT\(\*\).*fromGroupedOrders|HAVING'
        Reason = "HAVING fragments are selected from internal issue definitions; values are bound parameters."
    },
    @{
        Path = 'backend/src/main/java/com/hunt/otziv/r_review/board/service/ReviewBoardQueryService.java'
        Pattern = 'SELECT .* FROM Review r|ORDER BY r\.publishedDate|" WHERE " \+ String\.join\(" AND ", conditions\)'
        Reason = "JPQL clauses are built from internal predicates; user values are bound via setParameter()."
    },
    @{
        Path = 'backend/src/main/java/com/hunt/otziv/common_billing/repository/CommonInvoiceArchiveRepository.java'
        Pattern = 'INSERT INTO|"SELECT " \+ selectList\(|"SELECT 1 FROM " \+ itemTable|" WHERE scoped_item\.invoice_id = " \+ invoiceAlias|""" \+ scopeFilter\(scope, "account", "invoice_company", "a?ci", "(?:archive_)?orders"\) \+ """|^\s*"""\s*\+\s*(?:liveSelect|archiveSelect)\(scope, (?:true|false)\)\s*\+\s*"""\s*$|^\s*"""\s*\+\s*columns\s*\+\s*"\\n"\s*\+\s*"""\s*$|^\s*ORDER BY COALESCE\(archived_at, closed_at\) """\s*\+\s*direction\s*$'
        Reason = "Columns come from INFORMATION_SCHEMA through safeIdentifier() or literal column lists; tables, aliases, scope predicates and ASC/DESC are literals; manager ids are bound."
    },
    @{
        Path = 'backend/src/main/java/com/hunt/otziv/contractor_payments/repository/ContractorLegacyRewardReconciliationRepository.java'
        Pattern = '^\s*"""\s*\+\s*orderFilter\s*\+\s*"""\s*$'
        Reason = "orderFilter is empty or a fixed predicate with the bound :orderId parameter."
    },
    @{
        Path = 'backend/src/main/java/com/hunt/otziv/integration/outbox/repository/IntegrationOutboxRepository.java'
        Pattern = '^\s*"""\.formatted\(predicate\), Map\.of\("sampleLimit", countCap \+ 1\), Long\.class\);\s*$'
        Reason = "countCapped() is private and only receives literal status predicates from this class."
    },
    @{
        Path = 'backend/src/main/java/com/hunt/otziv/paper_billing/BillingPayerService.java'
        Pattern = 'WHERE c\.deleted_at IS NULL AND c\.company_id IN \("\s*$'
        Reason = "The continuation joins only '?' placeholders; company ids are bound."
    },
    @{
        Path = 'backend/src/main/java/com/hunt/otziv/payments/service/PaymentIssueResolutionEvidenceReader.java'
        Pattern = '^\s*"""\.formatted\((?:table, activeArchiveOnly|table|liveOnlyMarkers, table)\),'
        Reason = "table is one of the literal live/archive table names passed inside this class; marker fragments are literals."
    },
    @{
        Path = 'backend/src/main/java/com/hunt/otziv/specialist_transfer/service/SpecialistTransferService.java'
        Pattern = '^\s*"""\s*\+\s*companyFilter\s*\+\s*"""\s*$'
        Reason = "companyFilter is empty or a fixed predicate with bound :requestedCompanyIds."
    },
    @{
        Path = 'backend/src/main/java/com/hunt/otziv/manager_daily_summary/service/ManagerDailySummaryService.java'
        Pattern = '"UPDATE " \+ table \+ " SET reply_histogram = \? WHERE " \+ periodColumn \+ " = \? AND manager_id = \?"'
        Reason = "table/periodColumn are passed only as literals by the monthly and yearly rollups; values are bound."
    },
    @{
        Path = 'backend/src/main/java/com/hunt/otziv/paper_billing/PaperBillingService.java'
        Pattern = 'target_id IN \("\+marks\+"\)\) OR parent_invoice_id IN \("\+marks\+"\)'
        Reason = "marks is a comma-joined list of '?' placeholders; ids are bound."
    },
    @{
        Path = 'backend/src/main/java/com/hunt/otziv/paper_billing/PaperBillingStore.java'
        Pattern = '"\+(?:columns|prefix|column|receipt)\+"'
        Reason = "Column names are selected from string literals inside this class; values are bound."
    },
    @{
        Path = 'backend/src/main/java/com/hunt/otziv/u_users/api/TeamDirectoryReader.java'
        Pattern = '"\s*\+\s*role\.(?:id|table)\s*\+'
        Reason = "Identifiers come exclusively from the TeamDirectoryReader.Role enum."
    },
    @{
        Path = 'backend/src/main/java/com/hunt/otziv/security/credentials/CredentialEncryptionBackfill.java'
        Pattern = '"\s*\+\s*column\.(?:idColumn|column|table)\(\)'
        Reason = "Identifiers come only from the static CREDENTIAL_COLUMNS list."
    }
)

# SQL004 review of the frozen offline maintenance implementation only:
# kind is validated before selecting literal offer_id/assignment_id and their
# literal tables; predicates come only from internal literals/schema booleans.
# Cursor, upper bound and limit remain JDBC parameters. No caller SQL fragment
# reaches either expression. Any source change requires a fresh review: the
# digest is UTF-8 of all scanned lines joined with LF (without a final LF).
# In staged mode both the lines AND this digest come from git's index.
# The same review covers SQL003 for the listed lines once SQL003 started to
# fire: every concatenated fragment (pk, table, predicate, generation) is one
# of the literals described above.
$reviewedSql004 = @{
    Path = 'backend/src/main/java/com/hunt/otziv/performers/maintenance/PerformerLegacyMaintenance.java'
    SourceSha256 = '616900f0e6f31a0f5dc900e4cfa03d57f46f89545a24ff8cbb1760d56070e399'
    Lines = @(
        'var rows=jdbc.query("SELECT "+pk+",("+predicate+") FROM "+table+" WHERE "+pk+">? AND "+pk+"<=? ORDER BY "+pk+" LIMIT ?",',
        'var ids=jdbc.queryForList("SELECT "+pk+" FROM "+table+" WHERE "+pk+">? AND "+pk+"<=? ORDER BY "+pk+" LIMIT ?",Long.class,after,upper,limit);'
    )
    Sql003Lines = @(
        'var rows=jdbc.query("SELECT "+pk+",("+predicate+") FROM "+table+" WHERE "+pk+">? AND "+pk+"<=? ORDER BY "+pk+" LIMIT ?",',
        'var ids=jdbc.queryForList("SELECT "+pk+" FROM "+table+" WHERE "+pk+">? AND "+pk+"<=? ORDER BY "+pk+" LIMIT ?",Long.class,after,upper,limit);',
        'long upper=through==null?jdbc.queryForObject("SELECT COALESCE(MAX("+pk+"),0) FROM "+table,Long.class):through;',
        'boolean done=jdbc.queryForObject("SELECT COUNT(*)=0 FROM (SELECT "+pk+" FROM "+table+" WHERE "+pk+">? AND "+pk+"<=? LIMIT 1) remaining",Boolean.class,next,upper);',
        'boolean complete=jdbc.queryForObject("SELECT COUNT(*)=0 FROM (SELECT "+pk+" FROM "+table+" WHERE "+pk+">? AND "+pk+"<=? LIMIT 1) remaining",Boolean.class,next,upper);',
        'var rows = jdbc.query("SELECT assignment_id,status,"+generation+" FROM review_performer_assignments WHERE assignment_id>? AND assignment_id<=? ORDER BY assignment_id LIMIT ? FOR UPDATE",'
    )
    Sql005Lines = @()
}

# A08 archive journal ordering: the repository accepts Sort.Direction and maps
# it locally to the two literal strings ASC/DESC; request text never reaches
# SQL syntax. filterWhereClause() contains fixed predicates and binds values.
# Pin the full source as well as this exact SQL004 line: changing the enum
# mapping or another predicate requires fresh review, including staged mode.
# Paper-invoice audit: only the fixed archive eligibility predicate changed;
# the two-literal enum mapping and bound filter values were rechecked.
# The same review covers the SQL003 concatenation and the SQL005 text-block
# joins of filterWhereClause() on the listed lines, and the two blocker-SQL
# .formatted() calls whose arguments are this class's static blocker constants.
$reviewedArchiveOrder = @{
    Path = 'backend/src/main/java/com/hunt/otziv/payments/repository/PaymentLinkArchiveRepository.java'
    SourceSha256 = 'c4104780308f2fc9b052fef37692b9fb204e91e44df43834c4e3ade3305fb42c'
    Lines = @(
        '""" + filterWhereClause() + " ORDER BY apl.created_at " + order + ", apl.id " + order + """'
    )
    Sql003Lines = @(
        '""" + filterWhereClause() + " ORDER BY apl.created_at " + order + ", apl.id " + order + """'
    )
    Sql005Lines = @(
        '""" + filterWhereClause() + " ORDER BY apl.created_at " + order + ", apl.id " + order + """',
        '""" + filterWhereClause(), params, (rs, rowNum) -> new PaymentLinkAdminSummary(',
        '""".formatted(blockerSql);',
        '""".formatted('
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
                # Explicit UTF-8 keeps Windows PowerShell 5.1 and pwsh digests identical.
                Lines = @(Get-Content -LiteralPath $_.FullName -Encoding UTF8)
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

    foreach ($review in @($reviewedSql004, $reviewedArchiveOrder)) {
        if ($RelativePath -cne $review.Path -or $SourceSha256 -cne $review.SourceSha256) {
            continue
        }
        $reviewedLines = @()
        if ($RuleId -ceq 'SQL003') { $reviewedLines = @($review.Sql003Lines) }
        elseif ($RuleId -ceq 'SQL004') { $reviewedLines = @($review.Lines) }
        elseif ($RuleId -ceq 'SQL005') { $reviewedLines = @($review.Sql005Lines) }
        if ($reviewedLines -ccontains $Line.Trim()) {
            return $true
        }
    }
    foreach ($allow in $allowList) {
        if ($RelativePath -eq $allow.Path -and $Line -match $allow.Pattern) {
            return $true
        }
    }
    return $false
}

function Get-FollowingText {
    param([string[]]$Lines, [int]$Index, [string]$Head)
    $last = [Math]::Min($Lines.Count - 1, $Index + 30)
    $parts = @($Head)
    for ($next = $Index + 1; $next -le $last; $next++) { $parts += $Lines[$next] }
    return ($parts -join "`n")
}

# True when every argument up to the closing parenthesis is a literal or an
# UPPER_CASE constant. $Text starts right after the opening parenthesis.
function Test-ConstantArguments {
    param([string]$Text)
    $arguments = New-Object System.Collections.Generic.List[string]
    $current = New-Object System.Text.StringBuilder
    $depth = 0
    $inString = $false
    for ($position = 0; $position -lt $Text.Length; $position++) {
        $character = $Text[$position]
        if ($inString) {
            [void]$current.Append($character)
            if ($character -eq '\' -and $position + 1 -lt $Text.Length) {
                $position++
                [void]$current.Append($Text[$position])
            } elseif ($character -eq '"') {
                $inString = $false
            }
            continue
        }
        if ($character -eq '"') {
            $inString = $true
            [void]$current.Append($character)
        } elseif ($character -eq '(') {
            $depth++
            [void]$current.Append($character)
        } elseif ($character -eq ')') {
            if ($depth -eq 0) {
                $arguments.Add($current.ToString())
                foreach ($argument in $arguments) {
                    $trimmed = $argument.Trim()
                    if ($trimmed.Length -eq 0 -and $arguments.Count -eq 1) { continue }
                    if ($trimmed -cnotmatch $constantArgument) { return $false }
                }
                return $true
            }
            $depth--
            [void]$current.Append($character)
        } elseif ($character -eq ',' -and $depth -eq 0) {
            $arguments.Add($current.ToString())
            [void]$current.Clear()
        } else {
            [void]$current.Append($character)
        }
    }
    return $false
}

# $Rest starts right after a format string literal inside String.format(...).
function Test-ConstantFormatCallArguments {
    param([string]$Rest)
    if ($Rest -cmatch '^\s*\)') { return $true }
    $comma = [regex]::Match($Rest, '^\s*,')
    if (-not $comma.Success) { return $false }
    return (Test-ConstantArguments -Text $Rest.Substring($comma.Length))
}

function Add-Finding {
    param($Findings, $Seen, [string]$RuleId, [int]$Index, [string]$Message)
    $key = "$RuleId|$Index"
    if ($Seen.ContainsKey($key)) { return }
    $Seen[$key] = $true
    $Findings.Add([pscustomobject]@{ Rule = $RuleId; Index = $Index; Message = $Message })
}

function Test-TextBlock {
    param($Findings, $Seen, [string[]]$Lines, [string]$Prefix, [string]$Content, [string]$Suffix, [int]$StartIndex, [int]$CloseIndex)
    if (-not [regex]::IsMatch($Content, $sqlKeyword)) { return }
    $hasFormatSpecifier = [regex]::IsMatch($Content, $formatSpecifier)

    # """ + dynamicFragment
    if ([regex]::IsMatch($Suffix, '^\s*\+\s*' + $dynamicOperand)) {
        Add-Finding $Findings $Seen 'SQL005' $CloseIndex $textBlockMessage
    }
    # """.formatted(dynamicArgument)
    $formatted = [regex]::Match($Suffix, '^\s*\.formatted\s*\(')
    if ($formatted.Success -and $hasFormatSpecifier) {
        $rest = Get-FollowingText -Lines $Lines -Index $CloseIndex -Head $Suffix.Substring($formatted.Length)
        if (-not (Test-ConstantArguments -Text $rest)) {
            Add-Finding $Findings $Seen 'SQL005' $CloseIndex $textBlockMessage
        }
    }
    # dynamicFragment + """
    $operand = [regex]::Match($Prefix, '("(?:[^"\\]|\\.)*"|[A-Za-z_][\w.]*(?:\s*\([^()]*\))?|\))\s*\+\s*$')
    if ($operand.Success) {
        if ($operand.Groups[1].Value -cnotmatch $constantArgument) {
            Add-Finding $Findings $Seen 'SQL005' $StartIndex $textBlockMessage
        }
    } elseif ($Prefix -cmatch '\+\s*$') {
        Add-Finding $Findings $Seen 'SQL005' $StartIndex $textBlockMessage
    }
    # String.format("""...""", dynamicArgument)
    if ($hasFormatSpecifier -and $Prefix -cmatch '\bString\.format\s*\(\s*(?:[\w.]+\s*,\s*)?$') {
        $rest = Get-FollowingText -Lines $Lines -Index $CloseIndex -Head $Suffix
        if (-not (Test-ConstantFormatCallArguments -Rest $rest)) {
            Add-Finding $Findings $Seen 'SQL005' $StartIndex $textBlockMessage
        }
    }
}

# Returns raw findings (before allow-listing) for one Java source.
function Find-SqlRisks {
    param([string[]]$Lines)
    $findings = New-Object System.Collections.Generic.List[object]
    $seen = @{}
    $Lines = @($Lines)

    for ($index = 0; $index -lt $Lines.Count; $index++) {
        $line = [string]$Lines[$index]
        foreach ($rule in $rules) {
            $match = [regex]::Match($line, $rule.Pattern)
            if (-not $match.Success) { continue }
            if ($rule.ContainsKey('Format')) {
                $rest = Get-FollowingText -Lines $Lines -Index $index -Head $line.Substring($match.Index + $match.Length)
                $constant = if ($rule.Format -eq 'call') { Test-ConstantFormatCallArguments -Rest $rest } else { Test-ConstantArguments -Text $rest }
                if ($constant) { continue }
            }
            Add-Finding $findings $seen $rule.Id $index $rule.Message
        }

        # SQL003 split across a line break: "... WHERE a = " <newline> + input
        # or "... WHERE a = " + <newline> input.
        if ($index + 1 -lt $Lines.Count -and $line.IndexOf('"""') -lt 0) {
            $next = [string]$Lines[$index + 1]
            $tail = [regex]::Match($line, $sqlLiteral + '\s*(\+)?\s*$')
            if ($tail.Success -and $next.IndexOf('"""') -lt 0) {
                $nextPattern = if ($tail.Groups[1].Success) { '^\s*' + $dynamicOperand } else { '^\s*\+\s*' + $dynamicOperand }
                if ([regex]::IsMatch($next, $nextPattern)) {
                    Add-Finding $findings $seen 'SQL003' $index $ruleMessages['SQL003']
                }
            }
        }
    }

    # Java text blocks: the opening """ ends its line; the closing """ may be
    # followed by + fragment, .formatted(...) or the String.format arguments.
    $inBlock = $false
    $blockStart = -1
    $blockPrefix = ''
    $content = $null
    for ($index = 0; $index -lt $Lines.Count; $index++) {
        $line = [string]$Lines[$index]
        $position = 0
        while ($true) {
            $delimiter = $line.IndexOf('"""', $position)
            if ($delimiter -lt 0) {
                if ($inBlock) { [void]$content.Append($line.Substring($position)).Append("`n") }
                break
            }
            if (-not $inBlock) {
                if ($line.Substring($delimiter + 3).Trim().Length -ne 0) {
                    $position = $delimiter + 3
                    continue
                }
                $inBlock = $true
                $blockStart = $index
                $blockPrefix = $line.Substring($position, $delimiter - $position)
                $content = New-Object System.Text.StringBuilder
            } else {
                [void]$content.Append($line.Substring($position, $delimiter - $position))
                Test-TextBlock -Findings $findings -Seen $seen -Lines $Lines -Prefix $blockPrefix `
                        -Content $content.ToString() -Suffix $line.Substring($delimiter + 3) `
                        -StartIndex $blockStart -CloseIndex $index
                $inBlock = $false
            }
            $position = $delimiter + 3
        }
    }

    return $findings
}

# Fixtures prove every rule still fires (and stays quiet on safe shapes) before
# any scan result is trusted; a silently dead rule fails the guard.
function Invoke-SelfTest {
    $positive = @(
        @{ Rule = 'SQL001'; Lines = @('Query q = em.createNativeQuery(sql);') },
        @{ Rule = 'SQL002'; Lines = @('Statement s = connection.createStatement();') },
        @{ Rule = 'SQL003'; Lines = @('String q = "SELECT * FROM users WHERE name = ''" + userInput + "''";') },
        @{ Rule = 'SQL003'; Lines = @('em.createQuery("select u from User u where u.name = ''" + name + "''");') },
        @{ Rule = 'SQL003'; Lines = @('jdbc.update("DELETE FROM reviews WHERE id = " + request.getParameter("id"));') },
        @{ Rule = 'SQL003'; Lines = @('String q = "SELECT id FROM t WHERE a = " + String.valueOf(value);') },
        @{ Rule = 'SQL003'; Lines = @('String q = "SELECT id FROM t ORDER BY " + SORT_COLUMNS.get(sort);') },
        @{ Rule = 'SQL003'; Lines = @('String q = "SELECT * FROM t WHERE a = "', '        + userInput;') },
        @{ Rule = 'SQL003'; Lines = @('String q = "SELECT * FROM t WHERE a = " +', '        userInput;') },
        @{ Rule = 'SQL004'; Lines = @('String q = "SELECT id FROM t ORDER BY " + sort;') },
        @{ Rule = 'SQL005'; Lines = @('String q = """', '        SELECT * FROM t', '        WHERE a = """ + userInput;') },
        @{ Rule = 'SQL005'; Lines = @('String q = """', '        SELECT * FROM t WHERE name = ''%s''', '        """.formatted(name);') },
        @{ Rule = 'SQL005'; Lines = @('String q = selectClause + """', '        FROM t WHERE a = 1', '        """;') },
        @{ Rule = 'SQL005'; Lines = @('jdbc.query(String.format("""', '        SELECT * FROM t WHERE a = ''%s''', '        """, input), mapper);') },
        @{ Rule = 'SQL005'; Lines = @('jdbc.query(String.format("SELECT * FROM %s WHERE id = 1", table), mapper);') },
        @{ Rule = 'SQL005'; Lines = @('String q = "SELECT * FROM t WHERE a = ''%s''".formatted(input);') }
    )
    $negative = @(
        @{ Name = 'constant fragment'; Lines = @('String q = "SELECT * FROM t WHERE id = " + MAX_ROWS;') },
        @{ Name = 'qualified constant'; Lines = @('String q = "SELECT * FROM " + Tables.ORDERS;') },
        @{ Name = 'literal concatenation'; Lines = @('String q = "SELECT * FROM t " + "WHERE id = ?";') },
        @{ Name = 'numeric fragment'; Lines = @('String q = "SELECT id FROM t WHERE a = ? LIMIT " + 10;') },
        @{ Name = 'split literal concatenation'; Lines = @('String q = "SELECT * FROM t "', '        + "WHERE id = ?";') },
        @{ Name = 'update prose'; Lines = @('log.warn("Failed to update Keycloak user: " + username);') },
        @{ Name = 'from prose'; Lines = @('throw new IllegalStateException("Analytics still differs from canonical sources for month " + month);') },
        @{ Name = 'delete prose'; Lines = @('log.warn("Archive delete verification failed: selected=" + selected);') },
        @{ Name = 'bound JPQL'; Lines = @('@Query("select l from Lead l where l.id = :id")') },
        @{ Name = 'static text block'; Lines = @('String q = """', '        SELECT * FROM t WHERE id = :id', '        """;') },
        @{ Name = 'constant text block join'; Lines = @('String q = """', '        SELECT * FROM t WHERE id = ?', '        """ + ORDER_CLAUSE;') },
        @{ Name = 'constant formatted text block'; Lines = @('String q = """', '        SELECT * FROM t WHERE %s', '        """.formatted(', '        ACTIVE_FILTER,', '        "1 = 1"', ');') },
        @{ Name = 'non-SQL format'; Lines = @('String m = "Selected %s items".formatted(count);') },
        @{ Name = 'numeric SQL format'; Lines = @('String q = String.format("SELECT * FROM t LIMIT %d", limit);') }
    )

    $failures = New-Object System.Collections.Generic.List[string]
    foreach ($case in $positive) {
        $rulesFound = @(Find-SqlRisks -Lines $case.Lines | ForEach-Object { $_.Rule })
        if ($rulesFound -cnotcontains $case.Rule) {
            $failures.Add("missed $($case.Rule): $($case.Lines -join ' \n ')")
        }
    }
    foreach ($case in $negative) {
        $found = @(Find-SqlRisks -Lines $case.Lines)
        if ($found.Count -ne 0) {
            $failures.Add("false positive ($($case.Name)) $(@($found | ForEach-Object { $_.Rule }) -join ','): $($case.Lines -join ' \n ')")
        }
    }
    return , $failures
}

$selfTestFailures = Invoke-SelfTest
if ($selfTestFailures.Count -ne 0) {
    Write-Host "SQL injection guard self-test failed; the rules no longer detect what they claim to:" -ForegroundColor Red
    foreach ($failure in $selfTestFailures) { Write-Host "  $failure" -ForegroundColor Red }
    exit 2
}
if ($Mode -eq "selftest") {
    Write-Host "SQL injection guard self-test passed." -ForegroundColor Green
    exit 0
}

$findings = New-Object System.Collections.Generic.List[object]
$targets = @(Get-ScanTargets)

foreach ($target in $targets) {
    $relativePath = $target.RelativePath
    $lines = @($target.Lines)
    $sourceSha256 = if ($relativePath -ceq $reviewedSql004.Path -or $relativePath -ceq $reviewedArchiveOrder.Path) {
        Get-ScannedSourceSha256 -Lines $lines
    } else { '' }

    foreach ($risk in @(Find-SqlRisks -Lines $lines)) {
        $line = [string]$lines[$risk.Index]
        if ($line -match 'sql-guard:\s*allow') {
            continue
        }
        if (Test-AllowListed -RelativePath $relativePath -Line $line -RuleId $risk.Rule -SourceSha256 $sourceSha256) {
            continue
        }
        $findings.Add([pscustomobject]@{
            Rule = $risk.Rule
            Path = $relativePath
            Line = $risk.Index + 1
            Message = $risk.Message
            Code = $line.Trim()
        })
    }
}

if ($findings.Count -eq 0) {
    Write-Host "SQL injection guard passed for $($targets.Count) Java files." -ForegroundColor Green
    exit 0
}

Write-Host "SQL injection guard found risky query construction:" -ForegroundColor Red
foreach ($finding in ($findings | Sort-Object Path, Line, Rule)) {
    Write-Host "$($finding.Rule) $($finding.Path):$($finding.Line)" -ForegroundColor Red
    Write-Host "  $($finding.Message)" -ForegroundColor Yellow
    Write-Host "  $($finding.Code)"
}
Write-Host ""
Write-Host "Fix by binding parameters, using Spring Data/Pageable Sort, or mapping request values through a strict whitelist." -ForegroundColor Yellow
Write-Host "For reviewed internal-only dynamic SQL, add a focused allow-list entry in this script or a 'sql-guard: allow' comment." -ForegroundColor Yellow
exit 1
