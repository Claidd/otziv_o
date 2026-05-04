param(
    [string]$Container = "otziv-keycloak",
    [string]$Realm = "otziv",
    [string]$Theme = "otziv",
    [string]$AdminUser = $(if ($env:KEYCLOAK_ADMIN) { $env:KEYCLOAK_ADMIN } else { "admin" }),
    [string]$AdminPassword = $(if ($env:KEYCLOAK_ADMIN_PASSWORD) { $env:KEYCLOAK_ADMIN_PASSWORD } else { "admin" })
)

$supportedLocales = '["ru","en"]'

docker exec $Container /opt/keycloak/bin/kcadm.sh config credentials `
    --server http://localhost:8080 `
    --realm master `
    --user $AdminUser `
    --password $AdminPassword

docker exec $Container /opt/keycloak/bin/kcadm.sh update "realms/$Realm" `
    -s "loginTheme=$Theme" `
    -s "internationalizationEnabled=true" `
    -s "defaultLocale=ru" `
    -s "supportedLocales=$supportedLocales"

Write-Host "Keycloak login theme '$Theme' applied to realm '$Realm'."
