Set-StrictMode -Version Latest

function Get-OtzivProjectFilesRoot {
    param([string]$RepoRoot = "")

    $configured = [Environment]::GetEnvironmentVariable("OTZIV_PROJECT_FILES_ROOT")
    if (-not [string]::IsNullOrWhiteSpace($configured)) {
        return $configured
    }

    if ([string]::IsNullOrWhiteSpace($RepoRoot)) {
        throw "RepoRoot is not set. Pass -RepoRoot or set OTZIV_PROJECT_FILES_ROOT."
    }

    $repoPath = [System.IO.Path]::GetFullPath($RepoRoot)
    $repoParent = [System.IO.Directory]::GetParent($repoPath)
    if ($null -eq $repoParent) {
        throw "Cannot determine the project-files root from RepoRoot: $repoPath"
    }

    return $repoParent.FullName
}

function Get-OtzivEnvDirectory {
    param([string]$RepoRoot = "")

    $configured = [Environment]::GetEnvironmentVariable("OTZIV_ENV_DIR")
    if (-not [string]::IsNullOrWhiteSpace($configured)) {
        return $configured
    }

    $projectFilesRoot = Get-OtzivProjectFilesRoot -RepoRoot $RepoRoot
    return Join-Path (Join-Path $projectFilesRoot ".otziv") "env"
}

function Get-OtzivSshDirectory {
    param(
        [string]$RepoRoot = "",
        [string]$ProjectFilesRoot = ""
    )

    if ([string]::IsNullOrWhiteSpace($ProjectFilesRoot)) {
        $ProjectFilesRoot = Get-OtzivProjectFilesRoot -RepoRoot $RepoRoot
    }

    return Join-Path $ProjectFilesRoot ".ssh"
}

function Get-OtzivBackupDirectory {
    param(
        [string]$RepoRoot = "",
        [string]$ProjectFilesRoot = ""
    )

    if ([string]::IsNullOrWhiteSpace($ProjectFilesRoot)) {
        $ProjectFilesRoot = Get-OtzivProjectFilesRoot -RepoRoot $RepoRoot
    }

    return Join-Path (Join-Path $ProjectFilesRoot ".otziv") "backups"
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
        $envDirectory = Get-OtzivEnvDirectory -RepoRoot $RepoRoot
        $envFileLeaf = Split-Path -Leaf $EnvFile
        $aliasFileName = Get-OtzivEnvAliasFileName -EnvFile $EnvFile
        $isCanonicalAlias = $envFileLeaf -in @(".env", ".env.prod", ".env.prod-local")

        if (-not $isCanonicalAlias) {
            [void]$candidates.Add((Join-Path $RepoRoot $EnvFile))
        }

        [void]$candidates.Add((Join-Path $envDirectory $aliasFileName))
        if ($envFileLeaf -ne $aliasFileName) {
            [void]$candidates.Add((Join-Path $envDirectory $envFileLeaf))
        }
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
