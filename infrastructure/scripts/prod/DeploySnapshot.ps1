Set-StrictMode -Version Latest

function Get-OtzivDeployInputPaths {
    return @(
        'backend',
        'frontend',
        'mobile',
        'whatsapp',
        'infrastructure',
        '.github',
        'docker-compose.yaml',
        'docker-compose.build.yaml',
        'compose.yaml',
        'compose.prod-local.yaml',
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

    $output = @(& git -C $Repository @Arguments 2>&1)
    if ($LASTEXITCODE -ne 0) {
        throw $FailureMessage
    }
    return ($output -join [Environment]::NewLine).Trim()
}

function Get-OtzivDeployChanges {
    param(
        [Parameter(Mandatory = $true)][string]$Repository,
        [Parameter(Mandatory = $true)][string[]]$InputPaths
    )

    $output = @(& git -C $Repository status --porcelain --untracked-files=all -- @InputPaths)
    if ($LASTEXITCODE -ne 0) {
        throw 'Unable to inspect deployment inputs.'
    }
    return @($output | Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_) })
}

function New-OtzivDeploySnapshot {
    param(
        [Parameter(Mandatory = $true)][string]$Repository,
        [Parameter(Mandatory = $true)][string[]]$InputPaths
    )

    $repositoryRoot = [IO.Path]::GetFullPath($Repository)
    $baseRevision = Invoke-OtzivSnapshotGitText -Repository $repositoryRoot `
        -Arguments @('rev-parse', '--verify', 'HEAD^{commit}') `
        -FailureMessage 'Unable to resolve the deploy snapshot base revision.'
    if ($baseRevision -notmatch '^[0-9a-f]{40}$') {
        throw 'Deploy snapshot base did not resolve to one exact Git commit.'
    }

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

        $addOutput = @(& git -C $repositoryRoot add -A -- @InputPaths 2>&1)
        if ($LASTEXITCODE -ne 0) {
            throw "Unable to add deployment inputs to the isolated snapshot index:`n$($addOutput -join [Environment]::NewLine)"
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
        [Environment]::SetEnvironmentVariable('GIT_INDEX_FILE', $previousIndex)
        foreach ($variable in $identityVariables) {
            [Environment]::SetEnvironmentVariable($variable, $previousIdentity[$variable])
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
