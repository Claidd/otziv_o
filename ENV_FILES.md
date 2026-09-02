# Otziv Env Files

The repository and its private project files use one common parent directory:

```text
F:\Works\Projects\otziv
F:\Works\Projects\.ssh\otziv_vps_ed25519
F:\Works\Projects\.ssh\known_hosts
F:\Works\Projects\.otziv\env\local.env
F:\Works\Projects\.otziv\env\prod.env
F:\Works\Projects\.otziv\env\prod-local.env
F:\Works\Projects\.otziv\backups\pre-deploy
```

The scripts accept the familiar project-style env names and resolve them to the
external sibling directory. They also use the sibling `.ssh` directory when an
SSH key is not passed explicitly:

```text
.env            -> F:\Works\Projects\.otziv\env\local.env
.env.prod       -> F:\Works\Projects\.otziv\env\prod.env
.env.prod-local -> F:\Works\Projects\.otziv\env\prod-local.env
default SSH key -> F:\Works\Projects\.ssh\otziv_vps_ed25519
```

You can override the directory for any script by setting:

```powershell
$env:OTZIV_ENV_DIR = "D:\some\private\env-dir"
```

Local prod-like smoke:

```powershell
.\infrastructure\scripts\local\prod-like-smoke.ps1
```

The external `prod-local.env` also stores `OTZIV_LOCAL_LOGIN_USERNAME`,
`OTZIV_LOCAL_LOGIN_PASSWORD`, and the 32-byte Base64
`OTZIV_LOCAL_LOGIN_ALLOWLIST_HMAC_KEY_BASE64`. The tracked frozen allowlist
contains only keyed HMAC-SHA256 identities of canonical usernames; it contains
neither usernames nor production database IDs. Keep a secure backup of the
external env because the tracked snapshot cannot be matched without its HMAC
key. On a new computer, restore that env and initialize only the empty local
Keycloak volume:

```powershell
.\infrastructure\scripts\local\prod-like-smoke.ps1 `
  -RotateLocalKeycloakCredentials `
  -LocalLoginUsername <local-login>
```

The password is never tracked or printed. Copy it to the clipboard with
`infrastructure\scripts\local\copy-local-keycloak-login.ps1`.
`-InitializeLocalKeycloakUserSnapshot` is reserved for the initial creation of
the tracked snapshot when that file does not exist; it never overwrites it. It
is also the only operation allowed to create the HMAC key. A missing or invalid
key for an existing snapshot fails closed instead of silently changing the
allowlist identity.

Production deploy from the active checkout:

```powershell
Set-Location 'F:\Works\Projects\otziv'

& '.\infrastructure\scripts\prod\deploy-prod.ps1' `
  -VpsHost 95.213.248.152 `
  -VpsUser hunt `
  -VpsPort 22022 `
  -VpsPath /docker `
  -RemoteEnvFile .env `
  -Tag '6.20'
```

The omitted `-EnvFile` and `-SshKey` resolve to the external sibling files
shown above. Explicit absolute overrides remain supported. On the VPS the
uploaded file is still placed in the deploy directory as the name passed with
`-RemoteEnvFile`, for example `/docker/.env`.

## Secret scanning

Install the local pre-commit hook once per clone:

```powershell
.\infrastructure\scripts\security\install-pre-commit-hook.ps1
```

Run a manual working-tree scan:

```powershell
.\infrastructure\scripts\security\run-secret-scan.ps1 -Mode dir
```

Run a full git-history scan:

```powershell
.\infrastructure\scripts\security\run-secret-scan.ps1 -Mode git
```

The hook blocks staged real env files such as `.env`, `.env.prod`, `.env.prod-local`; only `.env*.example` files belong in git. The scanner runs with redaction enabled, so findings do not print raw secret values.

Historical check:

```powershell
git log --all -- .env*
```

This repository has old `.env` commits in history, so provider-side rotation is still required and a future history cleanup should be planned separately. The CI scan checks the current checkout and blocks tracked real env files; it avoids failing forever on already-known historical commits.

Safe metadata-only audit:

```powershell
.\infrastructure\scripts\security\audit-env-history.ps1
```

History cleanup runbook: `SECURITY_HISTORY_CLEANUP.md`.
