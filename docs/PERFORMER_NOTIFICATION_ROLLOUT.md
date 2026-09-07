# Scoped performer notifications: cutover and recovery

The performer assignment transaction records a domain-specific notification intent.
The dispatcher sends once outside that transaction. Telegram does not provide a
remote idempotency key for this operation: an unacknowledged request or an expired
sender lease becomes `UNKNOWN`, and must not be retried automatically. This is not
an exactly-once delivery guarantee and these messages must not be attached to the
generic R6 relay as an automatic retry handler.

## Deployment gates

1. Drain all old performer schedulers **and mutation instances** before changing
   delivery semantics. An old HTTP/Telegram mutation handler can still produce an
   old-style notification even when its scheduler is stopped. Do not run old and
   new producers together against this queue.
2. Review duplicate active `OFFERED` rows before V289. The unique active-offer
   migration intentionally fails on a conflict; an operator must decide which
   offer remains active. The migration does not choose a winner or impose a penalty.
3. Apply V288, V289 and V300 through the normal Flyway deployment. V288 preserves
   legacy delivery evidence. Generation-zero readiness history stays
   `LEGACY_UNKNOWN` and is excluded from new automatic readiness sends.
4. Start the new producers with `performers.notifications.dispatch-enabled=false`.
   Confirm that durable intents and operational metrics are visible. Enable the
   dispatcher only after the producer cutover and unresolved-state review.

## Readiness marker introduced by V300

`readiness_intent_generation` means that the exact scoped `READY` intent exists for
that generation. It says nothing about successful delivery. An intent with status
`UNKNOWN`, `BLOCKED`, `CANCELLED` or `SENT` still satisfies this marker. Manual
reconciliation updates the existing intent, preserving its identity.

The generated `ready_notification_pending` column and its index exclude previous
intent generations from each scheduler scan. V300 backfills only matching
assignment, type, operation key and current generation. An older intent cannot
suppress a new publication cycle. Generation zero remains excluded.

The producer holds the canonical order/assignment lock. It flushes the JPA
generation first, then inserts the intent and updates the marker in the **same
transaction**. Duplicate insertion repairs a missing marker without requeueing
the existing intent. The marker is owned by JDBC and has no writable JPA mapping;
a later entity flush cannot overwrite it with a stale in-memory value.

The original `NOT EXISTS` guard remains in the indexed query. It prevents a
duplicate selection if an old producer or a maintenance action created an intent
without updating its marker. It does not make a rolling downgrade operationally
safe: stale markers can restore the expensive history scan. Stop producers and
repeat V300's exact-generation backfill as a reviewed maintenance operation before
returning from an old producer version. Do not rerun or modify an applied Flyway
version. Never mark a generation based on notification counts or delivery status.

Do not delete current-generation intents while their assignments are retained.
Such retention would invalidate the existence marker and the durable deduplication
key. A future retention policy must migrate both pieces of evidence together.

## Monitoring and recovery

The `otziv.performers.notifications.*` metrics report pending/processing/unknown,
blocked and legacy-unknown counts, oldest pending/unknown age, expired leases,
fenced completions, transport-unknown outcomes and dispatch/snapshot errors.
Snapshots are cached for 30 seconds; check `snapshot_available` and `snapshot_age_seconds`
before interpreting a zero or unchanged value. Metrics contain no assignment,
user, chat, token or payload labels.

For an unknown result, inspect the actual Telegram conversation and durable
operation before using the admin resolution action. `CONFIRM_SENT` requires a
positive message ID. `CONFIRM_NOT_SENT_REQUEUE` requires an explicit finding that
the previous operation did not send; it reuses the same intent. A stale readiness
generation cannot be requeued. Keep the actor and evidence in the resolution audit.

On rollback, disable the dispatcher and keep intents, markers and resolution
history. Do not return to an implementation that blindly retries or sends inside
the assignment transaction. A delivery lease expiring during shutdown leaves
`UNKNOWN`; elapsed time is not proof that Telegram did not deliver the message.

## Local verification

The scoped MySQL tests cover exact-generation migration/backfill, preservation of
unknown outcomes, rollback of both intent and marker, duplicate repair, backlog
fairness and concurrent enqueue. Real JPA/MySQL tests additionally cover flushing
a managed generation, a later dirty entity flush, whole-workflow rollback and
concurrent generation change versus reconciliation. These tests do not send
production messages. Synthetic query measurements are separate evidence and are
not a production throughput guarantee.
