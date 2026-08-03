# Source and workstation disaster recovery

## What a clean Git clone contains

The repository is the recoverable source-of-truth for application code,
infrastructure definitions, database migrations, build wrappers and dependency
lockfiles. It also deliberately retains the currently unique signed APK files in
`mobile/builds/` and generated notification media in `generated-assets/`.

A clean clone can therefore rebuild the backend, frontend, mobile web bundle,
WhatsApp gateway and external-review worker without copying `node_modules`,
`target`, `dist`, `mobile/www` or Codex temporary files from the old computer.
Those directories are build products or caches, not source.

## What must not be stored in Git

Git is not a safe backup for live secrets or customer data. The following need
separate encrypted, access-controlled copies with at least two independent
locations:

- the production environment file and all API/database/OAuth secrets;
- Android signing keystore, its properties and recovery credentials;
- encrypted MySQL backups and the independent backup-encryption key;
- S3/object-storage business content and its recovery credentials;
- any required messaging/browser session state that cannot be recreated.

Do not place these values in a repository, release asset or ordinary cloud drive.
The backup encryption key must not exist only beside the backups it decrypts.
The database backup destination must also use the dedicated `BACKUP_S3_*`
account and bucket rather than the application's primary `S3_*` storage. A
verified backup run emits `backup-evidence.jsonl`; copy those non-secret records
to monitoring/audit storage, but do not mistake evidence metadata for the backup
object itself. See `docs/R0_BACKUP_RESTORE_DRILL.md` for the exact upload,
version-bound HEAD/download/checksum and Object Lock retention contract.

## Clean-machine recovery check

On a second machine, clone the repository and check the exact release commit:

```powershell
git clone <repository-url> otziv-recovery-check
cd otziv-recovery-check
git fsck --full
git status --short
git ls-files mobile/builds generated-assets
```

Restore secrets from the approved encrypted store, install Docker/Java/Node and
Android prerequisites, then run:

```powershell
.\infrastructure\scripts\security\check-repository-hygiene.ps1
.\infrastructure\scripts\security\check-infrastructure-contract.ps1
.\infrastructure\scripts\security\check-backup-readiness.ps1 -EnvFile .env.prod
.\infrastructure\scripts\local\prod-like-smoke.ps1
```

Before declaring disaster recovery ready, perform the isolated database restore
drill described in `docs/R0_BACKUP_RESTORE_DRILL.md`, verify representative S3
objects, and record the tested commit, backup checksum, recovery time and date.
For releases containing device-token migration V210, also follow
`docs/DEVICE_TOKEN_V210_ROLLOUT.md`; an application-only rollback to a pre-V210
binary is not compatible with the migrated database.

## Rules for removing retained artifacts later

Do not untrack `mobile/builds/` or `generated-assets/` merely to reduce Git size.
They may be removed only after all of the following are true:

1. Every retained artifact has a verified copy in owned release/object storage.
2. A second machine can download and checksum that copy without the original
   workstation.
3. Production deployment no longer reads the checkout copy.
4. A clean-clone and rollback drill succeeds.
5. The removal is made in a dedicated, reviewed change.

For an additional repository copy, create a Git bundle on encrypted external
storage and verify it by cloning the bundle. A bundle protects against a hosted
Git outage, but it still does not replace database, object-storage or secret
backups.
