# Synthetic queue query evidence, 2026-09-07

Run the current source queries against an isolated disposable MySQL instance:

```sh
node infrastructure/runtime-security/queue-load-smoke.mjs /secure/evidence/queue-load.json
```

The script requires local Docker and the production-pinned MySQL image. It creates its own labelled container and volume, exposes no port, uses `--network none`, and validates ownership before removal. It does not start Compose, connect to an application endpoint, read the restored VPS database, or call a provider. The output contains query hashes, full `EXPLAIN ANALYZE` plans, six timings per query and cleanup evidence. `PASS` means the measurement completed, not that a product latency SLO was met. No SLO has been supplied.

The fixture uses MySQL 9.0.0, two CPUs, 2 GiB container memory, a 128 MiB InnoDB buffer pool, and the repository's server time zone/collation. Lead schema includes the actual migrations through V1_10_299. Assignment and notification-intent columns and indexes come from their actual DDL; assignment foreign keys alone are omitted to avoid unrelated parent fixtures. The script extracts the current repository SELECT statements, including claim `FOR UPDATE SKIP LOCKED`, and executes plans inside rolled-back transactions. These are database execution timings, not Java transaction, network, concurrency, dispatch throughput or provider latency measurements.

Each size contains 20,000 or 100,000 terminal queue records plus 100 ready lead commands, 50 pending notification intents and 100 waiting assignments. The distributed profile spreads lead history across 1,000 leads and keeps terminal assignments PAID. The hot profile puts all lead history into one lead and changes historical assignments to WAITING_PUBLICATION with existing SENT READY intents. Its last 50 assignments still lack an intent. This exposes cost hidden by a restored database with empty queues or by testing only evenly distributed data.

## Measured change

Warm medians of five samples, milliseconds. Before and after used separate disposable containers with the same resource limits. Small differences on unchanged queries are run-to-run variation, not claimed improvements.

| Terminal history / profile | Lead claim before → after | Lead health before → after | Performer readiness before → after |
|---|---:|---:|---:|
| 20k distributed | 0.0651 → 0.0421 | 39.4 → 5.25 | 0.517 → 0.543 |
| 20k hot | 10.1 → 0.0698 | 28.5 → 6.19 | 167 → 105 |
| 100k distributed | 0.0874 → 0.0473 | 197 → 37.1 | 0.519 → 0.559 |
| 100k hot | 76.3 → 0.076 | 287 → 65.9 | 948 → 812 |

The old hot-lead anti-join visited 100,100 earlier-command rows and filtered delivery states. V1_10_299 adds a stored generated `blocking_lead_id` for READY, PROCESSING, UNKNOWN, LEGACY, QUARANTINED and DEAD. The claim now looks up `(blocking_lead_id,id)` and excludes completed history. The after plan examined the 100 unresolved rows for that lead instead of its 100k completed history. A large unresolved backlog can still cost work; this does not claim constant cost under every queue distribution. No unknown/quarantined/dead blocker was excluded merely to improve timing.

The covering `(delivery_state,created_at)` health index removes base-table lookups from the grouped count/oldest calculation. The after plan is a covering scan; exact all-history counts still require linear work. Existing cached health snapshots already keep this aggregation off the scrape path. The additional stored column and two indexes consume disk and add insert/state-update work. The measured SELECT improvement justifies keeping the health index in this fixture; production write amplification, storage growth and migration duration still require release sizing.

Performer claim remained 0.02–0.035 ms in these samples and its operational snapshot below 0.17 ms. The V299-only readiness plan still scanned 100,100 waiting assignments and performed a unique operation-key lookup for each, despite returning only 50 missing intents. This exposed a separate history-dependent repair scan.

The subsequent V1_10_300 correction adds a transactionally maintained exact-generation intent marker and an indexed generated pending flag. The final script applies that actual migration **after seeding** the existing SENT/PENDING ledger, exercising its backfill. Final readiness warm medians were 0.445/0.649 ms for 20k distributed/hot and 0.438/0.339 ms for 100k distributed/hot. The 100k hot plan selects the 50 missing-intent candidates using `idx_performer_ready_notification`; its safety anti-join no longer traverses the 100k already-notified assignments. This final run shared the host with a native Android build/emulator, so compare the visited-row reduction rather than tiny timing differences. The final artifact is `.codex-tmp/security-ci-synthetic-queue-load-final.json`. Canonical locks, generation checks and atomic JPA/JDBC behavior are covered separately by the performer MySQL/JPA suite; the query fixture alone does not prove them. Follow [the performer rollout protocol](../../docs/PERFORMER_NOTIFICATION_ROLLOUT.md), including marker repair after rollback to an older producer.

Raw local artifacts are `.codex-tmp/security-ci-synthetic-queue-load.json` (before, SHA-256 `1aa0d320900c21fc9182913b665d1917921c4d41c4848c1222d47127451c3927`) and `.codex-tmp/security-ci-synthetic-queue-load-after.json` (after, SHA-256 `411cc7c20ed92089433b4bcb55ee269aa2334744ee67451b05497075c38b1230`). Archive them with release evidence if retention is required; `.codex-tmp` itself is not durable evidence storage.

## Deployment and rollback

V1_10_299 is additive; published V1_10_293 and V1_10_298 stay unchanged. A stored generated-column ALTER and index construction can rebuild/lock a large existing table. Drain consumers, budget disk and a maintenance window, take the already-required recoverable backup, and measure this DDL against a representative copy before production rollout. Apply the migration before starting code that reads `blocking_lead_id`; do not assume an instant online ALTER. FIFO/race tests and parameterized unresolved-state/rollback tests belong to `LeadCommandMySqlIntegrationTest`.

Rollback application code only to the previous consumer compatible with the V298 compatibility fence; keep the additive column/indexes and Flyway history. Do not restore the legacy physical queue or drop the new column while any new consumer is running. This local query experiment does not constitute production migration or load acceptance.
