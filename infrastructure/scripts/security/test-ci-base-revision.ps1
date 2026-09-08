$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot 'resolve-ci-base-revision.ps1')

$git = (Get-Command git -CommandType Application | Select-Object -First 1).Source
$pwsh = (Get-Command pwsh -CommandType Application | Select-Object -First 1).Source
$fixtureRoot = Join-Path ([IO.Path]::GetTempPath()) ('otziv-ci-base-' + [Guid]::NewGuid().ToString('N'))
$null = New-Item -ItemType Directory -Path $fixtureRoot
$fixtureRoot = (Resolve-Path -LiteralPath $fixtureRoot).Path
$utf8 = [Text.UTF8Encoding]::new($false)
$checks = 0

function Invoke-FixtureProcess {
    param([string]$Executable, [string[]]$Arguments)
    $info = [Diagnostics.ProcessStartInfo]::new()
    $info.FileName = $Executable
    $info.WorkingDirectory = $fixtureRoot
    $info.UseShellExecute = $false
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    foreach ($argument in $Arguments) { $info.ArgumentList.Add($argument) }
    foreach ($name in @('GIT_INDEX_FILE', 'GIT_DIR', 'GIT_WORK_TREE')) { $null = $info.Environment.Remove($name) }
    $process = [Diagnostics.Process]::Start($info)
    $stdout = $process.StandardOutput.ReadToEndAsync()
    $stderr = $process.StandardError.ReadToEndAsync()
    $process.WaitForExit()
    $result = [pscustomobject]@{ ExitCode = $process.ExitCode; Output = $stdout.GetAwaiter().GetResult() + $stderr.GetAwaiter().GetResult() }
    $process.Dispose()
    return $result
}

function Invoke-FixtureGit {
    param([string[]]$Arguments)
    $result = Invoke-FixtureProcess -Executable $git -Arguments (@('-c', "safe.directory=$fixtureRoot", '-C', $fixtureRoot) + $Arguments)
    if ($result.ExitCode -ne 0) { throw "Fixture git failed: $($result.Output)" }
    return $result.Output.Trim()
}

function Assert-Value {
    param([bool]$Condition, [string]$Scenario)
    if (-not $Condition) { throw "CI base scenario failed: $Scenario" }
    $script:checks++
}

function Assert-Rejected {
    param([scriptblock]$Operation, [string]$Scenario)
    $rejected = $false
    try { $null = & $Operation } catch { $rejected = $true }
    Assert-Value -Condition $rejected -Scenario $Scenario
}

try {
    $null = Invoke-FixtureGit -Arguments @('init', '--quiet', '--initial-branch', 'fixture-base')
    $null = Invoke-FixtureGit -Arguments @('config', 'user.name', 'Local CI fixture')
    $null = Invoke-FixtureGit -Arguments @('config', 'user.email', 'ci-fixture@example.invalid')
    $null = Invoke-FixtureGit -Arguments @('config', 'commit.gpgsign', 'false')
    $null = Invoke-FixtureGit -Arguments @('config', 'core.hooksPath', (Join-Path $fixtureRoot '.empty-hooks'))
    $migration = 'backend/src/main/resources/db/migration/V1__fixture.sql'
    $null = New-Item -ItemType Directory -Force -Path (Split-Path (Join-Path $fixtureRoot $migration))
    [IO.File]::WriteAllText((Join-Path $fixtureRoot $migration), "SELECT 1;`n", $utf8)
    $null = Invoke-FixtureGit -Arguments @('add', '--', '.')
    $null = Invoke-FixtureGit -Arguments @('commit', '--quiet', '-m', 'Synthetic base')
    $baseline = Invoke-FixtureGit -Arguments @('rev-parse', 'HEAD')
    # Local remote-tracking refs only: no remote repository or network command.
    $null = Invoke-FixtureGit -Arguments @('update-ref', 'refs/remotes/origin/trunk', $baseline)
    $null = Invoke-FixtureGit -Arguments @('symbolic-ref', 'refs/remotes/origin/HEAD', 'refs/remotes/origin/trunk')
    $null = New-Item -ItemType Directory -Force -Path (Join-Path $fixtureRoot 'mobile/builds')
    [IO.File]::WriteAllText((Join-Path $fixtureRoot 'mobile/builds/new.apk'), 'Synthetic fixture, not an APK', $utf8)
    [IO.File]::WriteAllText((Join-Path $fixtureRoot $migration), "SELECT 2;`n", $utf8)
    [IO.File]::WriteAllBytes((Join-Path $fixtureRoot 'oversized-fixture.bin'), [byte[]]::new(1MB + 1))
    $null = Invoke-FixtureGit -Arguments @('add', '--', '.')
    $null = Invoke-FixtureGit -Arguments @('commit', '--quiet', '-m', 'Synthetic changed artifacts')

    foreach ($missing in @('', '   ', ('0' * 40), ('0' * 64))) {
        $resolved = Resolve-CiBaseRevision -BaseRevision $missing -DefaultBranch trunk -RepositoryRoot $fixtureRoot
        Assert-Value -Condition ($resolved -ceq $baseline) -Scenario 'Blank/zero before resolves actual default-branch commit'
    }
    $resolved = Resolve-CiBaseRevision -BaseRevision $baseline -DefaultBranch nonexistent -RepositoryRoot $fixtureRoot
    Assert-Value -Condition ($resolved -ceq $baseline) -Scenario 'Explicit PR base is preserved, even if default ref is absent'
    $resolved = Resolve-CiBaseRevision -RepositoryRoot $fixtureRoot
    Assert-Value -Condition ($resolved -ceq $baseline) -Scenario 'Origin HEAD identifies the default when metadata is omitted'
    Assert-Rejected -Operation { Resolve-CiBaseRevision -BaseRevision ('a' * 40) -DefaultBranch trunk -RepositoryRoot $fixtureRoot } -Scenario 'Missing explicit PR commit cannot fall back to default'
    $blob = Invoke-FixtureGit -Arguments @('rev-parse', "HEAD:$migration")
    Assert-Rejected -Operation { Resolve-CiBaseRevision -BaseRevision $blob -RepositoryRoot $fixtureRoot } -Scenario 'Blob is not accepted as a commit'
    Assert-Rejected -Operation { Resolve-CiBaseRevision -DefaultBranch absent -RepositoryRoot $fixtureRoot } -Scenario 'Missing declared default branch fails closed'
    Assert-Rejected -Operation { Resolve-CiBaseRevision -DefaultBranch '../outside' -RepositoryRoot $fixtureRoot } -Scenario 'Invalid default branch is rejected'
    $null = Invoke-FixtureGit -Arguments @('symbolic-ref', '--delete', 'refs/remotes/origin/HEAD')
    Assert-Rejected -Operation { Resolve-CiBaseRevision -RepositoryRoot $fixtureRoot } -Scenario 'No authoritative default must not silently use HEAD'

    $global:LASTEXITCODE = 0
    $resolved = Resolve-CiBaseRevision -BaseRevision ('0' * 40) -DefaultBranch trunk -RepositoryRoot $fixtureRoot
    Assert-Value -Condition ($global:LASTEXITCODE -eq 0) -Scenario 'Resolution does not leak a failing native probe exit code'
    $diff = Invoke-FixtureGit -Arguments @('diff', '--name-status', $resolved, 'HEAD', '--')
    Assert-Value -Condition ($diff -match 'A\s+mobile/builds/new\.apk') -Scenario 'Resolved diff includes newly added retained-category artifact'
    Assert-Value -Condition ($diff -match 'M\s+backend/src/main/resources/db/migration/V1__fixture\.sql') -Scenario 'Resolved diff includes modification to existing migration'
    $flyway = Invoke-FixtureProcess -Executable $pwsh -Arguments @('-NoProfile', '-File', (Join-Path $PSScriptRoot 'check-flyway-contract.ps1'), '-BaseRevision', $resolved)
    Assert-Value -Condition ($flyway.ExitCode -ne 0 -and $flyway.Output -match 'Published Flyway migrations are append-only') -Scenario 'Actual Flyway gate rejects modified migration instead of skipping the zero-base diff'
    $large = Invoke-FixtureProcess -Executable $pwsh -Arguments @('-NoProfile', '-File', (Join-Path $PSScriptRoot 'check-large-git-files.ps1'), '-Mode', 'range', '-BaseRevision', $resolved, '-TargetRevision', 'HEAD', '-MaxBlobMiB', '1')
    Assert-Value -Condition ($large.ExitCode -ne 0 -and $large.Output -match 'oversized-fixture.bin') -Scenario 'Actual blob gate sees oversized new object through resolved range'
    Write-Host "CI base revision: $checks causal checks passed; local Git fixture only, original repository/index untouched."
} finally {
    $resolvedFixture = (Resolve-Path -LiteralPath $fixtureRoot).Path
    $tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
    if ($resolvedFixture -ne $fixtureRoot -or -not $resolvedFixture.StartsWith($tempRoot, [StringComparison]::OrdinalIgnoreCase) -or
            -not ([IO.Path]::GetFileName($resolvedFixture)).StartsWith('otziv-ci-base-', [StringComparison]::Ordinal)) {
        throw 'Refusing cleanup outside the exact owned temporary fixture.'
    }
    Remove-Item -LiteralPath $resolvedFixture -Recurse -Force
}
