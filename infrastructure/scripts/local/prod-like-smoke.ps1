param(
    [string]$EnvFile = ".env.prod-local",
    [string]$ComposeFile = "compose.prod-local.yaml",
    [string]$BaseUrl = "http://localhost:8088",
    [int]$TimeoutSeconds = 1200,
    [switch]$OfflineAppBuild,
    [switch]$NoOfflineFallback,
    [switch]$NoBuild,
    [switch]$NoUp,
    [switch]$NoLogs,
    [switch]$SkipOpenAiProxyIpSync,
    [switch]$UseConfiguredOutboundProxy,
    [switch]$WithDbAdmin,
    [switch]$NoDbAdmin,
    [switch]$WithObservability,
    [switch]$WithReputationAiSmoke,
    [int]$ReputationAiCompanyId = 1,
    [switch]$SkipReputationAiOpenAiRouteCheck,
    [switch]$SkipWorkloadShadowSmoke,
    [switch]$RestoreProdDb,
    [switch]$SkipProdDbRestore,
    [string]$VpsHost = "95.213.248.152",
    [string]$VpsUser = "hunt",
    [ValidateRange(1, 65535)][int]$VpsPort = 22022,
    [string]$SshKey = "C:\Users\Hunt\.ssh\otziv_vps_ed25519",
    [switch]$AllowLocalMessengerSending,
    [string]$LocalLoginUsername = "",
    [string]$LocalKeycloakUserSnapshot = "infrastructure\keycloak\prod-local-user-snapshot.json",
    [switch]$InitializeLocalKeycloakUserSnapshot,
    [switch]$RotateLocalKeycloakCredentials,
    [switch]$SkipLocalLoginCredentialSync
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

# Capture an explicitly requested local-only password once, then remove it
# before any validation or child process can inherit it. The value remains
# only in this PowerShell process until login configuration is resolved.
$script:requestedLocalLoginPassword = [Environment]::GetEnvironmentVariable('OTZIV_LOCAL_LOGIN_PASSWORD')
if (-not [string]::IsNullOrWhiteSpace($script:requestedLocalLoginPassword)) {
    [Environment]::SetEnvironmentVariable('OTZIV_LOCAL_LOGIN_PASSWORD', $null)
}

function Format-RedactedCommand {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [Parameter(Mandatory = $true)][string[]]$Arguments
    )

    $redacted = [System.Collections.Generic.List[string]]::new()
    $redactNext = $false
    foreach ($argument in $Arguments) {
        if ($redactNext) {
            $redacted.Add('[REDACTED]')
            $redactNext = $false
            continue
        }

        if ($argument -match '(?i)^(--password|--client-secret|--secret|--token|-p)$') {
            $redacted.Add($argument)
            $redactNext = $true
        } elseif ($argument -match '(?i)^([^=]*(?:password|passwd|pwd|secret|token|api[_-]?key)[^=]*)=(.*)$') {
            $redacted.Add("$($Matches[1])=[REDACTED]")
        } elseif ($argument -match '(?i)^-p.+$') {
            $redacted.Add('-p[REDACTED]')
        } else {
            $redacted.Add($argument)
        }
    }

    return ((@($FilePath) + @($redacted)) -join ' ')
}

function Redact-SensitiveCommandOutput {
    param(
        [Parameter(Mandatory = $true)][string]$Text,
        [Parameter(Mandatory = $true)][string[]]$Arguments
    )

    $result = $Text
    for ($index = 0; $index -lt $Arguments.Count - 1; $index++) {
        if ($Arguments[$index] -match '(?i)^(--password|--client-secret|--secret|--token|-p)$') {
            $secret = $Arguments[$index + 1]
            if (-not [string]::IsNullOrEmpty($secret)) {
                $result = $result.Replace($secret, '[REDACTED]')
            }
        }
    }
    return $result
}

function Invoke-External {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [Parameter(Mandatory = $true)][string[]]$Arguments
    )

    & $FilePath @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Command failed: $(Format-RedactedCommand -FilePath $FilePath -Arguments $Arguments)"
    }
}

function Get-EnvValue {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Name
    )

    $found = $null
    foreach ($line in Get-Content -LiteralPath $Path) {
        $trimmed = $line.Trim()
        if ($trimmed.Length -eq 0 -or $trimmed.StartsWith("#")) {
            continue
        }

        $separator = $trimmed.IndexOf("=")
        if ($separator -le 0) {
            continue
        }

        if ($trimmed.Substring(0, $separator).Trim() -eq $Name) {
            $value = $trimmed.Substring($separator + 1).Trim()
            if (-not [string]::IsNullOrWhiteSpace($value)) {
                $found = $value
            }
        }
    }

    return $found
}

function Protect-SensitiveLocalPath {
    param([Parameter(Mandatory = $true)][string]$Path)

    if ([System.Environment]::OSVersion.Platform -eq [System.PlatformID]::Win32NT) {
        $sid = [System.Security.Principal.WindowsIdentity]::GetCurrent().User.Value
        $grant = if (Test-Path -LiteralPath $Path -PathType Container) { "*${sid}:(OI)(CI)F" } else { "*${sid}:F" }
        & icacls.exe $Path '/inheritance:r' '/grant:r' $grant '/grant:r' '*S-1-5-18:F' | Out-Null
        if ($LASTEXITCODE -ne 0) {
            throw "Failed to restrict ACL on sensitive path: $Path"
        }
        return
    }

    $mode = if (Test-Path -LiteralPath $Path -PathType Container) { '700' } else { '600' }
    & chmod $mode -- $Path
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to restrict permissions on sensitive path: $Path"
    }
}

function Set-LocalEnvFileValues {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][hashtable]$Values
    )

    foreach ($entry in $Values.GetEnumerator()) {
        if ($entry.Key -notmatch '^[A-Z][A-Z0-9_]*$') {
            throw "Invalid env key '$($entry.Key)'."
        }
        $value = [string]$entry.Value
        if ([string]::IsNullOrWhiteSpace($value) -or $value.Contains("`r") -or $value.Contains("`n") `
                -or $value -cne $value.Trim()) {
            throw "Env value '$($entry.Key)' must be a non-empty single-line value without leading or trailing whitespace."
        }
    }

    $sourceLines = [System.IO.File]::ReadAllLines($Path)
    $remaining = [System.Collections.Generic.HashSet[string]]::new(
        [System.StringComparer]::Ordinal
    )
    foreach ($key in $Values.Keys) {
        [void]$remaining.Add([string]$key)
    }
    $written = [System.Collections.Generic.HashSet[string]]::new(
        [System.StringComparer]::Ordinal
    )
    $updatedLines = [System.Collections.Generic.List[string]]::new()
    foreach ($line in $sourceLines) {
        if ($line -match '^(?<key>[A-Z][A-Z0-9_]*)=') {
            $key = $Matches['key']
            if ($Values.ContainsKey($key)) {
                if ($written.Add($key)) {
                    [void]$updatedLines.Add("$key=$($Values[$key])")
                    [void]$remaining.Remove($key)
                }
                continue
            }
        }
        [void]$updatedLines.Add($line)
    }
    foreach ($key in @($remaining | Sort-Object)) {
        [void]$updatedLines.Add("$key=$($Values[$key])")
    }

    $directory = Split-Path -Parent $Path
    $temporaryPath = Join-Path $directory (".$([System.IO.Path]::GetFileName($Path)).$([guid]::NewGuid().ToString('N')).tmp")
    $encoding = [System.Text.UTF8Encoding]::new($false)
    try {
        [System.IO.File]::WriteAllLines($temporaryPath, $updatedLines.ToArray(), $encoding)
        Protect-SensitiveLocalPath -Path $temporaryPath
        Move-Item -LiteralPath $temporaryPath -Destination $Path -Force
        Protect-SensitiveLocalPath -Path $Path
    } finally {
        if (Test-Path -LiteralPath $temporaryPath) {
            Remove-Item -LiteralPath $temporaryPath -Force
        }
    }
}

function Remove-LocalEnvFileValues {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string[]]$Names
    )

    $namesToRemove = [System.Collections.Generic.HashSet[string]]::new(
        [System.StringComparer]::Ordinal
    )
    foreach ($name in $Names) {
        if ($name -notmatch '^[A-Z][A-Z0-9_]*$') {
            throw "Invalid env key '$name'."
        }
        [void]$namesToRemove.Add($name)
    }

    $updatedLines = [System.Collections.Generic.List[string]]::new()
    foreach ($line in [System.IO.File]::ReadAllLines($Path)) {
        if ($line -match '^(?<key>[A-Z][A-Z0-9_]*)=' -and $namesToRemove.Contains($Matches['key'])) {
            continue
        }
        [void]$updatedLines.Add($line)
    }

    $directory = Split-Path -Parent $Path
    $temporaryPath = Join-Path $directory (".$([System.IO.Path]::GetFileName($Path)).$([guid]::NewGuid().ToString('N')).tmp")
    $encoding = [System.Text.UTF8Encoding]::new($false)
    try {
        [System.IO.File]::WriteAllLines($temporaryPath, $updatedLines.ToArray(), $encoding)
        Protect-SensitiveLocalPath -Path $temporaryPath
        Move-Item -LiteralPath $temporaryPath -Destination $Path -Force
        Protect-SensitiveLocalPath -Path $Path
    } finally {
        if (Test-Path -LiteralPath $temporaryPath) {
            Remove-Item -LiteralPath $temporaryPath -Force
        }
    }
}

function Resolve-LocalKeycloakLoginConfiguration {
    param(
        [Parameter(Mandatory = $true)][string]$EnvPath,
        [AllowEmptyString()][string]$UsernameOverride,
        [switch]$InitializeSnapshot,
        [switch]$RotateCredentials,
        [switch]$SkipCredentialSync
    )

    $storedUsername = Get-EnvValue -Path $EnvPath -Name 'OTZIV_LOCAL_LOGIN_USERNAME'
    $storedPassword = Get-EnvValue -Path $EnvPath -Name 'OTZIV_LOCAL_LOGIN_PASSWORD'
    $pendingUsername = Get-EnvValue -Path $EnvPath -Name 'OTZIV_LOCAL_LOGIN_PENDING_USERNAME'
    $pendingPassword = Get-EnvValue -Path $EnvPath -Name 'OTZIV_LOCAL_LOGIN_PENDING_PASSWORD'
    $requestedPassword = $script:requestedLocalLoginPassword
    $script:requestedLocalLoginPassword = $null
    $hasPendingUsername = -not [string]::IsNullOrWhiteSpace($pendingUsername)
    $hasPendingPassword = -not [string]::IsNullOrWhiteSpace($pendingPassword)
    if ($hasPendingUsername -ne $hasPendingPassword) {
        if (-not ($InitializeSnapshot -or $RotateCredentials)) {
            throw 'The external prod-local env contains an incomplete pending Keycloak credential rotation. Run an explicit -RotateLocalKeycloakCredentials operation to recover it.'
        }
        $pendingUsername = $null
        $pendingPassword = $null
        $hasPendingUsername = $false
        $hasPendingPassword = $false
    }

    $resumeCredentialRotation = $hasPendingUsername -and $hasPendingPassword
    try {
        if ($resumeCredentialRotation) {
            if ($InitializeSnapshot -or $RotateCredentials) {
                # An explicit recovery may correct a mistyped or newly
                # deactivated selected username. Unless a new password is
                # supplied, preserve the already pending shared password.
                $username = if (-not [string]::IsNullOrWhiteSpace($UsernameOverride)) {
                    $UsernameOverride.Trim()
                } else {
                    $pendingUsername
                }
                $password = if (-not [string]::IsNullOrWhiteSpace($requestedPassword)) {
                    $requestedPassword
                } else {
                    $pendingPassword
                }
            } else {
                if (-not [string]::IsNullOrWhiteSpace($UsernameOverride) -and
                        -not $pendingUsername.Equals($UsernameOverride.Trim(), [System.StringComparison]::OrdinalIgnoreCase)) {
                    throw "A pending local Keycloak credential rotation already exists for '$pendingUsername'. Run the normal smoke once without changing LocalLoginUsername to finish it, or use an explicit rotation to replace it."
                }
                if (-not [string]::IsNullOrWhiteSpace($requestedPassword) -and $requestedPassword -cne $pendingPassword) {
                    throw 'A pending local Keycloak credential rotation already exists with a different password. Run the normal smoke once without supplying a new password to finish it, or use an explicit rotation to replace it.'
                }
                $username = $pendingUsername
                $password = $pendingPassword
            }
        } else {
            $username = if (-not [string]::IsNullOrWhiteSpace($UsernameOverride)) {
                $UsernameOverride.Trim()
            } else {
                $storedUsername
            }
            $password = if (($InitializeSnapshot -or $RotateCredentials) -and
                    -not [string]::IsNullOrWhiteSpace($requestedPassword)) {
                $requestedPassword
            } else {
                $storedPassword
            }

            if ($InitializeSnapshot -or $RotateCredentials) {
                if ([string]::IsNullOrWhiteSpace($username)) {
                    throw 'Pass -LocalLoginUsername during one-time local Keycloak initialization, or set OTZIV_LOCAL_LOGIN_USERNAME in the external prod-local env file.'
                }
                if (($RotateCredentials -and [string]::IsNullOrWhiteSpace($requestedPassword)) `
                        -or [string]::IsNullOrWhiteSpace($password)) {
                    $password = New-LocalRandomSecret
                }

            }
        }
    } finally {
        $script:requestedLocalLoginPassword = $null
    }

    if (-not $SkipCredentialSync -and
            ([string]::IsNullOrWhiteSpace($username) -or [string]::IsNullOrWhiteSpace($password))) {
        throw 'OTZIV_LOCAL_LOGIN_USERNAME and OTZIV_LOCAL_LOGIN_PASSWORD are required in the external prod-local env file. Create them with -RotateLocalKeycloakCredentials -LocalLoginUsername <name>.'
    }

    return [pscustomobject]@{
        Username = $username
        Password = $password
        ResumeCredentialRotation = $resumeCredentialRotation
    }
}

function ConvertTo-SmokeArray {
    param([AllowNull()][object]$Value)

    if ($null -eq $Value) {
        return @()
    }
    if ($Value -is [array]) {
        return $Value
    }

    return @($Value)
}

function ConvertFrom-SmokeHexUtf8 {
    param(
        [Parameter(Mandatory = $true)]
        [AllowEmptyString()]
        [string]$Hex
    )

    if (($Hex.Length % 2) -ne 0 -or $Hex -notmatch '^[0-9A-Fa-f]*$') {
        throw 'Expected an even-length hexadecimal UTF-8 value.'
    }

    $bytes = [byte[]]::new([int]($Hex.Length / 2))
    for ($index = 0; $index -lt $bytes.Length; $index++) {
        $bytes[$index] = [Convert]::ToByte($Hex.Substring($index * 2, 2), 16)
    }

    # Do not use Convert.FromHexString here: the prod-like smoke remains
    # runnable from the Windows PowerShell 5.1 host shipped with Windows.
    $strictUtf8 = [System.Text.UTF8Encoding]::new($false, $true)
    return $strictUtf8.GetString($bytes)
}

function Wait-HttpOk {
    param(
        [Parameter(Mandatory = $true)][string]$Url,
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][datetime]$Deadline
    )

    Write-Host "Waiting for ${Name}: $Url"
    while ((Get-Date) -lt $Deadline) {
        try {
            $response = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 5
            if ($response.StatusCode -ge 200 -and $response.StatusCode -lt 400) {
                Write-Host "$Name is OK ($($response.StatusCode))."
                return
            }
        } catch {
            Start-Sleep -Seconds 5
            continue
        }

        Start-Sleep -Seconds 5
    }

    throw "Timed out waiting for $Name at $Url"
}

function Wait-ComposeServiceHealthy {
    param(
        [Parameter(Mandatory = $true)][string[]]$ComposeArguments,
        [Parameter(Mandatory = $true)][string]$Service,
        [int]$TimeoutSeconds = 180
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $containerId = & docker @($ComposeArguments + @("ps", "-q", $Service))
        if ($LASTEXITCODE -eq 0 -and -not [string]::IsNullOrWhiteSpace($containerId)) {
            $health = & docker @("inspect", "--format", "{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}", $containerId.Trim())
            if ($LASTEXITCODE -eq 0 -and $health.Trim() -eq "healthy") {
                return
            }
        }

        Start-Sleep -Seconds 3
    } while ((Get-Date) -lt $deadline)

    throw "Service '$Service' did not become healthy within $TimeoutSeconds seconds."
}

function Assert-FrontendShellRoute {
    param(
        [Parameter(Mandatory = $true)][string]$BaseUrl,
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Name
    )

    $root = $BaseUrl.TrimEnd("/")
    $routePath = if ($Path.StartsWith("/")) { $Path } else { "/$Path" }
    $url = "$root$routePath"
    $response = Invoke-WebRequest -Uri $url -UseBasicParsing -TimeoutSec 30
    $content = [string]$response.Content
    if ($response.StatusCode -ne 200 -or -not $content.Contains("app-root")) {
        throw "Frontend route failed for ${Name}: $url returned HTTP $($response.StatusCode)."
    }

    Write-Host "Frontend route OK: $routePath ($Name)."
}

function Invoke-PublicFrontendSmoke {
    param([Parameter(Mandatory = $true)][string]$BaseUrl)

    Write-Host "Running public frontend route smoke..."
    $routes = @(
        @{ Path = "/"; Name = "home" },
        @{ Path = "/services"; Name = "services" },
        @{ Path = "/prices"; Name = "prices" },
        @{ Path = "/payment"; Name = "payment" },
        @{ Path = "/refund"; Name = "refund" },
        @{ Path = "/offer"; Name = "offer" },
        @{ Path = "/privacy"; Name = "privacy" },
        @{ Path = "/contacts"; Name = "contacts" },
        @{ Path = "/receipt-consent"; Name = "receipt consent" },
        @{ Path = "/pay"; Name = "pay form" },
        @{ Path = "/pay/success"; Name = "payment success" },
        @{ Path = "/pay/fail"; Name = "payment fail" },
        @{ Path = "/pay/demo-token"; Name = "tokenized pay form" },
        @{ Path = "/uslugi"; Name = "services redirect alias" },
        @{ Path = "/oplata"; Name = "payment redirect alias" }
    )

    foreach ($route in $routes) {
        Assert-FrontendShellRoute -BaseUrl $BaseUrl -Path $route.Path -Name $route.Name
    }
}

function Get-SmokeResponseHeader {
    param(
        [AllowNull()][object]$Response,
        [Parameter(Mandatory = $true)][string]$Name
    )

    if ($null -eq $Response -or $null -eq $Response.Headers) {
        return $null
    }

    try {
        $values = $Response.Headers.GetValues($Name)
        if ($null -ne $values) {
            return (@($values) -join ", ")
        }
    } catch {
        # Windows PowerShell exposes WebHeaderCollection through an indexer.
    }

    try {
        return [string]$Response.Headers[$Name]
    } catch {
        return $null
    }
}

function Invoke-SmokeHttpRequest {
    param(
        [Parameter(Mandatory = $true)][string]$Url,
        [ValidateSet("GET", "PUT", "POST")][string]$Method = "GET",
        [AllowNull()][string]$Body,
        [AllowNull()][hashtable]$Headers
    )

    $request = @{
        Uri = $Url
        Method = $Method
        UseBasicParsing = $true
        TimeoutSec = 30
    }
    if ($null -ne $Body) {
        $request.Body = $Body
        $request.ContentType = "application/json; charset=utf-8"
    }
    if ($null -ne $Headers) {
        $request.Headers = $Headers
    }

    try {
        $response = Invoke-WebRequest @request
        return [pscustomobject]@{
            StatusCode = [int]$response.StatusCode
            WwwAuthenticate = Get-SmokeResponseHeader -Response $response -Name "WWW-Authenticate"
            CacheControl = Get-SmokeResponseHeader -Response $response -Name "Cache-Control"
        }
    } catch {
        $response = $_.Exception.Response
        if ($null -eq $response) {
            throw
        }

        return [pscustomobject]@{
            StatusCode = [int]$response.StatusCode
            WwwAuthenticate = Get-SmokeResponseHeader -Response $response -Name "WWW-Authenticate"
            CacheControl = Get-SmokeResponseHeader -Response $response -Name "Cache-Control"
        }
    }
}

function Assert-AnonymousMissingCapability {
    param(
        [Parameter(Mandatory = $true)][string]$Url,
        [Parameter(Mandatory = $true)][string]$Name,
        [ValidateSet("GET", "PUT", "POST")][string]$Method = "GET",
        [AllowNull()][string]$Body,
        [AllowNull()][hashtable]$Headers
    )

    $response = Invoke-SmokeHttpRequest -Url $Url -Method $Method -Body $Body -Headers $Headers
    if ($response.StatusCode -ne 404) {
        throw "Anonymous capability contract failed for ${Name}: expected HTTP 404 for a missing resource, got $($response.StatusCode)."
    }
    if (-not [string]::IsNullOrWhiteSpace([string]$response.WwwAuthenticate)) {
        throw "Anonymous capability contract failed for ${Name}: response unexpectedly contains WWW-Authenticate."
    }
    if ([string]::IsNullOrWhiteSpace([string]$response.CacheControl) -or
            [string]$response.CacheControl -notmatch "(^|,)\s*no-store(\s*(,|$))") {
        throw "Anonymous capability contract failed for ${Name}: response does not contain Cache-Control: no-store."
    }

    Write-Host "Anonymous capability route OK: $Name (404, no auth challenge, no-store)."
}

function Invoke-PublicCapabilityAuthorizationSmoke {
    param([Parameter(Mandatory = $true)][string]$BaseUrl)

    Write-Host "Running anonymous review/payment capability smoke..."
    $root = $BaseUrl.TrimEnd("/")
    $missingReviewId = [guid]::NewGuid().ToString()
    $missingPaymentToken = "smoke-missing-$([guid]::NewGuid().ToString('N'))"
    $capabilitySeed = [guid]::NewGuid().ToString("N")
    $missingCapabilityToken = "rc1_$capabilitySeed$($capabilitySeed.Substring(0, 11))"
    $reviewBody = '{"reviews":[]}'
    $reviewTextBody = '{"text":"prod-like smoke"}'
    $reviewAnswerBody = '{"answer":"prod-like smoke"}'
    $paymentInitBody = '{"email":"client@example.com","offerConsent":true,"privacyConsent":true,"receiptConsent":true}'

    Assert-AnonymousMissingCapability -Url "$root/api/review-check/$missingReviewId" -Name "review read"
    Assert-AnonymousMissingCapability -Url "$root/api/review-check/$missingReviewId" -Name "review save" -Method "PUT" -Body $reviewBody
    Assert-AnonymousMissingCapability -Url "$root/api/review-check/$missingReviewId/reviews/1/text" -Name "review text edit" -Method "PUT" -Body $reviewTextBody
    Assert-AnonymousMissingCapability -Url "$root/api/review-check/$missingReviewId/reviews/1/answer" -Name "review answer edit" -Method "PUT" -Body $reviewAnswerBody
    Assert-AnonymousMissingCapability -Url "$root/api/review-check/$missingReviewId/approve" -Name "review approve" -Method "POST" -Body $reviewBody
    Assert-AnonymousMissingCapability -Url "$root/api/review-check/$missingReviewId/correction" -Name "review correction" -Method "POST" -Body $reviewBody
    Assert-AnonymousMissingCapability `
        -Url "$root/api/review-capability" `
        -Name "opaque review capability" `
        -Headers @{ "X-Review-Capability" = $missingCapabilityToken }
    Assert-AnonymousMissingCapability -Url "$root/api/payments/public/$missingPaymentToken" -Name "single payment link"
    Assert-AnonymousMissingCapability -Url "$root/api/payments/public/$missingPaymentToken/init" -Name "payment init" -Method "POST" -Body $paymentInitBody
    Assert-AnonymousMissingCapability -Url "$root/api/payments/public/group/$missingPaymentToken" -Name "group payment link"
}

function Assert-LegacyUserMigrationDisabled {
    param([Parameter(Mandatory = $true)][string]$BaseUrl)

    $response = Invoke-SmokeHttpRequest `
        -Url "$($BaseUrl.TrimEnd('/'))/api/auth/legacy-migration" `
        -Method "POST" `
        -Body '{"username":"retired-migration-smoke","password":"not-a-real-password"}'
    if ($response.StatusCode -ne 410) {
        throw "Legacy user migration must be retired with HTTP 410, got $($response.StatusCode)."
    }
    if (-not [string]::IsNullOrWhiteSpace([string]$response.WwwAuthenticate)) {
        throw "Retired legacy migration unexpectedly returned an authentication challenge."
    }
    Write-Host "Legacy user migration is retired (410 Gone)."
}

function Assert-ScheduledMessageReconciliationHealthy {
    param([Parameter(Mandatory = $true)][string[]]$ComposeArguments)

    $appLogs = & docker @($ComposeArguments + @("logs", "--since=5m", "app")) 2>&1 | Out-String
    if ($LASTEXITCODE -ne 0) {
        throw "Could not inspect backend logs for scheduled message reconciliation failures."
    }
    if ($appLogs -match "Scheduled client message reconciliation transaction failed" `
            -or $appLogs -match "Column 'state_status' in field list is ambiguous") {
        throw "Scheduled client message reconciliation failed in prod-like backend logs."
    }
    if ($appLogs -match "Illegal mix of collations" `
            -or $appLogs -match "Contractor shadow route backfill failed" `
            -or $appLogs -match "Не удалось восстановить начисления завершенного заказа" `
            -or $appLogs -match "Unexpected error occurred in scheduled task") {
        throw "Contractor payment background processing failed in prod-like backend logs."
    }
    Write-Host "Scheduled client message reconciliation log check is clean."
    Write-Host "Contractor payment background-processing log check is clean."
}

function Assert-LegacyReviewCapabilityNotLogged {
    param(
        [Parameter(Mandatory = $true)][string]$BaseUrl,
        [Parameter(Mandatory = $true)][string[]]$ComposeArguments
    )

    Write-Host "Checking that legacy review UUID capabilities never enter Nginx access logs..."
    $root = $BaseUrl.TrimEnd("/")
    $reviewId = [guid]::NewGuid().ToString()
    $encodedReviewId = $reviewId.Replace("-", "%2D")
    $observableMarker = "legacy-observable-$([guid]::NewGuid().ToString('N'))"
    $paths = @(
        "/legacy/review/editReviews/$reviewId",
        "/legacy%2Freview%2FeditReviews%2F$encodedReviewId",
        "/legacy/review;smoke=1/editReviews/$reviewId;smoke=1"
    )

    foreach ($path in $paths) {
        Invoke-SmokeHttpRequest -Url "$root$path" -Method "GET" | Out-Null
    }

    $baseUri = [Uri]$BaseUrl
    if ($baseUri.Host -in @("localhost", "127.0.0.1")) {
        $redirectBuilder = [UriBuilder]$baseUri
        $redirectBuilder.Host = "127.0.0.1"
        $redirectRoot = $redirectBuilder.Uri.AbsoluteUri.TrimEnd("/")
        Invoke-SmokeHttpRequest -Url "$redirectRoot/legacy/review/editReviews/$reviewId" -Method "GET" | Out-Null
    }
    Invoke-SmokeHttpRequest -Url "$root/legacy/$observableMarker" -Method "GET" | Out-Null

    Start-Sleep -Milliseconds 250
    $nginxContainerIds = @(& docker @($ComposeArguments + @("ps", "-q", "nginx")))
    $dockerExitCode = $LASTEXITCODE
    $nginxContainerId = $nginxContainerIds |
        Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_) } |
        Select-Object -First 1
    if ($dockerExitCode -ne 0 -or [string]::IsNullOrWhiteSpace([string]$nginxContainerId)) {
        throw "Could not resolve the Nginx container for the legacy capability log assertion."
    }
    $nginxContainerId = ([string]$nginxContainerId).Trim()

    foreach ($needle in @($reviewId, $encodedReviewId)) {
        $matches = & docker @(
            "exec",
            $nginxContainerId,
            "grep",
            "-Fi",
            "--",
            $needle,
            "/var/log/nginx/access.log"
        ) 2>$null
        $grepExitCode = $LASTEXITCODE
        if ($grepExitCode -eq 0) {
            throw "Legacy review capability leaked into Nginx access.log: $($matches -join [Environment]::NewLine)"
        }
        if ($grepExitCode -ne 1) {
            throw "Could not inspect Nginx access.log for legacy review capability leakage (grep exit $grepExitCode)."
        }
    }

    $observableMatches = & docker @(
        "exec",
        $nginxContainerId,
        "grep",
        "-F",
        "--",
        $observableMarker,
        "/var/log/nginx/access.log"
    ) 2>$null
    if ($LASTEXITCODE -ne 0) {
        throw "Legacy observability contract failed: an unrelated /legacy route was not written to access.log."
    }

    Write-Host "Legacy review capability log contract OK: raw, encoded, matrix and redirect paths are absent; unrelated /legacy traffic remains observable."
}

function Convert-EnvBool {
    param(
        [AllowNull()][string]$Value,
        [Parameter(Mandatory = $true)][bool]$Default
    )

    if ([string]::IsNullOrWhiteSpace($Value)) {
        return $Default
    }

    return $Value.Equals("true", [System.StringComparison]::OrdinalIgnoreCase)
}

function Invoke-TbankPaymentConfigSmoke {
    param(
        [Parameter(Mandatory = $true)][string]$BaseUrl,
        [Parameter(Mandatory = $true)][string]$EnvPath
    )

    Write-Host "Running T-Bank payment config smoke..."
    $apiRoot = $BaseUrl.TrimEnd("/")
    $retiredPublicStatus = Invoke-SmokeWebRequest `
        -Uri "$apiRoot/api/payments/public/tbank-status" `
        -Method "Get" `
        -Headers @{}
    if ($retiredPublicStatus.StatusCode -ne 404) {
        throw "Retired public T-Bank status must return HTTP 404, got $($retiredPublicStatus.StatusCode)."
    }
    $authenticateHeader = [string]::Join(" ", @($retiredPublicStatus.Headers.WwwAuthenticate))
    if (-not [string]::IsNullOrWhiteSpace($authenticateHeader)) {
        throw "Retired public T-Bank status unexpectedly disclosed an authentication challenge."
    }

    $realm = Get-KeycloakRealm -EnvPath $EnvPath
    $adminToken = Get-KeycloakAdminToken -RootUrl $BaseUrl -EnvPath $EnvPath
    $keycloakAdminHeaders = @{ Authorization = "Bearer $adminToken" }
    $smokeClient = $null
    try {
        Remove-KeycloakSmokeClientsByPrefix `
            -RootUrl $BaseUrl `
            -Realm $realm `
            -AdminHeaders $keycloakAdminHeaders
        $smokeClient = New-KeycloakSmokeClient `
            -RootUrl $BaseUrl `
            -Realm $realm `
            -AdminHeaders $keycloakAdminHeaders `
            -Role "ADMIN"
        $roleToken = Get-KeycloakClientCredentialsToken `
            -RootUrl $BaseUrl `
            -Realm $realm `
            -ClientId $smokeClient.ClientId `
            -ClientSecret $smokeClient.ClientSecret
        $status = Invoke-RestMethod `
            -Uri "$apiRoot/api/admin/payments/tbank-status" `
            -Headers @{ Authorization = "Bearer $roleToken" } `
            -TimeoutSec 20

        $expectedEnabled = $false
        $expectedPaymentLinks = $false
        $expectedManagerUi = $false
        $expectedApplyConfirmed = $false
        $expectedBaseUrl = Get-EnvValue -Path $EnvPath -Name "OTZIV_PAYMENTS_TBANK_BASE_URL"
        $expectedRuntimeMode = "TEST"

        if ($status.enabled -ne $expectedEnabled) {
            throw "T-Bank enabled flag mismatch: expected $expectedEnabled, got $($status.enabled)."
        }
        if ($status.paymentLinksEnabled -ne $expectedPaymentLinks) {
            throw "T-Bank payment links flag mismatch: expected $expectedPaymentLinks, got $($status.paymentLinksEnabled)."
        }
        if ($status.managerUiEnabled -ne $expectedManagerUi) {
            throw "T-Bank manager UI flag mismatch: expected $expectedManagerUi, got $($status.managerUiEnabled)."
        }
        if ($status.applyConfirmedPayments -ne $expectedApplyConfirmed) {
            throw "T-Bank apply-confirmed flag mismatch: expected $expectedApplyConfirmed, got $($status.applyConfirmedPayments)."
        }
        if ($status.runtimeMode -notin @("TEST", "LIVE")) {
            throw "T-Bank runtime mode must be TEST or LIVE, got '$($status.runtimeMode)'."
        }
        if (-not [string]::IsNullOrWhiteSpace($expectedRuntimeMode) -and $status.runtimeMode -ne $expectedRuntimeMode.Trim().ToUpperInvariant()) {
            throw "T-Bank runtime mode mismatch: expected $expectedRuntimeMode, got $($status.runtimeMode)."
        }
        if (($status.runtimeMode -eq "TEST") -ne [bool]$status.testMode) {
            throw "T-Bank testMode flag mismatch for runtime $($status.runtimeMode): got $($status.testMode)."
        }
        if (-not [string]::IsNullOrWhiteSpace($expectedBaseUrl) -and $status.baseUrl -ne $expectedBaseUrl.TrimEnd("/")) {
            throw "T-Bank base URL mismatch: expected $expectedBaseUrl, got $($status.baseUrl)."
        }

        Write-Host "T-Bank config OK: public status retired, runtime=$($status.runtimeMode), enabled=$($status.enabled), paymentLinks=$($status.paymentLinksEnabled), managerUi=$($status.managerUiEnabled), applyConfirmed=$($status.applyConfirmedPayments), baseUrl=$($status.baseUrl)."
    } finally {
        Remove-KeycloakSmokeClient `
            -RootUrl $BaseUrl `
            -Realm $realm `
            -AdminHeaders $keycloakAdminHeaders `
            -Client $smokeClient
    }
}

function Disable-LocalExternalMessaging {
    param(
        [Parameter(Mandatory = $true)][string[]]$ComposeArguments,
        [Parameter(Mandatory = $true)][string]$EnvPath
    )

    $mysqlUser = Get-EnvValue -Path $EnvPath -Name "MYSQL_USER"
    $mysqlPassword = Get-EnvValue -Path $EnvPath -Name "MYSQL_PASSWORD"
    $mysqlDatabase = Get-EnvValue -Path $EnvPath -Name "MYSQL_DATABASE"
    if ([string]::IsNullOrWhiteSpace($mysqlUser) -or [string]::IsNullOrWhiteSpace($mysqlPassword) -or [string]::IsNullOrWhiteSpace($mysqlDatabase)) {
        throw "MYSQL_USER, MYSQL_PASSWORD, and MYSQL_DATABASE must be set to force local autoresponder dry-run mode."
    }

    $tableCheckOutput = & docker @($ComposeArguments + @(
        "exec", "-T", "-e", "MYSQL_PWD=$mysqlPassword", "mysql",
        "mysql",
        "--default-character-set=utf8mb4",
        "-u$mysqlUser",
        $mysqlDatabase,
        "-N", "-B",
        "-e",
        "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'app_settings'"
    )) 2>&1
    if ($LASTEXITCODE -ne 0) {
        $text = ($tableCheckOutput | ForEach-Object { $_.ToString() }) -join [Environment]::NewLine
        throw "Could not inspect local app_settings table: $text"
    }
    $tableExists = @($tableCheckOutput | ForEach-Object { $_.ToString().Trim() } | Where-Object { $_ -match "^[0-9]+$" } | Select-Object -First 1)
    if ($tableExists.Count -eq 0 -or $tableExists[0] -ne "1") {
        Write-Host "Local app_settings table is not migrated yet; messenger safety relies on env overrides until backend creates it."
        return
    }

    $sql = @"
INSERT INTO app_settings (setting_key, setting_value, updated_at)
VALUES
  ('client.messages.live.enabled', 'false', NOW(6)),
  ('client.messages.payment-overdue.live-enabled', 'false', NOW(6)),
  ('client.messages.immediate.enabled', 'false', NOW(6)),
  ('client.messages.monitor.enabled', 'false', NOW(6)),
  ('publication.health-monitor.enabled', 'false', NOW(6)),
  ('telegram.reports.morning.enabled', 'false', NOW(6)),
  ('telegram.reports.evening.enabled', 'false', NOW(6)),
  ('whatsapp.group-sync.enabled', 'false', NOW(6)),
  ('archive.orders.schedule.worker.enabled', 'false', NOW(6)),
  ('archive.orders.schedule.enabled', 'false', NOW(6)),
  ('archive.orders.apply.enabled', 'false', NOW(6)),
  ('archive.orders.run.mode', 'dry-run', NOW(6)),
  ('payment.links.archive.enabled', 'false', NOW(6)),
  ('payments.tbank.runtime-mode', 'TEST', NOW(6)),
  ('payments.tbank.enabled', 'false', NOW(6)),
  ('payments.tbank.payment-links-enabled', 'false', NOW(6)),
  ('payments.tbank.manager-ui-enabled', 'false', NOW(6)),
  ('payments.tbank.apply-confirmed-payments', 'false', NOW(6)),
  ('payments.tbank.tpay-enabled', 'false', NOW(6)),
  ('payments.tbank.sberpay-enabled', 'false', NOW(6)),
  ('payments.tbank.mirpay-enabled', 'false', NOW(6)),
  ('client.messages.payment-instruction-source', 'MANAGER_TEXT', NOW(6))
ON DUPLICATE KEY UPDATE setting_value = VALUES(setting_value), updated_at = VALUES(updated_at);

SELECT setting_key, setting_value
FROM app_settings
WHERE setting_key IN (
  'client.messages.live.enabled',
  'client.messages.payment-overdue.live-enabled',
  'client.messages.immediate.enabled',
  'client.messages.monitor.enabled',
  'publication.health-monitor.enabled',
  'telegram.reports.morning.enabled',
  'telegram.reports.evening.enabled',
  'whatsapp.group-sync.enabled',
  'archive.orders.schedule.worker.enabled',
  'archive.orders.schedule.enabled',
  'archive.orders.apply.enabled',
  'archive.orders.run.mode',
  'payment.links.archive.enabled',
  'payments.tbank.runtime-mode',
  'payments.tbank.enabled',
  'payments.tbank.payment-links-enabled',
  'payments.tbank.manager-ui-enabled',
  'payments.tbank.apply-confirmed-payments',
  'payments.tbank.tpay-enabled',
  'payments.tbank.sberpay-enabled',
  'payments.tbank.mirpay-enabled',
  'client.messages.payment-instruction-source'
)
ORDER BY setting_key;
"@

    $mysqlArgs = $ComposeArguments + @(
        "exec", "-T", "-e", "MYSQL_PWD=$mysqlPassword", "mysql",
        "mysql",
        "--default-character-set=utf8mb4",
        "-u$mysqlUser",
        $mysqlDatabase,
        "-e",
        $sql
    )
    $output = & docker @mysqlArgs 2>&1
    if ($LASTEXITCODE -ne 0) {
        $text = ($output | ForEach-Object { $_.ToString() }) -join [Environment]::NewLine
        throw "Could not force local external messaging safety mode: $text"
    }

    Write-Host "Local external messaging is disabled for this prod-like stack."
}

function Disable-LocalMessengerEnv {
    $env:TELEGRAM_BOT_TOKEN_LOCAL_DOCKER = ""
    $env:TELEGRAM_BOT_TOKEN = ""
    $env:TELEGRAM_BOT_REGISTRATION_ENABLED = "false"
    $env:MAX_BOT_TOKEN = ""
    $env:MAX_BOT_WEBHOOK_AUTO_REGISTER_ENABLED = "false"
    $env:MAX_BOT_LONG_POLLING_ENABLED = "false"
    $env:WHATSAPP_HEALTH_MONITOR_ENABLED = "false"
    $env:WHATSAPP_HEALTH_MONITOR_RESTART_ENABLED = "false"
    Write-Host "Local messenger tokens and bot registration are disabled for this prod-like stack."
}

function New-LocalRandomSecret {
    param([ValidateRange(32, 128)][int]$ByteCount = 32)

    $randomBytes = New-Object byte[] $ByteCount
    $randomSource = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $randomSource.GetBytes($randomBytes)
    } finally {
        $randomSource.Dispose()
    }

    return [System.BitConverter]::ToString($randomBytes).Replace('-', '').ToLowerInvariant()
}

function New-LocalRandomBase64Key {
    param([ValidateRange(32, 32)][int]$ByteCount = 32)

    $randomBytes = New-Object byte[] $ByteCount
    $randomSource = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $randomSource.GetBytes($randomBytes)
        return [Convert]::ToBase64String($randomBytes)
    } finally {
        [Array]::Clear($randomBytes, 0, $randomBytes.Length)
        $randomSource.Dispose()
    }
}

function Initialize-LocalCredentialEncryptionKey {
    param([Parameter(Mandatory = $true)][string]$EnvPath)

    $keyId = if (-not [string]::IsNullOrWhiteSpace($env:OTZIV_CREDENTIAL_ENCRYPTION_ACTIVE_KEY_ID)) {
        $env:OTZIV_CREDENTIAL_ENCRYPTION_ACTIVE_KEY_ID.Trim()
    } else {
        Get-EnvValue -Path $EnvPath -Name 'OTZIV_CREDENTIAL_ENCRYPTION_ACTIVE_KEY_ID'
    }
    if ([string]::IsNullOrWhiteSpace($keyId)) {
        $keyId = 'prod-local'
    }
    if ($keyId -notmatch '^[A-Za-z0-9._-]{1,64}$') {
        throw 'OTZIV_CREDENTIAL_ENCRYPTION_ACTIVE_KEY_ID must match [A-Za-z0-9._-]{1,64}.'
    }

    $encodedKey = if (-not [string]::IsNullOrWhiteSpace($env:OTZIV_CREDENTIAL_ENCRYPTION_ACTIVE_KEY_BASE64)) {
        $env:OTZIV_CREDENTIAL_ENCRYPTION_ACTIVE_KEY_BASE64.Trim()
    } else {
        Get-EnvValue -Path $EnvPath -Name 'OTZIV_CREDENTIAL_ENCRYPTION_ACTIVE_KEY_BASE64'
    }
    if ([string]::IsNullOrWhiteSpace($encodedKey)) {
        $encodedKey = New-LocalRandomBase64Key
        Set-LocalEnvFileValues -Path $EnvPath -Values @{
            OTZIV_CREDENTIAL_ENCRYPTION_REQUIRED = 'true'
            OTZIV_CREDENTIAL_ENCRYPTION_ACTIVE_KEY_ID = $keyId
            OTZIV_CREDENTIAL_ENCRYPTION_ACTIVE_KEY_BASE64 = $encodedKey
        }
        Write-Host 'Created a protected local-only credential encryption key in the external prod-local env file.'
    }

    $decodedKey = $null
    try {
        $decodedKey = [Convert]::FromBase64String($encodedKey)
        if ($decodedKey.Length -ne 32) {
            throw 'OTZIV_CREDENTIAL_ENCRYPTION_ACTIVE_KEY_BASE64 must decode to exactly 32 bytes.'
        }
    } catch [System.FormatException] {
        throw 'OTZIV_CREDENTIAL_ENCRYPTION_ACTIVE_KEY_BASE64 must be valid Base64 encoding exactly 32 bytes.'
    } finally {
        if ($null -ne $decodedKey) {
            [Array]::Clear($decodedKey, 0, $decodedKey.Length)
        }
    }

    # Process values override stale or incomplete external Compose defaults,
    # while the generated key remains stable across -SkipProdDbRestore runs.
    $env:OTZIV_CREDENTIAL_ENCRYPTION_REQUIRED = 'true'
    $env:OTZIV_CREDENTIAL_ENCRYPTION_ACTIVE_KEY_ID = $keyId
    $env:OTZIV_CREDENTIAL_ENCRYPTION_ACTIVE_KEY_BASE64 = $encodedKey
    Write-Host "Local credential encryption is enabled with key id '$keyId'."
}

function Initialize-LocalBotLinkSecrets {
    param([Parameter(Mandatory = $true)][string]$EnvPath)

    $telegramLinkValue = if (-not [string]::IsNullOrWhiteSpace($env:TELEGRAM_BOT_LINK_SECRET)) {
        $env:TELEGRAM_BOT_LINK_SECRET
    } else {
        Get-EnvValue -Path $EnvPath -Name 'TELEGRAM_BOT_LINK_SECRET'
    }
    $maxLinkValue = if (-not [string]::IsNullOrWhiteSpace($env:MAX_BOT_LINK_SECRET)) {
        $env:MAX_BOT_LINK_SECRET
    } else {
        Get-EnvValue -Path $EnvPath -Name 'MAX_BOT_LINK_SECRET'
    }
    $maxWebhookValue = if (-not [string]::IsNullOrWhiteSpace($env:MAX_BOT_WEBHOOK_SECRET)) {
        $env:MAX_BOT_WEBHOOK_SECRET
    } else {
        Get-EnvValue -Path $EnvPath -Name 'MAX_BOT_WEBHOOK_SECRET'
    }

    if ([string]::IsNullOrWhiteSpace($telegramLinkValue)) {
        $telegramLinkValue = New-LocalRandomSecret
        $env:TELEGRAM_BOT_LINK_SECRET = $telegramLinkValue
    }
    if ([string]::IsNullOrWhiteSpace($maxLinkValue)) {
        $maxLinkValue = New-LocalRandomSecret
        $env:MAX_BOT_LINK_SECRET = $maxLinkValue
    }
    if ([string]::IsNullOrWhiteSpace($maxWebhookValue) -or
            [System.Text.Encoding]::UTF8.GetByteCount($maxWebhookValue) -lt 32) {
        # The prod profile validates webhook credentials even though local smoke
        # deliberately disables MAX registration and outbound messaging. Never
        # pass a weak local placeholder through to that fail-closed validator.
        $maxWebhookValue = New-LocalRandomSecret
        $env:MAX_BOT_WEBHOOK_SECRET = $maxWebhookValue
    }

    if ([System.Text.Encoding]::UTF8.GetByteCount($telegramLinkValue) -lt 32) {
        throw 'TELEGRAM_BOT_LINK_SECRET must contain at least 32 UTF-8 bytes.'
    }
    if ([System.Text.Encoding]::UTF8.GetByteCount($maxLinkValue) -lt 32) {
        throw 'MAX_BOT_LINK_SECRET must contain at least 32 UTF-8 bytes.'
    }
    if ([System.Text.Encoding]::UTF8.GetByteCount($maxWebhookValue) -lt 32) {
        throw 'MAX_BOT_WEBHOOK_SECRET must contain at least 32 UTF-8 bytes.'
    }
    if ($telegramLinkValue -ceq $maxLinkValue) {
        throw 'TELEGRAM_BOT_LINK_SECRET and MAX_BOT_LINK_SECRET must be different secrets.'
    }
}

function Get-KeycloakServiceAccountToken {
    param(
        [Parameter(Mandatory = $true)][string]$RootUrl,
        [Parameter(Mandatory = $true)][string]$EnvPath
    )

    $realm = Get-EnvValue -Path $EnvPath -Name "KEYCLOAK_ADMIN_REALM"
    if ([string]::IsNullOrWhiteSpace($realm)) {
        $realm = "otziv"
    }
    $clientId = Get-EnvValue -Path $EnvPath -Name "KEYCLOAK_ADMIN_CLIENT_ID"
    if ([string]::IsNullOrWhiteSpace($clientId)) {
        $clientId = "otziv-backend"
    }
    $clientSecret = Get-EnvValue -Path $EnvPath -Name "KEYCLOAK_ADMIN_CLIENT_SECRET"
    if ([string]::IsNullOrWhiteSpace($clientSecret)) {
        throw "KEYCLOAK_ADMIN_CLIENT_SECRET must be set for reputation AI smoke."
    }

    $tokenUrl = "$($RootUrl.TrimEnd('/'))/keycloak/realms/$realm/protocol/openid-connect/token"
    $body = @{
        grant_type = "client_credentials"
        client_id = $clientId
        client_secret = $clientSecret
    }
    $response = Invoke-RestMethod -Uri $tokenUrl -Method Post -Body $body -ContentType "application/x-www-form-urlencoded" -TimeoutSec 20
    if ([string]::IsNullOrWhiteSpace($response.access_token)) {
        throw "Keycloak did not return a service account token."
    }

    return $response.access_token
}

function Get-KeycloakRealm {
    param([Parameter(Mandatory = $true)][string]$EnvPath)

    $realm = Get-EnvValue -Path $EnvPath -Name "KEYCLOAK_ADMIN_REALM"
    if ([string]::IsNullOrWhiteSpace($realm)) {
        return "otziv"
    }

    return $realm
}

function Get-KeycloakAdminToken {
    param(
        [Parameter(Mandatory = $true)][string]$RootUrl,
        [Parameter(Mandatory = $true)][string]$EnvPath
    )

    $adminUser = Get-EnvValue -Path $EnvPath -Name "KEYCLOAK_ADMIN"
    $adminPassword = Get-EnvValue -Path $EnvPath -Name "KEYCLOAK_ADMIN_PASSWORD"
    if ([string]::IsNullOrWhiteSpace($adminUser) -or [string]::IsNullOrWhiteSpace($adminPassword)) {
        throw "KEYCLOAK_ADMIN and KEYCLOAK_ADMIN_PASSWORD must be set for reputation AI role smoke."
    }

    $tokenUrl = "$($RootUrl.TrimEnd('/'))/keycloak/realms/master/protocol/openid-connect/token"
    $body = @{
        grant_type = "password"
        client_id = "admin-cli"
        username = $adminUser
        password = $adminPassword
    }
    $response = Invoke-RestMethod -Uri $tokenUrl -Method Post -Body $body -ContentType "application/x-www-form-urlencoded" -TimeoutSec 20
    if ([string]::IsNullOrWhiteSpace($response.access_token)) {
        throw "Keycloak did not return an admin token."
    }

    return $response.access_token
}

function Invoke-KeycloakAdminCli {
    param(
        [Parameter(Mandatory = $true)][string[]]$ComposeArguments,
        [Parameter(Mandatory = $true)][string[]]$Arguments
    )

    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $output = & docker @($ComposeArguments + @("exec", "-T", "keycloak", "/opt/keycloak/bin/kcadm.sh") + $Arguments) 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }

    if ($exitCode -ne 0) {
        $text = ($output | ForEach-Object { $_.ToString() }) -join [Environment]::NewLine
        $redactedText = Redact-SensitiveCommandOutput -Text $text -Arguments $Arguments
        throw "kcadm command failed: $(Format-RedactedCommand -FilePath 'kcadm.sh' -Arguments $Arguments): $redactedText"
    }

    return @($output | ForEach-Object { $_.ToString() })
}

function Get-KeycloakLoopbackBaseUrls {
    param([Parameter(Mandatory = $true)][string[]]$BaseUrls)

    $result = [System.Collections.Generic.List[string]]::new()
    foreach ($baseUrl in $BaseUrls) {
        if ([string]::IsNullOrWhiteSpace($baseUrl)) {
            continue
        }

        $normalized = $baseUrl.TrimEnd("/")
        if (-not $result.Contains($normalized)) {
            [void]$result.Add($normalized)
        }

        try {
            $uri = [Uri]$normalized
        } catch {
            continue
        }

        $uriHost = $uri.Host.ToLowerInvariant()
        $alternateHost = if ($uriHost -eq "localhost") {
            "127.0.0.1"
        } elseif ($uriHost -eq "127.0.0.1") {
            "localhost"
        } else {
            $null
        }

        if ([string]::IsNullOrWhiteSpace($alternateHost)) {
            continue
        }

        $port = if ($uri.IsDefaultPort) { "" } else { ":$($uri.Port)" }
        $path = $uri.AbsolutePath.TrimEnd("/")
        if ($path -eq "/") {
            $path = ""
        }

        $alternateUrl = "$($uri.Scheme)://$alternateHost$port$path"
        if (-not $result.Contains($alternateUrl)) {
            [void]$result.Add($alternateUrl)
        }
    }

    return $result.ToArray()
}

function Update-KeycloakFrontendLoopbackRedirects {
    param(
        [Parameter(Mandatory = $true)][string[]]$ComposeArguments,
        [Parameter(Mandatory = $true)][string]$EnvPath,
        [Parameter(Mandatory = $true)][string]$BaseUrl
    )

    $realm = Get-KeycloakRealm -EnvPath $EnvPath
    $adminUser = Get-EnvValue -Path $EnvPath -Name "KEYCLOAK_ADMIN"
    $adminPassword = Get-EnvValue -Path $EnvPath -Name "KEYCLOAK_ADMIN_PASSWORD"
    if ([string]::IsNullOrWhiteSpace($adminUser) -or [string]::IsNullOrWhiteSpace($adminPassword)) {
        throw "KEYCLOAK_ADMIN and KEYCLOAK_ADMIN_PASSWORD must be set for local Keycloak client sync."
    }

    for ($attempt = 1; $attempt -le 30; $attempt++) {
        try {
            Invoke-KeycloakAdminCli -ComposeArguments $ComposeArguments -Arguments @(
                "config", "credentials",
                "--server", "http://localhost:8080/keycloak",
                "--realm", "master",
                "--user", $adminUser,
                "--password", $adminPassword
            ) | Out-Null
            break
        } catch {
            if ($attempt -eq 30) {
                throw
            }
            Start-Sleep -Seconds 2
        }
    }

    $clientLines = Invoke-KeycloakAdminCli -ComposeArguments $ComposeArguments -Arguments @(
        "get", "clients",
        "-r", $realm,
        "-q", "clientId=otziv-frontend",
        "--fields", "id",
        "--format", "csv",
        "--noquotes"
    )
    $frontendClientUuid = ($clientLines |
        ForEach-Object { $_.Trim() } |
        Where-Object { -not [string]::IsNullOrWhiteSpace($_) -and $_ -ne "id" } |
        Select-Object -Last 1)

    if ([string]::IsNullOrWhiteSpace($frontendClientUuid)) {
        Write-Warning "Keycloak frontend client otziv-frontend was not found; skipping local redirect sync."
        return
    }

    $appBaseUrl = Get-EnvValue -Path $EnvPath -Name "OTZIV_APP_BASE_URL"
    if ([string]::IsNullOrWhiteSpace($appBaseUrl)) {
        $appBaseUrl = $BaseUrl
    }

    $baseUrls = Get-KeycloakLoopbackBaseUrls -BaseUrls @($appBaseUrl, $BaseUrl)
    $redirectUris = @($baseUrls | ForEach-Object { "$_/*" })
    $logoutRedirectUris = $redirectUris -join "##"

    Write-Host "Applying Keycloak frontend origins: $($baseUrls -join ', ')."
    $adminToken = Get-KeycloakAdminToken -RootUrl $BaseUrl -EnvPath $EnvPath
    $adminHeaders = @{ Authorization = "Bearer $adminToken" }
    $apiRoot = "$($BaseUrl.TrimEnd('/'))/keycloak/admin/realms/$realm"
    $client = Invoke-RestMethod -Uri "$apiRoot/clients/$frontendClientUuid" -Headers $adminHeaders -TimeoutSec 30
    $attributes = @{}
    if ($null -ne $client.attributes) {
        foreach ($property in $client.attributes.PSObject.Properties) {
            $attributes[$property.Name] = $property.Value
        }
    }

    $attributes["pkce.code.challenge.method"] = "S256"
    $attributes["post.logout.redirect.uris"] = $logoutRedirectUris
    $client.redirectUris = $redirectUris
    $client.webOrigins = $baseUrls
    $client.attributes = $attributes

    $body = $client | ConvertTo-Json -Depth 20
    Invoke-RestMethod -Uri "$apiRoot/clients/$frontendClientUuid" -Method Put -Headers $adminHeaders -Body $body -ContentType "application/json" -TimeoutSec 30 | Out-Null
}

function Get-LocalKeycloakRealmUsers {
    param(
        [Parameter(Mandatory = $true)][string]$ApiRoot,
        [Parameter(Mandatory = $true)][hashtable]$Headers
    )

    $pageSize = 100
    $first = 0
    $result = [System.Collections.Generic.List[object]]::new()
    do {
        $page = @(ConvertTo-SmokeArray -Value (Invoke-RestMethod `
            -Uri "$ApiRoot/users?first=$first&max=$pageSize" `
            -Headers $Headers `
            -TimeoutSec 30))
        foreach ($user in $page) {
            [void]$result.Add($user)
        }
        $first += $page.Count
    } while ($page.Count -eq $pageSize)

    return $result.ToArray()
}

function Get-LocalKeycloakRealmClients {
    param(
        [Parameter(Mandatory = $true)][string]$ApiRoot,
        [Parameter(Mandatory = $true)][hashtable]$Headers
    )

    $pageSize = 100
    $first = 0
    $result = [System.Collections.Generic.List[object]]::new()
    do {
        $page = @(ConvertTo-SmokeArray -Value (Invoke-RestMethod `
            -Uri "$ApiRoot/clients?first=$first&max=$pageSize" `
            -Headers $Headers `
            -TimeoutSec 30))
        foreach ($client in $page) {
            [void]$result.Add($client)
        }
        $first += $page.Count
    } while ($page.Count -eq $pageSize)

    return $result.ToArray()
}

function Remove-LocalKeycloakLoginSmokeClients {
    param(
        [Parameter(Mandatory = $true)][string]$ApiRoot,
        [Parameter(Mandatory = $true)][hashtable]$Headers
    )

    # Both prefixes are reserved by this isolated prod-local script. The first
    # is the current project-specific prefix; the second removes leftovers from
    # the earlier implementation without matching normal application clients.
    $reservedPrefixes = @(
        'otziv-prod-local-login-smoke-',
        'otziv-local-login-smoke-'
    )
    $staleClients = @(
        @(Get-LocalKeycloakRealmClients -ApiRoot $ApiRoot -Headers $Headers) |
            Where-Object {
                $candidateId = [string]$_.clientId
                $reservedPrefixes | Where-Object {
                    $candidateId.StartsWith($_, [System.StringComparison]::Ordinal)
                } | Select-Object -First 1
            }
    )
    foreach ($client in $staleClients) {
        $parsedClientUuid = [guid]::Empty
        if ([string]::IsNullOrWhiteSpace([string]$client.clientId) `
                -or -not [guid]::TryParse([string]$client.id, [ref]$parsedClientUuid)) {
            throw 'Local Keycloak returned an invalid reserved login-smoke client.'
        }
        Invoke-RestMethod `
            -Uri "$ApiRoot/clients/$($parsedClientUuid.ToString())" `
            -Method Delete `
            -Headers $Headers `
            -TimeoutSec 30 | Out-Null
    }

    $remainingReservedClients = @(
        @(Get-LocalKeycloakRealmClients -ApiRoot $ApiRoot -Headers $Headers) |
            Where-Object {
                $candidateId = [string]$_.clientId
                $reservedPrefixes | Where-Object {
                    $candidateId.StartsWith($_, [System.StringComparison]::Ordinal)
                } | Select-Object -First 1
            }
    )
    if ($remainingReservedClients.Count -ne 0) {
        throw "Local Keycloak still contains $($remainingReservedClients.Count) reserved login-smoke client(s) after cleanup."
    }
}

function Assert-LocalKeycloakIdentitySyncIsolation {
    param(
        [Parameter(Mandatory = $true)][string]$RootUrl,
        [Parameter(Mandatory = $true)][string[]]$ComposeArguments
    )

    try {
        $rootUri = [Uri]$RootUrl
    } catch {
        throw "Local Keycloak identity synchronization requires a valid loopback BaseUrl, got '$RootUrl'."
    }
    if (-not $rootUri.IsLoopback `
            -or $rootUri.Scheme -ne 'http' `
            -or $rootUri.AbsolutePath -ne '/' `
            -or -not [string]::IsNullOrWhiteSpace($rootUri.UserInfo) `
            -or -not [string]::IsNullOrWhiteSpace($rootUri.Query) `
            -or -not [string]::IsNullOrWhiteSpace($rootUri.Fragment)) {
        throw "Refusing to synchronize Keycloak identities through non-loopback BaseUrl '$RootUrl'."
    }

    $dockerContext = (& docker context show).Trim()
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($dockerContext)) {
        throw 'Could not determine the Docker context for local Keycloak identity synchronization.'
    }
    $dockerEndpoint = (& docker context inspect $dockerContext --format '{{.Endpoints.docker.Host}}').Trim()
    if ($LASTEXITCODE -ne 0 -or $dockerEndpoint -notmatch '^(npipe|unix)://') {
        throw "Refusing local identity synchronization through non-local Docker endpoint '$dockerEndpoint'."
    }

    foreach ($service in @('mysql', 'keycloak', 'nginx')) {
        $containerId = (& docker @($ComposeArguments + @('ps', '-q', $service))).Trim()
        if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($containerId)) {
            throw "Local identity synchronization requires a running '$service' Compose service."
        }
        $labels = (& docker inspect $containerId --format '{{index .Config.Labels "com.docker.compose.project"}}|{{index .Config.Labels "com.docker.compose.service"}}').Trim()
        if ($LASTEXITCODE -ne 0 -or $labels -ne "otziv-prod-local|$service") {
            throw "Refusing identity synchronization against non-isolated Compose service '$service' ($labels)."
        }
    }

    $publishedNginxLines = @(& docker @($ComposeArguments + @('port', 'nginx', '80')))
    $publishedNginxLines = @($publishedNginxLines | ForEach-Object { $_.ToString().Trim() } | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
    if ($LASTEXITCODE -ne 0 -or $publishedNginxLines.Count -ne 1) {
        throw "Expected exactly one published loopback endpoint for the isolated local Nginx service."
    }
    $publishedNginx = $publishedNginxLines[0]
    if ($publishedNginx -notmatch '^(?:\[(?<ipv6>[^\]]+)\]|(?<ipv4>[^:]+)):(?<port>[0-9]+)$') {
        throw "Could not verify the isolated local Nginx endpoint ($publishedNginx)."
    }
    $publishedHost = if ([string]::IsNullOrWhiteSpace($Matches['ipv6'])) { $Matches['ipv4'] } else { $Matches['ipv6'] }
    $publishedAddress = [System.Net.IPAddress]::None
    if (-not [System.Net.IPAddress]::TryParse($publishedHost, [ref]$publishedAddress) `
            -or -not [System.Net.IPAddress]::IsLoopback($publishedAddress) `
            -or [int]$Matches['port'] -ne $rootUri.Port) {
        throw "BaseUrl '$RootUrl' is not the published loopback endpoint of the isolated local Nginx service ($publishedNginx)."
    }
    $rootHostMatchesPublished = $rootUri.Host.Equals('localhost', [System.StringComparison]::OrdinalIgnoreCase) `
        -or $rootUri.Host.Equals($publishedAddress.ToString(), [System.StringComparison]::OrdinalIgnoreCase)
    if (-not $rootHostMatchesPublished) {
        throw "BaseUrl host '$($rootUri.Host)' does not match the isolated local Nginx host '$publishedHost'."
    }
}

function Get-LocalKeycloakDatabaseUsers {
    param(
        [Parameter(Mandatory = $true)][string]$EnvPath,
        [Parameter(Mandatory = $true)][string[]]$ComposeArguments
    )

    $mysqlUser = Get-EnvValue -Path $EnvPath -Name 'MYSQL_USER'
    $mysqlPassword = Get-EnvValue -Path $EnvPath -Name 'MYSQL_PASSWORD'
    $mysqlDatabase = Get-EnvValue -Path $EnvPath -Name 'MYSQL_DATABASE'
    if ([string]::IsNullOrWhiteSpace($mysqlUser) `
            -or [string]::IsNullOrWhiteSpace($mysqlPassword) `
            -or [string]::IsNullOrWhiteSpace($mysqlDatabase)) {
        throw 'MYSQL_USER, MYSQL_PASSWORD, and MYSQL_DATABASE are required for local identity synchronization.'
    }

    # The production subject is used only as an eligibility predicate and is
    # deliberately never selected. Only the minimum local-login data leaves
    # the isolated MySQL container: username, active status and managed roles;
    # no password, email, phone or full-name fields are selected.
    $databaseSql = @"
SET SESSION group_concat_max_len = 8192;
SELECT
    user_row.id,
    HEX(user_row.username),
    IF(user_row.active, 1, 0),
    COALESCE(GROUP_CONCAT(DISTINCT HEX(TRIM(role_row.name)) ORDER BY role_row.name SEPARATOR ','), '')
FROM users AS user_row
JOIN users_roles AS user_role ON user_role.user_id = user_row.id
JOIN roles AS role_row ON role_row.id = user_role.role_id
WHERE UPPER(TRIM(user_row.auth_provider)) = 'KEYCLOAK'
  AND user_row.keycloak_id IS NOT NULL
  AND TRIM(user_row.keycloak_id) <> ''
  AND EXISTS (
      SELECT 1
      FROM users_roles AS eligible_user_role
      JOIN roles AS eligible_role ON eligible_role.id = eligible_user_role.role_id
      WHERE eligible_user_role.user_id = user_row.id
        AND UPPER(TRIM(eligible_role.name)) IN (
            'ROLE_OWNER', 'ROLE_ADMIN', 'ROLE_MANAGER', 'ROLE_WORKER', 'ROLE_PERFORMER'
        )
  )
GROUP BY user_row.id, user_row.username, user_row.active
ORDER BY user_row.id;
"@
    $databaseOutput = & docker @($ComposeArguments + @(
        'exec', '-T', '-e', "MYSQL_PWD=$mysqlPassword", 'mysql',
        'mysql', '--default-character-set=utf8mb4', "-u$mysqlUser",
        $mysqlDatabase, '-N', '-B', '-e', $databaseSql
    )) 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw 'Could not read eligible users from the isolated local database for Keycloak provisioning.'
    }

    $managedRoleNames = [System.Collections.Generic.HashSet[string]]::new(
        [System.StringComparer]::Ordinal
    )
    foreach ($roleName in @('ADMIN', 'OWNER', 'MANAGER', 'WORKER', 'OPERATOR', 'MARKETOLOG', 'PERFORMER', 'CLIENT')) {
        [void]$managedRoleNames.Add($roleName)
    }
    $users = [System.Collections.Generic.List[object]]::new()
    $usersByName = [System.Collections.Generic.HashSet[string]]::new(
        [System.StringComparer]::OrdinalIgnoreCase
    )
    foreach ($line in $databaseOutput) {
        $text = $line.ToString()
        if ([string]::IsNullOrWhiteSpace($text)) {
            continue
        }
        if ($text -notmatch '^(?<id>[0-9]+)\t(?<usernameHex>[0-9A-F]*)\t(?<active>[01])\t(?<rolesHex>[0-9A-F,]*)$') {
            throw 'The isolated local database returned an unexpected row while enumerating eligible Keycloak users.'
        }

        $databaseId = [long]$Matches['id']
        $databaseUsernameHex = $Matches['usernameHex']
        $databaseActive = $Matches['active'] -eq '1'
        $databaseRolesHex = $Matches['rolesHex']
        try {
            $databaseUsername = ConvertFrom-SmokeHexUtf8 -Hex $databaseUsernameHex
        } catch {
            throw "The isolated local database returned an invalid encoded username for user id $databaseId."
        }
        if ([string]::IsNullOrWhiteSpace($databaseUsername)) {
            throw "The isolated local database contains a blank eligible username for user id $databaseId."
        }
        if (-not $usersByName.Add($databaseUsername)) {
            throw "The isolated local database contains duplicate case-insensitive eligible username '$databaseUsername'."
        }

        $userRoles = [System.Collections.Generic.SortedSet[string]]::new(
            [System.StringComparer]::OrdinalIgnoreCase
        )
        foreach ($roleHex in @($databaseRolesHex -split ',' | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })) {
            try {
                $databaseRole = (ConvertFrom-SmokeHexUtf8 -Hex $roleHex).Trim()
            } catch {
                throw "The isolated local database returned an invalid role for user id $databaseId."
            }
            if ($databaseRole -notmatch '^ROLE_(?<role>[A-Za-z0-9_]+)$') {
                throw "Eligible local database user '$databaseUsername' has unsupported role '$databaseRole'."
            }
            $realmRole = $Matches['role'].ToUpperInvariant()
            if (-not $managedRoleNames.Contains($realmRole)) {
                throw "Eligible local database user '$databaseUsername' has unmanaged application role '$databaseRole'."
            }
            [void]$userRoles.Add($realmRole)
        }

        [void]$users.Add([pscustomobject]@{
            Id = $databaseId
            Username = $databaseUsername
            UsernameHex = $databaseUsernameHex
            Active = $databaseActive
            RealmRoles = @($userRoles)
        })
    }
    if ($users.Count -eq 0) {
        throw 'The isolated local database contains no eligible Keycloak staff or performer users.'
    }

    return $users.ToArray()
}

function Get-LocalKeycloakSha256Hex {
    param([Parameter(Mandatory = $true)][string]$Text)

    $bytes = [System.Text.Encoding]::UTF8.GetBytes($Text)
    $sha256 = [System.Security.Cryptography.SHA256]::Create()
    try {
        $digest = $sha256.ComputeHash($bytes)
        return [System.BitConverter]::ToString($digest).Replace('-', '').ToLowerInvariant()
    } finally {
        [Array]::Clear($bytes, 0, $bytes.Length)
        $sha256.Dispose()
    }
}

function Get-LocalKeycloakAllowlistHmacKey {
    param(
        [Parameter(Mandatory = $true)][string]$EnvPath,
        [switch]$CreateIfMissing
    )

    $keyName = 'OTZIV_LOCAL_LOGIN_ALLOWLIST_HMAC_KEY_BASE64'
    $encodedKey = Get-EnvValue -Path $EnvPath -Name $keyName
    if ([string]::IsNullOrWhiteSpace($encodedKey)) {
        if (-not $CreateIfMissing) {
            throw "$keyName is missing from the protected external prod-local env file. Restore that file from its secure backup; a new key may be created only together with -InitializeLocalKeycloakUserSnapshot."
        }

        $randomBytes = New-Object byte[] 32
        $randomSource = [System.Security.Cryptography.RandomNumberGenerator]::Create()
        try {
            $randomSource.GetBytes($randomBytes)
            $encodedKey = [Convert]::ToBase64String($randomBytes)
            Set-LocalEnvFileValues -Path $EnvPath -Values @{
                OTZIV_LOCAL_LOGIN_ALLOWLIST_HMAC_KEY_BASE64 = $encodedKey
            }
        } finally {
            [Array]::Clear($randomBytes, 0, $randomBytes.Length)
            $randomSource.Dispose()
        }
    }

    $decodedKey = $null
    try {
        try {
            $decodedKey = [Convert]::FromBase64String($encodedKey)
        } catch {
            throw "$keyName in the protected external prod-local env file is not valid Base64. Restore the original 32-byte key; it must never be regenerated for an existing snapshot."
        }
        if ($decodedKey.Length -ne 32) {
            throw "$keyName in the protected external prod-local env file must decode to exactly 32 bytes. Restore the original key; it must never be regenerated for an existing snapshot."
        }
    } finally {
        if ($null -ne $decodedKey) {
            [Array]::Clear($decodedKey, 0, $decodedKey.Length)
        }
    }

    return $encodedKey
}

function Get-LocalKeycloakUsernameHmac {
    param(
        [Parameter(Mandatory = $true)][string]$Username,
        [Parameter(Mandatory = $true)][string]$KeyBase64
    )

    $canonical = $Username.Trim().Normalize([System.Text.NormalizationForm]::FormKC).ToLowerInvariant()
    if ([string]::IsNullOrWhiteSpace($canonical)) {
        throw 'Cannot authenticate a blank local Keycloak username for the frozen allowlist.'
    }

    $keyBytes = [Convert]::FromBase64String($KeyBase64)
    if ($keyBytes.Length -ne 32) {
        [Array]::Clear($keyBytes, 0, $keyBytes.Length)
        throw 'The local Keycloak allowlist HMAC key must contain exactly 32 bytes.'
    }
    $canonicalBytes = [System.Text.Encoding]::UTF8.GetBytes($canonical)
    $hmac = [System.Security.Cryptography.HMACSHA256]::new($keyBytes)
    $digest = $null
    try {
        $digest = $hmac.ComputeHash($canonicalBytes)
        return [System.BitConverter]::ToString($digest).Replace('-', '').ToLowerInvariant()
    } finally {
        if ($null -ne $digest) {
            [Array]::Clear($digest, 0, $digest.Length)
        }
        [Array]::Clear($canonicalBytes, 0, $canonicalBytes.Length)
        [Array]::Clear($keyBytes, 0, $keyBytes.Length)
        $hmac.Dispose()
    }
}

function Get-LocalKeycloakSnapshotUsersHash {
    param([Parameter(Mandatory = $true)][object[]]$Users)

    $projection = @($Users | Sort-Object UsernameHmacSha256 | ForEach-Object {
        [string]$_.UsernameHmacSha256
    }) -join "`n"
    return Get-LocalKeycloakSha256Hex -Text $projection
}

function New-LocalKeycloakUserSnapshot {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][object[]]$DatabaseUsers,
        [Parameter(Mandatory = $true)][string]$HmacKeyBase64
    )

    if (Test-Path -LiteralPath $Path) {
        throw "Local Keycloak user snapshot already exists and will not be overwritten: $Path"
    }
    $parent = Split-Path -Parent $Path
    if ([string]::IsNullOrWhiteSpace($parent) -or -not (Test-Path -LiteralPath $parent -PathType Container)) {
        throw "Local Keycloak user snapshot directory does not exist: $parent"
    }

    $usernameHmacs = [System.Collections.Generic.HashSet[string]]::new(
        [System.StringComparer]::Ordinal
    )
    $entries = [System.Collections.Generic.List[object]]::new()
    foreach ($databaseUser in $DatabaseUsers) {
        $usernameHmac = Get-LocalKeycloakUsernameHmac `
            -Username ([string]$databaseUser.Username) `
            -KeyBase64 $HmacKeyBase64
        if (-not $usernameHmacs.Add($usernameHmac)) {
            throw 'Refusing to create a local Keycloak user snapshot with duplicate canonical username identities.'
        }
        [void]$entries.Add([pscustomobject][ordered]@{
            usernameHmacSha256 = $usernameHmac
        })
    }
    if ($entries.Count -eq 0) {
        throw 'Refusing to create an empty local Keycloak user snapshot.'
    }

    $snapshotUsers = @($entries.ToArray() | Sort-Object usernameHmacSha256)
    $snapshot = [ordered]@{
        schemaVersion = 2
        capturedAtUtc = [DateTimeOffset]::UtcNow.ToString('o')
        selectionRoles = @('OWNER', 'ADMIN', 'MANAGER', 'WORKER', 'PERFORMER')
        usernameIdentityAlgorithm = 'HMAC-SHA256-NFKC-LOWER'
        usersSha256 = Get-LocalKeycloakSnapshotUsersHash -Users $snapshotUsers
        users = $snapshotUsers
    }
    $temporaryPath = "$Path.$([guid]::NewGuid().ToString('N')).tmp"
    $encoding = [System.Text.UTF8Encoding]::new($false)
    try {
        [System.IO.File]::WriteAllText(
            $temporaryPath,
            (($snapshot | ConvertTo-Json -Depth 8) + [Environment]::NewLine),
            $encoding
        )
        Move-Item -LiteralPath $temporaryPath -Destination $Path
    } finally {
        if (Test-Path -LiteralPath $temporaryPath) {
            Remove-Item -LiteralPath $temporaryPath -Force
        }
    }

    Write-Host "Frozen local Keycloak allowlist created with $($entries.Count) eligible user(s): $Path"
}

function Read-LocalKeycloakUserSnapshot {
    param([Parameter(Mandatory = $true)][string]$Path)

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "Local Keycloak user snapshot is missing: $Path. Create it once with -InitializeLocalKeycloakUserSnapshot -LocalLoginUsername <name>."
    }
    try {
        $snapshot = Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json
    } catch {
        throw "Local Keycloak user snapshot is not valid JSON: $Path"
    }
    if ($null -eq $snapshot -or [int]$snapshot.schemaVersion -ne 2) {
        throw "Local Keycloak user snapshot has an unsupported schema version: $Path"
    }
    $expectedRootProperties = @(
        'capturedAtUtc',
        'schemaVersion',
        'selectionRoles',
        'usernameIdentityAlgorithm',
        'users',
        'usersSha256'
    )
    $actualRootProperties = @($snapshot.PSObject.Properties | ForEach-Object { [string]$_.Name } | Sort-Object)
    if (($actualRootProperties -join ',') -cne (($expectedRootProperties | Sort-Object) -join ',')) {
        throw "Local Keycloak user snapshot contains unexpected root fields: $Path"
    }

    $expectedSelectionRoles = @('OWNER', 'ADMIN', 'MANAGER', 'WORKER', 'PERFORMER')
    $actualSelectionRoles = @(ConvertTo-SmokeArray -Value $snapshot.selectionRoles | ForEach-Object { [string]$_ })
    if (($actualSelectionRoles -join ',') -cne ($expectedSelectionRoles -join ',')) {
        throw "Local Keycloak user snapshot has unexpected selection roles: $Path"
    }
    if ([string]$snapshot.usernameIdentityAlgorithm -cne 'HMAC-SHA256-NFKC-LOWER') {
        throw "Local Keycloak user snapshot has an unsupported username identity algorithm: $Path"
    }

    $usernameHmacs = [System.Collections.Generic.HashSet[string]]::new(
        [System.StringComparer]::Ordinal
    )
    $entries = [System.Collections.Generic.List[object]]::new()
    foreach ($entry in @(ConvertTo-SmokeArray -Value $snapshot.users)) {
        if ($null -eq $entry) {
            throw "Local Keycloak user snapshot contains an invalid username HMAC entry: $Path"
        }
        $entryProperties = @($entry.PSObject.Properties | ForEach-Object { [string]$_.Name })
        if ($entryProperties.Count -ne 1 -or $entryProperties[0] -cne 'usernameHmacSha256') {
            throw "Local Keycloak user snapshot contains unexpected identity fields: $Path"
        }
        $usernameHmac = ([string]$entry.usernameHmacSha256).ToLowerInvariant()
        if ($usernameHmac -notmatch '^[0-9a-f]{64}$' `
                -or -not $usernameHmacs.Add($usernameHmac)) {
            throw "Local Keycloak user snapshot contains an invalid or duplicate username HMAC: $Path"
        }
        [void]$entries.Add([pscustomobject]@{
            UsernameHmacSha256 = $usernameHmac
        })
    }
    if ($entries.Count -eq 0) {
        throw "Local Keycloak user snapshot is empty: $Path"
    }

    $expectedUsersHash = Get-LocalKeycloakSnapshotUsersHash -Users @($entries.ToArray())
    if ([string]::IsNullOrWhiteSpace([string]$snapshot.usersSha256) `
            -or -not $expectedUsersHash.Equals([string]$snapshot.usersSha256, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Local Keycloak user snapshot checksum does not match its contents: $Path"
    }
    return $entries.ToArray()
}

function Select-FrozenLocalKeycloakDatabaseUsers {
    param(
        [Parameter(Mandatory = $true)][object[]]$DatabaseUsers,
        [Parameter(Mandatory = $true)][object[]]$SnapshotUsers,
        [Parameter(Mandatory = $true)][string]$HmacKeyBase64
    )

    $snapshotUsernameHmacs = [System.Collections.Generic.HashSet[string]]::new(
        [System.StringComparer]::Ordinal
    )
    foreach ($snapshotUser in $SnapshotUsers) {
        [void]$snapshotUsernameHmacs.Add([string]$snapshotUser.UsernameHmacSha256)
    }

    $currentUsernameHmacs = [System.Collections.Generic.HashSet[string]]::new(
        [System.StringComparer]::Ordinal
    )
    $frozenUsers = [System.Collections.Generic.List[object]]::new()
    foreach ($databaseUser in @($DatabaseUsers | Sort-Object Id)) {
        $currentUsernameHmac = Get-LocalKeycloakUsernameHmac `
            -Username ([string]$databaseUser.Username) `
            -KeyBase64 $HmacKeyBase64
        if (-not $currentUsernameHmacs.Add($currentUsernameHmac)) {
            throw 'The isolated local database returned duplicate canonical eligible usernames.'
        }
        if ($snapshotUsernameHmacs.Contains($currentUsernameHmac)) {
            [void]$frozenUsers.Add($databaseUser)
        }
    }

    $ignoredNewCount = $DatabaseUsers.Count - $frozenUsers.Count
    if ($ignoredNewCount -lt 0) {
        $ignoredNewCount = 0
    }
    if ($frozenUsers.Count -eq 0) {
        throw 'No current eligible database username matches the frozen local Keycloak allowlist. Restore the original external HMAC key; refusing to replace or empty the frozen identity set.'
    }
    Write-Host "Frozen local Keycloak allowlist: $($SnapshotUsers.Count) saved, $($frozenUsers.Count) currently eligible, $ignoredNewCount new VPS-derived user(s) ignored."
    return $frozenUsers.ToArray()
}

function Get-OrCreate-LocalKeycloakRealmRole {
    param(
        [Parameter(Mandatory = $true)][string]$ApiRoot,
        [Parameter(Mandatory = $true)][hashtable]$Headers,
        [Parameter(Mandatory = $true)][System.Collections.Generic.Dictionary[string, object]]$RoleCache,
        [Parameter(Mandatory = $true)][string]$RoleName
    )

    if ($RoleCache.ContainsKey($RoleName)) {
        return $RoleCache[$RoleName]
    }

    $encodedRole = [Uri]::EscapeDataString($RoleName)
    try {
        $realmRole = Invoke-RestMethod -Uri "$ApiRoot/roles/$encodedRole" -Headers $Headers -TimeoutSec 30
    } catch {
        $statusCode = if ($null -eq $_.Exception.Response) { 0 } else { [int]$_.Exception.Response.StatusCode }
        if ($statusCode -ne 404) {
            throw
        }
        $roleBody = @{
            name = $RoleName
            description = 'Managed only in the isolated local prod-like realm'
        } | ConvertTo-Json -Compress
        Invoke-RestMethod -Uri "$ApiRoot/roles" -Method Post -Headers $Headers -Body $roleBody -ContentType 'application/json' -TimeoutSec 30 | Out-Null
        $realmRole = Invoke-RestMethod -Uri "$ApiRoot/roles/$encodedRole" -Headers $Headers -TimeoutSec 30
    }
    $RoleCache.Add($RoleName, $realmRole)
    return $realmRole
}

function Sync-LocalKeycloakManagedRealmRoles {
    param(
        [Parameter(Mandatory = $true)][string]$ApiRoot,
        [Parameter(Mandatory = $true)][hashtable]$Headers,
        [Parameter(Mandatory = $true)][string]$KeycloakUserId,
        [Parameter(Mandatory = $true)][AllowEmptyCollection()][string[]]$DesiredRoles,
        [Parameter(Mandatory = $true)][System.Collections.Generic.Dictionary[string, object]]$RoleCache
    )

    $managedRoleNames = [System.Collections.Generic.HashSet[string]]::new(
        [System.StringComparer]::OrdinalIgnoreCase
    )
    foreach ($roleName in @('ADMIN', 'OWNER', 'MANAGER', 'WORKER', 'OPERATOR', 'MARKETOLOG', 'PERFORMER', 'CLIENT')) {
        [void]$managedRoleNames.Add($roleName)
    }
    $desired = [System.Collections.Generic.HashSet[string]]::new(
        [System.StringComparer]::Ordinal
    )
    foreach ($roleName in $DesiredRoles) {
        if (-not $managedRoleNames.Contains($roleName)) {
            throw "Refusing to assign unmanaged local realm role '$roleName'."
        }
        [void]$desired.Add($roleName)
    }

    $assignedRoles = @(ConvertTo-SmokeArray -Value (Invoke-RestMethod `
        -Uri "$ApiRoot/users/$KeycloakUserId/role-mappings/realm" `
        -Headers $Headers `
        -TimeoutSec 30))
    $assignedManaged = [System.Collections.Generic.Dictionary[string, object]]::new(
        [System.StringComparer]::Ordinal
    )
    foreach ($assignedRole in $assignedRoles) {
        $assignedName = [string]$assignedRole.name
        if ($managedRoleNames.Contains($assignedName) -and -not $assignedManaged.ContainsKey($assignedName)) {
            $assignedManaged.Add($assignedName, $assignedRole)
        }
    }

    $rolesToRemove = [System.Collections.Generic.List[object]]::new()
    foreach ($entry in $assignedManaged.GetEnumerator()) {
        if (-not $desired.Contains($entry.Key)) {
            [void]$rolesToRemove.Add($entry.Value)
        }
    }
    if ($rolesToRemove.Count -gt 0) {
        $removeBody = ConvertTo-Json -InputObject @($rolesToRemove.ToArray()) -Depth 10 -Compress
        Invoke-RestMethod `
            -Uri "$ApiRoot/users/$KeycloakUserId/role-mappings/realm" `
            -Method Delete `
            -Headers $Headers `
            -Body $removeBody `
            -ContentType 'application/json' `
            -TimeoutSec 30 | Out-Null
    }

    $rolesToAdd = [System.Collections.Generic.List[object]]::new()
    foreach ($desiredRole in $desired) {
        if (-not $assignedManaged.ContainsKey($desiredRole)) {
            [void]$rolesToAdd.Add((Get-OrCreate-LocalKeycloakRealmRole `
                -ApiRoot $ApiRoot `
                -Headers $Headers `
                -RoleCache $RoleCache `
                -RoleName $desiredRole))
        }
    }
    if ($rolesToAdd.Count -gt 0) {
        $addBody = ConvertTo-Json -InputObject @($rolesToAdd.ToArray()) -Depth 10 -Compress
        Invoke-RestMethod `
            -Uri "$ApiRoot/users/$KeycloakUserId/role-mappings/realm" `
            -Method Post `
            -Headers $Headers `
            -Body $addBody `
            -ContentType 'application/json' `
            -TimeoutSec 30 | Out-Null
    }

    $verifiedAssignments = @(ConvertTo-SmokeArray -Value (Invoke-RestMethod `
        -Uri "$ApiRoot/users/$KeycloakUserId/role-mappings/realm" `
        -Headers $Headers `
        -TimeoutSec 30))
    $verifiedManaged = [System.Collections.Generic.HashSet[string]]::new(
        [System.StringComparer]::Ordinal
    )
    foreach ($verifiedRole in $verifiedAssignments) {
        $verifiedName = [string]$verifiedRole.name
        if ($managedRoleNames.Contains($verifiedName)) {
            [void]$verifiedManaged.Add($verifiedName)
        }
    }
    if (-not $verifiedManaged.SetEquals($desired)) {
        throw "Local Keycloak managed realm-role verification failed for user id '$KeycloakUserId'."
    }
}

function Revoke-LocalKeycloakManagedUserSessions {
    param(
        [Parameter(Mandatory = $true)][string]$ApiRoot,
        [Parameter(Mandatory = $true)][hashtable]$Headers,
        [Parameter(Mandatory = $true)][string]$KeycloakUserId
    )

    Invoke-RestMethod `
        -Uri "$ApiRoot/users/$KeycloakUserId/logout" `
        -Method Post `
        -Headers $Headers `
        -TimeoutSec 30 | Out-Null

    $verifiedUser = Invoke-RestMethod `
        -Uri "$ApiRoot/users/$KeycloakUserId" `
        -Headers $Headers `
        -TimeoutSec 30
    $enabledProperty = $verifiedUser.PSObject.Properties['enabled']
    if ($null -eq $enabledProperty -or [bool]$enabledProperty.Value) {
        throw "Local Keycloak user '$KeycloakUserId' was not disabled during access revocation."
    }

    $remainingSessions = @(ConvertTo-SmokeArray -Value (Invoke-RestMethod `
        -Uri "$ApiRoot/users/$KeycloakUserId/sessions" `
        -Headers $Headers `
        -TimeoutSec 30))
    if ($remainingSessions.Count -ne 0) {
        throw "Local Keycloak user '$KeycloakUserId' still has $($remainingSessions.Count) session(s) after access revocation."
    }
}

function Ensure-LocalKeycloakManagedMarkerProfile {
    param(
        [Parameter(Mandatory = $true)][string]$ApiRoot,
        [Parameter(Mandatory = $true)][hashtable]$Headers
    )

    $profile = Invoke-RestMethod -Uri "$ApiRoot/users/profile" -Headers $Headers -TimeoutSec 30
    $profileAttributes = [System.Collections.Generic.List[object]]::new()
    foreach ($profileAttribute in @(ConvertTo-SmokeArray -Value $profile.attributes)) {
        [void]$profileAttributes.Add($profileAttribute)
    }
    $markerAttributes = @($profileAttributes | Where-Object { $_.name -eq 'otziv.local-managed' })
    if ($markerAttributes.Count -gt 1) {
        throw 'Local Keycloak user profile contains duplicate local-managed marker definitions.'
    }
    $markerDefinition = [ordered]@{
        name = 'otziv.local-managed'
        displayName = 'Local managed identity'
        permissions = [ordered]@{
            view = @('admin')
            edit = @('admin')
        }
        multivalued = $false
    }
    $replaceMarker = $markerAttributes.Count -eq 0
    if ($markerAttributes.Count -eq 1) {
        $existingMarker = $markerAttributes[0]
        $permissionsProperty = $existingMarker.PSObject.Properties['permissions']
        $multivaluedProperty = $existingMarker.PSObject.Properties['multivalued']
        $viewProperty = if ($null -eq $permissionsProperty -or $null -eq $permissionsProperty.Value) {
            $null
        } else {
            $permissionsProperty.Value.PSObject.Properties['view']
        }
        $editProperty = if ($null -eq $permissionsProperty -or $null -eq $permissionsProperty.Value) {
            $null
        } else {
            $permissionsProperty.Value.PSObject.Properties['edit']
        }
        $viewValues = @(if ($null -ne $viewProperty) { $viewProperty.Value })
        $editValues = @(if ($null -ne $editProperty) { $editProperty.Value })
        $replaceMarker = $viewValues.Count -ne 1 `
            -or [string]$viewValues[0] -cne 'admin' `
            -or $editValues.Count -ne 1 `
            -or [string]$editValues[0] -cne 'admin' `
            -or $null -eq $multivaluedProperty `
            -or [bool]$multivaluedProperty.Value
    }
    if ($replaceMarker) {
        $updatedAttributes = [System.Collections.Generic.List[object]]::new()
        foreach ($profileAttribute in $profileAttributes) {
            if ($profileAttribute.name -ne 'otziv.local-managed') {
                [void]$updatedAttributes.Add($profileAttribute)
            }
        }
        [void]$updatedAttributes.Add($markerDefinition)
        $profile.attributes = $updatedAttributes.ToArray()
        $profileBody = $profile | ConvertTo-Json -Depth 30
        Invoke-RestMethod `
            -Uri "$ApiRoot/users/profile" `
            -Method Put `
            -Headers $Headers `
            -Body $profileBody `
            -ContentType 'application/json' `
            -TimeoutSec 30 | Out-Null
    }

    $verifiedProfile = Invoke-RestMethod -Uri "$ApiRoot/users/profile" -Headers $Headers -TimeoutSec 30
    $verifiedMarkers = @(
        ConvertTo-SmokeArray -Value $verifiedProfile.attributes |
            Where-Object { $_.name -eq 'otziv.local-managed' }
    )
    $verifiedMarker = if ($verifiedMarkers.Count -eq 1) { $verifiedMarkers[0] } else { $null }
    $verifiedView = @(if ($null -ne $verifiedMarker) { $verifiedMarker.permissions.view })
    $verifiedEdit = @(if ($null -ne $verifiedMarker) { $verifiedMarker.permissions.edit })
    $verifiedMultivaluedProperty = if ($null -eq $verifiedMarker) {
        $null
    } else {
        $verifiedMarker.PSObject.Properties['multivalued']
    }
    if ($null -eq $verifiedMarker `
            -or $verifiedView.Count -ne 1 `
            -or [string]$verifiedView[0] -cne 'admin' `
            -or $verifiedEdit.Count -ne 1 `
            -or [string]$verifiedEdit[0] -cne 'admin' `
            -or $null -eq $verifiedMultivaluedProperty `
            -or [bool]$verifiedMultivaluedProperty.Value) {
        throw 'Local Keycloak user profile did not retain the admin-only local-managed marker.'
    }
}

function Sync-LocalKeycloakManagedUsers {
    param(
        [Parameter(Mandatory = $true)][string]$ApiRoot,
        [Parameter(Mandatory = $true)][hashtable]$Headers,
        [Parameter(Mandatory = $true)][object[]]$DatabaseUsers,
        [Parameter(Mandatory = $true)][AllowEmptyCollection()][object[]]$KeycloakUsers
    )

    Ensure-LocalKeycloakManagedMarkerProfile -ApiRoot $ApiRoot -Headers $Headers

    $keycloakUsersByName = [System.Collections.Generic.Dictionary[string, object]]::new(
        [System.StringComparer]::OrdinalIgnoreCase
    )
    foreach ($keycloakUser in $KeycloakUsers) {
        $usernameProperty = $keycloakUser.PSObject.Properties['username']
        $idProperty = $keycloakUser.PSObject.Properties['id']
        $serviceAccountProperty = $keycloakUser.PSObject.Properties['serviceAccountClientId']
        $keycloakUsername = if ($null -eq $usernameProperty) { $null } else { [string]$usernameProperty.Value }
        $serviceAccountClientId = if ($null -eq $serviceAccountProperty) { $null } else { [string]$serviceAccountProperty.Value }
        if ([string]::IsNullOrWhiteSpace($keycloakUsername) `
                -or -not [string]::IsNullOrWhiteSpace($serviceAccountClientId) `
                -or $keycloakUsername.StartsWith('service-account-', [System.StringComparison]::OrdinalIgnoreCase)) {
            continue
        }
        $parsedId = [guid]::Empty
        if ($null -eq $idProperty -or -not [guid]::TryParse([string]$idProperty.Value, [ref]$parsedId)) {
            throw "Local Keycloak returned an invalid user identifier for '$keycloakUsername'."
        }
        if ($keycloakUsersByName.ContainsKey($keycloakUsername)) {
            throw "Local Keycloak contains duplicate case-insensitive username '$keycloakUsername'."
        }
        $keycloakUsersByName.Add($keycloakUsername, $keycloakUser)
    }

    $roleCache = [System.Collections.Generic.Dictionary[string, object]]::new(
        [System.StringComparer]::Ordinal
    )
    $eligibleDatabaseUsernames = [System.Collections.Generic.HashSet[string]]::new(
        [System.StringComparer]::OrdinalIgnoreCase
    )
    foreach ($databaseUser in $DatabaseUsers) {
        [void]$eligibleDatabaseUsernames.Add([string]$databaseUser.Username)
    }
    $managedUsers = [System.Collections.Generic.List[object]]::new()
    $createdCount = 0
    foreach ($databaseUser in @($DatabaseUsers | Sort-Object Id)) {
        $createdForDatabaseUser = $false
        $keycloakUser = if ($keycloakUsersByName.ContainsKey($databaseUser.Username)) {
            $keycloakUsersByName[$databaseUser.Username]
        } else {
            $createBody = @{
                username = $databaseUser.Username
                enabled = [bool]$databaseUser.Active
                emailVerified = $false
                attributes = @{
                    'otziv.local-managed' = @('true')
                }
            } | ConvertTo-Json -Depth 8 -Compress
            Invoke-RestMethod `
                -Uri "$ApiRoot/users" `
                -Method Post `
                -Headers $Headers `
                -Body $createBody `
                -ContentType 'application/json' `
                -TimeoutSec 30 | Out-Null
            $encodedUsername = [Uri]::EscapeDataString($databaseUser.Username)
            $createdMatches = @(ConvertTo-SmokeArray -Value (Invoke-RestMethod `
                -Uri "$ApiRoot/users?username=$encodedUsername&exact=true&briefRepresentation=false" `
                -Headers $Headers `
                -TimeoutSec 30)) | Where-Object {
                    $_.username -and $_.username.Equals($databaseUser.Username, [System.StringComparison]::OrdinalIgnoreCase)
                }
            if ($createdMatches.Count -ne 1) {
                throw "Local Keycloak did not return exactly one newly managed user '$($databaseUser.Username)'."
            }
            $createdCount++
            $createdForDatabaseUser = $true
            $createdMatches[0]
        }

        $fullUser = Invoke-RestMethod -Uri "$ApiRoot/users/$($keycloakUser.id)" -Headers $Headers -TimeoutSec 30
        $attributes = [ordered]@{}
        $attributesProperty = $fullUser.PSObject.Properties['attributes']
        if (-not $createdForDatabaseUser) {
            $existingMarker = if ($null -eq $attributesProperty -or $null -eq $attributesProperty.Value) {
                $null
            } else {
                $attributesProperty.Value.PSObject.Properties['otziv.local-managed']
            }
            $existingMarkerValues = if ($null -eq $existingMarker) { @() } else { @($existingMarker.Value) }
            if (-not ($existingMarkerValues | Where-Object { [string]$_ -eq 'true' } | Select-Object -First 1)) {
                throw "Refusing to adopt existing unmarked local Keycloak user '$($databaseUser.Username)'."
            }
        }
        if ($null -ne $attributesProperty -and $null -ne $attributesProperty.Value) {
            foreach ($attribute in $attributesProperty.Value.PSObject.Properties) {
                $attributes[$attribute.Name] = $attribute.Value
            }
        }
        $attributes['otziv.local-managed'] = @('true')
        # PUT accepts a UserRepresentation rather than a patch. Reuse the full
        # local representation so fields such as email/name/requiredActions are
        # not cleared while updating only the managed identity state.
        $fullUser.username = $databaseUser.Username
        $fullUser.enabled = [bool]$databaseUser.Active
        if ($null -eq $attributesProperty) {
            $fullUser | Add-Member -NotePropertyName attributes -NotePropertyValue $attributes
        } else {
            $attributesProperty.Value = $attributes
        }
        $fullUser.PSObject.Properties.Remove('access')
        $updateBody = $fullUser | ConvertTo-Json -Depth 10 -Compress
        Invoke-RestMethod `
            -Uri "$ApiRoot/users/$($fullUser.id)" `
            -Method Put `
            -Headers $Headers `
            -Body $updateBody `
            -ContentType 'application/json' `
            -TimeoutSec 30 | Out-Null

        [string[]]$desiredRealmRoles = @()
        if ([bool]$databaseUser.Active) {
            $desiredRealmRoles = @($databaseUser.RealmRoles)
        }
        Sync-LocalKeycloakManagedRealmRoles `
            -ApiRoot $ApiRoot `
            -Headers $Headers `
            -KeycloakUserId ([string]$fullUser.id) `
            -DesiredRoles $desiredRealmRoles `
            -RoleCache $roleCache

        $verifiedUser = Invoke-RestMethod -Uri "$ApiRoot/users/$($fullUser.id)" -Headers $Headers -TimeoutSec 30
        $verifiedAttributesProperty = $verifiedUser.PSObject.Properties['attributes']
        $markerProperty = if ($null -eq $verifiedAttributesProperty -or $null -eq $verifiedAttributesProperty.Value) {
            $null
        } else {
            $verifiedAttributesProperty.Value.PSObject.Properties['otziv.local-managed']
        }
        $markerValues = if ($null -eq $markerProperty) { @() } else { @($markerProperty.Value) }
        # Keycloak canonicalizes usernames to lowercase in this realm, while
        # authentication and uniqueness are case-insensitive.
        if (-not $verifiedUser.username.Equals($databaseUser.Username, [System.StringComparison]::OrdinalIgnoreCase) `
                -or [bool]$verifiedUser.enabled -ne [bool]$databaseUser.Active `
                -or -not ($markerValues | Where-Object { [string]$_ -eq 'true' } | Select-Object -First 1)) {
            throw "Local Keycloak managed-user reconciliation failed for '$($databaseUser.Username)'."
        }
        if (-not [bool]$databaseUser.Active) {
            Revoke-LocalKeycloakManagedUserSessions `
                -ApiRoot $ApiRoot `
                -Headers $Headers `
                -KeycloakUserId ([string]$verifiedUser.id)
        }
        [void]$managedUsers.Add([pscustomobject]@{
            id = [string]$verifiedUser.id
            username = [string]$verifiedUser.username
            enabled = [bool]$verifiedUser.enabled
            created = $createdForDatabaseUser
        })
    }

    # The local realm survives database refreshes. Retire identities which were
    # provisioned by this script but no longer have one of the explicitly
    # allowed production roles, otherwise an old local password could outlive
    # its authorization scope.
    $retiredCount = 0
    foreach ($keycloakUser in $KeycloakUsers) {
        $username = [string]$keycloakUser.username
        $keycloakUserId = [string]$keycloakUser.id
        if ([string]::IsNullOrWhiteSpace($username) `
                -or [string]::IsNullOrWhiteSpace($keycloakUserId) `
                -or $eligibleDatabaseUsernames.Contains($username) `
                -or $username.StartsWith('service-account-', [System.StringComparison]::OrdinalIgnoreCase)) {
            continue
        }
        $fullUser = Invoke-RestMethod -Uri "$ApiRoot/users/$keycloakUserId" -Headers $Headers -TimeoutSec 30
        $attributesProperty = $fullUser.PSObject.Properties['attributes']
        $managedMarker = if ($null -eq $attributesProperty -or $null -eq $attributesProperty.Value) {
            $null
        } else {
            $attributesProperty.Value.PSObject.Properties['otziv.local-managed']
        }
        $markerValues = if ($null -eq $managedMarker) { @() } else { @($managedMarker.Value) }
        if (-not ($markerValues | Where-Object { [string]$_ -eq 'true' } | Select-Object -First 1)) {
            continue
        }

        $fullUser.enabled = $false
        $fullUser.PSObject.Properties.Remove('access')
        $retireBody = $fullUser | ConvertTo-Json -Depth 10 -Compress
        Invoke-RestMethod `
            -Uri "$ApiRoot/users/$keycloakUserId" `
            -Method Put `
            -Headers $Headers `
            -Body $retireBody `
            -ContentType 'application/json' `
            -TimeoutSec 30 | Out-Null
        Sync-LocalKeycloakManagedRealmRoles `
            -ApiRoot $ApiRoot `
            -Headers $Headers `
            -KeycloakUserId $keycloakUserId `
            -DesiredRoles @() `
            -RoleCache $roleCache
        Revoke-LocalKeycloakManagedUserSessions `
            -ApiRoot $ApiRoot `
            -Headers $Headers `
            -KeycloakUserId $keycloakUserId
        $retiredCount++
    }

    Write-Host "Local Keycloak managed-user provisioning OK: $($managedUsers.Count) eligible user(s), $createdCount created without credentials, $retiredCount stale local identity/identities disabled."
    return $managedUsers.ToArray()
}

function Sync-LocalKeycloakSubjectMappings {
    param(
        [Parameter(Mandatory = $true)][string]$EnvPath,
        [Parameter(Mandatory = $true)][string[]]$ComposeArguments,
        [Parameter(Mandatory = $true)][object[]]$DatabaseUsers,
        [Parameter(Mandatory = $true)][object[]]$KeycloakUsers
    )

    $mysqlUser = Get-EnvValue -Path $EnvPath -Name 'MYSQL_USER'
    $mysqlPassword = Get-EnvValue -Path $EnvPath -Name 'MYSQL_PASSWORD'
    $mysqlDatabase = Get-EnvValue -Path $EnvPath -Name 'MYSQL_DATABASE'
    if ([string]::IsNullOrWhiteSpace($mysqlUser) `
            -or [string]::IsNullOrWhiteSpace($mysqlPassword) `
            -or [string]::IsNullOrWhiteSpace($mysqlDatabase)) {
        throw 'MYSQL_USER, MYSQL_PASSWORD, and MYSQL_DATABASE are required for local identity synchronization.'
    }

    $keycloakUsersByName = [System.Collections.Generic.Dictionary[string, object]]::new(
        [System.StringComparer]::OrdinalIgnoreCase
    )
    foreach ($keycloakUser in $KeycloakUsers) {
        $parsedKeycloakId = [guid]::Empty
        if ([string]::IsNullOrWhiteSpace([string]$keycloakUser.username) `
                -or -not [guid]::TryParse([string]$keycloakUser.id, [ref]$parsedKeycloakId)) {
            throw 'Local Keycloak returned an invalid managed user during subject synchronization.'
        }
        if ($keycloakUsersByName.ContainsKey([string]$keycloakUser.username)) {
            throw "Local Keycloak contains duplicate managed username '$($keycloakUser.username)'."
        }
        $keycloakUsersByName.Add([string]$keycloakUser.username, [pscustomobject]@{
            Username = [string]$keycloakUser.username
            KeycloakId = $parsedKeycloakId.ToString()
        })
    }

    $mappings = [System.Collections.Generic.List[object]]::new()
    foreach ($databaseUser in $DatabaseUsers) {
        if (-not $keycloakUsersByName.ContainsKey($databaseUser.Username)) {
            throw "Eligible local database user '$($databaseUser.Username)' was not provisioned in local Keycloak."
        }
        $keycloakUser = $keycloakUsersByName[$databaseUser.Username]
        [void]$mappings.Add([pscustomobject]@{
            Id = $databaseUser.Id
            UsernameHex = $databaseUser.UsernameHex
            KeycloakId = $keycloakUser.KeycloakId
        })
    }

    $mappingValues = @($mappings | ForEach-Object {
        "($($_.Id), 0x$($_.UsernameHex), '$($_.KeycloakId)')"
    }) -join ",`n"
    $identitySql = @"
CREATE TEMPORARY TABLE local_keycloak_identity_sync (
    user_id BIGINT NOT NULL PRIMARY KEY,
    username VARBINARY(1020) NOT NULL,
    keycloak_id VARBINARY(64) NOT NULL UNIQUE
);
INSERT INTO local_keycloak_identity_sync (user_id, username, keycloak_id) VALUES
$mappingValues;
UPDATE users AS target
JOIN local_keycloak_identity_sync AS incoming
  ON incoming.user_id = target.id
 AND incoming.username = CONVERT(target.username USING binary)
SET target.keycloak_id = CONVERT(incoming.keycloak_id USING utf8mb4);
SELECT CONCAT('OTZIV_LOCAL_IDENTITY_VERIFIED=', COUNT(*))
FROM users AS target
JOIN local_keycloak_identity_sync AS incoming
  ON incoming.user_id = target.id
 AND incoming.username = CONVERT(target.username USING binary)
 AND incoming.keycloak_id = CONVERT(target.keycloak_id USING binary);
"@
    $identityOutput = & docker @($ComposeArguments + @(
        'exec', '-T', '-e', "MYSQL_PWD=$mysqlPassword", 'mysql',
        'mysql', '--default-character-set=utf8mb4', "-u$mysqlUser",
        $mysqlDatabase, '-N', '-B', '-e', $identitySql
    )) 2>&1
    if ($LASTEXITCODE -ne 0) {
        $identityDiagnostic = ($identityOutput | ForEach-Object { $_.ToString() }) -join [Environment]::NewLine
        throw "Could not synchronize the isolated local database identities with local Keycloak: $identityDiagnostic"
    }
    $verifiedLine = @($identityOutput |
        ForEach-Object { $_.ToString().Trim() } |
        Where-Object { $_ -match '^OTZIV_LOCAL_IDENTITY_VERIFIED=[0-9]+$' } |
        Select-Object -Last 1)
    if ($verifiedLine.Count -ne 1) {
        throw 'Local Keycloak identity synchronization did not return a verification count.'
    }
    $verifiedCount = [int]($verifiedLine[0] -replace '^OTZIV_LOCAL_IDENTITY_VERIFIED=', '')
    if ($verifiedCount -ne $DatabaseUsers.Count) {
        throw "Local Keycloak identity synchronization verified $verifiedCount of $($DatabaseUsers.Count) eligible users."
    }

    Write-Host "Local Keycloak identity synchronization OK: $verifiedCount eligible user(s)."
}

function Ensure-LocalKeycloakActiveLoginProfile {
    param(
        [Parameter(Mandatory = $true)][string]$ApiRoot,
        [Parameter(Mandatory = $true)][hashtable]$Headers,
        [Parameter(Mandatory = $true)][string]$KeycloakUserId
    )

    $user = Invoke-RestMethod -Uri "$ApiRoot/users/$KeycloakUserId" -Headers $Headers -TimeoutSec 30
    $changed = $false
    $localProfileValues = [ordered]@{
        email = "local-$($KeycloakUserId.Replace('-', ''))@account.invalid"
        firstName = 'Local'
        lastName = 'Account'
    }
    foreach ($entry in $localProfileValues.GetEnumerator()) {
        $property = $user.PSObject.Properties[$entry.Key]
        if ($null -eq $property) {
            $user | Add-Member -NotePropertyName $entry.Key -NotePropertyValue $entry.Value
            $changed = $true
        } elseif ([string]::IsNullOrWhiteSpace([string]$property.Value)) {
            $property.Value = $entry.Value
            $changed = $true
        }
    }

    if ($changed) {
        $user.PSObject.Properties.Remove('access')
        $body = $user | ConvertTo-Json -Depth 10 -Compress
        Invoke-RestMethod `
            -Uri "$ApiRoot/users/$KeycloakUserId" `
            -Method Put `
            -Headers $Headers `
            -Body $body `
            -ContentType 'application/json' `
            -TimeoutSec 30 | Out-Null
    }

    $verifiedUser = Invoke-RestMethod -Uri "$ApiRoot/users/$KeycloakUserId" -Headers $Headers -TimeoutSec 30
    foreach ($propertyName in @('email', 'firstName', 'lastName')) {
        $property = $verifiedUser.PSObject.Properties[$propertyName]
        if ($null -eq $property -or [string]::IsNullOrWhiteSpace([string]$property.Value)) {
            throw "Local Keycloak active login user is missing required profile field '$propertyName'."
        }
    }
}

function Sync-LocalKeycloakLoginCredential {
    param(
        [Parameter(Mandatory = $true)][string]$RootUrl,
        [Parameter(Mandatory = $true)][string]$EnvPath,
        [Parameter(Mandatory = $true)][string[]]$ComposeArguments,
        [Parameter(Mandatory = $true)][string]$SnapshotPath,
        [AllowEmptyString()][string]$Username,
        [AllowEmptyString()][string]$Password,
        [switch]$InitializeSnapshot,
        [switch]$RotateCredentials,
        [switch]$SkipCredentialSync
    )

    if (-not $SkipCredentialSync -and
        ([string]::IsNullOrWhiteSpace($Username) -or [string]::IsNullOrWhiteSpace($Password))) {
        throw 'Persistent local login username and password must not be empty when local credential verification is enabled.'
    }

    try {
        $rootUri = [Uri]$RootUrl
    } catch {
        throw "Local Keycloak identity synchronization requires a valid loopback BaseUrl, got '$RootUrl'."
    }

    if (-not $rootUri.IsLoopback) {
        throw "Refusing local Keycloak identity synchronization through non-loopback BaseUrl '$RootUrl'."
    }

    Assert-LocalKeycloakIdentitySyncIsolation -RootUrl $RootUrl -ComposeArguments $ComposeArguments
    $eligibleDatabaseUsers = @(Get-LocalKeycloakDatabaseUsers -EnvPath $EnvPath -ComposeArguments $ComposeArguments)
    $allowlistHmacKeyBase64 = Get-LocalKeycloakAllowlistHmacKey `
        -EnvPath $EnvPath `
        -CreateIfMissing:$InitializeSnapshot
    try {
        if ($InitializeSnapshot) {
            New-LocalKeycloakUserSnapshot `
                -Path $SnapshotPath `
                -DatabaseUsers $eligibleDatabaseUsers `
                -HmacKeyBase64 $allowlistHmacKeyBase64
        }
        $snapshotUsers = @(Read-LocalKeycloakUserSnapshot -Path $SnapshotPath)
        $databaseUsers = @(Select-FrozenLocalKeycloakDatabaseUsers `
            -DatabaseUsers $eligibleDatabaseUsers `
            -SnapshotUsers $snapshotUsers `
            -HmacKeyBase64 $allowlistHmacKeyBase64)
    } finally {
        $allowlistHmacKeyBase64 = $null
    }
    $realm = Get-KeycloakRealm -EnvPath $EnvPath
    $adminToken = Get-KeycloakAdminToken -RootUrl $RootUrl -EnvPath $EnvPath
    $headers = @{ Authorization = "Bearer $adminToken" }
    $apiRoot = "$($RootUrl.TrimEnd('/'))/keycloak/admin/realms/$realm"
    Remove-LocalKeycloakLoginSmokeClients -ApiRoot $apiRoot -Headers $headers
    $realmUsers = @(Get-LocalKeycloakRealmUsers -ApiRoot $apiRoot -Headers $headers)
    $managedUsers = @(Sync-LocalKeycloakManagedUsers `
        -ApiRoot $apiRoot `
        -Headers $headers `
        -DatabaseUsers $databaseUsers `
        -KeycloakUsers $realmUsers)
    Sync-LocalKeycloakSubjectMappings `
        -EnvPath $EnvPath `
        -ComposeArguments $ComposeArguments `
        -DatabaseUsers $databaseUsers `
        -KeycloakUsers $managedUsers

    if ($SkipCredentialSync) {
        Write-Host "Frozen local Keycloak identities and subject mappings synchronized; local password changes and login probes skipped."
        return
    }

    $activeDatabaseUsers = @($databaseUsers | Where-Object { [bool]$_.Active })
    $selectedDatabaseUsers = @($activeDatabaseUsers | Where-Object {
        $_.Username.Equals($Username, [System.StringComparison]::OrdinalIgnoreCase)
    })
    if ($selectedDatabaseUsers.Count -ne 1) {
        throw "LocalLoginUsername '$Username' is not an active eligible Keycloak staff or performer user in the isolated local database."
    }

    if ($InitializeSnapshot -or $RotateCredentials) {
        # This is the last durable step before password mutation. If the
        # process is interrupted afterwards, a normal smoke resumes the same
        # shared credential for every frozen active user.
        Set-LocalEnvFileValues -Path $EnvPath -Values @{
            OTZIV_LOCAL_LOGIN_PENDING_USERNAME = $Username
            OTZIV_LOCAL_LOGIN_PENDING_PASSWORD = $Password
        }
    }

    $managedUsersByName = [System.Collections.Generic.Dictionary[string, object]]::new(
        [System.StringComparer]::OrdinalIgnoreCase
    )
    foreach ($managedUser in $managedUsers) {
        if ([string]::IsNullOrWhiteSpace([string]$managedUser.username) `
                -or $managedUsersByName.ContainsKey([string]$managedUser.username)) {
            throw 'Local Keycloak managed-user list contains a blank or duplicate username.'
        }
        $managedUsersByName.Add([string]$managedUser.username, $managedUser)
    }

    $loginTargets = [System.Collections.Generic.List[object]]::new()
    $credentialResetCount = 0
    foreach ($databaseUser in @($activeDatabaseUsers | Sort-Object Id)) {
        if (-not $managedUsersByName.ContainsKey($databaseUser.Username)) {
            throw "Managed local Keycloak user '$($databaseUser.Username)' was not provisioned in realm '$realm'."
        }
        $managedUser = $managedUsersByName[$databaseUser.Username]
        if (-not [bool]$managedUser.enabled) {
            throw "Local Keycloak user '$($databaseUser.Username)' is disabled in realm '$realm'."
        }
        $parsedKeycloakId = [guid]::Empty
        if (-not [guid]::TryParse([string]$managedUser.id, [ref]$parsedKeycloakId)) {
            throw "Local Keycloak returned an invalid user identifier for '$($databaseUser.Username)'."
        }

        Ensure-LocalKeycloakActiveLoginProfile `
            -ApiRoot $apiRoot `
            -Headers $headers `
            -KeycloakUserId ([string]$managedUser.id)

        try {
            Invoke-RestMethod `
                -Uri "$apiRoot/attack-detection/brute-force/users/$($managedUser.id)" `
                -Method Delete `
                -Headers $headers `
                -TimeoutSec 30 | Out-Null
        } catch {
            throw "The local Keycloak brute-force state for '$($databaseUser.Username)' could not be cleared before login verification: $($_.Exception.Message)"
        }

        $credentials = @(ConvertTo-SmokeArray -Value (Invoke-RestMethod `
            -Uri "$apiRoot/users/$($managedUser.id)/credentials" `
            -Headers $headers `
            -TimeoutSec 30))
        $hasPasswordCredential = $null -ne ($credentials | Where-Object { $_.type -eq 'password' } | Select-Object -First 1)
        $resetCredential = $InitializeSnapshot `
            -or $RotateCredentials `
            -or [bool]$managedUser.created `
            -or -not $hasPasswordCredential
        if ($resetCredential) {
            $credential = @{
                type = 'password'
                value = $Password
                temporary = $false
            } | ConvertTo-Json -Compress
            Invoke-RestMethod `
                -Uri "$apiRoot/users/$($managedUser.id)/reset-password" `
                -Method Put `
                -Headers $headers `
                -Body $credential `
                -ContentType 'application/json' `
                -TimeoutSec 30 | Out-Null

            $credentialResetCount++

            $credentials = @(ConvertTo-SmokeArray -Value (Invoke-RestMethod `
                -Uri "$apiRoot/users/$($managedUser.id)/credentials" `
                -Headers $headers `
                -TimeoutSec 30))
            if (-not ($credentials | Where-Object { $_.type -eq 'password' } | Select-Object -First 1)) {
                throw "Keycloak did not retain a password credential for local user '$($databaseUser.Username)'."
            }
        }
        [void]$loginTargets.Add([pscustomobject]@{
            Username = [string]$databaseUser.Username
            KeycloakUsername = [string]$managedUser.username
        })
    }

    $loginClientId = "otziv-prod-local-login-smoke-$([guid]::NewGuid().ToString('N').Substring(0, 12))"
    $loginClientCreated = $false
    try {
        $loginClient = @{
            clientId = $loginClientId
            name = "Temporary local login smoke"
            enabled = $true
            publicClient = $true
            bearerOnly = $false
            standardFlowEnabled = $false
            implicitFlowEnabled = $false
            directAccessGrantsEnabled = $true
            serviceAccountsEnabled = $false
            protocol = "openid-connect"
            protocolMappers = @(
                @{
                    name = "backend audience"
                    protocol = "openid-connect"
                    protocolMapper = "oidc-audience-mapper"
                    consentRequired = $false
                    config = @{
                        "included.client.audience" = "otziv-backend"
                        "id.token.claim" = "false"
                        "access.token.claim" = "true"
                    }
                }
            )
        } | ConvertTo-Json -Depth 10 -Compress
        Invoke-RestMethod `
            -Uri "$apiRoot/clients" `
            -Method Post `
            -Headers $headers `
            -Body $loginClient `
            -ContentType "application/json" `
            -TimeoutSec 30 | Out-Null
        $loginClientCreated = $true

        foreach ($loginTarget in $loginTargets) {
            $token = Invoke-RestMethod `
                -Uri "$($RootUrl.TrimEnd('/'))/keycloak/realms/$realm/protocol/openid-connect/token" `
                -Method Post `
                -Body @{
                    grant_type = 'password'
                    client_id = $loginClientId
                    username = $loginTarget.KeycloakUsername
                    password = $Password
                    scope = 'openid'
                } `
                -ContentType 'application/x-www-form-urlencoded' `
                -TimeoutSec 30
            if ([string]::IsNullOrWhiteSpace($token.access_token)) {
                throw "Keycloak did not issue an access token for local user '$($loginTarget.Username)'."
            }
            $me = Invoke-RestMethod `
                -Uri "$($RootUrl.TrimEnd('/'))/api/me" `
                -Headers @{ Authorization = "Bearer $($token.access_token)" } `
                -TimeoutSec 30
            if (-not [bool]$me.authenticated `
                    -or -not ([string]$me.name).Equals($loginTarget.Username, [System.StringComparison]::OrdinalIgnoreCase) `
                    -or $null -eq $me.localUserId) {
                throw "Local Keycloak login for '$($loginTarget.Username)' succeeded, but backend local-user authorization did not."
            }
        }
    } finally {
        if ($loginClientCreated) {
            try {
                $encodedClientId = [Uri]::EscapeDataString($loginClientId)
                $clientResponse = Invoke-RestMethod -Uri "$apiRoot/clients?clientId=$encodedClientId" -Headers $headers -TimeoutSec 30
                $clients = @(ConvertTo-SmokeArray -Value $clientResponse) |
                    Where-Object { $_.clientId -eq $loginClientId }
                foreach ($client in $clients) {
                    Invoke-RestMethod -Uri "$apiRoot/clients/$($client.id)" -Method Delete -Headers $headers -TimeoutSec 30 | Out-Null
                }
            } catch {
                Write-Warning "Could not remove temporary Keycloak login client '$loginClientId': $($_.Exception.Message)"
            }
        }
    }

    Remove-LocalKeycloakLoginSmokeClients -ApiRoot $apiRoot -Headers $headers

    if ($InitializeSnapshot -or $RotateCredentials) {
        Set-LocalEnvFileValues -Path $EnvPath -Values @{
            OTZIV_LOCAL_LOGIN_USERNAME = $Username
            OTZIV_LOCAL_LOGIN_PASSWORD = $Password
        }
        Remove-LocalEnvFileValues `
            -Path $EnvPath `
            -Names @('OTZIV_LOCAL_LOGIN_PENDING_USERNAME', 'OTZIV_LOCAL_LOGIN_PENDING_PASSWORD')
        Write-Host "Persistent local Keycloak login settings saved in the protected external prod-local env file for '$Username'."
    }

    Write-Host "Frozen local Keycloak login verified for all $($loginTargets.Count) active user(s) in realm '$realm'; $credentialResetCount credential(s) initialized or explicitly rotated; selected account: '$Username'."
}

function Get-KeycloakClientCredentialsToken {
    param(
        [Parameter(Mandatory = $true)][string]$RootUrl,
        [Parameter(Mandatory = $true)][string]$Realm,
        [Parameter(Mandatory = $true)][string]$ClientId,
        [Parameter(Mandatory = $true)][string]$ClientSecret
    )

    $tokenUrl = "$($RootUrl.TrimEnd('/'))/keycloak/realms/$Realm/protocol/openid-connect/token"
    $body = @{
        grant_type = "client_credentials"
        client_id = $ClientId
        client_secret = $ClientSecret
    }
    $response = Invoke-RestMethod -Uri $tokenUrl -Method Post -Body $body -ContentType "application/x-www-form-urlencoded" -TimeoutSec 20
    if ([string]::IsNullOrWhiteSpace($response.access_token)) {
        throw "Keycloak did not return a token for smoke client $ClientId."
    }

    return $response.access_token
}

function New-KeycloakSmokeClient {
    param(
        [Parameter(Mandatory = $true)][string]$RootUrl,
        [Parameter(Mandatory = $true)][string]$Realm,
        [Parameter(Mandatory = $true)][hashtable]$AdminHeaders,
        [Parameter(Mandatory = $true)][string]$Role
    )

    $apiRoot = "$($RootUrl.TrimEnd('/'))/keycloak/admin/realms/$Realm"
    $roleKey = $Role.ToLowerInvariant()
    # Fixed IDs are exact-matched by the prod-like-only local security-state
    # exemption. The process mutex prevents concurrent smoke runs, and every
    # suite removes stale clients before creating one.
    $clientId = "otziv-smoke-ai-$roleKey"
    $clientBody = @{
        clientId = $clientId
        name = "Reputation AI smoke $Role"
        enabled = $true
        protocol = "openid-connect"
        publicClient = $false
        bearerOnly = $false
        standardFlowEnabled = $false
        implicitFlowEnabled = $false
        directAccessGrantsEnabled = $false
        serviceAccountsEnabled = $true
        protocolMappers = @(
            @{
                name = "realm roles"
                protocol = "openid-connect"
                protocolMapper = "oidc-usermodel-realm-role-mapper"
                consentRequired = $false
                config = @{
                    "multivalued" = "true"
                    "userinfo.token.claim" = "true"
                    "id.token.claim" = "true"
                    "access.token.claim" = "true"
                    "claim.name" = "roles"
                    "jsonType.label" = "String"
                }
            },
            @{
                name = "backend audience"
                protocol = "openid-connect"
                protocolMapper = "oidc-audience-mapper"
                consentRequired = $false
                config = @{
                    "included.client.audience" = "otziv-backend"
                    "id.token.claim" = "false"
                    "access.token.claim" = "true"
                }
            }
        )
    } | ConvertTo-Json -Depth 12

    Invoke-RestMethod -Uri "$apiRoot/clients" -Method Post -Headers $AdminHeaders -Body $clientBody -ContentType "application/json" -TimeoutSec 30 | Out-Null
    $clientResponse = Invoke-RestMethod -Uri "$apiRoot/clients?clientId=$([Uri]::EscapeDataString($clientId))" -Headers $AdminHeaders -TimeoutSec 30
    $clients = @(ConvertTo-SmokeArray -Value $clientResponse)
    if ($clients.Count -eq 0 -or [string]::IsNullOrWhiteSpace($clients[0].id)) {
        throw "Keycloak smoke client was not created: $clientId"
    }

    $clientUuid = $clients[0].id
    $secret = Invoke-RestMethod -Uri "$apiRoot/clients/$clientUuid/client-secret" -Headers $AdminHeaders -TimeoutSec 30
    if ([string]::IsNullOrWhiteSpace($secret.value)) {
        throw "Keycloak did not return a secret for smoke client $clientId."
    }

    $serviceAccount = Invoke-RestMethod -Uri "$apiRoot/clients/$clientUuid/service-account-user" -Headers $AdminHeaders -TimeoutSec 30
    if ([string]::IsNullOrWhiteSpace($serviceAccount.id)) {
        throw "Keycloak did not return a service account user for smoke client $clientId."
    }

    $realmRole = Invoke-RestMethod -Uri "$apiRoot/roles/$([Uri]::EscapeDataString($Role))" -Headers $AdminHeaders -TimeoutSec 30
    $roleBody = ConvertTo-Json -InputObject @(@{
        id = $realmRole.id
        name = $realmRole.name
        composite = $realmRole.composite
        clientRole = $realmRole.clientRole
        containerId = $realmRole.containerId
    }) -Depth 8
    Invoke-RestMethod -Uri "$apiRoot/users/$($serviceAccount.id)/role-mappings/realm" -Method Post -Headers $AdminHeaders -Body $roleBody -ContentType "application/json" -TimeoutSec 30 | Out-Null

    return [pscustomobject]@{
        ClientId = $clientId
        ClientUuid = $clientUuid
        ClientSecret = $secret.value
        Role = $Role
    }
}

function Remove-KeycloakSmokeClient {
    param(
        [Parameter(Mandatory = $true)][string]$RootUrl,
        [Parameter(Mandatory = $true)][string]$Realm,
        [Parameter(Mandatory = $true)][hashtable]$AdminHeaders,
        [Parameter(Mandatory = $true)][AllowNull()][object]$Client
    )

    if ($null -eq $Client -or [string]::IsNullOrWhiteSpace($Client.ClientUuid)) {
        return
    }

    try {
        $apiRoot = "$($RootUrl.TrimEnd('/'))/keycloak/admin/realms/$Realm"
        Invoke-RestMethod -Uri "$apiRoot/clients/$($Client.ClientUuid)" -Method Delete -Headers $AdminHeaders -TimeoutSec 30 | Out-Null
    } catch {
        Write-Warning "Could not remove Keycloak smoke client $($Client.ClientId): $($_.Exception.Message)"
    }
}

function Remove-KeycloakSmokeClientsByPrefix {
    param(
        [Parameter(Mandatory = $true)][string]$RootUrl,
        [Parameter(Mandatory = $true)][string]$Realm,
        [Parameter(Mandatory = $true)][hashtable]$AdminHeaders
    )

    $apiRoot = "$($RootUrl.TrimEnd('/'))/keycloak/admin/realms/$Realm"
    foreach ($clientId in @(
        "otziv-smoke-ai-admin",
        "otziv-smoke-ai-manager",
        "otziv-smoke-ai-marketolog"
    )) {
        $encodedClientId = [Uri]::EscapeDataString($clientId)
        $clientResponse = Invoke-RestMethod `
            -Uri "$apiRoot/clients?clientId=$encodedClientId" `
            -Headers $AdminHeaders `
            -TimeoutSec 30
        $clients = @(ConvertTo-SmokeArray -Value $clientResponse) |
            Where-Object { $_.clientId -eq $clientId }
        foreach ($client in $clients) {
            Remove-KeycloakSmokeClient -RootUrl $RootUrl -Realm $Realm -AdminHeaders $AdminHeaders -Client ([pscustomobject]@{
                ClientId = $client.clientId
                ClientUuid = $client.id
            })
        }
    }
}

function Invoke-SmokeWebRequest {
    param(
        [Parameter(Mandatory = $true)][string]$Uri,
        [Parameter(Mandatory = $true)][string]$Method,
        [Parameter(Mandatory = $true)][hashtable]$Headers,
        [string]$Body,
        [string]$ContentType
    )

    try {
        $request = @{
            Uri = $Uri
            Method = $Method
            Headers = $Headers
            UseBasicParsing = $true
            TimeoutSec = 30
        }
        if ($PSBoundParameters.ContainsKey("Body")) {
            $request.Body = $Body
        }
        if (-not [string]::IsNullOrWhiteSpace($ContentType)) {
            $request.ContentType = $ContentType
        }

        $response = Invoke-WebRequest @request
        return [pscustomobject]@{
            StatusCode = [int]$response.StatusCode
            Content = [string]$response.Content
            Headers = $response.Headers
        }
    } catch {
        $response = $_.Exception.Response
        if ($null -eq $response) {
            throw
        }

        return [pscustomobject]@{
            StatusCode = [int]$response.StatusCode
            Content = ""
            Headers = $response.Headers
        }
    }
}

function Assert-ReputationAiPromptRendering {
    param(
        [Parameter(Mandatory = $true)][string]$ApiRoot,
        [Parameter(Mandatory = $true)][hashtable]$Headers,
        [Parameter(Mandatory = $true)][string]$PromptKey,
        [Parameter(Mandatory = $true)][string]$Content
    )

    $encodedKey = [Uri]::EscapeDataString($PromptKey)
    $body = @{ content = $Content } | ConvertTo-Json -Compress
    $validation = Invoke-RestMethod -Uri "$ApiRoot/api/ai/reputation/prompts/$encodedKey/validate" -Method Post -Headers $Headers -Body $body -ContentType "application/json" -TimeoutSec 30
    if (-not $validation.valid) {
        throw "Reputation AI prompt validation failed for ${PromptKey}: missing=$($validation.missingPlaceholders -join ', ')."
    }

    $preview = Invoke-RestMethod -Uri "$ApiRoot/api/ai/reputation/prompts/$encodedKey/preview" -Method Post -Headers $Headers -Body $body -ContentType "application/json" -TimeoutSec 30
    if ([string]::IsNullOrWhiteSpace($preview.renderedContent)) {
        throw "Reputation AI prompt preview did not render content for $PromptKey."
    }
    if ($preview.renderedContent -match "\{\{[^}]+\}\}") {
        throw "Reputation AI prompt preview left unresolved placeholders for ${PromptKey}: $($preview.unresolvedPlaceholders -join ', ')."
    }
}

function Assert-ReputationAiPdfExport {
    param(
        [Parameter(Mandatory = $true)][string]$Uri,
        [Parameter(Mandatory = $true)][hashtable]$Headers,
        [Parameter(Mandatory = $true)][string]$Label
    )

    $tempFile = New-TemporaryFile
    try {
        $response = Invoke-WebRequest -Uri $Uri -Method Get -Headers $Headers -UseBasicParsing -TimeoutSec 45 -OutFile $tempFile.FullName -PassThru
        $bytes = [System.IO.File]::ReadAllBytes($tempFile.FullName)
        $signature = if ($bytes.Length -ge 4) { [System.Text.Encoding]::ASCII.GetString($bytes, 0, 4) } else { "" }
        $contentType = [string]::Join(" ", @($response.Headers["Content-Type"]))
        $contentDisposition = [string]::Join(" ", @($response.Headers["Content-Disposition"]))
        if ($response.StatusCode -ne 200 -or $bytes.Length -lt 1000 -or $signature -ne "%PDF" -or $contentType -notmatch "application/pdf" -or $contentDisposition -notmatch "\.pdf") {
            throw "Reputation AI $Label PDF export failed: HTTP $($response.StatusCode), length=$($bytes.Length), signature=$signature, contentType=$contentType, contentDisposition=$contentDisposition."
        }

        Write-Host "Reputation AI $Label PDF export OK: $($bytes.Length) bytes."
    } finally {
        Remove-Item -LiteralPath $tempFile.FullName -Force -ErrorAction SilentlyContinue
    }
}

function Invoke-ReputationAiPromptPresetSmoke {
    param(
        [Parameter(Mandatory = $true)][string]$ApiRoot,
        [Parameter(Mandatory = $true)][hashtable]$Headers,
        [Parameter(Mandatory = $true)][string]$PromptKey,
        [Parameter(Mandatory = $true)][string]$PresetKey
    )

    $encodedPromptKey = [Uri]::EscapeDataString($PromptKey)
    $encodedPresetKey = [Uri]::EscapeDataString($PresetKey)
    $promptResponse = Invoke-RestMethod -Uri "$ApiRoot/api/ai/reputation/prompts" -Headers $Headers -TimeoutSec 30
    $originalPrompt = @(ConvertTo-SmokeArray -Value $promptResponse) |
        Where-Object { $_.key -eq $PromptKey } |
        Select-Object -First 1
    if ($null -eq $originalPrompt) {
        throw "Reputation AI prompt was not found for preset smoke: $PromptKey."
    }

    $originalContent = [string]$originalPrompt.content
    $originalCustomized = [bool]$originalPrompt.customized
    $applied = $false
    try {
        $presetPrompt = Invoke-RestMethod -Uri "$ApiRoot/api/ai/reputation/prompts/$encodedPromptKey/presets/$encodedPresetKey" -Method Post -Headers $Headers -Body "{}" -ContentType "application/json" -TimeoutSec 30
        $applied = $true
        if ($presetPrompt.key -ne $PromptKey -or -not $presetPrompt.customized) {
            throw "Reputation AI prompt preset did not return a customized $PromptKey prompt."
        }
        if ($null -eq $presetPrompt.presets -or $presetPrompt.presets.Count -lt 3) {
            throw "Reputation AI prompt preset response did not include preset metadata."
        }

        Assert-ReputationAiPromptRendering -ApiRoot $ApiRoot -Headers $Headers -PromptKey $PromptKey -Content $presetPrompt.content

        $history = @(Invoke-RestMethod -Uri "$ApiRoot/api/ai/reputation/prompts/$encodedPromptKey/history?limit=5" -Headers $Headers -TimeoutSec 30)
        $presetHistory = $history | Where-Object { $_.action -eq "preset:$PresetKey" } | Select-Object -First 1
        if ($null -eq $presetHistory) {
            throw "Reputation AI prompt history did not include preset:$PresetKey for $PromptKey."
        }

        Write-Host "Reputation AI prompt preset OK: $PromptKey -> $PresetKey."
    } finally {
        if ($applied) {
            if ($originalCustomized) {
                $restoreBody = @{ content = $originalContent } | ConvertTo-Json -Compress
                Invoke-RestMethod -Uri "$ApiRoot/api/ai/reputation/prompts/$encodedPromptKey" -Method Put -Headers $Headers -Body $restoreBody -ContentType "application/json" -TimeoutSec 30 | Out-Null
            } else {
                Invoke-RestMethod -Uri "$ApiRoot/api/ai/reputation/prompts/$encodedPromptKey" -Method Delete -Headers $Headers -TimeoutSec 30 | Out-Null
            }

            $restoredResponse = Invoke-RestMethod -Uri "$ApiRoot/api/ai/reputation/prompts" -Headers $Headers -TimeoutSec 30
            $restoredPrompt = @(ConvertTo-SmokeArray -Value $restoredResponse) |
                Where-Object { $_.key -eq $PromptKey } |
                Select-Object -First 1
            if ($null -eq $restoredPrompt -or [string]$restoredPrompt.content -ne $originalContent) {
                throw "Reputation AI prompt restore failed after preset smoke for $PromptKey."
            }
        }
    }
}

function Invoke-ReputationAiRoleSmoke {
    param(
        [Parameter(Mandatory = $true)][string]$RootUrl,
        [Parameter(Mandatory = $true)][string]$EnvPath,
        [Parameter(Mandatory = $true)][string]$PromptKey,
        [Parameter(Mandatory = $true)][string]$PromptContent
    )

    $apiRoot = $RootUrl.TrimEnd("/")
    $realm = Get-KeycloakRealm -EnvPath $EnvPath
    $adminToken = Get-KeycloakAdminToken -RootUrl $RootUrl -EnvPath $EnvPath
    $adminHeaders = @{ Authorization = "Bearer $adminToken" }
    $clients = @()

    Remove-KeycloakSmokeClientsByPrefix -RootUrl $RootUrl -Realm $realm -AdminHeaders $adminHeaders
    try {
        foreach ($role in @("MANAGER", "MARKETOLOG")) {
            $client = New-KeycloakSmokeClient -RootUrl $RootUrl -Realm $realm -AdminHeaders $adminHeaders -Role $role
            $clients += $client
            $roleToken = Get-KeycloakClientCredentialsToken -RootUrl $RootUrl -Realm $realm -ClientId $client.ClientId -ClientSecret $client.ClientSecret
            $roleHeaders = @{ Authorization = "Bearer $roleToken" }

            $status = Invoke-SmokeWebRequest -Uri "$apiRoot/api/ai/reputation/status" -Method "Get" -Headers $roleHeaders
            if ($status.StatusCode -ne 200) {
                throw "Reputation AI status should be readable for $role, got HTTP $($status.StatusCode)."
            }
            $prompts = Invoke-SmokeWebRequest -Uri "$apiRoot/api/ai/reputation/prompts" -Method "Get" -Headers $roleHeaders
            if ($prompts.StatusCode -ne 200) {
                throw "Reputation AI prompts should be readable for $role, got HTTP $($prompts.StatusCode)."
            }

            $encodedPromptKey = [Uri]::EscapeDataString($PromptKey)
            $mutationBody = @{ content = $PromptContent } | ConvertTo-Json -Compress
            $presetAttempt = Invoke-SmokeWebRequest -Uri "$apiRoot/api/ai/reputation/prompts/$encodedPromptKey/presets/strict_facts" -Method "Post" -Headers $roleHeaders -Body "{}" -ContentType "application/json"
            if ($presetAttempt.StatusCode -ne 403) {
                throw "Reputation AI prompt preset should be forbidden for $role, got HTTP $($presetAttempt.StatusCode)."
            }
            $updateAttempt = Invoke-SmokeWebRequest -Uri "$apiRoot/api/ai/reputation/prompts/$encodedPromptKey" -Method "Put" -Headers $roleHeaders -Body $mutationBody -ContentType "application/json"
            if ($updateAttempt.StatusCode -ne 403) {
                throw "Reputation AI prompt update should be forbidden for $role, got HTTP $($updateAttempt.StatusCode)."
            }
            $resetAttempt = Invoke-SmokeWebRequest -Uri "$apiRoot/api/ai/reputation/prompts/$encodedPromptKey" -Method "Delete" -Headers $roleHeaders
            if ($resetAttempt.StatusCode -ne 403) {
                throw "Reputation AI prompt reset should be forbidden for $role, got HTTP $($resetAttempt.StatusCode)."
            }
        }

        Write-Host "Reputation AI role smoke OK: MANAGER/MARKETOLOG can read, prompt mutations are forbidden."
    } finally {
        foreach ($client in $clients) {
            Remove-KeycloakSmokeClient -RootUrl $RootUrl -Realm $realm -AdminHeaders $adminHeaders -Client $client
        }
        Remove-KeycloakSmokeClientsByPrefix -RootUrl $RootUrl -Realm $realm -AdminHeaders $adminHeaders
    }
}

function Invoke-ContractorPaymentShadowSmoke {
    param(
        [Parameter(Mandatory = $true)][string]$EnvPath,
        [Parameter(Mandatory = $true)][string[]]$ComposeArguments
    )

    Write-Host "Running contractor payment SHADOW safety smoke..."
    $mysqlUser = Get-EnvValue -Path $EnvPath -Name "MYSQL_USER"
    $mysqlPassword = Get-EnvValue -Path $EnvPath -Name "MYSQL_PASSWORD"
    $mysqlDatabase = Get-EnvValue -Path $EnvPath -Name "MYSQL_DATABASE"
    if ([string]::IsNullOrWhiteSpace($mysqlUser) `
            -or [string]::IsNullOrWhiteSpace($mysqlPassword) `
            -or [string]::IsNullOrWhiteSpace($mysqlDatabase)) {
        throw "MYSQL_USER, MYSQL_PASSWORD, and MYSQL_DATABASE are required for contractor payment schema smoke."
    }

    $contractorSchemaSql = @"
SELECT CONCAT('MIGRATIONS=', COUNT(*))
FROM flyway_schema_history
WHERE version IN (
  '1.10.217', '1.10.218', '1.10.219', '1.10.220', '1.10.221', '1.10.222',
  '1.10.223', '1.10.224', '1.10.225', '1.10.226', '1.10.227', '1.10.228',
  '1.10.229', '1.10.230'
)
  AND success = 1;

SELECT CONCAT('REQUIRED_TABLES=', COUNT(*))
FROM information_schema.tables
WHERE table_schema = DATABASE()
  AND table_name IN (
    'contractor_direct_settlements',
    'contractor_payment_accounting_phase',
    'contractor_payment_rollout_state',
    'contractor_completion_reward_markers',
    'contractor_completion_reward_repair_state',
    'contractor_completion_cutover_state'
  );

SELECT CONCAT('SAFE_SETTINGS=', COUNT(*))
FROM app_settings
WHERE (setting_key = 'contractor-payments.shadow-enabled' AND setting_value = 'true')
   OR (setting_key = 'contractor-payments.live-routing-enabled' AND setting_value = 'false')
   OR (setting_key = 'contractor-payments.reward-attribution-live-enabled' AND setting_value = 'false')
   OR (setting_key = 'contractor-payments.live-readiness-confirmed' AND setting_value = 'false')
   OR (setting_key = 'contractor-payments.completion-attribution-start-date' AND setting_value = '');

SELECT CONCAT('ACCOUNTING_SHADOW=', COUNT(*))
FROM contractor_payment_accounting_phase
WHERE id = 1 AND phase = 'SHADOW';

SELECT CONCAT('ROLLOUT_LEGACY=', COUNT(*))
FROM contractor_payment_rollout_state
WHERE id = 1
  AND accounting_authority = 'LEGACY'
  AND routing_requested = FALSE
  AND attribution_start_date IS NULL;

SELECT CONCAT('LIVE_ALLOCATIONS=', COUNT(*))
FROM contractor_payment_allocations
WHERE mode = 'LIVE';

SELECT CONCAT('CUTOVER_ROWS=', COUNT(*))
FROM contractor_completion_cutover_state;

SELECT CONCAT('REPAIR_ROWS=', COUNT(*))
FROM contractor_completion_reward_repair_state;

SELECT CONCAT('CUTOVER_CHECK=', COUNT(*))
FROM information_schema.check_constraints
WHERE constraint_schema = DATABASE()
  AND constraint_name = 'chk_contractor_completion_cutover_singleton'
  AND REPLACE(check_clause, CHAR(96), '') LIKE '%id = 1%';

SELECT CONCAT('COMPLETION_KEY=', COUNT(*))
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND table_name = 'zp'
  AND column_name = 'zp_completion_idempotency_key'
  AND extra LIKE '%STORED GENERATED%';

SELECT CONCAT('SOURCE_GENERATION=', COUNT(*))
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND table_name = 'contractor_payment_allocations'
  AND column_name = 'source_generation_snapshot';

SELECT CONCAT('GENERATION_COLLATIONS=', COUNT(*))
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND collation_name = 'utf8mb4_unicode_ci'
  AND (
    (table_name IN (
      'payment_links',
      'archive_payment_links',
      'common_invoices',
      'archive_common_invoices'
    ) AND column_name = 'shadow_route_generation')
    OR
    (table_name = 'contractor_payment_allocations'
      AND column_name = 'source_generation_snapshot')
  );

SELECT CONCAT('PAYMENT_GENERATION_JOIN=', IF(COUNT(*) >= 0, 1, 0))
FROM contractor_payment_allocations allocation
JOIN payment_links link
  ON allocation.source_generation_snapshot = link.shadow_route_generation;

SELECT CONCAT('COMMON_GENERATION_JOIN=', IF(COUNT(*) >= 0, 1, 0))
FROM contractor_payment_allocations allocation
JOIN common_invoices invoice
  ON allocation.source_generation_snapshot = invoice.shadow_route_generation;

SELECT CONCAT('CLAIM_KEY_JOIN=', IF(COUNT(*) >= 0, 1, 0))
FROM contractor_shadow_backfill_claims claim
LEFT JOIN payment_links link
  ON claim.claim_key = CONCAT('PAYMENT_LINK:', link.id);

SELECT CONCAT('COMPLETION_BASE_GAP_QUERY=', IF(COUNT(*) >= 0, 1, 0))
FROM (
  SELECT orders_row.order_id
  FROM orders orders_row
  JOIN order_statuses status_row
    ON status_row.order_status_id = orders_row.order_status
  WHERE status_row.order_status_title IN (
    'Опубликовано', 'Выставлен счет', 'Ожидает общего счета',
    'Напоминание', 'Не оплачено', 'Бан', 'Оплачено'
  )
    AND (
      SELECT COUNT(DISTINCT marker.logical_source)
      FROM contractor_completion_reward_markers marker
      WHERE marker.order_id = orders_row.order_id
        AND marker.logical_source IN (
          'ORDER_COMPLETION_MANAGER',
          'ORDER_COMPLETION_SPECIALIST',
          'PERFORMER_PRODUCT_COMPLETION'
        )
    ) < 3
    AND NOT EXISTS (
      SELECT repair.order_id
      FROM contractor_completion_reward_repair_state repair
      WHERE repair.order_id = orders_row.order_id
        AND repair.next_attempt_at > CURRENT_TIMESTAMP(6)
    )
  ORDER BY orders_row.order_id
  LIMIT 1
) completion_base_gap;

SELECT CONCAT('COMPLETION_DONE_TASK_GAP_QUERY=', IF(COUNT(*) >= 0, 1, 0))
FROM (
  SELECT DISTINCT task.bad_review_task_order
  FROM bad_review_tasks task
  WHERE task.bad_review_task_status = 'DONE'
    AND NOT EXISTS (
      SELECT marker.id
      FROM contractor_completion_reward_markers marker
      WHERE marker.order_id = task.bad_review_task_order
        AND marker.logical_source = CONCAT('BAD_REVIEW_DONE:', task.bad_review_task_id)
    )
    AND NOT EXISTS (
      SELECT repair.order_id
      FROM contractor_completion_reward_repair_state repair
      WHERE repair.order_id = task.bad_review_task_order
        AND repair.next_attempt_at > CURRENT_TIMESTAMP(6)
    )
  ORDER BY task.bad_review_task_order
  LIMIT 1
) completion_done_task_gap;

SELECT CONCAT('COMPLETION_CANCEL_TASK_GAP_QUERY=', IF(COUNT(*) >= 0, 1, 0))
FROM (
  SELECT task.bad_review_task_id
  FROM bad_review_tasks task
  WHERE task.bad_review_task_status = 'CANCELED'
    AND NOT EXISTS (
      SELECT cancel_marker.id
      FROM contractor_completion_reward_markers cancel_marker
      WHERE cancel_marker.order_id = task.bad_review_task_order
        AND cancel_marker.logical_source = CONCAT('BAD_REVIEW_CANCEL:', task.bad_review_task_id)
    )
    AND (
      EXISTS (
        SELECT done_marker.id
        FROM contractor_completion_reward_markers done_marker
        WHERE done_marker.order_id = task.bad_review_task_order
          AND done_marker.logical_source = CONCAT('BAD_REVIEW_DONE:', task.bad_review_task_id)
      )
      OR EXISTS (
        SELECT reward.zp_id
        FROM zp reward
        WHERE reward.zp_order = task.bad_review_task_order
          AND reward.zp_active = 1
          AND reward.zp_source IN (
            CONCAT('BAD_REVIEW_DONE_MANAGER:', task.bad_review_task_id),
            CONCAT('BAD_REVIEW_DONE_SPECIALIST:', task.bad_review_task_id)
          )
      )
    )
    AND NOT EXISTS (
      SELECT repair.order_id
      FROM contractor_completion_reward_repair_state repair
      WHERE repair.order_id = task.bad_review_task_order
        AND repair.next_attempt_at > CURRENT_TIMESTAMP(6)
    )
  ORDER BY task.bad_review_task_id
  LIMIT 1
) completion_cancel_task_gap;

SELECT CONCAT('ROUTING_REASON_COLUMNS=', COUNT(*))
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND table_name = 'contractor_payment_allocations'
  AND column_name IN (
    'routing_decision_reason',
    'specialist_rejection_reason',
    'manager_rejection_reason'
  );

SELECT CONCAT('ENCRYPTED_COMMENT_COLUMNS=', COUNT(*))
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND (
    (table_name = 'contractor_payment_profiles'
      AND column_name = 'payment_comment'
      AND character_maximum_length = 2048)
    OR
    (table_name = 'contractor_payment_allocations'
      AND column_name = 'payment_comment_snapshot'
      AND character_maximum_length = 2048)
  );

SELECT CONCAT('PII_CHECKS=', COUNT(*))
FROM information_schema.check_constraints
WHERE constraint_schema = DATABASE()
  AND constraint_name IN (
    'ck_common_invoices_contractor_pii_blank',
    'ck_archive_common_invoices_contractor_pii_blank'
  )
  AND check_clause LIKE '%payment_route_instruction_text%';

SELECT CONCAT('COMPANY_ROUTING_COLUMNS=', COUNT(*))
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND (
    (table_name = 'companies'
      AND column_name = 'company_contractor_payment_routing_enabled')
    OR
    (table_name IN (
      'payment_links',
      'archive_payment_links',
      'common_invoices',
      'archive_common_invoices'
    ) AND column_name = 'shadow_route_company_routing_allowed')
  );

SELECT CONCAT('COMPANY_ROUTING_DEFAULTS=', COUNT(*))
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND column_default = '1'
  AND is_nullable = 'NO'
  AND (
    (table_name = 'companies'
      AND column_name = 'company_contractor_payment_routing_enabled')
    OR
    (table_name IN (
      'payment_links',
      'archive_payment_links',
      'common_invoices',
      'archive_common_invoices'
    ) AND column_name = 'shadow_route_company_routing_allowed')
  );
"@
    $schemaOutput = & docker @($ComposeArguments + @(
        "exec", "-T", "-e", "MYSQL_PWD=$mysqlPassword", "mysql",
        "mysql",
        "--default-character-set=utf8mb4",
        "-u$mysqlUser",
        $mysqlDatabase,
        "-N", "-B",
        "-e",
        $contractorSchemaSql
    )) 2>&1
    if ($LASTEXITCODE -ne 0) {
        $schemaError = ($schemaOutput | ForEach-Object { $_.ToString() }) -join [Environment]::NewLine
        throw "Could not verify contractor payment Flyway/schema state: $schemaError"
    }
    $schemaFacts = @(
        $schemaOutput |
            ForEach-Object { $_.ToString().Trim() } |
            Where-Object { $_ -match "^[A-Z_]+=[0-9]+$" }
    )
    foreach ($expectedFact in @(
        "MIGRATIONS=14",
        "REQUIRED_TABLES=6",
        "SAFE_SETTINGS=5",
        "ACCOUNTING_SHADOW=1",
        "ROLLOUT_LEGACY=1",
        "LIVE_ALLOCATIONS=0",
        "CUTOVER_ROWS=0",
        "REPAIR_ROWS=0",
        "CUTOVER_CHECK=1",
        "COMPLETION_KEY=1",
        "SOURCE_GENERATION=1",
        "GENERATION_COLLATIONS=5",
        "PAYMENT_GENERATION_JOIN=1",
        "COMMON_GENERATION_JOIN=1",
        "CLAIM_KEY_JOIN=1",
        "COMPLETION_BASE_GAP_QUERY=1",
        "COMPLETION_DONE_TASK_GAP_QUERY=1",
        "COMPLETION_CANCEL_TASK_GAP_QUERY=1",
        "ROUTING_REASON_COLUMNS=3",
        "ENCRYPTED_COMMENT_COLUMNS=2",
        "PII_CHECKS=2",
        "COMPANY_ROUTING_COLUMNS=5",
        "COMPANY_ROUTING_DEFAULTS=5"
    )) {
        if ($schemaFacts -notcontains $expectedFact) {
            throw "Contractor payment schema invariant '$expectedFact' is missing. Actual: $($schemaFacts -join ', ')."
        }
    }

    $masterOutput = & docker @($ComposeArguments + @(
        "exec", "-T", "app", "sh", "-lc",
        'printf "LIVE_MASTER=%s\nREWARD_MASTER=%s\n" "$OTZIV_CONTRACTOR_PAYMENTS_LIVE_ROUTING_MASTER_ENABLED" "$OTZIV_CONTRACTOR_PAYMENTS_REWARD_ATTRIBUTION_MASTER_ENABLED"'
    )) 2>&1
    if ($LASTEXITCODE -ne 0) {
        $masterError = ($masterOutput | ForEach-Object { $_.ToString() }) -join [Environment]::NewLine
        throw "Could not verify contractor payment deployment masters: $masterError"
    }
    $masterFacts = @(
        $masterOutput |
            ForEach-Object { $_.ToString().Trim().ToLowerInvariant() } |
            Where-Object { $_ -match "^(live|reward)_master=(true|false)$" }
    )
    foreach ($expectedMaster in @("live_master=false", "reward_master=false")) {
        if ($masterFacts -notcontains $expectedMaster) {
            throw "Unsafe contractor payment deployment master. Expected '$expectedMaster'; actual: $($masterFacts -join ', ')."
        }
    }

    Write-Host "Contractor payment SHADOW safety smoke OK: V217-V230 schema is complete, company payment-routing defaults and source snapshots are present, generation joins are collation-safe, accounting/routing remain LEGACY/SHADOW, completion cutover is unset, and both deployment masters are false."
}

function Invoke-WorkloadShadowSmoke {
    param(
        [Parameter(Mandatory = $true)][string]$RootUrl,
        [Parameter(Mandatory = $true)][string]$EnvPath,
        [Parameter(Mandatory = $true)][string[]]$ComposeArguments
    )

    Write-Host "Running workload SHADOW safety smoke..."
    $apiRoot = $RootUrl.TrimEnd("/")
    $mysqlUser = Get-EnvValue -Path $EnvPath -Name "MYSQL_USER"
    $mysqlPassword = Get-EnvValue -Path $EnvPath -Name "MYSQL_PASSWORD"
    $mysqlDatabase = Get-EnvValue -Path $EnvPath -Name "MYSQL_DATABASE"
    if ([string]::IsNullOrWhiteSpace($mysqlUser) `
            -or [string]::IsNullOrWhiteSpace($mysqlPassword) `
            -or [string]::IsNullOrWhiteSpace($mysqlDatabase)) {
        throw "MYSQL_USER, MYSQL_PASSWORD, and MYSQL_DATABASE are required for workload schema smoke."
    }

    $workloadSchemaSql = @"
SELECT CONCAT('MIGRATIONS=', COUNT(*))
FROM flyway_schema_history
WHERE version IN (
  '1.10.152', '1.10.153', '1.10.154',
  '1.10.155', '1.10.156', '1.10.157'
)
  AND success = 1;

SELECT CONCAT('DELIVERY_DEADLINE=', COUNT(*))
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND table_name = 'workload_transfer_offers'
  AND column_name = 'delivery_deadline_at'
  AND is_nullable = 'NO';

SELECT CONCAT('OFFER_BATCH_INDEX=', COUNT(*))
FROM information_schema.statistics
WHERE table_schema = DATABASE()
  AND table_name = 'workload_transfer_offers'
  AND index_name = 'idx_workload_transfer_offer_processing_token'
  AND non_unique = 1;

SELECT CONCAT('EMERGENCY_BATCH_INDEX=', COUNT(*))
FROM information_schema.statistics
WHERE table_schema = DATABASE()
  AND table_name = 'workload_transfer_emergency_assignments'
  AND index_name = 'idx_workload_transfer_emergency_notification_token'
  AND non_unique = 1;

SELECT CONCAT('OBSOLETE_UNIQUE_BATCH_INDEXES=', COUNT(*))
FROM information_schema.statistics
WHERE table_schema = DATABASE()
  AND (
    (table_name = 'workload_transfer_offers'
      AND index_name = 'uk_workload_transfer_offer_processing_token')
    OR
    (table_name = 'workload_transfer_emergency_assignments'
      AND index_name = 'uk_workload_transfer_emergency_notification_token')
  );

SELECT CONCAT('STAGING_BATCH_COLUMN=', COUNT(*))
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND table_name = 'workload_transfer_offers'
  AND column_name = 'staging_batch_token'
  AND is_nullable = 'YES';

SELECT CONCAT('STAGING_BATCH_INDEX=', COUNT(DISTINCT index_name))
FROM information_schema.statistics
WHERE table_schema = DATABASE()
  AND table_name = 'workload_transfer_offers'
  AND index_name = 'idx_workload_transfer_offer_staging_batch'
  AND non_unique = 1;
"@
    $schemaOutput = & docker @($ComposeArguments + @(
        "exec", "-T", "-e", "MYSQL_PWD=$mysqlPassword", "mysql",
        "mysql",
        "--default-character-set=utf8mb4",
        "-u$mysqlUser",
        $mysqlDatabase,
        "-N", "-B",
        "-e",
        $workloadSchemaSql
    )) 2>&1
    if ($LASTEXITCODE -ne 0) {
        $schemaError = ($schemaOutput | ForEach-Object { $_.ToString() }) -join [Environment]::NewLine
        throw "Could not verify workload Flyway/schema state: $schemaError"
    }
    $schemaFacts = @(
        $schemaOutput |
            ForEach-Object { $_.ToString().Trim() } |
            Where-Object { $_ -match "^[A-Z_]+=[0-9]+$" }
    )
    foreach ($expectedFact in @(
        "MIGRATIONS=6",
        "DELIVERY_DEADLINE=1",
        "OFFER_BATCH_INDEX=1",
        "EMERGENCY_BATCH_INDEX=1",
        "OBSOLETE_UNIQUE_BATCH_INDEXES=0",
        "STAGING_BATCH_COLUMN=1",
        "STAGING_BATCH_INDEX=1"
    )) {
        if ($schemaFacts -notcontains $expectedFact) {
            throw "Workload schema invariant '$expectedFact' is missing. Actual: $($schemaFacts -join ', ')."
        }
    }

    $realm = Get-KeycloakRealm -EnvPath $EnvPath
    $adminToken = Get-KeycloakAdminToken -RootUrl $RootUrl -EnvPath $EnvPath
    $keycloakAdminHeaders = @{ Authorization = "Bearer $adminToken" }
    $client = $null

    Remove-KeycloakSmokeClientsByPrefix `
        -RootUrl $RootUrl `
        -Realm $realm `
        -AdminHeaders $keycloakAdminHeaders
    try {
        $client = New-KeycloakSmokeClient `
            -RootUrl $RootUrl `
            -Realm $realm `
            -AdminHeaders $keycloakAdminHeaders `
            -Role "ADMIN"
        $roleToken = Get-KeycloakClientCredentialsToken `
            -RootUrl $RootUrl `
            -Realm $realm `
            -ClientId $client.ClientId `
            -ClientSecret $client.ClientSecret
        $headers = @{ Authorization = "Bearer $roleToken" }

        $frontendRoute = Invoke-WebRequest `
            -Uri "$apiRoot/admin/workload-monitor" `
            -UseBasicParsing `
            -TimeoutSec 30
        if ($frontendRoute.StatusCode -ne 200 `
                -or -not ([string]$frontendRoute.Content).Contains("app-root")) {
            throw "Workload monitor frontend route failed: HTTP $($frontendRoute.StatusCode)."
        }

        $shadowSettings = Invoke-RestMethod `
            -Uri "$apiRoot/api/admin/workload-shadow/settings" `
            -Headers $headers `
            -TimeoutSec 30
        if ([string]$shadowSettings.mode -ne "SHADOW" -or [bool]$shadowSettings.applyEnabled) {
            throw "Unsafe workload SHADOW settings in prod-like: mode=$($shadowSettings.mode), applyEnabled=$($shadowSettings.applyEnabled)."
        }
        $shadowRevisionBeforeUpdate = [long]$shadowSettings.revision
        $shadowSettings = Invoke-RestMethod `
            -Uri "$apiRoot/api/admin/workload-shadow/settings" `
            -Method Put `
            -Headers $headers `
            -ContentType "application/json" `
            -Body ($shadowSettings | ConvertTo-Json -Depth 10 -Compress) `
            -TimeoutSec 30
        if ([string]$shadowSettings.mode -ne "SHADOW" `
                -or [bool]$shadowSettings.applyEnabled `
                -or [long]$shadowSettings.revision -ne ($shadowRevisionBeforeUpdate + 1)) {
            throw "Workload SHADOW settings round-trip failed or became unsafe: $($shadowSettings | ConvertTo-Json -Compress)."
        }

        $liveSettings = Invoke-RestMethod `
            -Uri "$apiRoot/api/admin/workload-shadow/live/settings" `
            -Headers $headers `
            -TimeoutSec 30
        if ([string]$liveSettings.mode -ne "SHADOW" -or [bool]$liveSettings.applyEnabled) {
            throw "Unsafe workload LIVE settings in prod-like: mode=$($liveSettings.mode), applyEnabled=$($liveSettings.applyEnabled)."
        }
        $liveRevisionBeforeUpdate = [long]$liveSettings.revision
        $liveSettings = Invoke-RestMethod `
            -Uri "$apiRoot/api/admin/workload-shadow/live/settings" `
            -Method Put `
            -Headers $headers `
            -ContentType "application/json" `
            -Body ($liveSettings | ConvertTo-Json -Depth 10 -Compress) `
            -TimeoutSec 30
        if ([string]$liveSettings.mode -ne "SHADOW" `
                -or [bool]$liveSettings.applyEnabled `
                -or [bool]$liveSettings.emergencyFallbackEnabled `
                -or [long]$liveSettings.revision -ne ($liveRevisionBeforeUpdate + 1)) {
            throw "Workload LIVE settings round-trip failed or became unsafe: $($liveSettings | ConvertTo-Json -Compress)."
        }

        $readiness = Invoke-RestMethod `
            -Uri "$apiRoot/api/admin/workload-shadow/live/readiness?targetMode=CANARY" `
            -Headers $headers `
            -TimeoutSec 30
        if ($null -eq $readiness.checks) {
            throw "Workload CANARY readiness did not return its checks."
        }

        # This is deliberately a real DML call. It catches missing transaction
        # boundaries that a fast container-health smoke cannot observe.
        $repair = Invoke-RestMethod `
            -Uri "$apiRoot/api/admin/workload-shadow/monitor/repair" `
            -Method Post `
            -Headers $headers `
            -ContentType "application/json" `
            -Body "{}" `
            -TimeoutSec 30
        if ($null -eq $repair.failedRuns -or $null -eq $repair.retriedLiveOffers) {
            throw "Workload repair response is incomplete."
        }

        $health = Invoke-RestMethod `
            -Uri "$apiRoot/api/admin/workload-shadow/monitor/health" `
            -Headers $headers `
            -TimeoutSec 30
        if ($null -ne $health.maintenance `
                -and ([string]$health.maintenance.repairStatus -eq "FAILED" `
                    -or [int]$health.maintenance.repairConsecutiveFailures -gt 0)) {
            throw "Workload maintenance health is failed after repair: $($health.maintenance | ConvertTo-Json -Compress)."
        }

        $activationBody = @{
            mode = "CANARY"
            confirmation = "ВКЛЮЧИТЬ БОЕВОЙ РЕЖИМ"
            revision = $liveSettings.revision
        } | ConvertTo-Json -Compress
        $activationAttempt = Invoke-SmokeWebRequest `
            -Uri "$apiRoot/api/admin/workload-shadow/live/activate" `
            -Method "Post" `
            -Headers $headers `
            -Body $activationBody `
            -ContentType "application/json"
        if ($activationAttempt.StatusCode -ne 403) {
            throw "ADMIN must not activate workload CANARY/LIVE, got HTTP $($activationAttempt.StatusCode)."
        }

        $appLogs = & docker @($ComposeArguments + @("logs", "--since=10m", "app")) 2>&1 | Out-String
        if ($LASTEXITCODE -ne 0) {
            throw "Could not read app logs after workload SHADOW smoke."
        }
        if ($appLogs -match "No active transaction for update or delete query" `
                -or $appLogs -match "Workload shadow stale-state repair failed" `
                -or $appLogs -match "Workload shadow nightly maintenance failed") {
            throw "Workload maintenance error was found in prod-like app logs."
        }

        Write-Host "Workload SHADOW safety smoke OK: V152-V157 schema is complete, both settings round-trips succeeded without enabling mutations, repair DML succeeded, ADMIN activation is forbidden."
    } finally {
        if ($null -ne $client) {
            Remove-KeycloakSmokeClient `
                -RootUrl $RootUrl `
                -Realm $realm `
                -AdminHeaders $keycloakAdminHeaders `
                -Client $client
        }
        Remove-KeycloakSmokeClientsByPrefix `
            -RootUrl $RootUrl `
            -Realm $realm `
            -AdminHeaders $keycloakAdminHeaders
    }
}

function Invoke-ReputationAiSmoke {
    param(
        [Parameter(Mandatory = $true)][string]$RootUrl,
        [Parameter(Mandatory = $true)][string]$EnvPath,
        [Parameter(Mandatory = $true)][int]$CompanyId,
        [Parameter(Mandatory = $true)][bool]$SkipRouteCheck
    )

    Write-Host "Running reputation AI smoke..."
    $token = Get-KeycloakServiceAccountToken -RootUrl $RootUrl -EnvPath $EnvPath
    $headers = @{ Authorization = "Bearer $token" }
    $apiRoot = $RootUrl.TrimEnd("/")

    $frontendRoute = Invoke-WebRequest -Uri "$apiRoot/admin/reputation-ai" -UseBasicParsing -TimeoutSec 30
    if ($frontendRoute.StatusCode -ne 200 -or -not ([string]$frontendRoute.Content).Contains("app-root")) {
        throw "Reputation AI frontend route shell failed: HTTP $($frontendRoute.StatusCode)."
    }
    Write-Host "Reputation AI frontend route OK: /admin/reputation-ai."

    $status = Invoke-RestMethod -Uri "$apiRoot/api/ai/reputation/status" -Headers $headers -TimeoutSec 30
    if ([string]::IsNullOrWhiteSpace($status.aiProvider)) {
        throw "Reputation AI status response did not include aiProvider."
    }
    if ($null -eq $status.openAiDiagnostics) {
        throw "Reputation AI status response did not include OpenAI diagnostics."
    }
    Write-Host "Reputation AI status OK: AI=$($status.aiProvider), Search=$($status.searchProvider), Route=$($status.openAiDiagnostics.route)."
    if ($status.aiProvider -match "^(yandex|yandexgpt)$") {
        if ([string]$status.yandexModel -match "lite") {
            throw "Reputation AI smoke expected YandexGPT Pro for deep reports, got model=$($status.yandexModel)."
        }
        $deepProfiles = @(ConvertTo-SmokeArray -Value $status.openAiResearchReportProfiles)
        $maximumProfile = $deepProfiles | Where-Object { $_.key -eq "maximum" } | Select-Object -First 1
        if ($null -eq $maximumProfile) {
            throw "Reputation AI status did not include maximum deep research profile."
        }
        if ([int]$maximumProfile.maxOutputTokens -lt 20000) {
            throw "Reputation AI Yandex maximum profile is too small: maxOutputTokens=$($maximumProfile.maxOutputTokens)."
        }
        if (-not ([string]$maximumProfile.searchContextSize).StartsWith("web_search:", [System.StringComparison]::OrdinalIgnoreCase)) {
            throw "Reputation AI Yandex deep report must use Responses Web Search, got searchContextSize=$($maximumProfile.searchContextSize)."
        }
        Write-Host "Reputation AI Yandex Responses/Web Search profile OK: model=$($status.yandexModel), maxOutputTokens=$($maximumProfile.maxOutputTokens), search=$($maximumProfile.searchContextSize)."
    }

    $prompts = Invoke-RestMethod -Uri "$apiRoot/api/ai/reputation/prompts" -Headers $headers -TimeoutSec 30
    $promptItems = @(ConvertTo-SmokeArray -Value $prompts)
    if ($promptItems.Count -lt 2) {
        throw "Reputation AI prompts response did not include expected prompt templates."
    }
    $deepReportPrompt = $promptItems | Where-Object { $_.key -eq "deep_report.instructions" } | Select-Object -First 1
    if ($null -eq $deepReportPrompt -or [string]::IsNullOrWhiteSpace($deepReportPrompt.content)) {
        throw "Reputation AI prompts response did not include deep_report.instructions content."
    }
    $contentPackPrompt = $promptItems | Where-Object { $_.key -eq "content_pack.user" } | Select-Object -First 1
    if ($null -eq $contentPackPrompt -or [string]::IsNullOrWhiteSpace($contentPackPrompt.content)) {
        throw "Reputation AI prompts response did not include content_pack.user content."
    }
    if ($null -eq $contentPackPrompt.presets -or $contentPackPrompt.presets.Count -lt 3) {
        throw "Reputation AI prompt presets were not returned for content_pack.user."
    }
    foreach ($prompt in @($deepReportPrompt, $contentPackPrompt)) {
        Assert-ReputationAiPromptRendering -ApiRoot $apiRoot -Headers $headers -PromptKey $prompt.key -Content $prompt.content
    }
    $promptHistory = Invoke-RestMethod -Uri "$apiRoot/api/ai/reputation/prompts/content_pack.user/history?limit=3" -Headers $headers -TimeoutSec 30
    $promptHistoryCount = if ($null -eq $promptHistory) { 0 } elseif ($promptHistory -is [array]) { $promptHistory.Count } else { 1 }
    Write-Host "Reputation AI prompts endpoint OK: $($promptItems.Count) prompt(s), history=$promptHistoryCount."

    Invoke-ReputationAiRoleSmoke -RootUrl $RootUrl -EnvPath $EnvPath -PromptKey "content_pack.user" -PromptContent $contentPackPrompt.content
    Invoke-ReputationAiPromptPresetSmoke -ApiRoot $apiRoot -Headers $headers -PromptKey "content_pack.user" -PresetKey "strict_facts"

    $history = Invoke-RestMethod -Uri "$apiRoot/api/ai/reputation/companies/$CompanyId/deep-research/jobs/history?limit=3" -Headers $headers -TimeoutSec 30
    $historyCount = if ($null -eq $history) { 0 } elseif ($history -is [array]) { $history.Count } else { 1 }
    Write-Host "Reputation AI history endpoint OK: $historyCount item(s)."

    $hasReadyDeepReport = $false
    $readyDeepReportJobId = $null
    $latestDeepJob = Invoke-SmokeWebRequest -Uri "$apiRoot/api/ai/reputation/companies/$CompanyId/deep-research/jobs/latest" -Method "Get" -Headers $headers
    if ($latestDeepJob.StatusCode -eq 200) {
        $deepJob = $latestDeepJob.Content | ConvertFrom-Json
        if ($null -ne $deepJob.report) {
            $hasReadyDeepReport = $true
            $readyDeepReportJobId = $deepJob.jobId
            $deepExport = Invoke-SmokeWebRequest -Uri "$apiRoot/api/ai/reputation/companies/$CompanyId/deep-research/jobs/latest/export" -Method "Get" -Headers $headers
            if ($deepExport.StatusCode -ne 200 -or [string]::IsNullOrWhiteSpace($deepExport.Content) -or -not $deepExport.Content.Contains("AI-")) {
                throw "Reputation AI deep report markdown export failed: HTTP $($deepExport.StatusCode), length=$($deepExport.Content.Length)."
            }
            Write-Host "Reputation AI deep report Markdown export OK: $($deepExport.Content.Length) chars."
            Assert-ReputationAiPdfExport -Uri "$apiRoot/api/ai/reputation/companies/$CompanyId/deep-research/jobs/latest/export/pdf" -Headers $headers -Label "deep report"
        } else {
            Write-Host "Reputation AI deep report Markdown export OK: latest job has no ready report yet."
        }
    } elseif ($latestDeepJob.StatusCode -eq 404) {
        Write-Host "Reputation AI deep report Markdown export OK: no deep report job yet (404)."
    } else {
        throw "Reputation AI latest deep report job endpoint failed: HTTP $($latestDeepJob.StatusCode)."
    }

    $hasReadyContentPack = $false
    $readyContentPackJobId = $null
    $latestContentPackJob = Invoke-SmokeWebRequest -Uri "$apiRoot/api/ai/reputation/companies/$CompanyId/content-pack/jobs/latest" -Method "Get" -Headers $headers
    if ($latestContentPackJob.StatusCode -eq 200) {
        $packJob = $latestContentPackJob.Content | ConvertFrom-Json
        if ($null -ne $packJob.pack) {
            $hasReadyContentPack = $true
            $readyContentPackJobId = $packJob.jobId
            $packExport = Invoke-SmokeWebRequest -Uri "$apiRoot/api/ai/reputation/companies/$CompanyId/content-pack/jobs/latest/export" -Method "Get" -Headers $headers
            if ($packExport.StatusCode -ne 200 -or [string]::IsNullOrWhiteSpace($packExport.Content) -or -not $packExport.Content.Contains("AI-")) {
                throw "Reputation AI content pack markdown export failed: HTTP $($packExport.StatusCode), length=$($packExport.Content.Length)."
            }
            Write-Host "Reputation AI content pack Markdown export OK: $($packExport.Content.Length) chars."
            Assert-ReputationAiPdfExport -Uri "$apiRoot/api/ai/reputation/companies/$CompanyId/content-pack/jobs/latest/export/pdf" -Headers $headers -Label "content pack"
        } else {
            Write-Host "Reputation AI content pack Markdown export OK: latest job has no ready pack yet."
        }
    } elseif ($latestContentPackJob.StatusCode -eq 404) {
        Write-Host "Reputation AI content pack Markdown export OK: no content pack job yet (404)."
    } else {
        throw "Reputation AI latest content pack job endpoint failed: HTTP $($latestContentPackJob.StatusCode)."
    }

    if ($hasReadyDeepReport -and $hasReadyContentPack) {
        if ([string]$status.aiProvider -eq "local") {
            $reviewTemplateBody = @{
                deepReportJobId = $readyDeepReportJobId
                contentPackJobId = $readyContentPackJobId
                manualNotes = "Smoke: улучшить углы отзывов через факты отчета и AI-пакета."
                topicsCount = 4
                draftsCount = 3
            } | ConvertTo-Json -Compress
            $reviewTemplates = Invoke-SmokeWebRequest -Uri "$apiRoot/api/ai/reputation/companies/$CompanyId/content-pack/review-templates" -Method "Post" -Headers $headers -Body $reviewTemplateBody -ContentType "application/json"
            if ($reviewTemplates.StatusCode -ne 200 -or [string]::IsNullOrWhiteSpace($reviewTemplates.Content)) {
                throw "Reputation AI review templates endpoint failed: HTTP $($reviewTemplates.StatusCode)."
            }
            $reviewTemplateResult = $reviewTemplates.Content | ConvertFrom-Json
            if ($reviewTemplateResult.honestReviewTopics.Count -lt 1 -or $reviewTemplateResult.reviewDraftTemplates.Count -lt 1) {
                throw "Reputation AI review templates endpoint returned empty topics or drafts."
            }
            Write-Host "Reputation AI review templates endpoint OK: topics=$($reviewTemplateResult.honestReviewTopics.Count), drafts=$($reviewTemplateResult.reviewDraftTemplates.Count)."

            $singleReviewBody = @{
                deepReportJobId = $readyDeepReportJobId
                contentPackJobId = $readyContentPackJobId
                idea = "Smoke: один черновик по УТП и готовому AI-пакету."
                style = "спокойный, честный, с мягкой рекламной пользой"
                length = "short"
            } | ConvertTo-Json -Compress
            $singleReview = Invoke-SmokeWebRequest -Uri "$apiRoot/api/ai/reputation/companies/$CompanyId/content-pack/review-draft" -Method "Post" -Headers $headers -Body $singleReviewBody -ContentType "application/json"
            if ($singleReview.StatusCode -ne 200 -or [string]::IsNullOrWhiteSpace($singleReview.Content)) {
                throw "Reputation AI single review draft endpoint failed: HTTP $($singleReview.StatusCode)."
            }
            $singleReviewResult = $singleReview.Content | ConvertFrom-Json
            if ([string]::IsNullOrWhiteSpace($singleReviewResult.draft) -or [string]::IsNullOrWhiteSpace($singleReviewResult.idea)) {
                throw "Reputation AI single review draft endpoint returned an empty draft or idea."
            }
            Write-Host "Reputation AI single review draft endpoint OK: provider=$($singleReviewResult.provider), chars=$($singleReviewResult.draft.Length)."
        } else {
            Write-Host "Reputation AI review templates endpoint OK: skipped live generation for AI provider '$($status.aiProvider)'."
            Write-Host "Reputation AI single review draft endpoint OK: skipped live generation for AI provider '$($status.aiProvider)'."
        }
    } else {
        Write-Host "Reputation AI review templates endpoint OK: skipped until both latest report and content pack are ready."
        Write-Host "Reputation AI single review draft endpoint OK: skipped until both latest report and content pack are ready."
    }

    $latestResearch = Invoke-SmokeWebRequest -Uri "$apiRoot/api/ai/reputation/companies/$CompanyId/research/latest" -Method "Get" -Headers $headers
    if ($latestResearch.StatusCode -eq 200) {
        $snapshot = $latestResearch.Content | ConvertFrom-Json
        if ($null -eq $snapshot.companyId) {
            throw "Reputation AI latest research response did not include companyId."
        }
        Write-Host "Reputation AI latest research endpoint OK: source(s)=$($snapshot.sources.Count), searchResults=$($snapshot.searchResultsCount)."
    } elseif ($latestResearch.StatusCode -eq 404) {
        Write-Host "Reputation AI latest research endpoint OK: no snapshot yet (404)."
    } else {
        throw "Reputation AI latest research endpoint failed: HTTP $($latestResearch.StatusCode)."
    }

    $sectionRewriteOptions = Invoke-SmokeWebRequest -Uri "$apiRoot/api/ai/reputation/companies/$CompanyId/deep-research/jobs/rebuild-section" -Method "Options" -Headers $headers
    $allowedMethods = [string]$sectionRewriteOptions.Headers.Allow
    if ($sectionRewriteOptions.StatusCode -lt 200 -or $sectionRewriteOptions.StatusCode -ge 400 -or -not $allowedMethods.Contains("POST")) {
        throw "Reputation AI rebuild-section endpoint is not advertised as POST. HTTP=$($sectionRewriteOptions.StatusCode), Allow=$allowedMethods"
    }
    Write-Host "Reputation AI rebuild-section endpoint OK: Allow=$allowedMethods."

    if (-not $SkipRouteCheck) {
        $diagnostics = Invoke-RestMethod -Uri "$apiRoot/api/ai/reputation/status/openai-check" -Method Post -Headers $headers -TimeoutSec 45
        if ($diagnostics.configured -and $diagnostics.lastCheckStatus -ne "ok") {
            throw "OpenAI route check failed: status=$($diagnostics.lastCheckStatus), http=$($diagnostics.lastHttpStatus), message=$($diagnostics.lastMessage)"
        }
        Write-Host "OpenAI route check OK: status=$($diagnostics.lastCheckStatus), http=$($diagnostics.lastHttpStatus)."
    }
}

function Test-RegistryBuildFailure {
    param([string]$Output)

    if ([string]::IsNullOrWhiteSpace($Output)) {
        return $false
    }

    return $Output -match "registry-1\.docker\.io|docker/dockerfile|failed to resolve source metadata|Docker Desktop has no HTTPS proxy|lookup .* no such host|no such host|network is unreachable|i/o timeout|TLS handshake timeout"
}

function Test-DockerComposeMissingNetwork {
    param([string]$Output)

    if ([string]::IsNullOrWhiteSpace($Output)) {
        return $false
    }

    return $Output -match "failed to set up container networking:\s*network\s+[0-9a-f]+\s+not found"
}

function Invoke-DockerComposeUp {
    param(
        [Parameter(Mandatory = $true)][string[]]$ComposeArguments,
        [Parameter(Mandatory = $true)][string[]]$UpArguments
    )

    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $output = & docker @($ComposeArguments + $UpArguments) 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }

    $text = ($output | ForEach-Object { $_.ToString() }) -join [Environment]::NewLine
    if (-not [string]::IsNullOrWhiteSpace($text)) {
        Write-Host $text
    }

    [pscustomobject]@{
        ExitCode = $exitCode
        Output = $text
    }
}

function Invoke-DockerComposeUpWithNetworkRepair {
    param(
        [Parameter(Mandatory = $true)][string[]]$ComposeArguments,
        [Parameter(Mandatory = $true)][string[]]$UpArguments
    )

    $result = Invoke-DockerComposeUp -ComposeArguments $ComposeArguments -UpArguments $UpArguments
    if ($result.ExitCode -eq 0 -or -not (Test-DockerComposeMissingNetwork -Output $result.Output)) {
        return $result
    }

    Write-Warning "Docker Compose found a stale container network. Recreating local containers without removing volumes, then retrying up once."
    Invoke-External -FilePath "docker" -Arguments ($ComposeArguments + @("down", "--remove-orphans"))
    return Invoke-DockerComposeUp -ComposeArguments $ComposeArguments -UpArguments $UpArguments
}

function Invoke-OfflineAppBuild {
    param(
        [Parameter(Mandatory = $true)][string]$RepoRoot,
        [Parameter(Mandatory = $true)][string]$EnvPath
    )

    $backendDir = Join-Path $RepoRoot "backend"
    $runtimeDockerfile = Join-Path $backendDir "Dockerfile.runtime-local"
    $appImage = Get-EnvValue -Path $EnvPath -Name "APP_IMAGE"
    if ([string]::IsNullOrWhiteSpace($appImage)) {
        $appImage = "otziv-app:prod-local"
    }

    Write-Host "Building backend jar locally for offline app image..."
    Push-Location $backendDir
    try {
        Invoke-External -FilePath (Join-Path $backendDir "mvnw.cmd") -Arguments @("-B", "-ntp", "clean", "package", "-DskipTests")
    } finally {
        Pop-Location
    }

    $runtimeBaseImage = ($appImage -replace ":", "-") + ":runtime-base"
    Invoke-External -FilePath "docker" -Arguments @("image", "inspect", "--format", "{{.Id}}", $appImage)
    Invoke-External -FilePath "docker" -Arguments @("tag", $appImage, $runtimeBaseImage)

    Write-Host "Building offline app image $appImage from existing runtime image..."
    $tempRoot = [System.IO.Path]::GetTempPath()
    $tempBuildDir = Join-Path $tempRoot ("otziv-app-runtime-" + [guid]::NewGuid())
    New-Item -ItemType Directory -Path $tempBuildDir | Out-Null
    try {
        Copy-Item -LiteralPath $runtimeDockerfile -Destination (Join-Path $tempBuildDir "Dockerfile")
        Copy-Item -LiteralPath (Join-Path $backendDir "target\otziv-1.jar") -Destination (Join-Path $tempBuildDir "otziv-1.jar")

        Invoke-External -FilePath "docker" -Arguments @(
            "build",
            "-f", (Join-Path $tempBuildDir "Dockerfile"),
            "--build-arg", "RUNTIME_IMAGE=$runtimeBaseImage",
            "-t", $appImage,
            $tempBuildDir
        )
    } finally {
        $resolvedTempRoot = (Resolve-Path $tempRoot).Path
        $resolvedBuildDir = if (Test-Path -LiteralPath $tempBuildDir) { (Resolve-Path $tempBuildDir).Path } else { $null }
        if ($resolvedBuildDir -and $resolvedBuildDir.StartsWith($resolvedTempRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
            Remove-Item -LiteralPath $resolvedBuildDir -Recurse -Force
        }
    }
}

$operationMutex = [System.Threading.Mutex]::new($false, 'OtzivProdLikeDatabaseOperation')
$operationLockHeld = $false
$previousLegacyMigrationEnv = [Environment]::GetEnvironmentVariable(
    'OTZIV_AUTH_LEGACY_MIGRATION_ENABLED',
    [EnvironmentVariableTarget]::Process
)
try {
try {
    $operationLockHeld = $operationMutex.WaitOne(0)
} catch [System.Threading.AbandonedMutexException] {
    $operationLockHeld = $true
}
if (-not $operationLockHeld) {
    throw 'Another prod-like smoke or production database restore is already running.'
}
if ($RestoreProdDb -and $SkipProdDbRestore) {
    throw '-RestoreProdDb and -SkipProdDbRestore are mutually exclusive.'
}
if ($RestoreProdDb) {
    Write-Host '-RestoreProdDb explicitly confirms the default fresh production DB restore.'
}

# The migration window is closed. Override an old local env file for this
# process without rewriting or exposing the user's external secret file.
$env:OTZIV_AUTH_LEGACY_MIGRATION_ENABLED = 'false'

$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = (Resolve-Path (Join-Path $scriptRoot "..\..\..")).Path
if (-not $SkipProdDbRestore -and ($VpsHost -notmatch '^[A-Za-z0-9.-]+$' -or $VpsUser -notmatch '^[A-Za-z0-9._-]+$')) {
    throw 'VpsHost/VpsUser contain unsupported characters.'
}
$envResolverPath = Join-Path $repoRoot "infrastructure\scripts\Resolve-OtzivEnvFile.ps1"
if (-not (Test-Path -LiteralPath $envResolverPath)) {
    throw "Env resolver script not found: $envResolverPath"
}
. $envResolverPath
$composePath = if ([System.IO.Path]::IsPathRooted($ComposeFile)) { $ComposeFile } else { Join-Path $repoRoot $ComposeFile }
$envPath = Resolve-OtzivEnvFile -EnvFile $EnvFile -RepoRoot $repoRoot
$localKeycloakSnapshotPath = if ([System.IO.Path]::IsPathRooted($LocalKeycloakUserSnapshot)) {
    $LocalKeycloakUserSnapshot
} else {
    Join-Path $repoRoot $LocalKeycloakUserSnapshot
}

if (-not (Test-Path -LiteralPath $composePath)) {
    throw "Compose file not found: $composePath"
}
if ($InitializeLocalKeycloakUserSnapshot -and $RotateLocalKeycloakCredentials) {
    throw '-InitializeLocalKeycloakUserSnapshot already initializes credentials; do not combine it with -RotateLocalKeycloakCredentials.'
}
if ($SkipLocalLoginCredentialSync -and $RotateLocalKeycloakCredentials) {
    throw '-SkipLocalLoginCredentialSync and -RotateLocalKeycloakCredentials are mutually exclusive.'
}
if ($SkipLocalLoginCredentialSync -and $InitializeLocalKeycloakUserSnapshot) {
    throw '-SkipLocalLoginCredentialSync cannot be used during one-time local Keycloak initialization.'
}
if ($InitializeLocalKeycloakUserSnapshot -and (Test-Path -LiteralPath $localKeycloakSnapshotPath)) {
    throw "Local Keycloak user snapshot already exists and will not be overwritten: $localKeycloakSnapshotPath"
}
if (-not $InitializeLocalKeycloakUserSnapshot -and -not (Test-Path -LiteralPath $localKeycloakSnapshotPath -PathType Leaf)) {
    throw "Local Keycloak user snapshot is missing: $localKeycloakSnapshotPath. Create it once with -InitializeLocalKeycloakUserSnapshot -LocalLoginUsername <name>."
}

Write-Host "Using env file: $envPath"
Initialize-LocalCredentialEncryptionKey -EnvPath $envPath
Initialize-LocalBotLinkSecrets -EnvPath $envPath
$localLoginConfiguration = Resolve-LocalKeycloakLoginConfiguration `
    -EnvPath $envPath `
    -UsernameOverride $LocalLoginUsername `
    -InitializeSnapshot:$InitializeLocalKeycloakUserSnapshot `
    -RotateCredentials:$RotateLocalKeycloakCredentials `
    -SkipCredentialSync:$SkipLocalLoginCredentialSync

if (-not $SkipProdDbRestore) {
    $restoreScript = Join-Path $scriptRoot "restore-prod-db-local.ps1"
    if (-not (Test-Path -LiteralPath $restoreScript)) {
        throw "Local prod DB restore script not found: $restoreScript"
    }
    if ([string]::IsNullOrWhiteSpace($VpsHost)) {
        throw "Pass -VpsHost, or use -SkipProdDbRestore to keep the existing local DB."
    }

    Write-Host "Refreshing local prod-like DB from VPS before smoke. Pass -SkipProdDbRestore to keep the existing local DB."
    $restoreArgs = @{
        EnvFile = $envPath
        ComposeFile = $composePath
        VpsHost = $VpsHost
        VpsUser = $VpsUser
        VpsPort = $VpsPort
    }
    if (-not [string]::IsNullOrWhiteSpace($SshKey)) {
        $restoreArgs["SshKey"] = $SshKey
    }
    & $restoreScript @restoreArgs
    if ($LASTEXITCODE -ne 0) {
        throw "Production DB restore failed."
    }
}

if (-not $AllowLocalMessengerSending) {
    Disable-LocalMessengerEnv
}

if (-not $UseConfiguredOutboundProxy) {
    $env:OPENAI_PROXY_ENABLED = "false"
    $env:WHATSAPP_PROXY_ENABLED = "false"
    $env:WHATSAPP_PROXY_HOST = ""
    $env:MAX_PROXY_ENABLED = "false"
    $env:MAX_PROXY_HOST = ""

    $telegramProxyHost = Get-EnvValue -Path $envPath -Name "TELEGRAM_PROXY_HOST"
    if ([string]::IsNullOrWhiteSpace($telegramProxyHost)) {
        $telegramProxyHost = Get-EnvValue -Path $envPath -Name "OPENAI_PROXY_HOST"
    }
    $telegramProxyPort = Get-EnvValue -Path $envPath -Name "TELEGRAM_PROXY_PORT"
    if ([string]::IsNullOrWhiteSpace($telegramProxyPort)) {
        $telegramProxyPort = Get-EnvValue -Path $envPath -Name "OPENAI_PROXY_PORT"
    }
    if ([string]::IsNullOrWhiteSpace($telegramProxyPort)) {
        $telegramProxyPort = "8888"
    }

    if ([string]::IsNullOrWhiteSpace($telegramProxyHost)) {
        $env:TELEGRAM_PROXY_ENABLED = "false"
        $env:TELEGRAM_PROXY_HOST = ""
        Write-Host "Local prod-like smoke uses direct outbound routes for OpenAI, Telegram, WhatsApp and MAX. Pass -UseConfiguredOutboundProxy to use proxy values from the env file."
    } else {
        $env:TELEGRAM_PROXY_ENABLED = "true"
        $env:TELEGRAM_PROXY_HOST = $telegramProxyHost
        $env:TELEGRAM_PROXY_PORT = $telegramProxyPort
        Write-Host "Local prod-like smoke uses direct outbound routes for OpenAI, WhatsApp and MAX; Telegram uses the configured local Docker proxy ${telegramProxyHost}:$telegramProxyPort because Docker does not inherit the host VPN route."
    }
}

$openAiProxyEnabled = Get-EnvValue -Path $envPath -Name "OPENAI_PROXY_ENABLED"
$openAiProxySyncLocalIp = Get-EnvValue -Path $envPath -Name "OPENAI_PROXY_SYNC_LOCAL_IP"
if (
    -not $SkipOpenAiProxyIpSync `
    -and $UseConfiguredOutboundProxy `
    -and $openAiProxyEnabled `
    -and $openAiProxyEnabled.Equals("true", [System.StringComparison]::OrdinalIgnoreCase) `
    -and -not ($openAiProxySyncLocalIp -and $openAiProxySyncLocalIp.Equals("false", [System.StringComparison]::OrdinalIgnoreCase))
) {
    $proxyIpSyncScript = Join-Path $scriptRoot "update-openai-proxy-local-ip.ps1"
    if (-not (Test-Path -LiteralPath $proxyIpSyncScript)) {
        throw "OpenAI proxy IP sync script not found: $proxyIpSyncScript"
    }

    & $proxyIpSyncScript -EnvFile $envPath
}

$composeArgs = @("compose", "-f", $composePath, "--env-file", $envPath)
$dbAdminComposeArgs = $composeArgs + @("--profile", "db-admin")
if (-not $NoDbAdmin) {
    $composeArgs += @("--profile", "db-admin")
    $phpMyAdminPort = Get-EnvValue -Path $envPath -Name "LOCAL_PHPMYADMIN_PORT"
    if ([string]::IsNullOrWhiteSpace($phpMyAdminPort)) {
        $phpMyAdminPort = "6572"
    }
    Write-Warning "Local phpMyAdmin compatibility mode is enabled: http://localhost:$phpMyAdminPort. Prefer -NoDbAdmin when database administration is not needed."
} elseif ($WithDbAdmin) {
    Write-Host "Local phpMyAdmin is disabled by -NoDbAdmin; ignoring -WithDbAdmin."
} else {
    Write-Host "Local phpMyAdmin is disabled. Omit -NoDbAdmin to start it."
}
if ($WithObservability) {
    $composeArgs += @("--profile", "observability")
    $env:OTZIV_TRACING_ENABLED = "true"
    Write-Host "Local observability is enabled: Loki, Tempo, Alloy, Prometheus, Grafana and Dozzle will be started."
} else {
    $env:OTZIV_TRACING_ENABLED = "false"
    Write-Host "Local observability is disabled. Pass -WithObservability to start Loki, Tempo, Alloy, Prometheus, Grafana and Dozzle."
}
Invoke-External -FilePath "docker" -Arguments ($composeArgs + @("config", "--quiet"))
if ($NoDbAdmin -and -not $NoUp) {
    Write-Host "Stopping any previously started local phpMyAdmin container."
    Invoke-External -FilePath "docker" -Arguments ($dbAdminComposeArgs + @("stop", "phpmyadmin"))
}

$localServices = & docker @($composeArgs + @("config", "--services"))
if ($LASTEXITCODE -ne 0) {
    throw "Failed to list compose services"
}
$blockedWhatsAppServices = @($localServices | Where-Object { $_ -in @("whatsapp_lika", "whatsapp_vika") })
if ($blockedWhatsAppServices.Count -gt 0) {
    throw "Local prod-like compose must not start WhatsApp services: $($blockedWhatsAppServices -join ', ')"
}

if ($OfflineAppBuild) {
    Invoke-OfflineAppBuild -RepoRoot $repoRoot -EnvPath $envPath
}

$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
try {
    if (-not $NoUp) {
        Invoke-External -FilePath "docker" -Arguments ($composeArgs + @("up", "-d", "mysql"))
        Wait-ComposeServiceHealthy -ComposeArguments $composeArgs -Service "mysql"
        Disable-LocalExternalMessaging -ComposeArguments $composeArgs -EnvPath $envPath

        if (-not $NoBuild -and -not $OfflineAppBuild) {
            # Build the application image before mounting legacy volumes. This
            # lets the ownership repair complete before Compose waits on the
            # non-root application's health check.
            $buildResult = Invoke-DockerComposeUp -ComposeArguments $composeArgs -UpArguments @("build")
            $canFallback = $buildResult.ExitCode -ne 0 -and -not $NoOfflineFallback -and (Test-RegistryBuildFailure -Output $buildResult.Output)
            if ($buildResult.ExitCode -ne 0 -and -not $canFallback) {
                throw "Command failed: docker $($composeArgs + @('build') -join ' ')"
            }
            if ($canFallback) {
                Write-Warning "Docker registry is unavailable. Falling back to offline backend image rebuild from local Maven jar."
                Invoke-OfflineAppBuild -RepoRoot $repoRoot -EnvPath $envPath
            }
        }

        # Existing prod-like volumes may have been initialized by the former
        # root runtime. The hardened image runs as 10001 and drops every
        # capability, so repair ownership before starting the application with
        # a short-lived container that receives only CAP_CHOWN.
        Write-Host "Migrating local application volume ownership to UID/GID 10001."
        Invoke-External -FilePath "docker" -Arguments ($composeArgs + @(
            "run", "--rm", "--no-deps", "--cap-add", "CHOWN", "--user", "0",
            "--entrypoint", "chown", "app", "-R", "10001:10001",
            "/app/logs", "/app/backup", "/app/mobile-releases", "/app/sent-hashes"
        ))

        $upArgs = @("up", "-d", "--remove-orphans")
        $upResult = Invoke-DockerComposeUpWithNetworkRepair -ComposeArguments $composeArgs -UpArguments $upArgs
        if ($upResult.ExitCode -ne 0) {
            throw "Command failed after image preparation: docker $($composeArgs + $upArgs -join ' ')"
        }
    }

    if (-not $NoUp) {
        Write-Host "Restarting nginx so its upstream resolves the currently running backend container."
        Invoke-External -FilePath "docker" -Arguments ($composeArgs + @("restart", "nginx"))
    }

    Wait-HttpOk -Url "$BaseUrl/actuator/health" -Name "backend health" -Deadline $deadline
    if (-not $NoUp) {
        Write-Host "Checking external-review DNS reachability and data-network isolation."
        Invoke-External -FilePath "docker" -Arguments ($composeArgs + @(
            "exec", "-T", "app", "getent", "hosts", "external-review-worker"
        ))
        Invoke-External -FilePath "docker" -Arguments ($composeArgs + @(
            "exec", "-T", "external-review-worker", "node", "-e",
            "require('node:dns').lookup('mysql', error => process.exit(error ? 0 : 1))"
        ))
    }
    Disable-LocalExternalMessaging -ComposeArguments $composeArgs -EnvPath $envPath
    if (-not $NoUp) {
        Write-Host "Restarting backend so local safety settings are loaded from the refreshed DB."
        Invoke-External -FilePath "docker" -Arguments ($composeArgs + @("up", "-d", "--force-recreate", "--no-deps", "app"))
        Write-Host "Restarting nginx so its upstream resolves the recreated backend container."
        Invoke-External -FilePath "docker" -Arguments ($composeArgs + @("up", "-d", "--force-recreate", "--no-deps", "nginx"))
        Wait-HttpOk -Url "$BaseUrl/actuator/health" -Name "backend health after local safety reload" -Deadline $deadline
    }
    Wait-HttpOk -Url "$BaseUrl/keycloak/realms/otziv/.well-known/openid-configuration" -Name "Keycloak realm" -Deadline $deadline
    Sync-LocalKeycloakLoginCredential `
        -RootUrl $BaseUrl `
        -EnvPath $envPath `
        -ComposeArguments $composeArgs `
        -SnapshotPath $localKeycloakSnapshotPath `
        -Username ([string]$localLoginConfiguration.Username) `
        -Password ([string]$localLoginConfiguration.Password) `
        -InitializeSnapshot:$InitializeLocalKeycloakUserSnapshot `
        -RotateCredentials:($RotateLocalKeycloakCredentials -or [bool]$localLoginConfiguration.ResumeCredentialRotation) `
        -SkipCredentialSync:$SkipLocalLoginCredentialSync
    Update-KeycloakFrontendLoopbackRedirects -ComposeArguments $composeArgs -EnvPath $envPath -BaseUrl $BaseUrl
    Wait-HttpOk -Url "$BaseUrl/" -Name "frontend" -Deadline $deadline
    Invoke-PublicFrontendSmoke -BaseUrl $BaseUrl
    Invoke-PublicCapabilityAuthorizationSmoke -BaseUrl $BaseUrl
    Assert-LegacyUserMigrationDisabled -BaseUrl $BaseUrl
    Assert-LegacyReviewCapabilityNotLogged -BaseUrl $BaseUrl -ComposeArguments $composeArgs
    Invoke-TbankPaymentConfigSmoke -BaseUrl $BaseUrl -EnvPath $envPath
    Invoke-ContractorPaymentShadowSmoke `
        -EnvPath $envPath `
        -ComposeArguments $composeArgs
    if (-not $SkipWorkloadShadowSmoke) {
        Invoke-WorkloadShadowSmoke `
            -RootUrl $BaseUrl `
            -EnvPath $envPath `
            -ComposeArguments $composeArgs
    }
    Assert-ScheduledMessageReconciliationHealthy -ComposeArguments $composeArgs
    if ($WithReputationAiSmoke) {
        Invoke-ReputationAiSmoke `
            -RootUrl $BaseUrl `
            -EnvPath $envPath `
            -CompanyId $ReputationAiCompanyId `
            -SkipRouteCheck:$SkipReputationAiOpenAiRouteCheck
    }

    Invoke-External -FilePath "docker" -Arguments ($composeArgs + @("ps"))
    Write-Host "Local prod-like smoke passed: $BaseUrl"
} catch {
    if (-not $NoLogs) {
        Write-Host ""
        Write-Host "Last container logs:"
        & docker @($composeArgs + @("logs", "--tail=200", "nginx", "app", "keycloak", "external-review-worker"))
    }

    throw
}
} finally {
    if ($null -eq $previousLegacyMigrationEnv) {
        Remove-Item Env:OTZIV_AUTH_LEGACY_MIGRATION_ENABLED -ErrorAction SilentlyContinue
    } else {
        $env:OTZIV_AUTH_LEGACY_MIGRATION_ENABLED = $previousLegacyMigrationEnv
    }
    if ($operationLockHeld) {
        $operationMutex.ReleaseMutex()
    }
    $operationMutex.Dispose()
}
