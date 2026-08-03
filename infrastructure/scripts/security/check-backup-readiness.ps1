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
    return $Value -match '^(?i:true|1|yes|on)$'
}

if (-not (Test-True (Get-Setting 'BACKUP_ENABLED'))) {
    Write-Warning 'Production backup automation is disabled. No email delivery or unverified object-storage upload will occur by default.'
    Write-Output 'Backup readiness contract passed (disabled by explicit safe default).'
    return
}

$missing = [System.Collections.Generic.List[string]]::new()
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

$backupEndpoint = Get-Setting 'BACKUP_S3_ENDPOINT'
$backupBucket = Get-Setting 'BACKUP_S3_BUCKET'
$backupAccessKey = Get-Setting 'BACKUP_S3_ACCESS_KEY'
if (-not [string]::IsNullOrWhiteSpace($backupEndpoint)) {
    $parsedEndpoint = $null
    if (-not [Uri]::TryCreate($backupEndpoint, [UriKind]::Absolute, [ref]$parsedEndpoint) -or
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

if (Test-True (Get-Setting 'BACKUP_MAIL_ENABLED')) {
    foreach ($name in @('BACKUP_MAIL_TO', 'BACKUP_MAIL_FROM')) {
        if ([string]::IsNullOrWhiteSpace((Get-Setting $name))) {
            $missing.Add("$name (required when BACKUP_MAIL_ENABLED=true)")
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
