$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$gate = Join-Path $PSScriptRoot 'check-large-git-files.ps1'
$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
$git = (Get-Command git -CommandType Application | Select-Object -First 1).Source
$pwsh = (Get-Command pwsh -CommandType Application | Select-Object -First 1).Source
$fixtureRoot = Join-Path ([IO.Path]::GetTempPath()) ('otziv-large-blob-' + [Guid]::NewGuid().ToString('N'))
$null = New-Item -ItemType Directory -Path $fixtureRoot
$fixtureRoot = (Resolve-Path -LiteralPath $fixtureRoot).Path
$checks = 0
$proofs = @(
    @{
        Path = 'infrastructure/runtime-security/proofs/c14-keycloak-published/publication/registry-attestation-0-payload-0.json'
        Oid = '9dd9bf0dde1160d9b7e5d3eb4c587a73c676102f'
        Sha256 = 'ff7d3aa12697a69ba04ec48fe09d84392191c3665b716c9a524ded249911ce84'
    },
    @{
        Path = 'infrastructure/runtime-security/proofs/c14-keycloak-published/final/publication/registry-attestation-0-payload-0.json'
        Oid = '18b86fe9a8f30d6060af8f8f700984506a5c97ee'
        Sha256 = 'fc5b457ff5060c8c1ad29ad16da704f95e03bbb56626b64a9ed262f52f85e605'
    },
    @{
        Path = 'infrastructure/runtime-security/proofs/c14-keycloak-published/final/anonymous/registry-attestation-0-payload-0.json'
        Oid = '18b86fe9a8f30d6060af8f8f700984506a5c97ee'
        Sha256 = 'fc5b457ff5060c8c1ad29ad16da704f95e03bbb56626b64a9ed262f52f85e605'
    }
)

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
    if (-not $Condition) { throw "Large blob scenario failed: $Scenario" }
    $script:checks++
}

function Assert-Gate {
    param([string]$Mode, [bool]$Accepted, [string]$Scenario, [string]$ExpectedPath = '', [int]$Limit = 5)
    $arguments = @('-NoProfile', '-File', $gate, '-Mode', $Mode, '-MaxBlobMiB', [string]$Limit)
    if ($Mode -eq 'range') { $arguments += @('-BaseRevision', $baseline, '-TargetRevision', 'HEAD') }
    $result = Invoke-FixtureProcess -Executable $pwsh -Arguments $arguments
    if ($Accepted) {
        Assert-Value ($result.ExitCode -eq 0 -and $result.Output.Contains('Large Git blob gate passed')) "$Scenario ($Mode): $($result.Output)"
    } else {
        Assert-Value ($result.ExitCode -ne 0 -and $result.Output.Contains('Git blobs larger than') -and $result.Output.Contains($ExpectedPath)) "$Scenario ($Mode): $($result.Output)"
    }
}

try {
    # Extract only the pure predicate; importing the gate itself would run against the caller's index.
    $tokens = $null
    $parseErrors = $null
    $ast = [Management.Automation.Language.Parser]::ParseFile($gate, [ref]$tokens, [ref]$parseErrors)
    Assert-Value ($parseErrors.Count -eq 0) 'Gate parses'
    $predicate = $ast.Find({ param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -ceq 'Test-ReviewedFrozenSbom' }, $false)
    . ([scriptblock]::Create($predicate.Extent.Text))
    foreach ($proof in $proofs) {
        $source = Join-Path $repositoryRoot $proof.Path
        Assert-Value ((Get-Item -LiteralPath $source).Length -eq 5928324 -and (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash.ToLowerInvariant() -ceq $proof.Sha256) 'Frozen source SHA256 and exact size'
        Assert-Value (Test-ReviewedFrozenSbom $proof.Path $proof.Oid 5928324) 'Exact reviewed tuple accepted'
        Assert-Value (-not (Test-ReviewedFrozenSbom $proof.Path $proof.Oid 5928323)) 'Matching path and OID cannot bypass a smaller size'
        Assert-Value (-not (Test-ReviewedFrozenSbom $proof.Path $proof.Oid 5928325)) 'Matching path and OID cannot bypass a larger size'
        Assert-Value (-not (Test-ReviewedFrozenSbom $proof.Path.ToUpperInvariant() $proof.Oid 5928324)) 'Path matching is case sensitive'
        Assert-Value (-not (Test-ReviewedFrozenSbom ('prefix/' + $proof.Path) $proof.Oid 5928324)) 'Suffix matching cannot grant an exception'
    }
    Assert-Value (-not (Test-ReviewedFrozenSbom $proofs[0].Path $proofs[1].Oid 5928324)) 'Historical path cannot use final blob'
    Assert-Value (-not (Test-ReviewedFrozenSbom $proofs[1].Path $proofs[0].Oid 5928324)) 'Final path cannot use historical blob'

    $null = Invoke-FixtureGit @('init', '--quiet', '--initial-branch', 'fixture-base')
    $null = Invoke-FixtureGit @('config', 'user.name', 'Local blob gate fixture')
    $null = Invoke-FixtureGit @('config', 'user.email', 'blob-fixture@example.invalid')
    $null = Invoke-FixtureGit @('config', 'commit.gpgsign', 'false')
    $null = Invoke-FixtureGit @('config', 'core.autocrlf', 'false')
    $null = Invoke-FixtureGit @('config', 'core.hooksPath', (Join-Path $fixtureRoot '.empty-hooks'))
    $null = Invoke-FixtureGit @('commit', '--quiet', '--allow-empty', '-m', 'Synthetic base')
    $baseline = Invoke-FixtureGit @('rev-parse', 'HEAD')
    foreach ($proof in $proofs) {
        $destination = Join-Path $fixtureRoot $proof.Path
        $null = New-Item -ItemType Directory -Force -Path (Split-Path $destination)
        Copy-Item -LiteralPath (Join-Path $repositoryRoot $proof.Path) -Destination $destination
    }
    $null = Invoke-FixtureGit @('add', '--', '.')
    foreach ($proof in $proofs) {
        $actualOid = Invoke-FixtureGit @('rev-parse', (':' + $proof.Path))
        Assert-Value ($actualOid -ceq $proof.Oid) 'Actual staged Git blob has the reviewed identity'
    }
    Assert-Gate staged $true 'All three immutable SBOM files are accepted'
    Assert-Gate staged $false 'Explicit stricter limit is preserved' $proofs[0].Path 1
    $null = Invoke-FixtureGit @('commit', '--quiet', '-m', 'Reviewed immutable attestations')
    $acceptedCommit = Invoke-FixtureGit @('rev-parse', 'HEAD')
    Assert-Gate range $true 'Reviewed attestations are accepted in a commit range'
    Assert-Gate range $false 'Explicit stricter range limit is preserved' $proofs[0].Path 1

    $otherPath = 'unreviewed-sbom.json'
    Copy-Item -LiteralPath (Join-Path $fixtureRoot $proofs[1].Path) -Destination (Join-Path $fixtureRoot $otherPath)
    $null = Invoke-FixtureGit @('add', '--', $otherPath)
    Assert-Gate staged $false 'Identical approved blob at another path is rejected' $otherPath
    $null = Invoke-FixtureGit @('commit', '--quiet', '-m', 'Synthetic unreviewed path')
    Assert-Gate range $false 'Identical approved blob at another path is rejected' $otherPath

    # Reset only the freshly created, owned fixture repository, never the caller's index/worktree.
    $null = Invoke-FixtureGit @('reset', '--hard', $acceptedCommit)
    $changedPath = $proofs[1].Path
    $changedBytes = [IO.File]::ReadAllBytes((Join-Path $fixtureRoot $changedPath))
    $changedBytes[0] = $changedBytes[0] -bxor 1
    [IO.File]::WriteAllBytes((Join-Path $fixtureRoot $changedPath), $changedBytes)
    $null = Invoke-FixtureGit @('add', '--', $changedPath)
    Assert-Gate staged $false 'Changed content at approved path and identical byte size is rejected' $changedPath
    $null = Invoke-FixtureGit @('commit', '--quiet', '-m', 'Synthetic same-size mutation')
    Assert-Gate range $false 'Changed content at approved path and identical byte size is rejected' $changedPath
    Copy-Item -LiteralPath (Join-Path $repositoryRoot $changedPath) -Destination (Join-Path $fixtureRoot $changedPath)
    $null = Invoke-FixtureGit @('add', '--', $changedPath)
    $null = Invoke-FixtureGit @('commit', '--quiet', '-m', 'Restore reviewed final bytes')
    Assert-Gate range $false 'Restoring the final approved blob cannot hide an oversized intermediate commit' $changedPath

    $null = Invoke-FixtureGit @('reset', '--hard', $acceptedCommit)
    $oversized = 'ordinary-oversized.bin'
    [IO.File]::WriteAllBytes((Join-Path $fixtureRoot $oversized), [byte[]]::new(5MB + 1))
    $null = Invoke-FixtureGit @('add', '--', $oversized)
    Assert-Gate staged $false 'Default 5 MiB remains enforced for ordinary blobs' $oversized
    $null = Invoke-FixtureGit @('commit', '--quiet', '-m', 'Synthetic ordinary oversized blob')
    Assert-Gate range $false 'Default 5 MiB remains enforced for ordinary blobs' $oversized
    Write-Host "Large Git blob gate: $checks causal checks passed; owned local Git fixture only."
} finally {
    $resolvedFixture = (Resolve-Path -LiteralPath $fixtureRoot).Path
    $tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
    if ($resolvedFixture -ne $fixtureRoot -or -not $resolvedFixture.StartsWith($tempRoot, [StringComparison]::OrdinalIgnoreCase) -or
            -not ([IO.Path]::GetFileName($resolvedFixture)).StartsWith('otziv-large-blob-', [StringComparison]::Ordinal)) {
        throw 'Refusing cleanup outside the exact owned temporary fixture.'
    }
    Remove-Item -LiteralPath $resolvedFixture -Recurse -Force
}
