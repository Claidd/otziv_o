Set-StrictMode -Version Latest

function Get-OtzivDeployInputPaths {
    return @(
        'backend',
        'frontend',
        'mobile',
        'shared',
        'contracts',
        'whatsapp',
        'infrastructure',
        'docs',
        'docs/WHATSAPP_INBOUND_DELIVERY_RUNBOOK.md',
        'docs/WHATSAPP_REMOTE_SESSION_RECOVERY.md',
        '.github',
        'docker-compose.yaml',
        'docker-compose.build.yaml',
        'compose.yaml',
        'compose.prod-local.yaml',
        'compose.monitoring.yaml',
        'compose.monitoring-security-upgrade.yaml',
        'Dockerfile.whatsapp',
        '.dockerignore',
        '.env.example',
        '.env.prod.example',
        '.env.prod-local.example',
        '.gitattributes',
        '.gitignore',
        '.gitleaks.toml'
    )
}

function Invoke-OtzivSnapshotGitText {
    param(
        [Parameter(Mandatory = $true)][string]$Repository,
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [Parameter(Mandatory = $true)][string]$FailureMessage
    )

    $result = Invoke-OtzivSnapshotGit -Repository $Repository -Arguments $Arguments
    if ($result.ExitCode -ne 0) {
        throw $FailureMessage
    }
    return (($result.Output | ForEach-Object { [string]$_ }) -join [Environment]::NewLine).Trim()
}

function Invoke-OtzivSnapshotGit {
    param(
        [Parameter(Mandatory = $true)][string]$Repository,
        [Parameter(Mandatory = $true)][string[]]$Arguments
    )

    # Windows PowerShell 5.1 turns redirected native stderr into ErrorRecord
    # objects. Under the deploy script's ErrorActionPreference=Stop that can
    # throw before LASTEXITCODE is inspected. Keep native failures as explicit
    # result data so every caller can fail closed with its own stable message.
    $previousErrorActionPreference = $ErrorActionPreference
    $nativePreference = Get-Variable -Name PSNativeCommandUseErrorActionPreference -ErrorAction SilentlyContinue
    try {
        $ErrorActionPreference = 'Continue'
        if ($null -ne $nativePreference) {
            Set-Variable -Name PSNativeCommandUseErrorActionPreference -Value $false -Scope Local
        }
        $output = @(& git --no-replace-objects -C $Repository @Arguments 2>&1)
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
        if ($null -ne $nativePreference) {
            Set-Variable -Name PSNativeCommandUseErrorActionPreference -Value $nativePreference.Value -Scope Local
        }
    }

    return [pscustomobject]@{
        ExitCode = $exitCode
        Output = [object[]]@($output)
    }
}

function Get-OtzivExactCommitRevision {
    param(
        [Parameter(Mandatory = $true)][string]$Repository,
        [Parameter(Mandatory = $true)][string]$Revision,
        [Parameter(Mandatory = $true)][string]$FailureMessage
    )

    $resolved = Invoke-OtzivSnapshotGitText -Repository $Repository `
        -Arguments @('rev-parse', '--verify', "${Revision}^{commit}") `
        -FailureMessage $FailureMessage
    if ($resolved -notmatch '^[0-9a-f]{40}$') {
        throw $FailureMessage
    }
    return $resolved
}

function Update-OtzivProductionMainRevision {
    param([Parameter(Mandatory = $true)][string]$Repository)

    $fetchResult = Invoke-OtzivSnapshotGit -Repository $Repository `
        -Arguments @('fetch', '--quiet', '--no-tags', 'origin',
            '+refs/heads/main:refs/remotes/origin/main')
    if ($fetchResult.ExitCode -ne 0) {
        throw 'Unable to fetch the protected production branch origin/main. Production deployment remains blocked.'
    }

    return Get-OtzivExactCommitRevision -Repository $Repository `
        -Revision 'refs/remotes/origin/main' `
        -FailureMessage 'Unable to resolve the protected production branch origin/main. Production deployment remains blocked.'
}

function Assert-OtzivProductionMainRevisionUnchanged {
    param(
        [Parameter(Mandatory = $true)][string]$SelectedRevision,
        [Parameter(Mandatory = $true)][string]$RefreshedRevision
    )

    if ($SelectedRevision -notmatch '^[0-9a-f]{40}$' -or
        $RefreshedRevision -notmatch '^[0-9a-f]{40}$') {
        throw 'Production-main stability verification requires exact 40-character Git revisions.'
    }
    if ($SelectedRevision -cne $RefreshedRevision) {
        throw "Protected origin/main advanced from $SelectedRevision to $RefreshedRevision while the deploy snapshot was being prepared. Restart deployment from the updated main line."
    }
}

function Assert-OtzivDeployRevisionContainsProductionMain {
    param(
        [Parameter(Mandatory = $true)][string]$Repository,
        [Parameter(Mandatory = $true)][string]$ProductionMainRevision,
        [Parameter(Mandatory = $true)][string]$DeployRevision
    )

    if ($ProductionMainRevision -notmatch '^[0-9a-f]{40}$' -or
        $DeployRevision -notmatch '^[0-9a-f]{40}$') {
        throw 'Production lineage verification requires exact 40-character Git revisions.'
    }

    $resolvedProductionMain = Get-OtzivExactCommitRevision -Repository $Repository `
        -Revision $ProductionMainRevision `
        -FailureMessage 'The protected production-main revision is unavailable. Production deployment remains blocked.'
    $resolvedDeployRevision = Get-OtzivExactCommitRevision -Repository $Repository `
        -Revision $DeployRevision `
        -FailureMessage 'The requested deploy revision is unavailable. Production deployment remains blocked.'

    $ancestryResult = Invoke-OtzivSnapshotGit -Repository $Repository `
        -Arguments @('merge-base', '--is-ancestor', $resolvedProductionMain, $resolvedDeployRevision)
    $ancestryExitCode = $ancestryResult.ExitCode
    if ($ancestryExitCode -eq 0) {
        return
    }
    if ($ancestryExitCode -eq 1) {
        throw "Deploy revision $resolvedDeployRevision does not contain protected origin/main revision $resolvedProductionMain. Update or rebase the worktree before deploying."
    }
    throw 'Unable to verify production Git ancestry. Production deployment remains blocked.'
}

function Assert-OtzivPreparedDeploySnapshotState {
    param(
        [Parameter(Mandatory = $true)][string]$Repository,
        [Parameter(Mandatory = $true)][string]$ExpectedRevision,
        [string]$CanonicalWorkspace = ''
    )

    if ($ExpectedRevision -notmatch '^[0-9a-f]{40}$') {
        throw 'Prepared deploy snapshot state requires one exact expected revision.'
    }
    $resolvedExpectedRevision = Get-OtzivExactCommitRevision -Repository $Repository `
        -Revision $ExpectedRevision `
        -FailureMessage 'Prepared deploy snapshot expected revision is unavailable.'
    $headRevision = Get-OtzivExactCommitRevision -Repository $Repository `
        -Revision 'HEAD' `
        -FailureMessage 'Unable to resolve the prepared deploy snapshot HEAD revision.'
    if ($headRevision -cne $resolvedExpectedRevision) {
        throw "Prepared deploy snapshot HEAD changed from $resolvedExpectedRevision to $headRevision. Production deployment remains blocked."
    }

    $statusArguments = @('status', '--porcelain', '--untracked-files=all')
    if ($CanonicalWorkspace) {
        # CI images and a Git archive never consume ignored local artifacts.
        # This mode is only for the primary, exact main checkout, not a snapshot.
        $canonical = [IO.Path]::GetFullPath($CanonicalWorkspace).TrimEnd('\', '/')
        $actual = [IO.Path]::GetFullPath($Repository).TrimEnd('\', '/')
        $common = Invoke-OtzivSnapshotGitText -Repository $Repository `
            -Arguments @('rev-parse', '--path-format=absolute', '--git-common-dir') `
            -FailureMessage 'Cannot resolve canonical workspace Git directory.'
        $branch = Invoke-OtzivSnapshotGitText -Repository $Repository `
            -Arguments @('branch', '--show-current') -FailureMessage 'Cannot resolve canonical branch.'
        $main = Get-OtzivExactCommitRevision -Repository $Repository -Revision 'origin/main' `
            -FailureMessage 'Cannot resolve canonical origin/main.'
        if ($actual -cne $canonical -or
            [IO.Path]::GetFullPath((Split-Path -Parent $common)).TrimEnd('\', '/') -cne $canonical -or
            $branch -cne 'main' -or $main -cne $headRevision) {
            throw 'Canonical CI deployment requires the primary workspace on exact origin/main.'
        }
    } else {
        $statusArguments += '--ignored=matching'
    }
    $statusResult = Invoke-OtzivSnapshotGit -Repository $Repository -Arguments $statusArguments
    if ($statusResult.ExitCode -ne 0) {
        throw 'Unable to verify prepared deploy snapshot cleanliness. Production deployment remains blocked.'
    }
    $changes = @($statusResult.Output | Where-Object {
            -not [string]::IsNullOrWhiteSpace([string]$_)
        })
    if ($changes.Count -gt 0) {
        throw 'Prepared deploy snapshot worktree changed after it was materialized or validated. Production deployment remains blocked.'
    }

    $indexFlagsResult = Invoke-OtzivSnapshotGit -Repository $Repository `
        -Arguments @('ls-files', '-v')
    if ($indexFlagsResult.ExitCode -ne 0) {
        throw 'Unable to verify prepared deploy snapshot index flags. Production deployment remains blocked.'
    }
    $unsafeIndexFlags = @($indexFlagsResult.Output | Where-Object {
            [string]$_ -cmatch '^(?:[a-z]|S) '
        })
    if ($unsafeIndexFlags.Count -gt 0) {
        throw 'Prepared deploy snapshot contains assume-unchanged or skip-worktree files. Production deployment remains blocked.'
    }
    return $headRevision
}

function Get-OtzivDeployChanges {
    param(
        [Parameter(Mandatory = $true)][string]$Repository,
        [Parameter(Mandatory = $true)][string[]]$InputPaths
    )

    $arguments = @('status', '--porcelain', '--untracked-files=all', '--') + $InputPaths
    $result = Invoke-OtzivSnapshotGit -Repository $Repository -Arguments $arguments
    if ($result.ExitCode -ne 0) {
        throw 'Unable to inspect deployment inputs.'
    }
    return @($result.Output | Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_) })
}

function New-OtzivDeploySnapshot {
    param(
        [Parameter(Mandatory = $true)][string]$Repository,
        [Parameter(Mandatory = $true)][string[]]$InputPaths
    )

    $repositoryRoot = [IO.Path]::GetFullPath($Repository)
    $baseRevision = Get-OtzivExactCommitRevision -Repository $repositoryRoot `
        -Revision 'HEAD' `
        -FailureMessage 'Unable to resolve the deploy snapshot base revision.'

    $temporaryIndex = Join-Path ([IO.Path]::GetTempPath()) ("otziv-deploy-index-" + [Guid]::NewGuid().ToString('N'))
    $previousIndex = [Environment]::GetEnvironmentVariable('GIT_INDEX_FILE')
    $identityVariables = @(
        'GIT_AUTHOR_NAME', 'GIT_AUTHOR_EMAIL', 'GIT_COMMITTER_NAME', 'GIT_COMMITTER_EMAIL'
    )
    $previousIdentity = @{}
    foreach ($variable in $identityVariables) {
        $previousIdentity[$variable] = [Environment]::GetEnvironmentVariable($variable)
    }

    try {
        [Environment]::SetEnvironmentVariable('GIT_INDEX_FILE', $temporaryIndex)
        [Environment]::SetEnvironmentVariable('GIT_AUTHOR_NAME', 'Otziv Deploy Snapshot')
        [Environment]::SetEnvironmentVariable('GIT_AUTHOR_EMAIL', 'deploy-snapshot@local.invalid')
        [Environment]::SetEnvironmentVariable('GIT_COMMITTER_NAME', 'Otziv Deploy Snapshot')
        [Environment]::SetEnvironmentVariable('GIT_COMMITTER_EMAIL', 'deploy-snapshot@local.invalid')

        [void](Invoke-OtzivSnapshotGitText -Repository $repositoryRoot `
            -Arguments @('read-tree', $baseRevision) `
            -FailureMessage 'Unable to initialize the isolated deploy snapshot index.')

        $addResult = Invoke-OtzivSnapshotGit -Repository $repositoryRoot `
            -Arguments (@('add', '-A', '--') + $InputPaths)
        if ($addResult.ExitCode -ne 0) {
            $addOutputText = (($addResult.Output | ForEach-Object { [string]$_ }) -join [Environment]::NewLine)
            throw "Unable to add deployment inputs to the isolated snapshot index:`n$addOutputText"
        }

        $changedText = Invoke-OtzivSnapshotGitText -Repository $repositoryRoot `
            -Arguments (@('diff', '--cached', '--name-only', '--diff-filter=ACDMRTUXB', $baseRevision, '--') + $InputPaths) `
            -FailureMessage 'Unable to enumerate deploy snapshot changes.'
        $changedFiles = @($changedText -split "`r?`n" | Where-Object {
                -not [string]::IsNullOrWhiteSpace($_)
            })
        if ($changedFiles.Count -eq 0) {
            throw 'Deploy snapshot was requested but contains no deployment-input changes.'
        }

        $tree = Invoke-OtzivSnapshotGitText -Repository $repositoryRoot `
            -Arguments @('write-tree') `
            -FailureMessage 'Unable to write the isolated deploy snapshot tree.'
        if ($tree -notmatch '^[0-9a-f]{40}$') {
            throw 'Deploy snapshot tree did not resolve to one exact Git tree.'
        }

        $createdAt = [DateTimeOffset]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ssZ')
        $message = "deploy: automatic local snapshot $createdAt"
        $commit = Invoke-OtzivSnapshotGitText -Repository $repositoryRoot `
            -Arguments @('commit-tree', $tree, '-p', $baseRevision, '-m', $message) `
            -FailureMessage 'Unable to create the isolated deploy snapshot commit.'
        if ($commit -notmatch '^[0-9a-f]{40}$') {
            throw 'Deploy snapshot did not resolve to one exact Git commit.'
        }
    } finally {
        # On recent PowerShell/.NET versions an untyped $null argument can
        # become an empty environment value. Git treats GIT_INDEX_FILE=''
        # as an empty index, so remove originally absent variables explicitly.
        if ($null -eq $previousIndex) {
            Remove-Item -LiteralPath 'Env:GIT_INDEX_FILE' -ErrorAction SilentlyContinue
        } else {
            [Environment]::SetEnvironmentVariable('GIT_INDEX_FILE', $previousIndex)
        }
        foreach ($variable in $identityVariables) {
            if ($null -eq $previousIdentity[$variable]) {
                Remove-Item -LiteralPath "Env:$variable" -ErrorAction SilentlyContinue
            } else {
                [Environment]::SetEnvironmentVariable($variable, $previousIdentity[$variable])
            }
        }
        if (Test-Path -LiteralPath $temporaryIndex) {
            Remove-Item -LiteralPath $temporaryIndex -Force
        }
        $temporaryLock = "$temporaryIndex.lock"
        if (Test-Path -LiteralPath $temporaryLock) {
            Remove-Item -LiteralPath $temporaryLock -Force
        }
    }

    $shortCommit = $commit.Substring(0, 12)
    $refTimestamp = [DateTimeOffset]::UtcNow.ToString('yyyyMMdd-HHmmss')
    $snapshotRef = "refs/otziv/deploy-snapshots/$refTimestamp-$shortCommit"
    [void](Invoke-OtzivSnapshotGitText -Repository $repositoryRoot `
        -Arguments @('update-ref', $snapshotRef, $commit) `
        -FailureMessage 'Unable to retain the deploy snapshot reference for audit and recovery.')

    return [pscustomobject]@{
        BaseRevision = $baseRevision
        Commit = $commit
        Ref = $snapshotRef
        ChangedFiles = $changedFiles
    }
}

function Export-OtzivCommittedDeployBundle {
    param(
        [Parameter(Mandatory = $true)][string]$Repository,
        [Parameter(Mandatory = $true)][string]$Revision,
        [Parameter(Mandatory = $true)][string]$StageRoot,
        [Parameter(Mandatory = $true)][string[]]$InputPaths,
        [string]$PreparedArchive = '',
        [string]$PreparedArchiveSha256 = ''
    )
    $exact = Get-OtzivExactCommitRevision -Repository $Repository -Revision $Revision `
        -FailureMessage 'Cannot resolve committed deployment bundle revision.'
    $paths = @($InputPaths | ForEach-Object { $_.Replace('\', '/') })
    foreach ($path in $paths) {
        if ([IO.Path]::IsPathRooted($path) -or '..' -in $path.Split('/') -or $path.StartsWith('-')) {
            throw 'Deployment archive inputs must be bounded repository paths.'
        }
    }
    $entries = Invoke-OtzivSnapshotGitText -Repository $Repository `
        -Arguments (@('ls-tree', '-r', $exact, '--') + $paths) `
        -FailureMessage 'Cannot inspect committed deployment bundle inputs.'
    if (@($entries -split "`n" | Where-Object { $_ -match '^(120000|160000) ' }).Count -gt 0) {
        throw 'Deployment bundle cannot contain symlinks or submodules.'
    }
    $archive = if ($PreparedArchive) { $PreparedArchive } else { Join-Path $StageRoot '.committed-inputs.tar' }
    if ($PreparedArchive) {
        if ($PreparedArchiveSha256 -notmatch '^[a-fA-F0-9]{64}$' -or
            (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash -cne $PreparedArchiveSha256.ToUpperInvariant()) {
            throw 'Prepared deployment archive changed after early verification.'
        }
    } elseif (Test-Path -LiteralPath $archive) { throw 'Committed deployment archive already exists.' }
    try {
        if (-not $PreparedArchive) {
            # Git for Windows applies core.autocrlf while archiving unless disabled;
            # bound evidence must retain the exact committed blob bytes.
            $result = Invoke-OtzivSnapshotGit -Repository $Repository `
                -Arguments (@('-c', 'core.autocrlf=false', 'archive', '--format=tar', "--output=$archive", $exact, '--') + $paths)
            if ($result.ExitCode -ne 0) { throw 'Cannot archive committed deployment inputs.' }
        }
        & tar -xf $archive -C $StageRoot
        if ($LASTEXITCODE -ne 0) { throw 'Cannot extract committed deployment inputs.' }
    } finally {
        if (-not $PreparedArchive -and (Test-Path -LiteralPath $archive)) { Remove-Item -LiteralPath $archive -Force }
    }
}

function Get-OtzivBoundSplitEvidencePaths {
    param(
        [Parameter(Mandatory = $true)][string]$Repository,
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Sha256
    )
    $relative = $Path.Replace('\', '/')
    if ($relative -notmatch '^infrastructure/runtime-security/proofs/[A-Za-z0-9_-]+/(?:publication|anonymous)/registry-attestation-[0-9]+-payload-[0-9]+\.json$' -or
        $Sha256 -cnotmatch '^[a-f0-9]{64}$') {
        throw 'SSL cutover evidence must use bounded repository paths.'
    }
    $original = Join-Path $Repository $relative
    $metadataPath = "$original.parts.json"
    if (Test-Path -LiteralPath $original) {
        if (Test-Path -LiteralPath $metadataPath) { throw 'Accepted SSL cutover evidence changed.' }
        if ((Get-FileHash -LiteralPath $original -Algorithm SHA256).Hash.ToLowerInvariant() -cne $Sha256) {
            throw 'Accepted SSL cutover evidence changed.'
        }
        return @($relative)
    }
    if (-not (Test-Path -LiteralPath $metadataPath -PathType Leaf) -or
        (Get-Item -LiteralPath $metadataPath).Length -gt 4096 -or
        (Get-Item -LiteralPath $metadataPath).LinkType) {
        throw 'Accepted SSL cutover evidence changed.'
    }
    $metadata = Get-Content -Raw -Encoding UTF8 -LiteralPath $metadataPath | ConvertFrom-Json
    $metadataKeys = @($metadata.PSObject.Properties.Name | Sort-Object)
    if (@(Compare-Object $metadataKeys @('bytes','parts','schema','sha256')).Count -gt 0 -or
        $metadata.schema -cne 'otziv-raw-evidence-parts-v1' -or
        $metadata.sha256 -cne $Sha256 -or
        $metadata.bytes -isnot [long] -and $metadata.bytes -isnot [int] -or
        $metadata.bytes -le (5 * 1024 * 1024) -or $metadata.bytes -gt (8 * 1024 * 1024) -or
        @($metadata.parts).Count -ne 2) {
        throw 'Accepted SSL cutover evidence changed.'
    }
    $name = [IO.Path]::GetFileName($relative)
    $expectedNames = @("$name.part001", "$name.part002", "$name.parts.json")
    $foundNames = @(Get-ChildItem -LiteralPath (Split-Path -Parent $metadataPath) -Force |
        Where-Object { $_.Name.StartsWith("$name.part", [StringComparison]::Ordinal) } |
        ForEach-Object Name | Sort-Object)
    if (@(Compare-Object $foundNames @($expectedNames | Sort-Object)).Count -gt 0) {
        throw 'Accepted SSL cutover evidence changed.'
    }
    $digest = [Security.Cryptography.IncrementalHash]::CreateHash([Security.Cryptography.HashAlgorithmName]::SHA256)
    $total = 0L
    try {
        for ($i = 0; $i -lt 2; $i++) {
            $part = $metadata.parts[$i]
            $partKeys = @($part.PSObject.Properties.Name | Sort-Object)
            if (@(Compare-Object $partKeys @('bytes','file','sha256')).Count -gt 0 -or
                $part.file -cne $expectedNames[$i] -or
                $part.bytes -isnot [long] -and $part.bytes -isnot [int] -or
                $part.bytes -le 0 -or $part.bytes -gt (4 * 1024 * 1024) -or
                $part.sha256 -cnotmatch '^[a-f0-9]{64}$') {
                throw 'Accepted SSL cutover evidence changed.'
            }
            $partPath = "$original.part00$($i + 1)"
            $item = Get-Item -LiteralPath $partPath -Force
            if ($item.LinkType -or $item.PSIsContainer -or $item.Length -ne $part.bytes) {
                throw 'Accepted SSL cutover evidence changed.'
            }
            $bytes = [IO.File]::ReadAllBytes($partPath)
            if ($bytes.Length -ne $part.bytes -or
                (Get-FileHash -LiteralPath $partPath -Algorithm SHA256).Hash.ToLowerInvariant() -cne $part.sha256) {
                throw 'Accepted SSL cutover evidence changed.'
            }
            $digest.AppendData($bytes)
            $total += $bytes.Length
        }
        $actual = [BitConverter]::ToString($digest.GetHashAndReset()).Replace('-', '').ToLowerInvariant()
    } finally {
        $digest.Dispose()
    }
    if ($total -ne $metadata.bytes -or $actual -cne $Sha256) {
        throw 'Accepted SSL cutover evidence changed.'
    }
    return @("$relative.part001", "$relative.part002", "$relative.parts.json")
}

function Get-OtzivCoordinatedSslBundlePaths {
    param([Parameter(Mandatory = $true)][string]$Repository)
    function Read-OtzivBoundDeployEvidencePathOnly([string]$Path, [string]$Sha256) {
        $relative = $Path.Replace('\', '/')
        if ($relative -notmatch '^[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)+$' -or
            '..' -in $relative.Split('/') -or '.' -in $relative.Split('/')) {
            throw 'SSL cutover evidence must use bounded repository paths.'
        }
        $file = Join-Path $Repository $relative
        if ($Sha256 -notmatch '^[a-f0-9]{64}$' -or
            (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant() -cne $Sha256) {
            throw 'Accepted SSL cutover evidence changed.'
        }
        return $file
    }
    $activationPath = 'infrastructure/runtime-security/reviewed-image-activations.json'
    $index = Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $Repository $activationPath) | ConvertFrom-Json
    $parentPath = 'infrastructure/runtime-security/c36-parent-postgres.json'
    $null = Read-OtzivBoundDeployEvidencePathOnly $parentPath 'fcc50b0b792637c08a9a74cf51057354d138b6a5a741a344ed6979a3c7a611b3'
    $paths = @($activationPath, $parentPath)
    if (Test-Path -LiteralPath (Join-Path $Repository 'infrastructure/runtime-security/evidence-archive-c26.json')) {
        $paths += @('infrastructure/runtime-security/evidence-archive-c26.json', 'infrastructure/runtime-security/evidence_archive.py')
    }
    $postgres = @($index.images | Where-Object { $_.component -eq 'postgres' })
    $keycloak = @($index.images | Where-Object { $_.component -eq 'keycloak' })
    if ($postgres.Count -ne 1 -or $keycloak.Count -ne 1 -or
        $postgres[0].manifest.path -cne 'infrastructure/runtime-security/reviewed-images-c36-postgres.json' -or
        $keycloak[0].manifest.path -cne 'infrastructure/runtime-security/reviewed-images-c35-keycloak.json') {
        throw 'Only the accepted C36 PostgreSQL and active C35 Keycloak pair permits this cutover.'
    }
    $pg = $postgres[0]; $kc = $keycloak[0]
    foreach ($entry in @($pg, $kc)) {
        $null = Read-OtzivBoundDeployEvidencePathOnly $entry.manifest.path $entry.manifest.sha256
        $paths += $entry.manifest.path
    }
    if (-not $pg.PSObject.Properties['postgresC36Acceptance'] -or
        -not $pg.postgresC36Acceptance.path -or
        $pg.databaseTransition.path -cne $pg.postgresC36Acceptance.path -or
        $pg.databaseTransition.sha256 -cne $pg.postgresC36Acceptance.sha256) {
        throw 'Missing accepted SSL cutover evidence: postgres'
    }
    if (-not $kc.PSObject.Properties['vertxRefreshAcceptance'] -or
        -not $kc.vertxRefreshAcceptance.path -or
        $kc.migrationAcceptance.path -cne $kc.vertxRefreshAcceptance.path -or
        $kc.migrationAcceptance.sha256 -cne $kc.vertxRefreshAcceptance.sha256) {
        throw 'Missing accepted SSL cutover evidence: keycloak'
    }
    $pgAcceptance = Get-Content -Raw -Encoding UTF8 -LiteralPath `
        (Read-OtzivBoundDeployEvidencePathOnly $pg.postgresC36Acceptance.path $pg.postgresC36Acceptance.sha256) | ConvertFrom-Json
    $kcAcceptance = Get-Content -Raw -Encoding UTF8 -LiteralPath `
        (Read-OtzivBoundDeployEvidencePathOnly $kc.vertxRefreshAcceptance.path $kc.vertxRefreshAcceptance.sha256) | ConvertFrom-Json
    if ($pgAcceptance.schema -cne 'otziv-postgres-c36-acceptance-v1' -or
        $pgAcceptance.result -cne 'PASS' -or $pgAcceptance.reference -cne $pg.reference -or
        $pgAcceptance.parentReference -cne 'ghcr.io/claidd/otziv-security@sha256:c974c12a1107e314b1ba8cf65bd1fc5b6964645ced420c2151d336ee8a9a6534' -or
        $pgAcceptance.parentConfigId -cne 'sha256:1f5c2590fadde6b449908569285f1ed91a10831d36535d5c4ed647e9a0afca6a' -or
        $pgAcceptance.pair.postgres.reference -cne $pg.reference -or
        $pgAcceptance.pair.postgres.imageConfigId -cne $pgAcceptance.imageConfigId -or
        $pgAcceptance.pair.keycloak.reference -cne $kc.reference -or
        $pgAcceptance.pair.keycloak.imageConfigId -cne 'sha256:60a8dd92cf43b8cf909a9e13f231da1f59103f9259cf482185c54856dcfd6a1b' -or
        @(@('commit','run','attempt') | Where-Object { $pgAcceptance.$_ -cne $pg.$_ }).Count -gt 0) {
        throw 'Invalid accepted SSL cutover evidence: postgres'
    }
    if ($kcAcceptance.schema -cne 'otziv-keycloak-vertx-c35-acceptance-v1' -or
        $kcAcceptance.result -cne 'PASS' -or $kcAcceptance.reference -cne $kc.reference -or
        $kcAcceptance.imageConfigId -cne 'sha256:60a8dd92cf43b8cf909a9e13f231da1f59103f9259cf482185c54856dcfd6a1b' -or
        $kcAcceptance.postgres.reference -cne $pgAcceptance.parentReference -or
        $kcAcceptance.postgres.imageConfigId -cne $pgAcceptance.parentConfigId -or
        @(@('commit','run','attempt') | Where-Object { $kcAcceptance.$_ -cne $kc.$_ }).Count -gt 0) {
        throw 'Invalid accepted SSL cutover evidence: keycloak'
    }
    $paths += @($pg.postgresC36Acceptance.path, $kc.vertxRefreshAcceptance.path)
    foreach ($accepted in @($pgAcceptance, $kcAcceptance)) {
        foreach ($map in @($accepted.files, $accepted.executedSources)) {
            if ($null -eq $map -or @($map.PSObject.Properties).Count -eq 0) { throw 'Accepted SSL cutover evidence is incomplete.' }
            foreach ($file in $map.PSObject.Properties) {
                if ($file.Name -match '/registry-attestation-[0-9]+-payload-[0-9]+\.json$' -and
                    $file.Name -match '^infrastructure/runtime-security/proofs/') {
                    $paths += @(Get-OtzivBoundSplitEvidencePaths -Repository $Repository -Path $file.Name -Sha256 $file.Value)
                } else {
                    $null = Read-OtzivBoundDeployEvidencePathOnly $file.Name $file.Value
                    $paths += $file.Name
                }
            }
        }
    }
    return @($paths | Select-Object -Unique)
}

function Get-OtzivCoordinatedMysqlBundlePaths {
    param([Parameter(Mandatory = $true)][string]$Repository)
    function Read-OtzivBoundMysqlEvidence([string]$Path, [string]$Sha256) {
        $relative = $Path.Replace('\', '/')
        if ($relative -notmatch '^infrastructure/runtime-security/(?:[A-Za-z0-9_.-]+/)*[A-Za-z0-9_.-]+$' -or
            '..' -in $relative.Split('/') -or '.' -in $relative.Split('/') -or
            $Sha256 -cnotmatch '^[a-f0-9]{64}$') {
            throw 'MySQL cutover evidence path or digest is invalid.'
        }
        $file = Join-Path $Repository $relative
        if (-not (Test-Path -LiteralPath $file -PathType Leaf) -or
            (Get-Item -LiteralPath $file).LinkType -or
            (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant() -cne $Sha256) {
            throw 'Accepted MySQL cutover evidence changed.'
        }
        return $file
    }
    $activationPath = 'infrastructure/runtime-security/reviewed-image-activations.json'
    $activation = Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $Repository $activationPath) | ConvertFrom-Json
    $entries = @($activation.images | Where-Object { $_.component -eq 'mysql' })
    $policies = @{
        'ghcr.io/claidd/otziv-security@sha256:0ad40a668c292ebe70297cd4df66b2e9c5067fad3bfdb54179347a597e1ac48f' = @{
            set = 'c46-mysql'
            config = 'sha256:d6202b12659d22367dfcf315852aaf87a967492ed7601c5f17e6425880c5a069'
            rehearsal = '677a9cb24dbf1ccadc3cdee49c1de3d2a3593f0284b15a1205d7c38c364743f1'
            script = 'infrastructure/runtime-security/mysql-c46-rehearsal.py'
            parentHash = '6ca701ab78702f26c04a89e41d888932d4ff2fbf94800c12d2402aba492b8839'
            source = 'ghcr.io/claidd/otziv-security@sha256:d8b2c5d02611016eee74b77caede5c57a16cd8a1f379bf5c74d322bfd1b5f75d'
        }
        'ghcr.io/claidd/otziv-security@sha256:eda70c6cb0a835e788a5ec96539610e9ef6a574ffc4129c9c5717acd647e159a' = @{
            set = 'c37-mysql'
            config = 'sha256:dcde12d588294e67f7de3a037ec69fd00d0e50c0c4b5d81dc3400545abe298dc'
            rehearsal = '2058c0cfa4c127d62f2c3d700300a3ecbe5ca6758ceaaf006fbf1bed43264571'
            script = 'infrastructure/runtime-security/mysql-c37-rehearsal.py'
            parentHash = 'fb89c3279005d18b01f24d565cf75094216a484ed246f52fc5662ae795930a53'
            source = 'ghcr.io/claidd/otziv-security@sha256:28521a2c331136328abde7b504ae0ded617c74caa8a4a2554f1f0494d4df9009'
        }
        'ghcr.io/claidd/otziv-security@sha256:d8b2c5d02611016eee74b77caede5c57a16cd8a1f379bf5c74d322bfd1b5f75d' = @{
            set = 'c42-mysql'
            config = 'sha256:b8b1aab82af45b783263e514e2f9746959de78d3a963245cf386720a32b8564f'
            rehearsal = '6b91b594468f820309b0971d1a3854cd8d421c3a83724ba928822d4c0763ece8'
            script = 'infrastructure/runtime-security/mysql-c42-rehearsal.py'
            parentHash = 'e6df75d221fa66b055f19598b5b03855e23396e7f908e09a502f293edb8d493e'
            source = 'ghcr.io/claidd/otziv-security@sha256:eda70c6cb0a835e788a5ec96539610e9ef6a574ffc4129c9c5717acd647e159a'
        }
    }
    if ($entries.Count -ne 1 -or -not $policies.ContainsKey([string]$entries[0].reference)) {
        throw 'Only an exact accepted MySQL target permits this cutover.'
    }
    $entry = $entries[0]
    $reference = [string]$entry.reference
    $policy = $policies[$reference]
    $prefix = 'infrastructure/runtime-security/proofs/' + $policy.set + '/'
    $config = $policy.config
    $rehearsalHash = $policy.rehearsal
    $expectedPaths = @{
        manifest = 'infrastructure/runtime-security/reviewed-images-' + $policy.set + '.json'
        publication = $prefix + 'publication/publication.json'
        anonymous = $prefix + 'anonymous/anonymous-download.json'
        databaseTransition = $prefix + 'rehearsal.json'
    }
    $paths = @($activationPath)
    foreach ($key in @('manifest', 'publication', 'anonymous', 'databaseTransition')) {
        $bound = $entry.$key
        if ($null -eq $bound -or $bound.path -cne $expectedPaths[$key]) {
            throw "Accepted MySQL cutover $key evidence is missing."
        }
        $null = Read-OtzivBoundMysqlEvidence $bound.path $bound.sha256
        $paths += $bound.path
    }
    if ($entry.databaseTransition.sha256 -cne $rehearsalHash) {
        throw 'Accepted MySQL cutover rehearsal digest changed.'
    }
    $rehearsal = Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $Repository $entry.databaseTransition.path) | ConvertFrom-Json
    $publication = Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $Repository $entry.publication.path) | ConvertFrom-Json
    $anonymous = Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $Repository $entry.anonymous.path) | ConvertFrom-Json
    if ($rehearsal.schema -cne 'otziv-mysql-same-engine-refresh-v1' -or
        $rehearsal.result -cne 'PASS' -or $rehearsal.preliminary -ne $false -or
        $rehearsal.candidate -cne $reference -or
        $publication.reference -cne $reference -or $publication.imageId -cne $config -or
        $publication.security.result -cne 'PASS' -or
        $anonymous.reference -cne $reference -or $anonymous.imageId -cne $config -or
        $anonymous.result -cne 'PASS') {
        throw 'Accepted MySQL cutover publication, download or rehearsal failed.'
    }
    $scriptPath = $policy.script
    $null = Read-OtzivBoundMysqlEvidence $scriptPath $rehearsal.executedSourceSha256
    $paths += $scriptPath
    $parentPath = $prefix + 'parent-activation.json'
    $null = Read-OtzivBoundMysqlEvidence $parentPath $policy.parentHash
    $parent = Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $Repository $parentPath) | ConvertFrom-Json
    if ($parent.component -cne 'mysql' -or
        $parent.reference -cne $policy.source) {
        throw 'MySQL cutover source activation changed.'
    }
    $paths += $parentPath
    return @($paths | Select-Object -Unique)
}

function Get-OtzivDeployBundlePaths {
    param([Parameter(Mandatory = $true)][string]$Repository,
        [switch]$CoordinatedMysqlRefresh, [switch]$CoordinatedSslRefresh)
    $errors = $null
    $ast = [Management.Automation.Language.Parser]::ParseFile(
        (Join-Path $Repository 'infrastructure/scripts/prod/deploy-prod.ps1'), [ref]$null, [ref]$errors)
    if ($errors.Count) { throw 'Cannot parse the deployment inventory.' }
    $matches = @($ast.FindAll({ param($node)
        $node -is [Management.Automation.Language.AssignmentStatementAst] -and
        $node.Left.Extent.Text -ceq '$deployBundlePaths'
    }, $true))
    if ($matches.Count -ne 1) { throw 'Deployment inventory must have one literal definition.' }
    $allowed = @('StatementBlockAst','PipelineAst','CommandExpressionAst','ArrayExpressionAst',
        'ArrayLiteralAst','StringConstantExpressionAst')
    $nodes = @($matches[0].Right.FindAll({ param($node) $true }, $true))
    if (@($nodes | Where-Object { $_.GetType().Name -notin $allowed }).Count -gt 0) {
        throw 'Deployment inventory must contain only literal paths.'
    }
    $paths = @($nodes | Where-Object { $_ -is [Management.Automation.Language.StringConstantExpressionAst] } | ForEach-Object { $_.Value })
    if ($paths.Count -eq 0 -or 'docker-compose.yaml' -notin $paths) { throw 'Deployment inventory is incomplete.' }
    if ($CoordinatedMysqlRefresh) { $paths += @(Get-OtzivCoordinatedMysqlBundlePaths -Repository $Repository) }
    if ($CoordinatedSslRefresh) { $paths += @(Get-OtzivCoordinatedSslBundlePaths -Repository $Repository) }
    return @($paths | Select-Object -Unique)
}

function New-OtzivPreparedDeployArchive {
    param(
        [Parameter(Mandatory = $true)][string]$Repository,
        [Parameter(Mandatory = $true)][string]$Revision,
        [Parameter(Mandatory = $true)][string]$Directory,
        [switch]$CoordinatedMysqlRefresh,
        [switch]$CoordinatedSslRefresh
    )
    $stage = Join-Path $Directory 'verified-source'
    if (Test-Path -LiteralPath $stage) { throw 'Prepared source directory already exists.' }
    New-Item -ItemType Directory -Path $stage | Out-Null
    $external = @{}
    $manifestPath = 'infrastructure/runtime-security/evidence-archive-c26.json'
    $loaderPath = 'infrastructure/runtime-security/evidence_archive.py'
    $externalManifestCommitted = $false
    if ($CoordinatedSslRefresh) {
        $exact = Get-OtzivExactCommitRevision -Repository $Repository -Revision $Revision `
            -FailureMessage 'Cannot resolve committed external evidence revision.'
        $manifestObject = Invoke-OtzivSnapshotGit -Repository $Repository -Arguments @('cat-file', '-e', "${exact}:$manifestPath")
        $externalManifestCommitted = $manifestObject.ExitCode -eq 0
        if ($externalManifestCommitted -ne (Test-Path -LiteralPath (Join-Path $Repository $manifestPath) -PathType Leaf)) {
            throw 'External evidence manifest is missing from the checkout or committed deployment revision.'
        }
    }
    if ($externalManifestCommitted) {
        # Both the loader and its trust manifest come from the exact checked commit.
        # An edited local manifest cannot authorize extra files, paths or download hosts.
        Export-OtzivCommittedDeployBundle -Repository $Repository -Revision $Revision -StageRoot $stage `
            -InputPaths @($manifestPath, $loaderPath)
        $boundManifest = Join-Path $stage $manifestPath
        if ((Get-FileHash -LiteralPath $boundManifest -Algorithm SHA256).Hash -cne
            (Get-FileHash -LiteralPath (Join-Path $Repository $manifestPath) -Algorithm SHA256).Hash) {
            throw 'External evidence manifest differs from the committed deployment revision.'
        }
        $hydrated = @(& python -B (Join-Path $stage $loaderPath) hydrate --root $Repository --manifest $boundManifest --download)
        if ($LASTEXITCODE -ne 0) { throw 'Committed external evidence hydration failed.' }
        $manifest = Get-Content -Raw -Encoding UTF8 -LiteralPath $boundManifest | ConvertFrom-Json
        foreach ($item in $manifest.files) { $external[$item.path] = $item }
    }
    $paths = @(Get-OtzivDeployBundlePaths -Repository $Repository `
        -CoordinatedMysqlRefresh:$CoordinatedMysqlRefresh -CoordinatedSslRefresh:$CoordinatedSslRefresh)
    $sourcePaths = @($paths | Where-Object { -not $external.ContainsKey($_.Replace('\', '/')) })
    Export-OtzivCommittedDeployBundle -Repository $Repository -Revision $Revision -StageRoot $stage -InputPaths $sourcePaths
    foreach ($path in $paths) {
        $relative = $path.Replace('\', '/')
        if (-not $external.ContainsKey($relative)) { continue }
        $item = $external[$relative]
        $source = Join-Path $Repository $relative
        $destination = Join-Path $stage $relative
        if ((Get-Item -LiteralPath $source).Length -ne $item.bytes -or
            (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash.ToLowerInvariant() -cne $item.sha256) {
            throw 'Accepted external deployment evidence changed before staging.'
        }
        [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($destination)) | Out-Null
        Copy-Item -LiteralPath $source -Destination $destination -Force
        if ((Get-Item -LiteralPath $destination).Length -ne $item.bytes -or
            (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash.ToLowerInvariant() -cne $item.sha256) {
            throw 'Accepted external deployment evidence changed during staging.'
        }
    }
    if ($CoordinatedMysqlRefresh) {
        $expectedMysqlEvidence = @(Get-OtzivCoordinatedMysqlBundlePaths -Repository $Repository)
        $stagedMysqlEvidence = @(Get-OtzivCoordinatedMysqlBundlePaths -Repository $stage)
        if (@(Compare-Object -ReferenceObject $expectedMysqlEvidence -DifferenceObject $stagedMysqlEvidence).Count -gt 0) {
            throw 'Staged MySQL cutover evidence changed the accepted deployment inventory.'
        }
    }
    if ($external.Count -gt 0) {
        # Acceptance references and executed source hashes must also hold in the sealed tree.
        $expectedEvidence = @(Get-OtzivCoordinatedSslBundlePaths -Repository $Repository)
        $stagedEvidence = @(Get-OtzivCoordinatedSslBundlePaths -Repository $stage)
        if (@(Compare-Object -ReferenceObject $expectedEvidence -DifferenceObject $stagedEvidence).Count -gt 0) {
            throw 'Staged external evidence changed the accepted deployment inventory.'
        }
    }
    foreach ($path in $paths) {
        if (-not (Test-Path -LiteralPath (Join-Path $stage $path))) { throw "Committed deploy input missing: $path" }
    }
    $archive = Join-Path $Directory 'verified-source.tar'
    if (Test-Path -LiteralPath $archive) { throw 'Prepared deployment archive already exists.' }
    & tar -cf $archive -C $stage .
    if ($LASTEXITCODE -ne 0) { throw 'Cannot seal the verified source archive.' }
    $receipt = [ordered]@{ revision = $Revision; archive = $archive
        sha256 = (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash; paths = $paths }
    $receipt | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $Directory 'verified-source.json') -Encoding utf8
    return [pscustomobject]$receipt
}
