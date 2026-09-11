# Local recovery CLI verification — 2026-09-07

The production commands in `infrastructure/recovery/cli.mjs` completed a real local fixture pipeline at **09:27:26 UTC: 13 checks passed**. No recovery production source changes were needed. No existing application, Keycloak, MySQL or object-storage data was used or modified.

The fixture used PostgreSQL 17.10, Node 22.23.2 and AWS CLI 2.31.0, with a separate TLS MinIO server and newly created versioned bucket. The AWS installer signature was verified against the fingerprint in the [official AWS installation instructions](https://docs.aws.amazon.com/cli/latest/userguide/getting-started-install.html); the installer checksum and exact image digests are retained in the evidence. Both fixture servers used an internal Docker network with no published ports. The source connection used PostgreSQL's local Unix socket. MinIO's fixture certificate was trusted explicitly; TLS verification was enabled.

## Observed behavior

- Real `preflight` checked tools/configuration and retained `remoteVerification=NOT_RUN`.
- Real `backup` streamed `pg_dump --format=custom` through AES-GCM, uploaded using AWS CLI v2, verified HEAD metadata and the exact object version, downloaded it, and authenticated the downloaded envelope. Remote-verified/completed receipts were written and the temporary capture directory was removed.
- A separate exact-version download matched the recorded ciphertext size and SHA-256. `manifest` generated the paired artifact metadata using explicitly synthetic companion attestations described below.
- Real `postgres-drill` authenticated the archive, created its own labelled PostgreSQL container/volume with `--network none`, restored with `pg_restore`, verified Keycloak-like tables/realm/changelog records, and removed its resources and plaintext workspace. The database-only drill took **7 seconds**; this is a fixture measurement, not a production RTO.
- An additional fresh database restore checked all **100 synthetic user rows** against the source content digest. `compare-identities` compared the restored subject/realm/business-role exports with the expected 100 fixture identities.
- Actual CLI calls rejected a wrong key with `encrypted_archive_authentication_failed` and corrupted ciphertext with `restore_object_checksum_mismatch`. Their resource inventories remained unchanged and the plaintext workspace was empty afterward.
- All six fixture resources — two containers, three volumes and one network — were removed only after exact ownership-label checks. Final inventories for fixture and drill labels were empty. The fixture tool image and nonsecret evidence files were retained.

## Evidence and repeat commands

Evidence is under `.codex-tmp/recovery-cli-e2e-20260907/`: `run.mjs`, `build/Dockerfile`, `commands.json`, `runtime-tools.json`, `summary.json`, `preflight.json`, `postgres-completed.json`, `postgres-drill.json`, `identity-comparison.json`, `negative-cli.json`, `cleanup.json` and the encrypted downloaded archive. The first harness attempt stopped before backup because Git's Windows safe-directory argument used backslashes; that failed attempt and successful cleanup remain in `attempt1-harness-git-path/`.

Run from the repository root with the recorded local images and Docker available:

```powershell
docker build --pull=false -t otziv-recovery-cli-fixture:20260907 .codex-tmp/recovery-cli-e2e-20260907/build
node .codex-tmp/recovery-cli-e2e-20260907/run.mjs
node .codex-tmp/recovery-cli-e2e-20260907/negative-cli.mjs
```

The harness invokes the unchanged production `preflight`, `backup`, `manifest`, `postgres-drill` and `compare-identities` commands. It records command arguments without credentials. Fixture encryption/S3 credentials are generated per run, never printed, and disappear with their process/container state. The retained encrypted archive is evidence of the completed run; repeat the harness to generate a new recoverable fixture. Preserve selected evidence outside the ignored temporary directory when long-term retention is required.

## Boundary of this proof

This is a **Keycloak-like database fixture**, not a running Keycloak server or restoration of a production realm. The paired manifest's MySQL receipt and business-object, secrets, Android-signing and integration-state companion entries are explicitly **synthetic attestations**; those components were not restored by this test. Local `independentConfirmed`/component flags and RPO/RTO values are fixture inputs solely for exercising the strict production protocol. They do not attest to an independent external account, site, storage provider, agreed policy or an authentic production recovery set. The MinIO fixture administrator was used for bootstrap and the local storage protocol check; least-privilege production IAM, object lock/SSE policies and external durability are separate acceptance checks.

The result therefore remains `fullSystemRestore=NOT_PROVEN`. External independent storage, owner-approved RPO/RTO, paired MySQL/Keycloak/object/secrets restoration, provider/session invalidation and authenticated application acceptance still require the full isolated recovery procedure in [the recovery runbook](../infrastructure/recovery/README.md). No schedule or external storage was activated by this verification.
