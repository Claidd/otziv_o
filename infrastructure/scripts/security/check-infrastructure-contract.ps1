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

$restore = Get-RepositoryText 'infrastructure/scripts/local/restore-prod-db-local.ps1'
$smoke = Get-RepositoryText 'infrastructure/scripts/local/prod-like-smoke.ps1'
$deploy = Get-RepositoryText 'infrastructure/scripts/prod/deploy-prod.ps1'
$productionCompose = Get-RepositoryText 'docker-compose.yaml'
$localCompose = Get-RepositoryText 'compose.prod-local.yaml'
$nginx = Get-RepositoryText 'infrastructure/nginx/prod.conf'
$secretWorkflow = Get-RepositoryText '.github/workflows/secret-scan.yml'
$maxWebhookRegistration = Get-RepositoryText 'infrastructure/scripts/prod/register-max-webhook.ps1'
$localSecretScan = Get-RepositoryText 'infrastructure/scripts/security/run-secret-scan.ps1'
$gitleaksConfig = Get-RepositoryText '.gitleaks.toml'
$qualityWorkflow = Get-RepositoryText '.github/workflows/quality-gates.yml'
$dependencyWorkflow = Get-RepositoryText '.github/workflows/dependency-audit.yml'
$sqlGuardWorkflow = Get-RepositoryText '.github/workflows/sql-injection-guard.yml'
$legacyWhatsAppDockerfile = Get-RepositoryText 'Dockerfile2.whatsapp'
$legacyNginxDockerfile = Get-RepositoryText 'Dockerfile.nginx'
$legacyDeploy = Get-RepositoryText 'infrastructure/scripts/prod/deploy-prod-ssh-images.ps1'
$backendDockerfile = Get-RepositoryText 'backend/Dockerfile'
$productionProperties = Get-RepositoryText 'backend/src/main/resources/application-prod.properties'

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
Assert-TextMatch $restore 'prod-like-smoke\.ps1[\s\S]{0,300}-SkipProdDbRestore' '-RunSmoke must not perform a second production DB restore.'
Assert-TextMatch $restore 'Format-RedactedCommand' 'Restore command errors must redact credentials.'
Assert-TextMatch $smoke 'Format-RedactedCommand' 'Smoke command errors must redact credentials.'
Assert-TextMatch $smoke 'Initialize-LocalBotLinkSecrets' 'Prod-like smoke must supply and validate distinct bot link secrets.'
Assert-TextMatch $smoke 'MAX_BOT_WEBHOOK_SECRET[\s\S]{0,900}New-LocalRandomSecret' 'Prod-like smoke must supply a strong ephemeral MAX webhook secret when local messaging is disabled.'

Assert-TextMatch $productionCompose 'EXTERNAL_REVIEW_WORKER_AUTH_REQUIRED:\s*\$\{EXTERNAL_REVIEW_WORKER_AUTH_REQUIRED:-true\}' 'Production external-review worker authentication must fail closed.'
Assert-TextMatch $productionCompose 'WHATSAPP_GATEWAY_AUTH_REQUIRED:\s*\$\{WHATSAPP_GATEWAY_AUTH_REQUIRED:-true\}' 'Production WhatsApp gateway authentication must fail closed.'
Assert-TextMatch $localCompose 'EXTERNAL_REVIEW_WORKER_AUTH_REQUIRED:\s*\$\{EXTERNAL_REVIEW_WORKER_AUTH_REQUIRED:-false\}' 'Prod-local external-review authentication default must remain development-compatible.'
Assert-TextMatch $productionCompose 'BACKUP_ENABLED:\s*\$\{BACKUP_ENABLED:-false\}' 'Production backups must be opt-in until destination/encryption controls are confirmed.'
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

Assert-TextMatch $deploy 'AllowDirtyWorktree' 'Production deployment must reject dirty build inputs by default.'
Assert-TextMatch $deploy 'flock -n 9' 'Production deployment must be protected by a remote exclusive lock.'
Assert-TextMatch $deploy 'deploy_cleanup' 'Production deployment must restore timers and clean temporary bundles on failure.'
Assert-TextMatch $deploy 'BACKUP_ENCRYPTION_AT_REST_CONFIRMED' 'Production deployment must validate backup encryption readiness when backups are enabled.'
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
Assert-TextNotMatch $pinnedWorkflowSet 'actions/(checkout|upload-artifact)@v[0-9]' 'Checkout/upload CI actions must use immutable commit pins, not mutable major tags.'
Assert-TextMatch $pinnedWorkflowSet 'actions/checkout@11bd71901bbe5b1630ceea73d27597364c9af683' 'CI checkout must retain its reviewed immutable pin.'
Assert-TextMatch $maxWebhookRegistration 'https://platform-api2\.max\.ru' 'MAX webhook registration must use the current MAX API endpoint.'
Assert-TextMatch $productionProperties 'MAX_BOT_API_BASE_URL:https://platform-api2\.max\.ru' 'Backend production properties must use the current MAX API endpoint.'
Assert-TextMatch $productionProperties 'backup\.enabled=\$\{BACKUP_ENABLED:false\}' 'Backend production backup automation must fail closed outside Compose as well.'
Assert-TextMatch $backendDockerfile 'COPY docker/certs/russian_trusted_root_ca\.crt' 'The backend image must copy the pinned Russian Trusted Root CA.'
Assert-TextMatch $backendDockerfile 'D2:6D:2D:02:31:B7:C3:9F:92:CC:73:85:12:BA:54:10:35:19:E4:40:5D:68:B5:BD:70:3E:97:88:CA:8E:CF:31' 'The backend image must verify the reviewed Russian Trusted Root CA fingerprint.'
Assert-TextMatch $backendDockerfile 'update-ca-certificates' 'The backend image must import the MAX root into the OS trust store.'
Assert-TextMatch $backendDockerfile 'keytool -importcert' 'The backend image must import the MAX root into the JVM trust store.'

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
Assert-TextMatch $legacyWhatsAppDockerfile 'OTZIV_ALLOW_QUARANTINED_DOCKERFILE' 'Legacy WhatsApp Dockerfile must refuse accidental builds.'
Assert-TextMatch $legacyNginxDockerfile 'OTZIV_ALLOW_QUARANTINED_DOCKERFILE' 'Legacy Nginx Dockerfile must refuse accidental builds.'
Assert-TextMatch $legacyDeploy 'if \(-not \$AllowLegacyDeploy\)' 'Legacy direct-image deploy must refuse accidental use.'

if ($violations.Count -gt 0) {
    Write-Error ("Infrastructure contract violations:`n - " + ($violations -join "`n - "))
}

Write-Output 'Infrastructure security contracts passed.'
