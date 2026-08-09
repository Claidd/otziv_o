[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repoRoot = (& git rev-parse --show-toplevel).Trim()
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($repoRoot)) {
    throw 'Run this script from inside the repository.'
}

$violations = [System.Collections.Generic.List[string]]::new()

function Get-RepositoryText {
    param([Parameter(Mandatory = $true)][string]$RelativePath)

    $path = Join-Path $repoRoot $RelativePath
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        $script:violations.Add("Missing required infrastructure file: $RelativePath")
        return ''
    }
    return [System.IO.File]::ReadAllText($path)
}

function Assert-TextMatch {
    param(
        [Parameter(Mandatory = $true)][string]$Text,
        [Parameter(Mandatory = $true)][string]$Pattern,
        [Parameter(Mandatory = $true)][string]$Message
    )

    if ($Text -notmatch $Pattern) {
        $script:violations.Add($Message)
    }
}

function Assert-TextNotMatch {
    param(
        [Parameter(Mandatory = $true)][string]$Text,
        [Parameter(Mandatory = $true)][string]$Pattern,
        [Parameter(Mandatory = $true)][string]$Message
    )

    if ($Text -match $Pattern) {
        $script:violations.Add($Message)
    }
}

function Assert-ComposeEnvironmentVariable {
    param(
        [Parameter(Mandatory = $true)][string]$Text,
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$DefaultSuffix,
        [Parameter(Mandatory = $true)][string]$Message
    )

    $expectedValue = '${' + $Name + $DefaultSuffix + '}'
    $pattern = '(?m)^\s+' + [Regex]::Escape($Name) + ':\s*' + [Regex]::Escape($expectedValue) + '\s*$'
    Assert-TextMatch -Text $Text -Pattern $pattern -Message $Message
}

function Assert-DigestPinnedDockerfileBases {
    param(
        [Parameter(Mandatory = $true)][string]$Text,
        [Parameter(Mandatory = $true)][string]$RelativePath
    )

    foreach ($line in ($Text -split "`r?`n")) {
        if ($line -notmatch '^\s*FROM\s+(?:--platform=\S+\s+)?(?<image>\S+)') {
            continue
        }
        $image = $Matches['image']
        if ($image -notmatch '@sha256:[0-9a-f]{64}$') {
            $script:violations.Add("Active Dockerfile base image is not pinned by digest in ${RelativePath}: $image")
        }
    }
}

function Assert-DigestPinnedComposeImages {
    param(
        [Parameter(Mandatory = $true)][string]$Text,
        [Parameter(Mandatory = $true)][string]$RelativePath
    )

    foreach ($line in ($Text -split "`r?`n")) {
        if ($line -notmatch '^\s*image:\s*(?<image>.+?)\s*$') {
            continue
        }
        $image = $Matches['image'].Split('#', 2)[0].Trim().Trim('''', '"')
        if ([string]::IsNullOrWhiteSpace($image) -or $image.Contains('${')) {
            continue
        }
        if ($image -notmatch '@sha256:[0-9a-f]{64}$') {
            $script:violations.Add("Literal Compose image is not pinned by digest in ${RelativePath}: $image")
        }
    }
}

$restore = Get-RepositoryText 'infrastructure/scripts/local/restore-prod-db-local.ps1'
$backupRestoreDrill = Get-RepositoryText 'infrastructure/scripts/local/restore-backup-drill.ps1'
$smoke = Get-RepositoryText 'infrastructure/scripts/local/prod-like-smoke.ps1'
$deploy = Get-RepositoryText 'infrastructure/scripts/prod/deploy-prod.ps1'
$productionCompose = Get-RepositoryText 'docker-compose.yaml'
$localCompose = Get-RepositoryText 'compose.prod-local.yaml'
$developmentCompose = Get-RepositoryText 'compose.yaml'
$nginx = Get-RepositoryText 'infrastructure/nginx/prod.conf'
$secretWorkflow = Get-RepositoryText '.github/workflows/secret-scan.yml'
$maxWebhookRegistration = Get-RepositoryText 'infrastructure/scripts/prod/register-max-webhook.ps1'
$localSecretScan = Get-RepositoryText 'infrastructure/scripts/security/run-secret-scan.ps1'
$backupReadiness = Get-RepositoryText 'infrastructure/scripts/security/check-backup-readiness.ps1'
$backupConfigImporter = Get-RepositoryText 'infrastructure/scripts/security/import-prod-backup-config.ps1'
$gitleaksConfig = Get-RepositoryText '.gitleaks.toml'
$qualityWorkflow = Get-RepositoryText '.github/workflows/quality-gates.yml'
$dependencyWorkflow = Get-RepositoryText '.github/workflows/dependency-audit.yml'
$sqlGuardWorkflow = Get-RepositoryText '.github/workflows/sql-injection-guard.yml'
$legacyWhatsAppDockerfile = Get-RepositoryText 'Dockerfile2.whatsapp'
$legacyNginxDockerfile = Get-RepositoryText 'Dockerfile.nginx'
$legacyDeploy = Get-RepositoryText 'infrastructure/scripts/prod/deploy-prod-ssh-images.ps1'
$backendDockerfile = Get-RepositoryText 'backend/Dockerfile'
$frontendDockerfile = Get-RepositoryText 'frontend/Dockerfile'
$frontendAngularConfig = Get-RepositoryText 'frontend/angular.json'
$frontendPackageConfig = Get-RepositoryText 'frontend/package.json'
$frontendSilentCheckSsoHtml = Get-RepositoryText 'frontend/public/silent-check-sso.html'
$frontendSilentCheckSsoScript = Get-RepositoryText 'frontend/public/silent-check-sso.js'
$whatsAppDockerfile = Get-RepositoryText 'Dockerfile.whatsapp'
$externalReviewWorkerDockerfile = Get-RepositoryText 'backend/external-review-worker/Dockerfile'
$whatsAppGateway = Get-RepositoryText 'whatsapp/index.js'
$productionProperties = Get-RepositoryText 'backend/src/main/resources/application-prod.properties'
$baseApplicationYaml = Get-RepositoryText 'backend/src/main/resources/application.yaml'
$baseProperties = Get-RepositoryText 'backend/src/main/resources/application.properties'
$notificationMediaImporter = Get-RepositoryText 'generated-assets/notification-media-v2/import_to_production.py'
$databaseBackupService = Get-RepositoryText 'backend/src/main/java/com/hunt/otziv/s3/buckupBD/service/DatabaseBackupService.java'
$backupScheduler = Get-RepositoryText 'backend/src/main/java/com/hunt/otziv/s3/buckupBD/service/BackupScheduler.java'
$mailConfig = Get-RepositoryText 'backend/src/main/java/com/hunt/otziv/config/email/config/MailConfig.java'
$backupS3Config = Get-RepositoryText 'backend/src/main/java/com/hunt/otziv/s3/buckupBD/config/BackupS3Config.java'
$primaryS3Config = Get-RepositoryText 'backend/src/main/java/com/hunt/otziv/s3/config/S3Config.java'
$baseEnvExample = Get-RepositoryText '.env.example'
$productionEnvExample = Get-RepositoryText '.env.prod.example'
$localEnvExample = Get-RepositoryText '.env.prod-local.example'
$frontendRoutes = Get-RepositoryText 'frontend/src/app/app.routes.ts'
$frontendNavigation = Get-RepositoryText 'frontend/src/app/shared/app-navigation.ts'
$frontendAdminLayout = Get-RepositoryText 'frontend/src/app/shared/admin-layout.component.ts'
$frontendUsersAdmin = Get-RepositoryText 'frontend/src/app/features/admin/users/users-admin.component.html'
$mobileRoutes = Get-RepositoryText 'mobile/src/app/app.routes.ts'
$keycloakLoginTheme = Get-RepositoryText 'infrastructure/keycloak/themes/otziv/login/login.ftl'

# No-argument smoke intentionally refreshes from production. This is a guarded
# operational contract, not an invitation to silently change the behavior.
Assert-TextMatch $smoke 'if\s*\(-not\s+\$SkipProdDbRestore\)' 'No-argument prod-like smoke must continue refreshing the production database.'
Assert-TextMatch $smoke 'OtzivProdLikeDatabaseOperation' 'Prod-like smoke must hold the shared database-operation lock.'
Assert-TextMatch $restore 'OtzivProdLikeDatabaseOperation' 'Production DB restore must hold the shared database-operation lock.'
Assert-TextMatch $restore 'mktemp /tmp/otziv-prod\.' 'Remote DB dumps must use mktemp rather than predictable names.'
Assert-TextMatch $restore 'umask 077' 'Remote DB dump creation must set umask 077.'
Assert-TextMatch $restore 'OTZIV_REMOTE_SHA256' 'Remote DB dump SHA-256 metadata must be verified locally.'
Assert-TextMatch $restore 'Test-GzipArchive -Path \$dumpFullPath' 'A gzip integrity check must run before the local MySQL volume is replaced.'
Assert-TextMatch $restore '"bash",\s*"-o",\s*"pipefail"' 'MySQL import must fail when gzip decompression fails.'
Assert-TextMatch $restore '\$remoteCommand\s*=\s*\$remoteCommand\.Replace\("`r`n",\s*"`n"\)\.Replace\("`r",\s*"`n"\)' 'Remote production dump commands must normalize Windows CRLF before crossing the SSH boundary.'
Assert-TextMatch $restore 'prod-like-smoke\.ps1[\s\S]{0,300}-SkipProdDbRestore' '-RunSmoke must not perform a second production DB restore.'
Assert-TextMatch $restore '\$pruneLocalDumps\s*=\s*\$PruneExpiredLocalDumps\s+-or\s+\(-not \$SkipDownload\s+-and\s+-not \$KeepExpiredLocalDumps\)' 'Fresh production dump downloads must enforce bounded local retention by default.'
Assert-TextMatch $restore 'Format-RedactedCommand' 'Restore command errors must redact credentials.'
Assert-TextMatch $databaseBackupService '"OTZIVDB2"\.getBytes' 'Database backups must use the stream-restorable OTZIVDB2 envelope.'
Assert-TextMatch $databaseBackupService 'cipher\.updateAAD\(aad\)' 'Every encrypted backup chunk must authenticate its envelope metadata.'
Assert-TextMatch $backupS3Config '@Bean\(name\s*=\s*"backupS3Client"' 'Database backups must use a dedicated S3 client.'
Assert-TextMatch $primaryS3Config '@Primary' 'The application S3 client must remain primary when the independent backup client is enabled.'
Assert-TextMatch $backupS3Config 'backup\.s3 must not use the primary S3 bucket' 'Backup S3 configuration must reject the primary object destination.'
Assert-TextMatch $backupS3Config 'credentials distinct from primary S3' 'Backup S3 configuration must reject primary S3 credentials.'
Assert-TextMatch $databaseBackupService '@Qualifier\("backupS3Client"\)' 'Database backup upload must be wired only to the dedicated backup S3 client.'
Assert-TextMatch $databaseBackupService 'headObject\(buildHeadObjectRequest' 'Every uploaded database backup must receive a mandatory HEAD verification.'
Assert-TextMatch $databaseBackupService 'getObject\([\s\S]{0,800}ResponseTransformer\.toFile' 'Every uploaded database backup must be downloaded for independent verification.'
Assert-TextMatch $databaseBackupService 'getObjectRetention\([\s\S]{0,300}buildGetObjectRetentionRequest' 'Object Lock must be verified through the provider retention API.'
Assert-TextMatch $databaseBackupService 'PutObjectResponse[\s\S]{0,300}versionId\(\)' 'Versioned backup verification must use the exact version returned by upload.'
Assert-TextMatch $databaseBackupService 'builder\.versionId\(versionId\)' 'HEAD, retention, and download request builders must bind to the uploaded object version.'
Assert-TextMatch $databaseBackupService 'Downloaded backup checksum or size does not match' 'Downloaded backups must fail closed on checksum or length mismatch.'
Assert-TextMatch $databaseBackupService 'if \(backupS3Props\.isRequireServerSideEncryption\(\)\)\s*\{\s*putBuilder\.serverSideEncryption\(ServerSideEncryption\.AES256\)' 'SSE-S3 must be requested only when the provider compatibility switch requires it.'
Assert-TextMatch $databaseBackupService 'clientSideEnvelopeVerified' 'Backup evidence must explicitly attest verification of the client-side encrypted envelope.'
Assert-TextMatch $databaseBackupService 'serverSideEncryptionRequired' 'Backup evidence must record whether SSE-S3 proof was required.'
Assert-TextMatch $databaseBackupService 'NONE_REPORTED' 'Backup evidence must truthfully distinguish an unreported provider SSE status.'
Assert-TextMatch $databaseBackupService 'verifyEncryptedEnvelope\(downloaded, configuration\.encryptionKey\(\)\)' 'The downloaded backup must authenticate its OTZIVDB2 AES-GCM envelope.'
Assert-TextMatch $databaseBackupService 'cleanupStaleTemporaryFiles' 'A leased backup run must remove strictly-owned stale temporary artifacts before starting.'
Assert-TextMatch $databaseBackupService 'acquireLocalBackupRunLock' 'Stale cleanup and backup execution must share an exclusive work-directory lock.'
Assert-TextMatch $databaseBackupService 'backup-evidence\.jsonl|otziv-backup-evidence-v1' 'Verified backup runs must emit machine-readable evidence.'
Assert-TextMatch $databaseBackupService 'uploadAndVerify\([\s\S]{0,500}writeRemoteVerificationEvidence\([\s\S]{0,500}sendEncryptedPartsByEmail' 'A durable remote-verified receipt must be appended before optional email can fail.'
Assert-TextMatch $databaseBackupService 'EVIDENCE_PHASE_REMOTE_VERIFIED\s*=\s*"remote-verified"' 'Backup evidence must distinguish a verified remote object from later operational completion.'
Assert-TextMatch $databaseBackupService 'EVIDENCE_PHASE_COMPLETED\s*=\s*"completed"' 'Backup evidence must append an explicit completed phase after email and cleanup.'
Assert-TextMatch $databaseBackupService 'emailDelivery' 'Backup evidence must record optional encrypted email delivery status.'
Assert-TextMatch $databaseBackupService 'objectLockRetainUntilDate' 'Configured backup retention must use provider-enforced Object Lock, not descriptive metadata alone.'
Assert-TextMatch $databaseBackupService 'verifyObjectRetention\(retention, objectLockMode, retentionUntil\)' 'Object Lock evidence must fail closed unless the exact retention response is verified.'
Assert-TextMatch $backupScheduler 'requireSingleNumericCronField\(fields\[0\], "seconds", 0, 59\)' 'Daily backup cron must reject a repeating seconds field.'
Assert-TextMatch $backupScheduler 'requireSingleNumericCronField\(fields\[1\], "minutes", 0, 59\)' 'Daily backup cron must reject a repeating minutes field.'
Assert-TextMatch $backupScheduler 'requireSingleNumericCronField\(fields\[2\], "hours", 0, 23\)' 'Daily backup cron must reject a repeating hours field.'
Assert-TextMatch $backupScheduler 'requireDailyWildcardCronField\(fields\[3\], "day-of-month"\)' 'Daily backups must run every day of the month.'
Assert-TextMatch $backupScheduler 'requireDailyWildcardCronField\(fields\[4\], "month"\)' 'Daily backups must run every month.'
Assert-TextMatch $backupScheduler 'requireDailyWildcardCronField\(fields\[5\], "day-of-week"\)' 'Daily backups must run every day of the week.'
Assert-TextMatch $backupScheduler 'catch-up-enabled must be true for recurring production backups' 'Recurring backups must retain bounded catch-up after downtime.'
Assert-TextMatch $backupScheduler 'catch-up-window must be greater than PT24H' 'Catch-up must cover the previous daily occurrence.'
Assert-TextMatch $productionProperties 'backup\.part-size-mb=\$\{BACKUP_PART_SIZE_MB:16\}' 'Production encrypted email parts must default to 16 MiB before MIME expansion.'
Assert-ComposeEnvironmentVariable $productionCompose 'BACKUP_PART_SIZE_MB' ':-16' 'Production Compose must default encrypted email parts to 16 MiB.'
Assert-ComposeEnvironmentVariable $localCompose 'BACKUP_PART_SIZE_MB' ':-16' 'Prod-like Compose must default encrypted email parts to 16 MiB.'
Assert-ComposeEnvironmentVariable $productionCompose 'BACKUP_SCHEDULE_CATCH_UP_ENABLED' ':-true' 'Production Compose must enable bounded catch-up by default.'
Assert-ComposeEnvironmentVariable $productionCompose 'BACKUP_SCHEDULE_CATCH_UP_WINDOW' ':-PT26H' 'Production Compose catch-up must cover the previous daily occurrence.'
Assert-ComposeEnvironmentVariable $localCompose 'BACKUP_SCHEDULE_CATCH_UP_ENABLED' ':-true' 'Prod-like Compose must match production catch-up defaults.'
Assert-ComposeEnvironmentVariable $localCompose 'BACKUP_SCHEDULE_CATCH_UP_WINDOW' ':-PT26H' 'Prod-like Compose must match the bounded production catch-up window.'
Assert-TextMatch $mailConfig 'mail\.smtp\.connectiontimeout' 'SMTP must use a finite connection timeout so backup locks cannot hang indefinitely.'
Assert-TextMatch $mailConfig 'mail\.smtp\.timeout' 'SMTP must use a finite read timeout so backup locks cannot hang indefinitely.'
Assert-TextMatch $mailConfig 'mail\.smtp\.writetimeout' 'SMTP must use a finite write timeout so backup locks cannot hang indefinitely.'
Assert-TextMatch $mailConfig 'mail\.smtp\.ssl\.checkserveridentity' 'SMTP TLS must verify the server identity.'
Assert-TextMatch $productionProperties 'spring\.mail\.properties\.mail\.smtp\.starttls\.required=\$\{MAIL_STARTTLS_REQUIRED:true\}' 'Production SMTP must require STARTTLS by default.'
Assert-TextMatch $productionProperties 'MAIL_SMTP_CONNECTION_TIMEOUT_MS:10000' 'Production SMTP connection timeout must have a finite safe default.'
Assert-TextMatch $productionProperties 'MAIL_SMTP_READ_TIMEOUT_MS:60000' 'Production SMTP read timeout must have a finite safe default.'
Assert-TextMatch $productionProperties 'MAIL_SMTP_WRITE_TIMEOUT_MS:60000' 'Production SMTP write timeout must have a finite safe default.'
Assert-ComposeEnvironmentVariable $productionCompose 'MAIL_STARTTLS_REQUIRED' ':-true' 'Production Compose must not downgrade required SMTP STARTTLS.'
Assert-ComposeEnvironmentVariable $productionCompose 'MAIL_SMTP_SSL_CHECK_SERVER_IDENTITY' ':-true' 'Production Compose must verify the SMTP server identity.'
Assert-TextNotMatch $productionCompose '(?m)^\s+BACKUP_RUN_ONCE_(?:ENABLED|REQUEST_ID):' 'Production Compose must not map run-once flags into its restartable long-running service.'
Assert-TextNotMatch $localCompose '(?m)^\s+BACKUP_RUN_ONCE_(?:ENABLED|REQUEST_ID):' 'Prod-like Compose must not map run-once flags into its restartable long-running service.'
Assert-TextMatch $backupRestoreDrill 'ConvertFrom-OtzivDb2Envelope' 'The isolated restore drill must support encrypted OTZIVDB2 backups.'
Assert-TextMatch $backupRestoreDrill '(?m)^#requires -Version 7\.0\r?$' 'The encrypted restore drill must require a PowerShell Core version with AES-GCM support.'
Assert-TextMatch $backupRestoreDrill '\[decimal\]::Ceiling\(\$quotient\)' 'OTZIVDB2 restore must calculate chunk counts without PowerShell rounding errors.'
Assert-TextMatch $backupRestoreDrill 'OTZIVDB1 is a legacy single-message envelope' 'The restore drill must explicitly reject the non-stream-restorable OTZIVDB1 format.'
Assert-TextMatch $backupRestoreDrill 'Remove-SensitiveTemporaryFile -Path \$decryptedTemporaryPath' 'The restore drill must remove decrypted temporary backup material in final cleanup.'
Assert-TextMatch $backupRestoreDrill '\[long\]\$MaxUncompressedBytes\s*=\s*1099511627776' 'The restore drill must enforce a finite default limit on the uncompressed SQL stream.'
Assert-TextMatch $backupRestoreDrill '-MaximumUncompressedBytes \$MaxUncompressedBytes' 'The configured uncompressed SQL limit must be passed to gzip validation.'
Assert-TextMatch $backupRestoreDrill '\$MaximumUncompressedBytes - \[long\]\$read' 'Gzip validation must fail before its uncompressed byte counter can overflow.'
Assert-TextMatch $backupRestoreDrill '\[Array\]::Clear\(\$buffer, 0, \$buffer\.Length\)' 'Gzip validation must clear its SQL plaintext buffer.'
Assert-TextMatch $smoke 'Format-RedactedCommand' 'Smoke command errors must redact credentials.'
Assert-TextMatch $smoke 'Initialize-LocalCredentialEncryptionKey' 'Prod-like smoke must initialize and validate its protected local credential-encryption key.'
Assert-TextMatch $smoke 'OTZIV_CREDENTIAL_ENCRYPTION_ACTIVE_KEY_BASE64[\s\S]{0,1800}Set-LocalEnvFileValues[\s\S]{0,1800}exactly 32 bytes' 'Prod-like smoke must persist a local-only AES-256 key externally and validate its decoded length.'
Assert-TextMatch $smoke 'function\s+Invoke-ContractorPaymentShadowSmoke' 'Prod-like smoke must validate the contractor payment schema and rollout safety gates on a current VPS snapshot.'
Assert-TextMatch $smoke 'MIGRATIONS=14[\s\S]{0,10000}ROLLOUT_LEGACY=1[\s\S]{0,10000}LIVE_ALLOCATIONS=0[\s\S]{0,10000}GENERATION_COLLATIONS=5[\s\S]{0,10000}live_master=false[\s\S]{0,500}reward_master=false' 'Contractor payment smoke must prove V217-V230, one-way rollout in LEGACY, collation-safe generation joins, and closed database/deployment LIVE gates.'
Assert-TextMatch $smoke 'COMPLETION_BASE_GAP_QUERY=1[\s\S]{0,1000}COMPLETION_DONE_TASK_GAP_QUERY=1[\s\S]{0,1000}COMPLETION_CANCEL_TASK_GAP_QUERY=1' 'Contractor payment smoke must execute the base, DONE-task, and cancellation readiness queries on the restored MySQL snapshot.'
Assert-TextMatch $smoke 'Illegal mix of collations[\s\S]{0,500}Contractor shadow route backfill failed[\s\S]{0,500}Не удалось восстановить начисления завершенного заказа' 'Prod-like log checks must fail on contractor collation, shadow-backfill, and completion-repair errors.'
Assert-TextMatch $smoke 'Initialize-LocalBotLinkSecrets' 'Prod-like smoke must supply and validate distinct bot link secrets.'
Assert-TextMatch $restore 'contractor_payment_profiles SET enabled = FALSE[\s\S]{0,300}recipient_name = NULL[\s\S]{0,300}payment_phone = NULL[\s\S]{0,300}payment_comment = NULL' 'Prod-like restore must scrub contractor profile recipient envelopes before using a local-only encryption key.'
Assert-TextMatch $restore 'contractor_payment_allocations SET recipient_name_snapshot = NULL[\s\S]{0,300}payment_phone_snapshot = NULL[\s\S]{0,300}bank_name_snapshot = NULL' 'Prod-like restore must scrub immutable contractor recipient snapshots without importing the production encryption key.'
Assert-TextMatch $restore 'contractor_payment_allocations SET payment_comment_snapshot = NULL' 'Prod-like restore must also scrub encrypted contractor allocation comments when that later column exists.'
Assert-TextMatch $smoke '"run", "--rm", "--no-deps", "--cap-add", "CHOWN", "--user", "0"' 'Prod-like smoke must repair legacy root-owned app volumes with one-shot CAP_CHOWN before testing the non-root runtime.'
Assert-TextMatch $smoke '(?s)@\("build"\).*?Migrating local application volume ownership.*?\$upArgs\s*=\s*@\("up", "-d", "--remove-orphans"\)' 'Prod-like smoke must build the app, repair volume ownership, and only then start the non-root runtime.'
Assert-TextMatch $smoke '\$env:OTZIV_AUTH_LEGACY_MIGRATION_ENABLED\s*=\s*''false''' 'Prod-like smoke must override stale external env files and keep retired user migration disabled.'
Assert-TextMatch $smoke 'Assert-LegacyUserMigrationDisabled' 'Prod-like smoke must prove that retired user migration returns HTTP 410.'
Assert-TextMatch $smoke 'function\s+Sync-LocalKeycloakManagedUsers' 'Prod-like smoke must provision and reconcile eligible users in the isolated local Keycloak realm.'
Assert-TextNotMatch $smoke '\[string\]\$LocalLoginUsername\s*=\s*"[^"\r\n]+"' 'A real local login username must not be hard-coded in the smoke script.'
Assert-TextNotMatch $smoke '\[string\]\$LocalLoginPassword' 'A local login password must never be accepted through a process command-line parameter.'
Assert-TextMatch $smoke 'OTZIV_LOCAL_LOGIN_USERNAME[\s\S]{0,500}OTZIV_LOCAL_LOGIN_PASSWORD' 'Persistent local login settings must come from the protected external prod-local env file.'
Assert-TextMatch $smoke 'SetEnvironmentVariable\(''OTZIV_LOCAL_LOGIN_PASSWORD'', \$null\)' 'An explicit local password rotation value must be removed before any child process can inherit it.'
Assert-TextMatch $smoke '\[switch\]\$InitializeLocalKeycloakUserSnapshot' 'The frozen local Keycloak allowlist must have an explicit one-time initialization switch.'
Assert-TextMatch $smoke 'snapshot already exists and will not be overwritten' 'The one-time local Keycloak allowlist must never be overwritten implicitly.'
Assert-TextMatch $smoke 'function\s+Read-LocalKeycloakUserSnapshot[\s\S]{0,1500}snapshot is missing[\s\S]{0,4500}checksum does not match' 'A missing, malformed, or corrupted frozen local Keycloak allowlist must fail closed.'
Assert-TextMatch $smoke 'OTZIV_LOCAL_LOGIN_ALLOWLIST_HMAC_KEY_BASE64[\s\S]{0,1800}CreateIfMissing[\s\S]{0,1800}exactly 32 bytes' 'The frozen local Keycloak allowlist must use a protected external 32-byte HMAC key.'
Assert-TextMatch $smoke 'a new key may be created only together with -InitializeLocalKeycloakUserSnapshot' 'An existing local Keycloak snapshot must fail closed when its external HMAC key is missing.'
Assert-TextMatch $smoke 'schemaVersion\s*=\s*2[\s\S]{0,500}usernameIdentityAlgorithm\s*=\s*''HMAC-SHA256-NFKC-LOWER''' 'The tracked local Keycloak allowlist must use the privacy-preserving HMAC schema.'
Assert-TextNotMatch $smoke '(?i)usernameSha256|database id and username hash' 'The local Keycloak allowlist implementation must not retain reversible username hashes or couple identities to production database IDs.'
Assert-TextMatch $smoke 'function\s+Select-FrozenLocalKeycloakDatabaseUsers[\s\S]{0,3500}UsernameHmacSha256[\s\S]{0,1500}new VPS-derived user\(s\) ignored' 'Normal local provisioning must intersect current canonical username HMACs with the frozen allowlist and ignore new VPS users.'
Assert-TextMatch $smoke 'Refusing to adopt existing unmarked local Keycloak user' 'Frozen provisioning must not silently adopt an unrelated existing realm user with the same username.'
Assert-TextMatch $smoke 'function\s+ConvertFrom-SmokeHexUtf8[\s\S]{0,1400}\[Convert\]::ToByte' 'Local Keycloak database decoding must remain compatible with Windows PowerShell 5.1.'
Assert-TextNotMatch $smoke '\[Convert\]::FromHexString' 'Prod-like smoke must not use the .NET Core-only Convert.FromHexString API.'
Assert-TextMatch $smoke "UPPER\(TRIM\(user_row\.auth_provider\)\)\s*=\s*'KEYCLOAK'" 'Local Keycloak provisioning must exclude legacy LOCAL users.'
Assert-TextMatch $smoke "user_row\.keycloak_id\s+IS\s+NOT\s+NULL[\s\S]{0,200}TRIM\(user_row\.keycloak_id\)\s*<>\s*''" 'Local Keycloak provisioning eligibility must require an existing production Keycloak link without copying its value.'
Assert-TextMatch $smoke "UPPER\(TRIM\(eligible_role\.name\)\)\s+IN\s*\([\s\S]{0,300}'ROLE_OWNER'[\s\S]{0,300}'ROLE_ADMIN'[\s\S]{0,300}'ROLE_MANAGER'[\s\S]{0,300}'ROLE_WORKER'[\s\S]{0,300}'ROLE_PERFORMER'" 'Local Keycloak provisioning must be limited to owner, admin, manager, specialist and performer users.'
Assert-TextMatch $smoke 'unexpected row while enumerating eligible Keycloak users' 'Local Keycloak provisioning must fail closed on unexpected database enumeration output.'
Assert-TextMatch $smoke "'otziv\.local-managed'\s*=\s*@\('true'\)" 'Locally provisioned Keycloak identities must carry the local-managed marker.'
Assert-TextMatch $smoke 'old local password could outlive' 'Locally managed identities that lose every eligible role must be retired.'
Assert-TextMatch $smoke "Refusing local identity synchronization through non-local Docker endpoint" 'Local identity synchronization must reject remote Docker contexts.'
Assert-TextMatch $smoke 'otziv-prod-local\|\$service' 'Local identity synchronization must verify the isolated prod-local Compose project and service labels.'
Assert-TextMatch $smoke "@\('port', 'nginx', '80'\)[\s\S]{0,500}publishedNginxLines\.Count\s+-ne\s+1" 'Local identity synchronization must bind BaseUrl to exactly one published endpoint of the isolated Nginx service.'
Assert-TextMatch $smoke 'IPAddress\]::IsLoopback\(\$publishedAddress\)[\s\S]{0,250}\$rootUri\.Port' 'The published Nginx endpoint must be loopback-only and use the effective BaseUrl port.'
Assert-TextMatch $smoke 'rootUri\.Host\.Equals\(''localhost''[\s\S]{0,250}rootUri\.Host\.Equals\(\$publishedAddress\.ToString' 'BaseUrl must use localhost or the exact published Nginx loopback address, not another 127/8 address.'
Assert-TextMatch $smoke '\$rootUri\.UserInfo[\s\S]{0,250}\$rootUri\.Query[\s\S]{0,250}\$rootUri\.Fragment' 'Local identity synchronization BaseUrl must reject userinfo, query strings and fragments.'
Assert-TextMatch $smoke 'Refusing to synchronize Keycloak identities through non-loopback BaseUrl' 'Local Keycloak identity synchronization must fail closed for non-loopback targets.'
Assert-TextMatch $smoke 'function\s+Sync-LocalKeycloakManagedRealmRoles[\s\S]{0,8000}SetEquals\(\$desired\)' 'Prod-like smoke must reconcile and verify the exact managed realm-role set from local DB roles.'
Assert-TextMatch $smoke 'function\s+Sync-LocalKeycloakManagedRealmRoles[\s\S]{0,1800}StringComparer\]::Ordinal\b' 'Keycloak realm-role reconciliation must use exact case-sensitive role names.'
Assert-TextMatch $smoke '\[string\[\]\]\$desiredRealmRoles\s*=\s*@\(\)[\s\S]{0,300}if\s*\(\[bool\]\$databaseUser\.Active\)[\s\S]{0,500}-DesiredRoles\s+\$desiredRealmRoles' 'Inactive eligible local identities must be disabled with an empty managed realm-role set.'
Assert-TextMatch $smoke 'function\s+Revoke-LocalKeycloakManagedUserSessions[\s\S]{0,2200}/logout[\s\S]{0,1400}/sessions[\s\S]{0,900}remainingSessions\.Count\s+-ne\s+0' 'Disabled local identities must be logged out and verified to have no remaining Keycloak sessions.'
Assert-TextMatch $smoke "function\s+Ensure-LocalKeycloakManagedMarkerProfile[\s\S]{0,3500}'otziv\.local-managed'[\s\S]{0,1500}view\s*=\s*@\('admin'\)[\s\S]{0,500}edit\s*=\s*@\('admin'\)" 'The local-managed user marker must be declared as an admin-only local Keycloak profile attribute.'
Assert-TextMatch $smoke '\$fullUser\.username\s*=\s*\$databaseUser\.Username[\s\S]{0,1000}\$updateBody\s*=\s*\$fullUser\s*\|\s*ConvertTo-Json' 'Managed-user updates must preserve the complete existing local Keycloak user representation.'
Assert-TextMatch $smoke 'Select-FrozenLocalKeycloakDatabaseUsers[\s\S]{0,1200}Sync-LocalKeycloakManagedUsers[\s\S]{0,900}Sync-LocalKeycloakSubjectMappings[\s\S]{0,5000}foreach\s*\(\$databaseUser\s+in\s+@\(\$activeDatabaseUsers' 'Only frozen eligible local users and subjects may be synchronized before login verification.'
Assert-TextMatch $smoke 'function\s+Ensure-LocalKeycloakActiveLoginProfile[\s\S]{0,1200}@account\.invalid' 'Every active eligible local login may receive unique non-production placeholder profile fields required by Keycloak.'
Assert-TextMatch $smoke 'Ensure-LocalKeycloakActiveLoginProfile[\s\S]{0,1400}reset-password' 'Each active eligible local login profile must be complete before its password and direct-grant probe are configured.'
Assert-TextMatch $smoke '-SkipCredentialSync:\$SkipLocalLoginCredentialSync' '-SkipLocalLoginCredentialSync must skip local password resets and login probes, not managed-user provisioning.'
Assert-TextMatch $smoke '\$resetCredential\s*=\s*\$InitializeSnapshot[\s\S]{0,300}\$RotateCredentials[\s\S]{0,300}\$managedUser\.created[\s\S]{0,300}\$hasPasswordCredential' 'Normal repeated smoke runs must not reset an existing frozen local password; initialization, explicit rotation, or a missing credential may reset it.'
Assert-TextMatch $smoke 'OTZIV_LOCAL_LOGIN_PENDING_USERNAME[\s\S]{0,300}OTZIV_LOCAL_LOGIN_PENDING_PASSWORD[\s\S]{0,2500}resumeCredentialRotation' 'Credential initialization and rotation must persist a resumable pending operation before changing Keycloak users.'
Assert-TextMatch $smoke 'Set-LocalEnvFileValues[\s\S]{0,500}OTZIV_LOCAL_LOGIN_USERNAME[\s\S]{0,300}OTZIV_LOCAL_LOGIN_PASSWORD[\s\S]{0,500}Remove-LocalEnvFileValues[\s\S]{0,300}OTZIV_LOCAL_LOGIN_PENDING_USERNAME' 'A verified pending credential rotation must be promoted atomically before its recovery marker is removed.'
Assert-TextMatch $smoke '-RotateCredentials:\(\$RotateLocalKeycloakCredentials -or \[bool\]\$localLoginConfiguration\.ResumeCredentialRotation\)' 'A normal smoke must resume an interrupted local credential rotation.'
Assert-TextMatch $smoke 'Ensure-LocalKeycloakActiveLoginProfile[\s\S]{0,900}attack-detection/brute-force/users/[\s\S]{0,900}\$credentials\s*=' 'Every active frozen local user must have stale brute-force state cleared before credential and login verification.'
Assert-TextMatch $smoke 'foreach \(\$loginTarget in \$loginTargets\)' 'Every active eligible local identity must pass a real Keycloak and backend authorization probe.'
Assert-TextMatch $smoke "'otziv-prod-local-login-smoke-'[\s\S]{0,250}'otziv-local-login-smoke-'" 'Local login smoke cleanup must cover the current project-specific prefix and the legacy reserved prefix.'
Assert-TextMatch $smoke '\$loginClientId\s*=\s*"otziv-prod-local-login-smoke-' 'Temporary direct-grant clients must use the isolated prod-local project prefix.'
Assert-TextNotMatch $smoke 'if\s*\(-not\s+\$SkipLocalLoginCredentialSync\)\s*\{\s*Sync-LocalKeycloakLoginCredential' 'Managed-user provisioning must still run when local credential probes are skipped.'

if ([regex]::Matches($smoke, 'Revoke-LocalKeycloakManagedUserSessions').Count -lt 3) {
    $violations.Add('Both inactive eligible and stale managed local identities must have their sessions revoked.')
}
if ([regex]::Matches($smoke, 'Remove-LocalKeycloakLoginSmokeClients').Count -lt 3) {
    $violations.Add('Reserved local direct-grant clients must be cleaned before and after successful login probes.')
}
if ([regex]::Matches($smoke, 'ConvertFrom-SmokeHexUtf8\s+-Hex').Count -lt 2) {
    $violations.Add('Both local usernames and realm roles must use the Windows PowerShell-compatible UTF-8 hex decoder.')
}

$managedUserFunction = [regex]::Match(
    $smoke,
    '(?s)function\s+Sync-LocalKeycloakManagedUsers\s*\{.*?(?=\r?\nfunction\s+Sync-LocalKeycloakSubjectMappings)'
).Value
if ([string]::IsNullOrWhiteSpace($managedUserFunction)) {
    $violations.Add('Could not isolate the local managed-user provisioning function for credential-safety checks.')
} else {
    Assert-TextNotMatch $managedUserFunction '(?m)^\s*(email|phone|firstName|lastName|credentials|password)\s*=' 'Bulk local Keycloak provisioning must not copy email, phone, full-name fields or assign credentials/passwords.'
    Assert-TextNotMatch $managedUserFunction 'keycloak_id' 'Bulk local Keycloak provisioning must not copy production Keycloak subjects.'
}
Assert-TextMatch $smoke 'MAX_BOT_WEBHOOK_SECRET[\s\S]{0,900}New-LocalRandomSecret' 'Prod-like smoke must supply a strong ephemeral MAX webhook secret when local messaging is disabled.'

Assert-TextMatch $baseProperties 'otziv\.auth\.legacy-migration\.enabled=\$\{OTZIV_AUTH_LEGACY_MIGRATION_ENABLED:false\}' 'Application fallback must keep retired legacy user migration disabled.'
Assert-TextMatch $productionCompose 'OTZIV_AUTH_LEGACY_MIGRATION_ENABLED:\s*\$\{OTZIV_AUTH_LEGACY_MIGRATION_ENABLED:-false\}' 'Production Compose must default retired legacy user migration to disabled.'
Assert-TextMatch $localCompose 'OTZIV_AUTH_LEGACY_MIGRATION_ENABLED:\s*\$\{OTZIV_AUTH_LEGACY_MIGRATION_ENABLED:-false\}' 'Prod-local Compose must default retired legacy user migration to disabled.'
Assert-TextMatch $baseEnvExample '(?m)^OTZIV_AUTH_LEGACY_MIGRATION_ENABLED=false\r?$' 'Base env example must keep retired legacy user migration disabled.'
Assert-TextMatch $productionEnvExample '(?m)^OTZIV_AUTH_LEGACY_MIGRATION_ENABLED=false\r?$' 'Production env example must keep retired legacy user migration disabled.'
Assert-TextMatch $localEnvExample '(?m)^OTZIV_AUTH_LEGACY_MIGRATION_ENABLED=false\r?$' 'Prod-local env example must keep retired legacy user migration disabled.'
Assert-TextMatch $deploy 'Set-EnvFileValue -Path \$stageEnv -Name "OTZIV_AUTH_LEGACY_MIGRATION_ENABLED" -Value "false"' 'Production deployment staging must overwrite stale legacy migration opt-ins.'
Assert-TextMatch $deploy '(?m)^set_env OTZIV_AUTH_LEGACY_MIGRATION_ENABLED "false"\r?$' 'Production remote deployment must persist the closed legacy migration window.'
Assert-TextMatch $legacyDeploy '(?m)^set_env OTZIV_AUTH_LEGACY_MIGRATION_ENABLED "false"\r?$' 'Legacy deployment must also persist the closed legacy migration window.'
Assert-TextNotMatch $frontendRoutes "path:\s*'legacy-migration'" 'Desktop frontend must not expose the retired legacy migration route.'
Assert-TextNotMatch $frontendNavigation "routerLink:\s*'/legacy-migration'" 'Desktop navigation must not link to retired legacy migration.'
Assert-TextNotMatch $frontendAdminLayout "'migration'" 'Desktop layout must not request the retired migration navigation entry.'
Assert-TextNotMatch $frontendUsersAdmin 'routerLink="/legacy-migration"' 'User administration must not link to retired legacy migration.'
Assert-TextNotMatch $mobileRoutes "path:\s*'legacy-migration'" 'Mobile frontend must not expose the retired legacy migration route.'
Assert-TextNotMatch $keycloakLoginTheme '/legacy-migration' 'Keycloak login must not advertise retired legacy migration.'

Assert-TextMatch $productionCompose 'OTZIV_SECURITY_LOCAL_STATE_EXEMPT_CLIENT_IDS:\s*\$\{OTZIV_SECURITY_LOCAL_STATE_EXEMPT_CLIENT_IDS:-\}' 'Production must not exempt any service account from local security state by default.'
Assert-TextMatch $localCompose 'OTZIV_SECURITY_LOCAL_STATE_EXEMPT_CLIENT_IDS:\s*\$\{OTZIV_SECURITY_LOCAL_STATE_EXEMPT_CLIENT_IDS:-otziv-smoke-ai-admin,otziv-smoke-ai-manager,otziv-smoke-ai-marketolog\}' 'Prod-local service-account exemption must be an exact bounded smoke-client list.'

Assert-TextMatch $productionCompose 'EXTERNAL_REVIEW_WORKER_AUTH_REQUIRED:\s*\$\{EXTERNAL_REVIEW_WORKER_AUTH_REQUIRED:-true\}' 'Production external-review worker authentication must fail closed.'
Assert-TextMatch $productionCompose 'WHATSAPP_GATEWAY_AUTH_REQUIRED:\s*\$\{WHATSAPP_GATEWAY_AUTH_REQUIRED:-true\}' 'Production WhatsApp gateway authentication must fail closed.'
Assert-TextMatch $localCompose 'EXTERNAL_REVIEW_WORKER_AUTH_REQUIRED:\s*\$\{EXTERNAL_REVIEW_WORKER_AUTH_REQUIRED:-false\}' 'Prod-local external-review authentication default must remain development-compatible.'
Assert-TextMatch $productionCompose 'BACKUP_ENABLED:\s*\$\{BACKUP_ENABLED:-false\}' 'Production backups must be opt-in until destination/encryption controls are confirmed.'
Assert-ComposeEnvironmentVariable $productionCompose 'BACKUP_S3_ACCESS_KEY' ':-' 'Production Compose must pass independent backup S3 credentials.'
Assert-ComposeEnvironmentVariable $productionCompose 'BACKUP_S3_SECRET_KEY' ':-' 'Production Compose must pass independent backup S3 credentials.'
Assert-ComposeEnvironmentVariable $productionCompose 'BACKUP_S3_BUCKET' ':-' 'Production Compose must pass the independent backup bucket.'
Assert-ComposeEnvironmentVariable $productionCompose 'BACKUP_S3_REQUIRE_SERVER_SIDE_ENCRYPTION' ':-true' 'Production SSE-S3 verification must fail closed by default.'
Assert-ComposeEnvironmentVariable $productionCompose 'BACKUP_S3_INDEPENDENT_CONFIRMED' ':-false' 'Production backup independence must require explicit confirmation.'
Assert-ComposeEnvironmentVariable $productionCompose 'BACKUP_S3_OBJECT_LOCK_ENABLED' ':-false' 'Production Object Lock must be explicitly enabled.'
Assert-ComposeEnvironmentVariable $localCompose 'BACKUP_S3_INDEPENDENT_CONFIRMED' ':-false' 'Prod-local backup independence must also fail closed.'
Assert-ComposeEnvironmentVariable $localCompose 'BACKUP_S3_REQUIRE_SERVER_SIDE_ENCRYPTION' ':-true' 'Prod-local SSE-S3 verification must fail closed by default.'
Assert-TextMatch $productionProperties 'backup\.s3\.require-server-side-encryption=\$\{BACKUP_S3_REQUIRE_SERVER_SIDE_ENCRYPTION:true\}' 'Application backup SSE-S3 verification must default to enabled.'
Assert-TextMatch $baseEnvExample '(?m)^BACKUP_S3_REQUIRE_SERVER_SIDE_ENCRYPTION=true\r?$' 'Base env example must keep SSE-S3 verification fail closed.'
Assert-TextMatch $productionEnvExample '(?m)^BACKUP_S3_REQUIRE_SERVER_SIDE_ENCRYPTION=true\r?$' 'Production env example must keep SSE-S3 verification fail closed.'
Assert-TextMatch $localEnvExample '(?m)^BACKUP_S3_REQUIRE_SERVER_SIDE_ENCRYPTION=true\r?$' 'Prod-local env example must keep SSE-S3 verification fail closed.'
Assert-TextMatch $productionCompose '(?ms)^\s{2}app:\s.*?read_only:\s*true\s.*?cap_drop:\s*\n\s*- ALL' 'Production backend container must be read-only with all Linux capabilities dropped.'
Assert-TextMatch $productionCompose '(?ms)^\s{2}whatsapp_lika:\s.*?read_only:\s*true\s.*?cap_drop:\s*\n\s*- ALL' 'Production WhatsApp containers must be read-only with all Linux capabilities dropped.'
Assert-TextMatch $localCompose '(?ms)^\s{2}app:\s.*?read_only:\s*true\s.*?cap_drop:\s*\n\s*- ALL' 'Prod-local backend must exercise the production non-root/read-only runtime contract.'
Assert-TextMatch $productionCompose 'image:\s*\$\{APP_IMAGE:\?APP_IMAGE must be an immutable release tag or digest\}' 'Production backend image must require an explicit immutable release identifier.'
Assert-TextMatch $productionCompose 'image:\s*\$\{WEB_IMAGE:\?WEB_IMAGE must be an immutable release tag or digest\}' 'Production web image must require an explicit immutable release identifier.'
Assert-TextMatch $productionCompose 'image:\s*\$\{WHATSAPP_IMAGE:\?WHATSAPP_IMAGE must be an explicit deployment tag\}' 'Production WhatsApp image must require an explicit deployment tag.'
Assert-TextMatch $productionCompose 'image:\s*\$\{EXTERNAL_REVIEW_WORKER_IMAGE:\?EXTERNAL_REVIEW_WORKER_IMAGE must be an explicit deployment tag or digest\}' 'Production external review worker image must require an explicit release identifier.'
Assert-TextMatch $productionCompose 'prom/prometheus@sha256:[0-9a-f]{64}' 'Prometheus image must be pinned by digest.'
Assert-TextMatch $productionCompose 'grafana/loki@sha256:[0-9a-f]{64}' 'Loki image must be pinned by digest.'
Assert-TextMatch $productionCompose 'grafana/tempo@sha256:[0-9a-f]{64}' 'Tempo image must be pinned by digest.'
Assert-TextMatch $productionCompose 'grafana/alloy@sha256:[0-9a-f]{64}' 'Alloy image must be pinned by digest.'
Assert-TextMatch $productionCompose 'grafana/grafana@sha256:[0-9a-f]{64}' 'Grafana image must be pinned by digest.'
Assert-ComposeEnvironmentVariable $productionCompose 'TELEGRAM_BOT_LINK_SECRET' ':-' 'Production Compose must pass the Telegram bot link secret.'
Assert-ComposeEnvironmentVariable $productionCompose 'MAX_BOT_LINK_SECRET' ':-' 'Production Compose must pass the MAX bot link secret.'
Assert-TextMatch $productionCompose 'MAX_BOT_WEBHOOK_HMAC_REQUIRED:\s*\$\{MAX_BOT_WEBHOOK_HMAC_REQUIRED:-false\}' 'MAX webhook HMAC must remain disabled because the official contract only sends X-Max-Bot-Api-Secret.'
Assert-TextMatch $productionCompose 'MAX_BOT_API_BASE_URL:\s*\$\{MAX_BOT_API_BASE_URL:-https://platform-api2\.max\.ru\}' 'Production Compose must use the current MAX API endpoint.'
Assert-TextMatch $localCompose 'MAX_BOT_API_BASE_URL:\s*\$\{MAX_BOT_API_BASE_URL:-https://platform-api2\.max\.ru\}' 'Prod-local Compose must use the current MAX API endpoint.'
Assert-TextMatch $productionCompose '(?ms)^\s{2}phpmyadmin:\s.*?profiles:\s*\["db-admin"\]' 'Production phpMyAdmin must remain behind the db-admin profile.'
Assert-TextMatch $productionCompose 'x-default-logging:\s*&default-logging' 'Production containers must use the bounded logging policy.'
Assert-TextMatch $localCompose 'x-default-logging:\s*&default-logging' 'Prod-local containers must use the bounded logging policy.'
foreach ($resourceLimitName in @(
    'MYSQL_MEMORY_LIMIT', 'KEYCLOAK_DB_MEMORY_LIMIT', 'KEYCLOAK_MEMORY_LIMIT',
    'APP_MEMORY_LIMIT', 'EXTERNAL_REVIEW_WORKER_MEMORY_LIMIT', 'WHATSAPP_MEMORY_LIMIT',
    'NGINX_MEMORY_LIMIT', 'DOZZLE_MEMORY_LIMIT', 'PROMETHEUS_MEMORY_LIMIT',
    'LOKI_MEMORY_LIMIT', 'TEMPO_MEMORY_LIMIT', 'ALLOY_MEMORY_LIMIT', 'GRAFANA_MEMORY_LIMIT'
)) {
    Assert-TextMatch $productionCompose ([Regex]::Escape($resourceLimitName)) "Production Compose is missing resource limit $resourceLimitName."
}

Assert-TextMatch $nginx 'proxy_set_header X-Forwarded-For \$remote_addr;' 'Nginx must replace untrusted X-Forwarded-For values.'
Assert-TextNotMatch $nginx '\$proxy_add_x_forwarded_for' 'Nginx must not append client-supplied X-Forwarded-For values.'
Assert-TextMatch $nginx 'Content-Security-Policy-Report-Only' 'Production Nginx must retain CSP telemetry before enforcement.'
Assert-TextMatch $nginx 'ngCspNonce="\$request_id"' 'Angular HTML must receive a per-response CSP nonce.'
Assert-TextMatch $nginx 'style-src-elem ''self'' ''nonce-\$request_id''' 'Runtime Angular style elements must require the response nonce.'
Assert-TextMatch $nginx 'style-src-attr ''unsafe-inline''' 'Dynamic Angular style attributes must be isolated in their CSP directive.'
Assert-TextMatch $nginx 'add_header Content-Security-Policy "default-src ''self'';[^"]+script-src \$otziv_csp_script_src;' 'Production Nginx must enforce CSP with a route-scoped script policy.'
Assert-TextMatch $nginx '(?ms)location ~\* "\^/\(\?:\[0-9a-f\].*?add_header Content-Security-Policy ' 'Public review/payment capability routes must retain CSP despite Nginx header-inheritance rules.'

Assert-TextNotMatch $developmentCompose '(?m)^\s*-\s*"?(?!127\.0\.0\.1:)(?:\$\{[^}]+\}|[0-9]+):[0-9]+"?\s*$' 'Development-only published ports must bind to loopback.'

Assert-TextMatch $deploy 'AllowDirtyWorktree' 'Production deployment must reject dirty build inputs by default.'
Assert-TextMatch $deploy 'mkdir "`\$deploy_lock_dir"[\s\S]{0,500}deploy_lock_token' 'Production deployment must acquire a durable remote exclusive lock before backup.'
Assert-TextMatch $deploy 'release_deploy_lock' 'Production deployment must release only the durable lock owned by its rollout token.'
Assert-TextMatch $deploy 'deploy_cleanup[\s\S]{0,4000}self-heal timer remains disabled and stopped[\s\S]{0,250}deploy lock remains' 'A production deployment that fails before payload completion must leave self-heal disabled and retain the durable lock for manual verification.'
Assert-TextMatch $deploy 'BACKUP_ENCRYPTION_AT_REST_CONFIRMED' 'Production deployment must validate backup encryption readiness when backups are enabled.'
Assert-TextMatch $deploy 'require_env BACKUP_S3_ENDPOINT' 'Production deployment must require the independent backup endpoint.'
Assert-TextMatch $deploy 'Assert-ProductionCredentialEncryptionConfig -Path \$envFilePath' 'Production deployment must validate an uploaded external credential-encryption key before build/push.'
Assert-TextMatch $deploy 'OTZIV_CREDENTIAL_ENCRYPTION_REQUIRED=true is mandatory for production deployment' 'Production deployment must fail closed unless credential encryption is required.'
Assert-TextMatch $deploy 'require_env OTZIV_CREDENTIAL_ENCRYPTION_ACTIVE_KEY_ID' 'Production deployment must require an external credential-encryption key id on the VPS.'
Assert-TextMatch $deploy 'require_env OTZIV_CREDENTIAL_ENCRYPTION_ACTIVE_KEY_BASE64' 'Production deployment must require an external credential-encryption key on the VPS.'
Assert-TextMatch $deploy 'OTZIV_CREDENTIAL_ENCRYPTION_ACTIVE_KEY_BASE64 must decode to exactly 32 bytes' 'Production deployment must validate the active AES-256 credential key without printing it.'
Assert-TextMatch $deploy 'BACKUP_S3_ENDPOINT must use HTTPS' 'Production deployment must reject plaintext backup transport before changing services.'
Assert-TextMatch $deploy 'BACKUP_S3_REQUIRE_SERVER_SIDE_ENCRYPTION must be true or false' 'Production deployment must validate the explicit provider SSE compatibility switch.'
Assert-TextMatch $deploy 'BACKUP_RUN_ONCE_ENABLED must remain false in persistent production env' 'Production deployment must reject persistent one-shot backup mode.'
Assert-TextMatch $deploy 'BACKUP_ENABLED=true requires BACKUP_SCHEDULE_ENABLED=true' 'Enabled production backups must retain their recurring schedule.'
Assert-TextMatch $deploy 'BACKUP_ENABLED=true requires BACKUP_SCHEDULE_CATCH_UP_ENABLED=true' 'Enabled production backups must retain catch-up after downtime.'
Assert-TextMatch $deploy 'BACKUP_SCHEDULE_CATCH_UP_WINDOW must be a whole-hour duration from PT25H through PT36H' 'Production catch-up must cover the previous daily occurrence and remain bounded.'
Assert-TextMatch $deploy "numeric seconds/minutes/hours value and '\*' for day-of-month/month/day-of-week" 'Production deployment must require one backup every calendar day.'
Assert-TextMatch $deploy 'BACKUP_SCHEDULE_ZONE must identify an installed IANA time zone' 'Production deployment must reject an unavailable backup time zone.'
Assert-TextMatch $deploy 'require_env BACKUP_RESTORE_DRILL_RTO' 'Production deployment must require measured restore RTO evidence when backups are enabled.'
Assert-TextMatch $deploy 'Backup storage must not use the primary S3 bucket' 'Production deployment must reject the primary S3 bucket for backups.'
Assert-TextMatch $deploy 'BACKUP_S3_OBJECT_LOCK_ENABLED' 'Production deployment must validate optional Object Lock retention.'
Assert-TextMatch $deploy 'BACKUP_ENCRYPTION_KEY_BASE64 must decode to exactly 32 bytes' 'Production deployment must validate the configured AES-256 backup key.'
Assert-TextMatch $deploy 'get_env BACKUP_MAIL_ENABLED false' 'Production deployment must gate backup email requirements on the explicit mail switch.'
Assert-TextMatch $deploy 'Scheduled DB-backup encryption and credential-field encryption must use different keys' 'Production deployment must reject scheduled backup key reuse for encrypted credential fields.'
Assert-TextMatch $deploy 'Pre-deploy and scheduled DB backups must use different encryption keys' 'Production deployment must keep scheduled and pre-deploy backup encryption keys distinct.'
Assert-TextMatch $deploy 'BACKUP_S3_ENDPOINT must be a literal env-safe HTTPS URI' 'Production deployment must reject env interpolation in the backup endpoint.'
Assert-TextMatch $deploy 'MAIL_STARTTLS_REQUIRED MAIL_SMTP_SSL_CHECK_SERVER_IDENTITY' 'Backup email deployment must require TLS and server-identity verification.'
Assert-TextMatch $deploy 'SMTP timeouts must be positive integers no greater than 600000 ms' 'Backup email deployment must reject absent or unbounded SMTP timeouts.'
Assert-TextMatch $backupReadiness 'FromBase64String\(\$encodedBackupKey\)' 'Backup readiness must decode and validate the configured encryption key.'
Assert-TextMatch $backupReadiness '\$decodedBackupKey\.Length -ne 32' 'Backup readiness must require exactly 32 bytes for AES-256.'
Assert-TextMatch $backupReadiness "Get-Setting 'BACKUP_MAIL_ENABLED'" 'Backup readiness must gate email validation on the explicit mail switch.'
Assert-TextMatch $backupReadiness "'BACKUP_S3_ACCESS_KEY'" 'Backup readiness must require independent S3 credentials.'
Assert-TextMatch $backupReadiness "Scheme -cne 'https'" 'Backup readiness must reject plaintext transport to independent backup storage.'
Assert-TextMatch $backupReadiness "'BACKUP_RESTORE_DRILL_DATE', 'BACKUP_RESTORE_DRILL_RTO'" 'Backup readiness must require both restore drill date and measured RTO.'
Assert-TextMatch $backupReadiness 'must differ from primary S3 bucket' 'Backup readiness must reject the primary S3 bucket.'
Assert-TextMatch $backupReadiness 'BACKUP_S3_OBJECT_LOCK_ENABLED=true \(required for non-zero retention\)' 'Backup readiness must not report unenforced retention as successful.'
Assert-TextMatch $backupReadiness 'BACKUP_S3_REQUIRE_SERVER_SIDE_ENCRYPTION \(exactly true or false\)' 'Backup readiness must validate the provider SSE compatibility switch without requiring it to be true.'
Assert-TextMatch $backupReadiness 'BACKUP_RUN_ONCE_ENABLED must remain false in persistent env' 'Backup readiness must reject persistent one-shot mode before its disabled fast path.'
Assert-TextMatch $backupReadiness 'BACKUP_SCHEDULE_ENABLED=true \(required for recurring production backups\)' 'Backup readiness must require the production recurring schedule.'
Assert-TextMatch $backupReadiness 'BACKUP_SCHEDULE_CATCH_UP_ENABLED=true' 'Backup readiness must require catch-up after downtime.'
Assert-TextMatch $backupReadiness 'greater than PT24H and no greater than PT36H' 'Backup readiness must keep catch-up both covering and bounded.'
Assert-TextMatch $backupReadiness 'function Test-DailyCron' 'Backup readiness must reject cron time fields that can repeat within a day.'
Assert-TextMatch $backupReadiness "fields\[3\].*'\*'.*fields\[4\].*'\*'.*fields\[5\].*'\*'" 'Backup readiness must require one run every calendar day.'
Assert-TextMatch $backupReadiness 'FindSystemTimeZoneById\(\$scheduleZone\)' 'Backup readiness must reject a time zone unavailable to the runtime.'
Assert-TextMatch $backupReadiness 'Test-Base64SecretsEqual -Left \$encodedBackupKey -Right \$credentialEncryptionKey' 'Backup readiness must compare decoded scheduled and credential encryption keys.'
Assert-TextMatch $backupReadiness 'Test-Base64SecretsEqual -Left \$encodedBackupKey -Right \$deployBackupKey' 'Backup readiness must compare decoded scheduled and pre-deploy backup encryption keys.'
Assert-TextMatch $backupReadiness 'MAIL_STARTTLS_REQUIRED' 'Backup readiness must reject an SMTP STARTTLS downgrade.'
Assert-TextMatch $backupReadiness 'MAIL_SMTP_SSL_CHECK_SERVER_IDENTITY' 'Backup readiness must require SMTP server-identity verification.'
Assert-TextMatch $backupConfigImporter "'BACKUP_S3_REQUIRE_SERVER_SIDE_ENCRYPTION'" 'Protected backup config import must allowlist the provider SSE compatibility switch.'
Assert-TextMatch $backupConfigImporter 'BACKUP_RUN_ONCE_ENABLED=true must not be persisted' 'Protected backup config import must reject persistent one-shot mode.'
Assert-TextMatch $backupConfigImporter 'BACKUP_ENABLED=true requires BACKUP_SCHEDULE_ENABLED=true' 'Protected backup config import must preserve recurring production backups.'
Assert-TextMatch $backupConfigImporter 'BACKUP_ENABLED=true requires BACKUP_SCHEDULE_CATCH_UP_ENABLED=true' 'Protected backup config import must preserve bounded catch-up.'
Assert-TextMatch $backupConfigImporter 'BACKUP_SCHEDULE_CATCH_UP_WINDOW must be greater than PT24H' 'Protected backup config import must cover the previous daily occurrence.'
Assert-TextMatch $backupConfigImporter 'function Assert-DailyCron' 'Protected backup config import must reject cron time fields that can repeat within a day.'
Assert-TextMatch $backupConfigImporter "day-of-month, month and day-of-week must all be '\*'" 'Protected backup config import must require one backup every calendar day.'
Assert-TextMatch $backupConfigImporter 'FindSystemTimeZoneById\(\$Value\)' 'Protected backup config import must reject an unavailable runtime time zone.'
Assert-TextMatch $backupConfigImporter 'function Assert-SafeEnvText' 'Protected backup config import must reject env-file interpolation in mail text.'
Assert-TextMatch $backupConfigImporter "Assert-SafeEnvText -Name 'BACKUP_S3_ENDPOINT'" 'Protected backup config import must reject env-file interpolation in the S3 endpoint.'
Assert-TextMatch $qualityWorkflow 'test-import-prod-backup-config\.ps1' 'Quality gates must execute the protected backup importer contract test.'
Assert-TextMatch $qualityWorkflow 'test-backup-readiness\.ps1' 'Quality gates must execute behavioral backup readiness guard tests.'
Assert-TextMatch $restore '\[switch\]\$KeepDownloadedDump' 'Fresh production database downloads must be retained only through an explicit opt-in.'
Assert-TextMatch $restore '\[ValidateRange\(1, 100\)\]\[int\]\$LocalDumpRetentionCount = 1' 'Local plaintext production dump retention must remain bounded to one by default.'
Assert-TextMatch $restore 'Removed ephemeral plaintext production dump after verified restore' 'Fresh production database dumps must be removed after restore verification.'
Assert-TextMatch $restore 'Refusing to auto-remove downloaded dump outside the protected backup directory' 'Automatic dump removal must remain confined to the protected backup directory.'
Assert-TextMatch $restore 'function Sanitize-RestoredExternalCredentials' 'A restored production database must remove third-party passwords before local application startup.'
Assert-TextMatch $restore "CONCAT\('local-disabled-', bot_id\)" 'Local restore must replace required bot passwords with deterministic non-production values.'
Assert-TextMatch $restore 'telephone_google_password = NULL' 'Local restore must remove copied telephone provider passwords.'
Assert-TextMatch $deploy 'TELEGRAM_BOT_LINK_SECRET must contain at least 32 bytes' 'Production deployment must validate Telegram bot link-secret strength.'
Assert-TextMatch $deploy 'MAX_BOT_LINK_SECRET must contain at least 32 bytes' 'Production deployment must validate MAX bot link-secret strength.'
Assert-TextMatch $deploy 'ensure_generated_link_secret TELEGRAM_BOT_LINK_SECRET' 'Deploy must securely generate a missing Telegram link secret before validation.'
Assert-TextMatch $deploy 'ensure_generated_link_secret MAX_BOT_LINK_SECRET' 'Deploy must securely generate a missing MAX link secret before validation.'
Assert-TextMatch $deploy 'openssl rand -hex 32' 'First-deploy link-secret generation must use 32 random bytes from OpenSSL.'
Assert-TextMatch $deploy 'existing non-empty values are never overwritten' 'Deploy must document its non-overwrite link-secret contract.'
Assert-TextMatch $deploy 'get_env PHPMYADMIN_ENABLED false' 'Production phpMyAdmin startup must require an explicit opt-in.'
Assert-TextNotMatch $deploy '(?m)^\s*remove_repo_images\s+"' 'Deploy must retain previous application images for manual rollback.'

Assert-TextMatch $secretWorkflow 'Gitleaks new commit history \(blocking\)' 'CI must scan secrets in newly introduced commit history.'
Assert-TextMatch $secretWorkflow '(?s)Gitleaks full history.*?gitleaks.*?git' 'CI must perform a scheduled/manual full-history secret scan.'
Assert-TextMatch $secretWorkflow 'keystore\\\.properties' 'CI must reject tracked Android keystore property files.'
Assert-TextMatch $secretWorkflow 'jks\|keystore' 'CI must reject tracked JKS/keystore files.'
Assert-TextMatch $localSecretScan 'Assert-NoStagedSensitiveFiles' 'The staged scan must enforce sensitive-file path gates.'
Assert-TextMatch $localSecretScan 'keystore\\\.properties' 'The staged scan must reject keystore.properties.'
Assert-TextMatch $localSecretScan 'jks\|keystore' 'The staged scan must reject JKS/keystore files.'
Assert-TextMatch $gitleaksConfig 'mobile/android/keystore\\\.properties' 'The dir scan may skip only the ignored local Android signing-properties path.'
$pinnedWorkflowSet = $secretWorkflow + $qualityWorkflow + $dependencyWorkflow + $sqlGuardWorkflow
Assert-TextNotMatch $pinnedWorkflowSet 'actions/(checkout|upload-artifact|setup-node|setup-java)@v[0-9]' 'CI actions must use immutable commit pins, not mutable major tags.'
Assert-TextMatch $pinnedWorkflowSet 'actions/checkout@11bd71901bbe5b1630ceea73d27597364c9af683' 'CI checkout must retain its reviewed immutable pin.'
Assert-TextMatch $pinnedWorkflowSet 'actions/setup-node@49933ea5288caeca8642d1e84afbd3f7d6820020' 'CI setup-node must retain its reviewed v4.4.0 immutable pin.'
Assert-TextMatch $pinnedWorkflowSet 'actions/setup-java@c1e323688fd81a25caa38c78aa6df2d33d3e20d9' 'CI setup-java must retain its reviewed v4.8.0 immutable pin.'
Assert-TextMatch $maxWebhookRegistration 'https://platform-api2\.max\.ru' 'MAX webhook registration must use the current MAX API endpoint.'
Assert-TextMatch $productionProperties 'MAX_BOT_API_BASE_URL:https://platform-api2\.max\.ru' 'Backend production properties must use the current MAX API endpoint.'
Assert-TextMatch $productionProperties 'backup\.enabled=\$\{BACKUP_ENABLED:false\}' 'Backend production backup automation must fail closed outside Compose as well.'
Assert-TextMatch $backendDockerfile 'COPY docker/certs/russian_trusted_root_ca\.crt' 'The backend image must copy the pinned Russian Trusted Root CA.'
Assert-TextMatch $backendDockerfile 'D2:6D:2D:02:31:B7:C3:9F:92:CC:73:85:12:BA:54:10:35:19:E4:40:5D:68:B5:BD:70:3E:97:88:CA:8E:CF:31' 'The backend image must verify the reviewed Russian Trusted Root CA fingerprint.'
Assert-TextMatch $backendDockerfile 'update-ca-certificates' 'The backend image must import the MAX root into the OS trust store.'
Assert-TextMatch $backendDockerfile 'keytool -importcert' 'The backend image must import the MAX root into the JVM trust store.'
Assert-TextMatch $backendDockerfile '(?m)^USER otziv:otziv\s*$' 'The backend runtime image must run as the dedicated non-root user.'
Assert-TextMatch $whatsAppDockerfile '(?m)^USER node\s*$' 'The WhatsApp runtime image must run as the non-root node user.'
Assert-TextMatch $whatsAppDockerfile '(?m)^\s*chromium-sandbox \\\r?$' 'The WhatsApp runtime image must install Chromium''s sandbox helper explicitly.'
Assert-TextNotMatch $whatsAppGateway '(?m)^[^/\r\n]*--no-sandbox' 'WhatsApp Chromium must not disable the browser sandbox.'
Assert-TextNotMatch $whatsAppGateway '(?m)^[^/\r\n]*--disable-setuid-sandbox' 'WhatsApp Chromium must not disable the setuid sandbox.'
Assert-TextMatch $productionCompose '(?ms)^\s{2}whatsapp_lika:.*?cap_drop:\s*\n\s*- ALL\s*\n\s*cap_add:\s*\n\s*- SYS_ADMIN\s*\n\s*- SYS_CHROOT' 'WhatsApp must admit only the two capabilities needed by Chromium''s namespace sandbox.'
Assert-TextMatch $deploy 'WhatsApp Chromium sandbox preflight failed; existing gateway containers were not stopped' 'Production deploy must validate Chromium sandbox compatibility before stopping existing gateways.'
Assert-TextMatch $deploy '(?m)^compose run --rm --no-deps --interactive=false -T --cap-add CHOWN --user 0 --entrypoint chown app ' 'The one-shot app ownership migration must regain CAP_CHOWN after the service drops all capabilities.'
Assert-TextMatch $deploy '(?m)^compose run --rm --no-deps --interactive=false -T --cap-add CHOWN --cap-add DAC_READ_SEARCH --user 0 --entrypoint sh whatsapp_lika ' 'The one-shot WhatsApp ownership migration must regain CAP_CHOWN and narrowly bypass read/search checks for legacy mode-0700 session trees.'
Assert-TextMatch $deploy '(?m)^compose run --rm --no-deps --interactive=false -T --cap-add CHOWN --cap-add DAC_READ_SEARCH --user 0 --entrypoint sh whatsapp_vika ' 'Both one-shot WhatsApp ownership migrations must handle legacy mode-0700 session trees.'
Assert-TextMatch $legacyDeploy '(?m)^compose run --rm --no-deps --interactive=false -T --cap-add CHOWN --user 0 --entrypoint chown app ' 'The legacy one-shot app ownership migration must regain CAP_CHOWN after the service drops all capabilities.'
Assert-TextMatch $legacyDeploy '(?m)^compose run --rm --no-deps --interactive=false -T --cap-add CHOWN --user 0 --entrypoint sh whatsapp_lika ' 'The legacy one-shot WhatsApp ownership migration must regain CAP_CHOWN after the service drops all capabilities.'

Assert-TextNotMatch $notificationMediaImporter 'MYSQL_ROOT_PASSWORD|mysql\s+-uroot' 'Notification media import must not use the MySQL root account.'
Assert-TextMatch $notificationMediaImporter 'MYSQL_PWD="\$MYSQL_PASSWORD"\s+mysql\s+-u"\$MYSQL_USER"' 'Notification media import must use the scoped application database account.'
$notificationTransactionStarts = [Regex]::Matches($notificationMediaImporter, 'START TRANSACTION;').Count
$notificationTransactionCommits = [Regex]::Matches($notificationMediaImporter, 'COMMIT;').Count
if ($notificationTransactionStarts -ne 1 -or $notificationTransactionCommits -ne 1) {
    $violations.Add('Notification media rule and asset mutations must share exactly one database transaction.')
}

$propertyVariables = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
foreach ($match in [Regex]::Matches($productionProperties + "`n" + $baseProperties, '\$\{([A-Z0-9_]+)(?::[^}]*)?\}')) {
    [void]$propertyVariables.Add($match.Groups[1].Value)
}
$composeVariables = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
foreach ($match in [Regex]::Matches($productionCompose, '\$\{([A-Z0-9_]+)(?::[-?][^}]*)?\}')) {
    [void]$composeVariables.Add($match.Groups[1].Value)
}
$processOnlyVariables = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
[void]$processOnlyVariables.Add('BACKUP_RUN_ONCE_ENABLED')
[void]$processOnlyVariables.Add('BACKUP_RUN_ONCE_REQUEST_ID')
foreach ($name in $propertyVariables) {
    if (-not $composeVariables.Contains($name) -and -not $processOnlyVariables.Contains($name)) {
        $violations.Add("Production Compose does not pass documented backend variable $name.")
    }
}

$trustedRootPath = Join-Path $repoRoot 'backend/docker/certs/russian_trusted_root_ca.crt'
if (-not (Test-Path -LiteralPath $trustedRootPath -PathType Leaf)) {
    $violations.Add('Pinned Russian Trusted Root CA file is missing.')
} else {
    try {
        $trustedRootPem = [System.IO.File]::ReadAllText($trustedRootPath)
        $trustedRoot = [System.Security.Cryptography.X509Certificates.X509Certificate2]::CreateFromPem($trustedRootPem)
        try {
            $actualFingerprint = $trustedRoot.GetCertHashString([System.Security.Cryptography.HashAlgorithmName]::SHA256)
            if ($actualFingerprint -ne 'D26D2D0231B7C39F92CC738512BA54103519E4405D68B5BD703E9788CA8ECF31') {
                $violations.Add("Russian Trusted Root CA fingerprint mismatch: $actualFingerprint")
            }
        } finally {
            $trustedRoot.Dispose()
        }
    } catch {
        $violations.Add("Unable to parse pinned Russian Trusted Root CA: $($_.Exception.Message)")
    }
}

Assert-TextNotMatch $productionCompose 'Dockerfile2\.whatsapp|Dockerfile\.nginx' 'Production Compose must not reference quarantined legacy Dockerfiles.'
Assert-TextNotMatch $localCompose 'Dockerfile2\.whatsapp|Dockerfile\.nginx' 'Prod-local Compose must not reference quarantined legacy Dockerfiles.'
Assert-TextNotMatch $developmentCompose 'Dockerfile2\.whatsapp|Dockerfile\.nginx' 'Development Compose must not reference quarantined legacy Dockerfiles.'
Assert-DigestPinnedDockerfileBases $backendDockerfile 'backend/Dockerfile'
Assert-DigestPinnedDockerfileBases $frontendDockerfile 'frontend/Dockerfile'

try {
    $frontendAngularJson = $frontendAngularConfig | ConvertFrom-Json -AsHashtable
    $inlineCritical = $frontendAngularJson['projects']['frontend']['architect']['build']['configurations']['production']['optimization']['styles']['inlineCritical']
    if ($inlineCritical -ne $false) {
        $violations.Add('Production frontend must disable critical CSS inlining so its stylesheet does not depend on a CSP-blocked inline onload handler.')
    }
}
catch {
    $violations.Add("Could not validate frontend/angular.json production CSS optimization: $($_.Exception.Message)")
}

try {
    $frontendPackageJson = $frontendPackageConfig | ConvertFrom-Json -AsHashtable
    $frontendOverrides = $frontendPackageJson['overrides']
    $requiredFrontendOverrides = [ordered]@{
        'hono' = '4.12.34'
        'ip-address' = '10.4.0'
    }
    foreach ($dependency in $requiredFrontendOverrides.Keys) {
        if ($frontendOverrides[$dependency] -ne $requiredFrontendOverrides[$dependency]) {
            $violations.Add("Frontend dependency override $dependency must remain pinned to $($requiredFrontendOverrides[$dependency]).")
        }
    }
    foreach ($parent in @('@angular/build', 'jsdom')) {
        if ($frontendOverrides[$parent]['undici'] -ne '7.29.0') {
            $violations.Add("Frontend dependency override $parent -> undici must remain pinned to 7.29.0.")
        }
    }
    if ($frontendOverrides['node-gyp']['undici'] -ne '6.28.0') {
        $violations.Add('Frontend dependency override node-gyp -> undici must remain pinned to 6.28.0.')
    }
}
catch {
    $violations.Add("Could not validate frontend/package.json security overrides: $($_.Exception.Message)")
}

Assert-TextMatch $frontendSilentCheckSsoHtml '<script\s+src="/silent-check-sso\.js"></script>' 'Keycloak silent SSO callback must use a same-origin external script allowed by the enforced CSP.'
Assert-TextNotMatch $frontendSilentCheckSsoHtml '<script(?:\s[^>]*)?>\s*[^<\s]' 'Keycloak silent SSO callback must not contain inline JavaScript blocked by production CSP.'
Assert-TextMatch $frontendSilentCheckSsoScript 'window\.parent\.postMessage\(window\.location\.href,\s*window\.location\.origin\)' 'Keycloak silent SSO callback script must return the callback URL to its same-origin parent.'
Assert-DigestPinnedDockerfileBases $whatsAppDockerfile 'Dockerfile.whatsapp'
Assert-DigestPinnedDockerfileBases $externalReviewWorkerDockerfile 'backend/external-review-worker/Dockerfile'
Assert-DigestPinnedComposeImages $productionCompose 'docker-compose.yaml'
Assert-DigestPinnedComposeImages $localCompose 'compose.prod-local.yaml'
Assert-DigestPinnedComposeImages $developmentCompose 'compose.yaml'
Assert-TextMatch $legacyWhatsAppDockerfile 'OTZIV_ALLOW_QUARANTINED_DOCKERFILE' 'Legacy WhatsApp Dockerfile must refuse accidental builds.'
Assert-TextMatch $legacyNginxDockerfile 'OTZIV_ALLOW_QUARANTINED_DOCKERFILE' 'Legacy Nginx Dockerfile must refuse accidental builds.'
Assert-TextMatch $legacyDeploy 'if \(-not \$AllowLegacyDeploy\)' 'Legacy direct-image deploy must refuse accidental use.'

if ($violations.Count -gt 0) {
    Write-Error ("Infrastructure contract violations:`n - " + ($violations -join "`n - "))
}

Write-Output 'Infrastructure security contracts passed.'
