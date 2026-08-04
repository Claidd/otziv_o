[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
$deployPath = Join-Path $repoRoot 'infrastructure\scripts\prod\deploy-prod.ps1'
$backupPath = Join-Path $repoRoot 'infrastructure\scripts\prod\create-pre-deploy-db-backup.sh'
$maxWebhookPath = Join-Path $repoRoot 'infrastructure\scripts\prod\register-max-webhook.sh'
$selfHealPath = Join-Path $repoRoot 'infrastructure\scripts\prod\otziv-prod-up.sh'
$buildComposePath = Join-Path $repoRoot 'docker-compose.build.yaml'
$productionComposePath = Join-Path $repoRoot 'docker-compose.yaml'

$deploy = [IO.File]::ReadAllText($deployPath)
$backup = [IO.File]::ReadAllText($backupPath)
$maxWebhook = [IO.File]::ReadAllText($maxWebhookPath)
$selfHeal = [IO.File]::ReadAllText($selfHealPath)
$buildCompose = [IO.File]::ReadAllText($buildComposePath)
$productionCompose = [IO.File]::ReadAllText($productionComposePath)

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

$workerBuildBlocks = [regex]::Matches($buildCompose, '(?m)^  external-review-worker:\s*$')
if ($workerBuildBlocks.Count -ne 1) {
    throw "docker-compose.build.yaml must define external-review-worker exactly once; found $($workerBuildBlocks.Count)."
}
Assert-Match $buildCompose 'EXTERNAL_REVIEW_WORKER_IMAGE[\s\S]{0,250}backend/external-review-worker' 'Build compose must publish the external review worker from its own Dockerfile.'
Assert-Match $productionCompose 'APP_MEMORY_LIMIT:-2304m' 'Production Compose must default backend memory to the audited 2304 MiB floor.'
Assert-Match $deploy 'APP_MEMORY_LIMIT[\s\S]{0,500}2304' 'Production deploy must reject an omitted or undersized backend memory limit.'
Assert-Match $deploy '\[switch\]\$EnableExternalReviewWorker' 'External review worker deployment must be an explicit opt-in.'
Assert-Match $deploy '\$buildArgs \+= @\("app", "nginx"\)[\s\S]{0,200}if \(\$EnableExternalReviewWorker\)[\s\S]{0,100}\$buildArgs \+= "external-review-worker"' 'Default builds must exclude the worker and append it only for an explicit opt-in.'
Assert-Match $deploy 'if \(\$EnableExternalReviewWorker\)[\s\S]{0,200}docker.+push.+\$externalReviewWorkerImage' 'Production deploy must push the worker image only in the opt-in branch.'
Assert-Match $deploy 'set_env EXTERNAL_REVIEW_WORKER_IMAGE.+external_review_worker_image' 'Production deploy must persist the worker image tag in the active VPS env.'
Assert-Match $deploy 'if \[ "`\$deploy_external_review_worker" = "1" \]; then[\s\S]{0,300}recreate_service_with_retry external-review-worker external-review[\s\S]{0,200}wait_service_healthy external-review-worker[\s\S]{0,100}fi' 'Production deploy must start and health-check the worker only when opted in.'
Assert-Match $deploy 'if \[ "`\$deploy_external_review_worker" != "1" \]; then[\s\S]{0,500}stop external-review-worker' 'Production deploy must stop a stale worker when the replacement backend has external checks disabled.'
Assert-Order $deploy 'wait_service_healthy app 1200' 'compose --profile external-review stop external-review-worker' 'A disabled rollout must keep the previous worker until the replacement backend is healthy.'
Assert-Match $deploy 'set_env EXTERNAL_REVIEW_CHECK_ENABLED "true"[\s\S]{0,100}set_env EXTERNAL_REVIEW_CHECK_ENABLED "false"' 'Production deploy must persist the backend hard switch consistently with the worker opt-in.'

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
Assert-Match $deploy 'must be a dedicated release subdirectory, not a filesystem root, user profile, or shared backup parent' 'Backup ACL hardening must reject dangerously broad local target directories.'
Assert-Match $deploy 'existing custom PreDeployBackupDirectory is not accepted[\s\S]{0,500}Assert-NoReparsePointInExistingPath' 'Backup ACL hardening must require a dedicated custom leaf and reject reparse-point ancestors.'
Assert-Match $deploy 'mkdir \$remoteUploadDirectoryQuoted[\s\S]{0,150}chmod 700 \$remoteUploadDirectoryQuoted[\s\S]{0,500}Copy-DeployBundle' 'The secret-bearing deploy bundle must be uploaded only inside a pre-created 0700 directory.'
Assert-Order $deploy 'Creating and verifying mandatory pre-deploy database backup on VPS' 'bash infrastructure/scripts/prod/validate-flyway-migrations.sh' 'The mandatory DB backup must finish before Flyway validation and app startup.'
Assert-Match $deploy 'deploy_lock_token[\s\S]{0,5000}mkdir "`\$deploy_lock_dir"' 'The rollout must acquire a durable cross-session lock before creating the backup.'
Assert-Match $deploy 'release_deploy_lock' 'The rollout must explicitly release its durable deployment lock.'
Assert-Match $deploy 'pause_self_heal\s+tar -xzf[\s\S]{0,700}create-pre-deploy-db-backup\.sh" create' 'Production self-heal must be stopped before the mandatory database backup begins.'
Assert-Match $deploy 'trap cleanup_preflight EXIT INT TERM[\s\S]{0,200}preflight_dir="`\$\(mktemp' 'Pre-backup lock cleanup must be armed before temporary-directory creation can fail.'
Assert-Match $deploy 'backup_dir="\.deploy-backups/`\$deploy_tag/rollout-`\$deploy_lock_token"' 'Each repeated deploy tag must preserve compose/env rollback files in a unique attempt directory.'
Assert-NotMatch $deploy 'deploy_cleanup\(\)[\s\S]{0,700}(?:resume_self_heal|systemctl start "`\$self_heal)' 'Failure cleanup must not restart self-heal against a partially deployed compose/env.'
Assert-Match $deploy 'deploy_cleanup\(\)[\s\S]{0,1000}systemctl disable "`\$self_heal_timer"' 'Failure cleanup must disable self-heal so a reboot cannot continue a failed rollout.'
Assert-Match $deploy 'if \[ "`\$status" -eq 0 \]; then[\s\S]{0,100}release_deploy_lock[\s\S]{0,500}deploy lock remains at' 'A failed rollout must retain its durable lock and release it only on success.'
Assert-Match $deploy 'release_payload_complete="1"[\s\S]{0,100}resume_self_heal_timer[\s\S]{0,100}release_deploy_lock' 'Final handoff interruptions must be distinguished from failures before the release payload and health checks complete.'
Assert-Match $deploy 'self-heal-timer-was-enabled' 'Deploy must persist the timer enablement state as well as its active state.'
Assert-Match $deploy 'resume_self_heal_timer\(\)[\s\S]{0,300}systemctl enable "`\$self_heal_timer"' 'A successful deploy must restore the original self-heal enablement state.'
Assert-Match $deploy 'timer_was_active="`\$\(cat[\s\S]{0,100}timer_was_enabled="`\$\(cat[\s\S]{0,500}case "`\$timer_was_enabled"[\s\S]{0,500}systemctl enable otziv-prod-up.timer[\s\S]{0,300}systemctl start otziv-prod-up.timer' 'Pre-rollout cleanup must validate both protected states before enabling and starting the old timer.'
Assert-Match $deploy 'install -o root -g root -m 0755[\s\S]{0,150}otziv-prod-up\.sh' 'Deploy must install the version-controlled production self-heal helper.'
Assert-Match $selfHeal '\[\[ -e "\$deploy_lock" \|\| -L "\$deploy_lock" \]\]' 'Production self-heal must respect the durable deploy lock, including symlinks.'
Assert-Match $selfHeal 'EXTERNAL_REVIEW_CHECK_ENABLED[\s\S]{0,1000}if \[\[ "\$external_review_enabled" == "true" \]\][\s\S]{0,200}--profile external-review up -d[\s\S]{0,300}stop external-review-worker[\s\S]{0,200}up -d' 'Production self-heal must start the worker only when enabled and keep it stopped otherwise.'
Assert-Match $selfHeal '\.self-heal-env-file[\s\S]{0,500}env_file_name' 'Production self-heal must honor the deploy-selected remote env filename.'
Assert-Match $deploy 'printf ''%s\\n'' "`\$env_file"[\s\S]{0,250}\.self-heal-env-file' 'Deploy must atomically persist RemoteEnvFile for the installed self-heal helper.'
Assert-Match $deploy 'Protected self-heal state is missing; leaving deploy lock for manual recovery' 'Local pre-rollout cleanup must fail closed when protected self-heal state is missing.'
Assert-Match $deploy 'Protected deploy lock ownership changed; refusing to remove it' 'Local pre-rollout cleanup must never remove an unowned lock.'

Assert-Match $maxWebhook 'POST|request = \\"POST\\"' 'MAX webhook release verification must perform POST /subscriptions.'
Assert-Match $maxWebhook 'success[\s\S]{0,100}true' 'MAX webhook release verification must require an explicit success=true response.'
Assert-Match $maxWebhook 'without exposing token or secret' 'MAX webhook release verification must avoid printing credentials.'
Assert-Order $deploy 'wait_service_healthy app 1200' 'register-max-webhook.sh' 'MAX webhook registration must happen after the new backend is healthy.'
Assert-Order $deploy 'register-max-webhook.sh "`$env_file"' 'publish_bundled_mobile_release' 'APK publication must happen only after MAX webhook registration succeeds.'
Assert-Match $deploy 'Published mobile APK reuses the requested versionCode with a different SHA-256' 'The remote precheck must reject same-code mobile artifacts with a different hash.'
Assert-Match $deploy 'Refusing to reuse mobile versionCode[\s\S]{0,100}different APK SHA-256' 'The publication transaction must enforce immutable versionCode-to-APK mapping.'
Assert-Match $deploy 'current_actual_sha[\s\S]{0,300}current_metadata_sha' 'Already-published APK files must be verified against release.json before they can be skipped.'
Assert-Match $deploy 'restore_backend_mobile_storage_owner\(\)[\s\S]{0,800}10001:10001' 'APK publication must restore backend ownership of mobile release storage.'
Assert-Match $deploy 'mobile_storage_owner_needs_restore[\s\S]{0,1000}restore_backend_mobile_storage_owner' 'Failure cleanup must repair mobile release storage ownership after partial publication.'

$publishCount = [regex]::Matches($deploy, '(?m)^publish_bundled_mobile_release\s*$').Count
if ($publishCount -ne 1) {
    throw "APK publication must have exactly one call site; found $publishCount."
}
Assert-Order $deploy 'wait_service_healthy app 1200' 'publish_bundled_mobile_release' 'APK publication must happen only after the final backend health check.'
Assert-Order $deploy 'wait_service_healthy external-review-worker 300 external-review' 'publish_bundled_mobile_release' 'When enabled, the worker health check must remain before APK publication.'
Assert-Order $deploy 'publish_bundled_mobile_release' 'resume_self_heal_timer' 'Self-heal must resume only after APK publication succeeds.'

foreach ($parser in @{
    'deploy embedded env parser' = $deploy
    'database-backup env parser' = $backup
    'MAX webhook env parser' = $maxWebhook
}.GetEnumerator()) {
    if (-not $parser.Value.Contains('s/\r$//')) {
        throw "$($parser.Key) must strip CR from CRLF production env files."
    }
}

Write-Output 'Deploy release contract passed: durable lock, encrypted DB backup, optional worker/MAX rollout, and post-health APK publication are ordered safely.'
