# Grafana 12.4.10 derivative evidence

This is a local review candidate, not a published release or production deployment. The automatic raw vulnerability gate remains **FAIL**: two HIGH findings remain visible. No scanner exclusion, severity change or acceptance is introduced.

## Source and narrowly bounded changes

Grafana 12.4 has official patch support through [May 24, 2027](https://grafana.com/docs/grafana/latest/upgrade-guide/when-to-upgrade/). The exact [12.4.10 source commit](https://github.com/grafana/grafana/commit/81407c71e96e8351b4600164c1b4d30c8baf41d6) is `81407c71e96e8351b4600164c1b4d30c8baf41d6`; GitHub's commit verification reports `verified=true`, `reason=valid`. Its source archive SHA-256 is `d70b8c78535f4f1b49c72f23129f4a77f4a066627f4c90dd9d71cfb001ba97bc`. The signed source identity and archive checksum are separate from signing or publishing the derivative image, neither of which occurred.

`builds/Grafana.Dockerfile` retains the official runtime `grafana/grafana@sha256:27e80e0f4fa3d423bcbbbb3418f2a6475833f94a2a88b6f2547c74830ce4286e`. All three original entrypoints are rebuilt using the source's `make build-go`, Go 1.27.1, original CGO-disabled/AMD64-v1/trimpath settings and no extra feature tags. SQLite remains `modernc.org/sqlite v1.52.0`. The runtime keeps official frontend files, bundled plugins, configuration, CA bundle, entrypoint and UID 472. No functionality is removed to reduce scan findings.

The original workspace's selected versions match every comparable dependency version embedded in the official binary. Of 1,534 selected modules, exactly six versions change; no module is added or removed:

| Module | Original | Derivative |
| --- | --- | --- |
| `google.golang.org/grpc` | 1.82.1 | 1.83.1 |
| `github.com/apache/thrift` | 0.23.1-0.20260429145742-d2acd3c49e58 | 0.24.0 |
| `cel.dev/expr` | 0.25.1 | 0.25.2 |
| `github.com/spiffe/go-spiffe/v2` | 2.6.0 | 2.7.0 |
| `go.opentelemetry.io/contrib/detectors/gcp` | 1.43.0 | 1.44.0 |
| `github.com/GoogleCloudPlatform/opentelemetry-operations-go/detectors/gcp` | 1.32.0 | 1.33.0 |

The last four are minimum requirements of gRPC 1.83.1. Strict comparison rejects other module/workspace changes before compilation and verifies the selected graph again afterward. The workspace is intentionally retained; this is not a `GOWORK=off` build. Alpine `libcrypto3` and `libssl3` move from 3.5.7-r0 to the exact 3.5.8-r0 packages, without changing CA or TLS policy. The binary version is `12.4.10+otziv.1`: build metadata preserves release-version ordering instead of marking the build as a prerelease.

## Exact local proof

Local image identity: `sha256:3a438cefccf81756412057b54c62ed6b5552fd01fc941c3246baf05122ef8ca6` (`otziv-grafana:12.4.10-security2`). This local identity is not a published registry reference.

Evidence directory: `.codex-tmp/remediation-final-gaps-20260907/monitoring/grafana-derivative/`.

- `build-provenance-v2.json`: source, recipe and binary build-information hashes; all three binaries actually use Go 1.27.1, CGO 0 and AMD64 v1.
- `asset-preservation.json`: 11,569 official UI/plugin/configuration/CA files match byte for byte. Manifest SHA-256: `e6e69cc6af8a28d083dc3e2dedc40a5350ab9e43f8d3811d7274fd86b7548d0b`. Runtime identity/environment/entrypoint also match.
- `upgrade-v2/proof.json`: unchanged persistent-data harness, 12/12 checks passed, owned-resource cleanup passed. It proves source data and provisioning survive upgrade/restart, a real authenticated datasource proxy request decrypts the stored secret, and rollback restores only the pre-upgrade backup. Only synthetic data and an internal network were used.
- `vulnerabilities-v2.json`, its CycloneDX SBOM and triage retain all five scanned targets. The four actionable original OpenSSL/gRPC/Thrift findings are gone. Raw results: **2 HIGH, 0 CRITICAL; 2 vendor-fixed findings; automatic gate FAIL**.

Both remaining findings are on unchanged `github.com/grafana/tempo v1.5.1-0.20260427112133-525d1bab07e0`: `CVE-2026-21728` and `CVE-2026-28377`. The installed commit contains [fix 650eb198](https://github.com/grafana/tempo/compare/650eb1985a0776789c8564122990f588a742356f...525d1bab07e0) and [fix bb8ca663](https://github.com/grafana/tempo/compare/bb8ca663db34a0980c9758b40d918fda3b4dbec3...525d1bab07e0), respectively. Both comparisons report `behind_by=0` and the fix itself as merge base; `tempo-ancestry-reference.json` preserves hashes of those primary-source responses. This supports a pseudo-version false-match review, but does not alter the raw report or make the automatic gate pass. Tempo is not upgraded wholesale or relabelled to conceal its installed version.

## Reproduce without deployment

```sh
docker build -f infrastructure/runtime-security/builds/Grafana.Dockerfile -t otziv-grafana:12.4.10-security2 infrastructure/runtime-security
node infrastructure/runtime-security/monitoring-upgrade.mjs grafana IMMUTABLE_LOCAL_IMAGE_ID NEW_OUTPUT/storage
node infrastructure/runtime-security/scan.mjs image IMMUTABLE_LOCAL_IMAGE_ID NEW_OUTPUT/vulnerabilities.json
```

Use a fresh output directory and the actual newly built image identity. The final scan is expected to exit nonzero while those raw findings remain in the feed. Source/derived module and build-information records are also retained inside `/usr/share/otziv-build`.

The fifth CI candidate row builds and tests this real image and applies the unchanged blocking raw scan. The opt-in overlay requires a separately published immutable Grafana reference along with the other four reviewed components; adding the row is not release acceptance. Production historical data/telemetry scale, external plugins, every Grafana feature and production secret-key recovery are not proved by this bounded fixture. Publishing, provenance verification, residual-finding review and operational rollout remain separate gates.
