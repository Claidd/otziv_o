param(
  [string]$AndroidJavaHome = $env:OTZIV_ANDROID_JAVA_HOME,
  [string]$AndroidSdk = $env:ANDROID_HOME
)

$ErrorActionPreference = "Stop"

function Get-AndroidJavaMajor {
  param([string]$Candidate)
  if ([string]::IsNullOrWhiteSpace($Candidate)) { return $null }
  $release = Join-Path $Candidate 'release'
  if (-not (Test-Path -LiteralPath (Join-Path $Candidate 'bin\java.exe') -PathType Leaf) -or
      -not (Test-Path -LiteralPath (Join-Path $Candidate 'bin\javac.exe') -PathType Leaf) -or
      -not (Test-Path -LiteralPath $release -PathType Leaf)) { return $null }
  $versionLine = Get-Content -LiteralPath $release | Where-Object { $_ -match '^JAVA_VERSION="([0-9]+)' } | Select-Object -First 1
  if ($versionLine -match '^JAVA_VERSION="([0-9]+)') { return [int]$Matches[1] }
  return $null
}

if (-not [string]::IsNullOrWhiteSpace($AndroidJavaHome)) {
  $major = Get-AndroidJavaMajor $AndroidJavaHome
  if ($null -eq $major -or $major -ne 21) {
    throw 'Explicit Android JDK must be an installed JDK 21; Capacitor plugins request that exact compiler toolchain.'
  }
} else {
  $candidates = @($env:JAVA_HOME)
  $jdkRoot = Join-Path $env:USERPROFILE '.jdks'
  if (Test-Path -LiteralPath $jdkRoot -PathType Container) {
    $candidates += @(Get-ChildItem -LiteralPath $jdkRoot -Directory | Select-Object -ExpandProperty FullName)
    $candidates += @(Get-ChildItem -LiteralPath $jdkRoot -Directory | ForEach-Object {
      Get-ChildItem -LiteralPath $_.FullName -Directory -ErrorAction SilentlyContinue | Select-Object -ExpandProperty FullName
    })
  }
  $AndroidJavaHome = $candidates | Where-Object {
    $major = Get-AndroidJavaMajor $_
    $null -ne $major -and $major -eq 21
  } | Select-Object -First 1
  if ([string]::IsNullOrWhiteSpace($AndroidJavaHome)) {
    throw 'No Android JDK 21 found. Set OTZIV_ANDROID_JAVA_HOME to an installed JDK 21.'
  }
}
if ([string]::IsNullOrWhiteSpace($AndroidSdk)) { $AndroidSdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
if (-not (Test-Path -LiteralPath (Join-Path $AndroidSdk 'platform-tools\adb.exe') -PathType Leaf)) {
  throw 'Android SDK platform-tools are missing. Set ANDROID_HOME to the installed SDK.'
}
$env:JAVA_HOME = (Resolve-Path -LiteralPath $AndroidJavaHome).Path
$env:ANDROID_HOME = (Resolve-Path -LiteralPath $AndroidSdk).Path
$env:PATH = "$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:PATH"

Write-Host "JAVA_HOME=$env:JAVA_HOME"
Write-Host "ANDROID_HOME=$env:ANDROID_HOME"
java -version
if ($LASTEXITCODE -ne 0) { throw 'Selected Android Java runtime could not start.' }
