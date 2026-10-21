$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot '../prod/DeploySnapshot.ps1')
. (Join-Path $PSScriptRoot '../prod/ReleaseMetrics.ps1')
$fixture = Join-Path ([IO.Path]::GetTempPath()) ('otziv-early-prep-' + [Guid]::NewGuid().ToString('N'))
function Fixture-Git([string[]]$Arguments) {
    $value = @(& git -C $fixture @Arguments 2>&1)
    if ($LASTEXITCODE -ne 0) { throw "Fixture Git failed: $($Arguments[0])" }
    return ($value -join "`n").Trim()
}
try {
    New-Item -ItemType Directory -Path (Join-Path $fixture 'infrastructure/scripts/prod') -Force | Out-Null
    # Exercise the real deploy inventory. The optional SSL cutover is approved only
    # for image generations with an sslRefreshAcceptance proof on both components.
    # A tiny fixture alone misses extra assignments in the actual deploy script.
    $repository = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
    $ordinaryPaths = @(Get-OtzivDeployBundlePaths -Repository $repository)
    $activation = Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $repository 'infrastructure/runtime-security/reviewed-image-activations.json') | ConvertFrom-Json
    $keycloak = @($activation.images | Where-Object { $_.component -eq 'keycloak' })
    if ($keycloak.Count -ne 1) { throw 'Expected exactly one active Keycloak image.' }
    $sslApproved = [bool]$keycloak[0].PSObject.Properties['sslRefreshAcceptance']
    $extraPaths = @()
    if ($sslApproved) {
        $sslPaths = @(Get-OtzivDeployBundlePaths -Repository $repository -CoordinatedSslRefresh)
        $extraPaths = @(Get-OtzivCoordinatedSslBundlePaths -Repository $repository)
        if ($sslPaths.Count -le $ordinaryPaths.Count -or $extraPaths.Count -lt 10) {
            throw 'Coordinated SSL archive is missing accepted evidence.'
        }
    } else {
        $rejected = $false
        try { $null = Get-OtzivDeployBundlePaths -Repository $repository -CoordinatedSslRefresh }
        catch {
            if ($_.Exception.Message -ne 'Missing accepted SSL cutover evidence: keycloak') { throw }
            $rejected = $true
        }
        if (-not $rejected) { throw 'Unreviewed Keycloak generation entered the SSL cutover archive.' }
    }
    $realRevision = (& git -C $repository rev-parse HEAD).Trim()
    if ($LASTEXITCODE -ne 0) { throw 'Cannot read the actual repository revision.' }
    $realEvidence = Join-Path $fixture 'real-release-evidence'
    New-Item -ItemType Directory -Path $realEvidence | Out-Null
    $realPrepared = New-OtzivPreparedDeployArchive -Repository $repository -Revision $realRevision `
        -Directory $realEvidence -CoordinatedSslRefresh:$sslApproved
    foreach ($path in @($ordinaryPaths + $extraPaths | Select-Object -Unique)) {
        if ($path -notin $realPrepared.paths -or
            -not (Test-Path -LiteralPath (Join-Path $realEvidence ('verified-source/' + $path)))) {
            throw "Deployment input absent from the committed archive: $path"
        }
    }
    $fixtureSecurity = Join-Path $fixture 'infrastructure/runtime-security'
    New-Item -ItemType Directory -Path $fixtureSecurity | Out-Null
    $invalidIndex = @{ images = @(@{ component = 'postgres'; sslRefreshAcceptance = @{ path = '../local-secret.txt'; sha256 = ('a' * 64) } }) }
    $invalidIndex | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $fixtureSecurity 'reviewed-image-activations.json') -Encoding utf8
    $rejected = $false
    try { $null = Get-OtzivCoordinatedSslBundlePaths -Repository $fixture }
    catch {
        if ($_.Exception.Message -ne 'SSL cutover evidence must use bounded repository paths.') { throw }
        $rejected = $true
    }
    if (-not $rejected) { throw 'SSL evidence escaped the repository.' }
    $invalidIndex.images[0].sslRefreshAcceptance.path = 'infrastructure/runtime-security/invalid-proof.json'
    [IO.File]::WriteAllText((Join-Path $fixtureSecurity 'invalid-proof.json'), '{}')
    $invalidIndex | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $fixtureSecurity 'reviewed-image-activations.json') -Encoding utf8
    $rejected = $false
    try { $null = Get-OtzivCoordinatedSslBundlePaths -Repository $fixture }
    catch {
        if ($_.Exception.Message -ne 'Accepted SSL cutover evidence changed.') { throw }
        $rejected = $true
    }
    if (-not $rejected) { throw 'Changed accepted SSL evidence was packaged.' }
    Remove-Item -LiteralPath @((Join-Path $fixtureSecurity 'reviewed-image-activations.json'), (Join-Path $fixtureSecurity 'invalid-proof.json')) -Force
    Remove-Item -LiteralPath $fixtureSecurity -Force
    Fixture-Git @('init','-q') | Out-Null
    Fixture-Git @('config','user.name','Early preparation test') | Out-Null
    Fixture-Git @('config','user.email','prep@example.invalid') | Out-Null
    [IO.File]::WriteAllText((Join-Path $fixture 'docker-compose.yaml'), 'services: {}')
    [IO.File]::WriteAllText((Join-Path $fixture 'infrastructure/scripts/prod/deploy-prod.ps1'), '$deployBundlePaths = @("docker-compose.yaml", "infrastructure/scripts/prod")')
    Fixture-Git @('add','docker-compose.yaml','infrastructure/scripts/prod') | Out-Null
    Fixture-Git @('commit','-qm','fixture') | Out-Null
    $revision = Fixture-Git @('rev-parse','HEAD')
    [IO.File]::WriteAllText((Join-Path $fixture 'infrastructure/scripts/prod/local-secret.txt'), 'must not ship')
    $evidence = Join-Path $fixture 'evidence'
    New-Item -ItemType Directory -Path $evidence | Out-Null
    $prepared = New-OtzivPreparedDeployArchive -Repository $fixture -Revision $revision -Directory $evidence
    if (Test-Path -LiteralPath (Join-Path $evidence 'verified-source/infrastructure/scripts/prod/local-secret.txt')) {
        throw 'Untracked private file entered the prepared bundle.'
    }
    $stage = Join-Path $fixture 'installed'
    New-Item -ItemType Directory -Path $stage | Out-Null
    Export-OtzivCommittedDeployBundle -Repository $fixture -Revision $revision -StageRoot $stage `
        -InputPaths $prepared.paths -PreparedArchive $prepared.archive -PreparedArchiveSha256 $prepared.sha256
    if ([IO.File]::ReadAllText((Join-Path $stage 'docker-compose.yaml')) -cne 'services: {}') { throw 'Prepared content changed.' }
    [IO.File]::AppendAllText($prepared.archive, 'tampered')
    $rejected = $false
    try {
        Export-OtzivCommittedDeployBundle -Repository $fixture -Revision $revision -StageRoot $stage `
            -InputPaths $prepared.paths -PreparedArchive $prepared.archive -PreparedArchiveSha256 $prepared.sha256
    } catch {
        if ($_.Exception.Message -notlike 'Prepared deployment archive changed*') { throw }
        $rejected = $true
    }
    if (-not $rejected) { throw 'Changed prepared bundle was accepted.' }
    $timing = Join-Path $evidence 'timing.json'
    Set-OtzivReleaseStage -Path $timing -Stage 'prepare'
    Set-OtzivReleaseStage -Path $timing -Stage 'transport'
    Set-OtzivReleaseStage -Path $timing -Stage 'complete' -Result failure
    $value = Get-Content -LiteralPath $timing -Raw | ConvertFrom-Json
    if ($value.result -ne 'failure' -or $value.stages.Count -ne 2 -or $value.stages[-1].result -ne 'failure') {
        throw 'Failure timing did not preserve the completed preparation stage.'
    }
    Write-Output 'Early preparation verified. Committed source, identical delivered archive, tamper rejection and failure timing.'
} finally {
    $resolved = [IO.Path]::GetFullPath($fixture)
    $temporary = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd([char[]]@('/','\')) + [IO.Path]::DirectorySeparatorChar
    if (-not $resolved.StartsWith($temporary, [StringComparison]::OrdinalIgnoreCase) -or
        [IO.Path]::GetFileName($resolved) -notmatch '^otziv-early-prep-[a-f0-9]{32}$') { throw 'Unexpected fixture path.' }
    if (Test-Path -LiteralPath $resolved) { Remove-Item -LiteralPath $resolved -Recurse -Force }
}
exit 0
