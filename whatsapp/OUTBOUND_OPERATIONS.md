# Outgoing operation contract

`POST /send` and `POST /send-group` accept an optional `operationId` JSON field
or matching `Idempotency-Key` header. IDs use 1–160 ASCII letters/digits/`._:-`
(first character alphanumeric). If both are present they must be identical.
The backend must persist one stable, non-secret business operation ID before
dispatch and reuse it across retries. A fresh ID is a new authorization to send.

The durable envelope binds the gateway `CLIENT_ID`, `send`/`send-group`, normalized
recipient and trimmed message. Reusing an ID with any different envelope returns
HTTP 409 `operation_payload_conflict`, without sending. Only hashes of the ID and
envelope are stored, alongside state, timestamps and the resulting message ID;
message text and recipient are not recorded in the ledger or new logs.

| Result | HTTP | Body |
|---|---|---|
| Confirmed, including replay | 200 | `status=ok`, `state=SUCCEEDED`, `messageId`, `operationId`, `replayed` |
| Same operation still running here | 409 | `status=pending`, `code=operation_running`, `state=RUNNING` |
| Send outcome cannot be proven | 409 | `status=unknown`, `code=operation_unknown`, `state=UNKNOWN` |
| Same ID, different envelope | 409 | `code=operation_payload_conflict` |
| Bad/conflicting ID | 400 | `invalid_operation_id` / `operation_id_conflict` |
| Store/readiness/admission unavailable | 503/429 | Stable local `code` |

All endpoints still require internal authentication. `GET /operations/:operationId`
returns `{operationId,state,messageId,envelopeHash}` (404 for an unknown ID); it needs neither
a connected WhatsApp client nor a new provider send. Successful replay also works
while the WhatsApp client is disconnected. State lookup is read-only.

Keyed send responses also expose the same persisted `envelopeHash`. Version 1 is
SHA-256 of UTF-8 `otziv.whatsapp.envelope.v1`, a zero byte, then JavaScript
`JSON.stringify([clientId,kind,normalizedDestination,trimmedMessage])`. The backend
matches gateway group normalization, ECMAScript whitespace and JSON escaping;
shared fixtures in `contracts/fixtures/whatsapp-operation-envelope-v1.json` cover
Unicode, lone surrogates and invalid inputs in both Java and Node tests. The hash
contains no raw message or recipient. Recovery requires the exact operation ID,
SUCCEEDED, a message ID and the original envelope hash. Missing metadata from an
older gateway is compatible with lookup but cannot authorize automatic recovery.

The caller must treat HTTP/network timeout, `operation_running`, `operation_unknown`
and result-persistence failures as potentially sent. It can query/replay the SAME
ID, but must not generate a replacement ID to bypass UNKNOWN. Do not automatically
retry an unkeyed legacy request. Legacy requests remain supported during producer
rollout and carry no new replay guarantee.

## Persistence and recovery

`WHATSAPP_OPERATION_LEDGER_PATH` defaults to `AUTH_PATH/outbound-operations` and
must be on a persistent private local filesystem supporting atomic exclusive
create, rename and fsync. Existing `/auth` bind mounts provide persistence. The
Node user needs write access; do not share it with an unrelated gateway or use an
NFS/object filesystem. Linux file and directory fsync protect claim-before-send;
Windows development uses file flush and atomic rename (Node cannot fsync a Windows
directory). Files are mode 0600 and the directory is created as 0700.

The claim is created exclusively and durably BEFORE calling `sendMessage`.
Concurrent same-ID requests cannot call the provider twice. Terminal replacement
is atomic and fsynced. Interrupted, torn or corrupted records never authorize a
second send. An unfinished claim seen after restart/another process is UNKNOWN;
an unreadable record fails closed. Failure persisting a result may leave UNKNOWN
even if the provider actually sent. This contract does not promise exactly-once
delivery by WhatsApp.

There is no automatic TTL deletion. `WHATSAPP_OPERATION_LEDGER_MAX_RECORDS`
(default 100000) rejects new identities at capacity rather than deleting replay
protection. Monitor capacity/disk; expanding or archiving must preserve identity
tombstones for the entire agreed replay horizon. Back up this directory with the
gateway state and preserve it on image rollback. Missing old ledger files remove
deduplication memory; restoring a ledger older than sent operations requires
reconciliation before resuming producers.

UNKNOWN needs verified provider evidence or a controlled operator decision.
The ledger provides a `reconcile` primitive accepting a verifier of the complete
envelope/message ID; it is deliberately NOT exposed as an HTTP "mark unsent"
endpoint. A future authenticated operator flow must supply trustworthy evidence,
audit the decision and fence the active gateway owner. It cannot infer non-delivery
from timeout or an absent message in a bounded history page.

## Shutdown and supported runtime

HTTP permits live until task work/cleanup completes. Timed operations register
their underlying promise so Promise.race losers cannot free admission early.
SIGTERM/SIGINT stop admission, close the HTTP listener, and drain existing work.
`WHATSAPP_DRAIN_TIMEOUT_MS` defaults to 30000 (maximum 300000). An unresolved send
at process exit retains its fsynced claim and becomes UNKNOWN after restart.
Configure container stop grace longer than the drain interval plus cleanup margin.
Replace one gateway at a time; never start a duplicate live WhatsApp session.

The reviewed dependency pair is whatsapp-web.js 1.34.7 / Puppeteer 25.10.0,
requiring Node >=22.12.0. `puppeteer-compatibility.js` adapts the removed
`Browser.isConnected()` through the auth-strategy hook to `Browser.connected`.
The explicit override replaces Puppeteer 24's vulnerable extract-zip dependency.
`npm run smoke:compatibility` uses real local/remote Puppeteer and wwebjs lifecycle,
request interception, injected fixture messaging/history and teardown; it contacts
no real WhatsApp account. Live authentication/provider compatibility remains a
separate rollout check. `npm test` uses fakes/local HTTP and no real messages.
