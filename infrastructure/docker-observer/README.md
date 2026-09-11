# Docker observer boundary

The proxy exposes a small GET/HEAD Docker discovery/logs/stats API to Dozzle and Alloy. It denies mutation methods, exec, archive/file access, volumes and unknown endpoints before opening the socket. Container inspect omits environment, commands, mounts and health-command output; labels are allowlisted. Read-only socket mounting alone cannot provide these HTTP authorization guarantees.

Only the observer receives the Docker socket. Consumers connect on the private `docker_observer_net`; it must have no published listener or unrelated application clients. This is a highly trusted observer: allowed logs, events and statistics can themselves contain sensitive application data. JSON sanitization cannot redact arbitrary log bodies. Keep log hygiene and dashboard authentication at the application/access layers.

```sh
node --test infrastructure/docker-observer/*.test.js
docker build -t otziv-observer-ci infrastructure/docker-observer
node infrastructure/docker-observer/consumer-smoke.mjs otziv-observer-ci
```

The consumer smoke reads the exact pinned Dozzle and Alloy images from production Compose, then uses its own labelled containers and an isolated internal network. A fixture emits a unique synthetic marker; the real Dozzle SSE endpoint must stream it and the real Alloy Docker source must forward it to a local Loki-protocol sink. The sink decodes a bounded raw Snappy body. The test also proves secret-field redaction and rejection of mutation/path probes. No production Compose stack, real message or external notification is used. Cleanup validates ownership before removing its containers/network.

On 2026-09-07 the pinned Dozzle v10.6.14 and Alloy passed this real consumer check against both the original and refreshed observer image. Dozzle log levels must be specified in the fixture request, as in its browser UI; an empty level set is not evidence of a proxy failure. Consumers do not need broader Docker write access to pass the test.

Re-run the smoke after changing the proxy or either pinned consumer. A rejected newly required endpoint must be reviewed individually; do not resolve compatibility failures by exposing a general Docker socket proxy. Roll back to the last tested observer/consumer digests while retaining the private network and socket restriction.

The stock production deploy now sources `infrastructure/scripts/prod/rollout-docker-observer.sh` from the release bundle. The observer is built and published locally with the application. Its immutable digest and measured layers are included in the VPS disk preflight. Rollout pulls that image, recreates it and waits for its health before touching consumers. Dozzle is replaced and a uniquely labelled disposable container must observe its own synthetic message in the actual SSE stream. Alloy is replaced only after that proof; a new probe must then find its own synthetic message in actual Loki query results. Probes have no Docker socket, secrets, privileged capabilities or host mounts; they run on the existing private application network and remove themselves. Failure stops the rollout before later consumers/orphan cleanup. Ordinary Compose starts also have explicit healthy-proxy dependencies.

`node infrastructure/docker-observer/deployment-contract.mjs IMAGE` executes the real sourced shell owner in network-disabled containers with recording deployment ports. Five cases cover success and failure before/after proxy readiness and each consumer's log-flow proof. The existing consumer smoke separately exercises the real pinned Dozzle/Alloy images. These disposable checks do not claim that production has been deployed or that its log flow has been observed.
