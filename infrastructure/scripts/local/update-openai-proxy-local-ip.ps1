param(
    [string]$EnvFile = ".env.prod-local",
    [string]$ProxySshTarget = $env:OPENAI_PROXY_SSH_TARGET,
    [string]$PublicIpLookupUrl = "https://api.ipify.org",
    [string]$RuleComment = "otziv-local-dev",
    [switch]$SkipProxyRouteIpDetection,
    [switch]$NoProxyTest
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Get-EnvValue {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Name
    )

    if (-not (Test-Path -LiteralPath $Path)) {
        return $null
    }

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
            if (($value.StartsWith('"') -and $value.EndsWith('"')) -or ($value.StartsWith("'") -and $value.EndsWith("'"))) {
                $value = $value.Substring(1, $value.Length - 2)
            }

            if (-not [string]::IsNullOrWhiteSpace($value)) {
                $found = $value
            }
        }
    }

    return $found
}

function Get-Setting {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [string]$DefaultValue = $null
    )

    $fromProcess = [Environment]::GetEnvironmentVariable($Name)
    if (-not [string]::IsNullOrWhiteSpace($fromProcess)) {
        return $fromProcess
    }

    $fromFile = Get-EnvValue -Path $envPath -Name $Name
    if (-not [string]::IsNullOrWhiteSpace($fromFile)) {
        return $fromFile
    }

    return $DefaultValue
}

function Assert-IPv4 {
    param([Parameter(Mandatory = $true)][string]$Value)

    $address = $null
    if (-not [System.Net.IPAddress]::TryParse($Value, [ref]$address)) {
        throw "Public IP lookup returned an invalid IP address: $Value"
    }

    if ($address.AddressFamily -ne [System.Net.Sockets.AddressFamily]::InterNetwork) {
        throw "Public IP lookup returned a non-IPv4 address: $Value"
    }
}

function Get-ProxyRouteIp {
    param(
        [Parameter(Mandatory = $true)][string]$ProxyHost,
        [Parameter(Mandatory = $true)][string]$ProxyPort,
        [Parameter(Mandatory = $true)][string]$ProxySshTarget
    )

    $captureCommand = "timeout 12 tcpdump -l -n -c 1 -i any 'tcp dst port $ProxyPort and tcp[tcpflags] & tcp-syn != 0' 2>/dev/null"
    $captureJob = Start-Job -ScriptBlock {
        param(
            [Parameter(Mandatory = $true)][string]$Target,
            [Parameter(Mandatory = $true)][string]$Command
        )

        & ssh $Target $Command 2>$null
    } -ArgumentList $ProxySshTarget, $captureCommand

    try {
        Start-Sleep -Seconds 3
        for ($attempt = 1; $attempt -le 5; $attempt++) {
            $client = [System.Net.Sockets.TcpClient]::new()
            try {
                $async = $client.BeginConnect($ProxyHost, [int]$ProxyPort, $null, $null)
                if ($async.AsyncWaitHandle.WaitOne(1500)) {
                    try {
                        $client.EndConnect($async)
                    } catch {
                        # The firewall may drop the probe before the TCP handshake completes; tcpdump still sees the SYN.
                    }
                }
            } finally {
                $client.Dispose()
            }

            if ($captureJob.State -ne "Running") {
                break
            }
            Start-Sleep -Milliseconds 700
        }

        $output = @()
        if (Wait-Job -Job $captureJob -Timeout 10) {
            $output = Receive-Job -Job $captureJob
        }
        foreach ($line in $output) {
            $text = $line.ToString()
            if ($text -match "IP\s+(\d{1,3}(?:\.\d{1,3}){3})\.\d+\s+>") {
                $sourceIp = $Matches[1]
                Assert-IPv4 -Value $sourceIp
                return $sourceIp
            }
        }
    } catch {
        Write-Warning "Could not detect route-specific proxy IP: $($_.Exception.Message)"
    } finally {
        if ($captureJob.State -eq "Running") {
            Stop-Job -Job $captureJob | Out-Null
        }
        Remove-Job -Job $captureJob -Force | Out-Null
    }

    return $null
}

function ConvertTo-CurlConfigValue {
    param([AllowNull()][string]$Value)

    $escaped = if ($null -eq $Value) { "" } else { $Value.Replace("\", "\\").Replace('"', '\"') }
    return '"' + $escaped + '"'
}

$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = (Resolve-Path (Join-Path $scriptRoot "..\..\..")).Path
$envPath = if ([System.IO.Path]::IsPathRooted($EnvFile)) { $EnvFile } else { Join-Path $repoRoot $EnvFile }

$proxyHost = Get-Setting -Name "OPENAI_PROXY_HOST"
$proxyPort = Get-Setting -Name "OPENAI_PROXY_PORT" -DefaultValue "3128"
$proxyUsername = Get-Setting -Name "OPENAI_PROXY_USERNAME"
$proxyPassword = Get-Setting -Name "OPENAI_PROXY_PASSWORD"
$openAiApiKey = Get-Setting -Name "OPENAI_API_KEY"

if ([string]::IsNullOrWhiteSpace($proxyHost)) {
    throw "OPENAI_PROXY_HOST is not set in environment or $envPath"
}

if ([string]::IsNullOrWhiteSpace($ProxySshTarget)) {
    $ProxySshTarget = "root@$proxyHost"
}

$currentIp = (Invoke-RestMethod -Uri $PublicIpLookupUrl -TimeoutSec 20).ToString().Trim()
Assert-IPv4 -Value $currentIp

Write-Host "Current local public IP: $currentIp"

$rules = [System.Collections.Generic.List[object]]::new()
$rules.Add([pscustomobject]@{ Ip = $currentIp; Comment = $RuleComment })

if (-not $SkipProxyRouteIpDetection) {
    $routeIp = Get-ProxyRouteIp -ProxyHost $proxyHost -ProxyPort $proxyPort -ProxySshTarget $ProxySshTarget
    if (-not [string]::IsNullOrWhiteSpace($routeIp) -and $routeIp -ne $currentIp) {
        Write-Host "Route-specific public IP for ${proxyHost}:$proxyPort is $routeIp"
        $rules.Add([pscustomobject]@{ Ip = $routeIp; Comment = "$RuleComment-route" })
    }
}

$remoteScript = @'
set -euo pipefail

current_ip="$1"
proxy_port="$2"
rule_comment="$3"

status="$(ufw status numbered)"
existing_rules="$(printf '%s\n' "$status" | awk -v comment="$rule_comment" '$0 ~ ("# " comment "$") { line=$0; sub(/^\[ */, "", line); sub(/\].*/, "", line); gsub(/ /, "", line); print line }' | sort -rn || true)"
existing_count="$(printf '%s\n' "$existing_rules" | awk 'NF { count++ } END { print count + 0 }')"
current_count="$(printf '%s\n' "$status" | awk -v comment="$rule_comment" -v current_ip="$current_ip" -v proxy_port="$proxy_port" '$0 ~ ("# " comment "$") && index($0, current_ip) && index($0, proxy_port "/tcp") { count++ } END { print count + 0 }')"

if [ "$existing_count" = "1" ] && [ "$current_count" = "1" ]; then
  echo "UFW rule '$rule_comment' is already current for $current_ip."
else
  if [ -n "$existing_rules" ]; then
    for rule_number in $existing_rules; do
      ufw --force delete "$rule_number" >/dev/null
    done
  fi

  ufw allow from "$current_ip" to any port "$proxy_port" proto tcp comment "$rule_comment" >/dev/null
  ufw reload >/dev/null
fi

squid_conf="/etc/squid/squid.conf"
if [ -f "$squid_conf" ]; then
  escaped_ip="$(printf '%s\n' "$current_ip" | sed 's/[.[\*^$()+?{}|]/\\&/g')"
  if ! grep -Eq "^[[:space:]]*acl[[:space:]]+otziv_clients[[:space:]]+src[[:space:]]+$escaped_ip/32([[:space:]]|$)" "$squid_conf"; then
    tmp_conf="$(mktemp)"
    acl_line="acl otziv_clients src $current_ip/32 # $rule_comment"
    awk -v acl_line="$acl_line" '
      BEGIN { inserted = 0 }
      /^[[:space:]]*http_access[[:space:]]+allow[[:space:]]+otziv_clients([[:space:]]|$)/ && inserted == 0 {
        print acl_line
        inserted = 1
      }
      { print }
      END {
        if (inserted == 0) {
          print acl_line
        }
      }
    ' "$squid_conf" > "$tmp_conf"
    cp "$squid_conf" "$squid_conf.bak.$(date +%Y%m%d%H%M%S)"
    cat "$tmp_conf" > "$squid_conf"
    rm -f "$tmp_conf"
    squid -k parse >/dev/null
    systemctl reload squid >/dev/null || systemctl restart squid >/dev/null
    echo "Squid ACL 'otziv_clients' updated for $current_ip."
  else
    echo "Squid ACL 'otziv_clients' is already current for $current_ip."
  fi
fi
ufw status verbose
'@

$remoteScript = $remoteScript -replace "`r", ""
$remoteScriptName = "otziv-openai-proxy-ip-" + [guid]::NewGuid().ToString("N") + ".sh"
$localScriptPath = Join-Path ([System.IO.Path]::GetTempPath()) $remoteScriptName
$remoteScriptPath = "/tmp/$remoteScriptName"

try {
    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($localScriptPath, $remoteScript, $utf8NoBom)

    & scp $localScriptPath "${ProxySshTarget}:$remoteScriptPath" | Out-Host
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to copy UFW update script to $ProxySshTarget"
    }

    foreach ($rule in $rules) {
        Write-Host "Updating UFW rule '$($rule.Comment)' on $ProxySshTarget for proxy port $proxyPort..."
        & ssh $ProxySshTarget "bash" $remoteScriptPath $rule.Ip $proxyPort $rule.Comment | Out-Host
        if ($LASTEXITCODE -ne 0) {
            throw "Failed to update UFW rule '$($rule.Comment)' on $ProxySshTarget"
        }
    }
} finally {
    if (Test-Path -LiteralPath $localScriptPath) {
        Remove-Item -LiteralPath $localScriptPath -Force
    }

    & ssh $ProxySshTarget "rm" "-f" $remoteScriptPath 2>$null | Out-Null
}

if (-not $NoProxyTest) {
    if ([string]::IsNullOrWhiteSpace($proxyUsername) -or [string]::IsNullOrWhiteSpace($proxyPassword)) {
        $proxyUri = "http://${proxyHost}:${proxyPort}"
    } else {
        $encodedUsername = [System.Uri]::EscapeDataString($proxyUsername)
        $encodedPassword = [System.Uri]::EscapeDataString($proxyPassword)
        $proxyUri = "http://${encodedUsername}:${encodedPassword}@${proxyHost}:${proxyPort}"
    }

    Write-Host "Testing proxy route to OpenAI..."
    $curlConfigPath = Join-Path ([System.IO.Path]::GetTempPath()) ("otziv-openai-proxy-curl-" + [guid]::NewGuid().ToString("N") + ".conf")
    try {
        $curlConfigLines = [System.Collections.Generic.List[string]]::new()
        $curlConfigLines.Add("proxy = $(ConvertTo-CurlConfigValue -Value $proxyUri)")
        $curlConfigLines.Add("connect-timeout = 20")
        $curlConfigLines.Add("max-time = 40")
        if (-not [string]::IsNullOrWhiteSpace($openAiApiKey)) {
            $curlConfigLines.Add("header = $(ConvertTo-CurlConfigValue -Value ("Authorization: Bearer " + $openAiApiKey))")
        }
        [System.IO.File]::WriteAllLines($curlConfigPath, $curlConfigLines, [System.Text.UTF8Encoding]::new($false))

        if ([string]::IsNullOrWhiteSpace($openAiApiKey)) {
            & curl.exe --config $curlConfigPath -I "https://api.openai.com/v1/models" | Out-Host
            if ($LASTEXITCODE -ne 0) {
                throw "Proxy connectivity test failed. UFW/Squid rule was updated, but the proxy route to OpenAI is still unreachable."
            }
        } else {
            $httpCode = (& curl.exe -sS -o NUL -w "%{http_code}" --config $curlConfigPath "https://api.openai.com/v1/models")
            if ($LASTEXITCODE -ne 0) {
                throw "Proxy connectivity test failed with curl exit code $LASTEXITCODE. UFW/Squid rule was updated, but the proxy route to OpenAI is still unreachable."
            }
            if (-not ($httpCode -match "^2\d\d$")) {
                throw "Proxy connectivity test reached OpenAI but returned HTTP $httpCode."
            }
            Write-Host "Proxy route to OpenAI is OK (HTTP $httpCode)."
        }
    } finally {
        if (Test-Path -LiteralPath $curlConfigPath) {
            Remove-Item -LiteralPath $curlConfigPath -Force
        }
    }
}

Write-Host "OpenAI proxy local IP rule is up to date."
