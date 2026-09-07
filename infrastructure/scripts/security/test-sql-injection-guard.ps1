$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path
$guard = Join-Path $PSScriptRoot 'sql-injection-guard.ps1'
$relativeSource = 'backend/src/main/java/com/hunt/otziv/performers/maintenance/PerformerLegacyMaintenance.java'
$source = [IO.File]::ReadAllText((Join-Path $repoRoot $relativeSource))
$git = (Get-Command git -CommandType Application | Select-Object -First 1).Source
$pwsh = (Get-Command pwsh -CommandType Application | Select-Object -First 1).Source
$fixtureRoot = Join-Path ([IO.Path]::GetTempPath()) ('otziv-sql-guard-' + [Guid]::NewGuid().ToString('N'))
$null = New-Item -ItemType Directory -Path $fixtureRoot
$fixtureRoot = (Resolve-Path -LiteralPath $fixtureRoot).Path
$fixtureSource = Join-Path $fixtureRoot $relativeSource
$fixtureGuard = Join-Path $fixtureRoot 'infrastructure/scripts/security/sql-injection-guard.ps1'
$utf8 = [Text.UTF8Encoding]::new($false)
$checks = 0

function Invoke-FixtureProcess {
    param([string]$Executable, [string[]]$Arguments)
    $info = [Diagnostics.ProcessStartInfo]::new()
    $info.FileName = $Executable
    $info.WorkingDirectory = $fixtureRoot
    $info.UseShellExecute = $false
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    foreach ($argument in $Arguments) { $info.ArgumentList.Add($argument) }
    # Never inherit a caller's publication index or redirect its Git worktree.
    foreach ($name in @('GIT_INDEX_FILE', 'GIT_DIR', 'GIT_WORK_TREE')) { $null = $info.Environment.Remove($name) }
    $process = [Diagnostics.Process]::Start($info)
    $stdout = $process.StandardOutput.ReadToEndAsync()
    $stderr = $process.StandardError.ReadToEndAsync()
    $process.WaitForExit()
    $result = [pscustomobject]@{ ExitCode = $process.ExitCode; Output = $stdout.GetAwaiter().GetResult() + $stderr.GetAwaiter().GetResult() }
    $process.Dispose()
    return $result
}

function Invoke-FixtureGit {
    param([string[]]$Arguments)
    $result = Invoke-FixtureProcess -Executable $git -Arguments (@('-c', "safe.directory=$fixtureRoot", '-C', $fixtureRoot) + $Arguments)
    if ($result.ExitCode -ne 0) { throw "Fixture git failed: $($result.Output)" }
}

function Assert-Guard {
    param([string]$Mode, [bool]$ShouldPass, [string]$Scenario)
    $result = Invoke-FixtureProcess -Executable $pwsh -Arguments @('-NoProfile', '-File', $fixtureGuard, '-Mode', $Mode)
    if (($result.ExitCode -eq 0) -ne $ShouldPass) { throw "Guard scenario failed: $Scenario`n$($result.Output)" }
    if (-not $ShouldPass -and $result.Output -notmatch 'SQL004') { throw "Expected SQL004 finding: $Scenario" }
    $script:checks++
}

try {
    $null = New-Item -ItemType Directory -Force -Path (Split-Path $fixtureSource), (Split-Path $fixtureGuard)
    Copy-Item -LiteralPath $guard -Destination $fixtureGuard
    [IO.File]::WriteAllText($fixtureSource, $source, $utf8)
    Invoke-FixtureGit -Arguments @('init', '--quiet')
    Invoke-FixtureGit -Arguments @('config', 'core.autocrlf', 'false')
    Invoke-FixtureGit -Arguments @('add', '--', $relativeSource)
    Assert-Guard -Mode dir -ShouldPass $true -Scenario 'Reviewed original source is accepted'
    Assert-Guard -Mode staged -ShouldPass $true -Scenario 'Reviewed source in index is accepted'

    $unsafe = $source.Replace('pk=offer?"offer_id":"assignment_id"', 'pk=kind')
    if ($unsafe -ceq $source) { throw 'Counterfactual must change identifier provenance.' }
    [IO.File]::WriteAllText($fixtureSource, $unsafe, $utf8)
    Assert-Guard -Mode staged -ShouldPass $true -Scenario 'Safe index is independent of malicious working-tree edit'
    Assert-Guard -Mode dir -ShouldPass $false -Scenario 'External identifier input invalidates the full-source review'
    Invoke-FixtureGit -Arguments @('add', '--', $relativeSource)
    [IO.File]::WriteAllText($fixtureSource, $source, $utf8)
    Assert-Guard -Mode staged -ShouldPass $false -Scenario 'Malicious index cannot borrow the safe working-tree digest'

    $lf = $source.Replace("`r`n", "`n")
    [IO.File]::WriteAllText($fixtureSource, $lf, $utf8)
    Invoke-FixtureGit -Arguments @('add', '--', $relativeSource)
    Assert-Guard -Mode staged -ShouldPass $true -Scenario 'LF index preserves the reviewed source'
    [IO.File]::WriteAllText($fixtureSource, $lf.Replace("`n", "`r`n"), $utf8)
    Invoke-FixtureGit -Arguments @('add', '--', $relativeSource)
    Assert-Guard -Mode staged -ShouldPass $true -Scenario 'CRLF index preserves the reviewed source'

    $otherQuery = $source + "`n" + 'var unrelated = "SELECT id FROM anything ORDER BY " + requestInput;' + "`n"
    [IO.File]::WriteAllText($fixtureSource, $otherQuery, $utf8)
    Invoke-FixtureGit -Arguments @('add', '--', $relativeSource)
    Assert-Guard -Mode staged -ShouldPass $false -Scenario 'Another dynamic query in the reviewed file is rejected'
    [IO.File]::WriteAllText($fixtureSource, $source, $utf8)
    $foreign = Join-Path (Split-Path $fixtureSource) 'ForeignMaintenance.java'
    [IO.File]::WriteAllText($foreign, $source, $utf8)
    Invoke-FixtureGit -Arguments @('add', '--', 'backend/src/main/java')
    Assert-Guard -Mode staged -ShouldPass $false -Scenario 'Identical source at another path is not exempt'

    # Isolate the real rule predicate to prove this review cannot exempt SQL001-3.
    $tokens = $null; $errors = $null
    $ast = [Management.Automation.Language.Parser]::ParseFile($guard, [ref]$tokens, [ref]$errors)
    if ($errors.Count) { throw 'Guard must parse.' }
    foreach ($name in @('allowList', 'reviewedSql004')) {
        $assignment = $ast.Find({ param($node)
            $node -is [Management.Automation.Language.AssignmentStatementAst] -and $node.Left.Extent.Text -eq ('$' + $name)
        }, $true)
        . ([scriptblock]::Create($assignment.Extent.Text))
    }
    $predicate = $ast.Find({ param($node)
        $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Test-AllowListed'
    }, $true)
    . ([scriptblock]::Create($predicate.Extent.Text))
    foreach ($rule in @('SQL001', 'SQL002', 'SQL003')) {
        if (Test-AllowListed -RelativePath $reviewedSql004.Path -Line $reviewedSql004.Lines[0] -SourceSha256 $reviewedSql004.SourceSha256 -RuleId $rule) {
            throw "SQL004 review must not exempt $rule"
        }
        $checks++
    }
    Write-Host "SQL injection guard: $checks causal checks passed. Original repository/index untouched."
} finally {
    $resolved = (Resolve-Path -LiteralPath $fixtureRoot).Path
    $tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
    if ($resolved -ne $fixtureRoot -or -not $resolved.StartsWith($tempRoot, [StringComparison]::OrdinalIgnoreCase) -or
            -not ([IO.Path]::GetFileName($resolved)).StartsWith('otziv-sql-guard-', [StringComparison]::Ordinal)) {
        throw 'Refusing cleanup outside the exact owned temporary fixture.'
    }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
