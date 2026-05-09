param(
    [string]$EnvFile = ".env.prod-local",
    [string]$ComposeFile = "compose.prod-local.yaml",
    [string]$BaseUrl = "http://localhost:8088",
    [string]$Date = (Get-Date -Format "yyyy-MM-dd"),
    [string]$AdminUsername = "alex",
    [string]$OwnerUsername = "hunt",
    [string]$WorkerUsername = "luboff",
    [long]$WorkerUserId = 6,
    [string]$RebuildStartMonth = "",
    [int]$TimeoutSeconds = 1200,
    [switch]$SkipRebuild,
    [switch]$NoBuild,
    [switch]$NoRestore
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Read-EnvFile {
    param([Parameter(Mandatory = $true)][string]$Path)

    $values = @{}
    foreach ($line in Get-Content -LiteralPath $Path) {
        $trimmed = $line.Trim()
        if ($trimmed.Length -eq 0 -or $trimmed.StartsWith("#")) {
            continue
        }

        $separator = $trimmed.IndexOf("=")
        if ($separator -le 0) {
            continue
        }

        $name = $trimmed.Substring(0, $separator).Trim()
        $value = $trimmed.Substring($separator + 1).Trim().Trim('"')
        $values[$name] = $value
    }

    return $values
}

function Set-ProcessEnvValue {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [AllowNull()][string]$Value
    )

    [Environment]::SetEnvironmentVariable($Name, $Value, "Process")
}

function Invoke-Smoke {
    param(
        [Parameter(Mandatory = $true)][string]$SmokePath,
        [Parameter(Mandatory = $true)][string]$EnvPath,
        [Parameter(Mandatory = $true)][string]$ComposePath,
        [Parameter(Mandatory = $true)][string]$SmokeBaseUrl,
        [Parameter(Mandatory = $true)][int]$SmokeTimeoutSeconds,
        [switch]$SkipBuild
    )

    $arguments = @{
        EnvFile = $EnvPath
        ComposeFile = $ComposePath
        BaseUrl = $SmokeBaseUrl
        TimeoutSeconds = $SmokeTimeoutSeconds
    }

    if ($SkipBuild) {
        $arguments["NoBuild"] = $true
    }

    & $SmokePath @arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Smoke script failed: $SmokePath"
    }
}

function ConvertTo-Base64Url {
    param([Parameter(Mandatory = $true)][byte[]]$Bytes)

    return [Convert]::ToBase64String($Bytes).TrimEnd("=").Replace("+", "-").Replace("/", "_")
}

function New-RandomBase64Url {
    param([int]$ByteCount = 32)

    $bytes = New-Object byte[] $ByteCount
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $rng.GetBytes($bytes)
    } finally {
        $rng.Dispose()
    }

    return ConvertTo-Base64Url -Bytes $bytes
}

function New-CodeChallenge {
    param([Parameter(Mandatory = $true)][string]$Verifier)

    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        $bytes = [System.Text.Encoding]::ASCII.GetBytes($Verifier)
        return ConvertTo-Base64Url -Bytes $sha.ComputeHash($bytes)
    } finally {
        $sha.Dispose()
    }
}

function Get-PropertyValue {
    param(
        [Parameter(Mandatory = $true)]$InputObject,
        [Parameter(Mandatory = $true)][string]$Name
    )

    $property = $InputObject.PSObject.Properties[$Name]
    if ($null -eq $property) {
        return $null
    }

    return $property.Value
}

function New-QueryString {
    param([Parameter(Mandatory = $true)][hashtable]$Values)

    return ($Values.GetEnumerator() | Sort-Object Name | ForEach-Object {
        "$($_.Name)=$([Uri]::EscapeDataString([string]$_.Value))"
    }) -join "&"
}

function Invoke-JsonGet {
    param(
        [Parameter(Mandatory = $true)][string]$Url,
        [Parameter(Mandatory = $true)][hashtable]$Headers
    )

    return Invoke-RestMethod -Method Get -Uri $Url -Headers $Headers
}

function Invoke-JsonPost {
    param(
        [Parameter(Mandatory = $true)][string]$Url,
        [Parameter(Mandatory = $true)][hashtable]$Headers
    )

    return Invoke-RestMethod -Method Post -Uri $Url -Headers $Headers
}

function Get-ServiceAccountToken {
    param(
        [Parameter(Mandatory = $true)][string]$RootUrl,
        [Parameter(Mandatory = $true)][string]$ClientId,
        [Parameter(Mandatory = $true)][string]$ClientSecret
    )

    $tokenUrl = "$RootUrl/keycloak/realms/otziv/protocol/openid-connect/token"
    $response = Invoke-RestMethod -Method Post -Uri $tokenUrl -Body @{
        grant_type = "client_credentials"
        client_id = $ClientId
        client_secret = $ClientSecret
    }

    return $response.access_token
}

function Get-KeycloakAdminToken {
    param(
        [Parameter(Mandatory = $true)][string]$RootUrl,
        [Parameter(Mandatory = $true)][string]$Username,
        [Parameter(Mandatory = $true)][string]$Password
    )

    $tokenUrl = "$RootUrl/keycloak/realms/master/protocol/openid-connect/token"
    $response = Invoke-RestMethod -Method Post -Uri $tokenUrl -Body @{
        grant_type = "password"
        client_id = "admin-cli"
        username = $Username
        password = $Password
    }

    return $response.access_token
}

function Get-ImpersonatedToken {
    param(
        [Parameter(Mandatory = $true)][string]$RootUrl,
        [Parameter(Mandatory = $true)][string]$Username,
        [Parameter(Mandatory = $true)][string]$KeycloakAdminUsername,
        [Parameter(Mandatory = $true)][string]$KeycloakAdminPassword
    )

    $adminToken = Get-KeycloakAdminToken -RootUrl $RootUrl -Username $KeycloakAdminUsername -Password $KeycloakAdminPassword
    $adminHeaders = @{ Authorization = "Bearer $adminToken" }
    $usersUrl = "$RootUrl/keycloak/admin/realms/otziv/users?username=$([Uri]::EscapeDataString($Username))&exact=true"
    $users = Invoke-RestMethod -Method Get -Uri $usersUrl -Headers $adminHeaders
    if ($null -eq $users -or $users.Count -eq 0) {
        throw "Keycloak user not found: $Username"
    }

    $userId = $users[0].id
    $impersonation = Invoke-WebRequest `
        -Method Post `
        -Uri "$RootUrl/keycloak/admin/realms/otziv/users/$userId/impersonation" `
        -Headers $adminHeaders

    $setCookies = @($impersonation.Headers["Set-Cookie"])
    $cookieHeader = ($setCookies | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | ForEach-Object {
        ($_ -split ";")[0]
    }) -join "; "

    if ([string]::IsNullOrWhiteSpace($cookieHeader)) {
        throw "Keycloak impersonation did not return a browser session for $Username."
    }

    $redirectUri = "$RootUrl/"
    $verifier = New-RandomBase64Url
    $challenge = New-CodeChallenge -Verifier $verifier
    $authUrl = "$RootUrl/keycloak/realms/otziv/protocol/openid-connect/auth?" + (New-QueryString -Values @{
        client_id = "otziv-frontend"
        redirect_uri = $redirectUri
        response_type = "code"
        scope = "openid"
        state = "local-analytics-verify"
        nonce = "local-analytics-verify"
        code_challenge = $challenge
        code_challenge_method = "S256"
    })

    try {
        $authResponse = Invoke-WebRequest -Uri $authUrl -Headers @{ Cookie = $cookieHeader } -MaximumRedirection 0 -ErrorAction Stop
    } catch {
        $authResponse = $_.Exception.Response
    }

    $location = [string]$authResponse.Headers.Location
    if ([string]::IsNullOrWhiteSpace($location) -or $location -notmatch "[?&]code=([^&]+)") {
        throw "Keycloak did not return an auth code for $Username. Location=$location"
    }

    $code = [Uri]::UnescapeDataString($Matches[1])
    $tokenResponse = Invoke-RestMethod `
        -Method Post `
        -Uri "$RootUrl/keycloak/realms/otziv/protocol/openid-connect/token" `
        -Body @{
            grant_type = "authorization_code"
            client_id = "otziv-frontend"
            redirect_uri = $redirectUri
            code = $code
            code_verifier = $verifier
        }

    return $tokenResponse.access_token
}

function Assert-ContainerFlags {
    param(
        [Parameter(Mandatory = $true)][string[]]$ComposeArguments,
        [Parameter(Mandatory = $true)][bool]$ExpectedReadEnabled,
        [Parameter(Mandatory = $true)][bool]$ExpectedRebuildApiEnabled
    )

    $appContainer = (& docker @($ComposeArguments + @("ps", "-q", "app"))).Trim()
    if ([string]::IsNullOrWhiteSpace($appContainer)) {
        throw "Could not resolve app container id."
    }

    $envText = (& docker @("exec", $appContainer, "sh", "-c", "env | sort | grep OTZIV_ANALYTICS")) -join [Environment]::NewLine
    Write-Host $envText

    $expectedRead = "OTZIV_ANALYTICS_AGGREGATES_READ_ENABLED=$($ExpectedReadEnabled.ToString().ToLowerInvariant())"
    $expectedRebuildApi = "OTZIV_ANALYTICS_REBUILD_API_ENABLED=$($ExpectedRebuildApiEnabled.ToString().ToLowerInvariant())"
    if ($envText -notmatch [regex]::Escape($expectedRead)) {
        throw "Unexpected aggregate read flag. Expected $expectedRead."
    }
    if ($envText -notmatch [regex]::Escape($expectedRebuildApi)) {
        throw "Unexpected rebuild API flag. Expected $expectedRebuildApi."
    }
    if ($envText -notmatch "OTZIV_ANALYTICS_REBUILD_SCHEDULE_ENABLED=false") {
        throw "The scheduled aggregate rebuild must stay disabled during local verification."
    }
}

function Invoke-RebuildWindow {
    param(
        [Parameter(Mandatory = $true)][string]$RootUrl,
        [Parameter(Mandatory = $true)][hashtable]$Headers,
        [Parameter(Mandatory = $true)][DateTime]$SelectedDate,
        [AllowEmptyString()][string]$StartMonth
    )

    if ([string]::IsNullOrWhiteSpace($StartMonth)) {
        $start = [DateTime]::new($SelectedDate.Year - 1, 1, 1)
    } else {
        $start = [DateTime]::ParseExact("$StartMonth-01", "yyyy-MM-dd", [Globalization.CultureInfo]::InvariantCulture)
    }

    $end = [DateTime]::new($SelectedDate.Year, $SelectedDate.Month, 1)
    if ($start -gt $end) {
        throw "Rebuild start month $($start.ToString("yyyy-MM")) is after selected month $($end.ToString("yyyy-MM"))."
    }

    Write-Host "Rebuilding analytics aggregates from $($start.ToString("yyyy-MM")) to $($end.ToString("yyyy-MM"))..."
    $month = $start
    while ($month -le $end) {
        $closed = $month -lt $end
        $url = "$RootUrl/api/admin/analytics/aggregates/rebuild-month?" + (New-QueryString -Values @{
            month = $month.ToString("yyyy-MM")
            closed = $closed.ToString().ToLowerInvariant()
        })

        $result = Invoke-JsonPost -Url $url -Headers $Headers
        $adminComparison = Get-PropertyValue -InputObject $result -Name "adminComparison"
        $matches = if ($null -ne $adminComparison) { Get-PropertyValue -InputObject $adminComparison -Name "matches" } else { $null }
        if ($matches -eq $false) {
            throw "Admin aggregate verification failed after rebuilding $($month.ToString("yyyy-MM"))."
        }

        Write-Host "  rebuilt $($month.ToString("yyyy-MM")) closed=$($closed.ToString().ToLowerInvariant())"
        $month = $month.AddMonths(1)
    }
}

function Assert-Compare {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$Url,
        [Parameter(Mandatory = $true)][hashtable]$Headers
    )

    $result = Invoke-JsonGet -Url $Url -Headers $Headers
    $matches = Get-PropertyValue -InputObject $result -Name "matches"
    $mismatchCount = Get-PropertyValue -InputObject $result -Name "mismatchCount"

    if ($matches -ne $true) {
        $json = $result | ConvertTo-Json -Depth 8
        throw "Compare failed for $Name. Result: $json"
    }
    if ($null -ne $mismatchCount -and [int]$mismatchCount -ne 0) {
        throw "Compare failed for $Name. mismatchCount=$mismatchCount"
    }

    Write-Host "  compare OK: $Name"
}

function Invoke-CompareChecks {
    param(
        [Parameter(Mandatory = $true)][string]$RootUrl,
        [Parameter(Mandatory = $true)][hashtable]$Headers,
        [Parameter(Mandatory = $true)][string]$SelectedDate,
        [Parameter(Mandatory = $true)][string]$AdminUser,
        [Parameter(Mandatory = $true)][string]$OwnerUser,
        [Parameter(Mandatory = $true)][long]$WorkerId
    )

    Write-Host "Running aggregate compare checks..."
    Assert-Compare -Name "analyse $AdminUser ADMIN" -Headers $Headers -Url "$RootUrl/api/admin/analytics/aggregates/compare-cabinet-analyse?$(New-QueryString -Values @{ username = $AdminUser; date = $SelectedDate; role = "ADMIN" })"
    Assert-Compare -Name "analyse $OwnerUser OWNER" -Headers $Headers -Url "$RootUrl/api/admin/analytics/aggregates/compare-cabinet-analyse?$(New-QueryString -Values @{ username = $OwnerUser; date = $SelectedDate; role = "OWNER" })"
    Assert-Compare -Name "score" -Headers $Headers -Url "$RootUrl/api/admin/analytics/aggregates/compare-score?$(New-QueryString -Values @{ date = $SelectedDate })"
    Assert-Compare -Name "team $OwnerUser OWNER" -Headers $Headers -Url "$RootUrl/api/admin/analytics/aggregates/compare-team?$(New-QueryString -Values @{ username = $OwnerUser; date = $SelectedDate; role = "OWNER" })"
    Assert-Compare -Name "user-stats userId=$WorkerId" -Headers $Headers -Url "$RootUrl/api/admin/analytics/aggregates/compare-user-stats?$(New-QueryString -Values @{ userId = $WorkerId; date = $SelectedDate })"
}

function Assert-CabinetEndpoint {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$Url,
        [Parameter(Mandatory = $true)][hashtable]$Headers
    )

    $response = Invoke-WebRequest -Headers $Headers -Uri $Url -UseBasicParsing
    if ($response.StatusCode -lt 200 -or $response.StatusCode -ge 300) {
        throw "Cabinet endpoint failed for $Name. Status=$($response.StatusCode)"
    }

    $null = $response.Content | ConvertFrom-Json
    Write-Host "  endpoint OK: $Name ($($response.StatusCode))"
}

function Invoke-CabinetChecks {
    param(
        [Parameter(Mandatory = $true)][string]$RootUrl,
        [Parameter(Mandatory = $true)][string]$SelectedDate,
        [Parameter(Mandatory = $true)][string]$AdminUser,
        [Parameter(Mandatory = $true)][string]$OwnerUser,
        [Parameter(Mandatory = $true)][string]$WorkerUser,
        [Parameter(Mandatory = $true)][long]$WorkerId,
        [Parameter(Mandatory = $true)][string]$KeycloakAdminUsername,
        [Parameter(Mandatory = $true)][string]$KeycloakAdminPassword
    )

    Write-Host "Running real /api/cabinet endpoint checks with impersonated user tokens..."

    $ownerToken = Get-ImpersonatedToken -RootUrl $RootUrl -Username $OwnerUser -KeycloakAdminUsername $KeycloakAdminUsername -KeycloakAdminPassword $KeycloakAdminPassword
    $ownerHeaders = @{ Authorization = "Bearer $ownerToken" }
    Assert-CabinetEndpoint -Name "$OwnerUser profile" -Headers $ownerHeaders -Url "$RootUrl/api/cabinet/profile?$(New-QueryString -Values @{ date = $SelectedDate; refresh = "true" })"
    Assert-CabinetEndpoint -Name "$OwnerUser user-info userId=$WorkerId" -Headers $ownerHeaders -Url "$RootUrl/api/cabinet/user-info?$(New-QueryString -Values @{ userId = $WorkerId; date = $SelectedDate; refresh = "true" })"
    Assert-CabinetEndpoint -Name "$OwnerUser team" -Headers $ownerHeaders -Url "$RootUrl/api/cabinet/team?$(New-QueryString -Values @{ date = $SelectedDate; refresh = "true" })"
    Assert-CabinetEndpoint -Name "$OwnerUser score" -Headers $ownerHeaders -Url "$RootUrl/api/cabinet/score?$(New-QueryString -Values @{ date = $SelectedDate; refresh = "true" })"
    Assert-CabinetEndpoint -Name "$OwnerUser analyse" -Headers $ownerHeaders -Url "$RootUrl/api/cabinet/analyse?$(New-QueryString -Values @{ date = $SelectedDate; refresh = "true" })"

    $adminToken = Get-ImpersonatedToken -RootUrl $RootUrl -Username $AdminUser -KeycloakAdminUsername $KeycloakAdminUsername -KeycloakAdminPassword $KeycloakAdminPassword
    $adminHeaders = @{ Authorization = "Bearer $adminToken" }
    Assert-CabinetEndpoint -Name "$AdminUser profile" -Headers $adminHeaders -Url "$RootUrl/api/cabinet/profile?$(New-QueryString -Values @{ date = $SelectedDate; refresh = "true" })"
    Assert-CabinetEndpoint -Name "$AdminUser team" -Headers $adminHeaders -Url "$RootUrl/api/cabinet/team?$(New-QueryString -Values @{ date = $SelectedDate; refresh = "true" })"
    Assert-CabinetEndpoint -Name "$AdminUser score" -Headers $adminHeaders -Url "$RootUrl/api/cabinet/score?$(New-QueryString -Values @{ date = $SelectedDate; refresh = "true" })"
    Assert-CabinetEndpoint -Name "$AdminUser analyse" -Headers $adminHeaders -Url "$RootUrl/api/cabinet/analyse?$(New-QueryString -Values @{ date = $SelectedDate; refresh = "true" })"

    if (-not [string]::IsNullOrWhiteSpace($WorkerUser)) {
        $workerToken = Get-ImpersonatedToken -RootUrl $RootUrl -Username $WorkerUser -KeycloakAdminUsername $KeycloakAdminUsername -KeycloakAdminPassword $KeycloakAdminPassword
        $workerHeaders = @{ Authorization = "Bearer $workerToken" }
        Assert-CabinetEndpoint -Name "$WorkerUser profile" -Headers $workerHeaders -Url "$RootUrl/api/cabinet/profile?$(New-QueryString -Values @{ date = $SelectedDate; refresh = "true" })"
        Assert-CabinetEndpoint -Name "$WorkerUser score" -Headers $workerHeaders -Url "$RootUrl/api/cabinet/score?$(New-QueryString -Values @{ date = $SelectedDate; refresh = "true" })"
    }
}

$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = (Resolve-Path (Join-Path $scriptRoot "..\..\..")).Path
$smokePath = Join-Path $scriptRoot "prod-like-smoke.ps1"
$composePath = if ([System.IO.Path]::IsPathRooted($ComposeFile)) { $ComposeFile } else { Join-Path $repoRoot $ComposeFile }
$envPath = if ([System.IO.Path]::IsPathRooted($EnvFile)) { $EnvFile } else { Join-Path $repoRoot $EnvFile }
$rootUrl = $BaseUrl.TrimEnd("/")

if (-not (Test-Path -LiteralPath $smokePath)) {
    throw "Smoke script not found: $smokePath"
}
if (-not (Test-Path -LiteralPath $composePath)) {
    throw "Compose file not found: $composePath"
}
if (-not (Test-Path -LiteralPath $envPath)) {
    throw "Env file not found: $envPath"
}

$selectedDate = [DateTime]::ParseExact($Date, "yyyy-MM-dd", [Globalization.CultureInfo]::InvariantCulture)
$envValues = Read-EnvFile -Path $envPath
$keycloakAdminUsername = if ($envValues.ContainsKey("KEYCLOAK_ADMIN")) { $envValues["KEYCLOAK_ADMIN"] } else { "admin" }
$keycloakAdminPassword = if ($envValues.ContainsKey("KEYCLOAK_ADMIN_PASSWORD")) { $envValues["KEYCLOAK_ADMIN_PASSWORD"] } else { "admin" }
$serviceClientId = if ($envValues.ContainsKey("KEYCLOAK_ADMIN_CLIENT_ID")) { $envValues["KEYCLOAK_ADMIN_CLIENT_ID"] } else { "otziv-backend" }
$serviceClientSecret = if ($envValues.ContainsKey("KEYCLOAK_ADMIN_CLIENT_SECRET")) { $envValues["KEYCLOAK_ADMIN_CLIENT_SECRET"] } else { "" }

if ([string]::IsNullOrWhiteSpace($serviceClientSecret)) {
    throw "KEYCLOAK_ADMIN_CLIENT_SECRET must be set in $envPath."
}

$analyticsEnvNames = @(
    "OTZIV_ANALYTICS_AGGREGATES_READ_ENABLED",
    "OTZIV_ANALYTICS_REBUILD_API_ENABLED",
    "OTZIV_ANALYTICS_REBUILD_SCHEDULE_ENABLED"
)
$previousEnv = @{}
foreach ($name in $analyticsEnvNames) {
    $previousEnv[$name] = [Environment]::GetEnvironmentVariable($name, "Process")
}

$composeArgs = @("compose", "-f", $composePath, "--env-file", $envPath)

try {
    Write-Host "Starting prod-like smoke with aggregate read and rebuild API enabled..."
    Set-ProcessEnvValue -Name "OTZIV_ANALYTICS_AGGREGATES_READ_ENABLED" -Value "true"
    Set-ProcessEnvValue -Name "OTZIV_ANALYTICS_REBUILD_API_ENABLED" -Value "true"
    Set-ProcessEnvValue -Name "OTZIV_ANALYTICS_REBUILD_SCHEDULE_ENABLED" -Value "false"
    Invoke-Smoke -SmokePath $smokePath -EnvPath $envPath -ComposePath $composePath -SmokeBaseUrl $rootUrl -SmokeTimeoutSeconds $TimeoutSeconds -SkipBuild:$NoBuild
    Assert-ContainerFlags -ComposeArguments $composeArgs -ExpectedReadEnabled $true -ExpectedRebuildApiEnabled $true

    $serviceToken = Get-ServiceAccountToken -RootUrl $rootUrl -ClientId $serviceClientId -ClientSecret $serviceClientSecret
    $serviceHeaders = @{ Authorization = "Bearer $serviceToken" }

    if (-not $SkipRebuild) {
        Invoke-RebuildWindow -RootUrl $rootUrl -Headers $serviceHeaders -SelectedDate $selectedDate -StartMonth $RebuildStartMonth
    } else {
        Write-Host "Skipping aggregate rebuild because -SkipRebuild was provided."
    }

    Invoke-CompareChecks `
        -RootUrl $rootUrl `
        -Headers $serviceHeaders `
        -SelectedDate $Date `
        -AdminUser $AdminUsername `
        -OwnerUser $OwnerUsername `
        -WorkerId $WorkerUserId

    Invoke-CabinetChecks `
        -RootUrl $rootUrl `
        -SelectedDate $Date `
        -AdminUser $AdminUsername `
        -OwnerUser $OwnerUsername `
        -WorkerUser $WorkerUsername `
        -WorkerId $WorkerUserId `
        -KeycloakAdminUsername $keycloakAdminUsername `
        -KeycloakAdminPassword $keycloakAdminPassword

    Write-Host "Aggregate analytics verification passed."
} finally {
    if (-not $NoRestore) {
        Write-Host "Restoring safe prod-like mode with aggregate read and rebuild API disabled..."
        Set-ProcessEnvValue -Name "OTZIV_ANALYTICS_AGGREGATES_READ_ENABLED" -Value "false"
        Set-ProcessEnvValue -Name "OTZIV_ANALYTICS_REBUILD_API_ENABLED" -Value "false"
        Set-ProcessEnvValue -Name "OTZIV_ANALYTICS_REBUILD_SCHEDULE_ENABLED" -Value "false"
        Invoke-Smoke -SmokePath $smokePath -EnvPath $envPath -ComposePath $composePath -SmokeBaseUrl $rootUrl -SmokeTimeoutSeconds $TimeoutSeconds -SkipBuild:$NoBuild
        Assert-ContainerFlags -ComposeArguments $composeArgs -ExpectedReadEnabled $false -ExpectedRebuildApiEnabled $false
    }

    foreach ($name in $analyticsEnvNames) {
        Set-ProcessEnvValue -Name $name -Value $previousEnv[$name]
    }
}
