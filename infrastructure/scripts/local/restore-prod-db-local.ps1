param(
    [string]$VpsHost = "",
    [string]$VpsUser = "hunt",
    [ValidateRange(1, 65535)][int]$VpsPort = 22022,
    [string]$SshKey = "",
    [string]$EnvFile = ".env.prod-local",
    [string]$ComposeFile = "compose.prod-local.yaml",
    [string]$LocalMysqlVolume = "otziv-prod-local_mysql_data",
    [string]$DumpPath = "",
    [switch]$SkipDownload,
    [switch]$KeepRemoteDump,
    [switch]$KeepDownloadedDump,
    [switch]$RunSmoke,
    [ValidateRange(1, 100)][int]$LocalDumpRetentionCount = 1,
    [switch]$PruneExpiredLocalDumps,
    [switch]$KeepExpiredLocalDumps,
    [switch]$Help
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Show-Help {
    @'
Restore the production MySQL database into the local prod-like stack.

The script restores into a dedicated local Docker volume by default and validates
Flyway checksums before the local backend is started.

Example:
  .\infrastructure\scripts\local\restore-prod-db-local.ps1 -VpsHost 95.213.248.152 -VpsUser hunt -VpsPort 22022 -SshKey C:\Users\Hunt\.ssh\otziv_vps_ed25519

Useful options:
  -DumpPath .\data\mysql_backup\prod.sql.gz   Restore an already downloaded dump.
  -SkipDownload                               Do not connect to VPS; requires -DumpPath.
  -RunSmoke                                   Run prod-like smoke after restore.
  -KeepDownloadedDump                         Retain the newly downloaded plaintext
                                               gzip after a verified restore. By
                                               default it is removed immediately.
  -KeepExpiredLocalDumps                      Keep older downloaded dumps instead of
                                               pruning to -LocalDumpRetentionCount.
'@ | Write-Host
}

function Invoke-External {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [Parameter(Mandatory = $true)][string[]]$Arguments
    )

    & $FilePath @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Command failed: $(Format-RedactedCommand -FilePath $FilePath -Arguments $Arguments)"
    }
}

function Format-RedactedCommand {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [Parameter(Mandatory = $true)][string[]]$Arguments
    )

    $redacted = [System.Collections.Generic.List[string]]::new()
    $redactNext = $false
    foreach ($argument in $Arguments) {
        if ($redactNext) {
            $redacted.Add("[REDACTED]")
            $redactNext = $false
            continue
        }

        if ($argument -match '(?i)^(--password|--client-secret|--secret|--token|-p)$') {
            $redacted.Add($argument)
            $redactNext = $true
        } elseif ($argument -match '(?i)^([^=]*(?:password|passwd|pwd|secret|token|api[_-]?key)[^=]*)=(.*)$') {
            $redacted.Add("$($Matches[1])=[REDACTED]")
        } elseif ($argument -match '(?i)^-p.+$') {
            $redacted.Add('-p[REDACTED]')
        } else {
            $redacted.Add($argument)
        }
    }

    return ((@($FilePath) + @($redacted)) -join ' ')
}

function Invoke-ExternalCapture {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [Parameter(Mandatory = $true)][string[]]$Arguments
    )

    $output = @(& $FilePath @Arguments 2>&1)
    if ($LASTEXITCODE -ne 0) {
        $details = ($output | ForEach-Object { "$_" }) -join [Environment]::NewLine
        throw "Command failed: $(Format-RedactedCommand -FilePath $FilePath -Arguments $Arguments)`n$details"
    }
    return $output
}

function Protect-SensitiveLocalPath {
    param([Parameter(Mandatory = $true)][string]$Path)

    if ([System.Environment]::OSVersion.Platform -eq [System.PlatformID]::Win32NT) {
        $sid = [System.Security.Principal.WindowsIdentity]::GetCurrent().User.Value
        $grant = if (Test-Path -LiteralPath $Path -PathType Container) { "*${sid}:(OI)(CI)F" } else { "*${sid}:F" }
        & icacls.exe $Path '/inheritance:r' '/grant:r' $grant '/grant:r' '*S-1-5-18:F' | Out-Null
        if ($LASTEXITCODE -ne 0) {
            throw "Failed to restrict ACL on sensitive path: $Path"
        }
        return
    }

    $mode = if (Test-Path -LiteralPath $Path -PathType Container) { '700' } else { '600' }
    & chmod $mode -- $Path
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to restrict permissions on sensitive path: $Path"
    }
}

function Test-GzipArchive {
    param([Parameter(Mandatory = $true)][string]$Path)

    $inputStream = [System.IO.File]::OpenRead($Path)
    try {
        $gzipStream = [System.IO.Compression.GZipStream]::new(
            $inputStream,
            [System.IO.Compression.CompressionMode]::Decompress
        )
        try {
            $buffer = [byte[]]::new(1MB)
            [long]$uncompressedBytes = 0
            while (($read = $gzipStream.Read($buffer, 0, $buffer.Length)) -gt 0) {
                $uncompressedBytes += $read
            }
            if ($uncompressedBytes -le 0) {
                throw "Gzip archive is empty: $Path"
            }
        } finally {
            $gzipStream.Dispose()
        }
    } catch {
        throw "Invalid or truncated gzip archive '$Path': $($_.Exception.Message)"
    } finally {
        $inputStream.Dispose()
    }
}

function Invoke-LocalDumpRetention {
    param(
        [Parameter(Mandatory = $true)][string]$Directory,
        [Parameter(Mandatory = $true)][int]$KeepCount,
        [switch]$Prune
    )

    $dumps = @(Get-ChildItem -LiteralPath $Directory -File -Filter 'prod-*.sql.gz' |
        Sort-Object LastWriteTimeUtc -Descending)
    if ($dumps.Count -le $KeepCount) {
        return
    }

    $expired = @($dumps | Select-Object -Skip $KeepCount)
    if (-not $Prune) {
        $expiredNames = ($expired.Name -join ', ')
        Write-Warning "Dump retention limit is $KeepCount; $($expired.Count) older dump(s) remain. Review and rerun with -PruneExpiredLocalDumps to remove only these files: $expiredNames"
        return
    }

    foreach ($dump in $expired) {
        Remove-Item -LiteralPath $dump.FullName -Force
        Write-Host "Removed expired local dump: $($dump.Name)"
    }
}

function ConvertTo-BashSingleQuoted {
    param([Parameter(Mandatory = $true)][string]$Value)
    $singleQuote = [string][char]39
    $doubleQuote = [string][char]34
    $escapedSingleQuote = $singleQuote + $doubleQuote + $singleQuote + $doubleQuote + $singleQuote
    return $singleQuote + $Value.Replace($singleQuote, $escapedSingleQuote) + $singleQuote
}

function Read-EnvFile {
    param([Parameter(Mandatory = $true)][string]$Path)

    $values = @{}
    foreach ($line in Get-Content -LiteralPath $Path) {
        $trimmed = $line.Trim()
        if ($trimmed.Length -eq 0 -or $trimmed.StartsWith("#")) {
            continue
        }

        $parts = $trimmed.Split("=", 2)
        if ($parts.Length -eq 2) {
            $values[$parts[0].Trim()] = $parts[1].Trim()
        }
    }

    return $values
}

function Wait-ComposeServiceHealthy {
    param(
        [Parameter(Mandatory = $true)][string[]]$ComposeArguments,
        [Parameter(Mandatory = $true)][string]$Service,
        [int]$TimeoutSeconds = 180
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $containerId = & docker @($ComposeArguments + @("ps", "-q", $Service))
        if ($LASTEXITCODE -eq 0 -and -not [string]::IsNullOrWhiteSpace($containerId)) {
            $health = & docker @("inspect", "--format", "{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}", $containerId.Trim())
            if ($LASTEXITCODE -eq 0 -and $health.Trim() -eq "healthy") {
                return
            }
        }

        Start-Sleep -Seconds 3
    } while ((Get-Date) -lt $deadline)

    throw "Service '$Service' did not become healthy within $TimeoutSeconds seconds."
}

$script:Crc32Polynomial = [uint32]3988292384
$script:Crc32Table = for ($n = 0; $n -lt 256; $n++) {
    [uint32]$crc = $n
    for ($k = 0; $k -lt 8; $k++) {
        if (($crc -band 1) -ne 0) {
            $crc = [uint32]($script:Crc32Polynomial -bxor ($crc -shr 1))
        } else {
            $crc = [uint32]($crc -shr 1)
        }
    }
    $crc
}

function Get-FlywayChecksum {
    param([Parameter(Mandatory = $true)][string]$Path)

    [uint32]$crc = [uint32]::MaxValue
    $first = $true
    foreach ($line in [System.IO.File]::ReadLines($Path, [System.Text.Encoding]::UTF8)) {
        $current = $line
        if ($first) {
            $current = $current.TrimStart([char]0xfeff)
            $first = $false
        }

        foreach ($byte in [System.Text.Encoding]::UTF8.GetBytes($current)) {
            $index = ($crc -bxor [uint32]$byte) -band 0xff
            $crc = [uint32]($script:Crc32Table[$index] -bxor ($crc -shr 8))
        }
    }

    $unsigned = [uint32]($crc -bxor [uint32]::MaxValue)
    return [BitConverter]::ToInt32([BitConverter]::GetBytes($unsigned), 0)
}

function Get-LocalMigrationChecksums {
    param([Parameter(Mandatory = $true)][string]$MigrationDir)

    $checksums = @{}
    Get-ChildItem -LiteralPath $MigrationDir -Filter "V*.sql" | ForEach-Object {
        if ($_.Name -match "^V(.+)__.+\.sql$") {
            $version = $Matches[1].Replace("_", ".")
            $checksums[$version] = [pscustomobject]@{
                FileName = $_.Name
                Checksum = Get-FlywayChecksum -Path $_.FullName
            }
        }
    }

    return $checksums
}

function Test-LocalFlywayChecksums {
    param(
        [Parameter(Mandatory = $true)][string[]]$ComposeArguments,
        [Parameter(Mandatory = $true)][hashtable]$EnvValues,
        [Parameter(Mandatory = $true)][string]$MigrationDir
    )

    $mysqlUser = $EnvValues["MYSQL_USER"]
    $mysqlPassword = $EnvValues["MYSQL_PASSWORD"]
    $mysqlDatabase = $EnvValues["MYSQL_DATABASE"]
    if ([string]::IsNullOrWhiteSpace($mysqlUser) -or [string]::IsNullOrWhiteSpace($mysqlPassword) -or [string]::IsNullOrWhiteSpace($mysqlDatabase)) {
        throw "MYSQL_USER, MYSQL_PASSWORD, and MYSQL_DATABASE must be set in the local env file."
    }

    $local = Get-LocalMigrationChecksums -MigrationDir $MigrationDir
    $rows = & docker @($ComposeArguments + @(
        "exec", "-T", "-e", "MYSQL_PWD=$mysqlPassword", "mysql",
        "mysql", "-u$mysqlUser", $mysqlDatabase,
        "-N", "-B",
        "-e", "SELECT version, checksum FROM flyway_schema_history WHERE success = 1 AND checksum IS NOT NULL"
    ))
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to read local flyway_schema_history."
    }

    $mismatches = @()
    foreach ($row in $rows) {
        if ([string]::IsNullOrWhiteSpace($row)) {
            continue
        }

        $parts = $row -split "`t", 2
        if ($parts.Count -ne 2) {
            continue
        }

        $version = $parts[0]
        $appliedChecksum = $parts[1]
        if (-not $local.ContainsKey($version)) {
            $mismatches += "version ${version}: exists in copied DB with checksum ${appliedChecksum}, but local migration file is missing"
            continue
        }

        $resolved = $local[$version]
        if ([string]$resolved.Checksum -ne $appliedChecksum) {
            $mismatches += "$($resolved.FileName): copied DB checksum ${appliedChecksum}, local file checksum $($resolved.Checksum)"
        }
    }

    if ($mismatches.Count -gt 0) {
        $messageLines = @(
            "Flyway checksum validation failed after local DB restore."
            "Do not edit already-applied V__ migrations. Revert the old migration and create a new V__ migration for follow-up changes."
        )
        $messageLines += $mismatches | ForEach-Object { "  - $_" }
        $message = $messageLines -join [Environment]::NewLine
        throw $message
    }

    Write-Host "Local Flyway checksum validation passed."
}

function Disable-RestoredDbExternalMessaging {
    param(
        [Parameter(Mandatory = $true)][string[]]$ComposeArguments,
        [Parameter(Mandatory = $true)][hashtable]$EnvValues
    )

    $mysqlUser = $EnvValues["MYSQL_USER"]
    $mysqlPassword = $EnvValues["MYSQL_PASSWORD"]
    $mysqlDatabase = $EnvValues["MYSQL_DATABASE"]
    if ([string]::IsNullOrWhiteSpace($mysqlUser) -or [string]::IsNullOrWhiteSpace($mysqlPassword) -or [string]::IsNullOrWhiteSpace($mysqlDatabase)) {
        throw "MYSQL_USER, MYSQL_PASSWORD, and MYSQL_DATABASE must be set in the local env file."
    }

    $sql = @"
INSERT INTO app_settings (setting_key, setting_value, updated_at)
VALUES
  ('client.messages.live.enabled', 'false', NOW(6)),
  ('client.messages.payment-overdue.live-enabled', 'false', NOW(6)),
  ('client.messages.immediate.enabled', 'false', NOW(6)),
  ('client.messages.monitor.enabled', 'false', NOW(6)),
  ('publication.health-monitor.enabled', 'false', NOW(6)),
  ('telegram.reports.morning.enabled', 'false', NOW(6)),
  ('telegram.reports.evening.enabled', 'false', NOW(6)),
  ('whatsapp.group-sync.enabled', 'false', NOW(6)),
  ('archive.orders.schedule.worker.enabled', 'false', NOW(6)),
  ('archive.orders.schedule.enabled', 'false', NOW(6)),
  ('archive.orders.apply.enabled', 'false', NOW(6)),
  ('archive.orders.run.mode', 'dry-run', NOW(6)),
  ('payment.links.archive.enabled', 'false', NOW(6)),
  ('payments.tbank.runtime-mode', 'TEST', NOW(6)),
  ('payments.tbank.enabled', 'false', NOW(6)),
  ('payments.tbank.payment-links-enabled', 'false', NOW(6)),
  ('payments.tbank.manager-ui-enabled', 'false', NOW(6)),
  ('payments.tbank.apply-confirmed-payments', 'false', NOW(6)),
  ('payments.tbank.tpay-enabled', 'false', NOW(6)),
  ('payments.tbank.sberpay-enabled', 'false', NOW(6)),
  ('payments.tbank.mirpay-enabled', 'false', NOW(6)),
  ('contractor-payments.shadow-enabled', 'true', NOW(6)),
  ('contractor-payments.live-routing-enabled', 'false', NOW(6)),
  ('contractor-payments.reward-attribution-live-enabled', 'false', NOW(6)),
  ('contractor-payments.live-readiness-confirmed', 'false', NOW(6)),
  ('contractor-payments.completion-attribution-start-date', '', NOW(6)),
  ('workload.live.mode', 'SHADOW', NOW(6)),
  ('workload.live.apply-enabled', 'false', NOW(6)),
  ('client.messages.payment-instruction-source', 'MANAGER_TEXT', NOW(6))
ON DUPLICATE KEY UPDATE setting_value = VALUES(setting_value), updated_at = VALUES(updated_at);
"@

    Invoke-External -FilePath "docker" -Arguments ($ComposeArguments + @(
        "exec", "-T", "-e", "MYSQL_PWD=$mysqlPassword", "mysql",
        "mysql",
        "--default-character-set=utf8mb4",
        "-u$mysqlUser",
        $mysqlDatabase,
        "-e",
        $sql
    ))

    Write-Host "Restored local DB external messaging is disabled."
}

function Sanitize-RestoredExternalCredentials {
    param(
        [Parameter(Mandatory = $true)][string[]]$ComposeArguments,
        [Parameter(Mandatory = $true)][hashtable]$EnvValues
    )

    $mysqlUser = $EnvValues['MYSQL_USER']
    $mysqlPassword = $EnvValues['MYSQL_PASSWORD']
    $mysqlDatabase = $EnvValues['MYSQL_DATABASE']
    if ([string]::IsNullOrWhiteSpace($mysqlUser) `
            -or [string]::IsNullOrWhiteSpace($mysqlPassword) `
            -or [string]::IsNullOrWhiteSpace($mysqlDatabase)) {
        throw 'MYSQL_USER, MYSQL_PASSWORD, and MYSQL_DATABASE must be set before local credential sanitization.'
    }

    # Production credential envelopes deliberately use a different key from
    # prod-local. Never copy recoverable third-party passwords into the local
    # database and never require the production decryption key on a developer
    # machine. Bot passwords remain non-empty only to satisfy the legacy schema;
    # all local external messaging/browser automation is disabled separately.
    $sql = @"
UPDATE bots
SET bot_password = CONCAT('local-disabled-', bot_id);

UPDATE telephones
SET telephone_google_password = NULL,
    telephone_avito_password = NULL,
    telephone_mail_password = NULL;

UPDATE bad_review_tasks
SET bad_review_task_bot_password_snapshot = NULL;

UPDATE review_recovery_tasks
SET review_recovery_task_bot_password_snapshot = NULL;

UPDATE archive_bad_review_tasks
SET bad_review_task_bot_password_snapshot = NULL;

-- A production snapshot may contain contractor recipient envelopes encrypted
-- with a production-only key. Prod-like deliberately uses a distinct local
-- key, so remove recipient PII before the application can read or backfill it.
-- Financial amounts/statuses remain available for migration and accounting
-- tests; routes with removed recipient details fail closed locally.
SET @has_contractor_profiles = (
    SELECT COUNT(*)
    FROM information_schema.tables
    WHERE table_schema = DATABASE()
      AND table_name = 'contractor_payment_profiles'
);
SET @sanitize_contractor_profiles = IF(
    @has_contractor_profiles = 1,
    'UPDATE contractor_payment_profiles SET enabled = FALSE, recipient_name = NULL, payment_phone = NULL, bank_name = NULL, payment_comment = NULL',
    'SELECT 1'
);
PREPARE sanitize_contractor_profiles_statement FROM @sanitize_contractor_profiles;
EXECUTE sanitize_contractor_profiles_statement;
DEALLOCATE PREPARE sanitize_contractor_profiles_statement;

SET @has_contractor_live_enabled = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'contractor_payment_profiles'
      AND column_name = 'live_enabled'
);
SET @disable_contractor_live = IF(
    @has_contractor_live_enabled = 1,
    'UPDATE contractor_payment_profiles SET live_enabled = FALSE',
    'SELECT 1'
);
PREPARE disable_contractor_live_statement FROM @disable_contractor_live;
EXECUTE disable_contractor_live_statement;
DEALLOCATE PREPARE disable_contractor_live_statement;

SET @has_contractor_allocations = (
    SELECT COUNT(*)
    FROM information_schema.tables
    WHERE table_schema = DATABASE()
      AND table_name = 'contractor_payment_allocations'
);
SET @sanitize_contractor_allocations = IF(
    @has_contractor_allocations = 1,
    'UPDATE contractor_payment_allocations SET recipient_name_snapshot = NULL, payment_phone_snapshot = NULL, bank_name_snapshot = NULL',
    'SELECT 1'
);
PREPARE sanitize_contractor_allocations_statement FROM @sanitize_contractor_allocations;
EXECUTE sanitize_contractor_allocations_statement;
DEALLOCATE PREPARE sanitize_contractor_allocations_statement;

SET @has_contractor_allocation_comment = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'contractor_payment_allocations'
      AND column_name = 'payment_comment_snapshot'
);
SET @sanitize_contractor_allocation_comment = IF(
    @has_contractor_allocation_comment = 1,
    'UPDATE contractor_payment_allocations SET payment_comment_snapshot = NULL',
    'SELECT 1'
);
PREPARE sanitize_contractor_allocation_comment_statement FROM @sanitize_contractor_allocation_comment;
EXECUTE sanitize_contractor_allocation_comment_statement;
DEALLOCATE PREPARE sanitize_contractor_allocation_comment_statement;

SET @has_payment_link_actual_encrypted_snapshots = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'payment_links'
      AND column_name IN (
          'manual_actual_original_recipient_name_snapshot',
          'manual_actual_recipient_name_snapshot',
          'manual_actual_receipt_url'
      )
);
SET @sanitize_payment_link_actual_encrypted_snapshots = IF(
    @has_payment_link_actual_encrypted_snapshots = 3,
    'UPDATE payment_links SET manual_actual_original_recipient_name_snapshot = NULL, manual_actual_recipient_name_snapshot = NULL, manual_actual_receipt_url = NULL',
    'SELECT 1'
);
PREPARE sanitize_payment_link_actual_encrypted_snapshots_statement FROM @sanitize_payment_link_actual_encrypted_snapshots;
EXECUTE sanitize_payment_link_actual_encrypted_snapshots_statement;
DEALLOCATE PREPARE sanitize_payment_link_actual_encrypted_snapshots_statement;

SET @has_manual_payment_ledger_encrypted_snapshots = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'manual_payment_task_ledger_entries'
      AND column_name IN (
          'accounting_target_label_snapshot',
          'manual_phone_snapshot',
          'bank_recipient_name_snapshot',
          'manual_bank_name_snapshot',
          'manual_payment_url_snapshot'
      )
);
SET @sanitize_manual_payment_ledger_encrypted_snapshots = IF(
    @has_manual_payment_ledger_encrypted_snapshots = 5,
    'UPDATE manual_payment_task_ledger_entries SET accounting_target_label_snapshot = NULL, manual_phone_snapshot = NULL, bank_recipient_name_snapshot = NULL, manual_bank_name_snapshot = NULL, manual_payment_url_snapshot = NULL',
    'SELECT 1'
);
PREPARE sanitize_manual_payment_ledger_encrypted_snapshots_statement FROM @sanitize_manual_payment_ledger_encrypted_snapshots;
EXECUTE sanitize_manual_payment_ledger_encrypted_snapshots_statement;
DEALLOCATE PREPARE sanitize_manual_payment_ledger_encrypted_snapshots_statement;

SET @has_actual_attribution_encrypted_snapshots = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'contractor_actual_payment_attributions'
      AND column_name IN (
          'original_recipient_name_snapshot',
          'actual_recipient_name_snapshot',
          'receipt_url'
      )
);
SET @sanitize_actual_attribution_encrypted_snapshots = IF(
    @has_actual_attribution_encrypted_snapshots = 3,
    'UPDATE contractor_actual_payment_attributions SET original_recipient_name_snapshot = NULL, actual_recipient_name_snapshot = NULL, receipt_url = NULL',
    'SELECT 1'
);
PREPARE sanitize_actual_attribution_encrypted_snapshots_statement FROM @sanitize_actual_attribution_encrypted_snapshots;
EXECUTE sanitize_actual_attribution_encrypted_snapshots_statement;
DEALLOCATE PREPARE sanitize_actual_attribution_encrypted_snapshots_statement;
"@

    Invoke-External -FilePath 'docker' -Arguments ($ComposeArguments + @(
        'exec', '-T', '-e', "MYSQL_PWD=$mysqlPassword", 'mysql',
        'mysql',
        '--default-character-set=utf8mb4',
        "-u$mysqlUser",
        $mysqlDatabase,
        '-e',
        $sql
    ))

    Write-Host 'Restored local DB third-party passwords and contractor recipient details were replaced with non-production values.'
}

if ($Help) {
    Show-Help
    exit 0
}

$restoreMutex = [System.Threading.Mutex]::new($false, 'OtzivProdLikeDatabaseOperation')
$restoreLockHeld = $false
try {
try {
    $restoreLockHeld = $restoreMutex.WaitOne(0)
} catch [System.Threading.AbandonedMutexException] {
    $restoreLockHeld = $true
}
if (-not $restoreLockHeld) {
    throw 'Another prod-like smoke or production database restore is already running.'
}

if ($SkipDownload -and [string]::IsNullOrWhiteSpace($DumpPath)) {
    throw "Pass -DumpPath when using -SkipDownload."
}
if (-not $SkipDownload -and [string]::IsNullOrWhiteSpace($VpsHost)) {
    throw "Pass -VpsHost, or use -SkipDownload with -DumpPath."
}
if (-not $SkipDownload -and ($VpsHost -notmatch '^[A-Za-z0-9.-]+$' -or $VpsUser -notmatch '^[A-Za-z0-9._-]+$')) {
    throw 'VpsHost/VpsUser contain unsupported characters.'
}

$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = (Resolve-Path (Join-Path $scriptRoot "..\..\..")).Path
$envResolverPath = Join-Path $repoRoot "infrastructure\scripts\Resolve-OtzivEnvFile.ps1"
if (-not (Test-Path -LiteralPath $envResolverPath)) {
    throw "Env resolver script not found: $envResolverPath"
}
. $envResolverPath
$composePath = if ([System.IO.Path]::IsPathRooted($ComposeFile)) { $ComposeFile } else { Join-Path $repoRoot $ComposeFile }
$envPath = Resolve-OtzivEnvFile -EnvFile $EnvFile -RepoRoot $repoRoot
$migrationDir = Join-Path $repoRoot "backend\src\main\resources\db\migration"
$backupDir = Join-Path $repoRoot "data\mysql_backup"

if (-not (Test-Path -LiteralPath $composePath)) {
    throw "Compose file not found: $composePath"
}
Write-Host "Using env file: $envPath"
if (-not (Test-Path -LiteralPath $migrationDir)) {
    throw "Migration directory not found: $migrationDir"
}

New-Item -ItemType Directory -Path $backupDir -Force | Out-Null
Protect-SensitiveLocalPath -Path $backupDir

if ([string]::IsNullOrWhiteSpace($DumpPath)) {
    $timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $DumpPath = Join-Path $backupDir "prod-$timestamp.sql.gz"
}
$dumpFullPath = if ([System.IO.Path]::IsPathRooted($DumpPath)) { $DumpPath } else { Join-Path $repoRoot $DumpPath }
$dumpFileName = Split-Path -Leaf $dumpFullPath
$mountedDumpPath = Join-Path $backupDir $dumpFileName

if (-not $SkipDownload -and -not $KeepDownloadedDump) {
    $plannedBackupDirectory = [System.IO.Path]::GetFullPath($backupDir).TrimEnd('\')
    $plannedDumpParent = [System.IO.Path]::GetFullPath((Split-Path -Parent $dumpFullPath)).TrimEnd('\')
    if (-not $plannedDumpParent.Equals($plannedBackupDirectory, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw 'Ephemeral downloads must target data\mysql_backup. Use -KeepDownloadedDump for an explicit external path.'
    }
}

if (-not $SkipDownload) {
    $remote = "${VpsUser}@${VpsHost}"
    $sshArgs = @()
    $scpArgs = @()
    if (-not [string]::IsNullOrWhiteSpace($SshKey)) {
        $sshArgs += @("-i", $SshKey)
        $scpArgs += @("-i", $SshKey)
    }
    $sshArgs += @("-p", "$VpsPort", "-o", "StrictHostKeyChecking=accept-new")
    $scpArgs += @("-P", "$VpsPort", "-o", "StrictHostKeyChecking=accept-new")

    $remoteCommand = @'
set -Eeuo pipefail
umask 077
remote_dump="$(mktemp /tmp/otziv-prod.XXXXXXXX.sql.gz)"
cleanup_failed_dump() {
  status=$?
  trap - EXIT INT TERM
  rm -f -- "$remote_dump"
  exit "$status"
}
trap cleanup_failed_dump EXIT INT TERM
docker exec my-mysql sh -lc 'MYSQL_PWD="$MYSQL_PASSWORD" mysqldump --single-transaction --quick --routines --triggers --no-tablespaces -u"$MYSQL_USER" "$MYSQL_DATABASE"' | gzip -1 > "$remote_dump"
gzip -t "$remote_dump"
remote_sha="$(sha256sum "$remote_dump" | awk '{print $1}')"
remote_size="$(wc -c < "$remote_dump" | tr -d ' ')"
printf 'OTZIV_REMOTE_DUMP=%s\n' "$remote_dump"
printf 'OTZIV_REMOTE_SHA256=%s\n' "$remote_sha"
printf 'OTZIV_REMOTE_SIZE=%s\n' "$remote_size"
trap - EXIT INT TERM
'@
    # Git may check PowerShell files out with CRLF on Windows. Passing that
    # here-string directly as an SSH argument makes Bash read `pipefail\r`.
    $remoteCommand = $remoteCommand.Replace("`r`n", "`n").Replace("`r", "`n")

    Write-Host "Creating and validating production dump on VPS..."
    $remoteOutput = @(Invoke-ExternalCapture -FilePath "ssh" -Arguments ($sshArgs + @($remote, $remoteCommand)))
    $remoteDump = $null
    $remoteSha256 = $null
    [long]$remoteSize = 0
    foreach ($outputLine in $remoteOutput) {
        $line = "$outputLine"
        if ($line -match '^OTZIV_REMOTE_DUMP=(/tmp/otziv-prod\.[A-Za-z0-9]+\.sql\.gz)$') {
            $remoteDump = $Matches[1]
        } elseif ($line -match '^OTZIV_REMOTE_SHA256=([a-fA-F0-9]{64})$') {
            $remoteSha256 = $Matches[1].ToLowerInvariant()
        } elseif ($line -match '^OTZIV_REMOTE_SIZE=([0-9]+)$') {
            $remoteSize = [long]$Matches[1]
        }
    }
    if ([string]::IsNullOrWhiteSpace($remoteDump) -or [string]::IsNullOrWhiteSpace($remoteSha256) -or $remoteSize -le 0) {
        throw 'The VPS did not return valid dump integrity metadata.'
    }

    $remoteDumpQuoted = ConvertTo-BashSingleQuoted -Value $remoteDump
    $dumpParent = Split-Path -Parent $dumpFullPath
    New-Item -ItemType Directory -Path $dumpParent -Force | Out-Null
    $partialDumpPath = "$dumpFullPath.partial-$PID-$([Guid]::NewGuid().ToString('N'))"
    try {
        Write-Host "Downloading production dump to a temporary local file..."
        Invoke-External -FilePath "scp" -Arguments ($scpArgs + @("${remote}:$remoteDump", $partialDumpPath))
        Protect-SensitiveLocalPath -Path $partialDumpPath

        $downloadedFile = Get-Item -LiteralPath $partialDumpPath
        if ($downloadedFile.Length -ne $remoteSize) {
            throw "Downloaded dump size mismatch: expected $remoteSize bytes, got $($downloadedFile.Length)."
        }
        $downloadedSha256 = (Get-FileHash -LiteralPath $partialDumpPath -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($downloadedSha256 -ne $remoteSha256) {
            throw "Downloaded dump SHA-256 mismatch: expected $remoteSha256, got $downloadedSha256."
        }
        Test-GzipArchive -Path $partialDumpPath
        Move-Item -LiteralPath $partialDumpPath -Destination $dumpFullPath -Force
        Protect-SensitiveLocalPath -Path $dumpFullPath
        Write-Host "Production dump verified (SHA-256 $downloadedSha256, $remoteSize bytes): $dumpFullPath"
    } finally {
        if (Test-Path -LiteralPath $partialDumpPath) {
            Remove-Item -LiteralPath $partialDumpPath -Force
        }
        if (-not $KeepRemoteDump -and -not [string]::IsNullOrWhiteSpace($remoteDump)) {
            try {
                Invoke-External -FilePath "ssh" -Arguments ($sshArgs + @($remote, "rm -f -- $remoteDumpQuoted"))
            } catch {
                Write-Warning "Failed to remove temporary VPS dump $remoteDump. Remove it manually; permissions are restricted by umask 077. $($_.Exception.Message)"
            }
        } elseif ($KeepRemoteDump) {
            Write-Warning "Keeping the restricted temporary VPS dump by explicit request: $remoteDump"
        }
    }
} elseif (-not (Test-Path -LiteralPath $dumpFullPath)) {
    throw "Dump file not found: $dumpFullPath"
}

if (-not (Test-Path -LiteralPath $dumpFullPath)) {
    throw "Dump file not found after download: $dumpFullPath"
}
Protect-SensitiveLocalPath -Path $dumpFullPath
Test-GzipArchive -Path $dumpFullPath
$resolvedDumpPath = (Resolve-Path -LiteralPath $dumpFullPath).Path
$resolvedMountedDumpPath = if (Test-Path -LiteralPath $mountedDumpPath) { (Resolve-Path -LiteralPath $mountedDumpPath).Path } else { $null }
if ($resolvedDumpPath -ne $resolvedMountedDumpPath) {
    $mountedPartialPath = "$mountedDumpPath.partial-$PID-$([Guid]::NewGuid().ToString('N'))"
    try {
        Copy-Item -LiteralPath $dumpFullPath -Destination $mountedPartialPath -Force
        Protect-SensitiveLocalPath -Path $mountedPartialPath
        Test-GzipArchive -Path $mountedPartialPath
        Move-Item -LiteralPath $mountedPartialPath -Destination $mountedDumpPath -Force
        Protect-SensitiveLocalPath -Path $mountedDumpPath
    } finally {
        if (Test-Path -LiteralPath $mountedPartialPath) {
            Remove-Item -LiteralPath $mountedPartialPath -Force
        }
    }
}

$envValues = Read-EnvFile -Path $envPath
$previousVolumeEnv = $env:LOCAL_MYSQL_VOLUME
$env:LOCAL_MYSQL_VOLUME = $LocalMysqlVolume
$composeArgs = @("compose", "-f", $composePath, "--env-file", $envPath)

try {
    Write-Host "Using local MySQL volume: $LocalMysqlVolume"
    Invoke-External -FilePath "docker" -Arguments ($composeArgs + @("config", "--quiet"))

    Write-Host "Stopping local prod-like stack..."
    Invoke-External -FilePath "docker" -Arguments ($composeArgs + @("down"))

    $existingVolume = & docker volume ls -q --filter "name=^${LocalMysqlVolume}$"
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to inspect Docker volumes."
    }
    if (-not [string]::IsNullOrWhiteSpace($existingVolume)) {
        Write-Host "Removing existing local MySQL volume $LocalMysqlVolume..."
        Invoke-External -FilePath "docker" -Arguments @("volume", "rm", $LocalMysqlVolume)
    }

    Write-Host "Starting local MySQL..."
    Invoke-External -FilePath "docker" -Arguments ($composeArgs + @("up", "-d", "mysql"))
    Wait-ComposeServiceHealthy -ComposeArguments $composeArgs -Service "mysql"

    Write-Host "Restoring dump into local MySQL..."
    Invoke-External -FilePath "docker" -Arguments ($composeArgs + @(
        "exec", "-T", "mysql",
        "bash", "-o", "pipefail", "-c", "gzip -t /backup/$dumpFileName && gzip -dc /backup/$dumpFileName | MYSQL_PWD=`"`$MYSQL_PASSWORD`" mysql -u`"`$MYSQL_USER`" `"`$MYSQL_DATABASE`""
    ))

    Disable-RestoredDbExternalMessaging -ComposeArguments $composeArgs -EnvValues $envValues
    Sanitize-RestoredExternalCredentials -ComposeArguments $composeArgs -EnvValues $envValues
    Test-LocalFlywayChecksums -ComposeArguments $composeArgs -EnvValues $envValues -MigrationDir $migrationDir

    # A successful fresh restore is the point at which an older local dump is no
    # longer needed for rollback. Keep a bounded number by default so repeated
    # prod-like smoke runs do not accumulate plaintext production snapshots.
    $pruneLocalDumps = $PruneExpiredLocalDumps -or (-not $SkipDownload -and -not $KeepExpiredLocalDumps)
    Invoke-LocalDumpRetention `
        -Directory $backupDir `
        -KeepCount $LocalDumpRetentionCount `
        -Prune:$pruneLocalDumps

    if (-not $SkipDownload -and -not $KeepDownloadedDump) {
        $resolvedBackupDirectory = (Resolve-Path -LiteralPath $backupDir).Path.TrimEnd('\')
        $downloadedDump = (Resolve-Path -LiteralPath $dumpFullPath).Path
        $downloadedParent = (Split-Path -Parent $downloadedDump).TrimEnd('\')
        if (-not $downloadedParent.Equals($resolvedBackupDirectory, [System.StringComparison]::OrdinalIgnoreCase)) {
            throw "Refusing to auto-remove downloaded dump outside the protected backup directory: $downloadedDump"
        }
        Remove-Item -LiteralPath $downloadedDump -Force
        Write-Host "Removed ephemeral plaintext production dump after verified restore: $dumpFileName"
    }

    if ($RunSmoke) {
        $smokeScript = Join-Path $scriptRoot "prod-like-smoke.ps1"
        & $smokeScript -EnvFile $envPath -ComposeFile $composePath -NoBuild -SkipProdDbRestore
        if (-not $?) {
            throw "Local prod-like smoke failed."
        }
    } else {
        Write-Host "Restore complete. Run prod-like smoke when needed:"
        Write-Host ".\infrastructure\scripts\local\prod-like-smoke.ps1 -OfflineAppBuild"
    }
} finally {
    $env:LOCAL_MYSQL_VOLUME = $previousVolumeEnv
}
} finally {
    if ($restoreLockHeld) {
        $restoreMutex.ReleaseMutex()
    }
    $restoreMutex.Dispose()
}
