# Legacy queue maintenance (P04 / P13)

This is an explicit offline operation. No application endpoint runs performer staging, restores a backup, changes database accounts, sends messages, or repairs Flyway history. V288 and V300 remain byte-for-byte unchanged. Test evidence is under `.codex-tmp/remediation-completion-20260907/`.

## Lead queue: complete read-only inventory and deliberate classification

Authenticated ADMIN/OWNER endpoint: `POST /api/admin/lead-commands/classify-legacy?dryRun=true&limit=250&afterId=0`. Keep `throughId` from the first response unchanged, use each `nextAfterId` as the next `afterId`, stop only at `complete=true`. The fixed high-water mark excludes new arrivals. Repeat the same cursor range with `dryRun=false` to apply the reviewed classifications; the authenticated actor is stored in resolution audit. The operation changes only rows still in LEGACY, uses one short transaction per conditional transition, and never sends a legacy row automatically.

`scanned` counts returned source rows; `changed` counts successful compare-and-set mutations, not proposed actions; `conflicts` counts losing compare-and-set attempts. `rows` contains only IDs, proposed states, reason codes and outcomes. No payloads, phone numbers or message bodies appear in this response. Dry-run reasons distinguish empty/null, empty JSON object, corrupt/unsupported/invalid payloads, exhausted attempts and valid historical records of unknown delivery. An unknown historical delivery stays UNKNOWN until separate deliberate resolution. A rejected concurrent classification is reported as CONFLICT and creates no false classification audit.

## Performer state machine and startup enforcement

The offline main is `com.hunt.otziv.performers.maintenance.PerformerLegacyMaintenanceCli`. It uses one JDBC connection and never starts Spring or messaging providers. State: `STAGING -> MIGRATE_READY -> RESTORING -> COMPLETE`; an explicit abort before migration restores originals and ends in `ABORTED`. The single-active-run database constraint prevents two active runs. Staging and restore checkpoints commit in the same transaction as each bounded data batch. Restarting the CLI with the same run ID resumes the exact stored originals.

`PerformerMaintenanceStartupGuard` executes during DataSource initialization, before Flyway/JPA/writers. It rejects an unfinished run. It also rejects bypassing the maintenance operation entirely: a pending V288 with affected historical rows, or pending V300 with a matching nonzero READY occurrence, requires offline maintenance before automatic migration. A fresh empty schema or a pending migration with no affected history may migrate normally. Existing upgraded databases use reconciliation below.

Before V288, staging preserves exact original status and Telegram message ID, temporarily masks historical WAITING_PUBLICATION/OFFERED candidates and clears only the staged message IDs. Before V300, it preserves exact publication generations and temporarily masks them as zero. Ordinary Flyway then applies the unchanged migration files. Restore puts every original value back, adds missing legacy intent evidence conservatively, and repairs only exact matching READY generation markers. A historical known message ID can establish SENT; missing delivery evidence establishes LEGACY_UNKNOWN, never PENDING. Original timestamps are not invented. Existing UNKNOWN/BLOCKED/CANCELLED intents remain untouched.

## Required write fence

Every mutation call verifies actual MySQL state: the named application account exists and is ACCOUNT LOCKed; GLOBAL read_only is ON; no other unlocked SUPER/CONNECTION_ADMIN account can bypass that fence; no other connection is using this database or application account; MySQL event_scheduler is OFF. Use a separate privileged maintenance account. The CLI never changes these settings itself. Stop all application instances, worker processes, database administration sessions and database writers before locking accounts. Save the original account-lock, read_only and event_scheduler settings outside the database for exact restoration. The operator must control the maintenance credentials exclusively for the duration.

Preflight `inspect-conflicts` must finish before staging. It reports assignment IDs with multiple OFFERED rows and their counts. Resolve those conflicts deliberately while the application remains stopped; `begin` repeats the global active-offer conflict check and refuses to mask conflicting offers. Do not rely on temporarily masked statuses to evade V289 uniqueness validation.

Environment variables (in a protected local file, not command arguments/logs):

- `OTZIV_MAINTENANCE_JDBC_URL`, `OTZIV_MAINTENANCE_USER`, `OTZIV_MAINTENANCE_PASSWORD`.
- `OTZIV_MAINTENANCE_WRITER_USER`, `OTZIV_MAINTENANCE_WRITER_HOST`: exact locked application account, usually the compose MYSQL_USER and `%`; verify mysql.user.
- `OTZIV_MAINTENANCE_ACTOR`: operator identifier recorded at begin.

Commands, each as a separate invocation:

```text
inspect-conflicts 0 new 250
preview-stage ASSIGNMENT 0 new 250
preview-stage OFFER 0 new 250
begin
status <run-id>
stage <run-id> 250
migrate <run-id>
restore <run-id> 250
abort <run-id> 250
reconcile READY_HISTORY true 0 new 250
reconcile OFFER_HISTORY true 0 new 250
reconcile MARKER true 0 new 250
```

For each inventory/reconciliation stream, retain `throughId`, advance `nextAfterId` until `complete`. `begin` returns the durable run ID. Repeat `stage` until MIGRATE_READY, then `migrate` once; it runs normal Flyway migrate/validate with target 1.10.300, no baseline/repair/checksum override. Repeat `restore` until COMPLETE. `abort` is permitted only before the relevant migrations begin; repeat it until ABORTED. A conflict while restoring throws with the affected ID and rolls back the entire current batch; fix the cause deliberately before resuming. Do not edit checkpoint rows.

Stage/restore output separates cumulative staging `scanned`/`changed` from `stepScanned`/`stepChanged`/`stepConflicts`, and includes entity type, ID and outcome for each row in the committed step. Replaying a completed stage changes nothing. Failed atomic steps report failure, never a successful changed count. Status polling is read-only. `reconcile ... false ...` applies reviewed pages on an already upgraded database under the same write fence; repeated pages are idempotent. All three reconciliation passes are required after a rollback to an older producer which did not maintain the marker.

## Exact local prod-like path

Use only an isolated local database restored by the stock `infrastructure/scripts/local/prod-like-smoke.ps1`; do not point this procedure at the VPS. Keep the same compose project/volume after the initial restore. The source compose file is `compose.prod-local.yaml`; supply the same protected external `-EnvFile` used by the smoke script. No change to the stock script is required.

1. Run the normal fresh local restore/build. If the early startup guard refuses historical automatic migration, stop `app` and all local database clients/writers. Do not start another restore. Save the pre-maintenance local backup and the original MySQL account/settings state.
2. Prepare a separate local maintenance account and verified fence described above. Compose credentials are sourced from the protected external env file (`MYSQL_DATABASE`, `MYSQL_USER`, `MYSQL_PASSWORD`, `MYSQL_ROOT_PASSWORD`); never print them. A dedicated protected `.codex-tmp/remediation-completion-20260907/performer-maintenance.local.env` supplies the OTZIV_MAINTENANCE variables to the one-off container. JDBC host is `mysql:3306`, database is the same MYSQL_DATABASE. The maintenance user needs the relevant schema DDL/DML/Flyway rights plus account/process/global-state inspection; a temporary local-only DBA account is appropriate. Other privileged local accounts must be locked while it runs.
3. Invoke each command via the already built app image, overriding its entry point. `--no-deps` is required because the locked application account makes its normal MySQL healthcheck fail during maintenance:

```powershell
$composeArgs = @('compose','--env-file',$EnvFile,'-f','compose.prod-local.yaml')
docker @composeArgs run --rm --no-deps --env-from-file .codex-tmp/remediation-completion-20260907/performer-maintenance.local.env --entrypoint java app '-Dloader.main=com.hunt.otziv.performers.maintenance.PerformerLegacyMaintenanceCli' -cp /app/app.jar org.springframework.boot.loader.launch.PropertiesLauncher inspect-conflicts 0 new 250
```

Use a Docker Compose version supporting `run --env-from-file`; if unavailable, pass only variable names with `-e OTZIV_MAINTENANCE_...` after securely loading the file into process environment, or use `docker run --env-file <file> --network <same internal network> --entrypoint java <already-built-app-image> ...`. Never interpolate secret values into CLI arguments. Continue the commands above with the same run ID/high-water marks.

4. After COMPLETE, restore original account locks/read_only/event_scheduler settings and close the maintenance account/session. Keep the checkpoint tables as evidence. Restart the same restored database with `prod-like-smoke.ps1 -EnvFile <same file> -SkipProdDbRestore -NoBuild`. Normal startup validates Flyway and applies migrations after V300, including the additive V302 schema. Confirm health and completion, with local messenger sending still disabled. There is no automatic unlock or silent production maintenance.

## Bounds, failures and limits

The guarantee is at most 500 source rows/data mutations per committed staging or reconciliation batch. Indexed keyset scans replace offset/full history materialization. The conflict preflight and startup EXISTS checks may still scan their predicates when historical indexes do not cover them. MySQL DDL/index creation and unchanged Flyway predicates may scan/lock whole tables even when staging removed data-mutation candidates; reserve a maintenance window. This tool does not claim bounded DDL duration or lock-free migration.

Interrupted staging/restore is resumable from transactional checkpoints. MySQL DDL is not transactional: if a process dies inside a migration after partial DDL, do not repair history or rewrite applied SQL. Restore the verified pre-maintenance local backup/clone and investigate the DDL failure before repeating the explicit procedure. This is distinct from the tested resumed staging and resumed restoration paths. Tests cover schema fixtures before V288 and after V288/before V300; migrations earlier than that may have their own data/DDL costs outside this tool's guarantee.
