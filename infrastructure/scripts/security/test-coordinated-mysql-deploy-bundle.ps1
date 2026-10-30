param([ValidateSet('c37-mysql', 'c42-mysql', 'c46-mysql')][string]$Set = 'c42-mysql')

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot '../prod/DeploySnapshot.ps1')

$repository = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
$tempRoot = Join-Path $repository '.codex-tmp'
New-Item -ItemType Directory -Path $tempRoot -Force | Out-Null
$fixture = Join-Path $tempRoot ('mysql-reviewed-bundle-test-' + [Guid]::NewGuid().ToString('N'))

function Fixture-Git([string[]]$Arguments) {
    $output = @(& git -C $fixture @Arguments 2>&1)
    if ($LASTEXITCODE -ne 0) { throw "Fixture Git failed: $($Arguments[0])" }
    return ($output -join "`n").Trim()
}

try {
    $activationPath = 'infrastructure/runtime-security/reviewed-image-activations.json'
    if ($Set -eq 'c37-mysql') {
        $entry = Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $repository 'infrastructure/runtime-security/proofs/c42-mysql/parent-activation.json') | ConvertFrom-Json
    } elseif ($Set -eq 'c42-mysql') {
        $entry = Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $repository 'infrastructure/runtime-security/proofs/c46-mysql/parent-activation.json') | ConvertFrom-Json
    } else {
        $activation = Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $repository $activationPath) | ConvertFrom-Json
        $entry = @($activation.images | Where-Object { $_.component -eq 'mysql' })[0]
    }
    $scriptPath = 'infrastructure/runtime-security/mysql-' + ($Set -replace '-mysql$', '') + '-rehearsal.py'
    $evidence = @($activationPath, $entry.manifest.path, $entry.publication.path, $entry.anonymous.path,
        $entry.databaseTransition.path, $scriptPath, ('infrastructure/runtime-security/proofs/' + $Set + '/parent-activation.json'))
    if ($evidence.Count -ne 7) { throw 'Reviewed MySQL deployment evidence inventory changed.' }
    foreach ($path in $evidence) {
        $destination = Join-Path $fixture $path
        New-Item -ItemType Directory -Path (Split-Path -Parent $destination) -Force | Out-Null
        Copy-Item -LiteralPath (Join-Path $repository $path) -Destination $destination
    }
    # Historical C37/C42 fixtures remain separate from the current reviewed default.
    $fixtureActivation = @{ schema = 'otziv-reviewed-image-activations-v1'; images = @($entry) }
    [IO.File]::WriteAllText((Join-Path $fixture $activationPath), ($fixtureActivation | ConvertTo-Json -Depth 20))
    $null = Get-OtzivCoordinatedMysqlBundlePaths -Repository $fixture
    $helpers = @('infrastructure/scripts/prod/coordinated_mysql_refresh.py',
        'infrastructure/scripts/prod/coordinated_mysql_brotli_refresh.py',
        'infrastructure/scripts/prod/coordinated_mysql_curl_refresh.py',
        'infrastructure/scripts/prod/coordinated_mysql_cutover.py')
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
    Fixture-Git @('config', 'user.name', 'Reviewed MySQL bundle test') | Out-Null
    Fixture-Git @('config', 'user.email', 'test@example.invalid') | Out-Null
    Fixture-Git @('add', '-A') | Out-Null
    Fixture-Git @('commit', '-qm', 'Reviewed MySQL fixture') | Out-Null
    $revision = Fixture-Git @('rev-parse', 'HEAD')
    $output = Join-Path $fixture 'prepared'
    New-Item -ItemType Directory -Path $output | Out-Null
    try {
        $prepared = New-OtzivPreparedDeployArchive -Repository $fixture -Revision $revision `
            -Directory $output -CoordinatedMysqlRefresh
    } catch {
        foreach ($path in $evidence) {
            $original = Join-Path $fixture $path
            $staged = Join-Path $output ('verified-source/' + $path)
            if (-not (Test-Path -LiteralPath $staged)) {
                Write-Error "Missing staged evidence: $path" -ErrorAction Continue
            } elseif ((Get-FileHash -LiteralPath $original -Algorithm SHA256).Hash -cne
                (Get-FileHash -LiteralPath $staged -Algorithm SHA256).Hash) {
                Write-Error "Changed staged evidence: $path" -ErrorAction Continue
            }
        }
        throw
    }
    foreach ($path in $evidence) {
        if ($path -notin $prepared.paths -or
            -not (Test-Path -LiteralPath (Join-Path $output ('verified-source/' + $path)))) {
            throw "Reviewed MySQL evidence missing from prepared archive: $path"
        }
    }
    $rehearsal = Join-Path $fixture ('infrastructure/runtime-security/proofs/' + $Set + '/rehearsal.json')
    [IO.File]::AppendAllText($rehearsal, 'tampered')
    $rejected = $false
    try { $null = Get-OtzivDeployBundlePaths -Repository $fixture -CoordinatedMysqlRefresh }
    catch {
        if ($_.Exception.Message -ne 'Accepted MySQL cutover evidence changed.') { throw }
        $rejected = $true
    }
    if (-not $rejected) { throw 'Tampered Reviewed MySQL rehearsal was accepted.' }
    Write-Output "$Set committed archive includes exactly bound proof, and rejects altered rehearsal."
} finally {
    $resolved = [IO.Path]::GetFullPath($fixture)
    $allowed = [IO.Path]::GetFullPath($tempRoot).TrimEnd([char[]]@('/', '\')) + [IO.Path]::DirectorySeparatorChar
    if (-not $resolved.StartsWith($allowed, [StringComparison]::OrdinalIgnoreCase) -or
        [IO.Path]::GetFileName($resolved) -notmatch '^mysql-reviewed-bundle-test-[a-f0-9]{32}$') {
        throw 'Unexpected Reviewed MySQL test fixture path.'
    }
    if (Test-Path -LiteralPath $resolved) { Remove-Item -LiteralPath $resolved -Recurse -Force }
}
