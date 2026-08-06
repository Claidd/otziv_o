Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$deployScript = Join-Path $PSScriptRoot 'deploy-prod.ps1'
$effectiveArguments = @($args)

& $deployScript @effectiveArguments -FastAutoSnapshotValidation
exit $LASTEXITCODE
