# Dot-source this file, then pass the returned commit to every range-based gate.
# This helper never fetches, invents an empty-tree base, or changes Git state.
function Resolve-CiBaseRevision {
    [CmdletBinding()]
    param(
        [AllowEmptyString()][string]$BaseRevision = '',
        [AllowEmptyString()][string]$DefaultBranch = '',
        [string]$RepositoryRoot = '.'
    )

    $root = (Resolve-Path -LiteralPath $RepositoryRoot -ErrorAction Stop).Path
    $git = (Get-Command git -CommandType Application -ErrorAction Stop | Select-Object -First 1).Source
    function Invoke-BaseGit {
        param([string[]]$Arguments)
        $start = [Diagnostics.ProcessStartInfo]::new()
        $start.FileName = $git
        $start.WorkingDirectory = $root
        $start.UseShellExecute = $false
        $start.RedirectStandardOutput = $true
        $start.RedirectStandardError = $true
        foreach ($argument in (@('-C', $root) + $Arguments)) { $start.ArgumentList.Add($argument) }
        # Resolve the named checkout even if a caller maintains another index.
        foreach ($name in @('GIT_DIR', 'GIT_WORK_TREE', 'GIT_INDEX_FILE')) { $null = $start.Environment.Remove($name) }
        $process = [Diagnostics.Process]::Start($start)
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        $process.WaitForExit()
        $result = [pscustomobject]@{ ExitCode = $process.ExitCode; Output = $stdout.GetAwaiter().GetResult().Trim() }
        $null = $stderr.GetAwaiter().GetResult()
        $process.Dispose()
        # ProcessStartInfo deliberately avoids leaking a failed probe into the
        # caller's $LASTEXITCODE and therefore the hosted pwsh step exit code.
        return $result
    }

    $requested = $BaseRevision.Trim()
    if ([string]::IsNullOrWhiteSpace($requested) -or $requested -match '^(?:0{40}|0{64})$') {
        if (-not [string]::IsNullOrWhiteSpace($DefaultBranch)) {
            $branch = $DefaultBranch.Trim()
            $validBranch = Invoke-BaseGit -Arguments @('check-ref-format', "refs/heads/$branch")
            if ($validBranch.ExitCode -ne 0) { throw 'CI default branch is not a valid Git branch name.' }
            $requested = "refs/remotes/origin/$branch"
        } else {
            $symbolic = Invoke-BaseGit -Arguments @('symbolic-ref', '--quiet', 'refs/remotes/origin/HEAD')
            if ($symbolic.ExitCode -ne 0 -or $symbolic.Output -notmatch '^refs/remotes/origin/.+') {
                throw 'CI base is absent or zero and no verified origin default branch is available. Supply the repository default branch and fetch its history.'
            }
            $requested = $symbolic.Output
        }
    }

    $resolved = Invoke-BaseGit -Arguments @('rev-parse', '--verify', '--end-of-options', "$requested^{commit}")
    if ($resolved.ExitCode -ne 0 -or $resolved.Output -notmatch '^(?:[0-9a-f]{40}|[0-9a-f]{64})$') {
        throw 'CI base does not resolve to an available commit. Fetch the supplied base/default-branch history; range enforcement must not be skipped.'
    }
    return $resolved.Output
}
