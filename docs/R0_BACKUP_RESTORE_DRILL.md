# R0: isolated backup restore drill

## Automated backup destination contract

`BACKUP_ENABLED` remains `false` by default. Enabling it now requires a
dedicated `BACKUP_S3_*` endpoint/client configuration, bucket and credential
pair. The application and deployment/readiness gates reject both the primary
S3 bucket and the primary S3 credential pair. There is deliberately no fallback
from `BACKUP_S3_*` to `S3_*`.

Set all of the following only in the protected deployment environment, never in
Git:

- `BACKUP_S3_ENDPOINT`, `BACKUP_S3_REGION`, `BACKUP_S3_BUCKET`,
  `BACKUP_S3_PROJECT`, `BACKUP_S3_ACCESS_KEY`, `BACKUP_S3_SECRET_KEY`;
- `BACKUP_S3_INDEPENDENT_CONFIRMED=true`,
  `BACKUP_DESTINATION_PRIVATE_CONFIRMED=true`, and
  `BACKUP_ENCRYPTION_AT_REST_CONFIRMED=true` after an operator has verified
  those facts;
- `BACKUP_S3_REQUIRE_SERVER_SIDE_ENCRYPTION=true` (the fail-closed default) for
  providers that support and report SSE-S3. Set it explicitly to `false` only
  for a provider such as Selectel that does not support the S3 bucket/SSE-S3
  encryption contract; this switch never disables client-side encryption;
- the independent 32-byte `BACKUP_ENCRYPTION_KEY_BASE64` client-side encryption
  key;
- the most recent measured `BACKUP_RESTORE_DRILL_RTO` in ISO-8601 duration
  form, plus optional `BACKUP_SOURCE_COMMIT`.

Each run uploads the encrypted `OTZIVDB2` object with its SHA-256 metadata,
performs a mandatory `HEAD`, downloads the object through the dedicated backup
client, and compares its byte length and SHA-256 with the local encrypted file.
A missing/mismatched HEAD result, download, hash, or authenticated OTZIVDB2
envelope fails the run; it is never logged or recorded as successful. When
`BACKUP_S3_REQUIRE_SERVER_SIDE_ENCRYPTION=true`, the upload requests SSE-S3
AES256 and HEAD must report it. In explicit compatibility mode (`false`) the
request omits the unsupported SSE header and HEAD may either report AES256 or
no SSE status. Evidence records `NONE_REPORTED` in the latter case while the
downloaded client-side AES-256-GCM envelope remains mandatory and is fully
authenticated with the independent backup key.

Provider-enforced retention is optional. To enable it, first create a bucket
with S3 Object Lock support, then set `BACKUP_S3_OBJECT_LOCK_ENABLED=true`, a
positive `BACKUP_S3_RETENTION_DAYS`, and mode `GOVERNANCE` or `COMPLIANCE`.
The dedicated backup principal must be allowed `PutObject`, `GetObject`,
`GetObjectVersion`, `PutObjectRetention`, and `GetObjectRetention` for the
backup prefix. It does not need list, delete, or governance-bypass access. The
service requires the version ID returned by the upload, uses that exact version
for HEAD, retention lookup, and the verification download, and confirms the
requested mode and retain-until timestamp through `GetObjectRetention`.
Unsupported Object Lock, a missing version ID, or an unconfirmed retention
response fails the backup. A non-zero retention value while Object Lock is
disabled is rejected, so descriptive metadata cannot be mistaken for enforced
retention.

After encryption, deletion of the plaintext `.sql` and `.sql.gz` files is a
mandatory gate: a deletion error aborts the run before upload. The downloaded
verification copy, optional encrypted email parts, and the local encrypted
temporary file are also removed with checked, fail-closed cleanup. Immediately
after the exact uploaded version passes HEAD, retention, download, SHA-256, and
authenticated-envelope verification, the application durably appends a
`phase=remote-verified` JSON line to
`BACKUP_WORK_DIR/BACKUP_EVIDENCE_FILE_NAME` (default
`backup-evidence.jsonl`). This receipt is written before optional email and
final encrypted-file cleanup, so a later SMTP or cleanup error cannot make the
bounded catch-up path upload a second immutable copy of an already verified
backup. After email and checked cleanup, a second `phase=completed` line records
their outcomes. Each line contains the UTC timestamp, bucket/object key and
exact object version ID, SHA-256, byte count, format, elapsed time, verification flags (including
`clientSideEncryption`, `clientSideEnvelopeVerified`, actual
`serverSideEncryption`, and `serverSideEncryptionRequired`), verified
retention, explicit temporary-file cleanup flags, `emailDelivery` status, and
optional release commit/restore-drill RTO. It contains no access key, secret,
encryption key, database password, or email credentials.

An SMTP failure is appended in the completed record with
`emailDelivery.succeeded=false` and is then surfaced as a failed run for
alerting; the already verified immutable object is not uploaded again. The next
daily run sends a newly created daily backup rather than replaying old email
parts. A remote-verified line proves the recoverable remote object, but does not
claim that email or final local cleanup completed. Preserve and monitor both
phases independently. There is one unavoidable local-journal edge: if the S3
verification succeeds but the durable remote-verified append itself fails, the
next catch-up cannot prove the object already exists and may upload another
copy. Alert on evidence-write failures; eliminating that edge requires a second
independent idempotency store at the provider.

Before a leased run begins, the service takes an exclusive lock inside the
real backup work directory and removes only strict application-owned temporary
names from an interrupted prior run. Plaintext `.sql` and `.sql.gz` files are
overwritten before checked deletion; encrypted objects, parts, and verification
downloads are deleted. The evidence file and unrelated files never match the
cleanup patterns.

The idempotent run-once mode is an internal prod-like test hook, not a
production procedure. Starting the complete application as a second one-off
container can also activate unrelated startup and outbound effects, and a
startup failure under a restart policy can repeat a remote immutable upload.
Therefore persistent env import, readiness, production deployment, and the
long-running Compose services all reject or ignore `BACKUP_RUN_ONCE_ENABLED=true`.
Use this hook only in an isolated test environment where every unrelated
outbound/startup effect is disabled. The recurring daily schedule and its
bounded catch-up are the only supported production execution path. Production
readiness requires catch-up to remain enabled with a window greater than 24
hours (26 hours by default), so one full daily occurrence remains recoverable
after ordinary host or deployment downtime without permitting unbounded
historical runs.

Encrypted email delivery uses 16 MiB raw parts by default so MIME/Base64
overhead remains below common 25 MiB message limits. Production SMTP uses
finite connection/read/write timeouts and requires authenticated STARTTLS with
server-identity verification; readiness and deployment reject a downgrade when
backup email is enabled.

This drill proves that a local encrypted `*.sql.gz.enc` backup can be
authenticated, restored, and read by MySQL without touching the running
Compose/prod-like stack. Legacy unencrypted `*.sql.gz` input remains accepted
for older backups.

## Preconditions

- Run the script with PowerShell 7.0 or newer (`pwsh`). Windows PowerShell 5.1
  is not supported because the drill relies on .NET `AesGcm` and PowerShell
  Core platform detection.
- Use a backup already copied to a local disk. UNC paths are rejected.
- For an encrypted backup, provide the same 32-byte Base64 key used by the
  backup service through `BACKUP_ENCRYPTION_KEY_BASE64` or
  `-EncryptionKeyBase64`. Do not put the key in a committed script.
- Use a local Docker Desktop/Engine context. `tcp://` and `ssh://` Docker
  endpoints are rejected so the drill cannot target a production daemon.
- Have the required MySQL image locally. The script defaults to the pinned
  production MySQL 9.0.0 digest
  `mysql@sha256:8b879a3959bc59adcb7281a41950d39cf8c9b3fb23b87b9b62318ce884a7c383`
  and deliberately uses `--pull=never`.
- Allow enough Docker disk space for both the uncompressed dump and a temporary
  MySQL data volume.

Run from the repository root:

```powershell
.\infrastructure\scripts\local\restore-backup-drill.ps1 `
  -DumpPath .\data\mysql_backup\backup_2026-08-01_03-00-00.sql.gz.enc
```

Use `-MysqlImage` only when the backup must be checked against another locally
available MySQL version. `-DrillId` is optional and is mainly useful for an
operator ticket; a unique ID is generated by default.

The drill accepts one complete backup object, preferably the `.sql.gz.enc`
object downloaded from S3. If encrypted email delivery is enabled, attachments
named `.part0`, `.part1`, and so on are consecutive raw byte ranges: verify that
every part is present exactly once, concatenate them in numeric part order into
the original `.sql.gz.enc` file, and pass only that reconstructed full file to
the drill. An individual part is not a valid backup envelope.

## Isolation and checks

The script does not invoke Compose, SSH, SCP, or any application service. It
creates one uniquely named MySQL container with `--network none`, publishes no
ports, and mounts one uniquely named temporary volume. A pre-existing resource
with either exact name aborts the drill. Ownership labels are checked before
cleanup, and `finally` removes only those two exact resources; there is no
`keep` mode.

`OTZIVDB2` uses independently authenticated AES-256-GCM chunks. The drill
validates the declared envelope length and authenticates every chunk before it
creates Docker resources. Truncated, extended, modified, or wrong-key backups
fail closed. The decrypted gzip exists only as a randomly named, restricted
local temporary file and is removed in `finally`; the key is never passed to
Docker. Legacy single-message `OTZIVDB1` envelopes are explicitly rejected
because they cannot be restored with bounded memory.

After envelope and gzip validation, the drill restores the SQL, checks the
required core tables, runs SQL `CHECK TABLE`, and verifies that
`flyway_schema_history` has successful versioned migrations and no failed
migrations. `-MaxDecryptedBytes` bounds the authenticated gzip size before any
temporary output is created (default: 500 GiB). `-MaxUncompressedBytes` bounds
the SQL stream produced by gzip validation (default: 1 TiB); validation aborts
as soon as that limit would be crossed and clears its SQL buffer before
returning. Set either limit explicitly when the expected production backup is
larger; do not remove the bounds.

Success ends with machine-readable lines including:

```text
R0_RESTORE_DRILL_RESULT=PASS
BACKUP_FORMAT=OTZIVDB2_CHUNKED_AES_256_GCM
BACKUP_SOURCE_BYTES=...
BACKUP_COMPRESSED_BYTES=...
BACKUP_UNCOMPRESSED_BYTES=...
MAX_UNCOMPRESSED_BYTES=1099511627776
RTO_SECONDS=...
CLEANUP_RESULT=PASS
ERROR=NONE
```

`RTO_SECONDS` includes envelope authentication/decryption, gzip validation,
temporary resource creation, restore, and database validation; final cleanup is
outside RTO. On failure, `RTO_SECONDS=NOT_ACHIEVED`, the process exits with an
error, and `ERROR` describes the cause.

If `CLEANUP_RESULT=FAIL`, use the emitted `DRILL_ID` to inspect the two exact
resources. Do not run broad Docker cleanup commands:

```powershell
docker container inspect "otziv-r0-<DRILL_ID>-mysql"
docker volume inspect "otziv-r0-<DRILL_ID>-data"
```

This is a technical restore test, not a complete business-data reconciliation.
Record the output, backup timestamp/SHA-256, and RTO in the R0 operations ticket.
