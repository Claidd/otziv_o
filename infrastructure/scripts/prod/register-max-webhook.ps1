param(
    [string]$EnvFile = ".env.prod",
    [string]$WebhookUrl = "",
    [string]$ApiBaseUrl = "",
    [string[]]$UpdateTypes = @("bot_started", "bot_added", "message_created"),
    [switch]$DeleteExisting,
    [switch]$ListOnly,
    [switch]$AllowNoSecret,
    [switch]$Help
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Show-Help {
    @'
Register MAX Bot API webhook for production.

Examples:
  .\infrastructure\scripts\prod\register-max-webhook.ps1
  .\infrastructure\scripts\prod\register-max-webhook.ps1 -EnvFile .env.prod -DeleteExisting
  .\infrastructure\scripts\prod\register-max-webhook.ps1 -WebhookUrl https://o-ogo.ru/webhook/max
  .\infrastructure\scripts\prod\register-max-webhook.ps1 -ListOnly

Required env values:
  MAX_BOT_TOKEN
  MAX_BOT_WEBHOOK_SECRET
  OTZIV_APP_BASE_URL, unless -WebhookUrl is passed

Default events:
  bot_started, bot_added, message_created
'@ | Write-Host
}

function Get-EnvFileValue {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Name,
        [string]$DefaultValue = ""
    )

    if (-not (Test-Path -LiteralPath $Path)) {
        return $DefaultValue
    }

    foreach ($existingLine in Get-Content -LiteralPath $Path) {
        $trimmed = $existingLine.Trim()
        if ($trimmed.Length -eq 0 -or $trimmed.StartsWith("#")) {
            continue
        }

        $separator = $trimmed.IndexOf("=")
        if ($separator -le 0) {
            continue
        }

        if ($trimmed.Substring(0, $separator).Trim() -eq $Name) {
            $value = $trimmed.Substring($separator + 1).Trim()
            if (($value.StartsWith('"') -and $value.EndsWith('"')) -or ($value.StartsWith("'") -and $value.EndsWith("'"))) {
                $value = $value.Substring(1, $value.Length - 2)
            }
            return $value
        }
    }

    return $DefaultValue
}

function Join-Url {
    param(
        [Parameter(Mandatory = $true)][string]$BaseUrl,
        [Parameter(Mandatory = $true)][string]$Path
    )

    return $BaseUrl.TrimEnd("/") + "/" + $Path.TrimStart("/")
}

if ($Help) {
    Show-Help
    exit 0
}

$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = (Resolve-Path (Join-Path $scriptRoot "..\..\..")).Path
$envFilePath = if ([System.IO.Path]::IsPathRooted($EnvFile)) {
    $EnvFile
} else {
    Join-Path $repoRoot $EnvFile
}

if (-not (Test-Path -LiteralPath $envFilePath)) {
    throw "Env file not found: $envFilePath"
}

$token = Get-EnvFileValue -Path $envFilePath -Name "MAX_BOT_TOKEN"
$secret = Get-EnvFileValue -Path $envFilePath -Name "MAX_BOT_WEBHOOK_SECRET"
$appBaseUrl = Get-EnvFileValue -Path $envFilePath -Name "OTZIV_APP_BASE_URL"
$configuredApiBaseUrl = Get-EnvFileValue -Path $envFilePath -Name "MAX_BOT_API_BASE_URL" -DefaultValue "https://platform-api.max.ru"
$longPollingEnabled = Get-EnvFileValue -Path $envFilePath -Name "MAX_BOT_LONG_POLLING_ENABLED" -DefaultValue "false"

if ([string]::IsNullOrWhiteSpace($token)) {
    throw "MAX_BOT_TOKEN is empty in $envFilePath"
}

if ([string]::IsNullOrWhiteSpace($ApiBaseUrl)) {
    $ApiBaseUrl = $configuredApiBaseUrl
}
$ApiBaseUrl = $ApiBaseUrl.TrimEnd("/")

if ([string]::IsNullOrWhiteSpace($WebhookUrl)) {
    if ([string]::IsNullOrWhiteSpace($appBaseUrl)) {
        throw "Pass -WebhookUrl or set OTZIV_APP_BASE_URL in $envFilePath"
    }
    $WebhookUrl = Join-Url -BaseUrl $appBaseUrl -Path "/webhook/max"
}

if (-not $WebhookUrl.StartsWith("https://", [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "MAX production webhook must use https:// and port 443: $WebhookUrl"
}

if ([string]::IsNullOrWhiteSpace($secret) -and -not $AllowNoSecret) {
    throw "MAX_BOT_WEBHOOK_SECRET is empty. Set it or pass -AllowNoSecret explicitly."
}

if (-not [string]::IsNullOrWhiteSpace($secret) -and $secret -notmatch "^[A-Za-z0-9_-]{5,256}$") {
    throw "MAX_BOT_WEBHOOK_SECRET must contain only A-Z, a-z, 0-9, underscore or dash, length 5-256."
}

if ($longPollingEnabled -match "^(?i:true|1|yes)$") {
    Write-Warning "MAX_BOT_LONG_POLLING_ENABLED is true. MAX docs say production should use Webhook, not Long Polling."
}

$headers = @{
    Authorization = $token
}

if ($ListOnly) {
    $subscriptions = Invoke-RestMethod `
        -Method Get `
        -Uri (Join-Url -BaseUrl $ApiBaseUrl -Path "/subscriptions") `
        -Headers $headers
    $subscriptions | ConvertTo-Json -Depth 8
    exit 0
}

if ($DeleteExisting) {
    $deleteUrl = Join-Url -BaseUrl $ApiBaseUrl -Path "/subscriptions"
    $deleteUrl = $deleteUrl + "?url=" + [System.Uri]::EscapeDataString($WebhookUrl)
    $deleteResult = Invoke-RestMethod -Method Delete -Uri $deleteUrl -Headers $headers
    Write-Host "Deleted existing MAX subscription for $WebhookUrl"
    $deleteResult | ConvertTo-Json -Depth 8
}

$body = [ordered]@{
    url = $WebhookUrl
    update_types = $UpdateTypes
}

if (-not [string]::IsNullOrWhiteSpace($secret)) {
    $body.secret = $secret
}

$jsonBody = $body | ConvertTo-Json -Depth 6
$result = Invoke-RestMethod `
    -Method Post `
    -Uri (Join-Url -BaseUrl $ApiBaseUrl -Path "/subscriptions") `
    -Headers $headers `
    -ContentType "application/json" `
    -Body $jsonBody

Write-Host "MAX webhook registered:"
Write-Host "  url=$WebhookUrl"
Write-Host "  update_types=$($UpdateTypes -join ',')"
$result | ConvertTo-Json -Depth 8
