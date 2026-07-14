param(
    [string]$LogPath = "data/app-logs"
)

$ErrorActionPreference = "Stop"

$resolvedLogPath = Resolve-Path -LiteralPath $LogPath -ErrorAction Stop
$pattern = 'Worker cellular access: user=(?<user>[^,]+), scope=(?<scope>[^,]+), mode=(?<mode>[^,]+), result=(?<result>[^,]+), reason=(?<reason>[^,]+), mobileDevice=(?<mobile>true|false), cidrMatch=(?<cidr>true|false), ipPrefix=(?<prefix>\S+)'

$events = Get-ChildItem -LiteralPath $resolvedLogPath -File -Recurse |
    Select-String -Pattern 'Worker cellular access:' |
    ForEach-Object {
        if ($_.Line -match $pattern) {
            [pscustomobject]@{
                User = $Matches.user
                Scope = $Matches.scope
                Mode = $Matches.mode
                MobileDevice = $Matches.mobile -eq 'true'
                CidrMatch = $Matches.cidr -eq 'true'
                Prefix = $Matches.prefix
            }
        }
    }

$mobileCandidates = @($events | Where-Object {
    $_.MobileDevice -and $_.Prefix -notin @('unknown', 'invalid')
})

if ($mobileCandidates.Count -eq 0) {
    Write-Output "Мобильные IP-префиксы в журнале пока не найдены."
    exit 0
}

$summary = $mobileCandidates |
    Group-Object -Property Prefix |
    ForEach-Object {
        $items = @($_.Group)
        [pscustomobject]@{
            Prefix = $_.Name
            Requests = $_.Count
            Workers = @($items.User | Sort-Object -Unique).Count
            Scopes = ($items.Scope | Sort-Object -Unique) -join ', '
            AlreadyAllowed = @($items | Where-Object CidrMatch).Count -gt 0
        }
    } |
    Sort-Object -Property @{ Expression = 'Requests'; Descending = $true }, Prefix

$summary | Format-Table -AutoSize

Write-Output ""
Write-Output "Кандидаты для проверки (не добавляйте автоматически без теста Wi-Fi/мобильной сети):"
($summary | Where-Object { -not $_.AlreadyAllowed }).Prefix
