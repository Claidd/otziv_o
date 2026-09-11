# Durable incoming WhatsApp delivery (A03)

The gateway persists incoming payloads under `/auth/inbound-inbox` before making an HTTP request. Each record includes the route, client, chat and provider message ID, the payload, attempt count and next attempt time. Writes use an atomic rename and file/directory fsync on Linux. The existing per-client `/auth` bind mounts in `docker-compose.yaml` survive container recreation. Use one gateway writer per client volume; keep the directory private and include it in encrypted, access-controlled backups because pending payloads contain message text and participant identifiers.

The group receiver commits `whatsapp_inbound_receipts` and the business effect in one database transaction. A successful duplicate receipt is retained permanently. A duplicate still executing in the same process receives `409` with `Retry-After`; database unique-key locking serializes other backend instances. Transaction or commit failure cannot return a successful ACK. Do not delete old receipts as a capacity workaround.

Preference changes enqueue their encrypted, frozen response in `whatsapp_inbound_reply_outbox` inside that same transaction. A scheduled dispatcher sends only committed responses with the original operation ID. A lost response ACK therefore retains the original outbound identity; provider UNKNOWN requires the existing outbound reconciliation procedure. Tracker failures now propagate and roll back the receipt. A first message is also tracked when its company was linked by group name during processing.

## Rollout and available history

1. Apply migration `V1_10_307__whatsapp_inbound_receipts.sql` and deploy the matching backend before allowing the new gateway to drain. Coordinate a short gateway stop for the receiver/gateway switch so the old retry implementation does not discard a new `409` response. Preserve the existing `/auth` volume.
2. Record the intended cutover boundary in **Unix seconds**, then supply `WHATSAPP_INBOUND_HISTORY_SINCE` on the first start of the new inbox. If omitted or empty, the inbox persists its own creation time as the boundary. Configuration changes do not silently replace an existing metadata boundary or cursor.
3. On WhatsApp `ready`, the gateway discovers all chats, including chats with no backend OPEN item. It queues available history before draining and repeats discovery every 60 seconds. Per-chat timestamps use a 120-second overlap and stable message IDs. Each page checkpoint is written only after the whole page has been durably queued. A process death during fetch, enqueue or checkpoint can cause replay, which the backend receipt handles.
4. Observe `/internal/inbox-metrics` until `historyPaused=false`, `historyDiscoveryFailed=false`, `historyGapChats=0` and the pending backlog drains. An explicit history cap holds delivery for the affected chat and keeps its cursor unchanged. Raise `WHATSAPP_INBOUND_HISTORY_MAX_MESSAGES` if available history exceeds the cap; the next recovery cycle revisits it. Do not delete queue files to make readiness green.

The supported `whatsapp-web.js` API has `fetchMessages({limit})`, with no offset or server cursor. Recovery expands the requested window from 100 to 200, 400, etc., capped at 10,000 by default (maximum configured value 100,000). It stops after crossing the overlap boundary or exhausting the history the provider currently exposes. Deleted messages, unavailable server history and messages outside the retained device history cannot be reconstructed. Exhausting available history is not proof that the provider returned every historical message.

The default boundary does **not** retroactively prove recovery of losses from the old implementation. Backfill before the coordinated cutover requires a reviewed window: old preference commands may already have been applied before durable receipts existed, and old gateway ACK-cache entries are not authoritative evidence of a committed business transaction. Reconcile those historical preference effects explicitly. Do not claim exactly-once behavior across an unverified pre-migration boundary.

Group receipts cover this repository's `/webhook/whatsapp-group-reply`. The optional `/webhook/outreach-reply` is sent to a separately configured external service whose receiver is absent from this repository. Its gateway delivery is durable and at least once; enabling it requires that external receiver to implement its own durable receipt and effect transaction. Outgoing messages in private chats are excluded as before.

## Controls and operational signals

All `/internal/*` routes below require the gateway's `X-Otziv-Internal-Token`; they refuse access when no shared secret is configured. Metrics and dead-letter responses contain counts and hashed record identities, not message text.

| Setting or endpoint | Behavior |
| --- | --- |
| `WHATSAPP_INBOUND_INBOX_PATH` | Default `/auth/inbound-inbox`; use persistent local storage |
| `WHATSAPP_INBOUND_MAX_RECORDS` | Default 100,000 pending plus dead records; reaching capacity rejects new persistence and makes readiness fail while accepted records can still drain |
| `WHATSAPP_INBOUND_HISTORY_SINCE` | Initial recovery boundary, Unix seconds, persisted once |
| `WHATSAPP_INBOUND_HISTORY_MAX_MESSAGES` | Available-history window cap, default 10,000 |
| `WHATSAPP_INBOUND_HISTORY_INTERVAL_MS` | Discovery interval, default 60,000 ms |
| `WHATSAPP_WEBHOOK_TIMEOUT_MS` | Timeout for each HTTP attempt, default 10,000 ms |
| `GET /internal/inbox-metrics` | Pending/dead counts, capacity, oldest age, persistence failures, history gap/discovery state |
| `GET /internal/inbox-dead-letters` | Stable hashed identity, attempts, creation time and last permanent HTTP status |
| `POST /internal/inbox-dead-letters/{key}/retry` | After fixing the receiver contract, requeues the same stored payload and identity; returns 202, or 404 if no matching dead letter exists |

Delivery uses one background worker, at most 50 records per batch, with exponential retry delays from one second up to five minutes. There is no retry-count expiry. Network failure, timeout, `202`, `409`, authentication/configuration errors and temporary server failures remain pending. Permanent payload/route failures (`400`, `404`, `405`, `410`, `413`, `415`, `422`) become persistent dead letters. A pending retry or dead letter blocks later records in its chat; other chats continue. Fix the cause before an operator requeues a dead letter.

Alert on growing oldest age/backlog, any dead letters, any persistence failure, capacity exhaustion, discovery failure and a nonzero history gap count. These authenticated signals are available for the deployment's monitoring collector; their presence in code does not establish that production alert routing has been configured or exercised. A write failure latches the inbox unhealthy and readiness fails. Repair storage, then restart with the same volume; never convert a failed persistence call into a successful delivery.

The backend response outbox can be inspected without reading message bodies:

```sql
SELECT state, COUNT(*) AS records, MIN(created_at) AS oldest
FROM whatsapp_inbound_reply_outbox
WHERE state <> 'COMPLETE'
GROUP BY state;
```

An abandoned `PROCESSING` lease becomes eligible after five minutes. Pending responses retry every minute with the same frozen operation. Investigate a growing backlog and resolve provider ambiguity through the outbound operation ledger; do not invent a replacement operation ID.

## Validation and recovery boundary

`node --test inbound-inbox.test.js message-webhook.test.js` covers an outage exceeding the old retry budget, restart with pending payloads, commit followed by lost ACK, concurrent worker calls, `409`/`202`, permanent failures/retry, capacity, fsync failure, first-chat recovery of 350 messages, cap gaps, partial-page failure and process death before cursor update. The full gateway test suite also exercises its existing outbound and browser lifecycle contracts.

`WhatsAppInboundReceiptMySqlIntegrationTest` covers durable receipts across backend instances, rollback and commit failure, in-process and cross-instance concurrent duplicate delivery, permanent receipt retention, and an encrypted response outbox that rolls back with the inbound transaction and restarts with the same outbound identity. `WhatsAppWebhookControllerTest` verifies non-ACK behavior, authentication and identity validation; `GroupReplyServiceImplTest` verifies tracker failure propagation and first-message linking. Run these in the project's coordinated MySQL/Maven validation; Node fixtures alone are not evidence of backend transaction semantics.

Ordinary gateway/backend process restart is covered by this design. Database point-in-time restore, loss of the gateway volume, and paired production disaster recovery need a separate recovery exercise. Restoring the database behind a newer gateway cursor can omit previously acknowledged effects; reconcile the database restore point with inbox/history checkpoints and available provider history before resuming. This change does not certify an arbitrary database/volume backup pair as consistent.
