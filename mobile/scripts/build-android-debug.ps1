$ErrorActionPreference = "Stop"

. "$PSScriptRoot\android-env.ps1"

$androidDir = Resolve-Path (Join-Path $PSScriptRoot "..\android")
Push-Location $androidDir
try {
  .\gradlew.bat assembleDebug
  if ($LASTEXITCODE -ne 0) { throw "Android debug build failed with exit code $LASTEXITCODE." }
} finally {
  Pop-Location
}
