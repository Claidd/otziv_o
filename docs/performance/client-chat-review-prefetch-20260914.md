# Client chat review preparation and profile follow-up

## Behavior

When a client message creates or refreshes an OPEN unanswered card, a transaction-bound event queues its ID after commit. One dedicated worker with a 64-item queue reads the latest scalar source in a separate short transaction, releases its connection, then performs the existing mandatory DeepSeek review. It never closes cards or sends messages. Queue rejection, provider failure and setting failures cannot fail the committed webhook. Existing synchronous review remains the fallback.

A bounded recovery pass considers the 64 newest OPEN cards from the last 24 hours every minute, starting 30 seconds after startup. The status/date/id index avoids scanning and sorting all manager histories. Older cards and overflow remain usable through the synchronous fallback. This best-effort optimization does not require another durable queue or an AI-result table.

Checked decisions retain the existing exact-text SHA-256 and current-confidence-policy key, with a 512-entry, 24-hour in-memory limit. Failures are never cached. At action time the existing authorized workflow reads the source again; the write transaction revalidates message ID, full saved text, timestamp, current manager, current provider availability and confidence, hard rejection rules, and audit evidence. No page fields, financial calculations or authorization rules are removed.

Disable preparation using `manager-control.unanswered-client-messages.no-response-ai-prefetch-enabled=false`; ordinary mandatory review still works. Monitor `otziv_client_chat_review_prefetch_total` by fixed `result` and `otziv_client_chat_review_prefetch_duration_seconds`. `ready` counts completed preparations (including negative decisions), not automatic closures; `cached` counts recovery skips. No customer identifiers, message text, tokens or provider error bodies appear in these metrics/logs.

## Module contract

`config.settings` owns the exact `ClientChatReviewSettings` API, consumed only by the client-chat preparation worker. It exports one read-only `prefetchEnabled()` policy with the established cached settings semantics. It exposes no repository, arbitrary setting key or mutation. The settings service owns its short read transaction; provider calls occur only after that and the source-snapshot transaction complete. Internal policy reads do not grant card access: actor authorization and audit remain in the existing command workflow. No new cross-module internal-access allowance or cycle is introduced.

## Profile evidence and scope

Pre-release warm 45-minute full-HTTP profile observations: N approximately 53, mean 106.8 ms, histogram P95 approximately 389.2 ms, 69.8% at most 100 ms. Isolated warm misses still reached 300–500 ms; therefore an overall sub-100 ms SLO is not established.

Paired read-only production SQL trials retained every financial row and all canonical live/ledger/archive exclusions. Moving predicates into UNION branches did not materially improve the existing optimizer plan. Removing redundant DISTINCT in unique-ID branches likewise did not produce a large improvement, so neither rewrite is shipped. JDBC probes inside the app container, using its actual connector and database, measured 2–83 ms across 47 active users (including first-use overhead); heavier-history repeated reads were approximately 14–67 ms after the first call. These are SQL measurements, not HTTP benchmarks.

A fixed `analytics.salary/daily-users` segment now isolates the complete canonical salary read from the surrounding worker-stat construction. Post-release HTTP observations must include first loads and cache misses and use the new segment to locate any remaining delay. Profile SQL, history, maps, sums, cache freshness and formulas are unchanged.

## Shared VPS database memory

Production MySQL had a 4 GiB container limit but the default 128 MiB InnoDB buffer pool. A 165-second observation recorded 12,119 physical page reads / 3,864,689 logical requests (73.45 physical reads/s, 0.314% miss ratio). The shared host has about 7.75 GiB RAM; about 1.84 GiB was available. MySQL already used approximately 1.22 GiB, so assigning its full container limit to the buffer would be unsafe.

The reviewed Compose command now sets the buffer pool to 512 MiB. This adds approximately 384 MiB plus allocation overhead and retains headroom for the JVM and browser services. InnoDB's transactionally coherent page cache preserves every record and read-consistency rule; financial SQL and application cache TTLs do not change. Durability settings, isolation, access controls and image digests are unchanged. The ordinary deployment may recreate MySQL to apply its startup configuration; verify actual `innodb_buffer_pool_size`, service health, free memory and physical-read rate afterward. Restore the previous source configuration to roll back the memory tuning.

The small buffer and observed physical reads are a plausible contributor to intermittent slow profiles, not proof that every delay is disk I/O. Compare subsequent physical-read rates and full HTTP latency, retaining cold observations and accounting for different traffic. Read-only diagnostic probes themselves warm database pages; do not treat their speed as an unbiased HTTP before/after result.

The database continuity guard must accept this exact memory option for the reviewed native MySQL image. It permits only the previous implicit default or `--innodb-buffer-pool-size=536870912`, in both target and existing launch metadata, so forward deployment and configuration rollback remain possible. Arbitrary sizes, alternate spellings, duplicate flags, storage paths, image changes and durability changes remain rejected. A regression resolves the repository's real Compose command and runs it through the continuity guard against the previous native launch; synthetic fixtures alone did not detect the initial mismatch.

## Design references

- Spring 7 transaction-bound events: https://docs.spring.io/spring-framework/reference/data-access/transaction/event.html
- AFTER_COMMIT resources remain accessible; separate read transactions and a dedicated worker prevent the provider call retaining them: https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/transaction/event/TransactionalEventListener.html
- MySQL buffer-pool sizing and online behavior: https://dev.mysql.com/doc/refman/8.4/en/innodb-buffer-pool-resize.html
- MySQL derived-condition pushdown behavior was verified against actual query plans: https://dev.mysql.com/doc/refman/8.0/en/derived-condition-pushdown-optimization.html

## Acceptance

Require behavior/transaction/MySQL projection tests, architecture gates, full PR and exact-main CI, normal backup-verified deployment, healthy services and new-process observations. Do not claim actual action latency improvement until real manager actions reuse prepared results. Artificially closing production cards is not an acceptable benchmark.
