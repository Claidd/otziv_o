param(
    [string]$Username = "hunt",
    [long]$ManagerId = 1,
    [string]$EnvFile = ".env.prod-local",
    [string]$ComposeFile = "compose.prod-local.yaml"
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

        $parts = $trimmed.Split("=", 2)
        if ($parts.Length -eq 2) {
            $values[$parts[0].Trim()] = $parts[1].Trim()
        }
    }

    return $values
}

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

function Escape-SqlString {
    param([Parameter(Mandatory = $true)][string]$Value)
    return $Value.Replace("\", "\\").Replace("'", "''")
}

$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = (Resolve-Path (Join-Path $scriptRoot "..\..\..")).Path
$composePath = if ([System.IO.Path]::IsPathRooted($ComposeFile)) { $ComposeFile } else { Join-Path $repoRoot $ComposeFile }
$envPath = if ([System.IO.Path]::IsPathRooted($EnvFile)) { $EnvFile } else { Join-Path $repoRoot $EnvFile }

if (-not (Test-Path -LiteralPath $composePath)) {
    throw "Compose file not found: $composePath"
}
if (-not (Test-Path -LiteralPath $envPath)) {
    throw "Env file not found: $envPath"
}

$envValues = Read-EnvFile -Path $envPath
$mysqlUser = $envValues["MYSQL_USER"]
$mysqlPassword = $envValues["MYSQL_PASSWORD"]
$mysqlDatabase = $envValues["MYSQL_DATABASE"]
$keycloakAdmin = if ($envValues.ContainsKey("KEYCLOAK_ADMIN")) { $envValues["KEYCLOAK_ADMIN"] } else { "admin" }
$keycloakAdminPassword = if ($envValues.ContainsKey("KEYCLOAK_ADMIN_PASSWORD")) { $envValues["KEYCLOAK_ADMIN_PASSWORD"] } else { "admin" }

if ([string]::IsNullOrWhiteSpace($mysqlUser) -or [string]::IsNullOrWhiteSpace($mysqlPassword) -or [string]::IsNullOrWhiteSpace($mysqlDatabase)) {
    throw "MYSQL_USER, MYSQL_PASSWORD, and MYSQL_DATABASE must be set in $envPath."
}

$escapedUsername = Escape-SqlString -Value $Username
$sql = @"
SET @user_id := (SELECT id FROM users WHERE username = '$escapedUsername' LIMIT 1);
SET @owner_role_id := (SELECT id FROM roles WHERE name = 'ROLE_OWNER' LIMIT 1);
INSERT IGNORE INTO users_roles(user_id, role_id) SELECT @user_id, @owner_role_id WHERE @user_id IS NOT NULL AND @owner_role_id IS NOT NULL;
INSERT IGNORE INTO managers_users(user_id, manager_id) SELECT @user_id, $ManagerId WHERE @user_id IS NOT NULL;
SELECT u.id, u.username, GROUP_CONCAT(r.name ORDER BY r.name) AS roles
FROM users u
LEFT JOIN users_roles ur ON ur.user_id = u.id
LEFT JOIN roles r ON r.id = ur.role_id
WHERE u.username = '$escapedUsername'
GROUP BY u.id, u.username;
SELECT * FROM managers_users WHERE user_id = @user_id;
"@

$composeArgs = @("compose", "-f", $composePath, "--env-file", $envPath)
Invoke-External -FilePath "docker" -Arguments ($composeArgs + @(
    "exec", "-T", "mysql",
    "mysql", "-u$mysqlUser", "-p$mysqlPassword", $mysqlDatabase, "-e", $sql
))

Invoke-External -FilePath "docker" -Arguments ($composeArgs + @(
    "exec", "-T", "keycloak",
    "/opt/keycloak/bin/kcadm.sh", "config", "credentials",
    "--server", "http://localhost:8080/keycloak",
    "--realm", "master",
    "--user", $keycloakAdmin,
    "--password", $keycloakAdminPassword
))

Invoke-External -FilePath "docker" -Arguments ($composeArgs + @(
    "exec", "-T", "keycloak",
    "/opt/keycloak/bin/kcadm.sh", "add-roles",
    "-r", "otziv",
    "--uusername", $Username,
    "--rolename", "OWNER"
))

Invoke-External -FilePath "docker" -Arguments ($composeArgs + @(
    "exec", "-T", "keycloak",
    "/opt/keycloak/bin/kcadm.sh", "remove-roles",
    "-r", "otziv",
    "--uusername", $Username,
    "--rolename", "CLIENT"
))

Write-Host "Local user '$Username' is promoted to OWNER. Log out and log in again to refresh the browser token."
