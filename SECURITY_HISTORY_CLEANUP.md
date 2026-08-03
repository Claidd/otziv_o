# Git History Secret Cleanup Runbook

This runbook is for removing historical secrets and private artifacts from git history. Do not run the rewrite casually: it changes commit hashes and requires a coordinated force-push.

## Current Audit

Metadata-only audit updated on 2026-08-03:

```text
Current tracked real env files: none
Affected refs: main, origin/main
Affected tags:
  before-manager-daily-summary-20260713
  before-manager-gamification-20260713
  before-vps-stabilization-20260714
  checkpoint/before-manager-performance-20260701-014342
Real .env history:
  2023-10-12 08f7e8ad A .env
  2023-11-04 7b0cd778 M .env
  2024-08-19 7b11b7ac M .env
  2025-05-05 dc7758cb M .env
  2026-04-11 c2e39125 M .env
  2026-04-11 ea86678a D .env
```

The 2026-08-03 full-history scan also identified historical TLS/private-key,
application-token and browser-profile material. Current-tree PII/debug artifacts
are being removed by the remediation commit, but that commit cannot erase older
objects. Treat the path list below as a minimum, not as the complete rewrite
manifest. Generate the final manifest from a fresh redacted full-history scan
after provider-side rotation and before running `git filter-repo`.

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

4. Record the maintenance evidence outside the repository:
   - UTC start time and write-freeze owner;
   - old credential identifiers (never secret values) and rotation tickets;
   - mirror path and its `git show-ref` checksum;
   - approved sensitive path/replacement manifest;
   - GitHub fork/cache cleanup request.

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

Create and review a newline-delimited manifest outside the repository. It must
include real env files, exposed key/profile paths and private artifacts found by
the redacted scan. Then remove every listed path from every ref:

```powershell
git filter-repo --sensitive-data-removal --invert-paths `
  --paths-from-file D:\secure-ops\otziv-sensitive-paths.txt
```

If a secret was embedded in an otherwise required source file, use a separately
reviewed `--replace-text` manifest instead of deleting the complete source path.
Never commit either manifest because it can reveal historical secret locations.

`git-filter-repo` may remove the `origin` remote as a safety measure. Re-add it if needed:

```powershell
git remote -v
git remote add origin https://github.com/Claidd/otziv_o.git
```

Verify metadata:

```powershell
git log --all -- .env .env.prod .env.prod-local ".env*"
git for-each-ref --format="%(refname) %(objectname)" refs/heads refs/tags
```

Run the redacted full-history scanner in the clean mirror and require zero
confirmed real secrets before any force-push. False positives must be documented
by rule and commit; do not add broad path allowlists to make the scan green.

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

3. Run current-tree and staged secret scans:

```powershell
.\infrastructure\scripts\security\run-secret-scan.ps1 -Mode dir
.\infrastructure\scripts\security\run-secret-scan.ps1 -Mode staged
```

4. Run history scan after the rewrite:

```powershell
.\infrastructure\scripts\security\run-secret-scan.ps1 -Mode git
```

The history scan is a release gate after cleanup: remove the temporary
`continue-on-error` exception from `.github/workflows/secret-scan.yml` only when
the rewritten remote scan is clean. Do not paste findings into chat or tickets;
keep reports redacted.

5. Ask GitHub Support to garbage-collect cached sensitive data if the repository is public or was forked. History rewriting removes refs, but hosted caches and forks can keep old objects around.

## Notes

- Do not use `git filter-branch`; it is slower and easier to misuse.
- The scheduled full-history CI job intentionally remains non-blocking until the coordinated rewrite is complete. Current-tree and new-range scans remain blocking.
- Rewriting history does not replace provider-side rotation. Any secret that was ever committed should be rotated.
