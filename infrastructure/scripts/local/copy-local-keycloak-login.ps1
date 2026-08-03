param(
    [string]$EnvFile = ".env.prod-local"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Get-RequiredEnvValue {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Name
    )

    $value = $null
    foreach ($line in Get-Content -LiteralPath $Path) {
        $trimmed = $line.Trim()
        if ($trimmed.Length -eq 0 -or $trimmed.StartsWith('#')) {
            continue
        }
        $separator = $trimmed.IndexOf('=')
        if ($separator -le 0 -or $trimmed.Substring(0, $separator).Trim() -ne $Name) {
            continue
        }
        $candidate = $trimmed.Substring($separator + 1).Trim()
        if (-not [string]::IsNullOrWhiteSpace($candidate)) {
            $value = $candidate
        }
    }
    if ([string]::IsNullOrWhiteSpace($value)) {
        throw "$Name is not initialized in the external prod-local env file."
    }
    return $value
}

$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = (Resolve-Path (Join-Path $scriptRoot "..\..\..")).Path
$resolverPath = Join-Path $repoRoot "infrastructure\scripts\Resolve-OtzivEnvFile.ps1"
. $resolverPath
$envPath = Resolve-OtzivEnvFile -EnvFile $EnvFile -RepoRoot $repoRoot

$username = Get-RequiredEnvValue -Path $envPath -Name 'OTZIV_LOCAL_LOGIN_USERNAME'
$password = Get-RequiredEnvValue -Path $envPath -Name 'OTZIV_LOCAL_LOGIN_PASSWORD'
$setClipboard = Get-Command Set-Clipboard -ErrorAction SilentlyContinue
if ($null -eq $setClipboard) {
    throw 'Set-Clipboard is unavailable in this PowerShell host.'
}

Set-Clipboard -Value $password
Write-Host "Local Keycloak username: $username"
Write-Host 'The local-only password was copied to the clipboard and was not printed.'
$password = $null
