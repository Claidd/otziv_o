# R6 transactional outbox foundation

This package adds the runtime for `V1_10_173__r2_integration_outbox.sql` without
moving any existing payment, Keycloak, WhatsApp or S3 side effect. Existing
behaviour is therefore unchanged. The relay is absent by default:

```properties
OTZIV_INTEGRATION_OUTBOX_RELAY_ENABLED=false
```

## Safety invariants

- `IntegrationOutboxService.enqueue(...)` requires an already active business
  transaction (`Propagation.MANDATORY`) and the row rolls back with that
  transaction.
- The caller supplies a stable, non-secret idempotency key. Only a domain-
  separated SHA-256 hash is persisted. The plaintext key is never logged.
- Payloads must be bounded JSON objects. Credential-shaped keys and obvious
  bearer/JWT/private-key values are rejected. A handler resolves credentials
  from its normal secret store at delivery time.
- MySQL `CURRENT_TIMESTAMP(6)` is the only lease/due clock. A claimant first
  performs a bounded non-locking scan of eligible aggregate heads, then tries
  each candidate by primary key with `FOR UPDATE SKIP LOCKED`, revalidates the
  aggregate head, installs a fresh UUID token, commits, and only then performs
  external I/O. The aggregate anti-join is deliberately absent from the locking
  query because MySQL can otherwise wait on unrelated locked aggregates despite
  `SKIP LOCKED`.
- Claim and final-lease recovery are restricted to event types whose handlers
  are registered on that exact application instance. An older instance in a
  rolling deployment cannot claim, increment or make a newer unknown type dead.
- A candidate is eligible only when no earlier `PENDING`, `PROCESSING` or `DEAD`
  row exists for the same aggregate. Backoff or a dead letter therefore cannot
  be overtaken, while unrelated aggregates remain eligible.
- `SUCCEEDED`, `PENDING` retry and `DEAD` transitions all require the current
  `(integration_outbox_id, status=PROCESSING, processing_token)` fence. A late
  worker cannot overwrite a newer claim.
- Attempts, batch size, lease, exponential backoff, jitter, payload size and
  operational counts are bounded by validated configuration.
- If a process dies on its last permitted attempt, a bounded lease-expiry pass
  moves the row to `DEAD` using its existing processing-token fence.
- Logs and metric tags contain no payload, aggregate id, processing token,
  processing owner, remote response or exception message. Metrics use only
  fixed low-cardinality outcomes.
- Reusing a deduplication key with a different aggregate envelope, version,
  delivery policy or semantic JSON payload fails closed.

Delivery is intentionally **at least once**. Every typed handler must use the
stable `eventId` as an idempotency key at the remote boundary. Its external I/O
timeout must be shorter than `OTZIV_INTEGRATION_OUTBOX_LEASE_DURATION`; otherwise
a lease can expire while the original handler is still running.

## Mandatory blockers before enabling the relay

`OTZIV_INTEGRATION_OUTBOX_RELAY_ENABLED` must remain `false` until every enabled
integration slice has all of the following:

- hard connect/read/request transport deadlines shorter than the processing
  lease, plus a dedicated bounded executor/bulkhead; Java interruption is only
  cooperative and must never be replaced with unsafe `Thread.stop`;
- a tested replay/requeue procedure for one `DEAD` event which preserves its
  original `eventId`, together with bounded terminal retention and a dedup
  tombstone strategy; blindly deleting terminal rows loses deduplication memory;
- one shared business idempotency contract for the legacy direct path and the
  outbox path during dual-write. An outbox-only `eventId` cannot suppress a side
  effect already sent directly under a different key;
- serialized/versioned producer mutations for each ordered aggregate. The
  aggregate-head guard orders committed rows but cannot observe an older event
  still hidden inside another uncommitted transaction;
- mixed-version tests proving old instances skip new event types and at least
  one new instance has the matching handler before enqueueing starts.

## Typed handler contract

Implement `IntegrationOutboxHandler<T>` and provide:

1. a unique, versioned event type such as `payment.receipt.issue.v1`;
2. a concrete payload DTO class;
3. a bounded, idempotent `handle` implementation;
4. `isRetryable` classification only for known permanent failures.

Duplicate handler types fail application startup. Instances do not claim event
types they do not know. A malformed payload for a registered type is a permanent
failure and moves that event to `DEAD` once the relay is enabled.

## Safe rollout sequence

1. Deploy this foundation with the relay disabled and verify Flyway V173 and
   additive claim indexes V195.
2. Add one typed handler and remote idempotency support, still relay-disabled.
3. Add transaction-bound dual-write for one existing side effect behind its own
   producer flag. Keep the legacy direct call authoritative during a strictly
   non-mutating shadow phase, or make both paths use the same remote business
   idempotency key.
4. Observe enqueue/dedup metrics and the admin status endpoint. Confirm payload
   policy and dedup semantics in staging.
5. Disable the shadow producer if any mismatch appears. No existing side effect
   path depends on this outbox yet.
6. Only after every mandatory blocker above and handler/remote idempotency
   validation, enable the relay on one instance, then expand gradually.
7. Move the direct side effect behind a separate cutover flag only after replay,
   retry and dead-letter drills pass.

The sanitized status endpoint is:

```text
GET /api/admin/integration-outbox/status
```

It is restricted to `ADMIN` and `OWNER` and returns capped counts and DB-clock
ages only. It never returns event ids, payloads, aggregate ids, errors, owners or
processing tokens. The outbox is not wired into public actuator readiness:
backlog must not trigger a restart loop or disclose integration state.

`V1_10_195__integration_outbox_claim_indexes.sql` is additive and must not be
folded back into already-applied V173. It provides dedicated pending, stale-lease
and aggregate-head indexes matching the runtime claim predicates.

## Runtime settings

| Environment variable | Default | Bound |
|---|---:|---:|
| `OTZIV_INTEGRATION_OUTBOX_RELAY_ENABLED` | `false` | explicit opt-in |
| `OTZIV_INTEGRATION_OUTBOX_BATCH_SIZE` | `25` | `1..100` |
| `OTZIV_INTEGRATION_OUTBOX_LEASE_DURATION` | `PT2M` | `PT5S..PT1H` |
| `OTZIV_INTEGRATION_OUTBOX_DEFAULT_MAX_ATTEMPTS` | `20` | `1..100` |
| `OTZIV_INTEGRATION_OUTBOX_BASE_BACKOFF` | `PT5S` | `PT0.1S..PT24H` |
| `OTZIV_INTEGRATION_OUTBOX_MAX_BACKOFF` | `PT30M` | base..`PT24H` |
| `OTZIV_INTEGRATION_OUTBOX_JITTER_RATIO` | `0.20` | `0..0.5` |
| `OTZIV_INTEGRATION_OUTBOX_MAX_PAYLOAD_BYTES` | `65536` | `1024..1048576` |
| `OTZIV_INTEGRATION_OUTBOX_STATUS_COUNT_CAP` | `10000` | `100..100000` |
| `OTZIV_INTEGRATION_OUTBOX_FIXED_DELAY_MS` | `5000` | `100..3600000` ms |
| `OTZIV_INTEGRATION_OUTBOX_INITIAL_DELAY_MS` | `30000` | `0..86400000` ms |
