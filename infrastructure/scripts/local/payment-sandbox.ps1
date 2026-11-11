<#
.SYNOPSIS
    Switches the local prod-like stand into the payment sandbox and back.

.DESCRIPTION
    The ordinary stand keeps every payment and messaging switch off, so issuing a card number or a bank
    link is refused there. In the sandbox the same code paths run as on production:

      * the new payment system chooses recipients and reserves limits; employees get test requisites;
      * T-Bank and Tochka answer from the local fixture (links, cancellation, "the client paid");
      * client messages are kept by the fixture instead of being sent.

    Nothing leaves the machine: the overlay replaces every real bank and messenger credential with a
    local marker, this script checks the resolved configuration before starting, and the application
    itself refuses to start in the sandbox profile if a real credential is left.

    Control page with links, "pay" buttons and captured messages: http://localhost:8099

    Details: docs/invoice-flows/06-local-payment-sandbox.md

.PARAMETER Action
    on      - switch the sandbox on (the stand must already be running)
    off     - return the stand to its ordinary safe state
    status  - show what is active (default)

.EXAMPLE
    ./infrastructure/scripts/local/payment-sandbox.ps1 on
#>
param(
    [Parameter(Position = 0)][ValidateSet('on', 'off', 'status')][string]$Action = 'status',
    [ValidateRange(60, 3600)][int]$TimeoutSeconds = 900
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$project = 'otziv-prod-local'
$appContainer = "$project-app-1"
$mysqlContainer = "$project-mysql-1"
$nginxContainer = "$project-nginx-1"
$fixtureContainer = "$project-paper-invoice-fixture-1"
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
$baseCompose = Join-Path $root 'compose.prod-local.yaml'
$fixtureCompose = Join-Path $root 'infrastructure\scripts\local\paper-invoice-fixture\compose.yaml'
$sandboxCompose = Join-Path $root 'infrastructure\scripts\local\payment-sandbox\compose.yaml'
$fixtureUrl = 'http://paper-invoice-fixture:8099'
$sandboxProfiles = 'prod,paper-invoice-fixture,payment-sandbox'

# The same keys the restore script switches off; "on" is what production runs with.
$settings = [ordered]@{
    'client.messages.live.enabled'                        = @('true', 'false')
    'client.messages.immediate.enabled'                   = @('true', 'false')
    'client.messages.payment-overdue.live-enabled'        = @('true', 'false')
    'client.messages.payment-instruction-source'          = @('BANK_LINK', 'MANAGER_TEXT')
    # LIVE only picks the terminal set and makes a payment count; both sets are local markers here.
    # In TEST a paid order link stays a trial (TEST_CONFIRMED) and the order is not moved.
    'payments.tbank.runtime-mode'                         = @('LIVE', 'TEST')
    'payments.tbank.enabled'                              = @('true', 'false')
    'payments.tbank.payment-links-enabled'                = @('true', 'false')
    'payments.tbank.manager-ui-enabled'                   = @('true', 'false')
    'payments.tbank.apply-confirmed-payments'             = @('true', 'false')
    'contractor-payments.shadow-enabled'                  = @('true', 'true')
    'contractor-payments.live-routing-enabled'            = @('true', 'false')
    'contractor-payments.reward-attribution-live-enabled' = @('true', 'false')
    'contractor-payments.live-readiness-confirmed'        = @('true', 'false')
}

# Docker writes its progress to stderr. Under "Stop", Windows PowerShell 5.1 turns such a line into a
# terminating error as soon as the caller redirects the script's output, so every docker call goes through here:
# the progress is shown as plain text and only the exit code decides whether the step failed.
function Invoke-Docker {
    param([Parameter(Mandatory = $true)][string[]]$Arguments, [switch]$Capture)
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        if ($Capture) {
            $output = @(& docker @Arguments 2>$null)
        } else {
            # Compose repeats one warning per old MySQL volume of the stand; it says nothing about this step.
            & docker @Arguments 2>&1 | ForEach-Object { "$_" } |
                Where-Object { $_ -notmatch 'level=warning msg="volume ' } |
                ForEach-Object { Write-Host $_ }
        }
        $code = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previous
    }
    if ($code -ne 0) { throw "docker $($Arguments[0]) failed with exit code $code." }
    if ($Capture) { return $output }
}

function Get-ContainerStatus {
    param([Parameter(Mandatory = $true)][string]$Name)
    $status = @(Invoke-Docker -Capture -Arguments @('ps', '-a', '--filter', "name=^/$Name`$", '--format', '{{.Status}}'))
    if ($status.Count -eq 0) { return '' }
    return [string]$status[0]
}

function Get-StandApp {
    if (-not (Get-ContainerStatus -Name $appContainer)) {
        throw "The stand is not running ($appContainer not found). Start it first: ./infrastructure/scripts/local/prod-like-smoke.ps1"
    }
    $app = ((Invoke-Docker -Capture -Arguments @('inspect', $appContainer)) -join "`n" | ConvertFrom-Json)[0]
    if ($app.Config.Labels.'com.docker.compose.project' -ne $project) {
        throw "Container $appContainer does not belong to the $project compose project."
    }
    return $app
}

function Get-AppEnvironment {
    param([Parameter(Mandatory = $true)]$App)
    $values = @{}
    foreach ($pair in $App.Config.Env) {
        $name, $value = $pair.Split('=', 2)
        $values[$name] = $value
    }
    return $values
}

# compose.prod-local.yaml takes several values from *_LOCAL_DOCKER variables that the smoke script passes
# through the process environment. Carry the running container's values over, so that a recreate keeps them.
function Import-LocalDockerVariables {
    param([Parameter(Mandatory = $true)][hashtable]$Current)
    foreach ($line in Get-Content -LiteralPath $baseCompose) {
        if ($line -match '^\s+([A-Z_0-9]+): \$\{([A-Z_0-9]+_LOCAL_DOCKER)\}\s*$' -and $Current.ContainsKey($Matches[1])) {
            [Environment]::SetEnvironmentVariable($Matches[2], $Current[$Matches[1]], 'Process')
        }
    }
}

function Get-ComposeArguments {
    param([Parameter(Mandatory = $true)][string]$EnvFile, [switch]$Sandbox)
    $arguments = @('compose', '--project-directory', $root, '--env-file', $EnvFile, '-f', $baseCompose)
    if ($Sandbox) {
        $arguments += @('-f', $fixtureCompose, '-f', $sandboxCompose)
    }
    return $arguments
}

function Assert-SandboxIsolation {
    param([Parameter(Mandatory = $true)][string[]]$ComposeArguments)
    $config = (Invoke-Docker -Capture -Arguments ($ComposeArguments + @('config', '--format', 'json'))) -join "`n" | ConvertFrom-Json
    $environment = $config.services.app.environment
    $problems = @()
    function Read-Value([string]$Name) {
        $property = $environment.PSObject.Properties[$Name]
        if ($null -eq $property -or $null -eq $property.Value) { return '' }
        return [string]$property.Value
    }
    if ((Read-Value 'SPRING_PROFILES_ACTIVE') -ne $sandboxProfiles) { $problems += 'SPRING_PROFILES_ACTIVE' }
    if ((Read-Value 'OTZIV_PAYMENTS_TBANK_BASE_URL') -ne "$fixtureUrl/tbank") { $problems += 'OTZIV_PAYMENTS_TBANK_BASE_URL' }
    if ((Read-Value 'PAPER_INVOICES_FIXTURE_URL') -ne $fixtureUrl) { $problems += 'PAPER_INVOICES_FIXTURE_URL' }
    if ((Read-Value 'OTZIV_PAYMENTS_TOCHKA_JWT_TOKEN') -ne 'local-fixture-only') { $problems += 'OTZIV_PAYMENTS_TOCHKA_JWT_TOKEN' }
    if ((Read-Value 'MAX_BOT_TOKEN') -ne '') { $problems += 'MAX_BOT_TOKEN' }
    if ((Read-Value 'TELEGRAM_BOT_SENDING_ENABLED') -ne 'false') { $problems += 'TELEGRAM_BOT_SENDING_ENABLED' }
    if ((Read-Value 'OTZIV_MOBILE_PUSH_FIREBASE_ENABLED') -ne 'false') { $problems += 'OTZIV_MOBILE_PUSH_FIREBASE_ENABLED' }
    if ((Read-Value 'MAIL_HOST') -ne 'localhost') { $problems += 'MAIL_HOST' }
    foreach ($property in $environment.PSObject.Properties) {
        $value = [string]$property.Value
        if ($property.Name -match '^OTZIV_PAYMENTS_TBANK_.*TERMINAL_KEY$' -and $value -notin @('', 'LocalSandboxPrimary', 'LocalSandboxSecondary')) {
            $problems += $property.Name
        }
        if ($property.Name -match '^OTZIV_PAYMENTS_TBANK_.*PASSWORD$' -and $value -notin @('', 'local-sandbox-only')) {
            $problems += $property.Name
        }
        if ($property.Name -match '^WHATSAPP_CLIENTS' -and $value -ne '') { $problems += $property.Name }
    }
    if ($problems.Count -gt 0) {
        throw "The sandbox is not isolated; nothing was changed. Not a local marker: $($problems -join ', ')"
    }
}

function Invoke-StandSql {
    param([Parameter(Mandatory = $true)][string]$Sql, [switch]$Table)
    $format = '-N -B'
    if ($Table) { $format = '-t' }
    # No double quotes inside: Windows PowerShell 5.1 drops them from native arguments.
    $command = "MYSQL_PWD=`$MYSQL_ROOT_PASSWORD; export MYSQL_PWD; exec mysql -uroot $format --default-character-set=utf8mb4 `$MYSQL_DATABASE"
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = @($Sql | & docker exec -i $mysqlContainer sh -c $command 2>$null)
        $code = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previous
    }
    if ($code -ne 0) { throw 'A database command failed on the stand.' }
    return $output
}

function Set-StandSettings {
    param([Parameter(Mandatory = $true)][int]$Column)
    $rows = @()
    foreach ($key in $settings.Keys) {
        $rows += "('$key', '$($settings[$key][$Column])', NOW(6))"
    }
    Invoke-StandSql -Sql @"
INSERT INTO app_settings (setting_key, setting_value, updated_at)
VALUES
  $($rows -join ",`n  ")
ON DUPLICATE KEY UPDATE setting_value = VALUES(setting_value), updated_at = VALUES(updated_at);
"@ | Out-Null
}

function Wait-AppHealthy {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        if ((Get-ContainerStatus -Name $appContainer) -like '*(healthy)*') { return }
        Start-Sleep -Seconds 5
    }
    Write-Host ''
    & cmd /c "docker logs --tail 40 $appContainer 2>&1" | ForEach-Object { Write-Host "$_" }
    throw "The application did not become healthy in $TimeoutSeconds seconds. If the log above says 'refused to start', a real credential is still configured; if it has no 'Local payment sandbox' line, rebuild the app image from the current sources."
}

function Get-SandboxLogLines {
    # cmd merges the container's stderr into stdout before PowerShell sees it.
    $lines = @(& cmd /c "docker logs $appContainer 2>&1" | Select-String -SimpleMatch 'Local payment sandbox')
    return $lines | ForEach-Object { ($_.Line -replace '^.*?(Local payment sandbox)', '$1').Trim() }
}

function Show-Status {
    $app = Get-StandApp
    $current = Get-AppEnvironment -App $app
    $active = $current['SPRING_PROFILES_ACTIVE'] -eq $sandboxProfiles
    if ($active) {
        Write-Host 'Payment sandbox: ON' -ForegroundColor Green
    } else {
        Write-Host 'Payment sandbox: OFF (ordinary safe stand)' -ForegroundColor Yellow
    }
    Write-Host "Application profiles: $($current['SPRING_PROFILES_ACTIVE'])"
    $fixtureState = Get-ContainerStatus -Name $fixtureContainer
    if (-not $fixtureState) { $fixtureState = 'absent' }
    Write-Host "Bank and message fixture: $fixtureState"
    $keys = ($settings.Keys | ForEach-Object { "'$_'" }) -join ','
    Invoke-StandSql -Table -Sql "SELECT setting_key, setting_value FROM app_settings WHERE setting_key IN ($keys) ORDER BY setting_key;" | Write-Host
    Invoke-StandSql -Table -Sql 'SELECT COUNT(*) AS profiles, SUM(enabled) AS enabled, SUM(live_enabled) AS admitted FROM contractor_payment_profiles;' | Write-Host
    if ($active) {
        Get-SandboxLogLines | Select-Object -Last 3 | ForEach-Object { Write-Host $_ }
        Write-Host 'Control page (links, "pay" buttons, captured client messages): http://localhost:8099'
    }
}

if ($Action -eq 'status') {
    Show-Status
    return
}

$app = Get-StandApp
$current = Get-AppEnvironment -App $app
$envFile = $app.Config.Labels.'com.docker.compose.project.environment_file'
if ([string]::IsNullOrWhiteSpace($envFile) -or -not (Test-Path -LiteralPath $envFile)) {
    throw 'Cannot find the env file the stand was started with.'
}
Import-LocalDockerVariables -Current $current

if ($Action -eq 'on') {
    $compose = Get-ComposeArguments -EnvFile $envFile -Sandbox
    Assert-SandboxIsolation -ComposeArguments $compose
    Write-Host 'Isolation checked: every bank and messenger value is a local marker.'

    Invoke-Docker -Arguments ($compose + @('up', '-d', '--no-build', '--no-deps', 'paper-invoice-fixture'))
    Set-StandSettings -Column 0
    Invoke-Docker -Arguments ($compose + @('up', '-d', '--no-build', '--no-deps', '--force-recreate', 'app'))
    Wait-AppHealthy
    Invoke-Docker -Arguments @('restart', $nginxContainer)

    if (-not (Get-SandboxLogLines | Where-Object { $_ -like 'Local payment sandbox is active*' })) {
        throw 'The application started without the sandbox code. Rebuild the app image from the current sources, then run this script again.'
    }
    Write-Host ''
    Show-Status
    return
}

# off: first make the database safe, then drop the fixture and start the ordinary application.
Set-StandSettings -Column 1
Invoke-StandSql -Sql @'
UPDATE contractor_payment_profiles
SET enabled = FALSE, live_enabled = FALSE, recipient_name = NULL, payment_phone = NULL, bank_name = NULL, payment_comment = NULL;
'@ | Out-Null
$compose = Get-ComposeArguments -EnvFile $envFile
Invoke-Docker -Arguments ($compose + @('up', '-d', '--no-build', '--no-deps', '--force-recreate', 'app'))
Wait-AppHealthy
Invoke-Docker -Arguments @('restart', $nginxContainer)
if (Get-ContainerStatus -Name $fixtureContainer) {
    Invoke-Docker -Arguments @('rm', '-f', $fixtureContainer)
}
Write-Host ''
Show-Status
