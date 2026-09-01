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
        [Parameter(Mandatory = $true)][string]$ExpectedRevision
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

    $statusResult = Invoke-OtzivSnapshotGit -Repository $Repository `
        -Arguments @('status', '--porcelain', '--untracked-files=all', '--ignored=matching')
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
