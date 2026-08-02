# R3 external-review worker guardrails

## Safety model

External review checks use short, separately proxied `REQUIRES_NEW` database transactions. The worker HTTP request and optional S3 upload run outside a database transaction.

- Candidate scans are advisory. A node may call the worker only after an atomic compare-and-set claim installs a unique processing token, owner and bounded lease.
- Completion, failure and unused-claim release lock the row and require the same token. A completion/failure additionally requires an unexpired lease, so a reclaimed job fences the previous node from writing.
- A crashed claim becomes eligible after `external-review-check.processing-lease` (default `5m`). The effective lease is clamped to at least worker connect timeout + worker read timeout + S3 API-call timeout + safety margin, all sourced from the same timeout policy. A stale `CHECKING` row at the retry limit is recovered to terminal `ERROR`.
- Lease timestamps, due scans and publication thresholds use `CURRENT_TIMESTAMP(6)` from the primary database. JVM clocks and node time zones are not lease authorities.
- Automatic enqueue dual-writes a deterministic SHA-256 hash scoped to the review. Only a violation of `uk_review_external_checks_dedup_hash`, followed by confirmation that the same hash exists, is treated as a concurrent duplicate. Other integrity failures propagate.
- After locking the review, automatic creation repeats the candidate scan's "no check of any source exists" invariant. A manual insert racing the advisory scan therefore prevents the automatic provider call.
- Legacy rows may keep a null dedup hash. New automatic and manual rows write a hash; manual checks retain their historical repeatable-command behavior by using a new event hash each time.
- Creation and aggregate updates take a narrow pessimistic lock on the base review row. A completed check updates `review.externalConfirm*` only when its ID is still the latest check ID for that review, so an older manual check cannot overwrite a newer result or newer `PENDING` state.
- Candidate selection and transactional revalidation both accept a legacy null aggregate status, enforce the publication delay and reject placeholder/blank text. Order-filial URL falls back to the review filial URL consistently in SQL and Java.
- Never log a processing token; entity/claim `toString()` also excludes it. Free-form upstream exception/error text is neither logged nor persisted; only local failure type/codes are retained. Worker trace IDs are stored only as SHA-256 fingerprints. Missing/unknown/non-terminal statuses and mismatched check IDs become stable local errors and their evidence is rejected.

## Runtime switch

Effective enablement is:

`external-review-check.enabled` (deployment hard master) **AND** fresh app setting `external.review.check.enabled`.

The database value is read without the settings cache before claim/network boundaries. A missing row defaults to `true` for compatibility. A malformed value or read failure fails closed.

ADMIN/OWNER endpoints:

- `GET /api/admin/external-review-checks/status`
- `PUT /api/admin/external-review-checks/status/enabled` with `{ "enabled": false }` or `{ "enabled": true }`

If the switch turns off after a claim but before HTTP, the claim is token-fenced back to its previous state without consuming an attempt. If it turns off after the worker has already returned, the S3 side effect is skipped but the worker result is still token-fenced into the database; discarding it would cause a duplicate provider call after re-enable.

## Screenshot boundary

Worker screenshots are optional and best-effort. The complete JSON body is bounded before Jackson deserialization by `external-review-check.worker-max-response-bytes` (default `8 MiB`). The decoded screenshot is separately bounded by `external-review-check.screenshot-max-bytes` (default `5 MiB`), must be valid Base64, and must have a PNG/JPEG signature matching the declared type. Evidence from a previous attempt is cleared unless the current response supplies fresh accepted evidence. A validation or S3 failure does not retry a worker call whose response was already received. A newly uploaded object is deleted best-effort if fenced completion loses or the DB commit fails; a successfully replaced/cleared screenshot also triggers best-effort deletion of its old key. Logs contain check/review IDs and a key fingerprint, never the public object URL or raw payload.

Timeout controls are `external-review-check.worker-connect-timeout`, `worker-read-timeout`, `screenshot-upload-timeout`, `processing-lease-safety-margin` and `processing-lease`. The worker uses a dedicated `RestTemplate`; S3 receives per-request API-call and attempt deadlines. When `external-review-check.proxy.enabled=true`, that dedicated client uses the configured host/port and optional proxy credentials; no global HTTP client or JVM authenticator is changed.

## Rollout and rollback

1. Apply migrations `V1_10_177` through `V1_10_179` and the stale-claim covering index in `V1_10_190` before deploying the R3 runtime.
2. Keep the deployment hard master off during schema rollout.
3. Deploy all application nodes, verify the status endpoint reports `hardEnabled=false`, and inspect stale `CHECKING` volume.
4. Turn on the deployment property, then use the database operator switch for a cross-node canary.
5. Verify that two nodes scanning the same row produce one worker call, expired claims are reclaimed, stale-token completion is ignored, and a switch flip prevents new HTTP/S3 side effects.
6. Monitor worker failure codes, retry volume, stale leases and S3 validation failures. Processing-token values must never appear in logs.

For an incident, set the database operator switch to `false`; this is observed freshly by every node. Use the deployment property as the hard stop when database settings are unavailable. Rollback of the application is non-destructive: the added columns/indexes remain nullable/compatible with the prior runtime. Do not remove claim columns or indexes while any R3 node is running.
