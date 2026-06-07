param(
    [string]$EnvFile = ".env.prod-local",
    [string]$ComposeFile = "compose.prod-local.yaml",
    [string]$BaseUrl = "http://localhost:8088",
    [int]$TimeoutSeconds = 1200,
    [switch]$OfflineAppBuild,
    [switch]$NoOfflineFallback,
    [switch]$NoBuild,
    [switch]$NoUp,
    [switch]$NoLogs,
    [switch]$SkipOpenAiProxyIpSync,
    [switch]$UseConfiguredOutboundProxy,
    [switch]$WithDbAdmin,
    [switch]$WithObservability,
    [switch]$WithReputationAiSmoke,
    [int]$ReputationAiCompanyId = 1,
    [switch]$SkipReputationAiOpenAiRouteCheck,
    [switch]$RestoreProdDb,
    [switch]$SkipProdDbRestore,
    [string]$VpsHost = "95.213.248.152",
    [string]$VpsUser = "root",
    [int]$VpsPort = 22,
    [string]$SshKey = "C:\Users\Hunt\.ssh\otziv_vps_ed25519",
    [switch]$AllowLocalMessengerSending
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Invoke-External {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [Parameter(Mandatory = $true)][string[]]$Arguments
    )

    & $FilePath @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Command failed: $FilePath $($Arguments -join ' ')"
    }
}

function Get-EnvValue {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Name
    )

    $found = $null
    foreach ($line in Get-Content -LiteralPath $Path) {
        $trimmed = $line.Trim()
        if ($trimmed.Length -eq 0 -or $trimmed.StartsWith("#")) {
            continue
        }

        $separator = $trimmed.IndexOf("=")
        if ($separator -le 0) {
            continue
        }

        if ($trimmed.Substring(0, $separator).Trim() -eq $Name) {
            $value = $trimmed.Substring($separator + 1).Trim()
            if (-not [string]::IsNullOrWhiteSpace($value)) {
                $found = $value
            }
        }
    }

    return $found
}

function ConvertTo-SmokeArray {
    param([AllowNull()][object]$Value)

    if ($null -eq $Value) {
        return @()
    }
    if ($Value -is [array]) {
        return $Value
    }

    return @($Value)
}

function Wait-HttpOk {
    param(
        [Parameter(Mandatory = $true)][string]$Url,
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][datetime]$Deadline
    )

    Write-Host "Waiting for ${Name}: $Url"
    while ((Get-Date) -lt $Deadline) {
        try {
            $response = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 5
            if ($response.StatusCode -ge 200 -and $response.StatusCode -lt 400) {
                Write-Host "$Name is OK ($($response.StatusCode))."
                return
            }
        } catch {
            Start-Sleep -Seconds 5
            continue
        }

        Start-Sleep -Seconds 5
    }

    throw "Timed out waiting for $Name at $Url"
}

function Wait-ComposeServiceHealthy {
    param(
        [Parameter(Mandatory = $true)][string[]]$ComposeArguments,
        [Parameter(Mandatory = $true)][string]$Service,
        [int]$TimeoutSeconds = 180
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $containerId = & docker @($ComposeArguments + @("ps", "-q", $Service))
        if ($LASTEXITCODE -eq 0 -and -not [string]::IsNullOrWhiteSpace($containerId)) {
            $health = & docker @("inspect", "--format", "{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}", $containerId.Trim())
            if ($LASTEXITCODE -eq 0 -and $health.Trim() -eq "healthy") {
                return
            }
        }

        Start-Sleep -Seconds 3
    } while ((Get-Date) -lt $deadline)

    throw "Service '$Service' did not become healthy within $TimeoutSeconds seconds."
}

function Assert-FrontendShellRoute {
    param(
        [Parameter(Mandatory = $true)][string]$BaseUrl,
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Name
    )

    $root = $BaseUrl.TrimEnd("/")
    $routePath = if ($Path.StartsWith("/")) { $Path } else { "/$Path" }
    $url = "$root$routePath"
    $response = Invoke-WebRequest -Uri $url -UseBasicParsing -TimeoutSec 30
    $content = [string]$response.Content
    if ($response.StatusCode -ne 200 -or -not $content.Contains("app-root")) {
        throw "Frontend route failed for ${Name}: $url returned HTTP $($response.StatusCode)."
    }

    Write-Host "Frontend route OK: $routePath ($Name)."
}

function Invoke-PublicFrontendSmoke {
    param([Parameter(Mandatory = $true)][string]$BaseUrl)

    Write-Host "Running public frontend route smoke..."
    $routes = @(
        @{ Path = "/"; Name = "home" },
        @{ Path = "/services"; Name = "services" },
        @{ Path = "/prices"; Name = "prices" },
        @{ Path = "/payment"; Name = "payment" },
        @{ Path = "/refund"; Name = "refund" },
        @{ Path = "/offer"; Name = "offer" },
        @{ Path = "/privacy"; Name = "privacy" },
        @{ Path = "/contacts"; Name = "contacts" },
        @{ Path = "/receipt-consent"; Name = "receipt consent" },
        @{ Path = "/pay"; Name = "pay form" },
        @{ Path = "/pay/success"; Name = "payment success" },
        @{ Path = "/pay/fail"; Name = "payment fail" },
        @{ Path = "/pay/demo-token"; Name = "tokenized pay form" },
        @{ Path = "/uslugi"; Name = "services redirect alias" },
        @{ Path = "/oplata"; Name = "payment redirect alias" }
    )

    foreach ($route in $routes) {
        Assert-FrontendShellRoute -BaseUrl $BaseUrl -Path $route.Path -Name $route.Name
    }
}

function Convert-EnvBool {
    param(
        [AllowNull()][string]$Value,
        [Parameter(Mandatory = $true)][bool]$Default
    )

    if ([string]::IsNullOrWhiteSpace($Value)) {
        return $Default
    }

    return $Value.Equals("true", [System.StringComparison]::OrdinalIgnoreCase)
}

function Invoke-TbankPaymentConfigSmoke {
    param(
        [Parameter(Mandatory = $true)][string]$BaseUrl,
        [Parameter(Mandatory = $true)][string]$EnvPath
    )

    Write-Host "Running T-Bank payment config smoke..."
    $url = "$($BaseUrl.TrimEnd('/'))/api/payments/public/tbank-status"
    $status = Invoke-RestMethod -Uri $url -TimeoutSec 20

    $expectedEnabled = $false
    $expectedPaymentLinks = $false
    $expectedManagerUi = $false
    $expectedApplyConfirmed = $false
    $expectedBaseUrl = Get-EnvValue -Path $EnvPath -Name "OTZIV_PAYMENTS_TBANK_BASE_URL"
    $expectedRuntimeMode = "TEST"

    if ($status.enabled -ne $expectedEnabled) {
        throw "T-Bank enabled flag mismatch: expected $expectedEnabled, got $($status.enabled)."
    }
    if ($status.paymentLinksEnabled -ne $expectedPaymentLinks) {
        throw "T-Bank payment links flag mismatch: expected $expectedPaymentLinks, got $($status.paymentLinksEnabled)."
    }
    if ($status.managerUiEnabled -ne $expectedManagerUi) {
        throw "T-Bank manager UI flag mismatch: expected $expectedManagerUi, got $($status.managerUiEnabled)."
    }
    if ($status.applyConfirmedPayments -ne $expectedApplyConfirmed) {
        throw "T-Bank apply-confirmed flag mismatch: expected $expectedApplyConfirmed, got $($status.applyConfirmedPayments)."
    }
    if ($status.runtimeMode -notin @("TEST", "LIVE")) {
        throw "T-Bank runtime mode must be TEST or LIVE, got '$($status.runtimeMode)'."
    }
    if (-not [string]::IsNullOrWhiteSpace($expectedRuntimeMode) -and $status.runtimeMode -ne $expectedRuntimeMode.Trim().ToUpperInvariant()) {
        throw "T-Bank runtime mode mismatch: expected $expectedRuntimeMode, got $($status.runtimeMode)."
    }
    if (($status.runtimeMode -eq "TEST") -ne [bool]$status.testMode) {
        throw "T-Bank testMode flag mismatch for runtime $($status.runtimeMode): got $($status.testMode)."
    }
    if (-not [string]::IsNullOrWhiteSpace($expectedBaseUrl) -and $status.baseUrl -ne $expectedBaseUrl.TrimEnd("/")) {
        throw "T-Bank base URL mismatch: expected $expectedBaseUrl, got $($status.baseUrl)."
    }

    Write-Host "T-Bank config OK: runtime=$($status.runtimeMode), enabled=$($status.enabled), paymentLinks=$($status.paymentLinksEnabled), managerUi=$($status.managerUiEnabled), applyConfirmed=$($status.applyConfirmedPayments), baseUrl=$($status.baseUrl)."
}

function Disable-LocalExternalMessaging {
    param(
        [Parameter(Mandatory = $true)][string[]]$ComposeArguments,
        [Parameter(Mandatory = $true)][string]$EnvPath
    )

    $mysqlUser = Get-EnvValue -Path $EnvPath -Name "MYSQL_USER"
    $mysqlPassword = Get-EnvValue -Path $EnvPath -Name "MYSQL_PASSWORD"
    $mysqlDatabase = Get-EnvValue -Path $EnvPath -Name "MYSQL_DATABASE"
    if ([string]::IsNullOrWhiteSpace($mysqlUser) -or [string]::IsNullOrWhiteSpace($mysqlPassword) -or [string]::IsNullOrWhiteSpace($mysqlDatabase)) {
        throw "MYSQL_USER, MYSQL_PASSWORD, and MYSQL_DATABASE must be set to force local autoresponder dry-run mode."
    }

    $tableCheckOutput = & docker @($ComposeArguments + @(
        "exec", "-T", "-e", "MYSQL_PWD=$mysqlPassword", "mysql",
        "mysql",
        "--default-character-set=utf8mb4",
        "-u$mysqlUser",
        $mysqlDatabase,
        "-N", "-B",
        "-e",
        "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'app_settings'"
    )) 2>&1
    if ($LASTEXITCODE -ne 0) {
        $text = ($tableCheckOutput | ForEach-Object { $_.ToString() }) -join [Environment]::NewLine
        throw "Could not inspect local app_settings table: $text"
    }
    $tableExists = @($tableCheckOutput | ForEach-Object { $_.ToString().Trim() } | Where-Object { $_ -match "^[0-9]+$" } | Select-Object -First 1)
    if ($tableExists.Count -eq 0 -or $tableExists[0] -ne "1") {
        Write-Host "Local app_settings table is not migrated yet; messenger safety relies on env overrides until backend creates it."
        return
    }

    $sql = @"
INSERT INTO app_settings (setting_key, setting_value, updated_at)
VALUES
  ('client.messages.live.enabled', 'false', NOW(6)),
  ('client.messages.payment-overdue.live-enabled', 'false', NOW(6)),
  ('client.messages.immediate.enabled', 'false', NOW(6)),
  ('client.messages.monitor.enabled', 'false', NOW(6)),
  ('publication.health-monitor.enabled', 'false', NOW(6)),
  ('telegram.reports.morning.enabled', 'false', NOW(6)),
  ('telegram.reports.evening.enabled', 'false', NOW(6)),
  ('whatsapp.group-sync.enabled', 'false', NOW(6)),
  ('archive.orders.schedule.worker.enabled', 'false', NOW(6)),
  ('archive.orders.schedule.enabled', 'false', NOW(6)),
  ('archive.orders.apply.enabled', 'false', NOW(6)),
  ('archive.orders.run.mode', 'dry-run', NOW(6)),
  ('payment.links.archive.enabled', 'false', NOW(6)),
  ('payments.tbank.runtime-mode', 'TEST', NOW(6)),
  ('payments.tbank.enabled', 'false', NOW(6)),
  ('payments.tbank.payment-links-enabled', 'false', NOW(6)),
  ('payments.tbank.manager-ui-enabled', 'false', NOW(6)),
  ('payments.tbank.apply-confirmed-payments', 'false', NOW(6)),
  ('payments.tbank.tpay-enabled', 'false', NOW(6)),
  ('payments.tbank.sberpay-enabled', 'false', NOW(6)),
  ('payments.tbank.mirpay-enabled', 'false', NOW(6)),
  ('client.messages.payment-instruction-source', 'MANAGER_TEXT', NOW(6))
ON DUPLICATE KEY UPDATE setting_value = VALUES(setting_value), updated_at = VALUES(updated_at);

SELECT setting_key, setting_value
FROM app_settings
WHERE setting_key IN (
  'client.messages.live.enabled',
  'client.messages.payment-overdue.live-enabled',
  'client.messages.immediate.enabled',
  'client.messages.monitor.enabled',
  'publication.health-monitor.enabled',
  'telegram.reports.morning.enabled',
  'telegram.reports.evening.enabled',
  'whatsapp.group-sync.enabled',
  'archive.orders.schedule.worker.enabled',
  'archive.orders.schedule.enabled',
  'archive.orders.apply.enabled',
  'archive.orders.run.mode',
  'payment.links.archive.enabled',
  'payments.tbank.runtime-mode',
  'payments.tbank.enabled',
  'payments.tbank.payment-links-enabled',
  'payments.tbank.manager-ui-enabled',
  'payments.tbank.apply-confirmed-payments',
  'payments.tbank.tpay-enabled',
  'payments.tbank.sberpay-enabled',
  'payments.tbank.mirpay-enabled',
  'client.messages.payment-instruction-source'
)
ORDER BY setting_key;
"@

    $mysqlArgs = $ComposeArguments + @(
        "exec", "-T", "-e", "MYSQL_PWD=$mysqlPassword", "mysql",
        "mysql",
        "--default-character-set=utf8mb4",
        "-u$mysqlUser",
        $mysqlDatabase,
        "-e",
        $sql
    )
    $output = & docker @mysqlArgs 2>&1
    if ($LASTEXITCODE -ne 0) {
        $text = ($output | ForEach-Object { $_.ToString() }) -join [Environment]::NewLine
        throw "Could not force local external messaging safety mode: $text"
    }

    Write-Host "Local external messaging is disabled for this prod-like stack."
}

function Disable-LocalMessengerEnv {
    $env:TELEGRAM_BOT_TOKEN_LOCAL_DOCKER = ""
    $env:TELEGRAM_BOT_TOKEN = ""
    $env:TELEGRAM_BOT_REGISTRATION_ENABLED = "false"
    $env:MAX_BOT_TOKEN = ""
    $env:MAX_BOT_WEBHOOK_AUTO_REGISTER_ENABLED = "false"
    $env:MAX_BOT_LONG_POLLING_ENABLED = "false"
    $env:WHATSAPP_HEALTH_MONITOR_ENABLED = "false"
    $env:WHATSAPP_HEALTH_MONITOR_RESTART_ENABLED = "false"
    Write-Host "Local messenger tokens and bot registration are disabled for this prod-like stack."
}

function Get-KeycloakServiceAccountToken {
    param(
        [Parameter(Mandatory = $true)][string]$RootUrl,
        [Parameter(Mandatory = $true)][string]$EnvPath
    )

    $realm = Get-EnvValue -Path $EnvPath -Name "KEYCLOAK_ADMIN_REALM"
    if ([string]::IsNullOrWhiteSpace($realm)) {
        $realm = "otziv"
    }
    $clientId = Get-EnvValue -Path $EnvPath -Name "KEYCLOAK_ADMIN_CLIENT_ID"
    if ([string]::IsNullOrWhiteSpace($clientId)) {
        $clientId = "otziv-backend"
    }
    $clientSecret = Get-EnvValue -Path $EnvPath -Name "KEYCLOAK_ADMIN_CLIENT_SECRET"
    if ([string]::IsNullOrWhiteSpace($clientSecret)) {
        throw "KEYCLOAK_ADMIN_CLIENT_SECRET must be set for reputation AI smoke."
    }

    $tokenUrl = "$($RootUrl.TrimEnd('/'))/keycloak/realms/$realm/protocol/openid-connect/token"
    $body = @{
        grant_type = "client_credentials"
        client_id = $clientId
        client_secret = $clientSecret
    }
    $response = Invoke-RestMethod -Uri $tokenUrl -Method Post -Body $body -ContentType "application/x-www-form-urlencoded" -TimeoutSec 20
    if ([string]::IsNullOrWhiteSpace($response.access_token)) {
        throw "Keycloak did not return a service account token."
    }

    return $response.access_token
}

function Get-KeycloakRealm {
    param([Parameter(Mandatory = $true)][string]$EnvPath)

    $realm = Get-EnvValue -Path $EnvPath -Name "KEYCLOAK_ADMIN_REALM"
    if ([string]::IsNullOrWhiteSpace($realm)) {
        return "otziv"
    }

    return $realm
}

function Get-KeycloakAdminToken {
    param(
        [Parameter(Mandatory = $true)][string]$RootUrl,
        [Parameter(Mandatory = $true)][string]$EnvPath
    )

    $adminUser = Get-EnvValue -Path $EnvPath -Name "KEYCLOAK_ADMIN"
    $adminPassword = Get-EnvValue -Path $EnvPath -Name "KEYCLOAK_ADMIN_PASSWORD"
    if ([string]::IsNullOrWhiteSpace($adminUser) -or [string]::IsNullOrWhiteSpace($adminPassword)) {
        throw "KEYCLOAK_ADMIN and KEYCLOAK_ADMIN_PASSWORD must be set for reputation AI role smoke."
    }

    $tokenUrl = "$($RootUrl.TrimEnd('/'))/keycloak/realms/master/protocol/openid-connect/token"
    $body = @{
        grant_type = "password"
        client_id = "admin-cli"
        username = $adminUser
        password = $adminPassword
    }
    $response = Invoke-RestMethod -Uri $tokenUrl -Method Post -Body $body -ContentType "application/x-www-form-urlencoded" -TimeoutSec 20
    if ([string]::IsNullOrWhiteSpace($response.access_token)) {
        throw "Keycloak did not return an admin token."
    }

    return $response.access_token
}

function Invoke-KeycloakAdminCli {
    param(
        [Parameter(Mandatory = $true)][string[]]$ComposeArguments,
        [Parameter(Mandatory = $true)][string[]]$Arguments
    )

    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $output = & docker @($ComposeArguments + @("exec", "-T", "keycloak", "/opt/keycloak/bin/kcadm.sh") + $Arguments) 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }

    if ($exitCode -ne 0) {
        $text = ($output | ForEach-Object { $_.ToString() }) -join [Environment]::NewLine
        throw "kcadm command failed: $($Arguments -join ' '): $text"
    }

    return @($output | ForEach-Object { $_.ToString() })
}

function Get-KeycloakLoopbackBaseUrls {
    param([Parameter(Mandatory = $true)][string[]]$BaseUrls)

    $result = [System.Collections.Generic.List[string]]::new()
    foreach ($baseUrl in $BaseUrls) {
        if ([string]::IsNullOrWhiteSpace($baseUrl)) {
            continue
        }

        $normalized = $baseUrl.TrimEnd("/")
        if (-not $result.Contains($normalized)) {
            [void]$result.Add($normalized)
        }

        try {
            $uri = [Uri]$normalized
        } catch {
            continue
        }

        $uriHost = $uri.Host.ToLowerInvariant()
        $alternateHost = if ($uriHost -eq "localhost") {
            "127.0.0.1"
        } elseif ($uriHost -eq "127.0.0.1") {
            "localhost"
        } else {
            $null
        }

        if ([string]::IsNullOrWhiteSpace($alternateHost)) {
            continue
        }

        $port = if ($uri.IsDefaultPort) { "" } else { ":$($uri.Port)" }
        $path = $uri.AbsolutePath.TrimEnd("/")
        if ($path -eq "/") {
            $path = ""
        }

        $alternateUrl = "$($uri.Scheme)://$alternateHost$port$path"
        if (-not $result.Contains($alternateUrl)) {
            [void]$result.Add($alternateUrl)
        }
    }

    return $result.ToArray()
}

function Update-KeycloakFrontendLoopbackRedirects {
    param(
        [Parameter(Mandatory = $true)][string[]]$ComposeArguments,
        [Parameter(Mandatory = $true)][string]$EnvPath,
        [Parameter(Mandatory = $true)][string]$BaseUrl
    )

    $realm = Get-KeycloakRealm -EnvPath $EnvPath
    $adminUser = Get-EnvValue -Path $EnvPath -Name "KEYCLOAK_ADMIN"
    $adminPassword = Get-EnvValue -Path $EnvPath -Name "KEYCLOAK_ADMIN_PASSWORD"
    if ([string]::IsNullOrWhiteSpace($adminUser) -or [string]::IsNullOrWhiteSpace($adminPassword)) {
        throw "KEYCLOAK_ADMIN and KEYCLOAK_ADMIN_PASSWORD must be set for local Keycloak client sync."
    }

    for ($attempt = 1; $attempt -le 30; $attempt++) {
        try {
            Invoke-KeycloakAdminCli -ComposeArguments $ComposeArguments -Arguments @(
                "config", "credentials",
                "--server", "http://localhost:8080/keycloak",
                "--realm", "master",
                "--user", $adminUser,
                "--password", $adminPassword
            ) | Out-Null
            break
        } catch {
            if ($attempt -eq 30) {
                throw
            }
            Start-Sleep -Seconds 2
        }
    }

    $clientLines = Invoke-KeycloakAdminCli -ComposeArguments $ComposeArguments -Arguments @(
        "get", "clients",
        "-r", $realm,
        "-q", "clientId=otziv-frontend",
        "--fields", "id",
        "--format", "csv",
        "--noquotes"
    )
    $frontendClientUuid = ($clientLines |
        ForEach-Object { $_.Trim() } |
        Where-Object { -not [string]::IsNullOrWhiteSpace($_) -and $_ -ne "id" } |
        Select-Object -Last 1)

    if ([string]::IsNullOrWhiteSpace($frontendClientUuid)) {
        Write-Warning "Keycloak frontend client otziv-frontend was not found; skipping local redirect sync."
        return
    }

    $appBaseUrl = Get-EnvValue -Path $EnvPath -Name "OTZIV_APP_BASE_URL"
    if ([string]::IsNullOrWhiteSpace($appBaseUrl)) {
        $appBaseUrl = $BaseUrl
    }

    $baseUrls = Get-KeycloakLoopbackBaseUrls -BaseUrls @($appBaseUrl, $BaseUrl)
    $redirectUris = @($baseUrls | ForEach-Object { "$_/*" })
    $logoutRedirectUris = $redirectUris -join "##"

    Write-Host "Applying Keycloak frontend origins: $($baseUrls -join ', ')."
    $adminToken = Get-KeycloakAdminToken -RootUrl $BaseUrl -EnvPath $EnvPath
    $adminHeaders = @{ Authorization = "Bearer $adminToken" }
    $apiRoot = "$($BaseUrl.TrimEnd('/'))/keycloak/admin/realms/$realm"
    $client = Invoke-RestMethod -Uri "$apiRoot/clients/$frontendClientUuid" -Headers $adminHeaders -TimeoutSec 30
    $attributes = @{}
    if ($null -ne $client.attributes) {
        foreach ($property in $client.attributes.PSObject.Properties) {
            $attributes[$property.Name] = $property.Value
        }
    }

    $attributes["pkce.code.challenge.method"] = "S256"
    $attributes["post.logout.redirect.uris"] = $logoutRedirectUris
    $client.redirectUris = $redirectUris
    $client.webOrigins = $baseUrls
    $client.attributes = $attributes

    $body = $client | ConvertTo-Json -Depth 20
    Invoke-RestMethod -Uri "$apiRoot/clients/$frontendClientUuid" -Method Put -Headers $adminHeaders -Body $body -ContentType "application/json" -TimeoutSec 30 | Out-Null
}

function Get-KeycloakClientCredentialsToken {
    param(
        [Parameter(Mandatory = $true)][string]$RootUrl,
        [Parameter(Mandatory = $true)][string]$Realm,
        [Parameter(Mandatory = $true)][string]$ClientId,
        [Parameter(Mandatory = $true)][string]$ClientSecret
    )

    $tokenUrl = "$($RootUrl.TrimEnd('/'))/keycloak/realms/$Realm/protocol/openid-connect/token"
    $body = @{
        grant_type = "client_credentials"
        client_id = $ClientId
        client_secret = $ClientSecret
    }
    $response = Invoke-RestMethod -Uri $tokenUrl -Method Post -Body $body -ContentType "application/x-www-form-urlencoded" -TimeoutSec 20
    if ([string]::IsNullOrWhiteSpace($response.access_token)) {
        throw "Keycloak did not return a token for smoke client $ClientId."
    }

    return $response.access_token
}

function New-KeycloakSmokeClient {
    param(
        [Parameter(Mandatory = $true)][string]$RootUrl,
        [Parameter(Mandatory = $true)][string]$Realm,
        [Parameter(Mandatory = $true)][hashtable]$AdminHeaders,
        [Parameter(Mandatory = $true)][string]$Role
    )

    $apiRoot = "$($RootUrl.TrimEnd('/'))/keycloak/admin/realms/$Realm"
    $roleKey = $Role.ToLowerInvariant()
    $clientId = "otziv-smoke-ai-$roleKey-$([guid]::NewGuid().ToString("N").Substring(0, 12))"
    $clientBody = @{
        clientId = $clientId
        name = "Reputation AI smoke $Role"
        enabled = $true
        protocol = "openid-connect"
        publicClient = $false
        bearerOnly = $false
        standardFlowEnabled = $false
        implicitFlowEnabled = $false
        directAccessGrantsEnabled = $false
        serviceAccountsEnabled = $true
        protocolMappers = @(
            @{
                name = "realm roles"
                protocol = "openid-connect"
                protocolMapper = "oidc-usermodel-realm-role-mapper"
                consentRequired = $false
                config = @{
                    "multivalued" = "true"
                    "userinfo.token.claim" = "true"
                    "id.token.claim" = "true"
                    "access.token.claim" = "true"
                    "claim.name" = "roles"
                    "jsonType.label" = "String"
                }
            },
            @{
                name = "backend audience"
                protocol = "openid-connect"
                protocolMapper = "oidc-audience-mapper"
                consentRequired = $false
                config = @{
                    "included.client.audience" = "otziv-backend"
                    "id.token.claim" = "false"
                    "access.token.claim" = "true"
                }
            }
        )
    } | ConvertTo-Json -Depth 12

    Invoke-RestMethod -Uri "$apiRoot/clients" -Method Post -Headers $AdminHeaders -Body $clientBody -ContentType "application/json" -TimeoutSec 30 | Out-Null
    $clientResponse = Invoke-RestMethod -Uri "$apiRoot/clients?clientId=$([Uri]::EscapeDataString($clientId))" -Headers $AdminHeaders -TimeoutSec 30
    $clients = @(ConvertTo-SmokeArray -Value $clientResponse)
    if ($clients.Count -eq 0 -or [string]::IsNullOrWhiteSpace($clients[0].id)) {
        throw "Keycloak smoke client was not created: $clientId"
    }

    $clientUuid = $clients[0].id
    $secret = Invoke-RestMethod -Uri "$apiRoot/clients/$clientUuid/client-secret" -Headers $AdminHeaders -TimeoutSec 30
    if ([string]::IsNullOrWhiteSpace($secret.value)) {
        throw "Keycloak did not return a secret for smoke client $clientId."
    }

    $serviceAccount = Invoke-RestMethod -Uri "$apiRoot/clients/$clientUuid/service-account-user" -Headers $AdminHeaders -TimeoutSec 30
    if ([string]::IsNullOrWhiteSpace($serviceAccount.id)) {
        throw "Keycloak did not return a service account user for smoke client $clientId."
    }

    $realmRole = Invoke-RestMethod -Uri "$apiRoot/roles/$([Uri]::EscapeDataString($Role))" -Headers $AdminHeaders -TimeoutSec 30
    $roleBody = ConvertTo-Json -InputObject @(@{
        id = $realmRole.id
        name = $realmRole.name
        composite = $realmRole.composite
        clientRole = $realmRole.clientRole
        containerId = $realmRole.containerId
    }) -Depth 8
    Invoke-RestMethod -Uri "$apiRoot/users/$($serviceAccount.id)/role-mappings/realm" -Method Post -Headers $AdminHeaders -Body $roleBody -ContentType "application/json" -TimeoutSec 30 | Out-Null

    return [pscustomobject]@{
        ClientId = $clientId
        ClientUuid = $clientUuid
        ClientSecret = $secret.value
        Role = $Role
    }
}

function Remove-KeycloakSmokeClient {
    param(
        [Parameter(Mandatory = $true)][string]$RootUrl,
        [Parameter(Mandatory = $true)][string]$Realm,
        [Parameter(Mandatory = $true)][hashtable]$AdminHeaders,
        [Parameter(Mandatory = $true)][object]$Client
    )

    if ($null -eq $Client -or [string]::IsNullOrWhiteSpace($Client.ClientUuid)) {
        return
    }

    try {
        $apiRoot = "$($RootUrl.TrimEnd('/'))/keycloak/admin/realms/$Realm"
        Invoke-RestMethod -Uri "$apiRoot/clients/$($Client.ClientUuid)" -Method Delete -Headers $AdminHeaders -TimeoutSec 30 | Out-Null
    } catch {
        Write-Warning "Could not remove Keycloak smoke client $($Client.ClientId): $($_.Exception.Message)"
    }
}

function Remove-KeycloakSmokeClientsByPrefix {
    param(
        [Parameter(Mandatory = $true)][string]$RootUrl,
        [Parameter(Mandatory = $true)][string]$Realm,
        [Parameter(Mandatory = $true)][hashtable]$AdminHeaders
    )

    $apiRoot = "$($RootUrl.TrimEnd('/'))/keycloak/admin/realms/$Realm"
    $clientResponse = Invoke-RestMethod -Uri "$apiRoot/clients?clientId=otziv-smoke-ai" -Headers $AdminHeaders -TimeoutSec 30
    $clients = @(ConvertTo-SmokeArray -Value $clientResponse) |
        Where-Object { $_.clientId -like "otziv-smoke-ai-*" }
    foreach ($client in $clients) {
        Remove-KeycloakSmokeClient -RootUrl $RootUrl -Realm $Realm -AdminHeaders $AdminHeaders -Client ([pscustomobject]@{
            ClientId = $client.clientId
            ClientUuid = $client.id
        })
    }
}

function Invoke-SmokeWebRequest {
    param(
        [Parameter(Mandatory = $true)][string]$Uri,
        [Parameter(Mandatory = $true)][string]$Method,
        [Parameter(Mandatory = $true)][hashtable]$Headers,
        [string]$Body,
        [string]$ContentType
    )

    try {
        $request = @{
            Uri = $Uri
            Method = $Method
            Headers = $Headers
            UseBasicParsing = $true
            TimeoutSec = 30
        }
        if ($PSBoundParameters.ContainsKey("Body")) {
            $request.Body = $Body
        }
        if (-not [string]::IsNullOrWhiteSpace($ContentType)) {
            $request.ContentType = $ContentType
        }

        $response = Invoke-WebRequest @request
        return [pscustomobject]@{
            StatusCode = [int]$response.StatusCode
            Content = [string]$response.Content
            Headers = $response.Headers
        }
    } catch {
        $response = $_.Exception.Response
        if ($null -eq $response) {
            throw
        }

        return [pscustomobject]@{
            StatusCode = [int]$response.StatusCode
            Content = ""
            Headers = $response.Headers
        }
    }
}

function Assert-ReputationAiPromptRendering {
    param(
        [Parameter(Mandatory = $true)][string]$ApiRoot,
        [Parameter(Mandatory = $true)][hashtable]$Headers,
        [Parameter(Mandatory = $true)][string]$PromptKey,
        [Parameter(Mandatory = $true)][string]$Content
    )

    $encodedKey = [Uri]::EscapeDataString($PromptKey)
    $body = @{ content = $Content } | ConvertTo-Json -Compress
    $validation = Invoke-RestMethod -Uri "$ApiRoot/api/ai/reputation/prompts/$encodedKey/validate" -Method Post -Headers $Headers -Body $body -ContentType "application/json" -TimeoutSec 30
    if (-not $validation.valid) {
        throw "Reputation AI prompt validation failed for ${PromptKey}: missing=$($validation.missingPlaceholders -join ', ')."
    }

    $preview = Invoke-RestMethod -Uri "$ApiRoot/api/ai/reputation/prompts/$encodedKey/preview" -Method Post -Headers $Headers -Body $body -ContentType "application/json" -TimeoutSec 30
    if ([string]::IsNullOrWhiteSpace($preview.renderedContent)) {
        throw "Reputation AI prompt preview did not render content for $PromptKey."
    }
    if ($preview.renderedContent -match "\{\{[^}]+\}\}") {
        throw "Reputation AI prompt preview left unresolved placeholders for ${PromptKey}: $($preview.unresolvedPlaceholders -join ', ')."
    }
}

function Assert-ReputationAiPdfExport {
    param(
        [Parameter(Mandatory = $true)][string]$Uri,
        [Parameter(Mandatory = $true)][hashtable]$Headers,
        [Parameter(Mandatory = $true)][string]$Label
    )

    $tempFile = New-TemporaryFile
    try {
        $response = Invoke-WebRequest -Uri $Uri -Method Get -Headers $Headers -UseBasicParsing -TimeoutSec 45 -OutFile $tempFile.FullName -PassThru
        $bytes = [System.IO.File]::ReadAllBytes($tempFile.FullName)
        $signature = if ($bytes.Length -ge 4) { [System.Text.Encoding]::ASCII.GetString($bytes, 0, 4) } else { "" }
        $contentType = [string]::Join(" ", @($response.Headers["Content-Type"]))
        $contentDisposition = [string]::Join(" ", @($response.Headers["Content-Disposition"]))
        if ($response.StatusCode -ne 200 -or $bytes.Length -lt 1000 -or $signature -ne "%PDF" -or $contentType -notmatch "application/pdf" -or $contentDisposition -notmatch "\.pdf") {
            throw "Reputation AI $Label PDF export failed: HTTP $($response.StatusCode), length=$($bytes.Length), signature=$signature, contentType=$contentType, contentDisposition=$contentDisposition."
        }

        Write-Host "Reputation AI $Label PDF export OK: $($bytes.Length) bytes."
    } finally {
        Remove-Item -LiteralPath $tempFile.FullName -Force -ErrorAction SilentlyContinue
    }
}

function Invoke-ReputationAiPromptPresetSmoke {
    param(
        [Parameter(Mandatory = $true)][string]$ApiRoot,
        [Parameter(Mandatory = $true)][hashtable]$Headers,
        [Parameter(Mandatory = $true)][string]$PromptKey,
        [Parameter(Mandatory = $true)][string]$PresetKey
    )

    $encodedPromptKey = [Uri]::EscapeDataString($PromptKey)
    $encodedPresetKey = [Uri]::EscapeDataString($PresetKey)
    $promptResponse = Invoke-RestMethod -Uri "$ApiRoot/api/ai/reputation/prompts" -Headers $Headers -TimeoutSec 30
    $originalPrompt = @(ConvertTo-SmokeArray -Value $promptResponse) |
        Where-Object { $_.key -eq $PromptKey } |
        Select-Object -First 1
    if ($null -eq $originalPrompt) {
        throw "Reputation AI prompt was not found for preset smoke: $PromptKey."
    }

    $originalContent = [string]$originalPrompt.content
    $originalCustomized = [bool]$originalPrompt.customized
    $applied = $false
    try {
        $presetPrompt = Invoke-RestMethod -Uri "$ApiRoot/api/ai/reputation/prompts/$encodedPromptKey/presets/$encodedPresetKey" -Method Post -Headers $Headers -Body "{}" -ContentType "application/json" -TimeoutSec 30
        $applied = $true
        if ($presetPrompt.key -ne $PromptKey -or -not $presetPrompt.customized) {
            throw "Reputation AI prompt preset did not return a customized $PromptKey prompt."
        }
        if ($null -eq $presetPrompt.presets -or $presetPrompt.presets.Count -lt 3) {
            throw "Reputation AI prompt preset response did not include preset metadata."
        }

        Assert-ReputationAiPromptRendering -ApiRoot $ApiRoot -Headers $Headers -PromptKey $PromptKey -Content $presetPrompt.content

        $history = @(Invoke-RestMethod -Uri "$ApiRoot/api/ai/reputation/prompts/$encodedPromptKey/history?limit=5" -Headers $Headers -TimeoutSec 30)
        $presetHistory = $history | Where-Object { $_.action -eq "preset:$PresetKey" } | Select-Object -First 1
        if ($null -eq $presetHistory) {
            throw "Reputation AI prompt history did not include preset:$PresetKey for $PromptKey."
        }

        Write-Host "Reputation AI prompt preset OK: $PromptKey -> $PresetKey."
    } finally {
        if ($applied) {
            if ($originalCustomized) {
                $restoreBody = @{ content = $originalContent } | ConvertTo-Json -Compress
                Invoke-RestMethod -Uri "$ApiRoot/api/ai/reputation/prompts/$encodedPromptKey" -Method Put -Headers $Headers -Body $restoreBody -ContentType "application/json" -TimeoutSec 30 | Out-Null
            } else {
                Invoke-RestMethod -Uri "$ApiRoot/api/ai/reputation/prompts/$encodedPromptKey" -Method Delete -Headers $Headers -TimeoutSec 30 | Out-Null
            }

            $restoredResponse = Invoke-RestMethod -Uri "$ApiRoot/api/ai/reputation/prompts" -Headers $Headers -TimeoutSec 30
            $restoredPrompt = @(ConvertTo-SmokeArray -Value $restoredResponse) |
                Where-Object { $_.key -eq $PromptKey } |
                Select-Object -First 1
            if ($null -eq $restoredPrompt -or [string]$restoredPrompt.content -ne $originalContent) {
                throw "Reputation AI prompt restore failed after preset smoke for $PromptKey."
            }
        }
    }
}

function Invoke-ReputationAiRoleSmoke {
    param(
        [Parameter(Mandatory = $true)][string]$RootUrl,
        [Parameter(Mandatory = $true)][string]$EnvPath,
        [Parameter(Mandatory = $true)][string]$PromptKey,
        [Parameter(Mandatory = $true)][string]$PromptContent
    )

    $apiRoot = $RootUrl.TrimEnd("/")
    $realm = Get-KeycloakRealm -EnvPath $EnvPath
    $adminToken = Get-KeycloakAdminToken -RootUrl $RootUrl -EnvPath $EnvPath
    $adminHeaders = @{ Authorization = "Bearer $adminToken" }
    $clients = @()

    Remove-KeycloakSmokeClientsByPrefix -RootUrl $RootUrl -Realm $realm -AdminHeaders $adminHeaders
    try {
        foreach ($role in @("MANAGER", "MARKETOLOG")) {
            $client = New-KeycloakSmokeClient -RootUrl $RootUrl -Realm $realm -AdminHeaders $adminHeaders -Role $role
            $clients += $client
            $roleToken = Get-KeycloakClientCredentialsToken -RootUrl $RootUrl -Realm $realm -ClientId $client.ClientId -ClientSecret $client.ClientSecret
            $roleHeaders = @{ Authorization = "Bearer $roleToken" }

            $status = Invoke-SmokeWebRequest -Uri "$apiRoot/api/ai/reputation/status" -Method "Get" -Headers $roleHeaders
            if ($status.StatusCode -ne 200) {
                throw "Reputation AI status should be readable for $role, got HTTP $($status.StatusCode)."
            }
            $prompts = Invoke-SmokeWebRequest -Uri "$apiRoot/api/ai/reputation/prompts" -Method "Get" -Headers $roleHeaders
            if ($prompts.StatusCode -ne 200) {
                throw "Reputation AI prompts should be readable for $role, got HTTP $($prompts.StatusCode)."
            }

            $encodedPromptKey = [Uri]::EscapeDataString($PromptKey)
            $mutationBody = @{ content = $PromptContent } | ConvertTo-Json -Compress
            $presetAttempt = Invoke-SmokeWebRequest -Uri "$apiRoot/api/ai/reputation/prompts/$encodedPromptKey/presets/strict_facts" -Method "Post" -Headers $roleHeaders -Body "{}" -ContentType "application/json"
            if ($presetAttempt.StatusCode -ne 403) {
                throw "Reputation AI prompt preset should be forbidden for $role, got HTTP $($presetAttempt.StatusCode)."
            }
            $updateAttempt = Invoke-SmokeWebRequest -Uri "$apiRoot/api/ai/reputation/prompts/$encodedPromptKey" -Method "Put" -Headers $roleHeaders -Body $mutationBody -ContentType "application/json"
            if ($updateAttempt.StatusCode -ne 403) {
                throw "Reputation AI prompt update should be forbidden for $role, got HTTP $($updateAttempt.StatusCode)."
            }
            $resetAttempt = Invoke-SmokeWebRequest -Uri "$apiRoot/api/ai/reputation/prompts/$encodedPromptKey" -Method "Delete" -Headers $roleHeaders
            if ($resetAttempt.StatusCode -ne 403) {
                throw "Reputation AI prompt reset should be forbidden for $role, got HTTP $($resetAttempt.StatusCode)."
            }
        }

        Write-Host "Reputation AI role smoke OK: MANAGER/MARKETOLOG can read, prompt mutations are forbidden."
    } finally {
        foreach ($client in $clients) {
            Remove-KeycloakSmokeClient -RootUrl $RootUrl -Realm $realm -AdminHeaders $adminHeaders -Client $client
        }
        Remove-KeycloakSmokeClientsByPrefix -RootUrl $RootUrl -Realm $realm -AdminHeaders $adminHeaders
    }
}

function Invoke-ReputationAiSmoke {
    param(
        [Parameter(Mandatory = $true)][string]$RootUrl,
        [Parameter(Mandatory = $true)][string]$EnvPath,
        [Parameter(Mandatory = $true)][int]$CompanyId,
        [Parameter(Mandatory = $true)][bool]$SkipRouteCheck
    )

    Write-Host "Running reputation AI smoke..."
    $token = Get-KeycloakServiceAccountToken -RootUrl $RootUrl -EnvPath $EnvPath
    $headers = @{ Authorization = "Bearer $token" }
    $apiRoot = $RootUrl.TrimEnd("/")

    $frontendRoute = Invoke-WebRequest -Uri "$apiRoot/admin/reputation-ai" -UseBasicParsing -TimeoutSec 30
    if ($frontendRoute.StatusCode -ne 200 -or -not ([string]$frontendRoute.Content).Contains("app-root")) {
        throw "Reputation AI frontend route shell failed: HTTP $($frontendRoute.StatusCode)."
    }
    Write-Host "Reputation AI frontend route OK: /admin/reputation-ai."

    $status = Invoke-RestMethod -Uri "$apiRoot/api/ai/reputation/status" -Headers $headers -TimeoutSec 30
    if ([string]::IsNullOrWhiteSpace($status.aiProvider)) {
        throw "Reputation AI status response did not include aiProvider."
    }
    if ($null -eq $status.openAiDiagnostics) {
        throw "Reputation AI status response did not include OpenAI diagnostics."
    }
    Write-Host "Reputation AI status OK: AI=$($status.aiProvider), Search=$($status.searchProvider), Route=$($status.openAiDiagnostics.route)."
    if ($status.aiProvider -match "^(yandex|yandexgpt)$") {
        if ([string]$status.yandexModel -match "lite") {
            throw "Reputation AI smoke expected YandexGPT Pro for deep reports, got model=$($status.yandexModel)."
        }
        $deepProfiles = @(ConvertTo-SmokeArray -Value $status.openAiResearchReportProfiles)
        $maximumProfile = $deepProfiles | Where-Object { $_.key -eq "maximum" } | Select-Object -First 1
        if ($null -eq $maximumProfile) {
            throw "Reputation AI status did not include maximum deep research profile."
        }
        if ([int]$maximumProfile.maxOutputTokens -lt 20000) {
            throw "Reputation AI Yandex maximum profile is too small: maxOutputTokens=$($maximumProfile.maxOutputTokens)."
        }
        if (-not ([string]$maximumProfile.searchContextSize).StartsWith("web_search:", [System.StringComparison]::OrdinalIgnoreCase)) {
            throw "Reputation AI Yandex deep report must use Responses Web Search, got searchContextSize=$($maximumProfile.searchContextSize)."
        }
        Write-Host "Reputation AI Yandex Responses/Web Search profile OK: model=$($status.yandexModel), maxOutputTokens=$($maximumProfile.maxOutputTokens), search=$($maximumProfile.searchContextSize)."
    }

    $prompts = Invoke-RestMethod -Uri "$apiRoot/api/ai/reputation/prompts" -Headers $headers -TimeoutSec 30
    $promptItems = @(ConvertTo-SmokeArray -Value $prompts)
    if ($promptItems.Count -lt 2) {
        throw "Reputation AI prompts response did not include expected prompt templates."
    }
    $deepReportPrompt = $promptItems | Where-Object { $_.key -eq "deep_report.instructions" } | Select-Object -First 1
    if ($null -eq $deepReportPrompt -or [string]::IsNullOrWhiteSpace($deepReportPrompt.content)) {
        throw "Reputation AI prompts response did not include deep_report.instructions content."
    }
    $contentPackPrompt = $promptItems | Where-Object { $_.key -eq "content_pack.user" } | Select-Object -First 1
    if ($null -eq $contentPackPrompt -or [string]::IsNullOrWhiteSpace($contentPackPrompt.content)) {
        throw "Reputation AI prompts response did not include content_pack.user content."
    }
    if ($null -eq $contentPackPrompt.presets -or $contentPackPrompt.presets.Count -lt 3) {
        throw "Reputation AI prompt presets were not returned for content_pack.user."
    }
    foreach ($prompt in @($deepReportPrompt, $contentPackPrompt)) {
        Assert-ReputationAiPromptRendering -ApiRoot $apiRoot -Headers $headers -PromptKey $prompt.key -Content $prompt.content
    }
    $promptHistory = Invoke-RestMethod -Uri "$apiRoot/api/ai/reputation/prompts/content_pack.user/history?limit=3" -Headers $headers -TimeoutSec 30
    $promptHistoryCount = if ($null -eq $promptHistory) { 0 } elseif ($promptHistory -is [array]) { $promptHistory.Count } else { 1 }
    Write-Host "Reputation AI prompts endpoint OK: $($promptItems.Count) prompt(s), history=$promptHistoryCount."

    Invoke-ReputationAiRoleSmoke -RootUrl $RootUrl -EnvPath $EnvPath -PromptKey "content_pack.user" -PromptContent $contentPackPrompt.content
    Invoke-ReputationAiPromptPresetSmoke -ApiRoot $apiRoot -Headers $headers -PromptKey "content_pack.user" -PresetKey "strict_facts"

    $history = Invoke-RestMethod -Uri "$apiRoot/api/ai/reputation/companies/$CompanyId/deep-research/jobs/history?limit=3" -Headers $headers -TimeoutSec 30
    $historyCount = if ($null -eq $history) { 0 } elseif ($history -is [array]) { $history.Count } else { 1 }
    Write-Host "Reputation AI history endpoint OK: $historyCount item(s)."

    $hasReadyDeepReport = $false
    $readyDeepReportJobId = $null
    $latestDeepJob = Invoke-SmokeWebRequest -Uri "$apiRoot/api/ai/reputation/companies/$CompanyId/deep-research/jobs/latest" -Method "Get" -Headers $headers
    if ($latestDeepJob.StatusCode -eq 200) {
        $deepJob = $latestDeepJob.Content | ConvertFrom-Json
        if ($null -ne $deepJob.report) {
            $hasReadyDeepReport = $true
            $readyDeepReportJobId = $deepJob.jobId
            $deepExport = Invoke-SmokeWebRequest -Uri "$apiRoot/api/ai/reputation/companies/$CompanyId/deep-research/jobs/latest/export" -Method "Get" -Headers $headers
            if ($deepExport.StatusCode -ne 200 -or [string]::IsNullOrWhiteSpace($deepExport.Content) -or -not $deepExport.Content.Contains("AI-")) {
                throw "Reputation AI deep report markdown export failed: HTTP $($deepExport.StatusCode), length=$($deepExport.Content.Length)."
            }
            Write-Host "Reputation AI deep report Markdown export OK: $($deepExport.Content.Length) chars."
            Assert-ReputationAiPdfExport -Uri "$apiRoot/api/ai/reputation/companies/$CompanyId/deep-research/jobs/latest/export/pdf" -Headers $headers -Label "deep report"
        } else {
            Write-Host "Reputation AI deep report Markdown export OK: latest job has no ready report yet."
        }
    } elseif ($latestDeepJob.StatusCode -eq 404) {
        Write-Host "Reputation AI deep report Markdown export OK: no deep report job yet (404)."
    } else {
        throw "Reputation AI latest deep report job endpoint failed: HTTP $($latestDeepJob.StatusCode)."
    }

    $hasReadyContentPack = $false
    $readyContentPackJobId = $null
    $latestContentPackJob = Invoke-SmokeWebRequest -Uri "$apiRoot/api/ai/reputation/companies/$CompanyId/content-pack/jobs/latest" -Method "Get" -Headers $headers
    if ($latestContentPackJob.StatusCode -eq 200) {
        $packJob = $latestContentPackJob.Content | ConvertFrom-Json
        if ($null -ne $packJob.pack) {
            $hasReadyContentPack = $true
            $readyContentPackJobId = $packJob.jobId
            $packExport = Invoke-SmokeWebRequest -Uri "$apiRoot/api/ai/reputation/companies/$CompanyId/content-pack/jobs/latest/export" -Method "Get" -Headers $headers
            if ($packExport.StatusCode -ne 200 -or [string]::IsNullOrWhiteSpace($packExport.Content) -or -not $packExport.Content.Contains("AI-")) {
                throw "Reputation AI content pack markdown export failed: HTTP $($packExport.StatusCode), length=$($packExport.Content.Length)."
            }
            Write-Host "Reputation AI content pack Markdown export OK: $($packExport.Content.Length) chars."
            Assert-ReputationAiPdfExport -Uri "$apiRoot/api/ai/reputation/companies/$CompanyId/content-pack/jobs/latest/export/pdf" -Headers $headers -Label "content pack"
        } else {
            Write-Host "Reputation AI content pack Markdown export OK: latest job has no ready pack yet."
        }
    } elseif ($latestContentPackJob.StatusCode -eq 404) {
        Write-Host "Reputation AI content pack Markdown export OK: no content pack job yet (404)."
    } else {
        throw "Reputation AI latest content pack job endpoint failed: HTTP $($latestContentPackJob.StatusCode)."
    }

    if ($hasReadyDeepReport -and $hasReadyContentPack) {
        if ([string]$status.aiProvider -eq "local") {
            $reviewTemplateBody = @{
                deepReportJobId = $readyDeepReportJobId
                contentPackJobId = $readyContentPackJobId
                manualNotes = "Smoke: улучшить углы отзывов через факты отчета и AI-пакета."
                topicsCount = 4
                draftsCount = 3
            } | ConvertTo-Json -Compress
            $reviewTemplates = Invoke-SmokeWebRequest -Uri "$apiRoot/api/ai/reputation/companies/$CompanyId/content-pack/review-templates" -Method "Post" -Headers $headers -Body $reviewTemplateBody -ContentType "application/json"
            if ($reviewTemplates.StatusCode -ne 200 -or [string]::IsNullOrWhiteSpace($reviewTemplates.Content)) {
                throw "Reputation AI review templates endpoint failed: HTTP $($reviewTemplates.StatusCode)."
            }
            $reviewTemplateResult = $reviewTemplates.Content | ConvertFrom-Json
            if ($reviewTemplateResult.honestReviewTopics.Count -lt 1 -or $reviewTemplateResult.reviewDraftTemplates.Count -lt 1) {
                throw "Reputation AI review templates endpoint returned empty topics or drafts."
            }
            Write-Host "Reputation AI review templates endpoint OK: topics=$($reviewTemplateResult.honestReviewTopics.Count), drafts=$($reviewTemplateResult.reviewDraftTemplates.Count)."

            $singleReviewBody = @{
                deepReportJobId = $readyDeepReportJobId
                contentPackJobId = $readyContentPackJobId
                idea = "Smoke: один черновик по УТП и готовому AI-пакету."
                style = "спокойный, честный, с мягкой рекламной пользой"
                length = "short"
            } | ConvertTo-Json -Compress
            $singleReview = Invoke-SmokeWebRequest -Uri "$apiRoot/api/ai/reputation/companies/$CompanyId/content-pack/review-draft" -Method "Post" -Headers $headers -Body $singleReviewBody -ContentType "application/json"
            if ($singleReview.StatusCode -ne 200 -or [string]::IsNullOrWhiteSpace($singleReview.Content)) {
                throw "Reputation AI single review draft endpoint failed: HTTP $($singleReview.StatusCode)."
            }
            $singleReviewResult = $singleReview.Content | ConvertFrom-Json
            if ([string]::IsNullOrWhiteSpace($singleReviewResult.draft) -or [string]::IsNullOrWhiteSpace($singleReviewResult.idea)) {
                throw "Reputation AI single review draft endpoint returned an empty draft or idea."
            }
            Write-Host "Reputation AI single review draft endpoint OK: provider=$($singleReviewResult.provider), chars=$($singleReviewResult.draft.Length)."
        } else {
            Write-Host "Reputation AI review templates endpoint OK: skipped live generation for AI provider '$($status.aiProvider)'."
            Write-Host "Reputation AI single review draft endpoint OK: skipped live generation for AI provider '$($status.aiProvider)'."
        }
    } else {
        Write-Host "Reputation AI review templates endpoint OK: skipped until both latest report and content pack are ready."
        Write-Host "Reputation AI single review draft endpoint OK: skipped until both latest report and content pack are ready."
    }

    $latestResearch = Invoke-SmokeWebRequest -Uri "$apiRoot/api/ai/reputation/companies/$CompanyId/research/latest" -Method "Get" -Headers $headers
    if ($latestResearch.StatusCode -eq 200) {
        $snapshot = $latestResearch.Content | ConvertFrom-Json
        if ($null -eq $snapshot.companyId) {
            throw "Reputation AI latest research response did not include companyId."
        }
        Write-Host "Reputation AI latest research endpoint OK: source(s)=$($snapshot.sources.Count), searchResults=$($snapshot.searchResultsCount)."
    } elseif ($latestResearch.StatusCode -eq 404) {
        Write-Host "Reputation AI latest research endpoint OK: no snapshot yet (404)."
    } else {
        throw "Reputation AI latest research endpoint failed: HTTP $($latestResearch.StatusCode)."
    }

    $sectionRewriteOptions = Invoke-SmokeWebRequest -Uri "$apiRoot/api/ai/reputation/companies/$CompanyId/deep-research/jobs/rebuild-section" -Method "Options" -Headers $headers
    $allowedMethods = [string]$sectionRewriteOptions.Headers.Allow
    if ($sectionRewriteOptions.StatusCode -lt 200 -or $sectionRewriteOptions.StatusCode -ge 400 -or -not $allowedMethods.Contains("POST")) {
        throw "Reputation AI rebuild-section endpoint is not advertised as POST. HTTP=$($sectionRewriteOptions.StatusCode), Allow=$allowedMethods"
    }
    Write-Host "Reputation AI rebuild-section endpoint OK: Allow=$allowedMethods."

    if (-not $SkipRouteCheck) {
        $diagnostics = Invoke-RestMethod -Uri "$apiRoot/api/ai/reputation/status/openai-check" -Method Post -Headers $headers -TimeoutSec 45
        if ($diagnostics.configured -and $diagnostics.lastCheckStatus -ne "ok") {
            throw "OpenAI route check failed: status=$($diagnostics.lastCheckStatus), http=$($diagnostics.lastHttpStatus), message=$($diagnostics.lastMessage)"
        }
        Write-Host "OpenAI route check OK: status=$($diagnostics.lastCheckStatus), http=$($diagnostics.lastHttpStatus)."
    }
}

function Test-RegistryBuildFailure {
    param([string]$Output)

    if ([string]::IsNullOrWhiteSpace($Output)) {
        return $false
    }

    return $Output -match "registry-1\.docker\.io|docker/dockerfile|failed to resolve source metadata|Docker Desktop has no HTTPS proxy|lookup .* no such host|no such host|network is unreachable|i/o timeout|TLS handshake timeout"
}

function Invoke-DockerComposeUp {
    param(
        [Parameter(Mandatory = $true)][string[]]$ComposeArguments,
        [Parameter(Mandatory = $true)][string[]]$UpArguments
    )

    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $output = & docker @($ComposeArguments + $UpArguments) 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }

    $text = ($output | ForEach-Object { $_.ToString() }) -join [Environment]::NewLine
    if (-not [string]::IsNullOrWhiteSpace($text)) {
        Write-Host $text
    }

    [pscustomobject]@{
        ExitCode = $exitCode
        Output = $text
    }
}

function Invoke-OfflineAppBuild {
    param(
        [Parameter(Mandatory = $true)][string]$RepoRoot,
        [Parameter(Mandatory = $true)][string]$EnvPath
    )

    $backendDir = Join-Path $RepoRoot "backend"
    $runtimeDockerfile = Join-Path $backendDir "Dockerfile.runtime-local"
    $appImage = Get-EnvValue -Path $EnvPath -Name "APP_IMAGE"
    if ([string]::IsNullOrWhiteSpace($appImage)) {
        $appImage = "otziv-app:prod-local"
    }

    Write-Host "Building backend jar locally for offline app image..."
    Push-Location $backendDir
    try {
        Invoke-External -FilePath (Join-Path $backendDir "mvnw.cmd") -Arguments @("-B", "-ntp", "clean", "package", "-DskipTests")
    } finally {
        Pop-Location
    }

    $runtimeBaseImage = ($appImage -replace ":", "-") + ":runtime-base"
    Invoke-External -FilePath "docker" -Arguments @("image", "inspect", "--format", "{{.Id}}", $appImage)
    Invoke-External -FilePath "docker" -Arguments @("tag", $appImage, $runtimeBaseImage)

    Write-Host "Building offline app image $appImage from existing runtime image..."
    $tempRoot = [System.IO.Path]::GetTempPath()
    $tempBuildDir = Join-Path $tempRoot ("otziv-app-runtime-" + [guid]::NewGuid())
    New-Item -ItemType Directory -Path $tempBuildDir | Out-Null
    try {
        Copy-Item -LiteralPath $runtimeDockerfile -Destination (Join-Path $tempBuildDir "Dockerfile")
        Copy-Item -LiteralPath (Join-Path $backendDir "target\otziv-1.jar") -Destination (Join-Path $tempBuildDir "otziv-1.jar")

        Invoke-External -FilePath "docker" -Arguments @(
            "build",
            "-f", (Join-Path $tempBuildDir "Dockerfile"),
            "--build-arg", "RUNTIME_IMAGE=$runtimeBaseImage",
            "-t", $appImage,
            $tempBuildDir
        )
    } finally {
        $resolvedTempRoot = (Resolve-Path $tempRoot).Path
        $resolvedBuildDir = if (Test-Path -LiteralPath $tempBuildDir) { (Resolve-Path $tempBuildDir).Path } else { $null }
        if ($resolvedBuildDir -and $resolvedBuildDir.StartsWith($resolvedTempRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
            Remove-Item -LiteralPath $resolvedBuildDir -Recurse -Force
        }
    }
}

$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = (Resolve-Path (Join-Path $scriptRoot "..\..\..")).Path
$envResolverPath = Join-Path $repoRoot "infrastructure\scripts\Resolve-OtzivEnvFile.ps1"
if (-not (Test-Path -LiteralPath $envResolverPath)) {
    throw "Env resolver script not found: $envResolverPath"
}
. $envResolverPath
$composePath = if ([System.IO.Path]::IsPathRooted($ComposeFile)) { $ComposeFile } else { Join-Path $repoRoot $ComposeFile }
$envPath = Resolve-OtzivEnvFile -EnvFile $EnvFile -RepoRoot $repoRoot

if (-not (Test-Path -LiteralPath $composePath)) {
    throw "Compose file not found: $composePath"
}

Write-Host "Using env file: $envPath"

if (-not $SkipProdDbRestore) {
    $restoreScript = Join-Path $scriptRoot "restore-prod-db-local.ps1"
    if (-not (Test-Path -LiteralPath $restoreScript)) {
        throw "Local prod DB restore script not found: $restoreScript"
    }
    if ([string]::IsNullOrWhiteSpace($VpsHost)) {
        throw "Pass -VpsHost, or use -SkipProdDbRestore to keep the existing local DB."
    }

    Write-Host "Refreshing local prod-like DB from VPS before smoke. Pass -SkipProdDbRestore to keep the existing local DB."
    $restoreArgs = @{
        EnvFile = $envPath
        ComposeFile = $composePath
        VpsHost = $VpsHost
        VpsUser = $VpsUser
        VpsPort = $VpsPort
    }
    if (-not [string]::IsNullOrWhiteSpace($SshKey)) {
        $restoreArgs["SshKey"] = $SshKey
    }
    & $restoreScript @restoreArgs
    if ($LASTEXITCODE -ne 0) {
        throw "Production DB restore failed."
    }
}

if (-not $AllowLocalMessengerSending) {
    Disable-LocalMessengerEnv
}

if (-not $UseConfiguredOutboundProxy) {
    $env:OPENAI_PROXY_ENABLED = "false"
    $env:WHATSAPP_PROXY_ENABLED = "false"
    $env:WHATSAPP_PROXY_HOST = ""
    $env:MAX_PROXY_ENABLED = "false"
    $env:MAX_PROXY_HOST = ""

    $telegramProxyHost = Get-EnvValue -Path $envPath -Name "TELEGRAM_PROXY_HOST"
    if ([string]::IsNullOrWhiteSpace($telegramProxyHost)) {
        $telegramProxyHost = Get-EnvValue -Path $envPath -Name "OPENAI_PROXY_HOST"
    }
    $telegramProxyPort = Get-EnvValue -Path $envPath -Name "TELEGRAM_PROXY_PORT"
    if ([string]::IsNullOrWhiteSpace($telegramProxyPort)) {
        $telegramProxyPort = Get-EnvValue -Path $envPath -Name "OPENAI_PROXY_PORT"
    }
    if ([string]::IsNullOrWhiteSpace($telegramProxyPort)) {
        $telegramProxyPort = "8888"
    }

    if ([string]::IsNullOrWhiteSpace($telegramProxyHost)) {
        $env:TELEGRAM_PROXY_ENABLED = "false"
        $env:TELEGRAM_PROXY_HOST = ""
        Write-Host "Local prod-like smoke uses direct outbound routes for OpenAI, Telegram, WhatsApp and MAX. Pass -UseConfiguredOutboundProxy to use proxy values from the env file."
    } else {
        $env:TELEGRAM_PROXY_ENABLED = "true"
        $env:TELEGRAM_PROXY_HOST = $telegramProxyHost
        $env:TELEGRAM_PROXY_PORT = $telegramProxyPort
        Write-Host "Local prod-like smoke uses direct outbound routes for OpenAI, WhatsApp and MAX; Telegram uses the configured local Docker proxy ${telegramProxyHost}:$telegramProxyPort because Docker does not inherit the host VPN route."
    }
}

$openAiProxyEnabled = Get-EnvValue -Path $envPath -Name "OPENAI_PROXY_ENABLED"
$openAiProxySyncLocalIp = Get-EnvValue -Path $envPath -Name "OPENAI_PROXY_SYNC_LOCAL_IP"
if (
    -not $SkipOpenAiProxyIpSync `
    -and $UseConfiguredOutboundProxy `
    -and $openAiProxyEnabled `
    -and $openAiProxyEnabled.Equals("true", [System.StringComparison]::OrdinalIgnoreCase) `
    -and -not ($openAiProxySyncLocalIp -and $openAiProxySyncLocalIp.Equals("false", [System.StringComparison]::OrdinalIgnoreCase))
) {
    $proxyIpSyncScript = Join-Path $scriptRoot "update-openai-proxy-local-ip.ps1"
    if (-not (Test-Path -LiteralPath $proxyIpSyncScript)) {
        throw "OpenAI proxy IP sync script not found: $proxyIpSyncScript"
    }

    & $proxyIpSyncScript -EnvFile $envPath
}

$composeArgs = @("compose", "-f", $composePath, "--env-file", $envPath)
if ($WithDbAdmin) {
    $composeArgs += @("--profile", "db-admin")
}
if ($WithObservability) {
    $composeArgs += @("--profile", "observability")
    $env:OTZIV_TRACING_ENABLED = "true"
    Write-Host "Local observability is enabled: Loki, Tempo, Alloy, Prometheus, Grafana and Dozzle will be started."
} else {
    $env:OTZIV_TRACING_ENABLED = "false"
    Write-Host "Local observability is disabled. Pass -WithObservability to start Loki, Tempo, Alloy, Prometheus, Grafana and Dozzle."
}
Invoke-External -FilePath "docker" -Arguments ($composeArgs + @("config", "--quiet"))

$localServices = & docker @($composeArgs + @("config", "--services"))
if ($LASTEXITCODE -ne 0) {
    throw "Failed to list compose services"
}
$blockedWhatsAppServices = @($localServices | Where-Object { $_ -in @("whatsapp_lika", "whatsapp_vika") })
if ($blockedWhatsAppServices.Count -gt 0) {
    throw "Local prod-like compose must not start WhatsApp services: $($blockedWhatsAppServices -join ', ')"
}

if ($OfflineAppBuild) {
    Invoke-OfflineAppBuild -RepoRoot $repoRoot -EnvPath $envPath
}

$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
try {
    if (-not $NoUp) {
        Invoke-External -FilePath "docker" -Arguments ($composeArgs + @("up", "-d", "mysql"))
        Wait-ComposeServiceHealthy -ComposeArguments $composeArgs -Service "mysql"
        Disable-LocalExternalMessaging -ComposeArguments $composeArgs -EnvPath $envPath

        $upArgs = @("up", "-d", "--remove-orphans")
        if (-not $NoBuild -and -not $OfflineAppBuild) {
            $upArgs += "--build"
        }

        $upResult = Invoke-DockerComposeUp -ComposeArguments $composeArgs -UpArguments $upArgs
        if ($upResult.ExitCode -ne 0) {
            $canFallback = -not $NoOfflineFallback -and -not $OfflineAppBuild -and -not $NoBuild -and (Test-RegistryBuildFailure -Output $upResult.Output)
            if (-not $canFallback) {
                throw "Command failed: docker $($composeArgs + $upArgs -join ' ')"
            }

            Write-Warning "Docker registry is unavailable. Falling back to offline backend image rebuild from local Maven jar."
            Invoke-OfflineAppBuild -RepoRoot $repoRoot -EnvPath $envPath

            $retryArgs = @("up", "-d", "--remove-orphans")
            $retryResult = Invoke-DockerComposeUp -ComposeArguments $composeArgs -UpArguments $retryArgs
            if ($retryResult.ExitCode -ne 0) {
                throw "Command failed after offline fallback: docker $($composeArgs + $retryArgs -join ' ')"
            }
        }
    }

    Wait-HttpOk -Url "$BaseUrl/actuator/health" -Name "backend health" -Deadline $deadline
    Disable-LocalExternalMessaging -ComposeArguments $composeArgs -EnvPath $envPath
    if (-not $NoUp) {
        Write-Host "Restarting backend so local safety settings are loaded from the refreshed DB."
        Invoke-External -FilePath "docker" -Arguments ($composeArgs + @("up", "-d", "--force-recreate", "--no-deps", "app"))
        Wait-HttpOk -Url "$BaseUrl/actuator/health" -Name "backend health after local safety reload" -Deadline $deadline
    }
    Wait-HttpOk -Url "$BaseUrl/keycloak/realms/otziv/.well-known/openid-configuration" -Name "Keycloak realm" -Deadline $deadline
    Update-KeycloakFrontendLoopbackRedirects -ComposeArguments $composeArgs -EnvPath $envPath -BaseUrl $BaseUrl
    Wait-HttpOk -Url "$BaseUrl/" -Name "frontend" -Deadline $deadline
    Invoke-PublicFrontendSmoke -BaseUrl $BaseUrl
    Invoke-TbankPaymentConfigSmoke -BaseUrl $BaseUrl -EnvPath $envPath
    if ($WithReputationAiSmoke) {
        Invoke-ReputationAiSmoke `
            -RootUrl $BaseUrl `
            -EnvPath $envPath `
            -CompanyId $ReputationAiCompanyId `
            -SkipRouteCheck:$SkipReputationAiOpenAiRouteCheck
    }

    Invoke-External -FilePath "docker" -Arguments ($composeArgs + @("ps"))
    Write-Host "Local prod-like smoke passed: $BaseUrl"
} catch {
    if (-not $NoLogs) {
        Write-Host ""
        Write-Host "Last container logs:"
        & docker @($composeArgs + @("logs", "--tail=200", "nginx", "app", "keycloak"))
    }

    throw
}
