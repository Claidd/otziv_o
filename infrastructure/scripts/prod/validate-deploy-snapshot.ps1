param(
    [Parameter(Mandatory = $true)][string]$RepoRoot,
    [Parameter(Mandatory = $true)][string]$BaseRevision,
    [switch]$FastBackendValidation
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$env:NG_CLI_ANALYTICS = 'false'

function Invoke-SnapshotCheck {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$WorkingDirectory,
        [Parameter(Mandatory = $true)][string]$FilePath,
        [hashtable]$Parameters = @{},
        [string[]]$Arguments = @()
    )

    Write-Host "Snapshot check: $Name"
    Push-Location $WorkingDirectory
    try {
        if ($Parameters.Count -gt 0 -and $Arguments.Count -gt 0) {
            throw "$Name mixes named and positional command parameters; validation is ambiguous."
        }
        if ($Parameters.Count -gt 0) {
            & $FilePath @Parameters
        } else {
            & $FilePath @Arguments
        }
        if ($LASTEXITCODE -ne 0) {
            throw "$Name failed with exit code $LASTEXITCODE. Production deployment remains blocked."
        }
    } finally {
        Pop-Location
    }
}

$root = (Resolve-Path -LiteralPath $RepoRoot).Path
$headRevision = (& git -C $root rev-parse --verify 'HEAD^{commit}').Trim()
if ($LASTEXITCODE -ne 0 -or $headRevision -notmatch '^[0-9a-f]{40}$') {
    throw 'Unable to resolve the prepared deploy snapshot revision.'
}
$base = (& git -C $root rev-parse --verify "${BaseRevision}^{commit}").Trim()
if ($LASTEXITCODE -ne 0 -or $base -notmatch '^[0-9a-f]{40}$') {
    throw 'Unable to resolve the deploy snapshot base revision.'
}

$changedFiles = @(& git -C $root diff --name-only --diff-filter=ACDMRTUXB $base $headRevision)
if ($LASTEXITCODE -ne 0 -or $changedFiles.Count -eq 0) {
    throw 'Prepared deploy snapshot has no verifiable changes.'
}
Write-Host "Validating deploy snapshot $headRevision ($($changedFiles.Count) changed files)."

$securityRoot = Join-Path $root 'infrastructure\scripts\security'
Invoke-SnapshotCheck -Name 'repository hygiene' -WorkingDirectory $root `
    -FilePath (Join-Path $securityRoot 'check-repository-hygiene.ps1') -Parameters @{ BaseRevision = $base }
Invoke-SnapshotCheck -Name 'large Git blob gate' -WorkingDirectory $root `
    -FilePath (Join-Path $securityRoot 'check-large-git-files.ps1') `
    -Parameters @{ Mode = 'range'; BaseRevision = $base; TargetRevision = $headRevision }
Invoke-SnapshotCheck -Name 'Flyway append-only contract' -WorkingDirectory $root `
    -FilePath (Join-Path $securityRoot 'check-flyway-contract.ps1') -Parameters @{ BaseRevision = $base }
Invoke-SnapshotCheck -Name 'infrastructure security contract' -WorkingDirectory $root `
    -FilePath (Join-Path $securityRoot 'check-infrastructure-contract.ps1')
Invoke-SnapshotCheck -Name 'production release contract' -WorkingDirectory $root `
    -FilePath (Join-Path $securityRoot 'check-deploy-release-contract.ps1')
Invoke-SnapshotCheck -Name 'secret scan' -WorkingDirectory $root `
    -FilePath (Join-Path $securityRoot 'run-secret-scan.ps1') -Parameters @{ Mode = 'dir' }

$composeFiles = @('compose.yaml', 'docker-compose.yaml', 'compose.prod-local.yaml', 'docker-compose.build.yaml')
$previousMultibrowserApiKey = [Environment]::GetEnvironmentVariable('MULTIBROWSER_API_KEY', 'Process')
try {
    # Compose must validate required-variable wiring without depending on or exposing
    # the operator's real production credential. This value never leaves validation.
    [Environment]::SetEnvironmentVariable(
        'MULTIBROWSER_API_KEY',
        'snapshot-compose-model-validation-placeholder',
        'Process'
    )
    foreach ($composeFile in $composeFiles) {
        Invoke-SnapshotCheck -Name "Compose model $composeFile" -WorkingDirectory $root `
            -FilePath 'docker' -Arguments @('compose', '--env-file', '.env.example', '-f', $composeFile, 'config', '--quiet')
    }
} finally {
    [Environment]::SetEnvironmentVariable('MULTIBROWSER_API_KEY', $previousMultibrowserApiKey, 'Process')
}

$hasBackendChanges = @($changedFiles | Where-Object { $_ -like 'backend/*' }).Count -gt 0
$hasFrontendChanges = @($changedFiles | Where-Object { $_ -like 'frontend/*' }).Count -gt 0
$hasMobileChanges = @($changedFiles | Where-Object { $_ -like 'mobile/*' }).Count -gt 0
$hasWhatsAppChanges = @($changedFiles | Where-Object { $_ -like 'whatsapp/*' }).Count -gt 0
$hasExternalWorkerChanges = @($changedFiles | Where-Object { $_ -like 'backend/external-review-worker/*' }).Count -gt 0
$hasFlywayMigrationChanges = @($changedFiles | Where-Object { $_ -like 'backend/src/main/resources/db/migration/*' }).Count -gt 0

if ($hasFrontendChanges) {
    $frontend = Join-Path $root 'frontend'
    Invoke-SnapshotCheck -Name 'frontend dependency install' -WorkingDirectory $frontend `
        -FilePath 'corepack' -Arguments @('npm', 'ci', '--no-audit', '--no-fund')
    Invoke-SnapshotCheck -Name 'frontend unit tests' -WorkingDirectory $frontend `
        -FilePath 'corepack' -Arguments @('npm', 'test', '--', '--watch=false')
    Invoke-SnapshotCheck -Name 'frontend production build' -WorkingDirectory $frontend `
        -FilePath 'corepack' -Arguments @('npm', 'run', 'build', '--', '--configuration', 'production')
}

if ($hasMobileChanges) {
    $mobile = Join-Path $root 'mobile'
    Invoke-SnapshotCheck -Name 'mobile dependency install' -WorkingDirectory $mobile `
        -FilePath 'corepack' -Arguments @('npm', 'ci', '--no-audit', '--no-fund')
    Invoke-SnapshotCheck -Name 'mobile unit tests' -WorkingDirectory $mobile `
        -FilePath 'corepack' -Arguments @('npm', 'run', 'test:unit')
    Invoke-SnapshotCheck -Name 'mobile production build' -WorkingDirectory $mobile `
        -FilePath 'corepack' -Arguments @('npm', 'run', 'build:prod')
}

if ($hasWhatsAppChanges) {
    $whatsApp = Join-Path $root 'whatsapp'
    Invoke-SnapshotCheck -Name 'WhatsApp dependency install' -WorkingDirectory $whatsApp `
        -FilePath 'corepack' -Arguments @('npm', 'ci', '--ignore-scripts', '--no-audit', '--no-fund')
    Invoke-SnapshotCheck -Name 'WhatsApp tests' -WorkingDirectory $whatsApp `
        -FilePath 'corepack' -Arguments @('npm', 'test')
}

if ($hasExternalWorkerChanges) {
    $externalWorker = Join-Path $root 'backend\external-review-worker'
    Invoke-SnapshotCheck -Name 'external worker dependency install' -WorkingDirectory $externalWorker `
        -FilePath 'corepack' -Arguments @('npm', 'ci', '--ignore-scripts', '--no-audit', '--no-fund')
    Get-ChildItem -LiteralPath (Join-Path $externalWorker 'src') -Recurse -File -Filter '*.js' | ForEach-Object {
        Invoke-SnapshotCheck -Name "external worker syntax $($_.Name)" -WorkingDirectory $externalWorker `
            -FilePath 'node' -Arguments @('--check', $_.FullName)
    }
    Invoke-SnapshotCheck -Name 'external worker tests' -WorkingDirectory $externalWorker `
        -FilePath 'corepack' -Arguments @('npm', 'test')
}

if ($hasBackendChanges) {
    if ($FastBackendValidation -and -not $hasFlywayMigrationChanges) {
        Write-Warning 'Fast backend validation active: skipping backend full Maven verify and running package with tests skipped.'
        if ([System.Environment]::OSVersion.Platform -eq [System.PlatformID]::Win32NT) {
            Invoke-SnapshotCheck -Name 'backend package without tests' -WorkingDirectory (Join-Path $root 'backend') `
                -FilePath '.\mvnw.cmd' -Arguments @('-B', '-ntp', '-DskipTests', 'package')
        } else {
            Invoke-SnapshotCheck -Name 'backend package without tests' -WorkingDirectory (Join-Path $root 'backend') `
                -FilePath 'bash' -Arguments @('./mvnw', '-B', '-ntp', '-DskipTests', 'package')
        }
    } elseif ([System.Environment]::OSVersion.Platform -eq [System.PlatformID]::Win32NT) {
        if ($FastBackendValidation -and $hasFlywayMigrationChanges) {
            Write-Warning 'Fast backend validation requested, but Flyway migrations changed; running backend full Maven verify.'
        }
        Invoke-SnapshotCheck -Name 'backend full test suite' -WorkingDirectory (Join-Path $root 'backend') `
            -FilePath '.\mvnw.cmd' -Arguments @('-B', '-ntp', 'verify')
    } else {
        if ($FastBackendValidation -and $hasFlywayMigrationChanges) {
            Write-Warning 'Fast backend validation requested, but Flyway migrations changed; running backend full Maven verify.'
        }
        Invoke-SnapshotCheck -Name 'backend full test suite' -WorkingDirectory (Join-Path $root 'backend') `
            -FilePath 'bash' -Arguments @('./mvnw', '-B', '-ntp', 'verify')
    }
}

Write-Host 'Automatic deploy snapshot validation passed.'
