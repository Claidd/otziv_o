# R1 rollout guardrails

## Immutable public contracts

R1 must not require registration or authentication for a person who has a valid public link.

- `/api/review-check/**` remains publicly readable and writable for review text, answer, correction, publication approval and return to correction.
- `/api/payments/public/**` and public common-invoice payment endpoints remain publicly readable and payable by link.
- Do not introduce or enable `review-check.legacy-uuid-write-enabled=false` or `review-check.public-approve-enabled=false` while these UUID links are the active public contract.
- A secure capability token replaces the UUID contract only through additive dual-read/dual-write rollout, usage telemetry and an explicit compatibility gate in R2/R3/R5/R11.

## Bot browser authorization

| Role | Browser access |
| --- | --- |
| ADMIN / OWNER / MANAGER | Any existing bot, preserving the current operational role matrix |
| WORKER | Own bot or a bot attached to the worker's current actionable review, bad-review task or open recovery task |
| Other / anonymous | No access |

Every metadata/open/close request performs a fresh database authorization check. Missing and unauthorized bot IDs both return `404`. The worker metadata response never contains the bot password. Browser responses use `Cache-Control: no-store`; VNC URLs accept only bounded absolute `http` and `https` URLs without credentials or control characters; upstream details and session URLs are not returned in errors or logs.

Task/review bot-deactivation endpoints accept `null` and legacy `0` as “use the currently attached bot”. A positive request ID must match the current attachment; otherwise the operation returns `409` before exclusion, deactivation, audit, save or reassignment.

## Runtime kill switches

- External review worker: deployment property `external-review-check.enabled` is the hard master; fresh database app setting `external.review.check.enabled` is the cross-node operator switch (missing means enabled for compatibility, malformed/unreadable means disabled). ADMIN/OWNER can inspect it with `GET /api/admin/external-review-checks/status` and change it with `PUT /api/admin/external-review-checks/status/enabled`. When effectively disabled, scheduled processing is skipped, a manual run fails before creating state, queued checks are not consumed and the HTTP client cannot be called directly. See `R3_EXTERNAL_REVIEW_WORKER_GUARDRAILS.md` for claim/fence rollout details.
- Reputation AI and external AI search: app setting `reputation.ai.enabled`, default `true` for compatibility. ADMIN/OWNER can change it with `PUT /api/ai/reputation/status/enabled` and `{ "enabled": false }`. Safety checks read the value directly from the database, so another application node does not retain a stale enabled value for the normal settings-cache TTL. The router, search router and direct response client all fail closed without a provider network call.

## Rollout order

1. Run targeted backend and client tests.
2. Run the complete backend suite and production client builds.
3. Run `infrastructure/scripts/local/prod-like-smoke.ps1` against the current working tree.
4. Verify anonymous review-check edit/approve/correction and both public payment families explicitly.
5. Verify ADMIN/OWNER/MANAGER browser launch and WORKER own/current-task launch; verify arbitrary WORKER bot ID returns `404` and `/api/admin/bots/{id}` does not disclose a password to WORKER.
6. Verify both kill switches in disabled and re-enabled states without production side effects.

Rollback is application-only for R1: no destructive schema change is introduced. Re-enable runtime switches through their existing setting/property and roll back the application image if a role scenario regresses.
