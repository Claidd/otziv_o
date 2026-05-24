$ErrorActionPreference = "Stop"

. "$PSScriptRoot\android-env.ps1"

$androidDir = Resolve-Path (Join-Path $PSScriptRoot "..\android")
Push-Location $androidDir
try {
  .\gradlew.bat assembleDebug
} finally {
  Pop-Location
}
