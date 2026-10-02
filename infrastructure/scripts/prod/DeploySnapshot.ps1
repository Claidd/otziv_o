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
            $result = Invoke-OtzivSnapshotGit -Repository $Repository `
                -Arguments (@('archive', '--format=tar', "--output=$archive", $exact, '--') + $paths)
            if ($result.ExitCode -ne 0) { throw 'Cannot archive committed deployment inputs.' }
        }
        & tar -xf $archive -C $StageRoot
        if ($LASTEXITCODE -ne 0) { throw 'Cannot extract committed deployment inputs.' }
    } finally {
        if (-not $PreparedArchive -and (Test-Path -LiteralPath $archive)) { Remove-Item -LiteralPath $archive -Force }
    }
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
    $paths = @($activationPath, 'infrastructure/runtime-security/c23-parent-activations.json')
    if (Test-Path -LiteralPath (Join-Path $Repository 'infrastructure/runtime-security/evidence-archive-c26.json')) {
        $paths += @('infrastructure/runtime-security/evidence-archive-c26.json', 'infrastructure/runtime-security/evidence_archive.py')
    }
    foreach ($component in @('postgres', 'keycloak')) {
        $entry = @($index.images | Where-Object { $_.component -eq $component })
        if ($entry.Count -ne 1 -or -not $entry[0].sslRefreshAcceptance.path) {
            throw "Missing accepted SSL cutover evidence: $component"
        }
        $reference = $entry[0].sslRefreshAcceptance
        $acceptanceFile = Read-OtzivBoundDeployEvidencePathOnly $reference.path $reference.sha256
        $accepted = Get-Content -Raw -Encoding UTF8 -LiteralPath $acceptanceFile | ConvertFrom-Json
        if ($accepted.component -cne $component -or $accepted.result -cne 'PASS' -or
            $accepted.schema -cne 'otziv-ssl-refresh-acceptance-v1') {
            throw "Invalid accepted SSL cutover evidence: $component"
        }
        $paths += $reference.path
        foreach ($map in @($accepted.files, $accepted.executedSources)) {
            foreach ($file in $map.PSObject.Properties) {
                $null = Read-OtzivBoundDeployEvidencePathOnly $file.Name $file.Value
                $paths += $file.Name
            }
        }
    }
    return @($paths | Select-Object -Unique)
}

function Get-OtzivDeployBundlePaths {
    param([Parameter(Mandatory = $true)][string]$Repository, [switch]$CoordinatedSslRefresh)
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
    if ($CoordinatedSslRefresh) { $paths += @(Get-OtzivCoordinatedSslBundlePaths -Repository $Repository) }
    return @($paths | Select-Object -Unique)
}

function New-OtzivPreparedDeployArchive {
    param(
        [Parameter(Mandatory = $true)][string]$Repository,
        [Parameter(Mandatory = $true)][string]$Revision,
        [Parameter(Mandatory = $true)][string]$Directory,
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
    $paths = @(Get-OtzivDeployBundlePaths -Repository $Repository -CoordinatedSslRefresh:$CoordinatedSslRefresh)
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
