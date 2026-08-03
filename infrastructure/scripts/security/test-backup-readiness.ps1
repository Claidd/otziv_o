#requires -Version 7.0
[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
$readiness = Join-Path $PSScriptRoot 'check-backup-readiness.ps1'
$testRoot = Join-Path ([IO.Path]::GetTempPath()) "otziv-backup-readiness-$([guid]::NewGuid().ToString('N'))"
[IO.Directory]::CreateDirectory($testRoot) | Out-Null
$envPath = Join-Path $testRoot 'prod.env'

function Assert-True {
    param([Parameter(Mandatory = $true)][bool]$Condition, [Parameter(Mandatory = $true)][string]$Message)
    if (-not $Condition) { throw $Message }
}

function New-TestKey {
    param([Parameter(Mandatory = $true)][byte]$Seed)
    $bytes = [byte[]]::new(32)
    try {
        for ($index = 0; $index -lt $bytes.Length; $index++) { $bytes[$index] = [byte]($Seed + $index) }
        return [Convert]::ToBase64String($bytes)
    } finally {
        [Array]::Clear($bytes, 0, $bytes.Length)
    }
}

function Write-TestEnv {
    param(
        [Parameter(Mandatory = $true)]$Base,
        [Parameter(Mandatory = $true)]$Overrides
    )

    $values = [ordered]@{}
    foreach ($entry in $Base.GetEnumerator()) { $values[$entry.Key] = $entry.Value }
    foreach ($entry in $Overrides.GetEnumerator()) { $values[$entry.Key] = $entry.Value }
    $lines = foreach ($entry in $values.GetEnumerator()) { "$($entry.Key)=$($entry.Value)" }
    [IO.File]::WriteAllLines($envPath, $lines, [Text.UTF8Encoding]::new($false))
}

function Assert-ReadinessFails {
    param(
        [Parameter(Mandatory = $true)]$Base,
        [Parameter(Mandatory = $true)]$Overrides,
        [Parameter(Mandatory = $true)][string]$Message
    )

    Write-TestEnv -Base $Base -Overrides $Overrides
    $failed = $false
    try { & $readiness -EnvFile $envPath *>$null } catch { $failed = $true }
    Assert-True -Condition $failed -Message $Message
}

try {
    $credentialKey = New-TestKey -Seed 1
    $backupKey = New-TestKey -Seed 80
    $primaryAccessName = [string]::Concat('S3_', 'ACCESS_KEY')
    $primarySecretName = [string]::Concat('S3_', 'SECRET_KEY')
    $backupAccessName = [string]::Concat('BACKUP_S3_', 'ACCESS_KEY')
    $backupSecretName = [string]::Concat('BACKUP_S3_', 'SECRET_KEY')
    $mailPasswordName = [string]::Concat('MAIL_', 'PASSWORD')
    $unsafePrimarySecretExpansion = [string]::Concat('$', '{', $primarySecretName, '}')
    $base = [ordered]@{
        BACKUP_ENABLED = 'true'
        BACKUP_SCHEDULE_ENABLED = 'true'
        BACKUP_SCHEDULE_CRON = '0 0 7 * * *'
        BACKUP_SCHEDULE_ZONE = 'Asia/Irkutsk'
        BACKUP_SCHEDULE_CATCH_UP_ENABLED = 'true'
        BACKUP_SCHEDULE_CATCH_UP_WINDOW = 'PT26H'
        BACKUP_RUN_ONCE_ENABLED = 'false'
        BACKUP_S3_ENDPOINT = 'https://s3.example.test'
        BACKUP_S3_REGION = 'ru-7'
        BACKUP_S3_BUCKET = 'production-db-backups'
        BACKUP_S3_PROJECT = 'otziv-prod'
        BACKUP_S3_REQUIRE_SERVER_SIDE_ENCRYPTION = 'false'
        BACKUP_S3_INDEPENDENT_CONFIRMED = 'true'
        BACKUP_DESTINATION_PRIVATE_CONFIRMED = 'true'
        BACKUP_ENCRYPTION_AT_REST_CONFIRMED = 'true'
        BACKUP_S3_OBJECT_LOCK_ENABLED = 'false'
        BACKUP_S3_RETENTION_DAYS = '0'
        BACKUP_ENCRYPTION_KEY_BASE64 = $backupKey
        OTZIV_CREDENTIAL_ENCRYPTION_ACTIVE_KEY_BASE64 = $credentialKey
        S3_BUCKET = 'primary-assets'
        BACKUP_RESTORE_DRILL_DATE = [DateTimeOffset]::UtcNow.ToString('O')
        BACKUP_RESTORE_DRILL_RTO = 'PT2M'
        BACKUP_MAIL_ENABLED = 'false'
    }
    $base[$backupAccessName] = [guid]::NewGuid().ToString('N')
    $base[$backupSecretName] = [guid]::NewGuid().ToString('N')
    $base[$primaryAccessName] = [guid]::NewGuid().ToString('N')

    Write-TestEnv -Base $base -Overrides ([ordered]@{})
    $validOutput = (& $readiness -EnvFile $envPath *>&1 | Out-String)
    Assert-True -Condition $validOutput.Contains('Backup readiness contract passed') -Message 'Valid backup readiness settings did not pass.'

    Assert-ReadinessFails -Base $base -Overrides ([ordered]@{
        BACKUP_ENCRYPTION_KEY_BASE64 = $credentialKey.TrimEnd('=')
    }) -Message 'Scheduled backup key reuse was not rejected bytewise.'
    Assert-ReadinessFails -Base $base -Overrides ([ordered]@{
        DEPLOY_DB_BACKUP_ENCRYPTION_KEY_BASE64 = $backupKey
    }) -Message 'Scheduled backup key reuse for pre-deploy backups was not rejected bytewise.'
    Assert-ReadinessFails -Base $base -Overrides ([ordered]@{
        BACKUP_ENABLED = 'yes'
    }) -Message 'A non-literal BACKUP_ENABLED boolean was accepted.'
    Assert-ReadinessFails -Base $base -Overrides ([ordered]@{
        BACKUP_SCHEDULE_CRON = '0 0 7 * * MON-FRI'
    }) -Message 'A schedule that skips calendar days was accepted.'
    Assert-ReadinessFails -Base $base -Overrides ([ordered]@{
        BACKUP_SCHEDULE_ZONE = 'Mars/Olympus_Mons'
    }) -Message 'An unavailable backup time zone was accepted.'
    Assert-ReadinessFails -Base $base -Overrides ([ordered]@{
        BACKUP_SCHEDULE_CATCH_UP_ENABLED = 'false'
    }) -Message 'Enabled backups were accepted without bounded catch-up.'
    Assert-ReadinessFails -Base $base -Overrides ([ordered]@{
        BACKUP_SCHEDULE_CATCH_UP_WINDOW = 'PT24H'
    }) -Message 'A catch-up window that cannot cover the previous daily occurrence was accepted.'
    Assert-ReadinessFails -Base $base -Overrides ([ordered]@{
        BACKUP_S3_ENDPOINT = 'https://s3.example.test/' + $unsafePrimarySecretExpansion
    }) -Message 'An env-interpolated backup endpoint was accepted.'

    $mail = [ordered]@{
        BACKUP_MAIL_ENABLED = 'true'
        BACKUP_EMAIL_DELIVERY_CONFIRMED = 'true'
        BACKUP_MAIL_TO = 'backup@example.com'
        BACKUP_MAIL_FROM = 'sender@example.com'
        MAIL_HOST = 'smtp.example.com'
        MAIL_PORT = '587'
        MAIL_USERNAME = 'backup-sender'
        MAIL_SMTP_AUTH = 'true'
        MAIL_STARTTLS_ENABLE = 'true'
        MAIL_STARTTLS_REQUIRED = 'true'
        MAIL_SMTP_SSL_CHECK_SERVER_IDENTITY = 'true'
        MAIL_SMTP_CONNECTION_TIMEOUT_MS = '10000'
        MAIL_SMTP_READ_TIMEOUT_MS = '0'
        MAIL_SMTP_WRITE_TIMEOUT_MS = '60000'
    }
    $mail[$mailPasswordName] = [guid]::NewGuid().ToString('N')
    Assert-ReadinessFails -Base $base -Overrides $mail -Message 'A zero SMTP timeout was accepted.'
    $mail.MAIL_SMTP_READ_TIMEOUT_MS = '60000'
    $mail.MAIL_STARTTLS_REQUIRED = 'false'
    Assert-ReadinessFails -Base $base -Overrides $mail -Message 'A backup SMTP STARTTLS downgrade was accepted.'
    $mail.MAIL_STARTTLS_REQUIRED = 'true'
    $mail.BACKUP_MAIL_TO = 'foo' + $unsafePrimarySecretExpansion + '@example.com'
    Assert-ReadinessFails -Base $base -Overrides $mail -Message 'An env-interpolated backup mail address was accepted.'
    $mail.BACKUP_MAIL_TO = 'not-an-address'
    Assert-ReadinessFails -Base $base -Overrides $mail -Message 'A malformed backup mail address was accepted.'

    Write-Output 'Production backup readiness contract tests passed.'
} finally {
    if (Test-Path -LiteralPath $testRoot) {
        Remove-Item -LiteralPath $testRoot -Recurse -Force
    }
}
