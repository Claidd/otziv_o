$ErrorActionPreference = "Stop"

$mobileDir = Resolve-Path (Join-Path $PSScriptRoot "..")
$googleServicesPath = Join-Path $mobileDir "android\app\google-services.json"

if (-not (Test-Path -LiteralPath $googleServicesPath)) {
  throw "Firebase config not found: $googleServicesPath. Download google-services.json for Android package com.hunt.otziv and place it there before using a push build."
}

$content = Get-Content -LiteralPath $googleServicesPath -Raw
if ([string]::IsNullOrWhiteSpace($content)) {
  throw "Firebase config is empty: $googleServicesPath"
}

if ($content -notmatch '"package_name"\s*:\s*"com\.hunt\.otziv"') {
  throw "Firebase config must be created for Android package com.hunt.otziv: $googleServicesPath"
}

Write-Host "Firebase Android config found: $googleServicesPath"
