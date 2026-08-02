[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$DumpPath,

    [string]$DrillId = "",

    [ValidateNotNullOrEmpty()]
    [string]$MysqlImage = "mysql@sha256:8b879a3959bc59adcb7281a41950d39cf8c9b3fb23b87b9b62318ce884a7c383",

    [ValidateRange(30, 1800)]
    [int]$StartupTimeoutSeconds = 180,

    [ValidatePattern("^[A-Za-z0-9_]+$")]
    [string]$DatabaseName = "otziv_restore_drill",

    [string[]]$RequiredTables = @(
        "flyway_schema_history",
        "users",
        "companies",
        "orders",
        "reviews"
    )
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$ownerLabel = "com.otziv.restore-drill.id"
$kindLabel = "com.otziv.restore-drill.kind"

function Invoke-Docker {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$Arguments,

        [string]$Operation = "Docker command"
    )

    $output = @(& docker @Arguments 2>&1)
    $exitCode = $LASTEXITCODE
    $text = (($output | ForEach-Object { [string]$_ }) -join [Environment]::NewLine).Trim()
    if ($exitCode -ne 0) {
        $detail = if ([string]::IsNullOrWhiteSpace($text)) { "no diagnostic output" } else { $text }
        throw "$Operation failed (exit code $exitCode): $detail"
    }

    return $text
}

function Assert-LocalDockerEndpoint {
    if ($null -eq (Get-Command docker -ErrorAction SilentlyContinue)) {
        throw "Docker CLI was not found."
    }

    if (-not [string]::IsNullOrWhiteSpace($env:DOCKER_HOST) -and
        $env:DOCKER_HOST -notmatch "^(npipe|unix)://") {
        throw "Refusing non-local DOCKER_HOST '$($env:DOCKER_HOST)'. Use a local npipe:// or unix:// Docker engine."
    }

    $endpointOutput = Invoke-Docker -Arguments @(
        "context", "inspect", "--format", "{{.Endpoints.docker.Host}}"
    ) -Operation "Inspect active Docker context"
    $localEndpoints = @(
        $endpointOutput -split "`r?`n" |
            ForEach-Object { $_.Trim() } |
            Where-Object { $_ -match "^(npipe|unix)://" }
    )
    if ($localEndpoints.Count -ne 1) {
        $diagnostic = $endpointOutput -replace "[\r\n]+", " "
        throw "Refusing ambiguous or non-local Docker endpoint '$diagnostic'. Restore drills may run only against one local npipe:// or unix:// engine."
    }

    Invoke-Docker -Arguments @("version", "--format", "{{.Server.Version}}") `
        -Operation "Connect to local Docker engine" | Out-Null
}

function Test-ContainerExists {
    param([Parameter(Mandatory = $true)][string]$Name)

    $names = Invoke-Docker -Arguments @(
        "container", "ls", "--all", "--filter", "name=^/$Name$", "--format", "{{.Names}}"
    ) -Operation "Inspect drill container collision"
    return @($names -split "`r?`n") -contains $Name
}

function Test-VolumeExists {
    param([Parameter(Mandatory = $true)][string]$Name)

    $names = Invoke-Docker -Arguments @(
        "volume", "ls", "--filter", "name=^$Name$", "--format", "{{.Name}}"
    ) -Operation "Inspect drill volume collision"
    return @($names -split "`r?`n") -contains $Name
}

function Get-ContainerOwner {
    param([Parameter(Mandatory = $true)][string]$Name)

    return Invoke-Docker -Arguments @(
        "container", "inspect", "--format", ('{{ index .Config.Labels "' + $ownerLabel + '" }}'), $Name
    ) -Operation "Read drill container ownership"
}

function Get-VolumeOwner {
    param([Parameter(Mandatory = $true)][string]$Name)

    return Invoke-Docker -Arguments @(
        "volume", "inspect", "--format", ('{{ index .Labels "' + $ownerLabel + '" }}'), $Name
    ) -Operation "Read drill volume ownership"
}

function New-RandomHex {
    param([ValidateRange(16, 64)][int]$Bytes = 24)

    $buffer = New-Object byte[] $Bytes
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $rng.GetBytes($buffer)
    } finally {
        $rng.Dispose()
    }
    return ($buffer | ForEach-Object { $_.ToString("x2") }) -join ""
}

function Test-GzipArchive {
    param([Parameter(Mandatory = $true)][string]$Path)

    [long]$uncompressedBytes = 0
    $input = [System.IO.File]::OpenRead($Path)
    try {
        $gzip = [System.IO.Compression.GZipStream]::new(
            $input,
            [System.IO.Compression.CompressionMode]::Decompress
        )
        try {
            $buffer = New-Object byte[] (1024 * 1024)
            while (($read = $gzip.Read($buffer, 0, $buffer.Length)) -gt 0) {
                $uncompressedBytes += $read
            }
        } finally {
            $gzip.Dispose()
        }
    } finally {
        $input.Dispose()
    }

    if ($uncompressedBytes -le 0) {
        throw "The gzip archive expands to an empty SQL stream."
    }
    return $uncompressedBytes
}

function Wait-DrillMysql {
    param(
        [Parameter(Mandatory = $true)][string]$ContainerName,
        [Parameter(Mandatory = $true)][int]$TimeoutSeconds
    )

    $watch = [System.Diagnostics.Stopwatch]::StartNew()
    while ($watch.Elapsed.TotalSeconds -lt $TimeoutSeconds) {
        $state = Invoke-Docker -Arguments @(
            "container", "inspect", "--format", "{{.State.Status}}", $ContainerName
        ) -Operation "Inspect drill MySQL state"
        if ($state -in @("dead", "exited")) {
            $logs = Invoke-Docker -Arguments @("logs", "--tail", "50", $ContainerName) `
                -Operation "Read failed drill MySQL logs"
            throw "Drill MySQL stopped during initialization. Last logs: $logs"
        }

        & docker exec $ContainerName sh -lc `
            'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysqladmin --protocol=socket -uroot ping --silent' `
            *> $null
        if ($LASTEXITCODE -eq 0) {
            $watch.Stop()
            return $watch.Elapsed.TotalSeconds
        }
        Start-Sleep -Seconds 2
    }

    $watch.Stop()
    throw "Drill MySQL did not become ready within $TimeoutSeconds seconds."
}

function Invoke-MysqlQuery {
    param(
        [Parameter(Mandatory = $true)][string]$ContainerName,
        [Parameter(Mandatory = $true)][string]$Sql
    )

    $command = 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --protocol=socket -uroot "$MYSQL_DATABASE" --batch --skip-column-names -e "$1"'
    return Invoke-Docker -Arguments @(
        "exec", $ContainerName, "sh", "-lc", $command, "restore-drill", $Sql
    ) -Operation "Run drill integrity query"
}

function Get-IntegerQueryResult {
    param(
        [Parameter(Mandatory = $true)][string]$ContainerName,
        [Parameter(Mandatory = $true)][string]$Sql,
        [Parameter(Mandatory = $true)][string]$Description
    )

    $value = (Invoke-MysqlQuery -ContainerName $ContainerName -Sql $Sql).Trim()
    [long]$parsed = 0
    if (-not [long]::TryParse($value, [ref]$parsed)) {
        throw "$Description returned a non-integer value '$value'."
    }
    return $parsed
}

function Format-Seconds {
    param([Nullable[double]]$Value)

    if ($null -eq $Value) {
        return "NOT_ACHIEVED"
    }
    return ([double]$Value).ToString("0.000", [System.Globalization.CultureInfo]::InvariantCulture)
}

if ([string]::IsNullOrWhiteSpace($DrillId)) {
    $DrillId = "$(Get-Date -Format 'yyyyMMddHHmmss')-$([guid]::NewGuid().ToString('N').Substring(0, 12))"
}
if ($DrillId -notmatch "^[a-z0-9][a-z0-9-]{0,47}$") {
    throw "DrillId must match ^[a-z0-9][a-z0-9-]{0,47}$."
}
if ($MysqlImage -notmatch "^[A-Za-z0-9][A-Za-z0-9._/:@-]{0,254}$") {
    throw "MysqlImage contains unsupported characters."
}
foreach ($table in $RequiredTables) {
    if ($table -notmatch "^[A-Za-z0-9_]+$") {
        throw "Required table name '$table' contains unsupported characters."
    }
}

$resolvedDump = (Resolve-Path -LiteralPath $DumpPath -ErrorAction Stop).Path
$dumpFile = Get-Item -LiteralPath $resolvedDump
if ($dumpFile -isnot [System.IO.FileInfo] -or $dumpFile.Name -notmatch "(?i)\.sql\.gz$") {
    throw "DumpPath must point to a local .sql.gz file."
}
if ($dumpFile.Length -le 0) {
    throw "Backup file is empty: $resolvedDump"
}
if ($resolvedDump -match "^\\\\") {
    throw "UNC paths are not allowed. Copy the backup to a local disk before the drill."
}

Write-Host "Validating local gzip archive..."
$uncompressedBytes = Test-GzipArchive -Path $resolvedDump
$backupSha256 = (Get-FileHash -LiteralPath $resolvedDump -Algorithm SHA256).Hash

Assert-LocalDockerEndpoint
Invoke-Docker -Arguments @("image", "inspect", $MysqlImage) `
    -Operation "Inspect local MySQL image (the drill never pulls images)" | Out-Null

$containerName = "otziv-r0-$DrillId-mysql"
$volumeName = "otziv-r0-$DrillId-data"
$rootPassword = New-RandomHex
$containerCreated = $false
$volumeCreated = $false
$cleanupErrors = [System.Collections.Generic.List[string]]::new()
$failureRecord = $null
$result = "FAIL"
$cleanupResult = "PASS"
$readySeconds = $null
$restoreSeconds = $null
$validationSeconds = $null
$rtoSeconds = $null
$tableCount = $null
$flywayAppliedCount = $null
$flywayLatestVersion = "NOT_CHECKED"
$totalWatch = [System.Diagnostics.Stopwatch]::StartNew()

try {
    if (Test-ContainerExists -Name $containerName) {
        throw "Container collision: '$containerName' already exists. Nothing was changed."
    }
    if (Test-VolumeExists -Name $volumeName) {
        throw "Volume collision: '$volumeName' already exists. Nothing was changed."
    }

    Write-Host "Creating isolated volume: $volumeName"
    Invoke-Docker -Arguments @(
        "volume", "create",
        "--label", "$ownerLabel=$DrillId",
        "--label", "$kindLabel=mysql-data",
        $volumeName
    ) -Operation "Create isolated drill volume" | Out-Null
    $volumeCreated = $true
    if ((Get-VolumeOwner -Name $volumeName) -ne $DrillId) {
        throw "Volume ownership verification failed; refusing to continue."
    }

    Write-Host "Creating isolated MySQL container: $containerName"
    Invoke-Docker -Arguments @(
        "create", "--pull=never",
        "--name", $containerName,
        "--network", "none",
        "--label", "$ownerLabel=$DrillId",
        "--label", "$kindLabel=mysql",
        "--mount", "type=volume,src=$volumeName,dst=/var/lib/mysql",
        "--env", "MYSQL_ROOT_PASSWORD=$rootPassword",
        "--env", "MYSQL_DATABASE=$DatabaseName",
        "--env", "MYSQL_INITDB_SKIP_TZINFO=1",
        $MysqlImage,
        "--character-set-server=utf8mb4",
        "--collation-server=utf8mb4_unicode_ci",
        "--default-time-zone=+08:00",
        "--restrict-fk-on-non-standard-key=OFF"
    ) -Operation "Create isolated drill MySQL" | Out-Null
    $containerCreated = $true
    if ((Get-ContainerOwner -Name $containerName) -ne $DrillId) {
        throw "Container ownership verification failed; refusing to continue."
    }

    Invoke-Docker -Arguments @("start", $containerName) -Operation "Start drill MySQL" | Out-Null
    $readySeconds = Wait-DrillMysql -ContainerName $containerName -TimeoutSeconds $StartupTimeoutSeconds

    Invoke-Docker -Arguments @("cp", $resolvedDump, "${containerName}:/tmp/restore.sql.gz") `
        -Operation "Copy local backup into drill container" | Out-Null

    Write-Host "Restoring backup into isolated MySQL..."
    $restoreWatch = [System.Diagnostics.Stopwatch]::StartNew()
    $restoreCommand = 'set -eu; gzip -t /tmp/restore.sql.gz; gzip -dc /tmp/restore.sql.gz | MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --protocol=socket -uroot "$MYSQL_DATABASE"'
    Invoke-Docker -Arguments @("exec", $containerName, "sh", "-lc", $restoreCommand) `
        -Operation "Restore backup" | Out-Null
    $restoreWatch.Stop()
    $restoreSeconds = $restoreWatch.Elapsed.TotalSeconds

    Write-Host "Running schema, integrity, and Flyway checks..."
    $validationWatch = [System.Diagnostics.Stopwatch]::StartNew()
    if ((Invoke-MysqlQuery -ContainerName $containerName -Sql "SELECT 1").Trim() -ne "1") {
        throw "Database connectivity check failed."
    }

    $tableNamesText = Invoke-MysqlQuery -ContainerName $containerName `
        -Sql "SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE()"
    $tableNames = @($tableNamesText -split "`r?`n" | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
    $tableCount = $tableNames.Count
    if ($tableCount -eq 0) {
        throw "Restored database contains no tables."
    }
    $missingTables = @($RequiredTables | Where-Object { $tableNames -notcontains $_ })
    if ($missingTables.Count -gt 0) {
        throw "Required tables are missing: $($missingTables -join ', ')."
    }

    $quotedTableNames = $tableNames | ForEach-Object {
        "``$($_.Replace('`', '``'))``"
    }
    $checkOutput = Invoke-MysqlQuery -ContainerName $containerName `
        -Sql ("CHECK TABLE " + ($quotedTableNames -join ", "))
    $integrityFailures = @(
        $checkOutput -split "`r?`n" |
            ForEach-Object {
                $parts = $_ -split "`t", 4
                if ($parts.Count -ge 4 -and
                    ($parts[2] -eq "error" -or
                     ($parts[2] -eq "status" -and $parts[3] -ne "OK"))) {
                    $_
                }
            } |
            Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
    )
    if ($integrityFailures.Count -gt 0) {
        throw "CHECK TABLE reported failures: $($integrityFailures -join '; ')"
    }

    $flywayAppliedCount = Get-IntegerQueryResult -ContainerName $containerName `
        -Sql "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1" `
        -Description "Flyway applied migration count"
    $flywayFailedCount = Get-IntegerQueryResult -ContainerName $containerName `
        -Sql "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 0" `
        -Description "Flyway failed migration count"
    if ($flywayAppliedCount -eq 0) {
        throw "Flyway history contains no successful migrations."
    }
    if ($flywayFailedCount -ne 0) {
        throw "Flyway history contains $flywayFailedCount failed migration(s)."
    }
    $flywayLatestVersion = (Invoke-MysqlQuery -ContainerName $containerName `
        -Sql "SELECT version FROM flyway_schema_history WHERE success = 1 AND version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1").Trim()
    if ([string]::IsNullOrWhiteSpace($flywayLatestVersion)) {
        throw "Flyway history contains no successful versioned migration."
    }

    $validationWatch.Stop()
    $validationSeconds = $validationWatch.Elapsed.TotalSeconds
    $totalWatch.Stop()
    $rtoSeconds = $totalWatch.Elapsed.TotalSeconds
    $result = "PASS"
} catch {
    $failureRecord = $_
    if ($totalWatch.IsRunning) {
        $totalWatch.Stop()
    }
} finally {
    if ($containerCreated) {
        try {
            if (Test-ContainerExists -Name $containerName) {
                $actualOwner = Get-ContainerOwner -Name $containerName
                if ($actualOwner -ne $DrillId) {
                    throw "Refusing to remove '$containerName': ownership label mismatch."
                }
                Invoke-Docker -Arguments @("container", "rm", "--force", $containerName) `
                    -Operation "Remove exact drill container" | Out-Null
            }
        } catch {
            $cleanupErrors.Add($_.Exception.Message)
        }
    }

    if ($volumeCreated) {
        try {
            if (Test-VolumeExists -Name $volumeName) {
                $actualOwner = Get-VolumeOwner -Name $volumeName
                if ($actualOwner -ne $DrillId) {
                    throw "Refusing to remove '$volumeName': ownership label mismatch."
                }
                Invoke-Docker -Arguments @("volume", "rm", $volumeName) `
                    -Operation "Remove exact drill volume" | Out-Null
            }
        } catch {
            $cleanupErrors.Add($_.Exception.Message)
        }
    }

    if ($cleanupErrors.Count -gt 0) {
        $cleanupResult = "FAIL"
        $result = "FAIL"
    }

    $errorText = if ($null -eq $failureRecord) {
        if ($cleanupErrors.Count -gt 0) { $cleanupErrors -join "; " } else { "NONE" }
    } else {
        $failureRecord.Exception.Message
    }
    $errorText = $errorText -replace "[\r\n]+", " "

    Write-Host ""
    Write-Host "R0_RESTORE_DRILL_RESULT=$result"
    Write-Host "DRILL_ID=$DrillId"
    Write-Host "BACKUP_PATH=$resolvedDump"
    Write-Host "BACKUP_SHA256=$backupSha256"
    Write-Host "BACKUP_COMPRESSED_BYTES=$($dumpFile.Length)"
    Write-Host "BACKUP_UNCOMPRESSED_BYTES=$uncompressedBytes"
    Write-Host "MYSQL_IMAGE=$MysqlImage"
    Write-Host "MYSQL_READY_SECONDS=$(Format-Seconds -Value $readySeconds)"
    Write-Host "RESTORE_SECONDS=$(Format-Seconds -Value $restoreSeconds)"
    Write-Host "VALIDATION_SECONDS=$(Format-Seconds -Value $validationSeconds)"
    Write-Host "RTO_SECONDS=$(Format-Seconds -Value $rtoSeconds)"
    Write-Host "ELAPSED_SECONDS=$(Format-Seconds -Value $totalWatch.Elapsed.TotalSeconds)"
    Write-Host "TABLE_COUNT=$(if ($null -eq $tableCount) { 'NOT_CHECKED' } else { $tableCount })"
    Write-Host "FLYWAY_APPLIED_COUNT=$(if ($null -eq $flywayAppliedCount) { 'NOT_CHECKED' } else { $flywayAppliedCount })"
    Write-Host "FLYWAY_LATEST_VERSION=$flywayLatestVersion"
    Write-Host "CLEANUP_RESULT=$cleanupResult"
    Write-Host "ERROR=$errorText"
}

if ($null -ne $failureRecord) {
    throw $failureRecord
}
if ($cleanupErrors.Count -gt 0) {
    throw "Restore drill passed validation but cleanup failed: $($cleanupErrors -join '; ')"
}
