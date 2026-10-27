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
    # Exercise the real deploy inventory. The optional cutover is approved only
    # for exact C36 PostgreSQL and C35 Keycloak with bound acceptance.
    # A tiny fixture alone misses extra assignments in the actual deploy script.
    $repository = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
    $ordinaryPaths = @(Get-OtzivDeployBundlePaths -Repository $repository)
    $activation = Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $repository 'infrastructure/runtime-security/reviewed-image-activations.json') | ConvertFrom-Json
    $keycloak = @($activation.images | Where-Object { $_.component -eq 'keycloak' })
    $postgres = @($activation.images | Where-Object { $_.component -eq 'postgres' })
    $mysql = @($activation.images | Where-Object { $_.component -eq 'mysql' })
    if ($keycloak.Count -ne 1 -or $postgres.Count -ne 1) { throw 'Expected one active PostgreSQL and Keycloak image.' }
    if ($mysql.Count -ne 1) { throw 'Expected one active MySQL image.' }
    $splitPath = 'infrastructure/runtime-security/proofs/c35-keycloak/publication/registry-attestation-0-payload-0.json'
    $splitAcceptance = Get-Content -Raw -Encoding UTF8 -LiteralPath `
        (Join-Path $repository 'infrastructure/runtime-security/proofs/c35-keycloak/acceptance.json') | ConvertFrom-Json
    $splitSha256 = $splitAcceptance.files.PSObject.Properties[$splitPath].Value
    $splitPhysicalPaths = @(Get-OtzivBoundSplitEvidencePaths -Repository $repository -Path $splitPath -Sha256 $splitSha256)
    $expectedSplitPaths = @("$splitPath.part001", "$splitPath.part002", "$splitPath.parts.json")
    if (@(Compare-Object $splitPhysicalPaths $expectedSplitPaths).Count -gt 0 -or
        $splitPath -in $splitPhysicalPaths) { throw 'Accepted split evidence was not bound to its physical parts.' }
    $splitFixture = Join-Path $fixture 'split'
    $splitFixtureDirectory = Join-Path $splitFixture (Split-Path -Parent $splitPath)
    New-Item -ItemType Directory -Path $splitFixtureDirectory -Force | Out-Null
    foreach ($path in $splitPhysicalPaths) {
        Copy-Item -LiteralPath (Join-Path $repository $path) -Destination (Join-Path $splitFixture $path)
    }
    $null = Get-OtzivBoundSplitEvidencePaths -Repository $splitFixture -Path $splitPath -Sha256 $splitSha256
    $changedPartPath = Join-Path $splitFixture "$splitPath.part002"
    $changedPart = [IO.File]::ReadAllBytes($changedPartPath)
    $changedPart[0] = $changedPart[0] -bxor 1
    [IO.File]::WriteAllBytes($changedPartPath, $changedPart)
    $rejectedSplit = $false
    try { $null = Get-OtzivBoundSplitEvidencePaths -Repository $splitFixture -Path $splitPath -Sha256 $splitSha256 }
    catch {
        if ($_.Exception.Message -ne 'Accepted SSL cutover evidence changed.') { throw }
        $rejectedSplit = $true
    }
    if (-not $rejectedSplit) { throw 'Changed split evidence entered the cutover archive.' }
    $sslApproved = $postgres[0].manifest.path -eq 'infrastructure/runtime-security/reviewed-images-c36-postgres.json' -and
        [bool]$postgres[0].PSObject.Properties['postgresC36Acceptance'] -and
        $keycloak[0].manifest.path -eq 'infrastructure/runtime-security/reviewed-images-c35-keycloak.json'
    $extraPaths = @()
    if ($sslApproved) {
        $sslPaths = @(Get-OtzivDeployBundlePaths -Repository $repository -CoordinatedSslRefresh)
        $extraPaths = @(Get-OtzivCoordinatedSslBundlePaths -Repository $repository)
        if ($sslPaths.Count -le $ordinaryPaths.Count -or $extraPaths.Count -lt 10) {
            throw 'Coordinated SSL archive is missing accepted evidence.'
        }
        if ($splitPath -in $sslPaths -or @($expectedSplitPaths | Where-Object { $_ -notin $sslPaths }).Count -gt 0) {
            throw 'Coordinated SSL archive lost committed split evidence.'
        }
    } else {
        $rejected = $false
        try { $null = Get-OtzivDeployBundlePaths -Repository $repository -CoordinatedSslRefresh }
        catch {
            if ($_.Exception.Message -ne 'Only the accepted C36 PostgreSQL and active C35 Keycloak pair permits this cutover.') { throw }
            $rejected = $true
        }
        if (-not $rejected) { throw 'Unaccepted PostgreSQL generation entered the cutover archive.' }
    }
    $mysqlApproved = $mysql[0].manifest.path -eq 'infrastructure/runtime-security/reviewed-images-c42-mysql.json' -and
        [bool]$mysql[0].PSObject.Properties['databaseTransition'] -and
        $mysql[0].databaseTransition.path -eq 'infrastructure/runtime-security/proofs/c42-mysql/rehearsal.json'
    $mysqlPaths = @()
    if ($mysqlApproved) {
        $mysqlPaths = @(Get-OtzivCoordinatedMysqlBundlePaths -Repository $repository)
        $combinedMysqlPaths = @(Get-OtzivDeployBundlePaths -Repository $repository -CoordinatedMysqlRefresh)
        if ($mysqlPaths.Count -lt 7 -or
            @($mysqlPaths | Where-Object { $_ -notin $combinedMysqlPaths }).Count -gt 0 -or
            'infrastructure/runtime-security/proofs/c42-mysql/rehearsal.json' -in $ordinaryPaths) {
            throw 'Reviewed MySQL C42 evidence entered the wrong deployment inventory.'
        }
        $tamperedRoot = Join-Path $fixture 'mysql-evidence'
        foreach ($path in $mysqlPaths) {
            $destination = Join-Path $tamperedRoot $path
            New-Item -ItemType Directory -Path (Split-Path -Parent $destination) -Force | Out-Null
            Copy-Item -LiteralPath (Join-Path $repository $path) -Destination $destination
        }
        $proof = Join-Path $tamperedRoot 'infrastructure/runtime-security/proofs/c42-mysql/rehearsal.json'
        [IO.File]::AppendAllText($proof, 'tampered')
        $rejectedMysql = $false
        try { $null = Get-OtzivCoordinatedMysqlBundlePaths -Repository $tamperedRoot }
        catch {
            if ($_.Exception.Message -ne 'Accepted MySQL cutover evidence changed.') { throw }
            $rejectedMysql = $true
        }
        if (-not $rejectedMysql) { throw 'Changed MySQL C42 rehearsal entered the deployment archive.' }
    } else {
        $rejectedMysql = $false
        try { $null = Get-OtzivDeployBundlePaths -Repository $repository -CoordinatedMysqlRefresh }
        catch {
            if ($_.Exception.Message -ne 'Only an exact accepted MySQL target permits this cutover.') { throw }
            $rejectedMysql = $true
        }
        if (-not $rejectedMysql) { throw 'Unaccepted MySQL image entered the coordinated archive.' }
    }
    $realRevision = (& git -C $repository rev-parse HEAD).Trim()
    if ($LASTEXITCODE -ne 0) { throw 'Cannot read the actual repository revision.' }
    $realEvidence = Join-Path $fixture 'real-release-evidence'
    New-Item -ItemType Directory -Path $realEvidence | Out-Null
    $realPrepared = New-OtzivPreparedDeployArchive -Repository $repository -Revision $realRevision `
        -Directory $realEvidence -CoordinatedMysqlRefresh:$mysqlApproved -CoordinatedSslRefresh:$sslApproved
    foreach ($path in @($ordinaryPaths + $extraPaths + $mysqlPaths | Select-Object -Unique)) {
        if ($path -notin $realPrepared.paths -or
            -not (Test-Path -LiteralPath (Join-Path $realEvidence ('verified-source/' + $path)))) {
            throw "Deployment input absent from the committed archive: $path"
        }
    }
    $fixtureSecurity = Join-Path $fixture 'infrastructure/runtime-security'
    New-Item -ItemType Directory -Path $fixtureSecurity | Out-Null
    [IO.File]::WriteAllBytes((Join-Path $fixtureSecurity 'c36-parent-postgres.json'),
        [IO.File]::ReadAllBytes((Join-Path $repository 'infrastructure/runtime-security/c36-parent-postgres.json')))
    $bad = @{ path = '../local-secret.txt'; sha256 = ('a' * 64) }
    $issuer = @{ path = 'infrastructure/runtime-security/issuer.json'; sha256 = ('b' * 64) }
    $pgManifestPath = Join-Path $fixtureSecurity 'reviewed-images-c36-postgres.json'
    $kcManifestPath = Join-Path $fixtureSecurity 'reviewed-images-c35-keycloak.json'
    [IO.File]::WriteAllText($pgManifestPath, '{}')
    [IO.File]::WriteAllText($kcManifestPath, '{}')
    $invalidIndex = @{ images = @(
        @{ component = 'postgres'; manifest = @{ path = 'infrastructure/runtime-security/reviewed-images-c36-postgres.json'; sha256 = (Get-FileHash -LiteralPath $pgManifestPath -Algorithm SHA256).Hash.ToLowerInvariant() }
           postgresC36Acceptance = $bad; databaseTransition = $bad },
        @{ component = 'keycloak'; manifest = @{ path = 'infrastructure/runtime-security/reviewed-images-c35-keycloak.json'; sha256 = (Get-FileHash -LiteralPath $kcManifestPath -Algorithm SHA256).Hash.ToLowerInvariant() }
           vertxRefreshAcceptance = $issuer; migrationAcceptance = $issuer }) }
    $invalidIndex | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $fixtureSecurity 'reviewed-image-activations.json') -Encoding utf8
    $rejected = $false
    try { $null = Get-OtzivCoordinatedSslBundlePaths -Repository $fixture }
    catch {
        if ($_.Exception.Message -ne 'SSL cutover evidence must use bounded repository paths.') { throw }
        $rejected = $true
    }
    if (-not $rejected) { throw 'SSL evidence escaped the repository.' }
    $invalidIndex.images[0].postgresC36Acceptance.path = 'infrastructure/runtime-security/invalid-proof.json'
    [IO.File]::WriteAllText((Join-Path $fixtureSecurity 'invalid-proof.json'), '{}')
    $invalidIndex | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $fixtureSecurity 'reviewed-image-activations.json') -Encoding utf8
    $rejected = $false
    try { $null = Get-OtzivCoordinatedSslBundlePaths -Repository $fixture }
    catch {
        if ($_.Exception.Message -ne 'Accepted SSL cutover evidence changed.') { throw }
        $rejected = $true
    }
    if (-not $rejected) { throw 'Changed accepted SSL evidence was packaged.' }
    Remove-Item -LiteralPath @((Join-Path $fixtureSecurity 'reviewed-image-activations.json'), (Join-Path $fixtureSecurity 'invalid-proof.json'), (Join-Path $fixtureSecurity 'c36-parent-postgres.json'), $pgManifestPath, $kcManifestPath) -Force
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
