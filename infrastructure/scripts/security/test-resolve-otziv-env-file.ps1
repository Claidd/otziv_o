[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..\..")).Path
$resolverPath = Join-Path $repoRoot "infrastructure\scripts\Resolve-OtzivEnvFile.ps1"
. $resolverPath

function ConvertTo-NormalizedTestPath {
    param([Parameter(Mandatory = $true)][string]$Path)

    return [System.IO.Path]::GetFullPath($Path).TrimEnd(@(
            [System.IO.Path]::DirectorySeparatorChar,
            [System.IO.Path]::AltDirectorySeparatorChar
        ))
}

function Assert-TestPathEqual {
    param(
        [Parameter(Mandatory = $true)][string]$Expected,
        [Parameter(Mandatory = $true)][string]$Actual,
        [Parameter(Mandatory = $true)][string]$Scenario
    )

    $expectedPath = ConvertTo-NormalizedTestPath -Path $Expected
    $actualPath = ConvertTo-NormalizedTestPath -Path $Actual
    if (-not $expectedPath.Equals($actualPath, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "$Scenario Expected '$expectedPath', got '$actualPath'."
    }
}

function Assert-TestThrows {
    param(
        [Parameter(Mandatory = $true)][scriptblock]$Action,
        [Parameter(Mandatory = $true)][string]$MessagePattern,
        [Parameter(Mandatory = $true)][string]$Scenario
    )

    try {
        & $Action | Out-Null
    } catch {
        if ($_.Exception.Message -notlike $MessagePattern) {
            throw "$Scenario Expected error '$MessagePattern', got '$($_.Exception.Message)'."
        }
        return
    }

    throw "$Scenario Expected an exception."
}

$oldProjectFilesRoot = [Environment]::GetEnvironmentVariable("OTZIV_PROJECT_FILES_ROOT")
$oldEnvDirectory = [Environment]::GetEnvironmentVariable("OTZIV_ENV_DIR")
$testRoot = Join-Path ([System.IO.Path]::GetTempPath()) "otziv-env-resolver-$([Guid]::NewGuid().ToString('N'))"

try {
    $projectFilesRoot = Join-Path $testRoot "projects"
    $testRepoRoot = Join-Path $projectFilesRoot "otziv"
    $externalEnvDirectory = Join-Path (Join-Path $projectFilesRoot ".otziv") "env"
    [void][System.IO.Directory]::CreateDirectory($testRepoRoot)
    [void][System.IO.Directory]::CreateDirectory($externalEnvDirectory)

    [Environment]::SetEnvironmentVariable("OTZIV_PROJECT_FILES_ROOT", $null)
    [Environment]::SetEnvironmentVariable("OTZIV_ENV_DIR", $null)

    Assert-TestPathEqual -Expected $projectFilesRoot `
        -Actual (Get-OtzivProjectFilesRoot -RepoRoot $testRepoRoot) `
        -Scenario "Default project-files root must be the parent of RepoRoot."
    Assert-TestPathEqual -Expected $externalEnvDirectory `
        -Actual (Get-OtzivEnvDirectory -RepoRoot $testRepoRoot) `
        -Scenario "Default env directory must use the sibling .otziv store."
    Assert-TestPathEqual -Expected (Join-Path $projectFilesRoot ".ssh") `
        -Actual (Get-OtzivSshDirectory -RepoRoot $testRepoRoot) `
        -Scenario "Default SSH directory must use the project-files root."
    Assert-TestPathEqual -Expected (Join-Path (Join-Path $projectFilesRoot ".otziv") "backups") `
        -Actual (Get-OtzivBackupDirectory -RepoRoot $testRepoRoot) `
        -Scenario "Default backup directory must use the project-files root."

    $configuredProjectFilesRoot = Join-Path $testRoot "configured-project-files"
    [Environment]::SetEnvironmentVariable("OTZIV_PROJECT_FILES_ROOT", $configuredProjectFilesRoot)
    Assert-TestPathEqual -Expected $configuredProjectFilesRoot `
        -Actual (Get-OtzivProjectFilesRoot) `
        -Scenario "OTZIV_PROJECT_FILES_ROOT must override RepoRoot discovery."
    Assert-TestPathEqual -Expected (Join-Path (Join-Path $configuredProjectFilesRoot ".otziv") "env") `
        -Actual (Get-OtzivEnvDirectory -RepoRoot $testRepoRoot) `
        -Scenario "The env store must inherit OTZIV_PROJECT_FILES_ROOT."
    Assert-TestPathEqual -Expected (Join-Path $configuredProjectFilesRoot ".ssh") `
        -Actual (Get-OtzivSshDirectory -RepoRoot $testRepoRoot) `
        -Scenario "The SSH store must inherit OTZIV_PROJECT_FILES_ROOT."
    Assert-TestPathEqual -Expected (Join-Path (Join-Path $configuredProjectFilesRoot ".otziv") "backups") `
        -Actual (Get-OtzivBackupDirectory -RepoRoot $testRepoRoot) `
        -Scenario "The backup store must inherit OTZIV_PROJECT_FILES_ROOT."

    $pinnedProjectFilesRoot = Join-Path $testRoot "pinned-project-files"
    Assert-TestPathEqual -Expected (Join-Path $pinnedProjectFilesRoot ".ssh") `
        -Actual (Get-OtzivSshDirectory -ProjectFilesRoot $pinnedProjectFilesRoot) `
        -Scenario "An explicit project-files root must pin the SSH directory."
    Assert-TestPathEqual -Expected (Join-Path (Join-Path $pinnedProjectFilesRoot ".otziv") "backups") `
        -Actual (Get-OtzivBackupDirectory -ProjectFilesRoot $pinnedProjectFilesRoot) `
        -Scenario "An explicit project-files root must pin the backup directory."

    $configuredEnvDirectory = Join-Path $testRoot "configured-env"
    [Environment]::SetEnvironmentVariable("OTZIV_ENV_DIR", $configuredEnvDirectory)
    Assert-TestPathEqual -Expected $configuredEnvDirectory `
        -Actual (Get-OtzivEnvDirectory) `
        -Scenario "OTZIV_ENV_DIR must have the highest env-directory priority."

    [Environment]::SetEnvironmentVariable("OTZIV_ENV_DIR", "   ")
    Assert-TestPathEqual -Expected (Join-Path (Join-Path $configuredProjectFilesRoot ".otziv") "env") `
        -Actual (Get-OtzivEnvDirectory -RepoRoot $testRepoRoot) `
        -Scenario "A whitespace OTZIV_ENV_DIR must not override project storage."

    [Environment]::SetEnvironmentVariable("OTZIV_PROJECT_FILES_ROOT", $null)
    [Environment]::SetEnvironmentVariable("OTZIV_ENV_DIR", $null)

    $aliases = [ordered]@{
        ".env" = "local.env"
        ".env.prod" = "prod.env"
        ".env.prod-local" = "prod-local.env"
    }
    foreach ($entry in $aliases.GetEnumerator()) {
        $repoFile = Join-Path $testRepoRoot $entry.Key
        $externalFile = Join-Path $externalEnvDirectory $entry.Value
        [System.IO.File]::WriteAllText($repoFile, "repository")
        [System.IO.File]::WriteAllText($externalFile, "external")

        Assert-TestPathEqual -Expected $externalFile `
            -Actual (Resolve-OtzivEnvFile -EnvFile $entry.Key -RepoRoot $testRepoRoot) `
            -Scenario "Canonical alias '$($entry.Key)' must resolve only through the external store."
    }

    $externalProdFile = Join-Path $externalEnvDirectory "prod.env"
    [System.IO.File]::Delete($externalProdFile)
    Assert-TestThrows -Action {
        Resolve-OtzivEnvFile -EnvFile ".env.prod" -RepoRoot $testRepoRoot
    } -MessagePattern "Env file not found.*" `
        -Scenario "A repository .env.prod must not be a canonical-alias fallback."
    Assert-TestPathEqual -Expected $externalProdFile `
        -Actual (Resolve-OtzivEnvFile -EnvFile ".env.prod" -RepoRoot $testRepoRoot -AllowMissing) `
        -Scenario "AllowMissing for a canonical alias must still return the external-store path."

    $repoCustomFile = Join-Path $testRepoRoot "custom.env"
    $externalCustomFile = Join-Path $externalEnvDirectory "custom.env"
    [System.IO.File]::WriteAllText($repoCustomFile, "repository-custom")
    [System.IO.File]::WriteAllText($externalCustomFile, "external-custom")
    Assert-TestPathEqual -Expected $repoCustomFile `
        -Actual (Resolve-OtzivEnvFile -EnvFile "custom.env" -RepoRoot $testRepoRoot) `
        -Scenario "Non-canonical relative env files must retain repository-first resolution."

    [System.IO.File]::Delete($repoCustomFile)
    Assert-TestPathEqual -Expected $externalCustomFile `
        -Actual (Resolve-OtzivEnvFile -EnvFile "custom.env" -RepoRoot $testRepoRoot) `
        -Scenario "Non-canonical relative env files must retain the external fallback."

    $absoluteEnvFile = Join-Path $testRoot "absolute.env"
    [System.IO.File]::WriteAllText($absoluteEnvFile, "absolute")
    Assert-TestPathEqual -Expected $absoluteEnvFile `
        -Actual (Resolve-OtzivEnvFile -EnvFile $absoluteEnvFile -RepoRoot $testRepoRoot) `
        -Scenario "An absolute env path must remain authoritative."

    Assert-TestThrows -Action {
        Get-OtzivProjectFilesRoot
    } -MessagePattern "RepoRoot is not set.*" `
        -Scenario "Project storage discovery without an override requires RepoRoot."

    Write-Host "Otziv env resolver behavioral tests passed."
} finally {
    [Environment]::SetEnvironmentVariable("OTZIV_PROJECT_FILES_ROOT", $oldProjectFilesRoot)
    [Environment]::SetEnvironmentVariable("OTZIV_ENV_DIR", $oldEnvDirectory)
    if ([System.IO.Directory]::Exists($testRoot)) {
        [System.IO.Directory]::Delete($testRoot, $true)
    }
}
