$ErrorActionPreference = 'Stop'
$path = Join-Path $PSScriptRoot '../local/prod-like-smoke.ps1'
$tokens = $null
$errors = $null
$ast = [System.Management.Automation.Language.Parser]::ParseFile(
    (Resolve-Path -LiteralPath $path).Path, [ref]$tokens, [ref]$errors)
if ($errors.Count -ne 0) { throw 'Smoke script must parse before testing its classifier.' }
$function = $ast.Find({ param($node)
    $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Test-RegistryBuildFailure'
}, $true)
. ([scriptblock]::Create($function.Extent.Text))
$compilationFailure = @'
#1 resolve image config for docker-image://docker.io/docker/dockerfile:1
#1 DONE 0.3s
#2 [app build] [ERROR] COMPILATION ERROR :
#2 [ERROR] cannot find symbol
target app: failed to solve: process "mvn package" did not complete successfully
'@
if (Test-RegistryBuildFailure $compilationFailure) { throw 'Compiler failure must not invoke a different/offline toolchain.' }
if (Test-RegistryBuildFailure '#1 resolve image config for docker.io/docker/dockerfile:1') { throw 'Normal registry output is not a failure.' }
if (-not (Test-RegistryBuildFailure 'ERROR: failed to resolve source metadata for docker.io/library/node: lookup registry-1.docker.io: no such host')) { throw 'Registry DNS failure must allow the documented fallback.' }
if (-not (Test-RegistryBuildFailure 'failed to solve: failed to do request: Head https://registry-1.docker.io/v2/: TLS handshake timeout')) { throw 'Registry TLS timeout must allow fallback.' }
Write-Host 'Local build fallback classifier: 4 checks passed.'
