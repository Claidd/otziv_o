param(
    [string]$VpsHost = "",
    [string]$VpsUser = "root",
    [int]$VpsPort = 22,
    [string]$SshKey = "",
    [string]$EnvFile = ".env.prod-local",
    [string]$ComposeFile = "compose.prod-local.yaml",
    [string]$LocalMysqlVolume = "otziv-prod-local_mysql_data",
    [string]$DumpPath = "",
    [switch]$SkipDownload,
    [switch]$KeepRemoteDump,
    [switch]$RunSmoke,
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
  .\infrastructure\scripts\local\restore-prod-db-local.ps1 -VpsHost 95.213.248.152 -SshKey C:\Users\Hunt\.ssh\otziv_vps_ed25519

Useful options:
  -DumpPath .\data\mysql_backup\prod.sql.gz   Restore an already downloaded dump.
  -SkipDownload                               Do not connect to VPS; requires -DumpPath.
  -RunSmoke                                   Run prod-like smoke after restore.
'@ | Write-Host
}

function Invoke-External {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [Parameter(Mandatory = $true)][string[]]$Arguments
    )

    & $FilePath @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Command failed: $FilePath $($Arguments -join ' ')"
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
        $message = @(
            "Flyway checksum validation failed after local DB restore.",
            "Do not edit already-applied V__ migrations. Revert the old migration and create a new V__ migration for follow-up changes.",
            ($mismatches | ForEach-Object { "  - $_" })
        ) -join [Environment]::NewLine
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

if ($Help) {
    Show-Help
    exit 0
}

if ($SkipDownload -and [string]::IsNullOrWhiteSpace($DumpPath)) {
    throw "Pass -DumpPath when using -SkipDownload."
}
if (-not $SkipDownload -and [string]::IsNullOrWhiteSpace($VpsHost)) {
    throw "Pass -VpsHost, or use -SkipDownload with -DumpPath."
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

if ([string]::IsNullOrWhiteSpace($DumpPath)) {
    $timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $DumpPath = Join-Path $backupDir "prod-$timestamp.sql.gz"
}
$dumpFullPath = if ([System.IO.Path]::IsPathRooted($DumpPath)) { $DumpPath } else { Join-Path $repoRoot $DumpPath }
$dumpFileName = Split-Path -Leaf $dumpFullPath
$mountedDumpPath = Join-Path $backupDir $dumpFileName

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

    $remoteStamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $remoteDump = "/tmp/otziv-prod-$remoteStamp.sql.gz"
    $remoteDumpQuoted = ConvertTo-BashSingleQuoted -Value $remoteDump
    $remoteCommand = "set -Eeuo pipefail; docker exec my-mysql sh -lc 'MYSQL_PWD=`"`$MYSQL_PASSWORD`" mysqldump --single-transaction --quick --routines --triggers --no-tablespaces -u`"`$MYSQL_USER`" `"`$MYSQL_DATABASE`"' | gzip -1 > $remoteDumpQuoted; ls -lh $remoteDumpQuoted"

    Write-Host "Creating production dump on VPS..."
    Invoke-External -FilePath "ssh" -Arguments ($sshArgs + @($remote, $remoteCommand))
    Write-Host "Downloading dump to $dumpFullPath..."
    Invoke-External -FilePath "scp" -Arguments ($scpArgs + @("${remote}:$remoteDump", $dumpFullPath))
    if (-not $KeepRemoteDump) {
        Invoke-External -FilePath "ssh" -Arguments ($sshArgs + @($remote, "rm -f $remoteDumpQuoted"))
    }
} elseif (-not (Test-Path -LiteralPath $dumpFullPath)) {
    throw "Dump file not found: $dumpFullPath"
}

if (-not (Test-Path -LiteralPath $dumpFullPath)) {
    throw "Dump file not found after download: $dumpFullPath"
}
$resolvedDumpPath = (Resolve-Path -LiteralPath $dumpFullPath).Path
$resolvedMountedDumpPath = if (Test-Path -LiteralPath $mountedDumpPath) { (Resolve-Path -LiteralPath $mountedDumpPath).Path } else { $null }
if ($resolvedDumpPath -ne $resolvedMountedDumpPath) {
    Copy-Item -LiteralPath $dumpFullPath -Destination $mountedDumpPath -Force
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
        "sh", "-lc", "gzip -dc /backup/$dumpFileName | MYSQL_PWD=`"`$MYSQL_PASSWORD`" mysql -u`"`$MYSQL_USER`" `"`$MYSQL_DATABASE`""
    ))

    Disable-RestoredDbExternalMessaging -ComposeArguments $composeArgs -EnvValues $envValues
    Test-LocalFlywayChecksums -ComposeArguments $composeArgs -EnvValues $envValues -MigrationDir $migrationDir

    if ($RunSmoke) {
        $smokeScript = Join-Path $scriptRoot "prod-like-smoke.ps1"
        & $smokeScript -EnvFile $envPath -ComposeFile $composePath -NoBuild
        if ($LASTEXITCODE -ne 0) {
            throw "Local prod-like smoke failed."
        }
    } else {
        Write-Host "Restore complete. Run prod-like smoke when needed:"
        Write-Host ".\infrastructure\scripts\local\prod-like-smoke.ps1 -OfflineAppBuild"
    }
} finally {
    $env:LOCAL_MYSQL_VOLUME = $previousVolumeEnv
}
