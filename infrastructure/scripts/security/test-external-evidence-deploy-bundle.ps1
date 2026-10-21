$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot '../prod/DeploySnapshot.ps1')
$fixture = Join-Path ([IO.Path]::GetTempPath()) ('otziv-external-evidence-' + [Guid]::NewGuid().ToString('N'))
function Fixture-Git([string[]]$Arguments) {
    $value = @(& git -C $fixture @Arguments 2>&1)
    if ($LASTEXITCODE -ne 0) { throw "Fixture Git failed: $($Arguments[0])" }
    return ($value -join "`n").Trim()
}
function Fixture-Write([string]$Path, [string]$Value) {
    $file = Join-Path $fixture $Path
    [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($file)) | Out-Null
    [IO.File]::WriteAllText($file, $Value, [Text.UTF8Encoding]::new($false))
    return $file
}
function Fixture-Hash([string]$Path) { return (Get-FileHash -LiteralPath (Join-Path $fixture $Path) -Algorithm SHA256).Hash.ToLowerInvariant() }
function Assert-Rejected([string]$Directory, [string]$Expected) {
    $rejected = $false
    try { $null = New-OtzivPreparedDeployArchive -Repository $fixture -Revision $revision -Directory (Join-Path $fixture $Directory) -CoordinatedSslRefresh }
    catch {
        if ($_.Exception.Message -notlike $Expected) { throw }
        $rejected = $true
    }
    if (-not $rejected) { throw "Unsafe evidence was accepted: $Directory" }
}
try {
    [IO.Directory]::CreateDirectory($fixture) | Out-Null
    Fixture-Git @('init','-q') | Out-Null
    Fixture-Git @('config','user.name','External evidence test') | Out-Null
    Fixture-Git @('config','user.email','evidence@example.invalid') | Out-Null
    Fixture-Git @('config','core.autocrlf','false') | Out-Null
    $null = Fixture-Write 'docker-compose.yaml' 'services: {}'
    $null = Fixture-Write 'infrastructure/scripts/prod/deploy-prod.ps1' '$deployBundlePaths = @("docker-compose.yaml", "infrastructure/scripts/prod")'
    $null = Fixture-Write '.gitignore' "infrastructure/runtime-security/proofs/c26-keycloak/`n.codex-tmp/`n"
    $sourcePath = 'infrastructure/runtime-security/fixture-source.py'
    $null = Fixture-Write $sourcePath '# committed proof source'
    $loaderPath = 'infrastructure/runtime-security/evidence_archive.py'
    $repository = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
    $null = Fixture-Write $loaderPath ([IO.File]::ReadAllText((Join-Path $repository $loaderPath)))
    $null = Fixture-Write 'infrastructure/runtime-security/c36-parent-postgres.json' `
        ([IO.File]::ReadAllText((Join-Path $repository 'infrastructure/runtime-security/c36-parent-postgres.json')))
    $pgManifest = 'infrastructure/runtime-security/reviewed-images-c36-postgres.json'
    $kcManifest = 'infrastructure/runtime-security/reviewed-images-c35-keycloak.json'
    $null = Fixture-Write $pgManifest '{}'
    $null = Fixture-Write $kcManifest '{}'
    $proofPath = 'infrastructure/runtime-security/proofs/c26-keycloak/proof.json'
    $null = Fixture-Write $proofPath '{"result":"PASS"}'
    $evidencePaths = @($proofPath)
    $candidate = 'ghcr.io/claidd/otziv-security@sha256:' + ('a' * 64)
    $candidateId = 'sha256:' + ('b' * 64)
    $source = 'ghcr.io/claidd/otziv-security@sha256:c974c12a1107e314b1ba8cf65bd1fc5b6964645ced420c2151d336ee8a9a6534'
    $sourceId = 'sha256:1f5c2590fadde6b449908569285f1ed91a10831d36535d5c4ed647e9a0afca6a'
    $issuer = 'ghcr.io/claidd/otziv-security@sha256:c28364544835f5fbef6d4e0b0b3e63154127f7fb440b05432760f11235b5f4a9'
    $issuerId = 'sha256:60a8dd92cf43b8cf909a9e13f231da1f59103f9259cf482185c54856dcfd6a1b'
    $pgPath = 'infrastructure/runtime-security/proofs/c36-postgres/postgres-acceptance.json'
    $kcPath = 'infrastructure/runtime-security/proofs/c36-postgres/keycloak-acceptance.json'
    $run = '123'; $attempt = '1'; $commit = 'c' * 40
    $pgAccepted = @{ schema = 'otziv-postgres-c36-acceptance-v1'; result = 'PASS'; reference = $candidate
        parentReference = $source; parentConfigId = $sourceId; imageConfigId = $candidateId
        commit = $commit; run = $run; attempt = $attempt
        pair = @{ postgres = @{ reference = $candidate; imageConfigId = $candidateId }
                  keycloak = @{ reference = $issuer; imageConfigId = $issuerId } }
        files = @{ $proofPath = (Fixture-Hash $proofPath) }; executedSources = @{ $sourcePath = (Fixture-Hash $sourcePath) } }
    $kcAccepted = @{ schema = 'otziv-keycloak-vertx-c35-acceptance-v1'; result = 'PASS'; component = 'keycloak'
        reference = $issuer; imageConfigId = $issuerId; postgres = @{ reference = $source; imageConfigId = $sourceId }
        commit = $commit; run = $run; attempt = $attempt
        files = @{ $proofPath = (Fixture-Hash $proofPath) }; executedSources = @{ $sourcePath = (Fixture-Hash $sourcePath) } }
    $null = Fixture-Write $pgPath ($pgAccepted | ConvertTo-Json -Depth 9 -Compress)
    $null = Fixture-Write $kcPath ($kcAccepted | ConvertTo-Json -Depth 9 -Compress)
    $pgBound = @{ path = $pgPath; sha256 = (Fixture-Hash $pgPath) }
    $kcBound = @{ path = $kcPath; sha256 = (Fixture-Hash $kcPath) }
    $index = @{ images = @(
        @{ component = 'postgres'; reference = $candidate; commit = $commit; run = $run; attempt = $attempt
           manifest = @{ path = $pgManifest; sha256 = (Fixture-Hash $pgManifest) }
           postgresC36Acceptance = $pgBound; databaseTransition = $pgBound },
        @{ component = 'keycloak'; reference = $issuer; commit = $commit; run = $run; attempt = $attempt
           manifest = @{ path = $kcManifest; sha256 = (Fixture-Hash $kcManifest) }
           vertxRefreshAcceptance = $kcBound; migrationAcceptance = $kcBound }) }
    $null = Fixture-Write 'infrastructure/runtime-security/reviewed-image-activations.json' ($index | ConvertTo-Json -Depth 6 -Compress)
    $manifestPath = 'infrastructure/runtime-security/evidence-archive-c26.json'
    $files = @($evidencePaths | Sort-Object -CaseSensitive | ForEach-Object {
        @{ path = $_; sha256 = (Fixture-Hash $_); bytes = (Get-Item -LiteralPath (Join-Path $fixture $_)).Length; mode = '100644' }
    })
    $manifest = @{ schema = 'otziv-c26-evidence-archive-v1'; sourceRevision = ('a' * 40)
        archive = @{ url = 'https://github.com/Claidd/otziv_o/releases/download/runtime-evidence-c26-20261002/c26-evidence.zip'; sha256 = ('b' * 64); bytes = 10 }
        files = $files }
    $manifestFile = Fixture-Write $manifestPath ($manifest | ConvertTo-Json -Depth 6 -Compress)
    $manifestBytes = [IO.File]::ReadAllBytes($manifestFile)
    Fixture-Git @('add','docker-compose.yaml','.gitignore','infrastructure') | Out-Null
    Fixture-Git @('commit','-qm','committed external evidence fixture') | Out-Null
    $revision = Fixture-Git @('rev-parse','HEAD')
    # Never use executable loader edits or ambient ignored files from the checkout.
    $null = Fixture-Write $loaderPath 'raise RuntimeError("uncommitted loader must never run")'
    $null = Fixture-Write 'infrastructure/runtime-security/proofs/c26-keycloak/private-unlisted.log' 'must not ship'
    $prepared = New-OtzivPreparedDeployArchive -Repository $fixture -Revision $revision -Directory (Join-Path $fixture 'valid') -CoordinatedSslRefresh
    $stage = Join-Path $fixture 'valid/verified-source'
    foreach ($path in $evidencePaths) {
        if ((Get-FileHash -LiteralPath (Join-Path $stage $path) -Algorithm SHA256).Hash.ToLowerInvariant() -cne (Fixture-Hash $path)) {
            throw 'Bound external evidence bytes changed in the sealed bundle.'
        }
    }
    if (Test-Path -LiteralPath (Join-Path $stage 'infrastructure/runtime-security/proofs/c26-keycloak/private-unlisted.log')) { throw 'Unlisted external evidence entered the bundle.' }
    if ([IO.File]::ReadAllText((Join-Path $stage $loaderPath)) -like '*uncommitted loader must never run*') { throw 'Uncommitted loader entered the bundle.' }
    $installed = Join-Path $fixture 'installed'; [IO.Directory]::CreateDirectory($installed) | Out-Null
    Export-OtzivCommittedDeployBundle -Repository $fixture -Revision $revision -StageRoot $installed -InputPaths $prepared.paths -PreparedArchive $prepared.archive -PreparedArchiveSha256 $prepared.sha256
    if ((Get-FileHash -LiteralPath (Join-Path $installed $proofPath) -Algorithm SHA256).Hash.ToLowerInvariant() -cne (Fixture-Hash $proofPath)) { throw 'Sealed transport omitted external evidence.' }
    [IO.File]::AppendAllText($prepared.archive, 'tampered')
    $rejected = $false
    try { Export-OtzivCommittedDeployBundle -Repository $fixture -Revision $revision -StageRoot $installed -InputPaths $prepared.paths -PreparedArchive $prepared.archive -PreparedArchiveSha256 $prepared.sha256 }
    catch { if ($_.Exception.Message -notlike 'Prepared deployment archive changed*') { throw }; $rejected = $true }
    if (-not $rejected) { throw 'Tampered sealed external archive was accepted.' }
    [IO.File]::AppendAllText($manifestFile, ' ')
    Assert-Rejected 'changed-manifest' 'External evidence manifest differs from the committed deployment revision.'
    [IO.File]::WriteAllBytes($manifestFile, $manifestBytes)
    Remove-Item -LiteralPath $manifestFile
    Assert-Rejected 'missing-manifest' 'External evidence manifest is missing from the checkout or committed deployment revision.'
    [IO.File]::WriteAllBytes($manifestFile, $manifestBytes)
    $proofFile = Join-Path $fixture $proofPath; $proofBytes = [IO.File]::ReadAllBytes($proofFile)
    [IO.File]::AppendAllText($proofFile, 'changed')
    Assert-Rejected 'changed-evidence' 'Committed external evidence hydration failed.'
    [IO.File]::WriteAllBytes($proofFile, $proofBytes)
    Remove-Item -LiteralPath $proofFile
    $null = Fixture-Write ('.codex-tmp/runtime-evidence/' + ('b' * 64) + '.zip') 'bad-cache!'
    Assert-Rejected 'changed-cache' 'Committed external evidence hydration failed.'
    Write-Output 'External deploy evidence verified: exact committed manifest/loader, bounded bytes, sealed transport and tamper rejection.'
} finally {
    $resolved = [IO.Path]::GetFullPath($fixture)
    $temporary = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd([char[]]@('/','\')) + [IO.Path]::DirectorySeparatorChar
    if (-not $resolved.StartsWith($temporary, [StringComparison]::OrdinalIgnoreCase) -or
        [IO.Path]::GetFileName($resolved) -notmatch '^otziv-external-evidence-[a-f0-9]{32}$') { throw 'Unexpected fixture path.' }
    if (Test-Path -LiteralPath $resolved) { Remove-Item -LiteralPath $resolved -Recurse -Force }
}

# Expected failing child processes must not leak their status to the CI wrapper.
exit 0
