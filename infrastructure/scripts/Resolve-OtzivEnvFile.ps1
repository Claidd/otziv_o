Set-StrictMode -Version Latest

function Get-OtzivEnvDirectory {
    param([string]$RepoRoot = "")

    $configured = [Environment]::GetEnvironmentVariable("OTZIV_ENV_DIR")
    if (-not [string]::IsNullOrWhiteSpace($configured)) {
        return $configured
    }

    if ([string]::IsNullOrWhiteSpace($env:USERPROFILE)) {
        throw "USERPROFILE is not set. Set OTZIV_ENV_DIR to the directory with Otziv env files."
    }

    return Join-Path $env:USERPROFILE ".otziv\env"
}

function Get-OtzivEnvAliasFileName {
    param([Parameter(Mandatory = $true)][string]$EnvFile)

    $leaf = Split-Path -Leaf $EnvFile
    switch ($leaf) {
        ".env" { return "local.env" }
        ".env.prod" { return "prod.env" }
        ".env.prod-local" { return "prod-local.env" }
        default { return $leaf }
    }
}

function Resolve-OtzivEnvFile {
    param(
        [Parameter(Mandatory = $true)][string]$EnvFile,
        [Parameter(Mandatory = $true)][string]$RepoRoot,
        [switch]$AllowMissing
    )

    $candidates = [System.Collections.Generic.List[string]]::new()

    if ([System.IO.Path]::IsPathRooted($EnvFile)) {
        [void]$candidates.Add($EnvFile)
    } else {
        [void]$candidates.Add((Join-Path $RepoRoot $EnvFile))

        $envDirectory = Get-OtzivEnvDirectory -RepoRoot $RepoRoot
        $aliasFileName = Get-OtzivEnvAliasFileName -EnvFile $EnvFile
        [void]$candidates.Add((Join-Path $envDirectory $aliasFileName))
        [void]$candidates.Add((Join-Path $envDirectory (Split-Path -Leaf $EnvFile)))
    }

    foreach ($candidate in $candidates) {
        if (Test-Path -LiteralPath $candidate) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }

    if ($AllowMissing) {
        return $candidates[0]
    }

    $searched = ($candidates | ForEach-Object { "  - $_" }) -join [Environment]::NewLine
    throw "Env file not found. Searched:$([Environment]::NewLine)$searched$([Environment]::NewLine)Put real env files in $(Get-OtzivEnvDirectory -RepoRoot $RepoRoot), or pass an absolute -EnvFile path."
}
