# R1 bot browser session lifecycle

## Security invariants

- A bot can have at most one durable active claim. The database unique key covers `OPENING`, `OPEN`, `CLOSING`, and `STOP_RETRY`.
- Opening checks current object access immediately before connect and again after the provider may have created the profile. A failed second check never returns the VNC capability and triggers cleanup.
- Heartbeats require an authenticated caller, the original opener subject, and a fresh object-access check. An expired heartbeat or absolute lease cannot be revived.
- Session-ID close is restricted to the original opener, but deliberately does not require current bot access. This lets the opener stop the immutable old profile after bot reassignment.
- Provider stop always uses `external_key_snapshot` captured before connect. It never rebuilds the key from mutable bot data.
- The VNC URL and password are returned only in the no-store open response. They are not persisted or logged. Provider messages and external keys are also excluded from lifecycle logs.
- VNC URLs must be absolute HTTP(S), have a host and no userinfo, fit the length bound, and contain neither literal nor percent-encoded control characters.
- VNC passwords must be bounded printable values; web/mobile additionally require the provider Base64URL format.

## State machine

`OPENING -> OPEN -> CLOSING -> CLOSED`

Any stop failure changes `CLOSING -> STOP_RETRY`; a later manual close or sweeper claim changes it back to `CLOSING`. Provider `404 Not Found` and `410 Gone` count as a successful idempotent stop.

Every transition is a conditional update on `(session_id, status, version)` and increments `version`. This prevents two application nodes from owning the same transition. A stale `CLOSING` row is reclaimed only after a delay longer than the provider HTTP read timeout. If the process dies after provider stop but before `CLOSED` is recorded, a later retry can call stop again, so the provider stop operation must remain idempotent.

The session table intentionally has no foreign key to `bots`. Deleting or reassigning a bot must not erase the immutable stop key while cleanup is unresolved.

## HTTP contract

All endpoints require one of `ADMIN`, `OWNER`, `MANAGER`, or `WORKER`; object-level authorization is applied in the service.

- `GET /api/bots/{botId}/browser/metadata` returns password-free display metadata.
- `POST /api/bots/{botId}/browser/open` accepts `{ "heartbeatSupported": true }` and returns `sessionId`, `vncUrl`, `vncPassword`, `heartbeatIntervalSeconds`, `expiresAt`, plus backward-compatible display fields.
- `POST /api/bots/{botId}/browser/sessions/{sessionId}/heartbeat` renews a live lease.
- `POST /api/bots/{botId}/browser/sessions/{sessionId}/close` closes idempotently by immutable session identity.
- `POST /api/bots/{botId}/browser/close` remains as a guarded rolling-deploy fallback. It prefers a tracked session; an authorized global role may close another opener's tracked session, while a worker may not.

The legacy MVC page `GET /bots/{botId}/browser`, web client, and mobile client all send the heartbeat capability, use session-scoped heartbeat/close when a session ID is present, and fall back to the old close route when talking to an older backend.

For compatibility with already deployed clients that send no capability, the backend sets heartbeat expiry equal to the absolute expiry. Such sessions still have the hard maximum duration and are cleaned by the sweeper.

## Timing configuration

Defaults are deliberately server-controlled:

| Property | Default | Purpose |
| --- | ---: | --- |
| `multibrowser.base-url` | required | Browser REST API URL; HTTPS on a separate VPS, port 8081 on a shared local network |
| `multibrowser.api-key` | required | Sent exactly once as `X-API-Key`; never logged |
| `multibrowser.connection-mode` | `PROXY` (`DIRECT` locally) | Explicit provider policy. `DIRECT` clears a saved proxy; `PROXY` never falls back to direct |
| `multibrowser.proxy-url` | required in `PROXY` | Server-side proxy endpoint; ignored and cleared in `DIRECT` |
| `multibrowser.heartbeat-interval-seconds` | 20 | Client heartbeat cadence returned by open |
| `multibrowser.heartbeat-timeout-seconds` | 75 | Missing-heartbeat lease timeout |
| `multibrowser.session-max-seconds` | 1800 | Absolute session lifetime |
| `multibrowser.opening-timeout-seconds` | 90 | Maximum age of an unresolved `OPENING` claim |
| `multibrowser.stop-retry-seconds` | 30 | Initial stop retry delay; exponential backoff is capped at 300 seconds |
| `multibrowser.session-sweep-delay-ms` | 30000 | Sweeper fixed delay |
| `multibrowser.session-sweep-initial-delay-ms` | 45000 | Startup delay before sweeping |

The provider client has a 5-second connect timeout and a 30-second read timeout. The stale `CLOSING` recovery threshold is at least 120 seconds.

## Deployment and operations

1. Apply Flyway migration `V1_10_188__bot_browser_sessions.sql` before serving the new backend.
2. Deploy the backend. Old clients remain functional through the guarded fallback and compatibility lease.
3. Deploy web/mobile clients to enable heartbeats and session-scoped close.
4. Monitor counts grouped by `status`, the age of the oldest active row, and repeated `STOP_RETRY` rows. Never expose `external_key_snapshot` or provider URLs in dashboards.

Production on separate VPS and local shared-network commands are documented in `docs/MULTIBROWSER_INTEGRATION.md`.

`STOP_RETRY` deliberately keeps the unique active-bot claim. Operators should fix provider reachability rather than deleting the row or opening a second profile. The sweeper processes at most 100 candidates per pass and uses indexed expiry/transition fields.
