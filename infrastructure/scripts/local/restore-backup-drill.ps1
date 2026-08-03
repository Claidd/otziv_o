#requires -Version 7.0

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

    [string]$EncryptionKeyBase64 = $env:BACKUP_ENCRYPTION_KEY_BASE64,

    [ValidateRange(1048576, 1099511627776)]
    [long]$MaxDecryptedBytes = 536870912000,

    [ValidateRange(1048576, 17592186044416)]
    [long]$MaxUncompressedBytes = 1099511627776,

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
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][long]$MaximumUncompressedBytes
    )

    [long]$uncompressedBytes = 0
    $buffer = $null
    $input = [System.IO.File]::OpenRead($Path)
    try {
        $gzip = [System.IO.Compression.GZipStream]::new(
            $input,
            [System.IO.Compression.CompressionMode]::Decompress
        )
        try {
            $buffer = New-Object byte[] (1024 * 1024)
            while (($read = $gzip.Read($buffer, 0, $buffer.Length)) -gt 0) {
                if ($uncompressedBytes -gt ($MaximumUncompressedBytes - [long]$read)) {
                    throw "The gzip archive exceeds MaxUncompressedBytes ($MaximumUncompressedBytes bytes)."
                }
                $uncompressedBytes += $read
            }
        } finally {
            if ($null -ne $buffer) {
                [Array]::Clear($buffer, 0, $buffer.Length)
            }
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

function Read-ExactBytes {
    param(
        [Parameter(Mandatory = $true)][System.IO.Stream]$Stream,
        [Parameter(Mandatory = $true)][ValidateRange(1, 67108880)][int]$Count,
        [Parameter(Mandatory = $true)][string]$Description
    )

    $buffer = New-Object byte[] $Count
    $offset = 0
    while ($offset -lt $Count) {
        $read = $Stream.Read($buffer, $offset, $Count - $offset)
        if ($read -le 0) {
            throw "Encrypted backup ended while reading $Description."
        }
        $offset += $read
    }
    return ,$buffer
}

function Get-UInt32BigEndian {
    param(
        [Parameter(Mandatory = $true)][byte[]]$Buffer,
        [Parameter(Mandatory = $true)][int]$Offset
    )

    [uint64]$value = 0
    for ($index = 0; $index -lt 4; $index++) {
        $value = ($value -shl 8) -bor [uint64]$Buffer[$Offset + $index]
    }
    return [uint32]$value
}

function Get-UInt64BigEndian {
    param(
        [Parameter(Mandatory = $true)][byte[]]$Buffer,
        [Parameter(Mandatory = $true)][int]$Offset
    )

    [uint64]$value = 0
    for ($index = 0; $index -lt 8; $index++) {
        $value = ($value -shl 8) -bor [uint64]$Buffer[$Offset + $index]
    }
    return $value
}

function Set-UInt32BigEndian {
    param(
        [Parameter(Mandatory = $true)][byte[]]$Buffer,
        [Parameter(Mandatory = $true)][int]$Offset,
        [Parameter(Mandatory = $true)][uint32]$Value
    )

    $Buffer[$Offset] = [byte](($Value -shr 24) -band 0xff)
    $Buffer[$Offset + 1] = [byte](($Value -shr 16) -band 0xff)
    $Buffer[$Offset + 2] = [byte](($Value -shr 8) -band 0xff)
    $Buffer[$Offset + 3] = [byte]($Value -band 0xff)
}

function Get-OtzivDb2ChunkCount {
    param(
        [Parameter(Mandatory = $true)][uint64]$PlaintextLength,
        [Parameter(Mandatory = $true)][uint32]$ChunkSize
    )

    if ($PlaintextLength -eq 0 -or $ChunkSize -eq 0) {
        throw "OTZIVDB2 chunk count requires positive length and chunk size."
    }
    [decimal]$quotient = [decimal]$PlaintextLength / [decimal]$ChunkSize
    return [uint64][decimal]::Ceiling($quotient)
}

function ConvertFrom-OtzivDb2Envelope {
    param(
        [Parameter(Mandatory = $true)][string]$InputPath,
        [Parameter(Mandatory = $true)][string]$OutputPath,
        [Parameter(Mandatory = $true)][string]$EncodedKey,
        [Parameter(Mandatory = $true)][long]$MaximumPlaintextBytes
    )

    if ([string]::IsNullOrWhiteSpace($EncodedKey)) {
        throw "Encrypted OTZIVDB2 backup requires EncryptionKeyBase64 or BACKUP_ENCRYPTION_KEY_BASE64."
    }
    try {
        [byte[]]$key = [Convert]::FromBase64String($EncodedKey.Trim())
    } catch {
        throw "The backup encryption key is not valid Base64."
    }
    if ($key.Length -ne 32) {
        [Array]::Clear($key, 0, $key.Length)
        throw "The backup encryption key must decode to exactly 32 bytes."
    }

    $headerLength = 28
    $tagLength = 16
    $minimumChunkBytes = 64 * 1024
    $maximumChunkBytes = 64 * 1024 * 1024
    $input = $null
    $output = $null
    $aes = $null
    $outputCreated = $false
    $completed = $false
    try {
        $input = [System.IO.FileStream]::new(
            $InputPath,
            [System.IO.FileMode]::Open,
            [System.IO.FileAccess]::Read,
            [System.IO.FileShare]::Read,
            1024 * 1024,
            [System.IO.FileOptions]::SequentialScan
        )
        if ($input.Length -lt $headerLength) {
            throw "Encrypted backup is shorter than the OTZIVDB2 header."
        }
        [byte[]]$header = Read-ExactBytes -Stream $input -Count $headerLength -Description "OTZIVDB2 header"
        $magic = [Text.Encoding]::ASCII.GetString($header, 0, 8)
        if ($magic -eq "OTZIVDB1") {
            throw "OTZIVDB1 is a legacy single-message envelope and is not stream-restorable. Create an OTZIVDB2 backup."
        }
        if ($magic -ne "OTZIVDB2") {
            throw "Encrypted backup has an unsupported envelope magic."
        }

        [uint32]$chunkSize = Get-UInt32BigEndian -Buffer $header -Offset 8
        [uint64]$plaintextLength = Get-UInt64BigEndian -Buffer $header -Offset 12
        if ($chunkSize -lt $minimumChunkBytes -or $chunkSize -gt $maximumChunkBytes) {
            throw "OTZIVDB2 chunk size is outside the supported range."
        }
        if ($plaintextLength -eq 0 -or $plaintextLength -gt [uint64]$MaximumPlaintextBytes) {
            throw "OTZIVDB2 plaintext length is empty or exceeds MaxDecryptedBytes."
        }

        [uint64]$chunkCount = Get-OtzivDb2ChunkCount `
            -PlaintextLength $plaintextLength `
            -ChunkSize $chunkSize
        if ($chunkCount -gt [uint64][uint32]::MaxValue + 1) {
            throw "OTZIVDB2 contains too many encrypted chunks."
        }
        $expectedEnvelopeLength = [System.Numerics.BigInteger]$headerLength `
            + [System.Numerics.BigInteger]$plaintextLength `
            + ([System.Numerics.BigInteger]$chunkCount * $tagLength)
        if ($expectedEnvelopeLength -ne [System.Numerics.BigInteger]$input.Length) {
            throw "OTZIVDB2 file length does not match its header; the backup is truncated or has trailing data."
        }

        $output = [System.IO.FileStream]::new(
            $OutputPath,
            [System.IO.FileMode]::CreateNew,
            [System.IO.FileAccess]::Write,
            [System.IO.FileShare]::None,
            1024 * 1024,
            [System.IO.FileOptions]::SequentialScan -bor [System.IO.FileOptions]::WriteThrough
        )
        $outputCreated = $true
        if (-not $IsWindows) {
            [System.IO.File]::SetUnixFileMode(
                $OutputPath,
                [System.IO.UnixFileMode]::UserRead -bor [System.IO.UnixFileMode]::UserWrite
            )
        }

        # The single-argument constructor is available throughout supported
        # PowerShell 7.x/.NET runtimes; OTZIVDB2 always uses a 16-byte tag.
        $aes = [System.Security.Cryptography.AesGcm]::new($key)
        $noncePrefix = New-Object byte[] 8
        [Array]::Copy($header, 20, $noncePrefix, 0, 8)
        [uint64]$remaining = $plaintextLength
        for ([uint64]$chunkIndex = 0; $chunkIndex -lt $chunkCount; $chunkIndex++) {
            $plaintextBytes = [int][Math]::Min([uint64]$chunkSize, $remaining)
            [byte[]]$ciphertext = Read-ExactBytes -Stream $input -Count $plaintextBytes -Description "chunk $chunkIndex ciphertext"
            [byte[]]$tag = Read-ExactBytes -Stream $input -Count $tagLength -Description "chunk $chunkIndex authentication tag"
            $nonce = New-Object byte[] 12
            [Array]::Copy($noncePrefix, 0, $nonce, 0, 8)
            Set-UInt32BigEndian -Buffer $nonce -Offset 8 -Value ([uint32]$chunkIndex)
            $aad = New-Object byte[] ($headerLength + 8)
            [Array]::Copy($header, 0, $aad, 0, $headerLength)
            Set-UInt32BigEndian -Buffer $aad -Offset $headerLength -Value ([uint32]$chunkIndex)
            Set-UInt32BigEndian -Buffer $aad -Offset ($headerLength + 4) -Value ([uint32]$plaintextBytes)
            $plaintext = New-Object byte[] $plaintextBytes
            try {
                try {
                    $aes.Decrypt($nonce, $ciphertext, $tag, $plaintext, $aad)
                } catch [System.Security.Cryptography.CryptographicException] {
                    throw "OTZIVDB2 authentication failed for chunk $chunkIndex. The backup or key is invalid."
                }
                $output.Write($plaintext, 0, $plaintext.Length)
            } finally {
                [Array]::Clear($plaintext, 0, $plaintext.Length)
            }
            $remaining -= [uint64]$plaintextBytes
        }
        if ($remaining -ne 0 -or $input.Position -ne $input.Length) {
            throw "OTZIVDB2 did not end at its authenticated boundary."
        }
        $output.Flush($true)
        if ($output.Length -ne [long]$plaintextLength) {
            throw "Decrypted OTZIVDB2 length does not match its authenticated header."
        }
        $completed = $true
        return [pscustomobject]@{
            Format = "OTZIVDB2_CHUNKED_AES_256_GCM"
            PlaintextBytes = [long]$plaintextLength
            ChunkSizeBytes = [long]$chunkSize
            ChunkCount = [long]$chunkCount
        }
    } finally {
        if ($null -ne $aes) { $aes.Dispose() }
        if ($null -ne $output) { $output.Dispose() }
        if ($null -ne $input) { $input.Dispose() }
        [Array]::Clear($key, 0, $key.Length)
        if (-not $completed -and $outputCreated -and [System.IO.File]::Exists($OutputPath)) {
            [System.IO.File]::Delete($OutputPath)
        }
    }
}

function New-SensitiveTemporaryPath {
    $temporaryRoot = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath())
    if ($temporaryRoot.StartsWith("\\")) {
        throw "UNC temporary directories are not allowed for decrypted backups."
    }
    return [System.IO.Path]::Combine(
        $temporaryRoot,
        "otziv-restore-$([guid]::NewGuid().ToString('N')).sql.gz"
    )
}

function Remove-SensitiveTemporaryFile {
    param([Parameter(Mandatory = $true)][string]$Path)

    $fullPath = [System.IO.Path]::GetFullPath($Path)
    $temporaryRoot = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath())
    if (-not $fullPath.StartsWith($temporaryRoot, [StringComparison]::OrdinalIgnoreCase) -or
        [System.IO.Path]::GetFileName($fullPath) -notmatch '^otziv-restore-[a-f0-9]{32}\.sql\.gz$') {
        throw "Refusing to remove an unrecognized decrypted-backup path."
    }
    if ([System.IO.File]::Exists($fullPath)) {
        [System.IO.File]::Delete($fullPath)
    }
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
if ($dumpFile -isnot [System.IO.FileInfo] -or $dumpFile.Name -notmatch "(?i)\.sql\.gz(?:\.enc)?$") {
    throw "DumpPath must point to a local .sql.gz or OTZIVDB2 .sql.gz.enc file."
}
if ($dumpFile.Length -le 0) {
    throw "Backup file is empty: $resolvedDump"
}
if ($resolvedDump -match "^\\\\") {
    throw "UNC paths are not allowed. Copy the backup to a local disk before the drill."
}

$backupSha256 = (Get-FileHash -LiteralPath $resolvedDump -Algorithm SHA256).Hash
$isEncryptedBackup = $dumpFile.Name -match "(?i)\.enc$"
$restoreInputPath = $resolvedDump
$decryptedTemporaryPath = $null
$backupFormat = if ($isEncryptedBackup) { "ENCRYPTED_PENDING_VALIDATION" } else { "LEGACY_PLAIN_GZIP" }
$restoreCompressedBytes = $null
$uncompressedBytes = $null

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
    if ($isEncryptedBackup) {
        Write-Host "Authenticating and decrypting OTZIVDB2 backup to a restricted temporary gzip file..."
        $decryptedTemporaryPath = New-SensitiveTemporaryPath
        $envelope = ConvertFrom-OtzivDb2Envelope `
            -InputPath $resolvedDump `
            -OutputPath $decryptedTemporaryPath `
            -EncodedKey $EncryptionKeyBase64 `
            -MaximumPlaintextBytes $MaxDecryptedBytes
        $backupFormat = $envelope.Format
        $restoreInputPath = $decryptedTemporaryPath
    }

    Write-Host "Validating local gzip archive..."
    $restoreCompressedBytes = (Get-Item -LiteralPath $restoreInputPath).Length
    $uncompressedBytes = Test-GzipArchive `
        -Path $restoreInputPath `
        -MaximumUncompressedBytes $MaxUncompressedBytes

    Assert-LocalDockerEndpoint
    Invoke-Docker -Arguments @("image", "inspect", $MysqlImage) `
        -Operation "Inspect local MySQL image (the drill never pulls images)" | Out-Null

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

    Invoke-Docker -Arguments @("cp", $restoreInputPath, "${containerName}:/tmp/restore.sql.gz") `
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

    if (-not [string]::IsNullOrWhiteSpace($decryptedTemporaryPath)) {
        try {
            Remove-SensitiveTemporaryFile -Path $decryptedTemporaryPath
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
    Write-Host "BACKUP_FORMAT=$backupFormat"
    Write-Host "BACKUP_SHA256=$backupSha256"
    Write-Host "BACKUP_SOURCE_BYTES=$($dumpFile.Length)"
    Write-Host "BACKUP_COMPRESSED_BYTES=$(if ($null -eq $restoreCompressedBytes) { 'NOT_CHECKED' } else { $restoreCompressedBytes })"
    Write-Host "BACKUP_UNCOMPRESSED_BYTES=$(if ($null -eq $uncompressedBytes) { 'NOT_CHECKED' } else { $uncompressedBytes })"
    Write-Host "MAX_UNCOMPRESSED_BYTES=$MaxUncompressedBytes"
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
