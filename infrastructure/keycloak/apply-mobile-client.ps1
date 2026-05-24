param(
    [string]$EnvFile = ".env.prod-local",
    [string]$Container = "otziv-prod-local-keycloak-1",
    [string]$Realm = "",
    [string]$Server = "http://localhost:8080/keycloak",
    [string]$AdminUser = "",
    [string]$AdminPassword = "",
    [string]$ClientId = "otziv-mobile",
    [string]$BackendAudience = "",
    [string]$AppBaseUrl = "",
    [string]$DevAppBaseUrl = "http://localhost:4300",
    [string]$NativeRedirectUri = "otziv://auth/callback",
    [string]$NativeLogoutRedirectUri = "otziv://logout"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Get-EnvValue {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Name
    )

    if (-not (Test-Path -LiteralPath $Path)) {
        return $null
    }

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
            return $trimmed.Substring($separator + 1).Trim()
        }
    }

    return $null
}

function Invoke-Kcadm {
    param([Parameter(Mandatory = $true)][string[]]$Arguments)

    $output = & docker exec $Container /opt/keycloak/bin/kcadm.sh @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "kcadm command failed: $($Arguments -join ' ')"
    }

    return $output
}

function Invoke-KcadmWithJson {
    param(
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [Parameter(Mandatory = $true)][object]$Body,
        [Parameter(Mandatory = $true)][string]$ContainerPath
    )

    $tempFile = [System.IO.Path]::GetTempFileName()
    try {
        $Body | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $tempFile -Encoding UTF8
        & docker cp $tempFile "${Container}:$ContainerPath"
        if ($LASTEXITCODE -ne 0) {
            throw "docker cp failed for $ContainerPath"
        }

        Invoke-Kcadm -Arguments ($Arguments + @("-f", $ContainerPath)) | Out-Null
    } finally {
        Remove-Item -LiteralPath $tempFile -Force -ErrorAction SilentlyContinue
    }
}

function Get-ClientUuid {
    param([Parameter(Mandatory = $true)][string]$LookupClientId)

    $rows = Invoke-Kcadm -Arguments @(
        "get", "clients",
        "-r", $Realm,
        "-q", "clientId=$LookupClientId",
        "--fields", "id",
        "--format", "csv",
        "--noquotes"
    )

    return $rows |
        ForEach-Object { $_.Trim() } |
        Where-Object { $_ -and $_ -ne "id" } |
        Select-Object -First 1
}

function Get-MapperUuid {
    param(
        [Parameter(Mandatory = $true)][string]$ClientUuid,
        [Parameter(Mandatory = $true)][string]$MapperName
    )

    $rows = Invoke-Kcadm -Arguments @(
        "get", "clients/$ClientUuid/protocol-mappers/models",
        "-r", $Realm,
        "--fields", "id,name",
        "--format", "csv",
        "--noquotes"
    )

    foreach ($row in $rows) {
        $parts = $row -split ",", 2
        if ($parts.Length -eq 2 -and $parts[1].Trim() -eq $MapperName) {
            return $parts[0].Trim()
        }
    }

    return $null
}

function Upsert-RealmRolesMapper {
    param([Parameter(Mandatory = $true)][string]$ClientUuid)

    $mapperUuid = Get-MapperUuid -ClientUuid $ClientUuid -MapperName "realm roles"
    $body = [ordered]@{
        name = "realm roles"
        protocol = "openid-connect"
        protocolMapper = "oidc-usermodel-realm-role-mapper"
        consentRequired = $false
        config = [ordered]@{
            multivalued = "true"
            "userinfo.token.claim" = "true"
            "id.token.claim" = "true"
            "access.token.claim" = "true"
            "claim.name" = "roles"
            "jsonType.label" = "String"
        }
    }

    if ($mapperUuid) {
        $body["id"] = $mapperUuid
        Invoke-KcadmWithJson `
            -Arguments @("update", "clients/$ClientUuid/protocol-mappers/models/$mapperUuid", "-r", $Realm) `
            -Body $body `
            -ContainerPath "/tmp/otziv-mobile-realm-roles-mapper.json"
    } else {
        Invoke-KcadmWithJson `
            -Arguments @("create", "clients/$ClientUuid/protocol-mappers/models", "-r", $Realm) `
            -Body $body `
            -ContainerPath "/tmp/otziv-mobile-realm-roles-mapper.json"
    }
}

function Upsert-BackendAudienceMapper {
    param([Parameter(Mandatory = $true)][string]$ClientUuid)

    $mapperUuid = Get-MapperUuid -ClientUuid $ClientUuid -MapperName "backend audience"
    $body = [ordered]@{
        name = "backend audience"
        protocol = "openid-connect"
        protocolMapper = "oidc-audience-mapper"
        consentRequired = $false
        config = [ordered]@{
            "included.client.audience" = $BackendAudience
            "id.token.claim" = "false"
            "access.token.claim" = "true"
        }
    }

    if ($mapperUuid) {
        $body["id"] = $mapperUuid
        Invoke-KcadmWithJson `
            -Arguments @("update", "clients/$ClientUuid/protocol-mappers/models/$mapperUuid", "-r", $Realm) `
            -Body $body `
            -ContainerPath "/tmp/otziv-mobile-backend-audience-mapper.json"
    } else {
        Invoke-KcadmWithJson `
            -Arguments @("create", "clients/$ClientUuid/protocol-mappers/models", "-r", $Realm) `
            -Body $body `
            -ContainerPath "/tmp/otziv-mobile-backend-audience-mapper.json"
    }
}

$Realm = if ($Realm) { $Realm } else { Get-EnvValue -Path $EnvFile -Name "KEYCLOAK_ADMIN_REALM" }
$AdminUser = if ($AdminUser) { $AdminUser } else { Get-EnvValue -Path $EnvFile -Name "KEYCLOAK_ADMIN" }
$AdminPassword = if ($AdminPassword) { $AdminPassword } else { Get-EnvValue -Path $EnvFile -Name "KEYCLOAK_ADMIN_PASSWORD" }
$BackendAudience = if ($BackendAudience) { $BackendAudience } else { Get-EnvValue -Path $EnvFile -Name "KEYCLOAK_ADMIN_CLIENT_ID" }
$AppBaseUrl = if ($AppBaseUrl) { $AppBaseUrl } else { Get-EnvValue -Path $EnvFile -Name "OTZIV_APP_BASE_URL" }

$Realm = if ($Realm) { $Realm } else { "otziv" }
$BackendAudience = if ($BackendAudience) { $BackendAudience } else { "otziv-backend" }
$AppBaseUrl = if ($AppBaseUrl) { $AppBaseUrl.TrimEnd("/") } else { "http://localhost:8088" }
$DevAppBaseUrl = $DevAppBaseUrl.TrimEnd("/")

if ([string]::IsNullOrWhiteSpace($AdminUser) -or [string]::IsNullOrWhiteSpace($AdminPassword)) {
    throw "KEYCLOAK_ADMIN and KEYCLOAK_ADMIN_PASSWORD must be set in $EnvFile or passed as parameters."
}

Invoke-Kcadm -Arguments @(
    "config", "credentials",
    "--server", $Server,
    "--realm", "master",
    "--user", $AdminUser,
    "--password", $AdminPassword
) | Out-Null

$clientUuid = Get-ClientUuid -LookupClientId $ClientId
if (-not $clientUuid) {
    Invoke-Kcadm -Arguments @(
        "create", "clients",
        "-r", $Realm,
        "-s", "clientId=$ClientId",
        "-s", "name=Otziv Mobile App",
        "-s", "description=Ionic Capacitor mobile application",
        "-s", "protocol=openid-connect",
        "-s", "enabled=true",
        "-s", "publicClient=true",
        "-s", "standardFlowEnabled=true",
        "-s", "implicitFlowEnabled=false",
        "-s", "directAccessGrantsEnabled=false",
        "-s", "serviceAccountsEnabled=false",
        "-s", "frontchannelLogout=true"
    ) | Out-Null

    $clientUuid = Get-ClientUuid -LookupClientId $ClientId
}

if (-not $clientUuid) {
    throw "Could not create or find Keycloak client '$ClientId'."
}

$redirectUris = @(
    "$AppBaseUrl/auth/callback",
    "$DevAppBaseUrl/auth/callback",
    $NativeRedirectUri
) | Select-Object -Unique

$webOrigins = @(
    $AppBaseUrl,
    $DevAppBaseUrl,
    "http://localhost",
    "capacitor://localhost"
) | Select-Object -Unique

$logoutUris = @(
    "$AppBaseUrl/login",
    "$DevAppBaseUrl/login",
    $NativeLogoutRedirectUri
) | Select-Object -Unique

$logoutUrisValue = $logoutUris -join "##"

$clientBody = [ordered]@{
    clientId = $ClientId
    name = "Otziv Mobile App"
    description = "Ionic Capacitor mobile application"
    enabled = $true
    protocol = "openid-connect"
    publicClient = $true
    standardFlowEnabled = $true
    implicitFlowEnabled = $false
    directAccessGrantsEnabled = $false
    serviceAccountsEnabled = $false
    frontchannelLogout = $true
    redirectUris = $redirectUris
    webOrigins = $webOrigins
    attributes = [ordered]@{
        "pkce.code.challenge.method" = "S256"
        "post.logout.redirect.uris" = $logoutUrisValue
    }
}

Invoke-KcadmWithJson `
    -Arguments @("update", "clients/$clientUuid", "-r", $Realm) `
    -Body $clientBody `
    -ContainerPath "/tmp/otziv-mobile-client.json"

Upsert-RealmRolesMapper -ClientUuid $clientUuid
Upsert-BackendAudienceMapper -ClientUuid $clientUuid

Write-Host "Keycloak client '$ClientId' is configured in realm '$Realm'."
