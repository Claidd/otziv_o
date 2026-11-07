$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot '../prod/DeploySnapshot.ps1')

# The C51 PostgreSQL refresh archive is sealed from an exact commit and carries the
# complete hash-bound C51 and C48 acceptance graph; a changed proof never ships.
$repository = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
$tempRoot = Join-Path $repository '.codex-tmp'
New-Item -ItemType Directory -Path $tempRoot -Force | Out-Null
$fixture = Join-Path $tempRoot ('postgres-reviewed-bundle-test-' + [Guid]::NewGuid().ToString('N'))

function Fixture-Git([string[]]$Arguments) {
    $output = @(& git -C $fixture @Arguments 2>&1)
    if ($LASTEXITCODE -ne 0) { throw "Fixture Git failed: $($Arguments[0])" }
    return ($output -join "`n").Trim()
}

try {
    $evidence = @(Get-OtzivCoordinatedPostgresBundlePaths -Repository $repository)
    foreach ($required in @('infrastructure/runtime-security/reviewed-image-activations.json',
        'infrastructure/runtime-security/c51-parent-postgres.json',
        'infrastructure/runtime-security/proofs/c51-postgres/acceptance.json',
        'infrastructure/runtime-security/proofs/c51-postgres/rehearsal/replay.json',
        'infrastructure/runtime-security/proofs/c51-postgres/rehearsal/capture-window.json',
        'infrastructure/runtime-security/postgres-c51-rehearsal.py',
        'infrastructure/runtime-security/proofs/c48-keycloak/acceptance.json')) {
        if ($required -notin $evidence) { throw "Reviewed PostgreSQL evidence inventory lacks $required" }
    }
    foreach ($path in $evidence) {
        $destination = Join-Path $fixture $path
        New-Item -ItemType Directory -Path (Split-Path -Parent $destination) -Force | Out-Null
        Copy-Item -LiteralPath (Join-Path $repository $path) -Destination $destination
    }
    $helpers = @('infrastructure/scripts/prod/coordinated_postgres_zlib_refresh.py',
        'infrastructure/scripts/prod/coordinated_ssl_refresh.py',
        'infrastructure/scripts/prod/database_image_guard.py')
    foreach ($helper in $helpers) {
        $helperDestination = Join-Path $fixture $helper
        New-Item -ItemType Directory -Path (Split-Path -Parent $helperDestination) -Force | Out-Null
        Copy-Item -LiteralPath (Join-Path $repository $helper) -Destination $helperDestination
    }
    [IO.File]::WriteAllText((Join-Path $fixture 'docker-compose.yaml'), 'name: otziv-prod')
    $inventory = '$deployBundlePaths = @("docker-compose.yaml", "' + ($helpers -join '", "') + '")'
    [IO.File]::WriteAllText((Join-Path $fixture 'infrastructure/scripts/prod/deploy-prod.ps1'), $inventory)
    Fixture-Git @('init', '-q') | Out-Null
    Fixture-Git @('config', 'core.autocrlf', 'false') | Out-Null
    Fixture-Git @('config', 'user.name', 'Reviewed PostgreSQL bundle test') | Out-Null
    Fixture-Git @('config', 'user.email', 'test@example.invalid') | Out-Null
    Fixture-Git @('add', '-A') | Out-Null
    Fixture-Git @('commit', '-qm', 'Reviewed PostgreSQL fixture') | Out-Null
    $revision = Fixture-Git @('rev-parse', 'HEAD')
    $output = Join-Path $fixture 'prepared'
    New-Item -ItemType Directory -Path $output | Out-Null
    $prepared = New-OtzivPreparedDeployArchive -Repository $fixture -Revision $revision `
        -Directory $output -CoordinatedPostgresRefresh
    foreach ($path in @($evidence + $helpers)) {
        $staged = Join-Path $output ('verified-source/' + $path)
        if ($path -notin $prepared.paths -or -not (Test-Path -LiteralPath $staged) -or
            (Get-FileHash -LiteralPath $staged -Algorithm SHA256).Hash -cne
            (Get-FileHash -LiteralPath (Join-Path $repository $path) -Algorithm SHA256).Hash) {
            throw "Reviewed PostgreSQL evidence missing or changed in the prepared archive: $path"
        }
    }
    $replay = Join-Path $fixture 'infrastructure/runtime-security/proofs/c51-postgres/rehearsal/replay.json'
    [IO.File]::AppendAllText($replay, 'tampered')
    $rejected = $false
    try { $null = Get-OtzivDeployBundlePaths -Repository $fixture -CoordinatedPostgresRefresh }
    catch {
        if ($_.Exception.Message -ne 'Accepted PostgreSQL refresh evidence changed.') { throw }
        $rejected = $true
    }
    if (-not $rejected) { throw 'Tampered reviewed PostgreSQL rehearsal was accepted.' }
    # An uncommitted change to a bound proof cannot replace the committed bytes in the archive.
    $second = Join-Path $fixture 'prepared-changed'
    New-Item -ItemType Directory -Path $second | Out-Null
    $rejected = $false
    try { $null = New-OtzivPreparedDeployArchive -Repository $fixture -Revision $revision -Directory $second -CoordinatedPostgresRefresh }
    catch {
        if ($_.Exception.Message -ne 'Accepted PostgreSQL refresh evidence changed.') { throw }
        $rejected = $true
    }
    if (-not $rejected) { throw 'An uncommitted PostgreSQL proof change entered the prepared archive.' }
    Write-Output 'c51-postgres committed archive includes the exactly bound C51/C48 proof and rejects altered rehearsal.'
} finally {
    $resolved = [IO.Path]::GetFullPath($fixture)
    $allowed = [IO.Path]::GetFullPath($tempRoot).TrimEnd([char[]]@('/', '\')) + [IO.Path]::DirectorySeparatorChar
    if (-not $resolved.StartsWith($allowed, [StringComparison]::OrdinalIgnoreCase) -or
        [IO.Path]::GetFileName($resolved) -notmatch '^postgres-reviewed-bundle-test-[a-f0-9]{32}$') {
        throw 'Unexpected reviewed PostgreSQL test fixture path.'
    }
    if (Test-Path -LiteralPath $resolved) { Remove-Item -LiteralPath $resolved -Recurse -Force }
}
