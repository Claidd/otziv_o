# Review-check capability rollout

## Compatibility invariant

The existing UUID links remain public and keep their current behavior without feature flags:

- `/api/review-check/{uuid}`;
- `/review/editReviews/{uuid}`;
- `/{uuid}` (SPA short link).

Anonymous holders of a valid legacy UUID can still read and edit review text, edit the answer/correction fields, approve publication, and send the order to correction. Do not add or use `review-check.legacy-uuid-write-enabled` or `review-check.public-approve-enabled`.

Opaque capabilities are a parallel contract. Existing message generation and copied UUID links are not switched until rollout telemetry and client compatibility have been verified.

## Opaque link contract

An authenticated `ADMIN`, `OWNER`, or `MANAGER` with object-level access can manage an order detail through:

- `POST /api/manager/orders/{orderId}/review-check-capabilities`;
- `GET /api/manager/orders/{orderId}/review-check-capabilities?orderDetailId={uuid}`;
- `POST /api/manager/orders/{orderId}/review-check-capabilities/{id}/rotate`;
- `POST /api/manager/orders/{orderId}/review-check-capabilities/{id}/revoke`.

Issue accepts `orderDetailId`, optional `scopes`, and `expiresInDays` (default 30, allowed 1–365). Supported scopes are `VIEW`, `EDIT`, `APPROVE`, and `SEND_CORRECTION`. Rotation preserves the old scope mask and atomically revokes the old capability before issuing the replacement.

The raw `rc1_...` token is present only in the issue/rotate response. Both responses use `Cache-Control: no-store`. List responses and database rows never contain it. Build the public link client-side as:

```text
https://<public-host>/review/c#<raw-token>
```

The fragment keeps the secret out of HTTP request targets and reverse-proxy access logs. At startup the SPA captures it into the current entry's in-memory/history state (so refresh works) and removes it from the visible URL. It sends the token only in `X-Review-Capability` to the separate `/api/review-capability` API. Never put this header or history state in application logs, traces, analytics, error reports, or support screenshots. Keep request-header logging disabled at the ingress and APM layers.

Residual client limitation: the in-memory/history-state token is intentionally not copied into persistent browser storage and must never be placed in a Keycloak `redirect_uri`. A full interactive Keycloak redirect can therefore lose an already-cleaned opaque link. Until a separately reviewed non-leaking resume protocol exists, treat login from `/review/c` as unsupported: finish the public-link action anonymously, or reopen the original message link after login. Silent `check-sso` and ordinary same-entry refresh remain supported.

The public opaque API supports only:

- `GET /api/review-capability` — `VIEW`;
- `PUT /api/review-capability` and review text/answer subroutes — `EDIT`;
- `POST /api/review-capability/approve` — `APPROVE`;
- `POST /api/review-capability/correction` — `SEND_CORRECTION`.

Invalid, malformed, expired, revoked, wrong-scope, and missing-resource requests all return the same 404 contract. The response never exposes the legacy UUID (the compatibility field contains the non-resource zero UUID), order/company identifiers, bot identity, manager links, staff actions, or internal notes. Expiry and last-use decisions use the database clock. Because `order_detail_id` has no live-table foreign key, a capability keeps the same object binding when an order moves live ↔ archive.

Legacy UUID, opaque capability, legacy form, and public payment responses are
marked `no-store`, `no-referrer`, and `noindex`. Public review traffic, public
payment traffic, and integration webhooks use separate rate-limit namespaces,
so a webhook burst cannot consume the review-link quota. The current limiter is
a bounded per-instance safety net configured by `webhook.rate-limit.*`; it is
not a cluster-wide quota. A strict cross-replica limit requires an ingress or
shared store rollout with observed client/NAT traffic first.

Production/prod-like Nginx suppresses access-log entries for bearer paths,
including Angular matrix-parameter variants. Its remaining access-log format
omits query strings and `Referer`; capability locations also suppress
request-bearing Nginx error entries, and capability SPA responses enforce
`no-referrer`/`no-store`. The application REST audit records the matched route
template rather than the raw URI and never interpolates the bearer UUID. Keep
these controls when changing ingress or audit logging: legacy UUID and payment
tokens are secrets even though they must remain valid public links.

## Telemetry and staged adoption

Successful legacy UUID requests are registered only after a successful route response. The database stores `SHA-256(lowercase UUID)`, `token_type = LEGACY_UUID`, and `last_used_at`; raw values are not copied. Opaque usage updates `last_used_at`. Both timestamp paths use the database clock and write at most once per token per minute, while the bounded use metric still counts each successful request. Metrics expose only bounded labels:

```text
review_check_capability_use_total{token_type="legacy_uuid|opaque",action="view|edit|approve|correction|other"}
```

Operational summaries (never select `token_hash` into dashboards or tickets):

```sql
SELECT token_type,
       COUNT(*) AS observed_links,
       MAX(last_used_at) AS last_observed_use
FROM review_check_capabilities
GROUP BY token_type;

SELECT DATE(last_used_at) AS use_day,
       token_type,
       COUNT(*) AS distinct_links
FROM review_check_capabilities
WHERE last_used_at >= CURRENT_TIMESTAMP - INTERVAL 90 DAY
GROUP BY DATE(last_used_at), token_type
ORDER BY use_day DESC, token_type;
```

Rollout sequence:

1. Deploy schema and dual-read telemetry while continuing to issue legacy UUID links.
2. Keep the anonymous route/security probes in `prod-like-smoke.ps1` green. Run the issue/read/edit/approve/correction/revoke/expiry/rotation and live↔archive lifecycle integration suite as a separate mandatory gate before enabling opaque issuance; the current smoke does not create a real capability fixture.
3. Enable opaque-link issuance for a small internal cohort while every legacy link remains valid.
4. Expand issuance only after client/support observability is stable; retain instant fallback to the existing UUID link.
5. Stop generating new legacy links only in a separately approved release after opaque usage is proven healthy.

## R11 removal gate

Legacy contracts must not be removed merely because no new UUID links are generated. Removal requires all of the following:

- at least 90 consecutive days with zero legacy-use metric increments and no newer `LEGACY_UUID.last_used_at`;
- the observation window is longer than the documented maximum customer message/link reuse period;
- archived-order restore and support logs show no legacy dependency;
- product/support owners explicitly confirm that old customer messages may expire;
- a tested rollback release can restore the legacy routes without data repair.

Until every condition is met, UUID reads and writes remain public and functional.
