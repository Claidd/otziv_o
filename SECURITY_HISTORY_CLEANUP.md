# Git History Secret Cleanup Runbook

This runbook is for removing historical real env files from git history. Do not run the rewrite casually: it changes commit hashes and requires a coordinated force-push.

## Current Audit

Metadata-only audit performed on 2026-06-06:

```text
Current tracked real env files: none
Affected refs: main, origin/main
Affected tags: none found
Real .env history:
  2023-10-12 08f7e8ad A .env
  2023-11-04 7b0cd778 M .env
  2024-08-19 7b11b7ac M .env
  2025-05-05 dc7758cb M .env
  2026-04-11 c2e39125 M .env
  2026-04-11 ea86678a D .env
```

`.env.prod.example` and `.env.prod-local.example` also appear in history, but the current cleanup target is the real `.env` path. Re-scan history after cleanup before deciding whether examples need rewriting too.

## Before Cleanup

1. Rotate provider secrets first or treat them as already compromised:
   - Database and MySQL root/user passwords
   - Keycloak admin/client/database secrets
   - Telegram, MAX, WhatsApp tokens and webhook secrets
   - T-Bank terminal/password keys
   - OpenAI/Yandex keys
   - S3 access/secret keys
   - Grafana/admin credentials

2. Freeze pushes briefly:
   - Tell anyone with a clone to stop pushing until rewrite is complete.
   - After rewrite they must re-clone or hard-reset to the new `origin/main`.

3. Keep a backup mirror:

```powershell
cd D:\Java
git clone --mirror https://github.com/Claidd/otziv_o.git otziv_o-history-backup.git
```

## Recommended Cleanup: git-filter-repo

GitHub currently recommends `git-filter-repo` for removing sensitive data from repository history.

Install if needed:

```powershell
python -m pip install --user git-filter-repo
```

Use a fresh mirror clone for the rewrite:

```powershell
cd D:\Java
git clone --mirror https://github.com/Claidd/otziv_o.git otziv_o-clean.git
cd D:\Java\otziv_o-clean.git
```

Remove real env paths from every ref:

```powershell
git filter-repo --sensitive-data-removal --invert-paths `
  --path .env `
  --path .env.prod `
  --path .env.prod-local
```

`git-filter-repo` may remove the `origin` remote as a safety measure. Re-add it if needed:

```powershell
git remote -v
git remote add origin https://github.com/Claidd/otziv_o.git
```

Verify metadata:

```powershell
git log --all -- .env .env.prod .env.prod-local ".env*"
```

Push rewritten history:

```powershell
git push --force --all origin
git push --force --tags origin
```

## Alternative Cleanup: BFG

Use this only if `git-filter-repo` is unavailable. BFG removes files by name across history, so review the pattern before running it.

```powershell
cd D:\Java
git clone --mirror https://github.com/Claidd/otziv_o.git otziv_o-clean.git
java -jar C:\tools\bfg.jar --delete-files ".env" D:\Java\otziv_o-clean.git
cd D:\Java\otziv_o-clean.git
git reflog expire --expire=now --all
git gc --prune=now --aggressive
git push --force --all origin
git push --force --tags origin
```

## After Cleanup

1. Re-clone the repo or reset local clone:

```powershell
git fetch origin
git reset --hard origin/main
git clean -fd
```

2. Reinstall the local hook:

```powershell
.\infrastructure\scripts\security\install-pre-commit-hook.ps1
```

3. Run current-tree secret scan:

```powershell
.\infrastructure\scripts\security\run-secret-scan.ps1 -Mode dir
```

4. Run history scan after the rewrite:

```powershell
.\infrastructure\scripts\security\run-secret-scan.ps1 -Mode git
```

If the history scan still flags old example files, decide whether to rewrite `.env.prod.example` / `.env.prod-local.example` history as well. Do not paste findings into chat or tickets; keep reports redacted.

5. Ask GitHub Support to garbage-collect cached sensitive data if the repository is public or was forked. History rewriting removes refs, but hosted caches and forks can keep old objects around.

## Notes

- Do not use `git filter-branch`; it is slower and easier to misuse.
- The current CI intentionally scans the checked-out tree and blocks tracked real env files. It does not scan all old commits until the known historical `.env` issue is cleaned.
- Rewriting history does not replace provider-side rotation. Any secret that was ever committed should be rotated.
