[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$ApkPath,
    [Parameter(Mandatory = $true)][string]$ReleaseJsonPath,
    [switch]$VerifyOnly,
    [switch]$StageOnly,
    [string]$VpsHost = '95.213.248.152',
    [string]$VpsUser = 'hunt',
    [ValidateRange(1, 65535)][int]$VpsPort = 22022,
    [string]$VpsPath = '/docker',
    [string]$SshKey = '',
    [string]$SshKnownHostsFile = ''
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if ($VerifyOnly -and $StageOnly) {
    throw 'Choose either standalone APK verification or VPS staging.'
}

# A replacement signer needs a separate channel. The old mobile release route
# remains untouched so existing installations cannot receive an incompatible APK.
$expectedSigner = '00E249AB3D6A15F051C70F66B726047FCC534FC358E409A2099B4C4C461F9218'
$publicOrigin = 'https://o-ogo.ru'

function ConvertTo-ShellSingleQuoted {
    param([Parameter(Mandatory = $true)][string]$Value)
    $single = [string][char]39
    $double = [string][char]34
    return $single + $Value.Replace($single, $single + $double + $single + $double + $single) + $single
}

function Invoke-RemotePython {
    param([string]$Program, [object]$Packet, [string[]]$SshArguments)
    $start = [Diagnostics.ProcessStartInfo]::new()
    $start.FileName = (Get-Command ssh -ErrorAction Stop).Source
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardInput = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    foreach ($argument in $SshArguments) { $start.ArgumentList.Add($argument) }
    $start.ArgumentList.Add("$VpsUser@$VpsHost")
    $start.ArgumentList.Add('sudo -n python3 -B -c ' + (ConvertTo-ShellSingleQuoted $Program))
    $process = [Diagnostics.Process]::Start($start)
    try {
        $process.StandardInput.WriteLine(($Packet | ConvertTo-Json -Compress -Depth 5))
        $process.StandardInput.Close()
        if (-not $process.WaitForExit(240000)) {
            $process.Kill($true)
            throw 'Standalone APK server publication timed out.'
        }
        $stdout = $process.StandardOutput.ReadToEnd()
        $stderr = $process.StandardError.ReadToEnd()
        if ($process.ExitCode -ne 0) {
            throw "Standalone APK server publication failed: $($stderr.Trim())"
        }
        return $stdout | ConvertFrom-Json
    } finally {
        $process.Dispose()
    }
}

function Assert-PublicReleaseMetadata {
    param([object]$Expected)
    $response = Invoke-WebRequest -Uri "$publicOrigin/android-new/release.json" -Headers @{ 'Cache-Control' = 'no-cache' } -TimeoutSec 30
    if ($response.StatusCode -ne 200) {
        throw 'The committed standalone Android release metadata is not public yet.'
    }
    $published = $response.Content | ConvertFrom-Json
    if ($published.enabled -ne $Expected.enabled -or
            $published.versionCode -ne $Expected.versionCode -or
            $published.versionName -cne $Expected.versionName -or
            $published.minSupportedVersionCode -ne $Expected.minSupportedVersionCode -or
            $published.required -ne $Expected.required -or
            $published.notes -cne $Expected.notes -or
            $published.publishedAt -cne $Expected.publishedAt -or
            $published.downloadUrl -cne $Expected.downloadUrl -or
            $published.sha256 -ine $Expected.sha256 -or
            $published.fileSize -ne $Expected.fileSize) {
        throw 'The public standalone Android release metadata differs from verified main.'
    }
}

function Assert-PublicApk {
    param([string]$Url, [string]$ExpectedSha256, [long]$ExpectedSize)
    $client = [Net.Http.HttpClient]::new()
    $client.Timeout = [TimeSpan]::FromMinutes(5)
    try {
        $response = $client.GetAsync($Url, [Net.Http.HttpCompletionOption]::ResponseHeadersRead).GetAwaiter().GetResult()
        try {
            if (-not $response.IsSuccessStatusCode -or $response.RequestMessage.RequestUri.AbsoluteUri -cne $Url) {
                throw 'The standalone APK public URL did not return its exact file.'
            }
            if ($null -ne $response.Content.Headers.ContentLength -and $response.Content.Headers.ContentLength -ne $ExpectedSize) {
                throw 'The public standalone APK length differs from the verified file.'
            }
            $stream = $response.Content.ReadAsStreamAsync().GetAwaiter().GetResult()
            $hash = [Security.Cryptography.SHA256]::Create()
            try {
                $buffer = [byte[]]::new(1048576)
                $readTotal = [long]0
                while (($read = $stream.Read($buffer, 0, $buffer.Length)) -gt 0) {
                    [void]$hash.TransformBlock($buffer, 0, $read, $buffer, 0)
                    $readTotal += $read
                }
                [void]$hash.TransformFinalBlock([byte[]]::new(0), 0, 0)
                $publicSha = [Convert]::ToHexString($hash.Hash)
                if ($readTotal -ne $ExpectedSize -or $publicSha -cne $ExpectedSha256) {
                    throw 'The public standalone APK bytes differ from the verified local file.'
                }
            } finally {
                $hash.Dispose()
                $stream.Dispose()
            }
        } finally {
            $response.Dispose()
        }
    } finally {
        $client.Dispose()
    }
}

if (-not (Test-Path -LiteralPath $ReleaseJsonPath -PathType Leaf)) {
    throw 'Committed standalone Android release metadata is missing.'
}
$release = Get-Content -LiteralPath $ReleaseJsonPath -Raw -Encoding UTF8 | ConvertFrom-Json
foreach ($field in @('enabled', 'versionCode', 'versionName', 'minSupportedVersionCode',
        'required', 'notes', 'fileSize', 'sha256', 'publishedAt', 'downloadUrl')) {
    if ($release.PSObject.Properties.Name -cnotcontains $field) {
        throw "Standalone Android release metadata is missing $field."
    }
}
if ($release.enabled -isnot [bool] -or -not $release.enabled -or
        $release.required -isnot [bool] -or $release.required -or
        [long]$release.minSupportedVersionCode -ne 0) {
    throw 'Standalone Android metadata must remain enabled, optional, and on its own channel.'
}
if ($release.versionCode -isnot [long] -and $release.versionCode -isnot [int]) {
    throw 'Standalone Android versionCode must be an integer.'
}
$versionCode = [long]$release.versionCode
$versionName = [string]$release.versionName
if ($versionCode -lt 75 -or $versionCode -gt 2100000000 -or $versionName -cne "1.0.$versionCode") {
    throw 'Standalone Android release must use versionCode 75 or higher and matching versionName.'
}
$fileName = "otziv-v$versionName.apk"
$downloadUrl = "/android-new/$fileName"
if ($release.downloadUrl -cne $downloadUrl -or
        [string]$release.sha256 -notmatch '^[0-9A-Fa-f]{64}$' -or
        [long]$release.fileSize -le 0 -or [long]$release.fileSize -gt 500MB) {
    throw 'Standalone Android download URL, SHA-256, or size is invalid.'
}
$expectedSha = ([string]$release.sha256).ToUpperInvariant()
$apk = Get-Item -LiteralPath $ApkPath -Force -ErrorAction Stop
if ($apk.PSIsContainer -or $apk.Extension -ine '.apk' -or
        ($apk.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0 -or
        $apk.Name -cne $fileName -or $apk.Length -ne [long]$release.fileSize) {
    throw 'Standalone APK filename, type, or size does not match release.json.'
}
$actualSha = (Get-FileHash -LiteralPath $apk.FullName -Algorithm SHA256).Hash.ToUpperInvariant()
if ($actualSha -cne $expectedSha) {
    throw 'Standalone APK SHA-256 does not match release.json.'
}
$verifier = Join-Path $PSScriptRoot '../../../mobile/scripts/verify-android-release.ps1'
$verified = & $verifier -ApkPath $apk.FullName -ExpectedVersionCode $versionCode -ExpectedVersionName $versionName -ExpectedSignerSha256 $expectedSigner -Quiet -PassThru
if ($verified.ArtifactSha256 -cne $expectedSha -or $verified.SignerSha256 -cne $expectedSigner) {
    throw 'Standalone APK verification returned an unexpected artifact or signer.'
}
if ($VerifyOnly) {
    Write-Host "Standalone Android APK verified before VPS access: $fileName, code $versionCode, SHA-256 $expectedSha."
    return
}

if ($VpsHost -notmatch '^[A-Za-z0-9][A-Za-z0-9.-]*$' -or
        $VpsUser -notmatch '^[a-z_][a-z0-9_-]*$' -or
        $VpsPath -notmatch '^/(?:[A-Za-z0-9._-]+/)*[A-Za-z0-9._-]+/?$' -or
        $VpsPath.Split('/') -contains '..') {
    throw 'Invalid standalone Android VPS destination.'
}
foreach ($path in @($SshKey, $SshKnownHostsFile)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Required SSH file is missing: $path"
    }
}
[void](Get-Command scp -ErrorAction Stop)
if (-not $StageOnly) {
    Assert-PublicReleaseMetadata -Expected $release
}

$remote = "$VpsUser@$VpsHost"
$remoteStage = '/tmp/.otziv-android-new-' + [guid]::NewGuid().ToString('N') + '.apk'
$sshArgs = @('-T', '-p', "$VpsPort", '-i', $SshKey,
    '-o', "UserKnownHostsFile=$($SshKnownHostsFile.Replace('\','/'))",
    '-o', 'StrictHostKeyChecking=yes', '-o', 'IdentitiesOnly=yes',
    '-o', 'BatchMode=yes', '-o', 'ConnectTimeout=15')
$scpArgs = @('-q', '-P', "$VpsPort", '-i', $SshKey,
    '-o', "UserKnownHostsFile=$($SshKnownHostsFile.Replace('\','/'))",
    '-o', 'StrictHostKeyChecking=yes', '-o', 'IdentitiesOnly=yes',
    '-o', 'BatchMode=yes', '-o', 'ConnectTimeout=15')
& scp @scpArgs $apk.FullName ($remote + ':' + $remoteStage)
if ($LASTEXITCODE -ne 0) {
    throw 'Standalone APK upload to VPS staging failed; production was not changed.'
}
$remoteProgram = @'
import hashlib, json, os, pathlib, stat, sys, tempfile
packet = json.load(sys.stdin)
stage = pathlib.Path(packet['stage'])
target_dir = pathlib.Path(packet['root']) / 'data/nginx/www/android-new'
target = target_dir / packet['fileName']
expected = packet['sha256']
expected_size = packet['fileSize']
temporary = None
def hash_file(path):
    digest = hashlib.sha256()
    with open(path, 'rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(chunk)
    return digest.hexdigest().upper()
try:
    target_dir.mkdir(parents=True, exist_ok=True)
    if target_dir.is_symlink() or not target_dir.is_dir():
        raise RuntimeError('Standalone APK target directory is unsafe')
    if target.is_symlink():
        raise RuntimeError('Standalone APK target is a symlink')
    source_fd = os.open(stage, os.O_RDONLY | os.O_NOFOLLOW)
    with os.fdopen(source_fd, 'rb') as source:
        source_stat = os.fstat(source.fileno())
        if not stat.S_ISREG(source_stat.st_mode) or source_stat.st_size != expected_size:
            raise RuntimeError('Staged APK type or size changed')
        if target.exists():
            if target.stat().st_size != expected_size or hash_file(target) != expected:
                raise RuntimeError('An APK with the same version has different bytes')
        else:
            fd, temporary = tempfile.mkstemp(prefix='.incoming-', suffix='.apk', dir=target_dir)
            digest = hashlib.sha256()
            with os.fdopen(fd, 'wb') as output:
                for chunk in iter(lambda: source.read(1024 * 1024), b''):
                    output.write(chunk)
                    digest.update(chunk)
                output.flush()
                os.fsync(output.fileno())
                os.fchmod(output.fileno(), 0o644)
            if digest.hexdigest().upper() != expected:
                raise RuntimeError('Staged APK SHA-256 changed in transit')
            os.replace(temporary, target)
            temporary = None
    actual = hash_file(target)
    if target.stat().st_size != expected_size or actual != expected:
        raise RuntimeError('Published standalone APK verification failed')
    print(json.dumps({'sha256': actual, 'fileSize': target.stat().st_size, 'path': str(target)}))
finally:
    if temporary is not None:
        try: os.unlink(temporary)
        except FileNotFoundError: pass
    try: os.unlink(stage)
    except FileNotFoundError: pass
'@
$packet = @{ root = $VpsPath.TrimEnd('/'); stage = $remoteStage; fileName = $fileName;
    sha256 = $expectedSha; fileSize = [long]$release.fileSize }
$remoteResult = Invoke-RemotePython -Program $remoteProgram -Packet $packet -SshArguments $sshArgs
if ($remoteResult.sha256 -cne $expectedSha -or $remoteResult.fileSize -ne [long]$release.fileSize -or
        $remoteResult.path -cne ($VpsPath.TrimEnd('/') + '/data/nginx/www/android-new/' + $fileName)) {
    throw 'VPS reported a different standalone APK after atomic publication.'
}
if ($StageOnly) {
    Write-Host "Standalone Android APK staged and hash-verified before web rollout: $fileName"
    return
}
Assert-PublicApk -Url "$publicOrigin$downloadUrl" -ExpectedSha256 $expectedSha -ExpectedSize ([long]$release.fileSize)
Write-Host "Standalone Android APK published and HTTP-verified: $publicOrigin$downloadUrl"
