#requires -Version 7.0
<#
.SYNOPSIS
Imports an allowlisted backup configuration fragment into the external production env.

.DESCRIPTION
The JSON root must be an object whose values are strings. Only the explicitly
allowlisted BACKUP_* names below are accepted. A subset can be staged while
BACKUP_ENABLED=false; enabling backups additionally requires complete S3,
encryption and restore-drill evidence.

The input is restricted to the current user before it is parsed. After a
successful atomic env update (including a validated no-op), it is overwritten
and deleted on a best-effort basis. Filesystems with copy-on-write, snapshots or
flash wear levelling cannot guarantee physical erasure, so the input must still
be created on an encrypted local volume. On validation/write failure the input
is retained with restricted permissions so a one-time credential is recoverable.
#>
[CmdletBinding(SupportsShouldProcess = $true, ConfirmImpact = 'High')]
param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$ConfigJsonPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Protect-SensitivePath {
    param([Parameter(Mandatory = $true)][string]$Path)

    if ([System.Environment]::OSVersion.Platform -eq [System.PlatformID]::Win32NT) {
        $sid = [System.Security.Principal.WindowsIdentity]::GetCurrent().User.Value
        & icacls.exe $Path '/inheritance:r' '/grant:r' "*${sid}:F" '/grant:r' '*S-1-5-18:F' | Out-Null
        if ($LASTEXITCODE -ne 0) {
            throw "Failed to restrict ACL on sensitive file: $Path"
        }
        return
    }

    & chmod 600 -- $Path
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to restrict permissions on sensitive file: $Path"
    }
}

function Test-PathInsideRoot {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Root
    )

    $comparison = if ([System.Environment]::OSVersion.Platform -eq [System.PlatformID]::Win32NT) {
        [StringComparison]::OrdinalIgnoreCase
    } else {
        [StringComparison]::Ordinal
    }
    $fullPath = [IO.Path]::GetFullPath($Path)
    $fullRoot = [IO.Path]::GetFullPath($Root).TrimEnd([IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar)
    return $fullPath.Equals($fullRoot, $comparison) -or
        $fullPath.StartsWith($fullRoot + [IO.Path]::DirectorySeparatorChar, $comparison)
}

function Read-EnvFile {
    param([Parameter(Mandatory = $true)][string]$Path)

    $values = [System.Collections.Generic.Dictionary[string, string]]::new([StringComparer]::Ordinal)
    foreach ($line in [IO.File]::ReadAllLines($Path)) {
        $trimmed = $line.Trim()
        if ($trimmed.Length -eq 0 -or $trimmed.StartsWith('#')) {
            continue
        }
        $separator = $trimmed.IndexOf('=')
        if ($separator -le 0) {
            continue
        }
        $name = $trimmed.Substring(0, $separator).Trim()
        if ($name -match '^[A-Z][A-Z0-9_]*$') {
            $values[$name] = $trimmed.Substring($separator + 1).Trim()
        }
    }
    return $values
}

function Get-MergedSetting {
    param(
        [Parameter(Mandatory = $true)]$Existing,
        [Parameter(Mandatory = $true)]$Updates,
        [Parameter(Mandatory = $true)][string]$Name
    )

    if ($Updates.Contains($Name)) {
        return [string]$Updates[$Name]
    }
    if ($Existing.ContainsKey($Name)) {
        return [string]$Existing[$Name]
    }
    return ''
}

function Assert-Base64Key {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$Value
    )

    $decoded = $null
    try {
        $decoded = [Convert]::FromBase64String($Value)
        if ($decoded.Length -ne 32) {
            throw "$Name must decode to exactly 32 bytes."
        }
        if ([Convert]::ToBase64String($decoded) -cne $Value) {
            throw "$Name must use canonical Base64 encoding."
        }
    } catch [FormatException] {
        throw "$Name must contain valid Base64."
    } finally {
        if ($null -ne $decoded) {
            [Array]::Clear($decoded, 0, $decoded.Length)
        }
    }
}

function Assert-PositiveDuration {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$Value,
        [Parameter(Mandatory = $true)][TimeSpan]$Maximum
    )

    try {
        $duration = [Xml.XmlConvert]::ToTimeSpan($Value)
    } catch {
        throw "$Name must be a valid ISO-8601 duration."
    }
    if ($duration -le [TimeSpan]::Zero -or $duration -gt $Maximum) {
        throw "$Name must be positive and no greater than $Maximum."
    }
}

function Assert-NonNegativeDuration {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$Value,
        [Parameter(Mandatory = $true)][TimeSpan]$Maximum
    )

    try {
        $duration = [Xml.XmlConvert]::ToTimeSpan($Value)
    } catch {
        throw "$Name must be a valid ISO-8601 duration."
    }
    if ($duration -lt [TimeSpan]::Zero -or $duration -gt $Maximum) {
        throw "$Name must be non-negative and no greater than $Maximum."
    }
}

function Assert-IntegerRange {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$Value,
        [Parameter(Mandatory = $true)][int]$Minimum,
        [Parameter(Mandatory = $true)][int]$Maximum
    )

    $parsed = 0
    if ($Value -notmatch '^(?:0|[1-9][0-9]*)$' -or
            -not [int]::TryParse($Value, [Globalization.NumberStyles]::None,
                [Globalization.CultureInfo]::InvariantCulture, [ref]$parsed) -or
            $parsed -lt $Minimum -or $parsed -gt $Maximum) {
        throw "$Name must be an integer from $Minimum to $Maximum."
    }
}

function Assert-Endpoint {
    param([Parameter(Mandatory = $true)][string]$Value)

    Assert-SafeEnvText -Name 'BACKUP_S3_ENDPOINT' -Value $Value -MaximumLength 2048
    $endpoint = $null
    if (-not [Uri]::TryCreate($Value, [UriKind]::Absolute, [ref]$endpoint) -or
            $endpoint.Scheme -cne 'https' -or [string]::IsNullOrWhiteSpace($endpoint.Host) -or
            -not [string]::IsNullOrWhiteSpace($endpoint.UserInfo) -or
            -not [string]::IsNullOrWhiteSpace($endpoint.Query) -or
            -not [string]::IsNullOrWhiteSpace($endpoint.Fragment)) {
        throw 'BACKUP_S3_ENDPOINT must be an absolute HTTPS URI without credentials, query or fragment.'
    }
}

function Assert-RestoreDrillDate {
    param([Parameter(Mandatory = $true)][string]$Value)

    if ($Value -notmatch '^\d{4}-\d{2}-\d{2}(?:T[^\s]+)?$') {
        throw 'BACKUP_RESTORE_DRILL_DATE must be an ISO-8601 date or timestamp.'
    }
    $date = [DateTimeOffset]::MinValue
    if (-not [DateTimeOffset]::TryParse($Value, [Globalization.CultureInfo]::InvariantCulture,
            [Globalization.DateTimeStyles]::AllowWhiteSpaces, [ref]$date)) {
        throw 'BACKUP_RESTORE_DRILL_DATE must be an ISO-8601 date or timestamp.'
    }
    if ($date.ToUniversalTime() -gt [DateTimeOffset]::UtcNow.AddDays(1)) {
        throw 'BACKUP_RESTORE_DRILL_DATE cannot be in the future.'
    }
}

function Assert-MailAddress {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$Value
    )

    Assert-SafeEnvText -Name $Name -Value $Value -MaximumLength 320
    try {
        $address = [Net.Mail.MailAddress]::new($Value)
        if ($address.Address -cne $Value) {
            throw 'non-canonical'
        }
    } catch {
        throw "$Name must contain one canonical email address."
    }
}

function Assert-SafeEnvText {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$Value,
        [Parameter(Mandatory = $true)][int]$MaximumLength
    )

    if ($Value.Length -gt $MaximumLength -or $Value.Contains('$') -or $Value.Contains('#') -or
            $Value.Contains('"') -or $Value.Contains("'") -or $Value.Contains("`r") -or
            $Value.Contains("`n")) {
        throw "$Name contains characters that cannot be safely persisted as a literal env-file value."
    }
    foreach ($character in $Value.ToCharArray()) {
        if ([char]::IsControl($character)) {
            throw "$Name contains characters that cannot be safely persisted as a literal env-file value."
        }
    }
}

function Assert-TimeZoneId {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$Value
    )

    if ($Value -notmatch '^[A-Za-z0-9][A-Za-z0-9._+/-]{0,127}$' -or $Value.Contains('..')) {
        throw "$Name contains unsafe characters."
    }
    try {
        [void][TimeZoneInfo]::FindSystemTimeZoneById($Value)
    } catch {
        throw "$Name must identify a time zone available to the runtime."
    }
}

function Assert-DailyCron {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$Value
    )

    if ($Value.Length -gt 127 -or $Value -notmatch '^[A-Za-z0-9*?,/\-#LW ]+$') {
        throw "$Name must be a safe six-field Spring cron expression."
    }
    $fields = @($Value -split ' +' | Where-Object { $_ })
    if ($fields.Count -ne 6) {
        throw "$Name must be a safe six-field Spring cron expression."
    }

    $timeFields = @(
        @{ Name = 'seconds'; Value = $fields[0]; Maximum = 59 },
        @{ Name = 'minutes'; Value = $fields[1]; Maximum = 59 },
        @{ Name = 'hours'; Value = $fields[2]; Maximum = 23 }
    )
    foreach ($field in $timeFields) {
        $parsed = 0
        if ($field.Value -notmatch '^[0-9]+$' -or
                -not [int]::TryParse($field.Value, [Globalization.NumberStyles]::None,
                    [Globalization.CultureInfo]::InvariantCulture, [ref]$parsed)) {
            throw "$Name $($field.Name) must be one numeric value so the schedule cannot run more than once per day."
        }
        if ($parsed -lt 0 -or $parsed -gt $field.Maximum) {
            throw "$Name $($field.Name) must be between 0 and $($field.Maximum)."
        }
    }
    if ($fields[3] -cne '*' -or $fields[4] -cne '*' -or $fields[5] -cne '*') {
        throw "$Name day-of-month, month and day-of-week must all be '*' so a backup runs every calendar day."
    }
}

function Set-EnvFileValuesAtomic {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)]$Values
    )

    $remaining = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($name in $Values.Keys) {
        [void]$remaining.Add([string]$name)
    }
    $written = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    $output = [System.Collections.Generic.List[string]]::new()
    foreach ($line in [IO.File]::ReadAllLines($Path)) {
        if ($line -match '^(?<name>[A-Z][A-Z0-9_]*)=' -and $Values.Contains($Matches.name)) {
            if ($written.Add($Matches.name)) {
                [void]$output.Add("$($Matches.name)=$($Values[$Matches.name])")
                [void]$remaining.Remove($Matches.name)
            }
            continue
        }
        [void]$output.Add($line)
    }
    foreach ($name in @($remaining | Sort-Object)) {
        [void]$output.Add("$name=$($Values[$name])")
    }

    $temporary = Join-Path (Split-Path -Parent $Path) ".$([IO.Path]::GetFileName($Path)).$([guid]::NewGuid().ToString('N')).tmp"
    try {
        [IO.File]::WriteAllLines($temporary, $output.ToArray(), [Text.UTF8Encoding]::new($false))
        Protect-SensitivePath -Path $temporary
        Move-Item -LiteralPath $temporary -Destination $Path -Force
        Protect-SensitivePath -Path $Path
    } finally {
        Remove-Item -LiteralPath $temporary -Force -ErrorAction SilentlyContinue
    }
}

function Remove-SensitiveInputFileBestEffort {
    param([Parameter(Mandatory = $true)][string]$Path)

    $buffer = [byte[]]::new(65536)
    try {
        [IO.File]::SetAttributes($Path, [IO.FileAttributes]::Normal)
        $stream = [IO.File]::Open($Path, [IO.FileMode]::Open, [IO.FileAccess]::Write, [IO.FileShare]::None)
        try {
            $remaining = $stream.Length
            while ($remaining -gt 0) {
                [Security.Cryptography.RandomNumberGenerator]::Fill($buffer)
                $count = [int][Math]::Min([long]$buffer.Length, $remaining)
                $stream.Write($buffer, 0, $count)
                $remaining -= $count
            }
            $stream.Flush($true)
            $stream.SetLength(0)
            $stream.Flush($true)
        } finally {
            $stream.Dispose()
        }
        [IO.File]::Delete($Path)
    } finally {
        [Array]::Clear($buffer, 0, $buffer.Length)
    }
}

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
. (Join-Path $repoRoot 'infrastructure\scripts\Resolve-OtzivEnvFile.ps1')
$envPath = Resolve-OtzivEnvFile -EnvFile '.env.prod' -RepoRoot $repoRoot
if (Test-PathInsideRoot -Path $envPath -Root $repoRoot) {
    throw "Production secrets must remain outside the Git worktree: $envPath"
}

if (-not (Test-Path -LiteralPath $ConfigJsonPath -PathType Leaf)) {
    throw "Backup configuration JSON was not found: $ConfigJsonPath"
}
$inputPath = (Resolve-Path -LiteralPath $ConfigJsonPath).Path
if (Test-PathInsideRoot -Path $inputPath -Root $repoRoot) {
    throw "Backup configuration JSON must remain outside the Git worktree: $inputPath"
}
if (([IO.File]::GetAttributes($inputPath) -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
    throw "Backup configuration JSON must not be a symbolic link or reparse point: $inputPath"
}
Protect-SensitivePath -Path $inputPath

$allowedNames = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
foreach ($name in @(
    'BACKUP_ENABLED', 'BACKUP_WORK_DIR', 'BACKUP_PART_SIZE_MB',
    'BACKUP_DUMP_TIMEOUT', 'BACKUP_UPLOAD_TIMEOUT', 'BACKUP_MAX_STDERR_BYTES',
    'BACKUP_ENCRYPTION_KEY_BASE64', 'BACKUP_EVIDENCE_FILE_NAME', 'BACKUP_SOURCE_COMMIT',
    'BACKUP_RESTORE_DRILL_RTO', 'BACKUP_RESTORE_DRILL_DATE',
    'BACKUP_SCHEDULE_ENABLED', 'BACKUP_SCHEDULE_CRON', 'BACKUP_SCHEDULE_ZONE',
    'BACKUP_SCHEDULE_CATCH_UP_ENABLED', 'BACKUP_SCHEDULE_CATCH_UP_WINDOW',
    'BACKUP_SCHEDULE_CATCH_UP_CHECK_INTERVAL', 'BACKUP_SCHEDULE_CATCH_UP_INITIAL_DELAY',
    'BACKUP_RUN_ONCE_ENABLED', 'BACKUP_RUN_ONCE_REQUEST_ID',
    'BACKUP_S3_ACCESS_KEY', 'BACKUP_S3_SECRET_KEY', 'BACKUP_S3_ENDPOINT',
    'BACKUP_S3_REGION', 'BACKUP_S3_BUCKET', 'BACKUP_S3_PROJECT',
    'BACKUP_S3_FORCE_PATH_STYLE', 'BACKUP_S3_REQUIRE_SERVER_SIDE_ENCRYPTION',
    'BACKUP_S3_INDEPENDENT_CONFIRMED',
    'BACKUP_DESTINATION_PRIVATE_CONFIRMED', 'BACKUP_ENCRYPTION_AT_REST_CONFIRMED',
    'BACKUP_S3_OBJECT_LOCK_ENABLED', 'BACKUP_S3_OBJECT_LOCK_MODE', 'BACKUP_S3_RETENTION_DAYS',
    'BACKUP_MAIL_ENABLED', 'BACKUP_EMAIL_DELIVERY_CONFIRMED',
    'BACKUP_MAIL_BODY', 'BACKUP_MAIL_FROM', 'BACKUP_MAIL_SUBJECT', 'BACKUP_MAIL_TO'
)) {
    [void]$allowedNames.Add($name)
}
$booleanNames = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
foreach ($name in @(
    'BACKUP_ENABLED', 'BACKUP_S3_FORCE_PATH_STYLE', 'BACKUP_S3_REQUIRE_SERVER_SIDE_ENCRYPTION',
    'BACKUP_S3_INDEPENDENT_CONFIRMED',
    'BACKUP_DESTINATION_PRIVATE_CONFIRMED', 'BACKUP_ENCRYPTION_AT_REST_CONFIRMED',
    'BACKUP_S3_OBJECT_LOCK_ENABLED', 'BACKUP_MAIL_ENABLED', 'BACKUP_EMAIL_DELIVERY_CONFIRMED',
    'BACKUP_SCHEDULE_ENABLED', 'BACKUP_SCHEDULE_CATCH_UP_ENABLED', 'BACKUP_RUN_ONCE_ENABLED'
)) {
    [void]$booleanNames.Add($name)
}

$updates = [ordered]@{}
$document = $null
$committed = $false
try {
    $inputInfo = Get-Item -LiteralPath $inputPath
    if ($inputInfo.Length -le 0 -or $inputInfo.Length -gt 1MB) {
        throw 'Backup configuration JSON must be between 1 byte and 1 MiB.'
    }
    try {
        $document = [Text.Json.JsonDocument]::Parse([IO.File]::ReadAllText($inputPath, [Text.Encoding]::UTF8))
    } catch [Text.Json.JsonException] {
        throw 'Backup configuration JSON is invalid.'
    }
    if ($document.RootElement.ValueKind -ne [Text.Json.JsonValueKind]::Object) {
        throw 'Backup configuration JSON root must be an object.'
    }

    $seen = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($property in $document.RootElement.EnumerateObject()) {
        $name = $property.Name
        if (-not $seen.Add($name)) {
            throw "Backup configuration JSON contains duplicate key '$name'."
        }
        if (-not $allowedNames.Contains($name)) {
            throw "Backup configuration JSON contains unknown key '$name'."
        }
        if ($property.Value.ValueKind -ne [Text.Json.JsonValueKind]::String) {
            throw "Backup configuration key '$name' must have a JSON string value."
        }
        $value = $property.Value.GetString()
        if ([string]::IsNullOrWhiteSpace($value) -or $value -cne $value.Trim() -or
                $value.Contains("`r") -or $value.Contains("`n")) {
            throw "Backup configuration key '$name' must be non-blank, trimmed and single-line."
        }
        $updates[$name] = $value
    }
    if ($updates.Count -eq 0) {
        throw 'Backup configuration JSON must contain at least one allowlisted setting.'
    }

    foreach ($entry in $updates.GetEnumerator()) {
        $name = [string]$entry.Key
        $value = [string]$entry.Value
        if ($booleanNames.Contains($name) -and $value -cnotin @('true', 'false')) {
            throw "$name must be exactly 'true' or 'false'."
        }
        switch ($name) {
            'BACKUP_DUMP_TIMEOUT' { Assert-PositiveDuration -Name $name -Value $value -Maximum ([TimeSpan]::FromHours(24)) }
            'BACKUP_UPLOAD_TIMEOUT' { Assert-PositiveDuration -Name $name -Value $value -Maximum ([TimeSpan]::FromHours(24)) }
            'BACKUP_RESTORE_DRILL_RTO' { Assert-PositiveDuration -Name $name -Value $value -Maximum ([TimeSpan]::FromDays(7)) }
            'BACKUP_SCHEDULE_CATCH_UP_WINDOW' { Assert-PositiveDuration -Name $name -Value $value -Maximum ([TimeSpan]::FromHours(36)) }
            'BACKUP_SCHEDULE_CATCH_UP_CHECK_INTERVAL' { Assert-PositiveDuration -Name $name -Value $value -Maximum ([TimeSpan]::FromHours(1)) }
            'BACKUP_SCHEDULE_CATCH_UP_INITIAL_DELAY' { Assert-NonNegativeDuration -Name $name -Value $value -Maximum ([TimeSpan]::FromHours(1)) }
            'BACKUP_RESTORE_DRILL_DATE' { Assert-RestoreDrillDate -Value $value }
            'BACKUP_PART_SIZE_MB' { Assert-IntegerRange -Name $name -Value $value -Minimum 1 -Maximum 1024 }
            'BACKUP_MAX_STDERR_BYTES' { Assert-IntegerRange -Name $name -Value $value -Minimum 1024 -Maximum 1048576 }
            'BACKUP_S3_RETENTION_DAYS' { Assert-IntegerRange -Name $name -Value $value -Minimum 0 -Maximum 36500 }
            'BACKUP_ENCRYPTION_KEY_BASE64' { Assert-Base64Key -Name $name -Value $value }
            'BACKUP_S3_ENDPOINT' { Assert-Endpoint -Value $value }
            'BACKUP_S3_REGION' {
                if ($value -notmatch '^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$') { throw "$name contains unsafe characters." }
            }
            'BACKUP_S3_BUCKET' {
                if ($value.Length -lt 3 -or $value.Length -gt 255 -or
                        $value -notmatch '^[a-z0-9][a-z0-9.-]*[a-z0-9]$' -or $value.Contains('..')) {
                    throw "$name is not a safe S3 bucket name."
                }
            }
            'BACKUP_S3_PROJECT' {
                if ($value -notmatch '^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$') { throw "$name contains unsafe characters." }
            }
            'BACKUP_S3_ACCESS_KEY' {
                if ($value -notmatch '^[A-Za-z0-9][A-Za-z0-9._-]{7,255}$') { throw "$name has an unsafe format." }
            }
            'BACKUP_S3_SECRET_KEY' {
                if ($value -notmatch '^[A-Za-z0-9/+][A-Za-z0-9/+_.=-]{15,511}$') { throw "$name has an unsafe format." }
            }
            'BACKUP_S3_OBJECT_LOCK_MODE' {
                if ($value -cnotin @('GOVERNANCE', 'COMPLIANCE')) { throw "$name must be GOVERNANCE or COMPLIANCE." }
            }
            'BACKUP_EVIDENCE_FILE_NAME' {
                if ($value -notmatch '^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$') { throw "$name must be a simple file name." }
            }
            'BACKUP_SOURCE_COMMIT' {
                if ($value -notmatch '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$') { throw "$name contains unsafe characters." }
            }
            'BACKUP_SCHEDULE_CRON' {
                Assert-DailyCron -Name $name -Value $value
            }
            'BACKUP_SCHEDULE_ZONE' {
                Assert-TimeZoneId -Name $name -Value $value
            }
            'BACKUP_RUN_ONCE_REQUEST_ID' {
                if ($value -notmatch '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$') { throw "$name contains unsafe characters." }
            }
            'BACKUP_WORK_DIR' {
                if ($value -notmatch '^/[A-Za-z0-9._/-]+$' -or $value -match '(?:^|/)\.\.(?:/|$)') { throw "$name must be a safe absolute container path." }
            }
            'BACKUP_MAIL_FROM' { Assert-MailAddress -Name $name -Value $value }
            'BACKUP_MAIL_TO' { Assert-MailAddress -Name $name -Value $value }
            'BACKUP_MAIL_SUBJECT' { Assert-SafeEnvText -Name $name -Value $value -MaximumLength 200 }
            'BACKUP_MAIL_BODY' { Assert-SafeEnvText -Name $name -Value $value -MaximumLength 2000 }
        }
    }

    $existing = Read-EnvFile -Path $envPath
    $objectLockEnabled = (Get-MergedSetting -Existing $existing -Updates $updates -Name 'BACKUP_S3_OBJECT_LOCK_ENABLED') -ceq 'true'
    $retentionText = Get-MergedSetting -Existing $existing -Updates $updates -Name 'BACKUP_S3_RETENTION_DAYS'
    $retentionDays = 0
    if (-not [string]::IsNullOrWhiteSpace($retentionText)) {
        Assert-IntegerRange -Name 'BACKUP_S3_RETENTION_DAYS' -Value $retentionText -Minimum 0 -Maximum 36500
        $retentionDays = [int]$retentionText
    }
    if ($objectLockEnabled -and $retentionDays -lt 1) {
        throw 'BACKUP_S3_RETENTION_DAYS must be positive when Object Lock is enabled.'
    }
    if (-not $objectLockEnabled -and $retentionDays -ne 0) {
        throw 'Non-zero BACKUP_S3_RETENTION_DAYS requires BACKUP_S3_OBJECT_LOCK_ENABLED=true.'
    }
    if ($objectLockEnabled) {
        $lockMode = Get-MergedSetting -Existing $existing -Updates $updates -Name 'BACKUP_S3_OBJECT_LOCK_MODE'
        if ($lockMode -cnotin @('GOVERNANCE', 'COMPLIANCE')) {
            throw 'Object Lock requires BACKUP_S3_OBJECT_LOCK_MODE=GOVERNANCE or COMPLIANCE.'
        }
    }

    $mailEnabled = (Get-MergedSetting -Existing $existing -Updates $updates -Name 'BACKUP_MAIL_ENABLED') -ceq 'true'
    if ($mailEnabled) {
        foreach ($name in @('BACKUP_MAIL_TO', 'BACKUP_MAIL_FROM')) {
            $mailValue = Get-MergedSetting -Existing $existing -Updates $updates -Name $name
            if ([string]::IsNullOrWhiteSpace($mailValue)) { throw "$name is required when backup mail is enabled." }
            Assert-MailAddress -Name $name -Value $mailValue
        }
        if ((Get-MergedSetting -Existing $existing -Updates $updates -Name 'BACKUP_EMAIL_DELIVERY_CONFIRMED') -cne 'true') {
            throw 'BACKUP_EMAIL_DELIVERY_CONFIRMED=true is required when backup mail is enabled.'
        }
    }

    $scheduleEnabledText = Get-MergedSetting -Existing $existing -Updates $updates -Name 'BACKUP_SCHEDULE_ENABLED'
    $scheduleEnabled = if ([string]::IsNullOrWhiteSpace($scheduleEnabledText)) { $true } else { $scheduleEnabledText -ceq 'true' }
    $catchUpEnabled = (Get-MergedSetting -Existing $existing -Updates $updates -Name 'BACKUP_SCHEDULE_CATCH_UP_ENABLED') -ceq 'true'
    $runOnceEnabled = (Get-MergedSetting -Existing $existing -Updates $updates -Name 'BACKUP_RUN_ONCE_ENABLED') -ceq 'true'
    if ($scheduleEnabled) {
        $scheduleCron = Get-MergedSetting -Existing $existing -Updates $updates -Name 'BACKUP_SCHEDULE_CRON'
        if ([string]::IsNullOrWhiteSpace($scheduleCron)) { $scheduleCron = '0 0 7 * * *' }
        Assert-DailyCron -Name 'BACKUP_SCHEDULE_CRON' -Value $scheduleCron
        $scheduleZone = Get-MergedSetting -Existing $existing -Updates $updates -Name 'BACKUP_SCHEDULE_ZONE'
        if ([string]::IsNullOrWhiteSpace($scheduleZone)) { $scheduleZone = 'Asia/Irkutsk' }
        Assert-TimeZoneId -Name 'BACKUP_SCHEDULE_ZONE' -Value $scheduleZone
    }
    if ($catchUpEnabled -and -not $scheduleEnabled) {
        throw 'BACKUP_SCHEDULE_CATCH_UP_ENABLED=true requires BACKUP_SCHEDULE_ENABLED=true.'
    }
    if ($catchUpEnabled) {
        $catchUpWindow = Get-MergedSetting -Existing $existing -Updates $updates -Name 'BACKUP_SCHEDULE_CATCH_UP_WINDOW'
        if ([string]::IsNullOrWhiteSpace($catchUpWindow)) { $catchUpWindow = 'PT26H' }
        Assert-PositiveDuration -Name 'BACKUP_SCHEDULE_CATCH_UP_WINDOW' -Value $catchUpWindow -Maximum ([TimeSpan]::FromHours(36))
        if ([Xml.XmlConvert]::ToTimeSpan($catchUpWindow) -le [TimeSpan]::FromHours(24)) {
            throw 'BACKUP_SCHEDULE_CATCH_UP_WINDOW must be greater than PT24H.'
        }
    }
    if ($runOnceEnabled) {
        throw 'BACKUP_RUN_ONCE_ENABLED=true must not be persisted; one-shot mode is restricted to an isolated process override.'
    }

    $enabled = (Get-MergedSetting -Existing $existing -Updates $updates -Name 'BACKUP_ENABLED') -ceq 'true'
    if ($enabled) {
        if (-not $scheduleEnabled) {
            throw 'BACKUP_ENABLED=true requires BACKUP_SCHEDULE_ENABLED=true for recurring production backups.'
        }
        if (-not $catchUpEnabled) {
            throw 'BACKUP_ENABLED=true requires BACKUP_SCHEDULE_CATCH_UP_ENABLED=true.'
        }
        foreach ($name in @(
            'BACKUP_ENCRYPTION_KEY_BASE64', 'BACKUP_S3_ACCESS_KEY', 'BACKUP_S3_SECRET_KEY',
            'BACKUP_S3_ENDPOINT', 'BACKUP_S3_REGION', 'BACKUP_S3_BUCKET', 'BACKUP_S3_PROJECT',
            'BACKUP_RESTORE_DRILL_DATE', 'BACKUP_RESTORE_DRILL_RTO'
        )) {
            if ([string]::IsNullOrWhiteSpace((Get-MergedSetting -Existing $existing -Updates $updates -Name $name))) {
                throw "$name is required when BACKUP_ENABLED=true."
            }
        }
        foreach ($name in @(
            'BACKUP_S3_INDEPENDENT_CONFIRMED', 'BACKUP_DESTINATION_PRIVATE_CONFIRMED',
            'BACKUP_ENCRYPTION_AT_REST_CONFIRMED'
        )) {
            if ((Get-MergedSetting -Existing $existing -Updates $updates -Name $name) -cne 'true') {
                throw "$name must be true when BACKUP_ENABLED=true."
            }
        }
        Assert-Base64Key -Name 'BACKUP_ENCRYPTION_KEY_BASE64' -Value (Get-MergedSetting -Existing $existing -Updates $updates -Name 'BACKUP_ENCRYPTION_KEY_BASE64')
        Assert-Endpoint -Value (Get-MergedSetting -Existing $existing -Updates $updates -Name 'BACKUP_S3_ENDPOINT')
        Assert-PositiveDuration -Name 'BACKUP_RESTORE_DRILL_RTO' -Value (Get-MergedSetting -Existing $existing -Updates $updates -Name 'BACKUP_RESTORE_DRILL_RTO') -Maximum ([TimeSpan]::FromDays(7))
        Assert-RestoreDrillDate -Value (Get-MergedSetting -Existing $existing -Updates $updates -Name 'BACKUP_RESTORE_DRILL_DATE')
    }

    $scheduledKey = Get-MergedSetting -Existing $existing -Updates $updates -Name 'BACKUP_ENCRYPTION_KEY_BASE64'
    if (-not [string]::IsNullOrWhiteSpace($scheduledKey)) {
        Assert-Base64Key -Name 'BACKUP_ENCRYPTION_KEY_BASE64' -Value $scheduledKey
        foreach ($otherName in @('OTZIV_CREDENTIAL_ENCRYPTION_ACTIVE_KEY_BASE64', 'DEPLOY_DB_BACKUP_ENCRYPTION_KEY_BASE64')) {
            if ($existing.ContainsKey($otherName) -and -not [string]::IsNullOrWhiteSpace($existing[$otherName])) {
                Assert-Base64Key -Name $otherName -Value $existing[$otherName]
                if ($scheduledKey -ceq $existing[$otherName]) {
                    throw "BACKUP_ENCRYPTION_KEY_BASE64 must differ from $otherName."
                }
            }
        }
    }
    $prospectiveBucket = Get-MergedSetting -Existing $existing -Updates $updates -Name 'BACKUP_S3_BUCKET'
    if ($existing.ContainsKey('S3_BUCKET') -and -not [string]::IsNullOrWhiteSpace($prospectiveBucket) -and
            $prospectiveBucket.Equals($existing['S3_BUCKET'], [StringComparison]::OrdinalIgnoreCase)) {
        throw 'BACKUP_S3_BUCKET must differ from the primary S3_BUCKET.'
    }
    $prospectiveAccessKey = Get-MergedSetting -Existing $existing -Updates $updates -Name 'BACKUP_S3_ACCESS_KEY'
    if ($existing.ContainsKey('S3_ACCESS_KEY') -and -not [string]::IsNullOrWhiteSpace($prospectiveAccessKey) -and
            $prospectiveAccessKey -ceq $existing['S3_ACCESS_KEY']) {
        throw 'BACKUP_S3_ACCESS_KEY must differ from the primary S3_ACCESS_KEY.'
    }

    $changedNames = @($updates.Keys | Where-Object {
        -not $existing.ContainsKey($_) -or $existing[$_] -cne [string]$updates[$_]
    })
    $action = if ($changedNames.Count -eq 0) {
        'validate the backup configuration and securely consume its input JSON'
    } else {
        "update $($changedNames.Count) allowlisted backup setting(s), preserve the previous env, and securely consume the input JSON"
    }
    if ($PSCmdlet.ShouldProcess($envPath, $action)) {
        if ($changedNames.Count -gt 0) {
            $backupPath = "$envPath.backup-config-$(Get-Date -Format 'yyyyMMdd-HHmmss-fff')-$([guid]::NewGuid().ToString('N').Substring(0, 8)).bak"
            Copy-Item -LiteralPath $envPath -Destination $backupPath
            Protect-SensitivePath -Path $backupPath
            Set-EnvFileValuesAtomic -Path $envPath -Values $updates
            Write-Host "Production backup configuration updated without printing secret values."
            Write-Host "Protected previous env copy: $backupPath"
            Write-Host "Updated keys: $($changedNames -join ', ')"
        } else {
            Write-Host 'Production backup configuration was already current; no env values were rewritten.'
        }
        $committed = $true
    }
} finally {
    if ($null -ne $document) {
        $document.Dispose()
    }
    if ($committed -and (Test-Path -LiteralPath $inputPath -PathType Leaf)) {
        Remove-SensitiveInputFileBestEffort -Path $inputPath
        Write-Host "Protected backup configuration input was securely consumed: $inputPath"
    } elseif (Test-Path -LiteralPath $inputPath -PathType Leaf) {
        Protect-SensitivePath -Path $inputPath
        Write-Warning "Backup configuration input was not consumed and remains protected for recovery: $inputPath"
    }
}
