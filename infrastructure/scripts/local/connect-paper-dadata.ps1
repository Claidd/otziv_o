param(
    [string]$EnvFile = 'E:/Works/Projects/.otziv/env/prod-local.env',
    [string]$KeyFile = "E:/Works/Projects/$([char]0x0425)$([char]0x043e)$([char]0x0441)$([char]0x0442).txt"
)

$ErrorActionPreference = 'Stop'
$source = [System.IO.File]::ReadAllText($KeyFile)
$sectionAt = $source.IndexOf('dadata.ru', [StringComparison]::OrdinalIgnoreCase)
if ($sectionAt -lt 0) { throw 'DaData section not found in the supplied key file.' }
$match = [regex]::Match($source.Substring($sectionAt), 'API-[^:\r\n]+:\s*([a-fA-F0-9]{40})\b')
if (-not $match.Success) { throw 'DaData API key not found.' }
$original = [System.IO.File]::ReadAllText($EnvFile)
$lines = @($original -split '\r?\n' | Where-Object { $_ -notmatch '^PAPER_INVOICES_DADATA_KEY=' })
$updated = ($lines -join "`n").TrimEnd() + "`nPAPER_INVOICES_DADATA_KEY=" + $match.Groups[1].Value + "`n"
# Atomic replacement keeps the existing restricted ACL. Run as the owner of this env file.
$temporaryPath = $EnvFile + '.dadata-' + [guid]::NewGuid().ToString('N') + '.tmp'
try {
    [System.IO.File]::WriteAllText($temporaryPath, $updated, [System.Text.UTF8Encoding]::new($false))
    Set-Acl -LiteralPath $temporaryPath -AclObject (Get-Acl -LiteralPath $EnvFile)
    [System.IO.File]::Replace($temporaryPath, $EnvFile, $null)
} finally {
    if (Test-Path -LiteralPath $temporaryPath) { Remove-Item -LiteralPath $temporaryPath -Force }
}
$verified = [System.IO.File]::ReadAllText($EnvFile)
if (-not $verified.Contains('PAPER_INVOICES_DADATA_KEY=' + $match.Groups[1].Value)) { throw 'DaData setting was not saved.' }
Write-Host 'DaData API key saved in the local env; key value was not printed. Recreate the local app container to load it.'
