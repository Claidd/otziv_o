param(
  [switch]$Push
)

$ErrorActionPreference = "Stop"

. "$PSScriptRoot\android-env.ps1"

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..")
$mobileDir = Resolve-Path (Join-Path $PSScriptRoot "..")
$androidDir = Resolve-Path (Join-Path $mobileDir "android")
$apkPath = Join-Path $androidDir "app\build\outputs\apk\debug\app-debug.apk"
$adb = Join-Path $env:ANDROID_HOME "platform-tools\adb.exe"

Push-Location $mobileDir
try {
  if ($Push) {
    npm run cap:sync:local:push
  } else {
    npm run cap:sync:local
  }
} finally {
  Pop-Location
}

Push-Location $androidDir
try {
  .\gradlew.bat assembleDebug
} finally {
  Pop-Location
}

& $adb reverse tcp:8088 tcp:8088 | Out-Null
& $adb install -r -d $apkPath
& $adb shell am start -n com.hunt.otziv/.MainActivity | Out-Null

Write-Host "Local Android build installed and started."
Write-Host "Backend/Keycloak expected at http://localhost:8088 through adb reverse."
if ($Push) {
  Write-Host "Push build enabled. Firebase config was required at android/app/google-services.json."
} else {
  Write-Host "Push build disabled. Use npm run android:run:local:push after adding android/app/google-services.json."
}
