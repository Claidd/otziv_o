#requires -Version 7.0
[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
$importer = Join-Path $PSScriptRoot 'import-prod-backup-config.ps1'
$testRoot = Join-Path ([IO.Path]::GetTempPath()) "otziv-backup-import-$([guid]::NewGuid().ToString('N'))"
$envDirectory = Join-Path $testRoot 'env'
[IO.Directory]::CreateDirectory($envDirectory) | Out-Null
$oldEnvDirectory = [Environment]::GetEnvironmentVariable('OTZIV_ENV_DIR')

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

function Write-JsonFile {
    param([Parameter(Mandatory = $true)][string]$Path, [Parameter(Mandatory = $true)]$Value)
    [IO.File]::WriteAllText($Path, ($Value | ConvertTo-Json -Depth 4), [Text.UTF8Encoding]::new($false))
}

try {
    [Environment]::SetEnvironmentVariable('OTZIV_ENV_DIR', $envDirectory)
    $envPath = Join-Path $envDirectory 'prod.env'
    $credentialKey = New-TestKey -Seed 1
    $deployKey = New-TestKey -Seed 40
    $scheduledKey = New-TestKey -Seed 80
    $primaryAccessName = [string]::Concat('S3_', 'ACCESS_KEY')
    $backupAccessName = [string]::Concat('BACKUP_S3_', 'ACCESS_KEY')
    $backupSecretName = [string]::Concat('BACKUP_S3_', 'SECRET_KEY')
    $primaryAccessValue = [guid]::NewGuid().ToString('N')
    $backupAccessValue = [guid]::NewGuid().ToString('N')
    $backupSecretValue = [guid]::NewGuid().ToString('N')
    $original = @(
        'BACKUP_ENABLED=false',
        "OTZIV_CREDENTIAL_ENCRYPTION_ACTIVE_KEY_BASE64=$credentialKey",
        "DEPLOY_DB_BACKUP_ENCRYPTION_KEY_BASE64=$deployKey",
        'S3_BUCKET=primary-assets',
        "$primaryAccessName=$primaryAccessValue"
    ) -join [Environment]::NewLine
    [IO.File]::WriteAllText($envPath, $original + [Environment]::NewLine, [Text.UTF8Encoding]::new($false))

    $valid = [ordered]@{
        BACKUP_ENABLED = 'false'
        BACKUP_WORK_DIR = '/app/backup'
        BACKUP_PART_SIZE_MB = '16'
        BACKUP_DUMP_TIMEOUT = 'PT15M'
        BACKUP_UPLOAD_TIMEOUT = 'PT10M'
        BACKUP_MAX_STDERR_BYTES = '65536'
        BACKUP_ENCRYPTION_KEY_BASE64 = $scheduledKey
        BACKUP_EVIDENCE_FILE_NAME = 'backup-evidence.jsonl'
        BACKUP_SCHEDULE_ENABLED = 'true'
        BACKUP_SCHEDULE_CRON = '0 0 7 * * *'
        BACKUP_SCHEDULE_ZONE = 'Asia/Irkutsk'
        BACKUP_SCHEDULE_CATCH_UP_ENABLED = 'true'
        BACKUP_SCHEDULE_CATCH_UP_WINDOW = 'PT26H'
        BACKUP_SCHEDULE_CATCH_UP_CHECK_INTERVAL = 'PT15M'
        BACKUP_SCHEDULE_CATCH_UP_INITIAL_DELAY = 'PT1M'
        BACKUP_RUN_ONCE_ENABLED = 'false'
        BACKUP_S3_ENDPOINT = 'https://s3.example.test'
        BACKUP_S3_REGION = 'ru-7'
        BACKUP_S3_BUCKET = 'production-db-backups'
        BACKUP_S3_PROJECT = 'otziv-prod'
        BACKUP_S3_FORCE_PATH_STYLE = 'false'
        BACKUP_S3_REQUIRE_SERVER_SIDE_ENCRYPTION = 'false'
        BACKUP_S3_INDEPENDENT_CONFIRMED = 'true'
        BACKUP_DESTINATION_PRIVATE_CONFIRMED = 'true'
        BACKUP_ENCRYPTION_AT_REST_CONFIRMED = 'true'
        BACKUP_S3_OBJECT_LOCK_ENABLED = 'true'
        BACKUP_S3_OBJECT_LOCK_MODE = 'GOVERNANCE'
        BACKUP_S3_RETENTION_DAYS = '90'
        BACKUP_MAIL_ENABLED = 'false'
        BACKUP_EMAIL_DELIVERY_CONFIRMED = 'false'
    }
    $valid[$backupAccessName] = $backupAccessValue
    $valid[$backupSecretName] = $backupSecretValue
    $validPath = Join-Path $testRoot 'valid.json'
    Write-JsonFile -Path $validPath -Value $valid
    $output = (& $importer -ConfigJsonPath $validPath -Confirm:$false *>&1 | Out-String)
    Assert-True -Condition (-not (Test-Path -LiteralPath $validPath)) -Message 'Successful import did not consume input JSON.'
    $updated = [IO.File]::ReadAllText($envPath)
    Assert-True -Condition ($updated.Contains("BACKUP_ENCRYPTION_KEY_BASE64=$scheduledKey")) -Message 'Scheduled backup key was not imported.'
    Assert-True -Condition ($updated.Contains('BACKUP_S3_REQUIRE_SERVER_SIDE_ENCRYPTION=false')) -Message 'SSE compatibility mode was not imported.'
    Assert-True -Condition (-not $output.Contains($scheduledKey)) -Message 'Importer printed the scheduled backup key.'
    Assert-True -Condition (-not $output.Contains($backupSecretValue)) -Message 'Importer printed the S3 secret key.'
    $backups = @(Get-ChildItem -LiteralPath $envDirectory -Filter 'prod.env.backup-config-*.bak')
    Assert-True -Condition ($backups.Count -eq 1) -Message 'Importer did not create exactly one protected env backup.'
    Assert-True -Condition ([IO.File]::ReadAllText($backups[0].FullName).Contains('S3_BUCKET=primary-assets')) -Message 'Env backup does not contain the prior state.'

    $beforeFailure = [IO.File]::ReadAllText($envPath)
    $unknownPath = Join-Path $testRoot 'unknown.json'
    Write-JsonFile -Path $unknownPath -Value ([ordered]@{ BACKUP_NOT_ALLOWLISTED = 'value' })
    $failed = $false
    try { & $importer -ConfigJsonPath $unknownPath -Confirm:$false 2>$null | Out-Null } catch { $failed = $true }
    Assert-True -Condition $failed -Message 'Unknown key was accepted.'
    Assert-True -Condition (Test-Path -LiteralPath $unknownPath) -Message 'Invalid input JSON was deleted.'
    Assert-True -Condition ([IO.File]::ReadAllText($envPath) -ceq $beforeFailure) -Message 'Unknown-key failure changed the env.'

    $sameKeyPath = Join-Path $testRoot 'same-key.json'
    Write-JsonFile -Path $sameKeyPath -Value ([ordered]@{ BACKUP_ENCRYPTION_KEY_BASE64 = $credentialKey })
    $failed = $false
    try { & $importer -ConfigJsonPath $sameKeyPath -Confirm:$false 2>$null | Out-Null } catch { $failed = $true }
    Assert-True -Condition $failed -Message 'Credential-key reuse was accepted.'
    Assert-True -Condition (Test-Path -LiteralPath $sameKeyPath) -Message 'Rejected key input was deleted.'

    $sameDeployKeyPath = Join-Path $testRoot 'same-deploy-key.json'
    Write-JsonFile -Path $sameDeployKeyPath -Value ([ordered]@{ BACKUP_ENCRYPTION_KEY_BASE64 = $deployKey })
    $failed = $false
    try { & $importer -ConfigJsonPath $sameDeployKeyPath -Confirm:$false 2>$null | Out-Null } catch { $failed = $true }
    Assert-True -Condition $failed -Message 'Pre-deploy backup key reuse was accepted.'
    Assert-True -Condition (Test-Path -LiteralPath $sameDeployKeyPath) -Message 'Rejected pre-deploy key input was deleted.'

    $invalidSseBooleanPath = Join-Path $testRoot 'invalid-sse-boolean.json'
    Write-JsonFile -Path $invalidSseBooleanPath -Value ([ordered]@{ BACKUP_S3_REQUIRE_SERVER_SIDE_ENCRYPTION = 'no' })
    $failed = $false
    try { & $importer -ConfigJsonPath $invalidSseBooleanPath -Confirm:$false 2>$null | Out-Null } catch { $failed = $true }
    Assert-True -Condition $failed -Message 'Invalid SSE requirement boolean was accepted.'
    Assert-True -Condition (Test-Path -LiteralPath $invalidSseBooleanPath) -Message 'Rejected SSE boolean input was deleted.'

    $unsafeRunOncePath = Join-Path $testRoot 'unsafe-run-once.json'
    Write-JsonFile -Path $unsafeRunOncePath -Value ([ordered]@{
        BACKUP_SCHEDULE_ENABLED = 'true'
        BACKUP_RUN_ONCE_ENABLED = 'true'
        BACKUP_RUN_ONCE_REQUEST_ID = 'verify-1'
    })
    $failed = $false
    try { & $importer -ConfigJsonPath $unsafeRunOncePath -Confirm:$false 2>$null | Out-Null } catch { $failed = $true }
    Assert-True -Condition $failed -Message 'Run-once was accepted while the recurring schedule remained enabled.'
    Assert-True -Condition (Test-Path -LiteralPath $unsafeRunOncePath) -Message 'Rejected run-once input was deleted.'

    $persistedRunOncePath = Join-Path $testRoot 'persisted-run-once.json'
    Write-JsonFile -Path $persistedRunOncePath -Value ([ordered]@{
        BACKUP_SCHEDULE_ENABLED = 'false'
        BACKUP_SCHEDULE_CATCH_UP_ENABLED = 'false'
        BACKUP_RUN_ONCE_ENABLED = 'true'
        BACKUP_RUN_ONCE_REQUEST_ID = 'verify-2'
    })
    $failed = $false
    try { & $importer -ConfigJsonPath $persistedRunOncePath -Confirm:$false 2>$null | Out-Null } catch { $failed = $true }
    Assert-True -Condition $failed -Message 'Persistent run-once was accepted with the recurring schedule disabled.'
    Assert-True -Condition (Test-Path -LiteralPath $persistedRunOncePath) -Message 'Rejected persistent run-once input was deleted.'

    $frequentCronPath = Join-Path $testRoot 'frequent-cron.json'
    Write-JsonFile -Path $frequentCronPath -Value ([ordered]@{ BACKUP_SCHEDULE_CRON = '0 */15 * * * *' })
    $failed = $false
    try { & $importer -ConfigJsonPath $frequentCronPath -Confirm:$false 2>$null | Out-Null } catch { $failed = $true }
    Assert-True -Condition $failed -Message 'A cron expression that can run more than once per day was accepted.'
    Assert-True -Condition (Test-Path -LiteralPath $frequentCronPath) -Message 'Rejected frequent cron input was deleted.'

    $nonDailyCronPath = Join-Path $testRoot 'non-daily-cron.json'
    Write-JsonFile -Path $nonDailyCronPath -Value ([ordered]@{ BACKUP_SCHEDULE_CRON = '0 0 7 * * MON-FRI' })
    $failed = $false
    try { & $importer -ConfigJsonPath $nonDailyCronPath -Confirm:$false 2>$null | Out-Null } catch { $failed = $true }
    Assert-True -Condition $failed -Message 'A schedule that skips calendar days was accepted as a daily backup.'
    Assert-True -Condition (Test-Path -LiteralPath $nonDailyCronPath) -Message 'Rejected non-daily cron input was deleted.'

    $invalidZonePath = Join-Path $testRoot 'invalid-zone.json'
    Write-JsonFile -Path $invalidZonePath -Value ([ordered]@{ BACKUP_SCHEDULE_ZONE = 'Mars/Olympus_Mons' })
    $failed = $false
    try { & $importer -ConfigJsonPath $invalidZonePath -Confirm:$false 2>$null | Out-Null } catch { $failed = $true }
    Assert-True -Condition $failed -Message 'An unavailable runtime time-zone ID was accepted.'
    Assert-True -Condition (Test-Path -LiteralPath $invalidZonePath) -Message 'Rejected time-zone input was deleted.'

    $unsafeEndpointPath = Join-Path $testRoot 'unsafe-endpoint.json'
    Write-JsonFile -Path $unsafeEndpointPath -Value ([ordered]@{ BACKUP_S3_ENDPOINT = 'https://s3.example.test/${S3_SECRET_KEY}' })
    $failed = $false
    try { & $importer -ConfigJsonPath $unsafeEndpointPath -Confirm:$false 2>$null | Out-Null } catch { $failed = $true }
    Assert-True -Condition $failed -Message 'An env-interpolated backup endpoint was accepted.'
    Assert-True -Condition (Test-Path -LiteralPath $unsafeEndpointPath) -Message 'Rejected endpoint input was deleted.'

    $disabledCatchUpPath = Join-Path $testRoot 'enabled-without-catch-up.json'
    Write-JsonFile -Path $disabledCatchUpPath -Value ([ordered]@{
        BACKUP_ENABLED = 'true'
        BACKUP_SCHEDULE_CATCH_UP_ENABLED = 'false'
    })
    $failed = $false
    try { & $importer -ConfigJsonPath $disabledCatchUpPath -Confirm:$false 2>$null | Out-Null } catch { $failed = $true }
    Assert-True -Condition $failed -Message 'Enabled backups were accepted without catch-up.'
    Assert-True -Condition (Test-Path -LiteralPath $disabledCatchUpPath) -Message 'Rejected catch-up input was deleted.'

    $shortCatchUpPath = Join-Path $testRoot 'short-catch-up.json'
    Write-JsonFile -Path $shortCatchUpPath -Value ([ordered]@{ BACKUP_SCHEDULE_CATCH_UP_WINDOW = 'PT24H' })
    $failed = $false
    try { & $importer -ConfigJsonPath $shortCatchUpPath -Confirm:$false 2>$null | Out-Null } catch { $failed = $true }
    Assert-True -Condition $failed -Message 'A catch-up window unable to cover the previous daily occurrence was accepted.'
    Assert-True -Condition (Test-Path -LiteralPath $shortCatchUpPath) -Message 'Rejected catch-up window input was deleted.'

    foreach ($unsafeMailText in @(
        @{ Name = 'BACKUP_MAIL_SUBJECT'; Value = 'Backup ${S3_SECRET_KEY}' },
        @{ Name = 'BACKUP_MAIL_SUBJECT'; Value = 'Backup #1' },
        @{ Name = 'BACKUP_MAIL_BODY'; Value = '"quoted" backup' },
        @{ Name = 'BACKUP_MAIL_BODY'; Value = "owner's backup" },
        @{ Name = 'BACKUP_MAIL_TO'; Value = 'foo${S3_SECRET_KEY}@example.com' },
        @{ Name = 'BACKUP_MAIL_FROM'; Value = 'foo#tag@example.com' }
    )) {
        $unsafeMailPath = Join-Path $testRoot "unsafe-mail-$([guid]::NewGuid().ToString('N')).json"
        $unsafeMailUpdate = [ordered]@{}
        $unsafeMailUpdate[$unsafeMailText.Name] = $unsafeMailText.Value
        Write-JsonFile -Path $unsafeMailPath -Value $unsafeMailUpdate
        $failed = $false
        try { & $importer -ConfigJsonPath $unsafeMailPath -Confirm:$false 2>$null | Out-Null } catch { $failed = $true }
        Assert-True -Condition $failed -Message "$($unsafeMailText.Name) accepted an env-file-dangerous value."
        Assert-True -Condition (Test-Path -LiteralPath $unsafeMailPath) -Message 'Rejected mail text input was deleted.'
    }

    $disabledSchedulePath = Join-Path $testRoot 'enabled-with-disabled-schedule.json'
    Write-JsonFile -Path $disabledSchedulePath -Value ([ordered]@{
        BACKUP_ENABLED = 'true'
        BACKUP_SCHEDULE_ENABLED = 'false'
        BACKUP_SCHEDULE_CATCH_UP_ENABLED = 'false'
        BACKUP_RESTORE_DRILL_DATE = '2026-08-03T00:00:00Z'
        BACKUP_RESTORE_DRILL_RTO = 'PT2M'
    })
    $failed = $false
    try { & $importer -ConfigJsonPath $disabledSchedulePath -Confirm:$false 2>$null | Out-Null } catch { $failed = $true }
    Assert-True -Condition $failed -Message 'Enabled backups were accepted with the recurring schedule disabled.'
    Assert-True -Condition (Test-Path -LiteralPath $disabledSchedulePath) -Message 'Rejected disabled-schedule input was deleted.'

    $enablePath = Join-Path $testRoot 'enable-without-drill.json'
    Write-JsonFile -Path $enablePath -Value ([ordered]@{ BACKUP_ENABLED = 'true' })
    $failed = $false
    try { & $importer -ConfigJsonPath $enablePath -Confirm:$false 2>$null | Out-Null } catch { $failed = $true }
    Assert-True -Condition $failed -Message 'Backup enablement without restore evidence was accepted.'
    Assert-True -Condition (Test-Path -LiteralPath $enablePath) -Message 'Rejected enablement input was deleted.'

    $whatIfPath = Join-Path $testRoot 'what-if.json'
    Write-JsonFile -Path $whatIfPath -Value ([ordered]@{ BACKUP_S3_RETENTION_DAYS = '91' })
    & $importer -ConfigJsonPath $whatIfPath -WhatIf | Out-Null
    Assert-True -Condition (Test-Path -LiteralPath $whatIfPath) -Message 'WhatIf consumed the input JSON.'
    Assert-True -Condition ([IO.File]::ReadAllText($envPath) -ceq $beforeFailure) -Message 'WhatIf changed the env.'

    Write-Output 'Production backup configuration importer contract passed.'
} finally {
    [Environment]::SetEnvironmentVariable('OTZIV_ENV_DIR', $oldEnvDirectory)
    if (Test-Path -LiteralPath $testRoot) {
        Remove-Item -LiteralPath $testRoot -Recurse -Force
    }
}
