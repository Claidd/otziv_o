[CmdletBinding()]
param(
    [string]$EnvFile = '.env.prod.example',
    [ValidateRange(1, 3650)][int]$MaximumRestoreDrillAgeDays = 90
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repoRoot = (& git rev-parse --show-toplevel).Trim()
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($repoRoot)) {
    throw 'Run this script from inside the repository.'
}

$envPath = if ([System.IO.Path]::IsPathRooted($EnvFile)) { $EnvFile } else { Join-Path $repoRoot $EnvFile }
if (-not (Test-Path -LiteralPath $envPath -PathType Leaf)) {
    throw "Environment file not found: $envPath"
}

$values = @{}
foreach ($line in Get-Content -LiteralPath $envPath) {
    $trimmed = $line.Trim()
    if ($trimmed.Length -eq 0 -or $trimmed.StartsWith('#')) {
        continue
    }
    $parts = $trimmed.Split('=', 2)
    if ($parts.Count -eq 2) {
        $values[$parts[0].Trim()] = $parts[1].Trim().Trim('"').Trim("'")
    }
}

function Get-Setting {
    param([Parameter(Mandatory = $true)][string]$Name)
    if ($values.ContainsKey($Name)) { return [string]$values[$Name] }
    return ''
}

function Test-True {
    param([AllowEmptyString()][string]$Value)
    return $Value -ceq 'true'
}

function Test-Base64SecretsEqual {
    param(
        [Parameter(Mandatory = $true)][string]$Left,
        [Parameter(Mandatory = $true)][string]$Right
    )

    $leftBytes = $null
    $rightBytes = $null
    try {
        $normalizedLeft = $Left.PadRight($Left.Length + ((4 - ($Left.Length % 4)) % 4), '=')
        $normalizedRight = $Right.PadRight($Right.Length + ((4 - ($Right.Length % 4)) % 4), '=')
        $leftBytes = [Convert]::FromBase64String($normalizedLeft)
        $rightBytes = [Convert]::FromBase64String($normalizedRight)
        if ($leftBytes.Length -ne 32 -or $rightBytes.Length -ne 32) { return $false }
        $difference = 0
        for ($index = 0; $index -lt $leftBytes.Length; $index++) {
            $difference = $difference -bor ($leftBytes[$index] -bxor $rightBytes[$index])
        }
        return $difference -eq 0
    } catch [FormatException] {
        return $false
    } finally {
        foreach ($buffer in @($leftBytes, $rightBytes)) {
            if ($null -ne $buffer) { [Array]::Clear($buffer, 0, $buffer.Length) }
        }
        $normalizedLeft = $null
        $normalizedRight = $null
    }
}

function Test-DailyCron {
    param([AllowEmptyString()][string]$Value)

    if ([string]::IsNullOrWhiteSpace($Value) -or $Value.Length -gt 127 -or
            $Value -notmatch '^[A-Za-z0-9*?,/\-#LW ]+$') {
        return $false
    }
    $fields = @($Value -split ' +' | Where-Object { $_ })
    if ($fields.Count -ne 6) { return $false }

    foreach ($field in @(
        @{ Value = $fields[0]; Maximum = 59 },
        @{ Value = $fields[1]; Maximum = 59 },
        @{ Value = $fields[2]; Maximum = 23 }
    )) {
        $parsed = 0
        if ($field.Value -notmatch '^[0-9]+$' -or
                -not [int]::TryParse($field.Value, [Globalization.NumberStyles]::None,
                    [Globalization.CultureInfo]::InvariantCulture, [ref]$parsed) -or
                $parsed -lt 0 -or $parsed -gt $field.Maximum) {
            return $false
        }
    }
    return $fields[3] -ceq '*' -and $fields[4] -ceq '*' -and $fields[5] -ceq '*'
}

$backupBooleanNames = @(
    'BACKUP_ENABLED',
    'BACKUP_SCHEDULE_ENABLED',
    'BACKUP_SCHEDULE_CATCH_UP_ENABLED',
    'BACKUP_RUN_ONCE_ENABLED',
    'BACKUP_S3_FORCE_PATH_STYLE',
    'BACKUP_S3_REQUIRE_SERVER_SIDE_ENCRYPTION',
    'BACKUP_S3_INDEPENDENT_CONFIRMED',
    'BACKUP_DESTINATION_PRIVATE_CONFIRMED',
    'BACKUP_ENCRYPTION_AT_REST_CONFIRMED',
    'BACKUP_S3_OBJECT_LOCK_ENABLED',
    'BACKUP_MAIL_ENABLED',
    'BACKUP_EMAIL_DELIVERY_CONFIRMED'
)
foreach ($booleanName in $backupBooleanNames) {
    $booleanValue = Get-Setting $booleanName
    if (-not [string]::IsNullOrWhiteSpace($booleanValue) -and $booleanValue -cnotin @('true', 'false')) {
        throw "$booleanName must be exactly true or false."
    }
}

$persistedRunOnce = Get-Setting 'BACKUP_RUN_ONCE_ENABLED'
if (-not [string]::IsNullOrWhiteSpace($persistedRunOnce) -and $persistedRunOnce -cne 'false') {
    throw 'BACKUP_RUN_ONCE_ENABLED must remain false in persistent env; one-shot mode is not a production procedure.'
}

if (-not (Test-True (Get-Setting 'BACKUP_ENABLED'))) {
    Write-Warning 'Production backup automation is disabled. No email delivery or unverified object-storage upload will occur by default.'
    Write-Output 'Backup readiness contract passed (disabled by explicit safe default).'
    return
}

$missing = [System.Collections.Generic.List[string]]::new()
$scheduleEnabled = Get-Setting 'BACKUP_SCHEDULE_ENABLED'
if ([string]::IsNullOrWhiteSpace($scheduleEnabled)) { $scheduleEnabled = 'true' }
if ($scheduleEnabled -cne 'true') {
    $missing.Add('BACKUP_SCHEDULE_ENABLED=true (required for recurring production backups)')
}
$catchUpEnabled = Get-Setting 'BACKUP_SCHEDULE_CATCH_UP_ENABLED'
if ([string]::IsNullOrWhiteSpace($catchUpEnabled)) { $catchUpEnabled = 'true' }
if ($catchUpEnabled -cne 'true') {
    $missing.Add('BACKUP_SCHEDULE_CATCH_UP_ENABLED=true (required to recover the previous daily occurrence)')
}
$catchUpWindow = Get-Setting 'BACKUP_SCHEDULE_CATCH_UP_WINDOW'
if ([string]::IsNullOrWhiteSpace($catchUpWindow)) { $catchUpWindow = 'PT26H' }
try {
    $parsedCatchUpWindow = [Xml.XmlConvert]::ToTimeSpan($catchUpWindow)
    if ($parsedCatchUpWindow -le [TimeSpan]::FromHours(24) -or
            $parsedCatchUpWindow -gt [TimeSpan]::FromHours(36)) {
        $missing.Add('BACKUP_SCHEDULE_CATCH_UP_WINDOW (greater than PT24H and no greater than PT36H)')
    }
} catch {
    $missing.Add('BACKUP_SCHEDULE_CATCH_UP_WINDOW (valid ISO-8601 duration)')
}
$scheduleCron = Get-Setting 'BACKUP_SCHEDULE_CRON'
if ([string]::IsNullOrWhiteSpace($scheduleCron)) { $scheduleCron = '0 0 7 * * *' }
if (-not (Test-DailyCron $scheduleCron)) {
    $missing.Add("BACKUP_SCHEDULE_CRON (one numeric seconds/minutes/hours value and '*' for day-of-month/month/day-of-week)")
}
$scheduleZone = Get-Setting 'BACKUP_SCHEDULE_ZONE'
if ([string]::IsNullOrWhiteSpace($scheduleZone)) { $scheduleZone = 'Asia/Irkutsk' }
if ($scheduleZone -notmatch '^[A-Za-z0-9][A-Za-z0-9._+/-]{0,127}$' -or $scheduleZone.Contains('..')) {
    $missing.Add('BACKUP_SCHEDULE_ZONE (safe runtime time-zone ID)')
} else {
    try {
        [void][TimeZoneInfo]::FindSystemTimeZoneById($scheduleZone)
    } catch {
        $missing.Add('BACKUP_SCHEDULE_ZONE (time-zone ID available to the runtime)')
    }
}
foreach ($name in @(
    'BACKUP_S3_ENDPOINT', 'BACKUP_S3_REGION', 'BACKUP_S3_BUCKET',
    'BACKUP_S3_PROJECT', 'BACKUP_S3_ACCESS_KEY', 'BACKUP_S3_SECRET_KEY',
    'BACKUP_RESTORE_DRILL_DATE', 'BACKUP_RESTORE_DRILL_RTO'
)) {
    if ([string]::IsNullOrWhiteSpace((Get-Setting $name))) {
        $missing.Add($name)
    }
}
if (-not (Test-True (Get-Setting 'BACKUP_S3_INDEPENDENT_CONFIRMED'))) {
    $missing.Add('BACKUP_S3_INDEPENDENT_CONFIRMED=true')
}
if (-not (Test-True (Get-Setting 'BACKUP_DESTINATION_PRIVATE_CONFIRMED'))) {
    $missing.Add('BACKUP_DESTINATION_PRIVATE_CONFIRMED=true')
}
if (-not (Test-True (Get-Setting 'BACKUP_ENCRYPTION_AT_REST_CONFIRMED'))) {
    $missing.Add('BACKUP_ENCRYPTION_AT_REST_CONFIRMED=true')
}
$requireServerSideEncryption = Get-Setting 'BACKUP_S3_REQUIRE_SERVER_SIDE_ENCRYPTION'
if ([string]::IsNullOrWhiteSpace($requireServerSideEncryption)) {
    $requireServerSideEncryption = 'true'
} elseif ($requireServerSideEncryption -cnotin @('true', 'false')) {
    $missing.Add('BACKUP_S3_REQUIRE_SERVER_SIDE_ENCRYPTION (exactly true or false)')
}

$backupEndpoint = Get-Setting 'BACKUP_S3_ENDPOINT'
$backupBucket = Get-Setting 'BACKUP_S3_BUCKET'
$backupAccessKey = Get-Setting 'BACKUP_S3_ACCESS_KEY'
if (-not [string]::IsNullOrWhiteSpace($backupEndpoint)) {
    $parsedEndpoint = $null
    if ($backupEndpoint.Contains('$') -or $backupEndpoint.Contains('#') -or
        $backupEndpoint.Contains('"') -or $backupEndpoint.Contains("'") -or
        $backupEndpoint.Contains("`r") -or $backupEndpoint.Contains("`n") -or
        -not [Uri]::TryCreate($backupEndpoint, [UriKind]::Absolute, [ref]$parsedEndpoint) -or
        $parsedEndpoint.Scheme -cne 'https' -or
        [string]::IsNullOrWhiteSpace($parsedEndpoint.Host) -or
        -not [string]::IsNullOrWhiteSpace($parsedEndpoint.UserInfo) -or
        -not [string]::IsNullOrWhiteSpace($parsedEndpoint.Query) -or
        -not [string]::IsNullOrWhiteSpace($parsedEndpoint.Fragment)) {
        $missing.Add('BACKUP_S3_ENDPOINT (absolute HTTPS URI without credentials, query or fragment)')
    }
}
if (-not [string]::IsNullOrWhiteSpace($backupBucket) -and
    $backupBucket.Trim().Equals((Get-Setting 'S3_BUCKET').Trim(), [StringComparison]::OrdinalIgnoreCase)) {
    $missing.Add('BACKUP_S3_BUCKET (must differ from primary S3 bucket)')
}
if (-not [string]::IsNullOrWhiteSpace($backupAccessKey) -and
    $backupAccessKey -ceq (Get-Setting 'S3_ACCESS_KEY')) {
    $missing.Add('BACKUP_S3_ACCESS_KEY (must identify credentials distinct from primary S3)')
}

$objectLockEnabled = Test-True (Get-Setting 'BACKUP_S3_OBJECT_LOCK_ENABLED')
$retentionDays = 0
$retentionText = Get-Setting 'BACKUP_S3_RETENTION_DAYS'
if (-not [string]::IsNullOrWhiteSpace($retentionText) -and
    -not [int]::TryParse($retentionText, [Globalization.NumberStyles]::None, [Globalization.CultureInfo]::InvariantCulture, [ref]$retentionDays)) {
    $missing.Add('BACKUP_S3_RETENTION_DAYS (integer from 0 to 36500)')
} elseif ($retentionDays -lt 0 -or $retentionDays -gt 36500) {
    $missing.Add('BACKUP_S3_RETENTION_DAYS (integer from 0 to 36500)')
} elseif ($objectLockEnabled -and $retentionDays -lt 1) {
    $missing.Add('BACKUP_S3_RETENTION_DAYS (positive when Object Lock is enabled)')
} elseif (-not $objectLockEnabled -and $retentionDays -ne 0) {
    $missing.Add('BACKUP_S3_OBJECT_LOCK_ENABLED=true (required for non-zero retention)')
}
if ($objectLockEnabled -and (Get-Setting 'BACKUP_S3_OBJECT_LOCK_MODE').ToUpperInvariant() -notin @('GOVERNANCE', 'COMPLIANCE')) {
    $missing.Add('BACKUP_S3_OBJECT_LOCK_MODE (GOVERNANCE or COMPLIANCE)')
}

$evidenceFileName = Get-Setting 'BACKUP_EVIDENCE_FILE_NAME'
if (-not [string]::IsNullOrWhiteSpace($evidenceFileName) -and $evidenceFileName -notmatch '^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$') {
    $missing.Add('BACKUP_EVIDENCE_FILE_NAME (simple file name only)')
}
$restoreDrillRto = Get-Setting 'BACKUP_RESTORE_DRILL_RTO'
if (-not [string]::IsNullOrWhiteSpace($restoreDrillRto)) {
    try {
        $parsedRto = [System.Xml.XmlConvert]::ToTimeSpan($restoreDrillRto)
        if ($parsedRto -le [TimeSpan]::Zero -or $parsedRto -gt [TimeSpan]::FromDays(7)) {
            $missing.Add('BACKUP_RESTORE_DRILL_RTO (positive ISO-8601 duration, maximum P7D)')
        }
    } catch {
        $missing.Add('BACKUP_RESTORE_DRILL_RTO (valid ISO-8601 duration)')
    }
}

$decodedBackupKey = $null
$encodedBackupKey = Get-Setting 'BACKUP_ENCRYPTION_KEY_BASE64'
if ([string]::IsNullOrWhiteSpace($encodedBackupKey)) {
    $missing.Add('BACKUP_ENCRYPTION_KEY_BASE64 (32-byte key encoded as Base64)')
} else {
    try {
        $decodedBackupKey = [Convert]::FromBase64String($encodedBackupKey)
        if ($decodedBackupKey.Length -ne 32) {
            $missing.Add('BACKUP_ENCRYPTION_KEY_BASE64 (must decode to exactly 32 bytes)')
        }
    } catch [FormatException] {
        $missing.Add('BACKUP_ENCRYPTION_KEY_BASE64 (invalid Base64)')
    } finally {
        if ($null -ne $decodedBackupKey) {
            [Array]::Clear($decodedBackupKey, 0, $decodedBackupKey.Length)
        }
    }
}

$credentialEncryptionKey = Get-Setting 'OTZIV_CREDENTIAL_ENCRYPTION_ACTIVE_KEY_BASE64'
if (-not [string]::IsNullOrWhiteSpace($encodedBackupKey) -and
        -not [string]::IsNullOrWhiteSpace($credentialEncryptionKey) -and
        (Test-Base64SecretsEqual -Left $encodedBackupKey -Right $credentialEncryptionKey)) {
    $missing.Add('BACKUP_ENCRYPTION_KEY_BASE64 (must differ from OTZIV_CREDENTIAL_ENCRYPTION_ACTIVE_KEY_BASE64)')
}
$credentialEncryptionKey = $null
$deployBackupKey = Get-Setting 'DEPLOY_DB_BACKUP_ENCRYPTION_KEY_BASE64'
if (-not [string]::IsNullOrWhiteSpace($encodedBackupKey) -and
        -not [string]::IsNullOrWhiteSpace($deployBackupKey) -and
        (Test-Base64SecretsEqual -Left $encodedBackupKey -Right $deployBackupKey)) {
    $missing.Add('BACKUP_ENCRYPTION_KEY_BASE64 (must differ from DEPLOY_DB_BACKUP_ENCRYPTION_KEY_BASE64)')
}
$deployBackupKey = $null

if (Test-True (Get-Setting 'BACKUP_MAIL_ENABLED')) {
    foreach ($name in @('BACKUP_MAIL_TO', 'BACKUP_MAIL_FROM', 'MAIL_HOST', 'MAIL_USERNAME', 'MAIL_PASSWORD')) {
        if ([string]::IsNullOrWhiteSpace((Get-Setting $name))) {
            $missing.Add("$name (required when BACKUP_MAIL_ENABLED=true)")
        }
    }
    $mailPortText = Get-Setting 'MAIL_PORT'
    if ([string]::IsNullOrWhiteSpace($mailPortText)) { $mailPortText = '587' }
    $mailPort = 0
    if (-not [int]::TryParse($mailPortText, [Globalization.NumberStyles]::None,
            [Globalization.CultureInfo]::InvariantCulture, [ref]$mailPort) -or
            $mailPort -lt 1 -or $mailPort -gt 65535) {
        $missing.Add('MAIL_PORT (integer from 1 to 65535)')
    }
    foreach ($timeout in @(
        @{ Name = 'MAIL_SMTP_CONNECTION_TIMEOUT_MS'; Default = '10000' },
        @{ Name = 'MAIL_SMTP_READ_TIMEOUT_MS'; Default = '60000' },
        @{ Name = 'MAIL_SMTP_WRITE_TIMEOUT_MS'; Default = '60000' }
    )) {
        $timeoutValue = Get-Setting $timeout.Name
        if ([string]::IsNullOrWhiteSpace($timeoutValue)) { $timeoutValue = $timeout.Default }
        $timeoutMillis = 0
        if (-not [int]::TryParse($timeoutValue, [Globalization.NumberStyles]::None,
                [Globalization.CultureInfo]::InvariantCulture, [ref]$timeoutMillis) -or
                $timeoutMillis -lt 1 -or $timeoutMillis -gt 600000) {
            $missing.Add("$($timeout.Name) (positive integer, maximum 600000 ms)")
        }
    }
    foreach ($requiredTrue in @(
        'MAIL_SMTP_AUTH',
        'MAIL_STARTTLS_ENABLE',
        'MAIL_STARTTLS_REQUIRED',
        'MAIL_SMTP_SSL_CHECK_SERVER_IDENTITY'
    )) {
        $flagValue = Get-Setting $requiredTrue
        if ([string]::IsNullOrWhiteSpace($flagValue)) { $flagValue = 'true' }
        if ($flagValue -cne 'true') {
            $missing.Add("$requiredTrue=true (required for encrypted authenticated backup email)")
        }
    }
    foreach ($mailTextName in @('BACKUP_MAIL_SUBJECT', 'BACKUP_MAIL_BODY')) {
        $mailText = Get-Setting $mailTextName
        if (-not [string]::IsNullOrWhiteSpace($mailText) -and
                ($mailText.Contains('$') -or $mailText.Contains('#') -or
                 $mailText.Contains('"') -or $mailText.Contains("'") -or
                 $mailText.Contains("`r") -or $mailText.Contains("`n"))) {
            $missing.Add("$mailTextName (literal single-line env-safe text)")
        }
    }
    foreach ($mailAddressName in @('BACKUP_MAIL_TO', 'BACKUP_MAIL_FROM')) {
        $mailAddress = Get-Setting $mailAddressName
        if (-not [string]::IsNullOrWhiteSpace($mailAddress) -and
                ($mailAddress.Contains('$') -or $mailAddress.Contains('#') -or
                 $mailAddress.Contains('"') -or $mailAddress.Contains("'") -or
                 $mailAddress.Contains("`r") -or $mailAddress.Contains("`n"))) {
            $missing.Add("$mailAddressName (literal single-address env-safe text)")
            continue
        }
        if (-not [string]::IsNullOrWhiteSpace($mailAddress)) {
            try {
                $parsedMailAddress = [Net.Mail.MailAddress]::new($mailAddress)
                if ($parsedMailAddress.Address -cne $mailAddress) { throw 'non-canonical' }
            } catch {
                $missing.Add("$mailAddressName (one canonical email address)")
            }
        }
    }
    if (-not (Test-True (Get-Setting 'BACKUP_EMAIL_DELIVERY_CONFIRMED'))) {
        $missing.Add('BACKUP_EMAIL_DELIVERY_CONFIRMED=true (required when BACKUP_MAIL_ENABLED=true)')
    }
}
if ($missing.Count -gt 0) {
    throw "BACKUP_ENABLED=true is not production-ready. Missing/invalid controls: $($missing -join ', ')"
}

$drillDate = [DateTimeOffset]::MinValue
if (-not [DateTimeOffset]::TryParse((Get-Setting 'BACKUP_RESTORE_DRILL_DATE'), [ref]$drillDate)) {
    throw 'BACKUP_RESTORE_DRILL_DATE must be an ISO-8601 date or timestamp.'
}
$age = [DateTimeOffset]::UtcNow - $drillDate.ToUniversalTime()
if ($age.TotalDays -lt -1) {
    throw 'BACKUP_RESTORE_DRILL_DATE cannot be in the future.'
}
if ($age.TotalDays -gt $MaximumRestoreDrillAgeDays) {
    throw "The last recorded restore drill is older than $MaximumRestoreDrillAgeDays days."
}

Write-Output "Backup readiness contract passed; last restore drill age is $([Math]::Floor($age.TotalDays)) day(s)."
