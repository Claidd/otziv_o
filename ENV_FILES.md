# Otziv Env Files

Real env files are stored outside the repository:

```text
C:\Users\Hunt\.otziv\env\local.env
C:\Users\Hunt\.otziv\env\prod.env
C:\Users\Hunt\.otziv\env\prod-local.env
```

The scripts also accept the old project-style names and resolve them to the safe directory:

```text
.env            -> C:\Users\Hunt\.otziv\env\local.env
.env.prod       -> C:\Users\Hunt\.otziv\env\prod.env
.env.prod-local -> C:\Users\Hunt\.otziv\env\prod-local.env
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

Production deploy, preserving the familiar command:

```powershell
& D:\Java\otziv\infrastructure\scripts\prod\deploy-prod.ps1 `
  -VpsHost 95.213.248.152 `
  -VpsUser hunt `
  -VpsPort 22022 `
  -VpsPath /docker `
  -SshKey "$env:USERPROFILE\.ssh\otziv_vps_ed25519" `
  -EnvFile .env.prod `
  -RemoteEnvFile .env `
  -Tag 3.29
```

On the VPS the uploaded file is still placed in the deploy directory as the name passed with `-RemoteEnvFile`, for example `/docker/.env`.

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
