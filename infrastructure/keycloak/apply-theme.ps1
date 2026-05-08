param(
    [string]$Container = "otziv-keycloak",
    [string]$Realm = "otziv",
    [string]$Theme = "otziv",
    [int]$AccessTokenLifespan = 600,
    [int]$SsoSessionIdleTimeout = 28800,
    [int]$SsoSessionMaxLifespan = 86400,
    [string]$Server = $(if ($env:KEYCLOAK_KCADM_SERVER) { $env:KEYCLOAK_KCADM_SERVER } else { "http://localhost:8080/keycloak" }),
    [string]$AdminUser = $(if ($env:KEYCLOAK_ADMIN) { $env:KEYCLOAK_ADMIN } else { "admin" }),
    [string]$AdminPassword = $(if ($env:KEYCLOAK_ADMIN_PASSWORD) { $env:KEYCLOAK_ADMIN_PASSWORD } else { "admin" })
)

$supportedLocales = '["ru","en"]'

docker exec $Container /opt/keycloak/bin/kcadm.sh config credentials `
    --server $Server `
    --realm master `
    --user $AdminUser `
    --password $AdminPassword

docker exec $Container /opt/keycloak/bin/kcadm.sh update "realms/$Realm" `
    -s "loginTheme=$Theme" `
    -s "internationalizationEnabled=true" `
    -s "defaultLocale=ru" `
    -s "supportedLocales=$supportedLocales" `
    -s "accessTokenLifespan=$AccessTokenLifespan" `
    -s "ssoSessionIdleTimeout=$SsoSessionIdleTimeout" `
    -s "ssoSessionMaxLifespan=$SsoSessionMaxLifespan"

Write-Host "Keycloak realm settings applied to realm '$Realm'."
