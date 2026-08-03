[CmdletBinding(SupportsShouldProcess = $true, ConfirmImpact = 'High')]
param(
    [string]$EnvFile = '.env.prod',
    [Parameter(Mandatory = $true)][string]$ReleaseTag,
    [string]$DockerHubNamespace = 'claid38',
    [string]$CredentialKeyId = "prod-$(Get-Date -Format 'yyyyMMdd')"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function New-RandomBytes {
    param([Parameter(Mandatory = $true)][ValidateRange(16, 4096)][int]$Count)

    $bytes = [byte[]]::new($Count)
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $rng.GetBytes($bytes)
        return $bytes
    } finally {
        $rng.Dispose()
    }
}

function New-RandomHexSecret {
    $bytes = New-RandomBytes -Count 32
    try {
        return ([BitConverter]::ToString($bytes)).Replace('-', '').ToLowerInvariant()
    } finally {
        [Array]::Clear($bytes, 0, $bytes.Length)
    }
}

function New-RandomBase64Key {
    $bytes = New-RandomBytes -Count 32
    try {
        return [Convert]::ToBase64String($bytes)
    } finally {
        [Array]::Clear($bytes, 0, $bytes.Length)
    }
}

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

function Read-EnvFile {
    param([Parameter(Mandatory = $true)][string]$Path)

    $values = [System.Collections.Generic.Dictionary[string, string]]::new(
        [System.StringComparer]::Ordinal
    )
    foreach ($line in [System.IO.File]::ReadAllLines($Path)) {
        $trimmed = $line.Trim()
        if ($trimmed.Length -eq 0 -or $trimmed.StartsWith('#')) {
            continue
        }
        $separator = $trimmed.IndexOf('=')
        if ($separator -le 0) {
            continue
        }
        $name = $trimmed.Substring(0, $separator).Trim()
        if ($name -notmatch '^[A-Z][A-Z0-9_]*$') {
            continue
        }
        $values[$name] = $trimmed.Substring($separator + 1).Trim()
    }
    return $values
}

function Test-NonBlankSetting {
    param(
        [Parameter(Mandatory = $true)]$Values,
        [Parameter(Mandatory = $true)][string]$Name
    )
    return $Values.ContainsKey($Name) -and -not [string]::IsNullOrWhiteSpace($Values[$Name])
}

function Test-MinimumAppMemoryLimit {
    param([AllowNull()][string]$Value)

    if ([string]::IsNullOrWhiteSpace($Value) -or $Value -notmatch '^(?<amount>[1-9][0-9]*)(?<unit>[mMgG])$') {
        return $false
    }
    $amount = [long]$Matches.amount
    $memoryMiB = if ($Matches.unit.ToLowerInvariant() -eq 'g') { $amount * 1024L } else { $amount }
    return $memoryMiB -ge 2304L
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
    } catch [System.FormatException] {
        throw "$Name must contain valid Base64."
    } finally {
        if ($null -ne $decoded) {
            [Array]::Clear($decoded, 0, $decoded.Length)
        }
    }
}

function Set-EnvFileValuesAtomic {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)]$Values
    )

    foreach ($entry in $Values.GetEnumerator()) {
        $value = [string]$entry.Value
        if ($entry.Key -notmatch '^[A-Z][A-Z0-9_]*$' -or
                [string]::IsNullOrWhiteSpace($value) -or
                $value.Contains("`r") -or $value.Contains("`n") -or
                $value -cne $value.Trim()) {
            throw "Refusing to write invalid env setting '$($entry.Key)'."
        }
    }

    $remaining = [System.Collections.Generic.HashSet[string]]::new(
        [System.StringComparer]::Ordinal
    )
    foreach ($name in $Values.Keys) {
        [void]$remaining.Add([string]$name)
    }
    $written = [System.Collections.Generic.HashSet[string]]::new(
        [System.StringComparer]::Ordinal
    )
    $output = [System.Collections.Generic.List[string]]::new()
    foreach ($line in [System.IO.File]::ReadAllLines($Path)) {
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

    $temporary = Join-Path (Split-Path -Parent $Path) (".$([IO.Path]::GetFileName($Path)).$([guid]::NewGuid().ToString('N')).tmp")
    try {
        [IO.File]::WriteAllLines($temporary, $output.ToArray(), [Text.UTF8Encoding]::new($false))
        Protect-SensitivePath -Path $temporary
        Move-Item -LiteralPath $temporary -Destination $Path -Force
        Protect-SensitivePath -Path $Path
    } finally {
        Remove-Item -LiteralPath $temporary -Force -ErrorAction SilentlyContinue
    }
}

if ($ReleaseTag -notmatch '^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$') {
    throw 'ReleaseTag is not a valid Docker tag.'
}
if ($DockerHubNamespace -notmatch '^[a-z0-9]+(?:[._-][a-z0-9]+)*$') {
    throw 'DockerHubNamespace is invalid.'
}
if ($CredentialKeyId -notmatch '^[A-Za-z0-9._-]{1,64}$') {
    throw 'CredentialKeyId must match [A-Za-z0-9._-]{1,64}.'
}

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
. (Join-Path $repoRoot 'infrastructure\scripts\Resolve-OtzivEnvFile.ps1')
$envPath = Resolve-OtzivEnvFile -EnvFile $EnvFile -RepoRoot $repoRoot
$repoPrefix = $repoRoot.TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
if ($envPath.StartsWith($repoPrefix, [StringComparison]::OrdinalIgnoreCase)) {
    throw "Production secrets must remain outside the Git worktree: $envPath"
}

$existing = Read-EnvFile -Path $envPath
$updates = [ordered]@{
    OTZIV_APP_BASE_URL = 'https://o-ogo.ru'
    OTZIV_CREDENTIAL_ENCRYPTION_REQUIRED = 'true'
    OTZIV_CREDENTIAL_ENCRYPTION_BACKFILL_ENABLED = 'true'
    OTZIV_CREDENTIAL_ENCRYPTION_BACKFILL_BATCH_SIZE = '250'
    WHATSAPP_WEBHOOK_HMAC_REQUIRED = 'true'
    WHATSAPP_GATEWAY_AUTH_REQUIRED = 'true'
    EXTERNAL_REVIEW_WORKER_AUTH_REQUIRED = 'true'
    EXTERNAL_REVIEW_WORKER_IMAGE = "$DockerHubNamespace/otziv-external-review-worker:$ReleaseTag"
}
if ((Test-NonBlankSetting -Values $existing -Name 'APP_MEMORY_LIMIT') -and
        (Test-MinimumAppMemoryLimit -Value $existing['APP_MEMORY_LIMIT'])) {
    $updates.APP_MEMORY_LIMIT = $existing['APP_MEMORY_LIMIT']
} else {
    $updates.APP_MEMORY_LIMIT = '2304m'
}

if (Test-NonBlankSetting -Values $existing -Name 'OTZIV_CREDENTIAL_ENCRYPTION_ACTIVE_KEY_ID') {
    $updates.OTZIV_CREDENTIAL_ENCRYPTION_ACTIVE_KEY_ID = $existing['OTZIV_CREDENTIAL_ENCRYPTION_ACTIVE_KEY_ID']
} else {
    $updates.OTZIV_CREDENTIAL_ENCRYPTION_ACTIVE_KEY_ID = $CredentialKeyId
}
if (Test-NonBlankSetting -Values $existing -Name 'OTZIV_CREDENTIAL_ENCRYPTION_ACTIVE_KEY_BASE64') {
    Assert-Base64Key -Name 'OTZIV_CREDENTIAL_ENCRYPTION_ACTIVE_KEY_BASE64' -Value $existing['OTZIV_CREDENTIAL_ENCRYPTION_ACTIVE_KEY_BASE64']
    $updates.OTZIV_CREDENTIAL_ENCRYPTION_ACTIVE_KEY_BASE64 = $existing['OTZIV_CREDENTIAL_ENCRYPTION_ACTIVE_KEY_BASE64']
} else {
    $updates.OTZIV_CREDENTIAL_ENCRYPTION_ACTIVE_KEY_BASE64 = New-RandomBase64Key
}
if (Test-NonBlankSetting -Values $existing -Name 'WHATSAPP_GATEWAY_SHARED_SECRET') {
    $updates.WHATSAPP_GATEWAY_SHARED_SECRET = $existing['WHATSAPP_GATEWAY_SHARED_SECRET']
} else {
    $updates.WHATSAPP_GATEWAY_SHARED_SECRET = New-RandomHexSecret
}
if (Test-NonBlankSetting -Values $existing -Name 'EXTERNAL_REVIEW_WORKER_SHARED_SECRET') {
    $updates.EXTERNAL_REVIEW_WORKER_SHARED_SECRET = $existing['EXTERNAL_REVIEW_WORKER_SHARED_SECRET']
} else {
    $updates.EXTERNAL_REVIEW_WORKER_SHARED_SECRET = New-RandomHexSecret
}
if ((Test-NonBlankSetting -Values $existing -Name 'MAX_BOT_WEBHOOK_SECRET') -and
        $existing['MAX_BOT_WEBHOOK_SECRET'].Length -ge 32) {
    $updates.MAX_BOT_WEBHOOK_SECRET = $existing['MAX_BOT_WEBHOOK_SECRET']
} else {
    $updates.MAX_BOT_WEBHOOK_SECRET = New-RandomHexSecret
}
if ((Test-NonBlankSetting -Values $existing -Name 'TELEGRAM_BOT_LINK_SECRET') -and
        $existing['TELEGRAM_BOT_LINK_SECRET'].Length -ge 32) {
    $updates.TELEGRAM_BOT_LINK_SECRET = $existing['TELEGRAM_BOT_LINK_SECRET']
} else {
    $updates.TELEGRAM_BOT_LINK_SECRET = New-RandomHexSecret
}
if ((Test-NonBlankSetting -Values $existing -Name 'MAX_BOT_LINK_SECRET') -and
        $existing['MAX_BOT_LINK_SECRET'].Length -ge 32) {
    $updates.MAX_BOT_LINK_SECRET = $existing['MAX_BOT_LINK_SECRET']
} else {
    $updates.MAX_BOT_LINK_SECRET = New-RandomHexSecret
}
if (Test-NonBlankSetting -Values $existing -Name 'DEPLOY_DB_BACKUP_ENCRYPTION_KEY_BASE64') {
    Assert-Base64Key -Name 'DEPLOY_DB_BACKUP_ENCRYPTION_KEY_BASE64' -Value $existing['DEPLOY_DB_BACKUP_ENCRYPTION_KEY_BASE64']
    $updates.DEPLOY_DB_BACKUP_ENCRYPTION_KEY_BASE64 = $existing['DEPLOY_DB_BACKUP_ENCRYPTION_KEY_BASE64']
} else {
    $updates.DEPLOY_DB_BACKUP_ENCRYPTION_KEY_BASE64 = New-RandomBase64Key
}

$secretNames = @(
    'WHATSAPP_WEBHOOK_SECRET',
    'WHATSAPP_GATEWAY_SHARED_SECRET',
    'EXTERNAL_REVIEW_WORKER_SHARED_SECRET',
    'MAX_BOT_WEBHOOK_SECRET',
    'TELEGRAM_BOT_LINK_SECRET',
    'MAX_BOT_LINK_SECRET'
)
$secretValues = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
foreach ($name in $secretNames) {
    $value = if ($updates.Contains($name)) { [string]$updates[$name] } elseif ($existing.ContainsKey($name)) { $existing[$name] } else { '' }
    if ([string]::IsNullOrWhiteSpace($value) -or $value.Length -lt 32) {
        throw "$name must contain at least 32 characters."
    }
    if (-not $secretValues.Add($value)) {
        throw 'Webhook, gateway and external-worker secrets must be distinct.'
    }
}

Assert-Base64Key -Name 'OTZIV_CREDENTIAL_ENCRYPTION_ACTIVE_KEY_BASE64' -Value $updates.OTZIV_CREDENTIAL_ENCRYPTION_ACTIVE_KEY_BASE64
Assert-Base64Key -Name 'DEPLOY_DB_BACKUP_ENCRYPTION_KEY_BASE64' -Value $updates.DEPLOY_DB_BACKUP_ENCRYPTION_KEY_BASE64
if ($updates.OTZIV_CREDENTIAL_ENCRYPTION_ACTIVE_KEY_BASE64 -ceq $updates.DEPLOY_DB_BACKUP_ENCRYPTION_KEY_BASE64) {
    throw 'Credential encryption and deploy DB-backup encryption must use different keys.'
}

$changedNames = @($updates.Keys | Where-Object {
    -not $existing.ContainsKey($_) -or $existing[$_] -cne [string]$updates[$_]
})
if ($changedNames.Count -eq 0) {
    Write-Host "Production deploy secrets are already initialized: $envPath"
    return
}

if ($PSCmdlet.ShouldProcess($envPath, "initialize $($changedNames.Count) production deploy setting(s)")) {
    $backupPath = "$envPath.pre-deploy-$(Get-Date -Format 'yyyyMMdd-HHmmss').bak"
    Copy-Item -LiteralPath $envPath -Destination $backupPath
    Protect-SensitivePath -Path $backupPath
    Set-EnvFileValuesAtomic -Path $envPath -Values $updates
    Write-Host "Production deploy settings initialized without printing secret values."
    Write-Host "Protected previous env copy: $backupPath"
    Write-Host "Updated keys: $($changedNames -join ', ')"
}
