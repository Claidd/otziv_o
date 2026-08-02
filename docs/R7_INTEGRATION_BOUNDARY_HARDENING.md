# R7 integration boundary hardening

This release hardens the internal external-review worker and WhatsApp gateways
without changing public review/payment URLs or application role rules.

## Service authentication rollout

The backend sends `X-Otziv-Internal-Token` on every worker/gateway request when
the corresponding secret is configured. Node compares a SHA-256 digest with
`crypto.timingSafeEqual`; tokens are never included in logs or error responses.
Only minimal `/health` and `/ready` endpoints remain unauthenticated.

Use different, high-entropy values for these two directions:

- `EXTERNAL_REVIEW_WORKER_SHARED_SECRET`
- `WHATSAPP_GATEWAY_SHARED_SECRET`

The safe rolling sequence is:

1. Deploy the new backend and Node images with both secrets empty and both
   `*_AUTH_REQUIRED=false`. Existing local stacks continue to work.
2. Put a secret in the app container first and restart only the app. Old Node
   images ignore the additional header.
3. Put the same secret in the matching Node service and restart that service.
   A configured secret immediately enables constant-time enforcement.
4. After successful authenticated traffic, set `*_AUTH_REQUIRED=true`. This
   makes a missing secret a startup error instead of silently disabling auth.

`.env.prod.example` deliberately sets both required flags to `true` and leaves
the values blank, so an operator must provision secrets before production
startup. Never reuse `WHATSAPP_WEBHOOK_SECRET`: that secret protects the opposite
WhatsApp-to-backend direction.

## External-review egress policy

The worker rejects top-level filial URLs unless they are credential-free HTTP or
HTTPS URLs with no control characters. Literal and DNS-resolved localhost,
private, carrier-grade NAT, link-local, documentation, multicast, and reserved
IPv4/IPv6 ranges are blocked. Browser routing repeats validation for redirects
and subrequests and limits main-frame navigations. DNS is re-resolved rather than
trusted from an earlier application check, reducing the DNS-rebinding window.

This is defense in depth, not a substitute for egress policy. If a forward proxy
is enabled, it must independently deny private destinations and DNS rebinding.
The compose files place external-review only on `external_review_net` and
WhatsApp only on `messaging_net`; the two worker classes cannot resolve or reach
each other. MySQL and Keycloak remain only on `internal_net`, while the backend
joins all required bridges.

## Resource and container bounds

External-review has bounded JSON/text/navigation/concurrency settings, a
read-only root filesystem, writable `/tmp`, `no-new-privileges`, all Linux
capabilities dropped, PID/CPU/memory limits, and an unprivileged Node 22 runtime.
Its Chromium sandbox remains enabled; a container smoke launches Chromium under
that exact hardening profile without `--no-sandbox` or setuid-sandbox bypasses.
WhatsApp has bounded JSON/message/concurrency settings, Node 22 with lockfile
installation, `init`, `no-new-privileges`, and PID/CPU/memory limits. QR values
are no longer printed unless `WHATSAPP_QR_LOG_ENABLED=true`; `/qr` is protected.
Its Chromium process still runs as root with `--no-sandbox` in this transitional
release; internal authentication, isolated networking, resource limits, and
`no-new-privileges` reduce exposure but do not replace the Chromium sandbox.

## Staged WhatsApp non-root migration

WhatsApp intentionally remains root in this release because existing bind-mounted
`./data/whatsapp_auth/*:/auth` directories can have installation-specific owners.
Changing UID blindly can make the sessions unreadable and force a new QR login.

For a later maintenance window, back up both auth directories, stop one gateway,
record ownership and permissions, create a fixed gateway UID, migrate one copy,
and verify restart plus message send/receive before doing the second gateway.
Only then add `USER`/compose `user` and consider `cap_drop: [ALL]` and a read-only
root filesystem. Do not chown both live session directories as part of an
ordinary rolling deploy.
