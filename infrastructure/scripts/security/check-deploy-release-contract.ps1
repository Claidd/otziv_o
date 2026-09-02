[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
$deployPath = Join-Path $repoRoot 'infrastructure\scripts\prod\deploy-prod.ps1'
$snapshotPath = Join-Path $repoRoot 'infrastructure\scripts\prod\DeploySnapshot.ps1'
$snapshotValidatorPath = Join-Path $repoRoot 'infrastructure\scripts\prod\validate-deploy-snapshot.ps1'
$legacyDeployPath = Join-Path $repoRoot 'infrastructure\scripts\prod\deploy-prod-ssh-images.ps1'
$backupPath = Join-Path $repoRoot 'infrastructure\scripts\prod\create-pre-deploy-db-backup.sh'
$maxWebhookPath = Join-Path $repoRoot 'infrastructure\scripts\prod\register-max-webhook.sh'
$selfHealPath = Join-Path $repoRoot 'infrastructure\scripts\prod\otziv-prod-up.sh'
$selfHealTimerPath = Join-Path $repoRoot 'infrastructure\systemd\otziv-prod-up.timer'
$selfHealServiceTemplatePath = Join-Path $repoRoot 'infrastructure\systemd\otziv-prod-up.service.in'
$whatsappIndexPath = Join-Path $repoRoot 'whatsapp\index.js'
$whatsappPackagePath = Join-Path $repoRoot 'whatsapp\package.json'
$whatsappChromiumLaunchPath = Join-Path $repoRoot 'whatsapp\chromium-launch.js'
$whatsappChromiumSmokePath = Join-Path $repoRoot 'whatsapp\chromium-smoke.js'
$buildComposePath = Join-Path $repoRoot 'docker-compose.build.yaml'
$productionComposePath = Join-Path $repoRoot 'docker-compose.yaml'

$deploy = [IO.File]::ReadAllText($deployPath)
$snapshot = [IO.File]::ReadAllText($snapshotPath)
$snapshotValidator = [IO.File]::ReadAllText($snapshotValidatorPath)
$legacyDeploy = [IO.File]::ReadAllText($legacyDeployPath)
$backup = [IO.File]::ReadAllText($backupPath)
$maxWebhook = [IO.File]::ReadAllText($maxWebhookPath)
$selfHeal = [IO.File]::ReadAllText($selfHealPath)
$selfHealTimer = [IO.File]::ReadAllText($selfHealTimerPath)
$selfHealServiceTemplate = [IO.File]::ReadAllText($selfHealServiceTemplatePath)
$whatsappIndex = [IO.File]::ReadAllText($whatsappIndexPath)
$whatsappPackage = [IO.File]::ReadAllText($whatsappPackagePath)
$whatsappChromiumLaunch = [IO.File]::ReadAllText($whatsappChromiumLaunchPath)
$whatsappChromiumSmoke = [IO.File]::ReadAllText($whatsappChromiumSmokePath)
$buildCompose = [IO.File]::ReadAllText($buildComposePath)
$productionCompose = [IO.File]::ReadAllText($productionComposePath)
. $snapshotPath

function Assert-Match {
    param([string]$Text, [string]$Pattern, [string]$Message)
    if (-not [regex]::IsMatch($Text, $Pattern, [Text.RegularExpressions.RegexOptions]::Singleline)) {
        throw $Message
    }
}

function Assert-NotMatch {
    param([string]$Text, [string]$Pattern, [string]$Message)
    if ([regex]::IsMatch($Text, $Pattern, [Text.RegularExpressions.RegexOptions]::Singleline)) {
        throw $Message
    }
}

function Assert-Order {
    param([string]$Text, [string]$Earlier, [string]$Later, [string]$Message)
    $earlierIndex = $Text.IndexOf($Earlier, [StringComparison]::Ordinal)
    $laterIndex = $Text.LastIndexOf($Later, [StringComparison]::Ordinal)
    if ($earlierIndex -lt 0 -or $laterIndex -lt 0 -or $earlierIndex -ge $laterIndex) {
        throw $Message
    }
}

function Get-LocalNodeDependencyClosure {
    param([Parameter(Mandatory = $true)][string]$EntryPath)

    $whatsappRoot = [IO.Path]::GetFullPath((Join-Path $repoRoot 'whatsapp'))
    $whatsappPrefix = $whatsappRoot.TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
    $pending = [Collections.Generic.Stack[string]]::new()
    $visited = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    $pending.Push([IO.Path]::GetFullPath($EntryPath))

    while ($pending.Count -gt 0) {
        $currentPath = $pending.Pop()
        if (-not $visited.Add($currentPath)) {
            continue
        }
        if (-not [IO.File]::Exists($currentPath)) {
            throw "Local Node dependency does not exist: $currentPath"
        }

        $source = [IO.File]::ReadAllText($currentPath)
        foreach ($match in [regex]::Matches($source, 'require\(["''](?<relative>\./[^"'']+)["'']\)')) {
            $dependencyPath = [IO.Path]::GetFullPath((Join-Path ([IO.Path]::GetDirectoryName($currentPath)) $match.Groups['relative'].Value))
            if ([string]::IsNullOrEmpty([IO.Path]::GetExtension($dependencyPath))) {
                $dependencyPath += '.js'
            }
            if (-not $dependencyPath.StartsWith($whatsappPrefix, [StringComparison]::OrdinalIgnoreCase)) {
                throw "WhatsApp local dependency escapes its source directory: $dependencyPath"
            }
            if (-not $visited.Contains($dependencyPath)) {
                $pending.Push($dependencyPath)
            }
        }
    }

    return @($visited)
}

function Invoke-DeployLineageTestGit {
    param(
        [Parameter(Mandatory = $true)][string]$Repository,
        [Parameter(Mandatory = $true)][string[]]$Arguments
    )

    $output = @(& git -C $Repository @Arguments 2>&1)
    if ($LASTEXITCODE -ne 0) {
        throw "Deploy-lineage test Git command failed: git -C $Repository $($Arguments -join ' ')"
    }
    return ($output -join [Environment]::NewLine).Trim()
}

function Assert-DeployLineageRejected {
    param(
        [Parameter(Mandatory = $true)][string]$Repository,
        [Parameter(Mandatory = $true)][string]$ProductionMainRevision,
        [Parameter(Mandatory = $true)][string]$DeployRevision,
        [Parameter(Mandatory = $true)][string]$Scenario
    )

    try {
        Assert-OtzivDeployRevisionContainsProductionMain -Repository $Repository `
            -ProductionMainRevision $ProductionMainRevision `
            -DeployRevision $DeployRevision
    } catch {
        if ($_.Exception.Message -notlike 'Deploy revision * does not contain protected origin/main revision *') {
            throw
        }
        return
    }
    throw "Production lineage guard accepted a $Scenario deploy revision."
}

function Assert-PreparedSnapshotStateRejected {
    param(
        [Parameter(Mandatory = $true)][string]$Repository,
        [Parameter(Mandatory = $true)][string]$ExpectedRevision,
        [Parameter(Mandatory = $true)][string]$ExpectedMessagePattern,
        [Parameter(Mandatory = $true)][string]$Scenario
    )

    try {
        [void](Assert-OtzivPreparedDeploySnapshotState -Repository $Repository `
                -ExpectedRevision $ExpectedRevision)
    } catch {
        if ($_.Exception.Message -notlike $ExpectedMessagePattern) {
            throw
        }
        return
    }
    throw "Prepared snapshot state guard accepted $Scenario."
}

function Test-DeployLineageGuard {
    $testRoot = Join-Path ([IO.Path]::GetTempPath()) ("otziv-deploy-lineage-contract-" + [Guid]::NewGuid().ToString('N'))
    $remoteRepository = Join-Path $testRoot 'origin.git'
    $workingRepository = Join-Path $testRoot 'work'
    New-Item -ItemType Directory -Path $testRoot | Out-Null
    try {
        [void](Invoke-DeployLineageTestGit -Repository $testRoot -Arguments @('init', '--quiet', '--bare', $remoteRepository))
        [void](Invoke-DeployLineageTestGit -Repository $testRoot -Arguments @('init', '--quiet', '-b', 'main', $workingRepository))
        Set-Content -LiteralPath (Join-Path $workingRepository 'tracked-sentinel.txt') `
            -Value 'tracked' -Encoding Ascii
        [void](Invoke-DeployLineageTestGit -Repository $workingRepository `
            -Arguments @('add', '--', 'tracked-sentinel.txt'))
        [void](Invoke-DeployLineageTestGit -Repository $workingRepository `
            -Arguments @('-c', 'user.name=Otziv Contract', '-c', 'user.email=contract@local.invalid',
                'commit', '--quiet', '-m', 'base'))
        $baseRevision = Invoke-DeployLineageTestGit -Repository $workingRepository -Arguments @('rev-parse', 'HEAD')
        [void](Invoke-DeployLineageTestGit -Repository $workingRepository -Arguments @('remote', 'add', 'origin', $remoteRepository))
        [void](Invoke-DeployLineageTestGit -Repository $workingRepository -Arguments @('push', '--quiet', '-u', 'origin', 'main'))

        $protectedBase = Update-OtzivProductionMainRevision -Repository $workingRepository
        if ($protectedBase -cne $baseRevision) {
            throw 'Production lineage guard did not pin the exact fetched origin/main revision.'
        }
        Assert-OtzivDeployRevisionContainsProductionMain -Repository $workingRepository `
            -ProductionMainRevision $protectedBase `
            -DeployRevision $baseRevision

        [void](Invoke-DeployLineageTestGit -Repository $workingRepository `
            -Arguments @('-c', 'user.name=Otziv Contract', '-c', 'user.email=contract@local.invalid',
                'commit', '--quiet', '--allow-empty', '-m', 'ahead'))
        $aheadRevision = Invoke-DeployLineageTestGit -Repository $workingRepository -Arguments @('rev-parse', 'HEAD')
        Assert-OtzivDeployRevisionContainsProductionMain -Repository $workingRepository `
            -ProductionMainRevision $protectedBase `
            -DeployRevision $aheadRevision
        [void](Assert-OtzivPreparedDeploySnapshotState -Repository $workingRepository `
                -ExpectedRevision $aheadRevision)

        [void](Invoke-DeployLineageTestGit -Repository $workingRepository `
            -Arguments @('checkout', '--quiet', '-b', 'post-validator-mutation', $aheadRevision))
        [void](Invoke-DeployLineageTestGit -Repository $workingRepository `
            -Arguments @('-c', 'user.name=Otziv Contract', '-c', 'user.email=contract@local.invalid',
                'commit', '--quiet', '--allow-empty', '-m', 'mutated after validator'))
        Assert-PreparedSnapshotStateRejected -Repository $workingRepository `
            -ExpectedRevision $aheadRevision `
            -ExpectedMessagePattern 'Prepared deploy snapshot HEAD changed from * to *' `
            -Scenario 'a descendant commit created after validation'

        [void](Invoke-DeployLineageTestGit -Repository $workingRepository -Arguments @('checkout', '--quiet', 'main'))
        $dirtyMutationPath = Join-Path $workingRepository 'post-validator-mutation.txt'
        Set-Content -LiteralPath $dirtyMutationPath -Value 'mutation' -Encoding Ascii
        Assert-PreparedSnapshotStateRejected -Repository $workingRepository `
            -ExpectedRevision $aheadRevision `
            -ExpectedMessagePattern 'Prepared deploy snapshot worktree changed after it was materialized or validated.*' `
            -Scenario 'an uncommitted mutation created after validation'
        Remove-Item -LiteralPath $dirtyMutationPath -Force
        [void](Assert-OtzivPreparedDeploySnapshotState -Repository $workingRepository `
                -ExpectedRevision $aheadRevision)

        $ignoredMutationPath = Join-Path $workingRepository 'ignored-mutation.yaml'
        Add-Content -LiteralPath (Join-Path $workingRepository '.git\info\exclude') `
            -Value '*.yaml' -Encoding Ascii
        Set-Content -LiteralPath $ignoredMutationPath -Value 'ignored mutation' -Encoding Ascii
        Assert-PreparedSnapshotStateRejected -Repository $workingRepository `
            -ExpectedRevision $aheadRevision `
            -ExpectedMessagePattern 'Prepared deploy snapshot worktree changed after it was materialized or validated.*' `
            -Scenario 'an ignored file created after validation'
        Remove-Item -LiteralPath $ignoredMutationPath -Force

        [void](Invoke-DeployLineageTestGit -Repository $workingRepository `
            -Arguments @('update-index', '--assume-unchanged', '--', 'tracked-sentinel.txt'))
        Assert-PreparedSnapshotStateRejected -Repository $workingRepository `
            -ExpectedRevision $aheadRevision `
            -ExpectedMessagePattern 'Prepared deploy snapshot contains assume-unchanged or skip-worktree files.*' `
            -Scenario 'an assume-unchanged index entry'
        [void](Invoke-DeployLineageTestGit -Repository $workingRepository `
            -Arguments @('update-index', '--no-assume-unchanged', '--', 'tracked-sentinel.txt'))
        [void](Assert-OtzivPreparedDeploySnapshotState -Repository $workingRepository `
                -ExpectedRevision $aheadRevision)

        [void](Invoke-DeployLineageTestGit -Repository $workingRepository -Arguments @('push', '--quiet', 'origin', 'main'))
        $protectedAhead = Update-OtzivProductionMainRevision -Repository $workingRepository
        if ($protectedAhead -cne $aheadRevision) {
            throw 'Production lineage guard did not refresh origin/main before checking ancestry.'
        }
        Assert-OtzivProductionMainRevisionUnchanged `
            -SelectedRevision $protectedAhead `
            -RefreshedRevision $protectedAhead
        $preparedAdvanceRejected = $false
        try {
            Assert-OtzivProductionMainRevisionUnchanged `
                -SelectedRevision $protectedBase `
                -RefreshedRevision $protectedAhead
        } catch {
            if ($_.Exception.Message -notlike 'Protected origin/main advanced from * to * while the deploy snapshot was being prepared.*') {
                throw
            }
            $preparedAdvanceRejected = $true
        }
        if (-not $preparedAdvanceRejected) {
            throw 'Prepared deployment did not reject origin/main advancing after its immutable revision was selected.'
        }

        [void](Invoke-DeployLineageTestGit -Repository $workingRepository -Arguments @('checkout', '--quiet', '--detach', $baseRevision))
        Assert-DeployLineageRejected -Repository $workingRepository `
            -ProductionMainRevision $protectedAhead `
            -DeployRevision $baseRevision `
            -Scenario 'behind'

        [void](Invoke-DeployLineageTestGit -Repository $workingRepository -Arguments @('checkout', '--quiet', '-b', 'divergent', $baseRevision))
        [void](Invoke-DeployLineageTestGit -Repository $workingRepository `
            -Arguments @('-c', 'user.name=Otziv Contract', '-c', 'user.email=contract@local.invalid',
                'commit', '--quiet', '--allow-empty', '-m', 'divergent'))
        $divergentRevision = Invoke-DeployLineageTestGit -Repository $workingRepository -Arguments @('rev-parse', 'HEAD')
        Assert-DeployLineageRejected -Repository $workingRepository `
            -ProductionMainRevision $protectedAhead `
            -DeployRevision $divergentRevision `
            -Scenario 'divergent'
        [void](Invoke-DeployLineageTestGit -Repository $workingRepository `
            -Arguments @('replace', '--graft', $divergentRevision, $protectedAhead))
        Assert-DeployLineageRejected -Repository $workingRepository `
            -ProductionMainRevision $protectedAhead `
            -DeployRevision $divergentRevision `
            -Scenario 'divergent with a local replacement parent'
        $validatorRejectedDivergentBase = $false
        try {
            & $snapshotValidatorPath -RepoRoot $workingRepository -BaseRevision $protectedAhead
        } catch {
            if ($_.Exception.Message -ne 'Deploy snapshot base revision is not an ancestor of the prepared snapshot.') {
                throw
            }
            $validatorRejectedDivergentBase = $true
        }
        if (-not $validatorRejectedDivergentBase) {
            throw 'Deploy snapshot validator accepted a divergent base revision.'
        }
        [void](Invoke-DeployLineageTestGit -Repository $workingRepository `
            -Arguments @('replace', '-d', $divergentRevision))

        [void](Invoke-DeployLineageTestGit -Repository $workingRepository `
            -Arguments @('remote', 'set-url', 'origin', (Join-Path $testRoot 'missing-origin.git')))
        try {
            [void](Update-OtzivProductionMainRevision -Repository $workingRepository)
        } catch {
            if ($_.Exception.Message -ne 'Unable to fetch the protected production branch origin/main. Production deployment remains blocked.') {
                throw
            }
            Write-Host 'Deploy lineage regression succeeded. Current/ahead accepted; behind/divergent/replaced ancestry, advanced prepared main, post-validator mutations, and fetch failure rejected.'
            return
        }
        throw 'Production lineage guard did not fail closed when origin/main could not be fetched.'
    } finally {
        if (Test-Path -LiteralPath $testRoot) {
            Remove-Item -LiteralPath $testRoot -Recurse -Force
        }
    }
}

Test-DeployLineageGuard

$workerBuildBlocks = [regex]::Matches($buildCompose, '(?m)^  external-review-worker:\s*$')
if ($workerBuildBlocks.Count -ne 1) {
    throw "docker-compose.build.yaml must define external-review-worker exactly once; found $($workerBuildBlocks.Count)."
}
Assert-Match $buildCompose 'EXTERNAL_REVIEW_WORKER_IMAGE[\s\S]{0,250}backend/external-review-worker' 'Build compose must publish the external review worker from its own Dockerfile.'
Assert-Match $productionCompose 'APP_MEMORY_LIMIT:-2304m' 'Production Compose must default backend memory to the audited 2304 MiB floor.'
Assert-Match $deploy 'APP_MEMORY_LIMIT[\s\S]{0,500}2304' 'Production deploy must reject an omitted or undersized backend memory limit.'
Assert-Match $deploy '\[switch\]\$EnableExternalReviewWorker' 'External review worker deployment must be an explicit opt-in.'
Assert-Match $deploy '\$buildArgs \+= @\("app", "nginx"\)[\s\S]{0,200}if \(\$EnableExternalReviewWorker\)[\s\S]{0,100}\$buildArgs \+= "external-review-worker"' 'Default builds must exclude the worker and append it only for an explicit opt-in.'
Assert-Match $legacyDeploy '\$buildArgs \+= @\("app", "nginx"\)' 'The quarantined legacy deploy must not build the external review worker implicitly.'
Assert-Match $deploy 'if \(\$EnableExternalReviewWorker\)[\s\S]{0,200}docker.+push.+\$externalReviewWorkerImage' 'Production deploy must push the worker image only in the opt-in branch.'
Assert-Match $deploy 'set_env EXTERNAL_REVIEW_WORKER_IMAGE.+external_review_worker_image' 'Production deploy must persist the worker image tag in the active VPS env.'
Assert-Match $deploy 'if \[ "`\$deploy_external_review_worker" = "1" \]; then[\s\S]{0,300}recreate_service_with_retry external-review-worker external-review[\s\S]{0,200}wait_service_healthy external-review-worker[\s\S]{0,300}assert_running_service_image external-review-worker[\s\S]{0,100}fi' 'Production deploy must start, health-check, and verify the worker image only when opted in.'
Assert-Match $deploy 'if \[ "`\$deploy_external_review_worker" != "1" \]; then[\s\S]{0,500}stop external-review-worker' 'Production deploy must stop a stale worker when the replacement backend has external checks disabled.'
Assert-Order $deploy 'wait_service_healthy app 1200' 'compose --profile external-review stop external-review-worker' 'A disabled rollout must keep the previous worker until the replacement backend is healthy.'
Assert-Order $deploy 'wait_service_healthy app 1200' '--remove-orphans --no-deps dozzle alloy' 'Orphan cleanup must not run until the replacement backend is healthy.'
Assert-Match $deploy 'if \[ "`\$deploy_external_review_worker" = "1" \]; then[\s\S]{0,150}compose --profile external-review up -d --remove-orphans --no-deps dozzle alloy[\s\S]{0,100}else[\s\S]{0,100}compose up -d --remove-orphans --no-deps dozzle alloy' 'Orphan cleanup must preserve the opted-in worker profile.'
Assert-Match $deploy 'set_env EXTERNAL_REVIEW_CHECK_ENABLED "true"[\s\S]{0,100}set_env EXTERNAL_REVIEW_CHECK_ENABLED "false"' 'Production deploy must persist the backend hard switch consistently with the worker opt-in.'
Assert-Match $deploy 'Join-Path \$scriptRoot ''DeploySnapshot\.ps1''' 'Production deploy must load the isolated automatic snapshot implementation.'
Assert-Match $snapshot '@\(''fetch'', ''--quiet'', ''--no-tags'', ''origin''[\s\S]{0,100}\+refs/heads/main:refs/remotes/origin/main' 'Production deploy must fetch the exact protected origin/main ref before selecting a release.'
Assert-Match $snapshot 'Windows PowerShell 5\.1[\s\S]{0,500}\$ErrorActionPreference = ''Continue''[\s\S]{0,500}PSNativeCommandUseErrorActionPreference' 'Native Git capture must preserve explicit exit-code handling under Windows PowerShell 5.1 and modern pwsh.'
Assert-Match $snapshot '\$fetchResult = Invoke-OtzivSnapshotGit[\s\S]{0,200}\$fetchResult\.ExitCode -ne 0' 'A failed production-main fetch must use the cross-PowerShell native result wrapper and fail closed.'
Assert-Match $snapshot '@\(''merge-base'', ''--is-ancestor'', \$resolvedProductionMain, \$resolvedDeployRevision\)' 'Production lineage verification must use Git ancestry rather than timestamps or branch names.'
Assert-Match $snapshot 'git --no-replace-objects -C \$Repository' 'Production Git verification must ignore local replacement-object ancestry.'
Assert-Order $deploy 'Update-OtzivProductionMainRevision -Repository $repoRoot' '$dirtyDeployInputs = @(Get-OtzivDeployChanges' 'Production must refresh and verify origin/main before inspecting or snapshotting local deployment inputs.'
Assert-Match $deploy '\$DeployProtectedMainRevision -notmatch ''\^\[0-9a-f\]\{40\}\$''[\s\S]{0,700}Update-OtzivProductionMainRevision -Repository \$repoRoot[\s\S]{0,300}Assert-OtzivProductionMainRevisionUnchanged' 'Prepared snapshots must refetch origin/main and reject a protected revision that advanced during snapshot validation.'
Assert-Order $deploy 'Assert-OtzivProductionMainRevisionUnchanged' '$dirtyDeployInputs = @(Get-OtzivDeployChanges' 'Prepared snapshots must verify protected-main stability before inspecting deployment inputs.'
Assert-Order $deploy 'Assert-OtzivDeployRevisionContainsProductionMain -Repository $repoRoot' '$dirtyDeployInputs = @(Get-OtzivDeployChanges' 'Production snapshots must verify protected-main ancestry before inspecting deployment inputs.'
Assert-Match $deploy '\$DeploySnapshotRevision -notmatch ''\^\[0-9a-f\]\{40\}\$''[\s\S]{0,400}Assert-OtzivPreparedDeploySnapshotState -Repository \$repoRoot' 'Prepared deployment must bind its child worktree to the exact immutable snapshot commit and require a clean tree.'
Assert-Order $deploy 'Assert-OtzivPreparedDeploySnapshotState -Repository $repoRoot' '$dirtyDeployInputs = @(Get-OtzivDeployChanges' 'Prepared child exact-revision and cleanliness verification must run before dirty-worktree handling.'
Assert-Match $snapshot '''--ignored=matching''' 'Prepared snapshot cleanliness must include ignored files that could otherwise enter a Docker context.'
Assert-Match $snapshot '@\(''ls-files'', ''-v''\)[\s\S]{0,300}\^\(\?:\[a-z\]\|S\)' 'Prepared snapshot verification must reject assume-unchanged and skip-worktree entries.'
Assert-Order $deploy '$snapshotMobileRelease = if ($SkipMobileApkUpload)' 'New-OtzivDeploySnapshot -Repository $repoRoot -InputPaths $deployInputPaths' 'Automatic snapshot deploys must pin the local mobile release before materializing the isolated worktree.'
Assert-Match $deploy '\$forwardParameters\[''MobileApkPath''\]\s*=\s*\$snapshotMobileRelease\.File\.FullName' 'Automatic snapshot recursion must forward the pinned absolute local APK path instead of rediscovering releases inside the Git snapshot.'
Assert-Match $deploy 'New-OtzivDeploySnapshot -Repository \$repoRoot -InputPaths \$deployInputPaths' 'Dirty production inputs must be captured through the isolated snapshot index.'
Assert-Match $deploy 'worktree add --detach \$snapshotWorktree \$snapshot\.Commit' 'Automatic deploy must materialize the exact snapshot in a detached worktree.'
Assert-Match $deploy '& \$snapshotValidator -RepoRoot \$snapshotWorktree -BaseRevision \$snapshot\.BaseRevision' 'Automatic deploy must validate the clean snapshot before contacting production.'
Assert-Order $deploy '& $snapshotValidator' '& $preparedDeployScript @forwardParameters' 'Automatic snapshot validation must finish before the production deploy script is invoked.'
Assert-Match $deploy 'Remove\(''AllowDirtyWorktree''\)' 'Automatic snapshot recursion must not forward the dirty-worktree bypass.'
Assert-Match $deploy '\$forwardParameters\[''DeploySnapshotRevision''\]\s*=\s*\$snapshot\.Commit' 'Automatic snapshot recursion must forward the exact immutable snapshot commit.'
Assert-Match $deploy '\$forwardParameters\[''DeployProtectedMainRevision''\]\s*=\s*\$protectedMainRevision' 'Automatic snapshot recursion must forward the immutable protected origin/main revision.'
Assert-Match $deploy '\$forwardParameters\[''ProjectFilesRoot''\]\s*=\s*\$ProjectFilesRoot' 'Automatic snapshot recursion must retain the original external project-files root.'
Assert-Match $deploy '\$forwardParameters\[''SshKey''\]\s*=\s*\$SshKey' 'Automatic snapshot recursion must retain the resolved external SSH private key.'
Assert-Match $deploy '\$forwardParameters\[''SshKnownHostsFile''\]\s*=\s*\$SshKnownHostsFile' 'Automatic snapshot recursion must retain the resolved external SSH known_hosts file.'
Assert-Order $deploy '$forwardParameters[''ProjectFilesRoot''] = $ProjectFilesRoot' '& $preparedDeployScript @forwardParameters' 'External project-file paths must be pinned before the temporary snapshot child starts.'
Assert-Order $deploy 'Automatic deploy snapshot validation was bypassed explicitly.' '[void](Assert-OtzivPreparedDeploySnapshotState -Repository $snapshotWorktree' 'Skipping snapshot tests must not skip the post-validation exact-revision and cleanliness check.'
Assert-Order $deploy 'Automatic deploy snapshot validation was bypassed explicitly.' '@(''clean'', ''-ffdx'')' 'Generated and ignored validator artifacts must be removed even when validation is bypassed.'
Assert-Order $deploy '@(''clean'', ''-ffdx'')' '[void](Assert-OtzivPreparedDeploySnapshotState -Repository $snapshotWorktree' 'Snapshot cleanup must finish before the post-validator byte-state check.'
Assert-Order $deploy '[void](Assert-OtzivPreparedDeploySnapshotState -Repository $snapshotWorktree' '& $preparedDeployScript @forwardParameters' 'The prepared worktree must be rechecked after validation immediately before launching the child deploy.'
Assert-Match $deploy 'Invoke-External -FilePath "docker" -Arguments \$buildArgs[\s\S]{0,300}Assert-OtzivPreparedDeploySnapshotState -Repository \$repoRoot[\s\S]{0,300}docker.+push' 'Prepared source bytes must be rechecked after Docker build and before image push.'
Assert-Match $deploy 'Copy-DeployPath -RepoRoot \$repoRoot[\s\S]{0,300}Assert-OtzivPreparedDeploySnapshotState -Repository \$repoRoot' 'Prepared source bytes must be rechecked after deployment-bundle copying.'
Assert-Match $deploy '\$revisionTagSuffix = "-\$\(\$gitRevision\.Substring\(0, 12\)\)"' 'Production image tags must include the exact deploy snapshot revision.'
Assert-Match $snapshot '''backend''[\s\S]{0,100}''frontend''[\s\S]{0,100}''mobile''[\s\S]{0,100}''whatsapp''[\s\S]{0,100}''infrastructure''' 'Automatic snapshots must include every deploy source tree.'
Assert-NotMatch $snapshot '''outreach-module''' 'Automatic snapshots must not include the extracted outreach module.'
Assert-NotMatch $snapshot '(?m)^\s*''\.''\s*,?\s*$' 'Automatic snapshots must never add the unrestricted repository root.'
Assert-Match $snapshot 'GIT_INDEX_FILE' 'Automatic snapshots must use an isolated Git index.'
Assert-Match $snapshot '@\(''read-tree'', \$baseRevision\)' 'Automatic snapshots must start from the exact current commit tree.'
Assert-Match $snapshot 'Invoke-OtzivSnapshotGit -Repository \$repositoryRoot[\s\S]{0,100}@\(''add'', ''-A'', ''--''\) \+ \$InputPaths' 'Automatic snapshots must capture tracked, deleted, and untracked allowlisted inputs.'
Assert-Match $snapshot '@\(''commit-tree'', \$tree, ''-p'', \$baseRevision' 'Automatic snapshots must produce an immutable Git commit without changing the user branch.'
Assert-Match $snapshot '''update-ref'', \$snapshotRef, \$commit' 'Automatic snapshot commits must retain a local recovery reference.'
Assert-Match $snapshotValidator 'run-secret-scan\.ps1''[\s\S]{0,100}Mode\s*=\s*''dir''' 'Automatic deploy snapshots must pass a secret scan.'
Assert-Match $snapshotValidator 'check-flyway-contract\.ps1''[\s\S]{0,100}BaseRevision\s*=\s*\$base' 'Automatic deploy snapshots must enforce append-only Flyway migrations.'
Assert-Match $snapshotValidator 'merge-base --is-ancestor \$base \$headRevision' 'Snapshot validation must reject a base revision that is not an ancestor of the prepared snapshot.'
Assert-Match $snapshotValidator 'git --no-replace-objects -C \$root merge-base' 'Snapshot base validation must ignore local replacement-object ancestry.'
Assert-Match $snapshotValidator '''backend full test suite''[\s\S]{0,300}''verify''' 'Changed backend snapshots must pass the full Maven suite.'
Assert-NotMatch $snapshotValidator "\.\./pom\.xml|outreach-module" 'Backend snapshot validation must remain independent from the extracted outreach reactor.'
Assert-Match $snapshotValidator '''frontend unit tests''[\s\S]{0,200}''--watch=false''' 'Changed frontend snapshots must pass unit tests.'
Assert-Match $snapshotValidator '''mobile unit tests''[\s\S]{0,200}''test:unit''' 'Changed mobile snapshots must pass unit tests.'
Assert-Match $snapshotValidator 'previousMultibrowserApiKey[\s\S]{0,500}snapshot-compose-model-validation-placeholder' 'Compose snapshot validation must use a non-secret MultiBrowser credential placeholder.'
Assert-Match $snapshotValidator 'finally\s*\{[\s\S]{0,300}SetEnvironmentVariable\(''MULTIBROWSER_API_KEY'',\s*\$previousMultibrowserApiKey' 'Compose snapshot validation must restore the operator MultiBrowser environment after model checks.'

$workerPushCount = [regex]::Matches($deploy, 'Invoke-External[^\r\n]+@\("push", \$externalReviewWorkerImage\)').Count
if ($workerPushCount -ne 1) {
    throw "Production deploy must contain exactly one guarded worker push call; found $workerPushCount."
}
$workerPullCount = [regex]::Matches($deploy, '(?m)^\s*compose --profile external-review pull app nginx external-review-worker\s*$').Count
if ($workerPullCount -ne 1) {
    throw "Production deploy must contain exactly one guarded worker pull call; found $workerPullCount."
}
$workerRecreateCount = [regex]::Matches($deploy, '(?m)^\s*recreate_service_with_retry external-review-worker external-review\s*$').Count
if ($workerRecreateCount -ne 1) {
    throw "Production deploy must contain exactly one guarded worker recreate call; found $workerRecreateCount."
}
$workerWaitCount = [regex]::Matches($deploy, '(?m)^\s*wait_service_healthy external-review-worker 300 external-review\s*$').Count
if ($workerWaitCount -ne 2) {
    throw "Production deploy must contain exactly two guarded worker health checks; found $workerWaitCount."
}

Assert-Match $deploy 'DEPLOY_DB_BACKUP_ENCRYPTION_KEY_BASE64 must decode to exactly 32 bytes' 'Production deploy must validate a dedicated 32-byte pre-deploy DB backup key.'
Assert-Match $deploy 'Deploy DB-backup encryption and credential-field encryption must use different keys' 'Production deploy must reject reuse of the credential-field encryption key for DB backups.'
Assert-Match $deploy 'Pre-deploy and scheduled DB backups must use different encryption keys' 'Pre-deploy backups must not reuse the scheduled-backup encryption key.'
Assert-Match $backup 'create_backup\(\)[\s\S]{0,1000}assert_distinct_backup_keys "\$env_file" "\$key_base64"[\s\S]{0,300}docker inspect' 'Decoded backup-key separation must be enforced remotely before any pre-deploy backup state is created.'
Assert-NotMatch $backup '(?m)^\s*key_base64="\$\(get_env "\$env_file" OTZIV_CREDENTIAL_ENCRYPTION_ACTIVE_KEY_BASE64' 'Pre-deploy DB backup must never select the credential-field encryption key as its encryption key.'
Assert-Match $backup 'mysqldump[\s\S]{0,500}\| gzip -9 \| openssl enc' 'mysqldump must stream directly through gzip into encryption without a plaintext database artifact.'
Assert-Match $backup 'MYSQL_PWD="\$MYSQL_PASSWORD" exec mysqldump[\s\S]{0,100}-u"\$MYSQL_USER"' 'Pre-deploy mysqldump must avoid exposing the database password as a command-line argument.'
Assert-NotMatch $backup 'mysqldump[\s\\\r\n]+-u"\$MYSQL_USER" -p"\$MYSQL_PASSWORD"' 'Pre-deploy mysqldump must not pass the database password through argv.'
Assert-NotMatch $backup 'gzip_file="\$work_dir/database\.sql\.gz"' 'Normal backup creation must not write a plaintext compressed database artifact to disk.'
Assert-Match $backup 'HMAC_DERIVATION_LABEL|otziv-predeploy-backup-authentication-v1' 'Encrypted pre-deploy backups must have a separately derived HMAC key.'
Assert-Match $backup 'decrypt_artifact_to_stdout[\s\S]{0,1000}gzip -t' 'The encrypted backup must be stream-decrypted and gzip-verified before deployment continues.'
Assert-Match $backup 'FLYWAY_FINGERPRINT=' 'The backup manifest must bind the checked Flyway history state.'
Assert-Match $backup 'restore_clean[\s\S]{0,5000}DROP DATABASE IF EXISTS[\s\S]{0,500}CREATE DATABASE' 'Recovery must restore into a clean database schema rather than overlaying newer objects.'
Assert-Match $backup 'for unit in otziv-prod-up.timer otziv-prod-up.service' 'Clean restore must fail closed on both timer and active self-heal service states.'
Assert-Match $backup 'systemctl is-enabled otziv-prod-up.timer[\s\S]{0,500}Refusing restore while otziv-prod-up.timer is enabled' 'Clean restore must require self-heal autostart to be disabled as well as inactive.'
Assert-Match $deploy 'Rollback scaffold only[\s\S]{0,500}systemctl disable --now otziv-prod-up.timer[\s\S]{0,150}systemctl stop otziv-prod-up.service' 'Generated rollback instructions must disable timer autostart before clean database restore.'
Assert-Match $backup 'write-path services are running' 'Clean restore must fail while application write paths are active.'
Assert-Match $backup 'OTZIV_SCHEMA_DEFAULTS' 'Clean restore must recover schema defaults from authenticated encrypted backup content.'
Assert-Match $backup 'OTZIV_RESTORE_COMPATIBILITY_SQL' 'Schema compatibility SQL must cross the docker exec boundary without host-side expansion.'
Assert-NotMatch $backup 'CREATE DATABASE[^\r\n]+utf8mb4_unicode_ci' 'Clean restore must not silently replace the production schema collation.'
$unsafeRestoreQuotePrefix = ([string][char]39 * 3) + '$OTZIV_RESTORE_'
if ($backup.Contains($unsafeRestoreQuotePrefix)) {
    throw 'Restore variables must not use triple-quote shell syntax that expands on the host under set -u.'
}
Assert-Match $deploy 'Flyway history changed after the verified pre-deploy backup' 'The rollout must fail if Flyway history changes after backup creation.'
Assert-Match $deploy 'PreDeployBackupDirectory must stay outside the Git worktree' 'Downloaded production DB backups must never be written inside the Git worktree.'
Assert-Match $deploy 'Get-OtzivBackupDirectory -RepoRoot \$repoRoot -ProjectFilesRoot \$ProjectFilesRoot[\s\S]{0,300}Join-Path \$otzivBackupDirectory ''pre-deploy''' 'Production backup downloads must default to the external sibling .otziv backup tree.'
Assert-Match $deploy 'must be a dedicated release subdirectory, not a filesystem root, project-files root, or shared backup parent' 'Backup ACL hardening must reject dangerously broad local target directories.'
Assert-Match $deploy 'existing custom PreDeployBackupDirectory is not accepted[\s\S]{0,500}Assert-NoReparsePointInExistingPath' 'Backup ACL hardening must require a dedicated custom leaf and reject reparse-point ancestors.'
Assert-Match $deploy 'IdentitiesOnly=yes[\s\S]{0,200}UserKnownHostsFile=\$sshKnownHostsOptionPath' 'Production SSH and SCP must use only the external project key and known_hosts file.'
Assert-Match $deploy 'Get-OtzivSshDirectory -RepoRoot \$repoRoot -ProjectFilesRoot \$ProjectFilesRoot' 'Production SSH defaults must resolve from the external sibling .ssh directory.'
Assert-Match $deploy 'ProjectFilesRoot must be a dedicated directory, not a filesystem root' 'External project storage must reject ambiguous filesystem-root overrides.'
Assert-Match $deploy 'SSH private key not found:[\s\S]{0,200}Assert-NoReparsePointInExistingPath -Path \$SshKey' 'The external SSH private key must be a real leaf without reparse-point ancestors.'
Assert-Match $deploy 'SSH known_hosts file not found:[\s\S]{0,200}Assert-NoReparsePointInExistingPath -Path \$SshKnownHostsFile' 'The external SSH trust store must be a real leaf without reparse-point ancestors.'
Assert-Match $deploy 'StrictHostKeyChecking=accept-new' 'SSH must reject changed host keys while allowing the first verified endpoint connection to be recorded.'
Assert-NotMatch $deploy 'USERPROFILE|SpecialFolder\]::UserProfile|C:\\Users\\Hunt|D:\\Java\\otziv|F:\\Работа\\Проекты' 'Production deploy must not fall back to legacy profile or checkout paths.'
Assert-Match $deploy 'function Invoke-ProcessWithWallClockTimeout' 'Production deploy must provide a bounded external-process runner for the early SSH preflight.'
Assert-Match $deploy 'ProcessStartInfo[\s\S]{0,500}RedirectStandardOutput = \$true[\s\S]{0,200}RedirectStandardError = \$true[\s\S]{0,300}ArgumentList\.Add\(\$argument\)' 'The bounded process runner must pass arguments without shell interpolation and drain both output streams.'
Assert-Match $deploy 'ReadToEndAsync\(\)[\s\S]{0,200}ReadToEndAsync\(\)[\s\S]{0,300}WaitForExit\(\$TimeoutSeconds \* 1000\)' 'The bounded process runner must asynchronously capture diagnostics before applying its wall-clock timeout.'
Assert-Match $deploy '\$process\.Kill\(\$true\)[\s\S]{0,200}\$process\.Kill\(\)' 'A timed-out SSH preflight must kill the entire process tree with a single-process fallback.'
Assert-Match $deploy 'ExitCode\s+=\s+if \(\$timedOut\)[\s\S]{0,150}TimedOut\s+=\s+\$timedOut[\s\S]{0,150}Diagnostic\s+=\s+\$diagnostic' 'The bounded process runner must return exit, timeout, and redacted diagnostic state.'
Assert-Match $deploy '\$sshPreflightProcessTimeoutSeconds = 25[\s\S]{0,100}\$sshPreflightAttempts = 3[\s\S]{0,500}Invoke-ProcessWithWallClockTimeout[\s\S]{0,300}-TimeoutSeconds \$sshPreflightProcessTimeoutSeconds' 'Production deploy must hard-limit every early SSH preflight attempt to 25 seconds.'
Assert-Match $deploy '\$sshPreflightTransient = \$sshPreflightResult\.TimedOut -or \$sshPreflightResult\.ExitCode -eq 255[\s\S]{0,600}Start-Sleep -Seconds 10' 'Production deploy must retry only transient read-only SSH preflight failures.'
Assert-NotMatch $deploy 'Checking VPS SSH access before build/push\.\.\.[\s\S]{0,300}& ssh @sshArgs' 'The early SSH preflight must never invoke an unbounded native ssh process directly.'
Assert-Order $deploy 'Checking VPS SSH access before build/push...' 'Invoke-External -FilePath "docker" -Arguments $buildArgs' 'Production deploy must prove SSH access before spending time on Docker builds.'
Assert-Match $deploy 'mkdir \$remoteUploadDirectoryQuoted[\s\S]{0,150}chmod 700 \$remoteUploadDirectoryQuoted[\s\S]{0,500}Copy-DeployBundle' 'The secret-bearing deploy bundle must be uploaded only inside a pre-created 0700 directory.'
Assert-Match $deploy 'WriteAllText\([\s\S]{0,250}\$remoteScript[\s\S]{0,500}Get-FileHash[\s\S]{0,500}Copy-DeployBundle[\s\S]{0,1000}expected_sha256[\s\S]{0,500}sha256sum' 'The complete remote rollout must be uploaded as a hash-verified file before execution.'
Assert-Match $deploy 'sha256sum -- "`\$rollout_script"[\s\S]{0,500}exec bash "`\$rollout_script" </dev/null' 'The remote rollout must execute the hash-verified file with detached stdin.'
Assert-NotMatch $deploy '\$remoteScript\s*\|\s*&\s*ssh' 'The production rollout must never stream executable Bash through stdin.'
$rolloutPathInitializationCount = [regex]::Matches($deploy, '(?m)^rollout_script_path=\$remoteRolloutScriptQuoted\s*$').Count
if ($rolloutPathInitializationCount -ne 1) {
    throw "The main rollout must initialize rollout_script_path exactly once before EXIT cleanup can reference it under set -u; found $rolloutPathInitializationCount initializations."
}
Assert-Match $deploy 'redactedCommand = Format-RedactedCommand[\s\S]{0,150}Write-Warning "Command failed on attempt' 'Retry warnings must use the central command redactor.'
Assert-NotMatch $deploy 'Write-Warning "Command failed on attempt[^\r\n]+\$Arguments -join' 'Retry warnings must never log raw command arguments.'
Assert-Match $deploy 'expectedRemoteDeployMarker[\s\S]{0,100}OTZIV_DEPLOY_COMPLETE=[\s\S]{0,300}-ceq \$expectedRemoteDeployMarker[\s\S]{0,200}Count -ne 1' 'The local deploy must require exactly one case-sensitive token-bound completion marker.'
$completionMarkerEmitterCount = [regex]::Matches($deploy, 'OTZIV_DEPLOY_COMPLETE=%s\\n').Count
if ($completionMarkerEmitterCount -ne 1) {
    throw "Production deploy must contain exactly one remote completion marker emitter; found $completionMarkerEmitterCount."
}
Assert-Match $deploy 'unset APP_IMAGE WEB_IMAGE EXTERNAL_REVIEW_WORKER_IMAGE WHATSAPP_IMAGE[\s\S]{0,150}EXTERNAL_REVIEW_CHECK_ENABLED[\s\S]{0,150}COMPOSE_PROJECT_NAME COMPOSE_PROFILES' 'Ambient SSH variables must not override release-critical Compose values.'
Assert-Match $deploy 'compose_project_name="otziv-prod"[\s\S]{0,300}docker compose --project-name "`\$compose_project_name" --project-directory "`\$remote_path"[\s\S]{0,300}docker-compose --project-name "`\$compose_project_name" --project-directory "`\$remote_path"' 'Every production Compose invocation must prefer the plugin and pin the audited project name and directory.'
Assert-Match $selfHeal 'compose_project_name="otziv-prod"[\s\S]{0,100}if docker compose version[\s\S]{0,100}compose=\(docker compose\)' 'Production self-heal must prefer the same Compose plugin as the rollout.'
Assert-Match $selfHeal 'compose_args=\([\s\S]{0,100}--project-name "\$compose_project_name"[\s\S]{0,100}--project-directory "\$deploy_path"' 'Production self-heal must pin the same audited project name and directory as the rollout.'
Assert-Match $deploy 'assert_compose_service_image\(\)[\s\S]{0,1000}compose config --format json[\s\S]{0,2000}assert_running_service_image\(\)[\s\S]{0,1000}docker image inspect[\s\S]{0,500}docker inspect' 'Deploy must verify both resolved Compose image references and running container image IDs.'
Assert-Match $deploy 'assert_compose_service_image external-review-worker "`\$external_review_worker_image" external-review' 'The opt-in worker image must be resolved with its Compose profile enabled.'
Assert-Match $deploy 'assert_running_service_image app "`\$app_image"[\s\S]{0,1500}assert_running_service_image nginx "`\$web_image"' 'Backend and frontend image IDs must be verified during the rollout.'
Assert-Match $deploy 'whatsapp\\chromium-launch\.js' 'Deploy bundle must include the shared audited Chromium launch arguments.'
Assert-Match $deploy 'whatsapp\\chromium-smoke\.js' 'Deploy bundle must include the real Chromium launch smoke test.'
$whatsappRuntimeDependencies = Get-LocalNodeDependencyClosure -EntryPath $whatsappIndexPath
$resolvedRepoRoot = [IO.Path]::GetFullPath($repoRoot)
$repoRootPrefix = $resolvedRepoRoot.TrimEnd([char[]]@(
        [IO.Path]::DirectorySeparatorChar,
        [IO.Path]::AltDirectorySeparatorChar
    )) + [IO.Path]::DirectorySeparatorChar
foreach ($dependencyPath in $whatsappRuntimeDependencies) {
    $resolvedDependencyPath = [IO.Path]::GetFullPath($dependencyPath)
    if (-not $resolvedDependencyPath.StartsWith($repoRootPrefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw "WhatsApp runtime dependency escapes the repository: $resolvedDependencyPath"
    }
    $relativePath = $resolvedDependencyPath.Substring($repoRootPrefix.Length).Replace('/', '\')
    Assert-Match $deploy ('"' + [regex]::Escape($relativePath) + '"') "Deploy bundle must include WhatsApp runtime dependency: $relativePath"
}
Assert-Match $whatsappIndex 'chromiumLaunchArgs\(proxyServerArg\(\)\)' 'WhatsApp clients must use the shared audited Chromium launch arguments.'
Assert-Match $whatsappIndex 'webVersionCache:\s*\{[\s\S]{0,300}type:\s*"none"' 'WhatsApp Web cache must stay disabled because its default local persistence targets the read-only application directory before READY.'
Assert-Match $whatsappPackage '"brace-expansion"\s*:\s*"2\.1\.4"' 'WhatsApp must retain the patched brace-expansion override.'
Assert-Match $whatsappPackage '"ip-address"\s*:\s*"10\.4\.0"' 'WhatsApp must retain the patched ip-address override.'
Assert-NotMatch ($whatsappIndex + "`n" + $whatsappChromiumLaunch) '--no-sandbox|--disable-setuid-sandbox|--no-zygote' 'WhatsApp Chromium must keep its Linux sandbox enabled without the incompatible no-zygote flag.'
Assert-Match $whatsappChromiumSmoke 'require\("puppeteer"\)[\s\S]{0,300}require\("\./chromium-launch"\)[\s\S]{0,500}puppeteer\.launch[\s\S]{0,500}args: chromiumLaunchArgs\(""\)' 'Chromium smoke test must launch real Puppeteer with the same audited arguments as production.'
Assert-Match $deploy 'compose run --rm --no-deps --interactive=false -T --entrypoint node whatsapp_lika chromium-smoke\.js </dev/null' 'Deploy must run the real Chromium sandbox smoke test under the production Compose security profile.'
Assert-Order $deploy 'whatsapp_lika chromium-smoke.js </dev/null' 'compose stop whatsapp_lika whatsapp_vika' 'The real Chromium sandbox preflight must pass before existing WhatsApp gateways are stopped.'

$publicBindPermissionFunction = [regex]::Match(
    $deploy,
    '(?ms)^normalize_public_bind_mount_permissions\(\) \{\s*(?<body>.*?)^\}'
)
if (-not $publicBindPermissionFunction.Success) {
    throw 'Production deploy must define a dedicated public bind-mount permission normalizer.'
}
$publicBindPermissionBody = $publicBindPermissionFunction.Groups['body'].Value
Assert-Match $publicBindPermissionBody 'for relative_path in infrastructure infrastructure/keycloak; do' 'Only the audited public parent directories may be made traversable.'
Assert-Match $publicBindPermissionBody 'infrastructure/keycloak/themes[\s\\]+infrastructure/prometheus[\s\\]+infrastructure/loki[\s\\]+infrastructure/tempo[\s\\]+infrastructure/alloy[\s\\]+infrastructure/grafana; do' 'Every public directory bind source must be covered by targeted normalization.'
Assert-Match $publicBindPermissionBody 'for relative_path in infrastructure/keycloak/realm-config\.prod\.json; do' 'The standalone Keycloak realm bind source must be covered explicitly.'
Assert-Match $publicBindPermissionBody 'find -P "`\$target" ! -type d ! -type f -print -quit' 'Public bind trees must reject symlinks and other non-regular filesystem nodes before chmod.'
Assert-Match $publicBindPermissionBody 'find -P "`\$target" -type d -exec chmod 0755 -- \{\} \+' 'Public bind directories must receive traversal-only public permissions.'
Assert-Match $publicBindPermissionBody 'find -P "`\$target" -type f -exec chmod 0644 -- \{\} \+' 'Public bind files must receive read-only public permissions.'
Assert-NotMatch $publicBindPermissionBody 'chmod\s+-R|find -P "`\$remote_path/infrastructure"|`\$env_file|\.env|\.deploy-backups|\.deploy-mobile-update' 'Permission normalization must not recursively expose infrastructure or touch secret/runtime artifacts.'

$publicBindSources = @(
    @{ Compose = './infrastructure/keycloak/realm-config.prod.json'; CoveredBy = 'infrastructure/keycloak/realm-config.prod.json' },
    @{ Compose = './infrastructure/keycloak/themes'; CoveredBy = 'infrastructure/keycloak/themes' },
    @{ Compose = './infrastructure/prometheus/prometheus.yml'; CoveredBy = 'infrastructure/prometheus' },
    @{ Compose = './infrastructure/loki/loki-config.yaml'; CoveredBy = 'infrastructure/loki' },
    @{ Compose = './infrastructure/tempo/tempo.yaml'; CoveredBy = 'infrastructure/tempo' },
    @{ Compose = './infrastructure/alloy/config.alloy'; CoveredBy = 'infrastructure/alloy' },
    @{ Compose = './infrastructure/grafana/provisioning'; CoveredBy = 'infrastructure/grafana' },
    @{ Compose = './infrastructure/grafana/dashboards'; CoveredBy = 'infrastructure/grafana' }
)
foreach ($publicBindSource in $publicBindSources) {
    Assert-Match $productionCompose ([regex]::Escape($publicBindSource.Compose)) "Production Compose bind source is missing: $($publicBindSource.Compose)"
    Assert-Match $publicBindPermissionBody ([regex]::Escape($publicBindSource.CoveredBy)) "Deploy permission normalization does not cover: $($publicBindSource.Compose)"
}
$publicBindNormalizerCallCount = [regex]::Matches($deploy, '(?m)^normalize_public_bind_mount_permissions\s*$').Count
if ($publicBindNormalizerCallCount -ne 1) {
    throw "Production rollout must invoke public bind-mount normalization exactly once; found $publicBindNormalizerCallCount calls."
}
Assert-Order $deploy 'tar --warning=no-timestamp -xzf "`$bundle_path" -C "`$remote_path"' 'normalize_public_bind_mount_permissions' 'Public bind permissions must be normalized only after protected bundle extraction.'
Assert-Order $deploy 'normalize_public_bind_mount_permissions' 'compose up -d --no-deps mysql keycloak-postgres loki tempo' 'Public bind permissions must be normalized before any affected production container starts.'

Assert-Order $deploy 'Creating and verifying mandatory pre-deploy database backup on VPS' 'bash infrastructure/scripts/prod/validate-flyway-migrations.sh' 'The mandatory DB backup must finish before Flyway validation and app startup.'
Assert-Match $deploy 'deploy_lock_token[\s\S]{0,5000}mkdir "`\$deploy_lock_dir"' 'The rollout must acquire a durable cross-session lock before creating the backup.'
Assert-Match $deploy 'release_deploy_lock' 'The rollout must explicitly release its durable deployment lock.'
Assert-Match $deploy 'release_deploy_lock\(\)[\s\S]{0,500}if ! rm -f[\s\S]{0,250}return 1[\s\S]{0,150}if ! rmdir[\s\S]{0,250}return 1' 'Lock release must propagate failures even when called from a conditional cleanup branch.'
Assert-Match $deploy 'pause_self_heal\s+tar --warning=no-timestamp -xzf[\s\S]{0,800}create-pre-deploy-db-backup\.sh" create' 'Production self-heal must be stopped before the mandatory database backup begins.'
Assert-Match $deploy 'trap cleanup_preflight EXIT[\s\S]{0,100}trap ''exit 130'' INT[\s\S]{0,100}trap ''exit 143'' TERM[\s\S]{0,200}preflight_dir="`\$\(mktemp' 'Pre-backup cleanup must be armed with non-zero signal exits before temporary-directory creation can fail.'
Assert-Match $deploy 'backup_dir="\.deploy-backups/`\$deploy_tag/rollout-`\$deploy_lock_token"' 'Each repeated deploy tag must preserve compose/env rollback files in a unique attempt directory.'
Assert-Match $deploy 'deploy_cleanup\(\)[\s\S]{0,1500}systemctl disable "`\$self_heal_timer"' 'Failure cleanup must disable self-heal so a reboot cannot continue a failed rollout.'
Assert-Match $deploy 'if \[ "`\$status" -eq 0 \] && \[ "`\$release_payload_complete" != "1" \]; then[\s\S]{0,250}status="1"' 'A clean EOF before the verified release sentinel must be converted into a failed rollout.'
Assert-Match $deploy 'if \[ "`\$status" -eq 0 \]; then[\s\S]{0,200}resume_self_heal_timer[\s\S]{0,200}release_deploy_lock[\s\S]{0,200}OTZIV_DEPLOY_COMPLETE=%s' 'Only the completed-success cleanup branch may restore self-heal, release the lock, and emit the marker.'
Assert-Match $deploy 'publish_bundled_mobile_release\s+release_payload_complete="1"\s+"@' 'The release sentinel must be the final command in the rollout main body.'
Assert-Match $deploy 'trap deploy_cleanup EXIT[\s\S]{0,100}trap ''exit 130'' INT[\s\S]{0,100}trap ''exit 143'' TERM' 'INT and TERM must become non-zero exits before EXIT cleanup evaluates rollout status.'
$interruptTrapCount = [regex]::Matches($deploy, '(?m)^trap ''exit 130'' INT\s*$').Count
$terminateTrapCount = [regex]::Matches($deploy, '(?m)^trap ''exit 143'' TERM\s*$').Count
if ($interruptTrapCount -ne 2 -or $terminateTrapCount -ne 2) {
    throw "Preflight and rollout must each map INT/TERM to non-zero exits; found INT=$interruptTrapCount TERM=$terminateTrapCount."
}
Assert-NotMatch $deploy 'trap (?:cleanup_preflight|deploy_cleanup) EXIT INT TERM' 'EXIT, INT, and TERM must not share a cleanup trap that can preserve a stale zero status.'
Assert-Match $deploy 'self-heal-timer-was-enabled' 'Deploy must persist the timer enablement state as well as its active state.'
Assert-Match $deploy 'resume_self_heal_timer\(\)[\s\S]{0,300}systemctl enable "`\$self_heal_timer"' 'A successful deploy must restore the original self-heal enablement state.'
Assert-Match $deploy 'resume_self_heal_timer\(\)[\s\S]{0,600}if ! sudo -n systemctl enable[\s\S]{0,100}return 1[\s\S]{0,300}if ! sudo -n systemctl start[\s\S]{0,100}return 1[\s\S]{0,150}return 0' 'Self-heal restoration must explicitly propagate enable/start failures from conditional cleanup.'
Assert-Match $deploy 'timer_was_active="`\$\(cat[\s\S]{0,100}timer_was_enabled="`\$\(cat[\s\S]{0,500}case "`\$timer_was_enabled"[\s\S]{0,500}systemctl enable otziv-prod-up.timer[\s\S]{0,300}systemctl start otziv-prod-up.timer' 'Pre-rollout cleanup must validate both protected states before enabling and starting the old timer.'
Assert-Match $deploy 'install -o root -g root -m 0755[\s\S]{0,150}otziv-prod-up\.sh' 'Deploy must install the version-controlled production self-heal helper.'
Assert-Match $selfHealTimer '(?m)^OnActiveSec=90s\s*$' 'Self-heal timer must schedule its first run relative to every timer activation, including post-deploy restart.'
Assert-Match $selfHealTimer '(?m)^OnUnitInactiveSec=2min\s*$' 'Self-heal timer must reschedule from completion of the Type=oneshot service.'
Assert-NotMatch $selfHealTimer '(?m)^(?:Requires|After)=docker\.service\s*$' 'The timer itself must survive an independent Docker stop/start; Docker ordering belongs on the invoked service.'
Assert-NotMatch $selfHealTimer '(?m)^OnBootSec=' 'Self-heal timer must not depend on a boot-relative trigger that becomes elapsed after deploy restart.'
Assert-NotMatch $selfHealTimer '(?m)^OnUnitActiveSec=' 'Self-heal timer must not use an active-state trigger for a Type=oneshot target.'
Assert-NotMatch $selfHealTimer '(?m)^Persistent=' 'Persistent has no effect for this monotonic-only timer and must not imply catch-up semantics.'
Assert-Match $selfHealServiceTemplate '(?m)^Type=oneshot\s*$' 'Version-controlled self-heal service must remain a oneshot unit.'
Assert-Match $selfHealServiceTemplate '(?m)^WorkingDirectory=@@OTZIV_DEPLOY_PATH@@\s*$' 'Self-heal service must render the validated production deployment path.'
$serviceTemplatePlaceholderCount = [regex]::Matches($selfHealServiceTemplate, '@@OTZIV_DEPLOY_PATH@@').Count
if ($serviceTemplatePlaceholderCount -ne 2) {
    throw "Self-heal service template must contain exactly two deploy-path placeholders; found $serviceTemplatePlaceholderCount."
}
Assert-Match $deploy 'infrastructure\\systemd\\otziv-prod-up\.timer[\s\S]{0,150}infrastructure\\systemd\\otziv-prod-up\.service\.in' 'Deploy bundle must include both version-controlled self-heal units.'
Assert-Match $deploy 'systemd-analyze verify[\s\S]{0,500}install -o root -g root -m 0644[\s\S]{0,300}otziv-prod-up\.timer[\s\S]{0,300}install -o root -g root -m 0644[\s\S]{0,300}otziv-prod-up\.service[\s\S]{0,150}systemctl daemon-reload' 'Deploy must verify, install, and reload both self-heal units while the timer is stopped.'
Assert-Match $deploy 'Rollback scaffold only[\s\S]{0,1500}otziv-prod-up\.timer /etc/systemd/system/otziv-prod-up\.timer[\s\S]{0,300}otziv-prod-up\.service /etc/systemd/system/otziv-prod-up\.service[\s\S]{0,150}systemctl daemon-reload' 'Rollback scaffold must restore both prior self-heal units before timer recovery.'
$finiteTimerCheckCount = [regex]::Matches($deploy, 'NextElapseUSecMonotonic').Count
if ($finiteTimerCheckCount -ne 3) {
    throw "Pre-backup cleanup, rollout cleanup, and local SSH cleanup must each require a finite next self-heal run; found $finiteTimerCheckCount checks."
}
Assert-Match $selfHeal '\[\[ -e "\$deploy_lock" \|\| -L "\$deploy_lock" \]\]' 'Production self-heal must respect the durable deploy lock, including symlinks.'
Assert-Match $selfHeal 'EXTERNAL_REVIEW_CHECK_ENABLED[\s\S]{0,1000}if \[\[ "\$external_review_enabled" == "true" \]\][\s\S]{0,200}--profile external-review up -d[\s\S]{0,300}stop external-review-worker[\s\S]{0,200}up -d' 'Production self-heal must start the worker only when enabled and keep it stopped otherwise.'
Assert-Match $selfHeal '\.self-heal-env-file[\s\S]{0,500}env_file_name' 'Production self-heal must honor the deploy-selected remote env filename.'
Assert-Match $deploy 'printf ''%s\\n'' "`\$env_file"[\s\S]{0,250}\.self-heal-env-file' 'Deploy must atomically persist RemoteEnvFile for the installed self-heal helper.'
Assert-Match $deploy 'Protected self-heal state is missing; leaving deploy lock for manual recovery' 'Local pre-rollout cleanup must fail closed when protected self-heal state is missing.'
Assert-Match $deploy 'Protected deploy lock ownership changed; refusing to remove it' 'Local pre-rollout cleanup must never remove an unowned lock.'

foreach ($composeRunContract in @(
    @{ Name = 'production deploy'; Text = $deploy; Expected = 4 },
    @{ Name = 'quarantined legacy deploy'; Text = $legacyDeploy; Expected = 4 }
)) {
    $composeRunLines = @($composeRunContract.Text -split "`r?`n" | Where-Object {
        $_.Trim() -match '^(?:if ! )?compose run\b'
    })
    if ($composeRunLines.Count -ne $composeRunContract.Expected) {
        throw "$($composeRunContract.Name) must contain exactly $($composeRunContract.Expected) audited compose run calls; found $($composeRunLines.Count)."
    }
    foreach ($composeRunLine in $composeRunLines) {
        if (-not $composeRunLine.Contains('--interactive=false') -or
            -not $composeRunLine.Contains(' -T ') -or
            -not $composeRunLine.Contains('</dev/null')) {
            throw "$($composeRunContract.Name) compose run must disable interactive stdin and TTY and redirect stdin from /dev/null: $($composeRunLine.Trim())"
        }
    }
}

Assert-Match $maxWebhook 'POST|request = \\"POST\\"' 'MAX webhook release verification must perform POST /subscriptions.'
Assert-Match $maxWebhook 'success[\s\S]{0,100}true' 'MAX webhook release verification must require an explicit success=true response.'
Assert-Match $maxWebhook 'without exposing token or secret' 'MAX webhook release verification must avoid printing credentials.'
Assert-Order $deploy 'wait_service_healthy app 1200' 'register-max-webhook.sh' 'MAX webhook registration must happen after the new backend is healthy.'
Assert-Order $deploy 'register-max-webhook.sh "`$env_file"' 'publish_bundled_mobile_release' 'APK publication must happen only after MAX webhook registration succeeds.'
Assert-Match $deploy 'Invoke-ExternalWithRetry -FilePath "docker" -Arguments @\("push", \$appImage\) -Attempts 3 -DelaySeconds 10' 'Production deploy must retry transient application image push failures.'
Assert-Match $deploy 'Invoke-ExternalWithRetry -FilePath "docker" -Arguments @\("push", \$webImage\) -Attempts 3 -DelaySeconds 10' 'Production deploy must retry transient web image push failures.'
Assert-Match $deploy 'Invoke-ExternalWithRetry -FilePath "docker" -Arguments @\("push", \$externalReviewWorkerImage\) -Attempts 3 -DelaySeconds 10' 'Production deploy must retry transient external review worker image push failures.'
$mobileVerificationCallCount = [regex]::Matches(
    $deploy,
    '(?m)^\s*\$mobileRelease = Confirm-MobileReleaseArtifact -RepoRoot \$repoRoot -Candidate \$mobileRelease\s*$'
).Count
if ($mobileVerificationCallCount -ne 1) {
    throw "Production deploy must verify the selected mobile artifact exactly once; found $mobileVerificationCallCount calls."
}
Assert-Order $deploy '$mobileRelease = Confirm-MobileReleaseArtifact -RepoRoot $repoRoot -Candidate $mobileRelease' 'Checking VPS SSH access before build/push...' 'The selected mobile APK must be verified before any VPS access.'
Assert-Order $deploy '$mobileRelease = Confirm-MobileReleaseArtifact -RepoRoot $repoRoot -Candidate $mobileRelease' 'Invoke-External -FilePath "docker" -Arguments $buildArgs' 'The selected mobile APK must be verified before Docker build/push.'
Assert-Order $deploy '$remoteMobileOutput = @($remoteMobileCheck | & ssh' '$remoteMobileExitCode = $LASTEXITCODE' 'The mobile release precheck must capture SSH output before immediately preserving the native exit code.'
Assert-Order $deploy '$remoteMobileExitCode = $LASTEXITCODE' '$remoteMobileState = (($remoteMobileOutput' 'The mobile release precheck must validate the SSH exit code before parsing its output.'
Assert-Match $deploy '\$remoteMobileAttempts = 3[\s\S]{0,800}\$remoteMobileExitCode -ne 255[\s\S]{0,500}Start-Sleep -Seconds 10' 'The read-only mobile release precheck must retry transient SSH transport failures.'
Assert-NotMatch $deploy '\(\$remoteMobileCheck \| & ssh[^\r\n]*\)\.Trim\(\)' 'The mobile release precheck must not call Trim before checking the SSH exit code.'
Assert-Match $deploy 'Published mobile APK reuses the requested versionCode with a different SHA-256' 'The remote precheck must reject same-code mobile artifacts with a different hash.'
Assert-Match $deploy 'Refusing to reuse mobile versionCode[\s\S]{0,100}different APK SHA-256' 'The publication transaction must enforce immutable versionCode-to-APK mapping.'
Assert-Match $deploy 'current_actual_sha[\s\S]{0,300}current_metadata_sha' 'Already-published APK files must be verified against release.json before they can be skipped.'
Assert-Match $deploy 'if \[ ! -r "`\$metadata" \]; then\s+printf ''MISSING''\s+exit 0' 'An unreadable remote mobile manifest must be deferred to the locked publication step without leaking grep permission errors.'
Assert-Match $deploy 'sudo -n chown -R "`\$deploy_uid:`\$deploy_gid" "`\$target_dir"' 'APK publication must recursively grant the SSH deploy user access to existing private mobile release files.'
Assert-Match $deploy 'chown -R `\$deploy_uid:`\$deploy_gid /host-data/mobile-releases' 'The no-sudo APK publication fallback must recursively repair existing mobile release ownership.'
Assert-Match $deploy 'Mobile release metadata is not readable after permission repair' 'APK publication must fail closed if release.json remains unreadable after ownership repair.'
Assert-Match $deploy 'Mobile release storage must not be a symlink' 'Recursive mobile release ownership repair must reject a symlink target.'
$mobilePermissionNormalizationCount = [regex]::Matches($deploy, 'Unable to normalize published mobile release permissions').Count
if ($mobilePermissionNormalizationCount -ne 2) {
    throw "Verified existing and newly copied mobile releases must both normalize public file permissions; found $mobilePermissionNormalizationCount guarded paths."
}
Assert-Match $deploy 'restore_backend_mobile_storage_owner\(\)[\s\S]{0,800}10001:10001' 'APK publication must restore backend ownership of mobile release storage.'
Assert-Match $deploy 'mobile_storage_owner_needs_restore[\s\S]{0,1000}restore_backend_mobile_storage_owner' 'Failure cleanup must repair mobile release storage ownership after partial publication.'

$publishCount = [regex]::Matches($deploy, '(?m)^publish_bundled_mobile_release\s*$').Count
if ($publishCount -ne 1) {
    throw "APK publication must have exactly one call site; found $publishCount."
}
Assert-Order $deploy 'wait_service_healthy app 1200' 'publish_bundled_mobile_release' 'APK publication must happen only after the final backend health check.'
Assert-Order $deploy 'wait_service_healthy external-review-worker 300 external-review' 'publish_bundled_mobile_release' 'When enabled, the worker health check must remain before APK publication.'

foreach ($parser in @{
    'deploy embedded env parser' = $deploy
    'database-backup env parser' = $backup
    'MAX webhook env parser' = $maxWebhook
}.GetEnumerator()) {
    if (-not $parser.Value.Contains('s/\r$//')) {
        throw "$($parser.Key) must strip CR from CRLF production env files."
    }
}

& git --no-replace-objects -C $repoRoot rev-parse --verify HEAD *> $null
if ($LASTEXITCODE -ne 0) {
    throw 'Unable to restore a successful native-command state after deploy release regressions.'
}
Write-Output 'Deploy release contract passed: durable lock, encrypted DB backup, optional worker/MAX rollout, and post-health APK publication are ordered safely.'
