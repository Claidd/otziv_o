[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot '../prod/DeploySnapshot.ps1')

$fixtureRoot = Join-Path ([IO.Path]::GetTempPath()) ('otziv-snapshot-inputs-' + [Guid]::NewGuid().ToString('N'))
$previousLocation = Get-Location
$gitEnvironmentNames = @('GIT_INDEX_FILE', 'GIT_AUTHOR_NAME', 'GIT_AUTHOR_EMAIL', 'GIT_COMMITTER_NAME', 'GIT_COMMITTER_EMAIL')
$originalEnvironment = @{}
foreach ($name in $gitEnvironmentNames) { $originalEnvironment[$name] = [Environment]::GetEnvironmentVariable($name) }
function Invoke-FixtureGit {
    param([string[]]$GitArguments)
    $output = @(& git -C $fixtureRoot @GitArguments 2>&1)
    if ($LASTEXITCODE -ne 0) { throw "Fixture git command failed: $($GitArguments[0])" }
    return (($output | ForEach-Object { [string]$_ }) -join "`n").Trim()
}
function Write-FixtureFile {
    param([string]$RelativePath, [string]$Value)
    $target = Join-Path $fixtureRoot $RelativePath
    [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($target)) | Out-Null
    [IO.File]::WriteAllText($target, $Value, [Text.UTF8Encoding]::new($false))
}
try {
    [IO.Directory]::CreateDirectory($fixtureRoot) | Out-Null
    Invoke-FixtureGit @('init','--quiet') | Out-Null
    Invoke-FixtureGit @('config','user.name','Snapshot regression') | Out-Null
    Invoke-FixtureGit @('config','user.email','snapshot-regression@local.invalid') | Out-Null
    Invoke-FixtureGit @('config','core.autocrlf','false') | Out-Null
    $inputs = @(Get-OtzivDeployInputPaths)
    foreach ($inputPath in $inputs) {
        if ($inputPath -in @('backend','frontend','mobile','shared','contracts','whatsapp','infrastructure','docs','.github')) {
            Write-FixtureFile "$inputPath/baseline.txt" 'baseline'
        } else {
            Write-FixtureFile $inputPath 'baseline'
        }
    }
    Write-FixtureFile 'outside-input.txt' 'outside baseline'
    Invoke-FixtureGit @('add','-A') | Out-Null
    Invoke-FixtureGit @('commit','--quiet','-m','Baseline') | Out-Null
    $originalHead = Invoke-FixtureGit @('rev-parse','HEAD')
    Write-FixtureFile 'outside-input.txt' 'user staged content'
    Invoke-FixtureGit @('add','--','outside-input.txt') | Out-Null
    $originalIndexTree = Invoke-FixtureGit @('write-tree')
    $expected = [ordered]@{
        'shared/client-common/src/order-contract.generated.ts' = 'export type OrderId = number;'
        'contracts/generated/client-api.openapi.json' = '{"checkpoint":"current"}'
        'docs/ARCHITECTURE_REMEDIATION_REGISTRY_2026-09-07.json' = '{"scope":"current"}'
        'docs/WHATSAPP_INBOUND_DELIVERY_RUNBOOK.md' = 'current recovery instructions'
        'compose.monitoring.yaml' = 'services: {signals-publisher: {}}'
        'compose.monitoring-security-upgrade.yaml' = 'services: {grafana: {}}'
    }
    foreach ($path in $expected.Keys) { Write-FixtureFile $path $expected[$path] }
    Write-FixtureFile '.env.prod' 'PRIVATE_FIXTURE_MUST_NOT_ENTER_SNAPSHOT=true'
    $snapshot = New-OtzivDeploySnapshot -Repository $fixtureRoot -InputPaths $inputs
    foreach ($path in $expected.Keys) {
        $observed = Invoke-FixtureGit @('show',"$($snapshot.Commit):$path")
        if ($observed -cne $expected[$path]) { throw "Snapshot omitted current build/validation input: $path" }
    }
    if ((Invoke-FixtureGit @('rev-parse','HEAD')) -cne $originalHead) { throw 'Snapshot changed the checkout HEAD.' }
    $actualIndexTree = Invoke-FixtureGit @('write-tree')
    if ($actualIndexTree -cne $originalIndexTree) { throw "Snapshot changed the user index: $originalIndexTree -> $actualIndexTree; index=$env:GIT_INDEX_FILE" }
    if ((Invoke-FixtureGit @('show',"$($snapshot.Commit):outside-input.txt")) -cne 'outside baseline') {
        throw 'Snapshot included an unrelated staged change.'
    }
    $snapshotPaths = (Invoke-FixtureGit @('ls-tree','-r','--name-only',$snapshot.Commit)) -split "`n"
    if ('.env.prod' -in $snapshotPaths) { throw 'Snapshot included an external private input.' }
    foreach ($name in $gitEnvironmentNames) {
        if ([Environment]::GetEnvironmentVariable($name) -cne $originalEnvironment[$name]) {
            throw "Snapshot did not restore Git environment variable $name."
        }
    }
    # Exercise callers which already use a separate index, including failure
    # before commit creation. Neither path may replace or erase that index.
    $customIndex = Join-Path $fixtureRoot '.git/caller-index'
    Copy-Item -LiteralPath (Join-Path $fixtureRoot '.git/index') -Destination $customIndex
    $customIndexHash = (Get-FileHash -LiteralPath $customIndex).Hash
    $env:GIT_INDEX_FILE = $customIndex
    $env:GIT_AUTHOR_NAME = 'Caller author'
    $null = New-OtzivDeploySnapshot -Repository $fixtureRoot -InputPaths $inputs
    $failureObserved = $false
    try { $null = New-OtzivDeploySnapshot -Repository $fixtureRoot -InputPaths @('missing-snapshot-input') }
    catch {
        if ($_.Exception.Message -notlike 'Unable to add deployment inputs*') { throw }
        $failureObserved = $true
    }
    if (-not $failureObserved) { throw 'Expected snapshot input failure did not occur.' }
    if ($env:GIT_INDEX_FILE -cne $customIndex -or $env:GIT_AUTHOR_NAME -cne 'Caller author' -or
            (Get-FileHash -LiteralPath $customIndex).Hash -cne $customIndexHash) {
        throw 'Snapshot success/failure did not preserve caller environment and custom index.'
    }
    Write-Output 'Snapshot input regression passed: current shared/contracts/docs/monitoring inputs retained; user HEAD/index and unrelated/private inputs preserved.'
} finally {
    foreach ($name in $gitEnvironmentNames) {
        if ($null -eq $originalEnvironment[$name]) { Remove-Item -LiteralPath "Env:$name" -ErrorAction SilentlyContinue }
        else { [Environment]::SetEnvironmentVariable($name, $originalEnvironment[$name]) }
    }
    Set-Location -LiteralPath $previousLocation.Path
    if (Test-Path -LiteralPath $fixtureRoot) {
        $resolvedFixture = (Resolve-Path -LiteralPath $fixtureRoot).Path
        $resolvedTemp = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd([char[]]@('/','\')) + [IO.Path]::DirectorySeparatorChar
        if (-not $resolvedFixture.StartsWith($resolvedTemp,[StringComparison]::OrdinalIgnoreCase) -or
                [IO.Path]::GetFileName($resolvedFixture) -notmatch '^otziv-snapshot-inputs-[a-f0-9]{32}$') {
            throw 'Refusing to remove an unexpected snapshot fixture path.'
        }
        Remove-Item -LiteralPath $resolvedFixture -Recurse -Force
    }
}
