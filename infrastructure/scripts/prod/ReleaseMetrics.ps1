function Set-OtzivReleaseStage {
    param([string]$Path, [string]$Stage, [ValidateSet('running','success','failure')][string]$Result = 'running')
    if (-not $Path) { return }
    $now = [DateTimeOffset]::UtcNow
    $value = if (Test-Path -LiteralPath $Path) {
        Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json -AsHashtable
    } else { @{ schema = 'otziv-release-timing-v1'; startedAt = $now.ToString('o'); stages = @() } }
    if ($value.stages.Count -gt 0 -and -not $value.stages[-1].ContainsKey('endedAt')) {
        $last = $value.stages[-1]
        $last.endedAt = $now.ToString('o')
        $last.seconds = [Math]::Round(($now - [DateTimeOffset]$last.startedAt).TotalSeconds, 3)
        $last.result = if ($Result -eq 'failure') { 'failure' } else { 'success' }
    }
    if ($Result -eq 'running') { $value.stages += @{ name = $Stage; startedAt = $now.ToString('o') } }
    else {
        $value.result = $Result; $value.endedAt = $now.ToString('o')
        $value.totalSeconds = [Math]::Round(($now - [DateTimeOffset]$value.startedAt).TotalSeconds, 3)
    }
    $value | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $Path -Encoding utf8
}
