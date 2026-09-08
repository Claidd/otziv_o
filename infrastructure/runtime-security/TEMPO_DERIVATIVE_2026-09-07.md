# Tempo 2.10.8 security derivative

This bounded follow-up retains official Tempo 2.10.8, its runtime filesystem, UID/GID 10001, AMD64 v2 target, existing data schema and configuration. It updates the Go toolchain and three security dependencies and applies the separately reviewed request-queue shutdown correction below. It does not remove a backend or switch a storage format.

The [official signed release](https://github.com/grafana/tempo/releases/tag/v2.10.8) resolves to commit `f0f3ed59197bfe9f54f3b0f8015ccca112f9e544`; the source archive checksum is `c354a7495843161a41ad75d96dd4e8f17aabc9a5f439060cf974a57b4ef2ff85`. The unchanged vendor runtime is `grafana/tempo@sha256:b18e2bf60dd852ae891d721f906c15eb6af558c0e785fe82c019da4f2006f071`.

The builder is pinned Go 1.27.1. Direct security updates are gRPC 1.83.1, Apache Thrift 0.24.0 and `golang.org/x/crypto` 0.55.0. Exact required transitive updates are enforced with a checked expected `go.mod` before and after compilation. These come from the official [gRPC module](https://github.com/grpc/grpc-go/blob/v1.83.1/go.mod), [crypto module](https://github.com/golang/crypto/blob/v0.55.0/go.mod), [text module](https://github.com/golang/text/blob/v0.41.0/go.mod) and [tools module](https://github.com/golang/tools/blob/v0.48.0/go.mod). There is no `go get -u` or vulnerability suppression. Original/resulting modules, sums, license and binary build info remain in `/usr/share/otziv-build`.

```sh
docker build -f infrastructure/runtime-security/builds/Tempo.Dockerfile -t otziv-tempo:2.10.8-security2 infrastructure/runtime-security
node infrastructure/runtime-security/scan.mjs image otziv-tempo:2.10.8-security2 NEW_OUTPUT/vulnerabilities.json
node infrastructure/runtime-security/monitoring-upgrade.mjs tempo IMMUTABLE_BUILT_IMAGE_ID NEW_OUTPUT/trace-upgrade
```

The trace proof uses the existing isolated real-service harness: seed the old Tempo, flush actual Parquet/index/bloom files and WAL, perform a bounded graceful stop, capture and verify a full backup manifest, open the same volume with the candidate, query previous traces, ingest new traces, restart, query both, restore the backup into a fresh volume and verify rollback with the old version. No deployed volume is used. These synthetic traces prove format compatibility for the exercised schema; production historical data and production-volume migration timing are separate operational requirements.

The shared loopback-only HTTP readiness binary runs with the same patched Go toolchain. It requires actual HTTP 200, rejects redirects/non-200/errors/timeouts and has three behavioral tests. The image's `CMD` healthcheck requests `http://127.0.0.1:3200/ready`; the trace proof also requires Docker to report `healthy`. This replaces the unavailable shell/wget command when the derivative is eventually selected by an opt-in release override.

Resource bounds: one build, two Go workers, `GOMAXPROCS=2`, `GOMEMLIMIT=2GiB`; the Go memory target is not a hard bound for the whole Docker builder. The scanner has a hard 2 GiB container limit and the trace service a 768 MiB limit. The build and tests are local only; publishing and deployment require their own immutable release artifact and operational gates.

Evidence is retained under `.codex-tmp/remediation-final-gaps-20260907/monitoring/tempo-derivative/`, including failed attempts. Final results are added only after the built image passes the scan and actual trace/health/rollback checks.

## Request-queue shutdown correction

The first derivative's complete image scan had zero HIGH/CRITICAL findings, but the actual restart drill failed: after successfully querying original and new traces, stopping the restarted process exceeded the unchanged 30-second deadline and Docker returned exit 137 with `OOMKilled=false`. This is retained in `trace-upgrade-v1/proof.json` and `restart.log`; it is not a passing rollback proof.

In the exact upstream `modules/frontend/queue/queue.go`, stopping waits for the tenant-queue map to become empty after its periodic cleanup service has already stopped. Dequeuing the last request leaves an empty map entry and does not notify shutdown. A longer grace period cannot restore that stopped cleanup service. The separate `builds/tempo-queue-shutdown.patch` removes empty entries while draining, wakes the draining waiter after dequeue, rejects new admissions during drain, and rechecks admission when a new tenant's producer upgrades its read lock. Already admitted requests remain available to queriers; the patch does not discard pending requests or claim completion when consumers are absent.

The original queue file SHA-256 is checked before `git apply --check`; source commit and before/after hashes, exact patch, formatted regression source and test logs remain inside the image. The original file hash is `5978bc9e1d464d9d040b995f94e180bb8043c8542de813eb6ce8103f8630364a`. The module changes retain their separate exact-diff guard. This is a disclosed local source patch, not an upstream release or CVE exclusion.

`queue-red.log` records three deterministic failures on the unmodified upstream queue: empty-queue shutdown, acceptance during drain, and admission after lock upgrade. The fourth concurrent test is a positive control. The final build runs the full upstream queue test package with four added behavioral regressions and repeats the added cases five times with Go's race detector. These include waiting for pending work, rejection of both existing/new-tenant admissions, delivery of every admitted synthetic job exactly once, and a post-lock admission check. Test-only cleanup releases intentionally stuck RED waiters; production code never uses that cleanup path. The real service harness and its shutdown deadline are unchanged.

## Final local result

Final image `otziv-tempo:2.10.8-security2` has local ID `sha256:dee455ed0c802c8e2e42ff7ee843effd457cf8ac312893c210800ade8e201102` (runtime config `b61f42d76336113019250edcbbaaf1438b6b7c1afb3c7c9f70e1f3cce35bcd7a`). It is not a published registry reference. `vulnerabilities-v2.json` reports three analyzed artifacts, zero HIGH and zero CRITICAL, with a complete CycloneDX inventory and no exclusions. `trace-upgrade-v2/proof.json` records all 14 checks and owned-resource cleanup passing at 2026-09-07T18:22:50.480Z; source, candidate, restart and rollback all exited 0 without OOM.

`provenance-v2/queue-tests.txt` records nine top-level tests plus ten subtests; `queue-race-tests.txt` records 20 successful executions of the four added cases. The three HTTP helper tests also passed. The patched and formatted queue source hash is `db8c6eaaf2868c2f485c51a883466129c10a6c924077e3fed7db51b72f52b578`. `build-v5-queue-fix.log` is the successful final build; previous failed logs are retained.

The opt-in release overlay replaces Tempo's unavailable shell command with the actual HTTP probe and explicitly sets its stop grace to the same 30-second bound exercised here. The mandatory fourth immutable reference is `OTZIV_TEMPO_SECURITY_IMAGE`. The CI matrix builds this Dockerfile, including queue/race regressions, then runs the unchanged real trace harness and whole-image scanner. The historical three-component registry and its 39 proof hashes remain intact; the current registry adds these 14 checks. No production deployment or historical-volume proof is implied.
