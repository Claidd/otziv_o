param(
    [string]$VpsHost = "",
    [string]$VpsUser = "hunt",
    [ValidateRange(1, 65535)][int]$VpsPort = 22022,
    [string]$SshKey = "",
    [string]$EnvFile = ".env.prod-local",
    [string]$ComposeFile = "compose.prod-local.yaml",
    [string]$LocalMysqlVolume = "otziv-prod-local_mysql_973_data",
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
PowerShell 7 is required. The default volume is otziv-prod-local_mysql_973_data;
legacy MySQL 9.0 volumes are preserved. Gzip decompression runs on the host.

Example:
  .\infrastructure\scripts\local\restore-prod-db-local.ps1 -VpsHost 95.213.248.152 -VpsUser hunt -VpsPort 22022

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

function Get-LocalRestoreDockerJson {
    param([Parameter(Mandatory)][string[]]$Arguments)

    # Compose config contains credentials. Neither its output nor Docker errors
    # belong in a restore log, including when parsing or validation fails.
    $output = @(& docker @Arguments 2>$null)
    if ($LASTEXITCODE -ne 0) { throw 'Local restore metadata inspection failed.' }
    try { return (($output -join "`n") | ConvertFrom-Json -AsHashtable) }
    catch { throw 'Local restore metadata is not valid JSON.' }
}

function Assert-LocalRestoreContract {
    param(
        [Parameter(Mandatory)][string[]]$ComposeArguments,
        [Parameter(Mandatory)][string]$RepoRoot,
        [Parameter(Mandatory)][string]$ComposePath,
        [Parameter(Mandatory)][string]$VolumeName
    )

    if ($PSVersionTable.PSVersion.Major -lt 7) { throw 'Local restore requires PowerShell 7 for binary process input.' }
    $expectedCompose = [IO.Path]::GetFullPath((Join-Path $RepoRoot 'compose.prod-local.yaml'))
    if ([IO.Path]::GetFullPath($ComposePath) -ne $expectedCompose) { throw 'Restore is restricted to this workspace compose.prod-local.yaml.' }
    if ($VolumeName -cnotmatch '^otziv-prod-local_mysql_973_[a-z0-9][a-z0-9_-]*$') {
        throw 'Restore requires a dedicated otziv-prod-local_mysql_973_ volume; legacy MySQL volumes are never reused or removed.'
    }
    $image = 'ghcr.io/claidd/otziv-security@sha256:3a3caaab4e71b3bfdec9da21c17c00ed10ca237151919aeddac8e5ce4b8b7baa'
    $configuration = Get-LocalRestoreDockerJson -Arguments ($ComposeArguments + @('config', '--format', 'json'))
    $mysql = $configuration.services.mysql
    $volume = $configuration.volumes.mysql_data
    $expectedCommand = @('mysqld', '--user=999', '--character-set-server=utf8mb4', '--collation-server=utf8mb4_unicode_ci', '--default-time-zone=+08:00', '--restrict-fk-on-non-standard-key=OFF', '--gtid-mode=OFF', '--enforce-gtid-consistency=OFF', '--log-bin=mysql-bin', '--binlog-format=ROW', '--event-scheduler=OFF')
    if ($configuration.name -cne 'otziv-prod-local' -or $mysql.image -cne $image -or $mysql.user -cne '999:999' -or
        $null -ne $mysql['entrypoint'] -or (($mysql.command -join "`n") -cne ($expectedCommand -join "`n"))) {
        throw 'Local MySQL project, image or native UID 999 launch contract differs from the reviewed 9.7.3 configuration.'
    }
    if ($volume.name -cne $VolumeName -or $volume['external'] -eq $true -or $volume['driver_opts'] -or
        ($volume['driver'] -and $volume['driver'] -cne 'local')) { throw 'Local MySQL volume declaration is not a dedicated local named volume.' }
    $dataMounts = @($mysql.volumes | Where-Object { $_.target -eq '/var/lib/mysql' })
    if ($dataMounts.Count -ne 1 -or $dataMounts[0].type -cne 'volume' -or $dataMounts[0].source -cne 'mysql_data' -or
        $dataMounts[0]['read_only'] -eq $true -or $dataMounts[0]['volume']['nocopy'] -ne $true -or $dataMounts[0]['volume']['subpath']) {
        throw 'Local MySQL data must use the dedicated volume with nocopy and no subpath.'
    }
    if (@($mysql.tmpfs).Count -ne 1 -or $mysql.tmpfs[0] -cne '/var/run/mysqld:rw,noexec,nosuid,size=16m,uid=999,gid=999,mode=0755' -or
        @($mysql.volumes | Where-Object { $_.target -notin @('/var/lib/mysql', '/backup', '/var/lib/mysql-files') }).Count -ne 0) {
        throw 'Local MySQL runtime mounts differ from the reviewed native configuration.'
    }
    foreach ($binding in @(@{ Target = '/backup'; Directory = 'data/mysql_backup' }, @{ Target = '/var/lib/mysql-files'; Directory = 'data/bots' })) {
        $mounts = @($mysql.volumes | Where-Object { $_.target -ceq $binding.Target })
        if ($mounts.Count -ne 1 -or $mounts[0].type -cne 'bind' -or
            [IO.Path]::GetFullPath($mounts[0].source) -ne [IO.Path]::GetFullPath((Join-Path $RepoRoot $binding.Directory)) -or
            ($binding.Target -ceq '/var/lib/mysql-files' -and $mounts[0]['read_only'] -eq $true)) {
            throw 'Local MySQL auxiliary binds must resolve to this workspace backup and writable bots directories.'
        }
    }
    $imageMetadata = @(Get-LocalRestoreDockerJson -Arguments @('image', 'inspect', $image))[0]
    if ($imageMetadata.Id -notin @('sha256:3a3caaab4e71b3bfdec9da21c17c00ed10ca237151919aeddac8e5ce4b8b7baa', 'sha256:80ee3b50147a329addbaf754abc4dce86dc06636624680d780351e2320a11070') -or
        $imageMetadata.Os -cne 'linux' -or $imageMetadata.Architecture -cne 'amd64' -or
        ($imageMetadata.Config.Entrypoint -join "`n") -cne '/entrypoint.sh' -or $image -notin $imageMetadata.RepoDigests) {
        throw 'The reviewed local MySQL image is not present with the expected immutable identity.'
    }
    $containers = @(& docker ps -aq --no-trunc --filter 'label=com.docker.compose.project=otziv-prod-local' 2>$null)
    if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect local Compose project ownership.' }
    $managedContainers = @(& docker ps -aq --no-trunc --filter 'label=com.docker.compose.project=otziv-prod-local' --filter 'label=com.docker.compose.config-hash' 2>$null)
    if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect local Compose managed containers.' }
    $composeContainers = @(& docker @($ComposeArguments + @('ps', '--all', '--quiet')) 2>$null)
    if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect the local Compose operation inventory.' }
    foreach ($inventory in @(@{ Ids = $containers }, @{ Ids = $managedContainers }, @{ Ids = $composeContainers })) {
        if (@($inventory.Ids | Where-Object { $_ -cnotmatch '^[a-f0-9]{64}$' }).Count -gt 0 -or
            @($inventory.Ids | Sort-Object -Unique).Count -ne $inventory.Ids.Count) {
            throw 'Local Compose ownership inventory contains invalid or duplicate identities.'
        }
    }
    if ((($managedContainers | Sort-Object) -join "`n") -cne (($composeContainers | Sort-Object) -join "`n") -or
        @($managedContainers | Where-Object { $_ -notin $containers }).Count -gt 0) {
        throw 'Compose and Docker disagree about the local managed container inventory.'
    }
    $unmanagedProjectContainers = $false
    foreach ($containerId in $containers) {
        $container = @(Get-LocalRestoreDockerJson -Arguments @('inspect', "$containerId"))[0]
        $labels = $container.Config.Labels
        if ($container.Id -cne $containerId -or $labels['com.docker.compose.project'] -cne 'otziv-prod-local') {
            throw 'Local Compose container identity changed during ownership inspection.'
        }
        if ($containerId -notin $managedContainers) {
            # Compose 5.5.0 create/down/restart select project + config-hash,
            # but its start phase also selects project + oneoff=False. Only
            # containers without BOTH labels are outside all reviewed paths.
            # Never ignore a malformed/empty label or a foreign managed replica.
            if ($labels.ContainsKey('com.docker.compose.config-hash') -or $labels.ContainsKey('com.docker.compose.oneoff')) {
                throw 'An unverified local project container can be selected by Compose start or reconciliation.'
            }
            $unmanagedProjectContainers = $true
            continue
        }
        if (-not $labels['com.docker.compose.project.working_dir'] -or -not $labels['com.docker.compose.project.config_files'] -or
            [IO.Path]::GetFullPath($labels['com.docker.compose.project.working_dir']) -ne [IO.Path]::GetFullPath($RepoRoot) -or
            [IO.Path]::GetFullPath($labels['com.docker.compose.project.config_files']) -ne $expectedCompose) {
            throw 'A local Compose project container belongs to another workspace/configuration; refusing to stop it.'
        }
    }
    if ($unmanagedProjectContainers) {
        $composeVersion = @(& docker compose version --short 2>$null)
        if ($LASTEXITCODE -ne 0 -or ($composeVersion -join "`n") -cne '5.5.0') {
            throw 'Project-only containers require the independently verified Docker Compose 5.5.0 selectors.'
        }
    }
    $existing = @(& docker volume ls -q --filter "name=^${VolumeName}$" 2>$null)
    if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect local MySQL volumes.' }
    if ($existing.Count -gt 0) {
        $metadata = @(Get-LocalRestoreDockerJson -Arguments @('volume', 'inspect', $VolumeName))[0]
        if ($metadata.Name -cne $VolumeName -or $metadata.Driver -cne 'local' -or ($metadata.Options -and $metadata.Options.Count -gt 0) -or
            $metadata.Labels['com.docker.compose.project'] -cne 'otziv-prod-local' -or
            $metadata.Labels['com.docker.compose.volume'] -cne 'mysql_data' -or
            $metadata.Labels['com.otziv.mysql.local-engine'] -cne '9.7.3') {
            throw 'Existing local MySQL volume lacks verified 9.7.3 restore ownership; it will not be deleted.'
        }
        $users = @(& docker ps -aq --filter "volume=$VolumeName" 2>$null)
        if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect local MySQL volume users.' }
        foreach ($containerId in $users) {
            $container = @(Get-LocalRestoreDockerJson -Arguments @('inspect', "$containerId"))[0]
            if ($container.Config.Labels['com.docker.compose.project'] -cne 'otziv-prod-local' -or
                $container.Config.Labels['com.docker.compose.service'] -cne 'mysql' -or $container.Config.Image -cne $image) {
                throw 'A foreign or legacy MySQL container uses the selected volume; refusing to remove it.'
            }
        }
    }
    return @{ Image = $image; VolumeExists = ($existing.Count -gt 0) }
}

function Initialize-EmptyLocalMySqlVolume {
    param([Parameter(Mandatory)][string]$VolumeName, [Parameter(Mandatory)][string]$Image)

    if ($VolumeName -cnotmatch '^otziv-prod-local_mysql_973_[a-z0-9][a-z0-9_-]*$' -or
        $Image -cne 'ghcr.io/claidd/otziv-security@sha256:3a3caaab4e71b3bfdec9da21c17c00ed10ca237151919aeddac8e5ce4b8b7baa') {
        throw 'Empty-volume initialization requires the reviewed local 9.7.3 image and versioned volume.'
    }
    $existing = @(& docker volume ls -q --filter "name=^${VolumeName}$" 2>$null)
    if ($LASTEXITCODE -ne 0 -or $existing.Count -gt 0) { throw 'Empty-volume initialization requires an absent volume.' }
    $owner = [Guid]::NewGuid().ToString('N')
    Invoke-External -FilePath 'docker' -Arguments @('volume', 'create', '--label', 'com.docker.compose.project=otziv-prod-local', '--label', 'com.docker.compose.volume=mysql_data', '--label', 'com.otziv.mysql.local-engine=9.7.3', '--label', "com.otziv.mysql.restore-owner=$owner", $VolumeName)
    $metadata = @(Get-LocalRestoreDockerJson -Arguments @('volume', 'inspect', $VolumeName))[0]
    if ($metadata.Labels['com.otziv.mysql.restore-owner'] -cne $owner -or $metadata.Driver -cne 'local' -or ($metadata.Options -and $metadata.Options.Count -gt 0)) {
        throw 'New local MySQL volume ownership changed before initialization.'
    }
    # Only the root of a new, empty named volume is changed. The fixed command
    # fails before chown if any datadir content appeared; never recursive chown.
    Invoke-External -FilePath 'docker' -Arguments @('run', '--rm', '--pull=never', '--network', 'none', '--user', '0:0', '--mount', "type=volume,source=$VolumeName,target=/data,volume-nocopy", '--entrypoint', 'sh', $Image, '-c', 'test -z "$(ls -A /data)" && chown 999:999 /data && test "$(stat -c %u:%g /data)" = 999:999')
}

function Import-LocalMySqlGzipStream {
    param(
        [Parameter(Mandatory)][string[]]$ComposeArguments,
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][ValidatePattern('^[a-f0-9]{64}$')][string]$ExpectedSha256,
        [ValidateRange(1, 3600)][int]$TimeoutSeconds = 900
    )

    $inputStream = [IO.File]::Open($Path, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::Read)
    $process = $null
    $processStarted = $false
    try {
        $hash = [Security.Cryptography.SHA256]::Create()
        try { $actualHash = [Convert]::ToHexString($hash.ComputeHash($inputStream)).ToLowerInvariant() }
        finally { $hash.Dispose() }
        if ($actualHash -cne $ExpectedSha256) { throw 'The verified local dump changed before import.' }
        $inputStream.Position = 0
        # Validate all gzip bytes before starting mysql. Hold the same read-only
        # file handle until import ends and send bytes without text re-encoding.
        $verification = [IO.Compression.GZipStream]::new($inputStream, [IO.Compression.CompressionMode]::Decompress, $true)
        try {
            $buffer = [byte[]]::new(1MB)
            [long]$verifiedBytes = 0
            while (($count = $verification.Read($buffer, 0, $buffer.Length)) -gt 0) { $verifiedBytes += $count }
            if ($verifiedBytes -eq 0) { throw 'The local gzip dump is empty.' }
        } finally { $verification.Dispose() }
        $inputStream.Position = 0
        $info = [Diagnostics.ProcessStartInfo]::new()
        $info.FileName = 'docker'
        $info.UseShellExecute = $false
        $info.CreateNoWindow = $true
        $info.RedirectStandardInput = $true
        $info.RedirectStandardOutput = $true
        $info.RedirectStandardError = $true
        $arguments = $ComposeArguments + @('exec', '-T', 'mysql', 'sh', '-c', 'MYSQL_PWD="$MYSQL_PASSWORD" exec mysql -u"$MYSQL_USER" "$MYSQL_DATABASE"')
        foreach ($argument in $arguments) { [void]$info.ArgumentList.Add($argument) }
        $process = [Diagnostics.Process]::new()
        $process.StartInfo = $info
        $processStarted = $process.Start()
        if (-not $processStarted) { throw 'Cannot start local MySQL import.' }
        # Drain and discard both streams: failed SQL can contain production data.
        $stdout = $process.StandardOutput.BaseStream.CopyToAsync([IO.Stream]::Null)
        $stderr = $process.StandardError.BaseStream.CopyToAsync([IO.Stream]::Null)
        $timer = [Diagnostics.Stopwatch]::StartNew()
        $gzip = [IO.Compression.GZipStream]::new($inputStream, [IO.Compression.CompressionMode]::Decompress, $true)
        try {
            $copy = $gzip.CopyToAsync($process.StandardInput.BaseStream)
            if (-not $copy.Wait($TimeoutSeconds * 1000)) { throw 'Local MySQL import stream timed out.' }
            $copy.GetAwaiter().GetResult()
        } finally { $gzip.Dispose(); $process.StandardInput.Close() }
        $remaining = [Math]::Max(1, ($TimeoutSeconds * 1000) - $timer.ElapsedMilliseconds)
        if (-not $process.WaitForExit([int]$remaining)) { throw 'Local MySQL import process timed out.' }
        $stdout.GetAwaiter().GetResult()
        $stderr.GetAwaiter().GetResult()
        if ($process.ExitCode -ne 0) { throw 'Local MySQL import failed; source SQL and credentials are not logged.' }
    } finally {
        if ($null -ne $process) {
            if ($processStarted -and -not $process.HasExited) { $process.Kill($true); [void]$process.WaitForExit(10000) }
            $process.Dispose()
        }
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
if ([string]::IsNullOrWhiteSpace($SshKey)) {
    $SshKey = Join-Path (Get-OtzivSshDirectory -RepoRoot $repoRoot) "otziv_vps_ed25519"
}
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
$verifiedDumpSha256 = (Get-FileHash -LiteralPath $dumpFullPath -Algorithm SHA256).Hash.ToLowerInvariant()
if (-not $SkipDownload -and $verifiedDumpSha256 -cne $remoteSha256) { throw 'The downloaded dump changed after verification.' }
$resolvedDumpPath = (Resolve-Path -LiteralPath $dumpFullPath).Path
$resolvedMountedDumpPath = if (Test-Path -LiteralPath $mountedDumpPath) { (Resolve-Path -LiteralPath $mountedDumpPath).Path } else { $null }
if ($resolvedDumpPath -ne $resolvedMountedDumpPath) {
    $mountedPartialPath = "$mountedDumpPath.partial-$PID-$([Guid]::NewGuid().ToString('N'))"
    try {
        Copy-Item -LiteralPath $dumpFullPath -Destination $mountedPartialPath -Force
        Protect-SensitiveLocalPath -Path $mountedPartialPath
        Test-GzipArchive -Path $mountedPartialPath
        if ((Get-FileHash -LiteralPath $mountedPartialPath -Algorithm SHA256).Hash.ToLowerInvariant() -cne $verifiedDumpSha256) {
            throw 'The protected local dump copy differs from the verified source.'
        }
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
foreach ($configuredVolume in @($previousVolumeEnv, $envValues['LOCAL_MYSQL_VOLUME'])) {
    if (-not [string]::IsNullOrWhiteSpace($configuredVolume) -and $configuredVolume -cnotmatch '^otziv-prod-local_mysql_973_[a-z0-9][a-z0-9_-]*$') {
        throw 'Remove the legacy LOCAL_MYSQL_VOLUME override before a 9.7.3 local restore; the old volume is preserved.'
    }
}
$env:LOCAL_MYSQL_VOLUME = $LocalMysqlVolume
$composeArgs = @("compose", "-f", $composePath, "--env-file", $envPath)

try {
    Write-Host "Using local MySQL volume: $LocalMysqlVolume"
    $restoreContract = Assert-LocalRestoreContract -ComposeArguments $composeArgs -RepoRoot $repoRoot -ComposePath $composePath -VolumeName $LocalMysqlVolume

    Write-Host "Stopping local prod-like stack..."
    Invoke-External -FilePath "docker" -Arguments ($composeArgs + @("down"))

    # Recheck ownership immediately before deletion as well as before down.
    $restoreContract = Assert-LocalRestoreContract -ComposeArguments $composeArgs -RepoRoot $repoRoot -ComposePath $composePath -VolumeName $LocalMysqlVolume
    if ($restoreContract.VolumeExists) {
        Write-Host "Removing existing local MySQL volume $LocalMysqlVolume..."
        Invoke-External -FilePath "docker" -Arguments @("volume", "rm", $LocalMysqlVolume)
    }

    Initialize-EmptyLocalMySqlVolume -VolumeName $LocalMysqlVolume -Image $restoreContract.Image

    Write-Host "Starting local MySQL..."
    Invoke-External -FilePath "docker" -Arguments ($composeArgs + @("up", "-d", "mysql"))
    Wait-ComposeServiceHealthy -ComposeArguments $composeArgs -Service "mysql"

    Write-Host "Restoring dump into local MySQL..."
    Import-LocalMySqlGzipStream -ComposeArguments $composeArgs -Path $resolvedDumpPath -ExpectedSha256 $verifiedDumpSha256

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
