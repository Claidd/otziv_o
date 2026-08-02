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
foreach ($name in @('S3_ENDPOINT', 'S3_BUCKET', 'S3_ACCESS_KEY', 'S3_SECRET_KEY', 'BACKUP_RESTORE_DRILL_DATE')) {
    if ([string]::IsNullOrWhiteSpace((Get-Setting $name))) {
        $missing.Add($name)
    }
}
if (-not (Test-True (Get-Setting 'BACKUP_DESTINATION_PRIVATE_CONFIRMED'))) {
    $missing.Add('BACKUP_DESTINATION_PRIVATE_CONFIRMED=true')
}
if (-not (Test-True (Get-Setting 'BACKUP_ENCRYPTION_AT_REST_CONFIRMED'))) {
    $missing.Add('BACKUP_ENCRYPTION_AT_REST_CONFIRMED=true')
}
if (-not [string]::IsNullOrWhiteSpace((Get-Setting 'BACKUP_MAIL_TO')) -and
    -not (Test-True (Get-Setting 'BACKUP_EMAIL_DELIVERY_CONFIRMED'))) {
    $missing.Add('BACKUP_EMAIL_DELIVERY_CONFIRMED=true (required when BACKUP_MAIL_TO is set)')
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
