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
