param(
    [string]$EnvFile = ".env.prod-local",
    [string]$ComposeFile = "compose.prod-local.yaml",
    [string]$BaseUrl = "http://localhost:8088"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Assert-True {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) {
        throw "ASSERTION FAILED: $Message"
    }
}

function ConvertFrom-EnvFile {
    param([string]$Path)
    $values = @{}
    foreach ($line in Get-Content -LiteralPath $Path) {
        $trimmed = $line.Trim()
        if ($trimmed.Length -eq 0 -or $trimmed.StartsWith('#')) {
            continue
        }
        $separator = $trimmed.IndexOf('=')
        if ($separator -gt 0) {
            $values[$trimmed.Substring(0, $separator).Trim()] = $trimmed.Substring($separator + 1).Trim()
        }
    }
    return $values
}

function ConvertFrom-ContainerEnv {
    param([string]$Container)
    $inspect = (docker inspect $Container | ConvertFrom-Json)[0]
    if ($LASTEXITCODE -ne 0 -or $null -eq $inspect) {
        throw "Container is unavailable: $Container"
    }
    $values = @{}
    foreach ($entry in $inspect.Config.Env) {
        $parts = $entry -split '=', 2
        if ($parts.Count -eq 2) {
            $values[$parts[0]] = $parts[1]
        }
    }
    return $values
}

function Invoke-LocalSql {
    param([Parameter(Mandatory = $true)][string]$Sql)
    $arguments = @(
        'exec', '-e', "MYSQL_PWD=$script:databasePassword",
        'otziv-prod-local-mysql-1', 'mysql',
        "-u$script:databaseUser", $script:databaseName,
        '--batch', '--raw', '--skip-column-names', '-e', $Sql
    )
    $output = & docker @arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Local MySQL command failed."
    }
    return @($output)
}

function Get-SqlScalar {
    param([Parameter(Mandatory = $true)][string]$Sql)
    $rows = @(Invoke-LocalSql -Sql $Sql)
    if ($rows.Count -eq 0) {
        return $null
    }
    return [string]$rows[0]
}

function Escape-SqlString {
    param([AllowNull()][string]$Value)
    if ($null -eq $Value) {
        return 'NULL'
    }
    return "'" + $Value.Replace("'", "''") + "'"
}

function Wait-LocalApp {
    $deadline = [DateTime]::UtcNow.AddMinutes(3)
    do {
        try {
            $response = Invoke-WebRequest -Uri "$($BaseUrl.TrimEnd('/'))/actuator/health" -TimeoutSec 5 -SkipHttpErrorCheck
            if ($response.StatusCode -eq 200) {
                return
            }
        } catch {
            # The container can accept connections before Spring is ready.
        }
        Start-Sleep -Seconds 2
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Local app did not become healthy."
}

function Restart-LocalApp {
    param([bool]$EnablePaymentRoutingMasters)
    $oldRouting = [Environment]::GetEnvironmentVariable('OTZIV_CONTRACTOR_PAYMENTS_LIVE_ROUTING_MASTER_ENABLED')
    $oldAttribution = [Environment]::GetEnvironmentVariable('OTZIV_CONTRACTOR_PAYMENTS_REWARD_ATTRIBUTION_MASTER_ENABLED')
    try {
        [Environment]::SetEnvironmentVariable(
            'OTZIV_CONTRACTOR_PAYMENTS_LIVE_ROUTING_MASTER_ENABLED',
            $(if ($EnablePaymentRoutingMasters) { 'true' } else { $null })
        )
        [Environment]::SetEnvironmentVariable(
            'OTZIV_CONTRACTOR_PAYMENTS_REWARD_ATTRIBUTION_MASTER_ENABLED',
            $(if ($EnablePaymentRoutingMasters) { 'true' } else { $null })
        )
        & docker compose --env-file $script:envPath -f $ComposeFile up -d --no-deps --force-recreate app | Out-Host
        if ($LASTEXITCODE -ne 0) {
            throw "Unable to recreate the local app container."
        }
        Wait-LocalApp
    } finally {
        [Environment]::SetEnvironmentVariable('OTZIV_CONTRACTOR_PAYMENTS_LIVE_ROUTING_MASTER_ENABLED', $oldRouting)
        [Environment]::SetEnvironmentVariable('OTZIV_CONTRACTOR_PAYMENTS_REWARD_ATTRIBUTION_MASTER_ENABLED', $oldAttribution)
    }
}

function Invoke-Api {
    param(
        [Parameter(Mandatory = $true)][string]$Method,
        [Parameter(Mandatory = $true)][string]$Path,
        [AllowNull()]$Body = $null,
        [switch]$Anonymous
    )
    $headers = @{}
    if (-not $Anonymous) {
        $headers.Authorization = "Bearer $script:userAccessToken"
    }
    $parameters = @{
        Uri = "$($BaseUrl.TrimEnd('/'))$Path"
        Method = $Method
        Headers = $headers
        SkipHttpErrorCheck = $true
        TimeoutSec = 30
    }
    if ($null -ne $Body) {
        $parameters.Body = ($Body | ConvertTo-Json -Depth 12 -Compress)
        $parameters.ContentType = 'application/json'
    }
    $response = Invoke-WebRequest @parameters
    $json = $null
    if (-not [string]::IsNullOrWhiteSpace($response.Content)) {
        try {
            $json = $response.Content | ConvertFrom-Json
        } catch {
            $json = $response.Content
        }
    }
    return [pscustomobject]@{
        Status = [int]$response.StatusCode
        Json = $json
        Content = [string]$response.Content
    }
}

function Assert-ApiSuccess {
    param($Response, [string]$Operation, [int[]]$Expected = @(200))
    Assert-True -Condition ($Expected -contains $Response.Status) `
        -Message "$Operation returned HTTP $($Response.Status): $($Response.Content)"
    return $Response.Json
}

function New-LocalLoginClient {
    $keycloakEnvironment = ConvertFrom-ContainerEnv -Container 'otziv-prod-local-keycloak-1'
    $adminUsername = $keycloakEnvironment['KC_BOOTSTRAP_ADMIN_USERNAME']
    if ([string]::IsNullOrWhiteSpace($adminUsername)) {
        $adminUsername = $keycloakEnvironment['KEYCLOAK_ADMIN']
    }
    $script:keycloakAdminPassword = $keycloakEnvironment['KC_BOOTSTRAP_ADMIN_PASSWORD']
    if ([string]::IsNullOrWhiteSpace($script:keycloakAdminPassword)) {
        $script:keycloakAdminPassword = $keycloakEnvironment['KEYCLOAK_ADMIN_PASSWORD']
    }
    $adminToken = (Invoke-RestMethod `
        -Method Post `
        -Uri "$($BaseUrl.TrimEnd('/'))/keycloak/realms/master/protocol/openid-connect/token" `
        -Body @{
            grant_type = 'password'
            client_id = 'admin-cli'
            username = $adminUsername
            password = $script:keycloakAdminPassword
        } `
        -ContentType 'application/x-www-form-urlencoded' `
        -TimeoutSec 30).access_token
    $script:keycloakAdminHeaders = @{ Authorization = "Bearer $adminToken" }

    $clientId = "codex-payment-route-scenario-$([guid]::NewGuid().ToString('N').Substring(0, 12))"
    $client = @{
        clientId = $clientId
        name = 'Temporary local payment route scenario smoke'
        enabled = $true
        publicClient = $true
        bearerOnly = $false
        standardFlowEnabled = $false
        implicitFlowEnabled = $false
        directAccessGrantsEnabled = $true
        serviceAccountsEnabled = $false
        protocol = 'openid-connect'
        protocolMappers = @(
            @{
                name = 'backend audience'
                protocol = 'openid-connect'
                protocolMapper = 'oidc-audience-mapper'
                consentRequired = $false
                config = @{
                    'included.client.audience' = 'otziv-backend'
                    'id.token.claim' = 'false'
                    'access.token.claim' = 'true'
                }
            }
        )
    }
    $created = Invoke-WebRequest `
        -Method Post `
        -Uri "$($BaseUrl.TrimEnd('/'))/keycloak/admin/realms/otziv/clients" `
        -Headers $script:keycloakAdminHeaders `
        -Body ($client | ConvertTo-Json -Depth 10 -Compress) `
        -ContentType 'application/json' `
        -TimeoutSec 30
    $script:keycloakClientUuid = ([string]$created.Headers.Location).Split('/')[-1]

    $script:userAccessToken = (Invoke-RestMethod `
        -Method Post `
        -Uri "$($BaseUrl.TrimEnd('/'))/keycloak/realms/otziv/protocol/openid-connect/token" `
        -Body @{
            grant_type = 'password'
            client_id = $clientId
            username = $script:localLoginUsername
            password = $script:localLoginPassword
            scope = 'openid'
        } `
        -ContentType 'application/x-www-form-urlencoded' `
        -TimeoutSec 30).access_token
}

function Remove-LocalLoginClient {
    if (-not [string]::IsNullOrWhiteSpace($script:keycloakClientUuid) `
            -and $null -ne $script:keycloakAdminHeaders) {
        try {
            Invoke-RestMethod `
                -Method Delete `
                -Uri "$($BaseUrl.TrimEnd('/'))/keycloak/admin/realms/otziv/clients/$script:keycloakClientUuid" `
                -Headers $script:keycloakAdminHeaders `
                -TimeoutSec 30 | Out-Null
        } catch {
            Write-Warning "Temporary Keycloak client cleanup failed: $($_.Exception.Message)"
        }
    }
}

function Get-PaymentRow {
    param([long]$LinkId)
    $row = @(Invoke-LocalSql -Sql @"
SELECT CONCAT_WS(CHAR(9),
  id, order_id, status, payment_method, COALESCE(manual_source,''),
  COALESCE(contractor_allocation_id,0), amount_kopecks,
  COALESCE(manual_reported_at,''), COALESCE(paid_at,''),
  COALESCE(payment_profile_name,''), COALESCE(tbank_payment_id,''), COALESCE(payment_url,''))
FROM payment_links WHERE id=$LinkId;
"@)
    Assert-True ($row.Count -eq 1) "Payment link $LinkId was not found."
    $parts = ([string]$row[0]) -split "`t", 12
    return [pscustomobject]@{
        Id = [long]$parts[0]
        OrderId = [long]$parts[1]
        Status = $parts[2]
        Method = $parts[3]
        ManualSource = $parts[4]
        AllocationId = [long]$parts[5]
        AmountKopecks = [long]$parts[6]
        ManualReportedAt = $parts[7]
        PaidAt = $parts[8]
        ProfileName = $parts[9]
        TbankPaymentId = $parts[10]
        PaymentUrl = $parts[11]
    }
}

function Get-AllocationRow {
    param([long]$AllocationId)
    $row = @(Invoke-LocalSql -Sql @"
SELECT CONCAT_WS(CHAR(9),
  id, mode, source_type, source_id, order_id, recipient_type,
  COALESCE(recipient_profile_id,0), amount_kopecks, confirmed_kopecks,
  returned_kopecks, status, COALESCE(routing_decision_reason,''),
  COALESCE(specialist_rejection_reason,''), COALESCE(manager_rejection_reason,''))
FROM contractor_payment_allocations WHERE id=$AllocationId;
"@)
    Assert-True ($row.Count -eq 1) "Allocation $AllocationId was not found."
    $parts = ([string]$row[0]) -split "`t", 14
    return [pscustomobject]@{
        Id = [long]$parts[0]
        Mode = $parts[1]
        SourceType = $parts[2]
        SourceId = [long]$parts[3]
        OrderId = [long]$parts[4]
        RecipientType = $parts[5]
        RecipientProfileId = [long]$parts[6]
        AmountKopecks = [long]$parts[7]
        ConfirmedKopecks = [long]$parts[8]
        ReturnedKopecks = [long]$parts[9]
        Status = $parts[10]
        Decision = $parts[11]
        SpecialistRejection = $parts[12]
        ManagerRejection = $parts[13]
    }
}
function Wait-AllocationStatus {
    param(
        [long]$AllocationId,
        [string[]]$ExpectedStatuses,
        [int]$TimeoutSeconds = 15
    )
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        $allocation = Get-AllocationRow $AllocationId
        if ($ExpectedStatuses -contains $allocation.Status) {
            return $allocation
        }
        Start-Sleep -Milliseconds 250
    } while ([DateTime]::UtcNow -lt $deadline)
    return Get-AllocationRow $AllocationId
}

function Set-TestProfileAvailability {
    param([bool]$SpecialistLive, [bool]$ManagerLive)
    $specialist = if ($SpecialistLive) { 1 } else { 0 }
    $manager = if ($ManagerLive) { 1 } else { 0 }
    Invoke-LocalSql -Sql @"
UPDATE contractor_payment_profiles
SET enabled=1,
    live_enabled=CASE id
      WHEN $script:specialistProfileId THEN $specialist
      WHEN $script:managerProfileId THEN $manager
      ELSE live_enabled END,
    opening_balance_kopecks=100000000,
    tracking_started_at='2026-07-31 00:00:00'
WHERE id IN ($script:specialistProfileId,$script:managerProfileId);
"@ | Out-Null
}
function Set-TestProfileDetails {
    param(
        [long]$UserId,
        [string]$Role,
        [bool]$LiveEnabled,
        [string]$RecipientName,
        [string]$PaymentPhone
    )
    $profiles = Assert-ApiSuccess `
        (Invoke-Api -Method Get -Path "/api/admin/users/$UserId/contractor-payment-profiles") `
        "load $Role payment profile"
    $profile = @($profiles | Where-Object { $_.role -eq $Role }) | Select-Object -First 1
    Assert-True ($null -ne $profile) "$Role payment profile was not found for user $UserId."
    Assert-ApiSuccess `
        (Invoke-Api -Method Put -Path "/api/admin/users/$UserId/contractor-payment-profiles" -Body @{
            role = $Role
            expectedVersion = [long]$profile.rowVersion
            enabled = $true
            liveEnabled = $LiveEnabled
            recipientName = $RecipientName
            paymentPhone = $PaymentPhone
            bankName = 'Локальный тестовый банк'
            paymentComment = 'Только prod-like scenario smoke'
            openingBalanceKopecks = 100000000
            openingBalanceReason = ''
        }) `
        "configure $Role payment profile" | Out-Null
}

function Close-PriorTaggedOrder {
    param([long]$OrderId)
    $contextResponse = Invoke-Api -Method Get -Path "/api/manager/orders/$OrderId/payment-route-change-context"
    if ($contextResponse.Status -ne 200 -or $null -eq $contextResponse.Json.paymentLinkId) {
        [void](Try-DeleteTestOrder -OrderId $OrderId)
        return
    }
    $link = Get-PaymentRow ([long]$contextResponse.Json.paymentLinkId)
    if ($link.Method -eq 'MANUAL_MOBILE_BANK') {
        Close-ManualLinkUnpaid -LinkId $link.Id
        [void](Try-DeleteTestOrder -OrderId $OrderId)
        return
    }
    if ($link.Status -eq 'CREATED' -and [string]::IsNullOrWhiteSpace($link.TbankPaymentId)) {
        $replacement = Assert-ApiSuccess `
            (Invoke-Api -Method Post -Path "/api/manager/orders/$OrderId/payment-route-change" -Body @{
                expectedPaymentLinkId = $link.Id
                target = 'EMPLOYEE_REQUISITES'
                confirmedUnpaid = $true
            }) `
            "retire prior tagged owner link for order $OrderId"
        Close-ManualLinkUnpaid -LinkId ([long]$replacement.paymentLinkId)
        [void](Try-DeleteTestOrder -OrderId $OrderId)
    }
}

function New-TestOrder {
    param([string]$Scenario)
    $before = [long](Get-SqlScalar -Sql 'SELECT COALESCE(MAX(order_id),0) FROM orders;')
    $response = Invoke-Api -Method Post -Path "/api/manager/companies/$script:companyId/orders" -Body @{
        productId = $script:productId
        amount = $script:orderAmount
        workerId = $script:workerId
        filialId = $script:filialId
    }
    Assert-ApiSuccess $response "create test order ($Scenario)" @(201) | Out-Null
    $id = Get-SqlScalar -Sql @"
SELECT order_id FROM orders
WHERE order_id > $before AND order_company=$script:companyId
ORDER BY order_id DESC LIMIT 1;
"@
    Assert-True (-not [string]::IsNullOrWhiteSpace($id)) "Created order id was not found for $Scenario."
    $orderId = [long]$id
    Invoke-LocalSql -Sql "UPDATE orders SET order_zametka=$(Escape-SqlString "CODEX LOCAL PAYMENT ROUTE TEST: $Scenario") WHERE order_id=$orderId;" | Out-Null
    [void]$script:testOrderIds.Add($orderId)
    [void]$script:createdTestOrderIds.Add($orderId)
    return $orderId
}

function New-CompletedTestOrderFromSource {
    param([long]$SourceOrderId, [string]$Scenario)
    $orderId = New-TestOrder -Scenario $Scenario
    $sourceReviewCount = [long](Get-SqlScalar -Sql @"
SELECT COUNT(*) FROM reviews r
JOIN order_details od ON od.order_detail_id=r.review_order_details
WHERE od.order_detail_order=$SourceOrderId AND r.review_publish=1;
"@)
    Assert-True ($sourceReviewCount -eq $script:orderAmount) `
        "Source order $SourceOrderId has $sourceReviewCount published reviews, expected $script:orderAmount."
    Invoke-LocalSql -Sql @"
DELETE r FROM reviews r
JOIN order_details target ON target.order_detail_id=r.review_order_details
WHERE target.order_detail_order=$orderId;
UPDATE order_details target
JOIN order_details source ON source.order_detail_order=$SourceOrderId
SET target.order_detail_product=source.order_detail_product,
    target.order_detail_amount=source.order_detail_amount,
    target.order_detail_price=source.order_detail_price,
    target.order_detail_comments=source.order_detail_comments,
    target.order_detail_date_published=source.order_detail_date_published
WHERE target.order_detail_order=$orderId;

INSERT INTO reviews (
 review_text,review_category,review_subcategory,review_answer,review_created,review_changed,
 review_publish,review_publish_date,review_order_details,review_bot,review_filial,review_worker,
 review_vigul,review_product,review_price,review_url,review_account_walk_delay_days,
 review_account_walk_delay_bot_id,review_account_walk_not_before,review_published_marked_at,
 review_external_confirm_status,review_external_confirmed_at,review_external_confirm_screenshot_url,
 review_created_at,review_vigul_changed_at,review_text_ready_at,review_text_ready_worker_id,row_version
)
SELECT
 r.review_text,r.review_category,r.review_subcategory,r.review_answer,r.review_created,r.review_changed,
 r.review_publish,r.review_publish_date,target.order_detail_id,r.review_bot,r.review_filial,r.review_worker,
 r.review_vigul,r.review_product,r.review_price,r.review_url,r.review_account_walk_delay_days,
 r.review_account_walk_delay_bot_id,r.review_account_walk_not_before,r.review_published_marked_at,
 r.review_external_confirm_status,r.review_external_confirmed_at,r.review_external_confirm_screenshot_url,
 r.review_created_at,r.review_vigul_changed_at,r.review_text_ready_at,r.review_text_ready_worker_id,0
FROM reviews r
JOIN order_details source ON source.order_detail_id=r.review_order_details
JOIN order_details target ON target.order_detail_order=$orderId
WHERE source.order_detail_order=$SourceOrderId AND r.review_publish=1;

UPDATE orders target
JOIN orders source ON source.order_id=$SourceOrderId
SET target.order_counter=target.order_amount,
    target.order_changed=CURDATE(),
    target.order_status=(
        SELECT order_status_id
        FROM order_statuses
        WHERE order_status_title=CONVERT(0xD09FD183D0B1D0BBD0B8D0BAD0B0D186D0B8D18F USING utf8mb4)
        LIMIT 1
    )
WHERE target.order_id=$orderId;
"@ | Out-Null
    return $orderId
}

function New-PaymentLink {
    param([long]$OrderId)
    $response = Invoke-Api -Method Post -Path "/api/manager/orders/$OrderId/payment-link"
    $json = Assert-ApiSuccess $response "create payment link for order $OrderId"
    Assert-True (-not [string]::IsNullOrWhiteSpace([string]$json.token)) "Payment token is missing."
    $linkId = [long](Get-SqlScalar -Sql "SELECT id FROM payment_links WHERE token=$(Escape-SqlString ([string]$json.token));")
    Assert-True ($linkId -gt 0) "Created payment link was not persisted."
    return [pscustomobject]@{ Id = $linkId; Api = $json }
}

function Publish-TestOrderAndGetPaymentLink {
    param([long]$OrderId)
    $before = [long](Get-SqlScalar -Sql 'SELECT COALESCE(MAX(id),0) FROM payment_links;')
    Assert-ApiSuccess `
        (Invoke-Api -Method Post -Path "/api/manager/orders/$OrderId/status" -Body @{ status = '�㡫�����' }) `
        "publish completed test order $OrderId" @(204) | Out-Null
    $deadline = [DateTime]::UtcNow.AddSeconds(10)
    do {
        $linkId = Get-SqlScalar -Sql "SELECT id FROM payment_links WHERE order_id=$OrderId AND id>$before ORDER BY id DESC LIMIT 1;"
        if (-not [string]::IsNullOrWhiteSpace([string]$linkId)) { break }
        Start-Sleep -Milliseconds 250
    } while ([DateTime]::UtcNow -lt $deadline)
    Assert-True (-not [string]::IsNullOrWhiteSpace([string]$linkId)) 'Published order did not create a payment link.'
    $token = Get-SqlScalar -Sql "SELECT token FROM payment_links WHERE id=$linkId;"
    return [pscustomobject]@{ Id = [long]$linkId; Api = [pscustomobject]@{ token = [string]$token } }
}

function Publish-TestOrderAndGetPaymentLinkUtf8 {
    param([long]$OrderId)
    $publishStatus = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('0J7Qv9GD0LHQu9C40LrQvtCy0LDQvdC+'))
    Assert-ApiSuccess `
        (Invoke-Api -Method Post -Path "/api/manager/orders/$OrderId/status" -Body @{ status = $publishStatus }) `
        "publish completed test order $OrderId" @(204) | Out-Null
    return New-PaymentLink -OrderId $OrderId
}

function Close-ManualLinkUnpaid {
    param([long]$LinkId)
    $response = Invoke-Api -Method Post -Path "/api/admin/payments/manual-links/$LinkId/close-unpaid" -Body @{
        recipientStatementChecked = $true
        paymentAbsent = $true
        note = 'Local scenario smoke cleanup'
    }
    Assert-ApiSuccess $response "close manual link $LinkId as unpaid" | Out-Null
    $link = Get-PaymentRow $LinkId
    Assert-True ($link.Status -in @('CANCELED', 'EXPIRED', 'REJECTED')) "Manual link $LinkId stayed active."
    if ($link.AllocationId -gt 0) {
        $allocation = Wait-AllocationStatus $link.AllocationId @('RELEASED_UNPAID', 'EXPIRED', 'CANCELED')
        Assert-True ($allocation.Status -in @('RELEASED_UNPAID', 'EXPIRED', 'CANCELED')) `
            "Reserve $($allocation.Id) was not released after unpaid closure."
    }
}

function Try-DeleteTestOrder {
    param([long]$OrderId)
    $response = Invoke-Api -Method Delete -Path "/api/manager/orders/$OrderId"
    if ($response.Status -eq 204) {
        return $true
    }
    Write-Warning "Local test order $OrderId could not be deleted through the application (HTTP $($response.Status))."
    return $false
}

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
. (Join-Path $repoRoot 'infrastructure\scripts\Resolve-OtzivEnvFile.ps1')
$script:envPath = Resolve-OtzivEnvFile -EnvFile $EnvFile -RepoRoot $repoRoot
$envValues = ConvertFrom-EnvFile -Path $script:envPath
$script:localLoginUsername = $envValues['OTZIV_LOCAL_LOGIN_USERNAME']
$script:localLoginPassword = $envValues['OTZIV_LOCAL_LOGIN_PASSWORD']
Assert-True (-not [string]::IsNullOrWhiteSpace($script:localLoginUsername)) 'Local login username is missing.'
Assert-True (-not [string]::IsNullOrWhiteSpace($script:localLoginPassword)) 'Local login password is missing.'

$databaseEnvironment = ConvertFrom-ContainerEnv -Container 'otziv-prod-local-mysql-1'
$script:databaseUser = $databaseEnvironment['MYSQL_USER']
$script:databasePassword = $databaseEnvironment['MYSQL_PASSWORD']
$script:databaseName = $databaseEnvironment['MYSQL_DATABASE']
$script:userAccessToken = $null
$script:keycloakAdminPassword = $null
$script:keycloakAdminHeaders = $null
$script:keycloakClientUuid = $null
$script:testOrderIds = [System.Collections.Generic.List[long]]::new()
$script:createdTestOrderIds = [System.Collections.Generic.List[long]]::new()
$deletedOrders = 0
$settingsSnapshot = @{}
$profileSnapshot = @{}
$mastersEnabled = $false

$settingKeys = @(
    'payments.tbank.payment-links-enabled',
    'contractor-payments.shadow-enabled',
    'contractor-payments.live-routing-enabled',
    'contractor-payments.reward-attribution-live-enabled',
    'contractor-payments.live-readiness-confirmed',
    'client.messages.payment-instruction-source',
    'client.messages.worker.enabled',
    'client.messages.live.enabled'
)

try {
    Write-Host 'Selecting an isolated recipient/manager/company route from the refreshed local database...'
    $candidateRows = @(Invoke-LocalSql -Sql @"
SELECT CONCAT_WS(CHAR(9), o.order_id, c.company_id, c.company_manager, w.worker_id,
       wp.id, mp.id, f.filial_id, w.user_id, m.user_id,
       (SELECT od.order_detail_product FROM order_details od WHERE od.order_detail_order=o.order_id LIMIT 1), o.order_amount)
FROM orders o
JOIN companies c ON c.company_id=o.order_company
JOIN managers m ON m.manager_id=c.company_manager
JOIN contractor_payment_profiles mp ON mp.user_id=m.user_id AND mp.contractor_role='MANAGER'
JOIN workers w ON w.worker_id=o.order_worker AND w.accepts_company_transfers=1
JOIN workers_companies wc ON wc.company_id=c.company_id AND wc.worker_id=w.worker_id
JOIN contractor_payment_profiles wp ON wp.user_id=w.user_id AND wp.contractor_role='SPECIALIST'
JOIN filial f ON f.filial_id=o.order_filial AND f.filial_archived=0
WHERE c.company_active=1 AND c.company_contractor_payment_routing_enabled=1
  AND (o.order_zametka IS NULL OR o.order_zametka NOT LIKE 'CODEX LOCAL PAYMENT ROUTE TEST:%')
  AND o.order_complete=0 AND o.order_counter>=o.order_amount AND o.order_amount>0
  AND EXISTS (SELECT 1 FROM order_details od JOIN reviews r ON r.review_order_details=od.order_detail_id
              WHERE od.order_detail_order=o.order_id AND r.review_publish=1)
  AND NOT EXISTS (SELECT 1 FROM common_invoice_orders cio
                  WHERE cio.order_id=o.order_id AND cio.active_membership=1)
  AND NOT EXISTS (SELECT 1 FROM common_billing_account_companies cac
                  JOIN common_billing_accounts ca ON ca.account_id=cac.account_id AND ca.enabled=1
                  WHERE cac.company_id=o.order_company AND cac.enabled=1)
  AND NOT EXISTS (SELECT 1 FROM payment_check pc
                  WHERE pc.check_order=o.order_id AND pc.check_active=1)
  AND NOT EXISTS (SELECT 1 FROM payment_links pl
                  WHERE pl.order_id=o.order_id
                    AND pl.status IN ('CREATED','READY','WAITING_MANUAL_PAYMENT','MANUAL_REPORTED','CONFIRMED','PAID'))
  AND NOT EXISTS (SELECT 1 FROM manual_payment_tasks mt
                  WHERE mt.manager_id=m.manager_id AND mt.status='ACTIVE')
ORDER BY o.order_id DESC LIMIT 50;
"@)
    Assert-True ($candidateRows.Count -gt 0) 'No isolated contractor route candidate exists in local data.'
    $candidate = ([string]$candidateRows[0]) -split "`t"
    $script:existingReadyOrderId = [long]$candidate[0]
    $script:companyId = [long]$candidate[1]
    $script:managerId = [long]$candidate[2]
    $script:workerId = [long]$candidate[3]
    $script:specialistProfileId = [long]$candidate[4]
    $script:managerProfileId = [long]$candidate[5]
    $script:filialId = [long]$candidate[6]
    $script:specialistUserId = [long]$candidate[7]
    $script:managerUserId = [long]$candidate[8]
    $script:productId = [long]$candidate[9]
    $script:orderAmount = [int]$candidate[10]

    foreach ($row in @(Invoke-LocalSql -Sql @"
SELECT CONCAT_WS(CHAR(9), setting_key, HEX(setting_value))
FROM app_settings
WHERE setting_key IN ($(($settingKeys | ForEach-Object { Escape-SqlString $_ }) -join ','));
"@)) {
        $parts = ([string]$row) -split "`t", 2
        $settingsSnapshot[$parts[0]] = $parts[1]
    }
    foreach ($key in $settingKeys) {
        Assert-True $settingsSnapshot.ContainsKey($key) "Missing app setting: $key"
    }

    foreach ($row in @(Invoke-LocalSql -Sql @"
SELECT CONCAT_WS(CHAR(9), id, enabled, live_enabled, opening_balance_kopecks,
       DATE_FORMAT(tracking_started_at,'%Y-%m-%d %H:%i:%s.%f'),
       COALESCE(HEX(recipient_name),'~'), COALESCE(HEX(payment_phone),'~'),
       COALESCE(HEX(bank_name),'~'), COALESCE(HEX(payment_comment),'~'),
       row_version)
FROM contractor_payment_profiles
WHERE id IN ($script:specialistProfileId,$script:managerProfileId);
"@)) {
        $parts = ([string]$row) -split "`t", 10
        $profileSnapshot[[long]$parts[0]] = $parts
    }
    Assert-True ($profileSnapshot.Count -eq 2) 'Recipient profile snapshot is incomplete.'

    Invoke-LocalSql -Sql @"
UPDATE app_settings SET setting_value='true', updated_at=NOW(6)
WHERE setting_key IN (
 'payments.tbank.payment-links-enabled',
 'contractor-payments.shadow-enabled',
 'contractor-payments.live-routing-enabled',
 'contractor-payments.reward-attribution-live-enabled',
 'contractor-payments.live-readiness-confirmed',
 'client.messages.worker.enabled',
 'client.messages.live.enabled'
);
UPDATE app_settings SET setting_value='TBANK_LINK', updated_at=NOW(6)
WHERE setting_key='client.messages.payment-instruction-source';
"@ | Out-Null
    Set-TestProfileAvailability -SpecialistLive $true -ManagerLive $true
    Restart-LocalApp -EnablePaymentRoutingMasters $true
    $mastersEnabled = $true
    New-LocalLoginClient

    $me = Assert-ApiSuccess (Invoke-Api -Method Get -Path '/api/me') 'authenticated local user'
    Assert-True ($me.authenticated -eq $true) 'Local user is not authenticated.'
    Assert-True ((@($me.realmRoles) -contains 'OWNER') -or (@($me.realmRoles) -contains 'ADMIN')) `
        'The selected local test account must have OWNER or ADMIN role.'
    Set-TestProfileDetails -UserId $script:specialistUserId -Role 'SPECIALIST' -LiveEnabled $true `
        -RecipientName 'Локальный тестовый специалист' -PaymentPhone '+79110000001'
    Set-TestProfileDetails -UserId $script:managerUserId -Role 'MANAGER' -LiveEnabled $true `
        -RecipientName 'Локальный тестовый менеджер' -PaymentPhone '+79110000002'

    $priorTaggedOrders = @(Invoke-LocalSql -Sql "SELECT order_id FROM orders WHERE order_zametka LIKE 'CODEX LOCAL PAYMENT ROUTE TEST:%' ORDER BY order_id;")
    foreach ($priorOrderId in $priorTaggedOrders) {
        Close-PriorTaggedOrder -OrderId ([long]$priorOrderId)
    }


    $system = Assert-ApiSuccess (Invoke-Api -Method Get -Path '/api/admin/contractor-payments/system') 'payment system status'
    Assert-True ($system.completionAccountingEffective -eq $true) 'Completion accounting is not effective locally.'
    Assert-True ($system.liveRoutingEffective -eq $true) `
        "LIVE routing did not become effective: $(@($system.activationBlockedReasons) -join '; ')"

    $creation = Assert-ApiSuccess `
        (Invoke-Api -Method Get -Path "/api/manager/companies/$script:companyId/order-create") `
        'load order creation options'
    Assert-True (@($creation.products).Count -gt 0) 'The selected company has no products.'
    Assert-True (@($creation.workers | Where-Object { $_.id -eq $script:workerId }).Count -gt 0) `
        'The selected specialist is unavailable for the company.'
    Assert-True (@($creation.filials | Where-Object { $_.id -eq $script:filialId }).Count -gt 0) `
        'The selected filial is unavailable for the company.'
    Assert-True (@($creation.products | Where-Object { $_.id -eq $script:productId }).Count -gt 0) 'Source product is unavailable for cloned order.'
    Assert-True (@($creation.amounts | Where-Object { [int]$_ -eq $script:orderAmount }).Count -gt 0) 'Source amount is unavailable for cloned order.'

    Write-Host 'Scenario 1/3: specialist route, route replacement, payment confirmation and reserve correction...'
    Set-TestProfileAvailability -SpecialistLive $true -ManagerLive $true
    $order1 = New-CompletedTestOrderFromSource -SourceOrderId $script:existingReadyOrderId -Scenario 'specialist-switch-confirm-correct'
    $initial = Publish-TestOrderAndGetPaymentLinkUtf8 -OrderId $order1
    $initialLink = Get-PaymentRow $initial.Id
    $initialAllocation = Get-AllocationRow $initialLink.AllocationId
    Assert-True ($initialLink.Method -eq 'MANUAL_MOBILE_BANK') 'Specialist route did not expose manual requisites.'
    Assert-True ($initialLink.ManualSource -eq 'CONTRACTOR_PAYMENT_PROFILE') 'Specialist route source is not frozen.'
    Assert-True ($initialAllocation.Mode -eq 'LIVE') 'Specialist allocation is not LIVE.'
    Assert-True ($initialAllocation.RecipientType -eq 'SPECIALIST') `
        "Expected SPECIALIST, got $($initialAllocation.RecipientType) / $($initialAllocation.Decision)."
    Assert-True ($initialAllocation.Status -eq 'RESERVED') 'Specialist amount was not reserved.'

    $publicInitial = Assert-ApiSuccess `
        (Invoke-Api -Method Get -Path "/api/payments/public/$($initial.Api.token)" -Anonymous) `
        'public specialist payment page'
    Assert-True ($publicInitial.paymentMethod -eq 'MANUAL_MOBILE_BANK') 'Public page lost the contractor route.'
    Assert-True (-not [string]::IsNullOrWhiteSpace([string]$publicInitial.manualPhone)) 'Transfer number is absent.'
    Assert-True (-not [string]::IsNullOrWhiteSpace([string]$publicInitial.manualRecipientName)) 'Recipient is absent.'

    $context = Assert-ApiSuccess `
        (Invoke-Api -Method Get -Path "/api/manager/orders/$order1/payment-route-change-context") `
        'payment route context'
    Assert-True ($context.canChange -eq $true) 'Fresh specialist route cannot be changed.'
    Assert-True ([long]$context.paymentLinkId -eq $initial.Id) 'Route context points to the wrong link.'

    $toOwner = Assert-ApiSuccess `
        (Invoke-Api -Method Post -Path "/api/manager/orders/$order1/payment-route-change" -Body @{
            expectedPaymentLinkId = $initial.Id
            target = 'OWNER_TBANK'
            confirmedUnpaid = $true
        }) `
        'switch specialist route to owner'
    Assert-True ($toOwner.clientNotificationScheduled -eq $true) 'Client notification was not scheduled after route replacement.'
    $releasedInitial = Wait-AllocationStatus $initialAllocation.Id @('RELEASED_UNPAID', 'CANCELED')
    Assert-True ($releasedInitial.Status -in @('RELEASED_UNPAID', 'CANCELED')) 'Old specialist reserve stayed active.'
    $ownerLink = Get-PaymentRow ([long]$toOwner.paymentLinkId)
    $ownerAllocation = Get-AllocationRow $ownerLink.AllocationId
    Assert-True ($ownerLink.Method -in @('BANK_FORM', 'SBP_QR')) 'Owner route is not acquiring.'
    Assert-True ($ownerAllocation.RecipientType -eq 'OWNER') 'Owner route did not create OWNER allocation.'
    Assert-True ($ownerAllocation.Status -eq 'OWNER_FALLBACK') 'Owner route has an unexpected allocation state.'

    $ownerPublicToken = Get-SqlScalar -Sql "SELECT token FROM payment_links WHERE id=$($ownerLink.Id);"
    $publicOwner = Assert-ApiSuccess `
        (Invoke-Api -Method Get -Path "/api/payments/public/$ownerPublicToken" -Anonymous) `
        'public owner payment page'
    Assert-True ([string]::IsNullOrWhiteSpace([string]$publicOwner.manualPhone)) 'Owner page leaked an employee transfer number.'
    Assert-True ([string]::IsNullOrWhiteSpace([string]$publicOwner.manualRecipientName)) 'Owner page leaked an employee recipient.'

    $stale = Invoke-Api -Method Post -Path "/api/manager/orders/$order1/payment-route-change" -Body @{
        expectedPaymentLinkId = $initial.Id
        target = 'EMPLOYEE_REQUISITES'
        confirmedUnpaid = $true
    }
    Assert-True ($stale.Status -eq 409) "Stale link id was not rejected (HTTP $($stale.Status))."
    Assert-True ((Get-PaymentRow $ownerLink.Id).Status -eq 'CREATED') 'Stale request mutated the current owner route.'

    $backToEmployee = Assert-ApiSuccess `
        (Invoke-Api -Method Post -Path "/api/manager/orders/$order1/payment-route-change" -Body @{
            expectedPaymentLinkId = $ownerLink.Id
            target = 'EMPLOYEE_REQUISITES'
            confirmedUnpaid = $true
        }) `
        'switch owner route back to employee'
    $employeeLink = Get-PaymentRow ([long]$backToEmployee.paymentLinkId)
    $employeeAllocation = Get-AllocationRow $employeeLink.AllocationId
    Assert-True ($employeeAllocation.RecipientType -eq 'SPECIALIST') 'Employee route did not return to specialist.'
    Assert-True ($employeeAllocation.Status -eq 'RESERVED') 'Replacement specialist reserve is not active.'
    $releasedOwner = Get-AllocationRow $ownerAllocation.Id
    Assert-True ($releasedOwner.Status -in @('OWNER_FALLBACK', 'RELEASED_UNPAID', 'CANCELED')) 'Replaced owner allocation has an unexpected status.'
    Assert-True ((Get-PaymentRow $ownerLink.Id).Status -eq 'CANCELED') 'Replaced owner payment link stayed active.'

    $manualContext = Assert-ApiSuccess `
        (Invoke-Api -Method Get -Path "/api/manager/orders/$order1/manual-card-payment-context") `
        'manual payment confirmation context'
    Assert-True (-not [string]::IsNullOrWhiteSpace([string]$manualContext.originalRecipient.key)) `
        'Manual confirmation context has no immutable recipient key.'
    Assert-ApiSuccess `
        (Invoke-Api -Method Post -Path "/api/manager/orders/$order1/confirm-manual-card-payment" -Body @{
            reason = 'Local payment routing scenario smoke'
            recipientKey = $manualContext.originalRecipient.key
            recipientType = $manualContext.originalRecipient.recipientType
            recipientProfileId = $manualContext.originalRecipient.recipientProfileId
        }) `
        'manager confirms manual payment' @(200, 204) | Out-Null
    $confirmedLink = Get-PaymentRow $employeeLink.Id
    $confirmedAllocation = Get-AllocationRow $employeeAllocation.Id
    Assert-True ($confirmedLink.Status -eq 'CONFIRMED') 'Manager confirmation did not confirm the manual link.'
    Assert-True ($confirmedAllocation.Status -in @('CONFIRMED', 'PARTIALLY_CONFIRMED')) 'Allocation was not confirmed.'
    Assert-True ($confirmedAllocation.ConfirmedKopecks -eq $confirmedAllocation.AmountKopecks) 'Confirmed amount is inconsistent.'
    $paidOrder = @(Invoke-LocalSql -Sql "SELECT CONCAT_WS(CHAR(9),IF(order_complete,1,0),COALESCE(order_pay_day,'')) FROM orders WHERE order_id=$order1;")[0] -split "`t", 2
    Assert-True ([int]$paidOrder[0] -eq 1 -and -not [string]::IsNullOrWhiteSpace($paidOrder[1])) `
        'Confirmed manual payment did not close the order.'

    $afterPaidChange = Invoke-Api -Method Post -Path "/api/manager/orders/$order1/payment-route-change" -Body @{
        expectedPaymentLinkId = $employeeLink.Id
        target = 'OWNER_TBANK'
        confirmedUnpaid = $true
    }
    Assert-True ($afterPaidChange.Status -eq 409) 'A confirmed payment route was incorrectly replaced.'

    $unsafeCancel = Invoke-Api -Method Post -Path "/api/manager/orders/$order1/payment-cancel" -Body @{}
    Assert-True ($unsafeCancel.Status -eq 409) 'Ordinary order cancellation bypassed confirmed-link reconciliation.'

    $confirmedAfterRejectedCancel = Get-AllocationRow $employeeAllocation.Id
    Assert-True ($confirmedAfterRejectedCancel.Status -eq 'CONFIRMED' -and $confirmedAfterRejectedCancel.ReturnedKopecks -eq 0) `
        'Rejected cancellation mutated the confirmed allocation.'
    $afterRejectedCancelOrder = @(Invoke-LocalSql -Sql "SELECT CONCAT_WS(CHAR(9),IF(order_complete,1,0),COALESCE(order_pay_day,'')) FROM orders WHERE order_id=$order1;")[0] -split "`t", 2
    Assert-True ([int]$afterRejectedCancelOrder[0] -eq 1 -and -not [string]::IsNullOrWhiteSpace($afterRejectedCancelOrder[1])) `
        'Rejected cancellation mutated the paid order.'

    Write-Host 'Scenario 2/3: specialist rejected, manager fallback...'
    Set-TestProfileAvailability -SpecialistLive $false -ManagerLive $true
    $order2 = New-TestOrder -Scenario 'manager-fallback'
    $managerPayment = New-PaymentLink -OrderId $order2
    $managerLink = Get-PaymentRow $managerPayment.Id
    $managerAllocation = Get-AllocationRow $managerLink.AllocationId
    Assert-True ($managerAllocation.RecipientType -eq 'MANAGER') `
        "Expected MANAGER fallback, got $($managerAllocation.RecipientType)."
    Assert-True ($managerAllocation.SpecialistRejection -eq 'LIVE_PROFILE_DISABLED') `
        "Specialist rejection was not recorded: $($managerAllocation.SpecialistRejection)."
    Assert-True ($managerAllocation.Status -eq 'RESERVED') 'Manager amount was not reserved.'
    Close-ManualLinkUnpaid -LinkId $managerLink.Id

    Write-Host 'Scenario 3/3: both employees rejected, owner fallback and atomic failed override...'
    Set-TestProfileAvailability -SpecialistLive $false -ManagerLive $false
    $order3 = New-TestOrder -Scenario 'owner-fallback-atomic-override'
    $ownerFallbackPayment = New-PaymentLink -OrderId $order3
    $ownerFallbackLink = Get-PaymentRow $ownerFallbackPayment.Id
    $ownerFallbackAllocation = Get-AllocationRow $ownerFallbackLink.AllocationId
    Assert-True ($ownerFallbackAllocation.RecipientType -eq 'OWNER') 'Expected OWNER fallback.'
    Assert-True ($ownerFallbackAllocation.SpecialistRejection -eq 'LIVE_PROFILE_DISABLED') `
        'Owner fallback lost the specialist rejection reason.'
    Assert-True ($ownerFallbackAllocation.ManagerRejection -eq 'LIVE_PROFILE_DISABLED') `
        'Owner fallback lost the manager rejection reason.'

    $blockedEmployee = Invoke-Api -Method Post -Path "/api/manager/orders/$order3/payment-route-change" -Body @{
        expectedPaymentLinkId = $ownerFallbackLink.Id
        target = 'EMPLOYEE_REQUISITES'
        confirmedUnpaid = $true
    }
    Assert-True ($blockedEmployee.Status -eq 409) 'Unavailable employee override did not fail closed.'
    $ownerAfterBlocked = Get-PaymentRow $ownerFallbackLink.Id
    Assert-True ($ownerAfterBlocked.Status -eq 'CREATED') 'Failed override canceled the old payable owner route.'
    Assert-True ((Get-AllocationRow $ownerFallbackAllocation.Id).Status -eq 'OWNER_FALLBACK') `
        'Failed override mutated the old owner allocation.'

    Set-TestProfileAvailability -SpecialistLive $true -ManagerLive $false
    $forcedEmployee = Assert-ApiSuccess `
        (Invoke-Api -Method Post -Path "/api/manager/orders/$order3/payment-route-change" -Body @{
            expectedPaymentLinkId = $ownerFallbackLink.Id
            target = 'EMPLOYEE_REQUISITES'
            confirmedUnpaid = $true
        }) `
        'retry employee override after enabling specialist'
    $forcedEmployeeLink = Get-PaymentRow ([long]$forcedEmployee.paymentLinkId)
    $forcedEmployeeAllocation = Get-AllocationRow $forcedEmployeeLink.AllocationId
    Assert-True ($forcedEmployeeAllocation.RecipientType -eq 'SPECIALIST') `
        'Eligible specialist was not selected after retry.'
    Close-ManualLinkUnpaid -LinkId $forcedEmployeeLink.Id

    $activeAllocationCount = [long](Get-SqlScalar -Sql @"
SELECT COUNT(*) FROM contractor_payment_allocations
WHERE order_id IN ($($script:testOrderIds -join ','))
  AND status IN ('RESERVED','CLIENT_REPORTED','PARTIALLY_CONFIRMED');
"@)
    Assert-True ($activeAllocationCount -eq 0) "Test left $activeAllocationCount active reserve(s)."

    $eventCount = [long](Get-SqlScalar -Sql @"
SELECT COUNT(*) FROM contractor_payment_allocation_events e
JOIN contractor_payment_allocations a ON a.id=e.allocation_id
WHERE a.order_id IN ($($script:testOrderIds -join ','));
"@)
    Assert-True ($eventCount -ge 10) 'Allocation event ledger did not record the scenario transitions.'

    $errorPatterns = ($script:testOrderIds | ForEach-Object { [string]$_ }) -join '|'
    $logs = docker logs --since 20m otziv-prod-local-app-1 2>&1
    $testErrors = @($logs | Select-String -Pattern $errorPatterns | Select-String -Pattern '\] ERROR|Exception')
    Assert-True ($testErrors.Count -eq 0) `
        "Application logged an exception for a local test order: $($testErrors -join '; ')"

    foreach ($orderId in @($script:createdTestOrderIds)) {
        if (Try-DeleteTestOrder -OrderId $orderId) {
            $deletedOrders++
        }
    }

    Write-Host "Payment routing scenario smoke PASSED: orders=$($script:testOrderIds -join ','), deleted=$deletedOrders, allocationEvents=$eventCount."
} catch {
    Write-Warning "Payment routing scenario failed before local state restoration: $($_.Exception.Message)"
    docker logs --since 10m otziv-prod-local-app-1 2>&1 | Select-Object -Last 250
    throw
} finally {
    Remove-LocalLoginClient

    if ($profileSnapshot.Count -gt 0) {
        foreach ($profileId in @($profileSnapshot.Keys)) {
            $parts = $profileSnapshot[$profileId]
            Invoke-LocalSql -Sql @"
UPDATE contractor_payment_profiles
SET enabled=$($parts[1]), live_enabled=$($parts[2]), opening_balance_kopecks=$($parts[3]),
    tracking_started_at=$(Escape-SqlString $parts[4]),
    recipient_name=CASE WHEN $(Escape-SqlString $parts[5])='~' THEN NULL ELSE CONVERT(UNHEX($(Escape-SqlString $parts[5])) USING utf8mb4) END,
    payment_phone=CASE WHEN $(Escape-SqlString $parts[6])='~' THEN NULL ELSE CONVERT(UNHEX($(Escape-SqlString $parts[6])) USING utf8mb4) END,
    bank_name=CASE WHEN $(Escape-SqlString $parts[7])='~' THEN NULL ELSE CONVERT(UNHEX($(Escape-SqlString $parts[7])) USING utf8mb4) END,
    payment_comment=CASE WHEN $(Escape-SqlString $parts[8])='~' THEN NULL ELSE CONVERT(UNHEX($(Escape-SqlString $parts[8])) USING utf8mb4) END,
    row_version=$($parts[9])
WHERE id=$profileId;
"@ | Out-Null
        }
    }
    if ($settingsSnapshot.Count -gt 0) {
        foreach ($key in @($settingsSnapshot.Keys)) {
            Invoke-LocalSql -Sql @"
UPDATE app_settings
SET setting_value=CONVERT(UNHEX($(Escape-SqlString $settingsSnapshot[$key])) USING utf8mb4), updated_at=NOW(6)
WHERE setting_key=$(Escape-SqlString $key);
"@ | Out-Null
        }
    }
    if ($mastersEnabled) {
        try {
            Restart-LocalApp -EnablePaymentRoutingMasters $false
        } catch {
            Write-Warning "Local app restore failed: $($_.Exception.Message)"
        }
    }
    $script:localLoginPassword = $null
    $script:keycloakAdminPassword = $null
    $script:userAccessToken = $null
    $script:databasePassword = $null
}
