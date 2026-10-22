$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot '../prod/DeploySnapshot.ps1')

$repository = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
$tempRoot = Join-Path $repository '.codex-tmp'
New-Item -ItemType Directory -Path $tempRoot -Force | Out-Null
$fixture = Join-Path $tempRoot ('mysql-c37-bundle-test-' + [Guid]::NewGuid().ToString('N'))

function Fixture-Git([string[]]$Arguments) {
    $output = @(& git -C $fixture @Arguments 2>&1)
    if ($LASTEXITCODE -ne 0) { throw "Fixture Git failed: $($Arguments[0])" }
    return ($output -join "`n").Trim()
}

try {
    $evidence = @(Get-OtzivCoordinatedMysqlBundlePaths -Repository $repository)
    if ($evidence.Count -ne 7) { throw 'C37 deployment evidence inventory changed.' }
    foreach ($path in $evidence) {
        $destination = Join-Path $fixture $path
        New-Item -ItemType Directory -Path (Split-Path -Parent $destination) -Force | Out-Null
        Copy-Item -LiteralPath (Join-Path $repository $path) -Destination $destination
    }
    $helper = 'infrastructure/scripts/prod/coordinated_mysql_refresh.py'
    $helperDestination = Join-Path $fixture $helper
    New-Item -ItemType Directory -Path (Split-Path -Parent $helperDestination) -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $repository $helper) -Destination $helperDestination
    [IO.File]::WriteAllText((Join-Path $fixture 'docker-compose.yaml'), 'name: otziv-prod')
    [IO.File]::WriteAllText((Join-Path $fixture 'infrastructure/scripts/prod/deploy-prod.ps1'),
        '$deployBundlePaths = @("docker-compose.yaml", "infrastructure/scripts/prod/coordinated_mysql_refresh.py")')
    Fixture-Git @('init', '-q') | Out-Null
    Fixture-Git @('config', 'core.autocrlf', 'false') | Out-Null
    Fixture-Git @('config', 'user.name', 'C37 bundle test') | Out-Null
    Fixture-Git @('config', 'user.email', 'test@example.invalid') | Out-Null
    Fixture-Git @('add', '-A') | Out-Null
    Fixture-Git @('commit', '-qm', 'C37 fixture') | Out-Null
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
            throw "C37 evidence missing from prepared archive: $path"
        }
    }
    $rehearsal = Join-Path $fixture 'infrastructure/runtime-security/proofs/c37-mysql/rehearsal.json'
    [IO.File]::AppendAllText($rehearsal, 'tampered')
    $rejected = $false
    try { $null = Get-OtzivDeployBundlePaths -Repository $fixture -CoordinatedMysqlRefresh }
    catch {
        if ($_.Exception.Message -ne 'Accepted MySQL C37 evidence changed.') { throw }
        $rejected = $true
    }
    if (-not $rejected) { throw 'Tampered C37 rehearsal was accepted.' }
    Write-Output 'C37 committed archive includes exactly bound proof, and rejects altered rehearsal.'
} finally {
    $resolved = [IO.Path]::GetFullPath($fixture)
    $allowed = [IO.Path]::GetFullPath($tempRoot).TrimEnd([char[]]@('/', '\')) + [IO.Path]::DirectorySeparatorChar
    if (-not $resolved.StartsWith($allowed, [StringComparison]::OrdinalIgnoreCase) -or
        [IO.Path]::GetFileName($resolved) -notmatch '^mysql-c37-bundle-test-[a-f0-9]{32}$') {
        throw 'Unexpected C37 test fixture path.'
    }
    if (Test-Path -LiteralPath $resolved) { Remove-Item -LiteralPath $resolved -Recurse -Force }
}
