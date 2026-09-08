# Monitoring image upgrade evidence

This is a local candidate assessment, not a production rollout authorization. The evidence directory is `.codex-tmp/remediation-final-gaps-20260907/monitoring/`. Raw vulnerability reports are retained together with CycloneDX inventories and contextual triage; no CVE is suppressed. A successful candidate scan does not change the deployed image.

## Scope and present release gates

| Component | Candidate | Observed gate |
| --- | --- | --- |
| Dozzle | Official 10.10.0, digest `27128281cc1f93b0f849f2a5fb4ef9c12a0cce1135ab3b6fede27f9867c8a1c2` | Raw 0 HIGH/CRITICAL; actual Docker observer в†’ Dozzle log stream and deployment probe passed. Three repository Compose pins updated. |
| Prometheus | 3.13.3 with Go 1.27.1 and gRPC 1.83.1 | Built candidate raw 0 HIGH/CRITICAL; all 12 persisted-data/restart/backup-rollback checks passed. Publishing and deployment remain separate. |
| Loki | 3.7.7 with Go 1.27.1, gRPC 1.83.1 and HTTP readiness helper | Raw 0 HIGH/CRITICAL; helper three behavioral tests and all 14 persisted-data/native-health/restart/backup-rollback checks passed. |
| Alloy | 1.19.2 with Go 1.27.1 and gRPC 1.83.1 | Built candidate raw zero fixable HIGH/CRITICAL; two vendor-unfixed HIGH remain explicit. All 13 actual file-position upgrade/restart/backup-rollback checks and full Docker/file consumer proof passed; risk review remains required. |
| Grafana | 12.4.10+otziv.1 with Go 1.27.1, gRPC 1.83.1, Thrift 0.24.0 and OpenSSL 3.5.8 | Derivative 12/12 storage/provisioning/secret-decryption/restart/rollback checks passed; 11,569 official asset files match. Four actionable original findings resolved. Raw two HIGH Tempo pseudo-version findings remain visible with primary fix-ancestry evidence; automatic security gate still FAIL. Active pin unchanged. |
| Tempo | 2.10.8 with Go 1.27.1, gRPC 1.83.1, Thrift 0.24.0, crypto 0.55.0 and a disclosed queue shutdown patch | Final derivative raw 0 HIGH/CRITICAL; all 14 persisted-trace/native-health/restart/backup-rollback checks passed, with every stop exit 0 and no OOM. Default pin unchanged; publishing and operational rollout remain separate. |

The two **Grafana 12.4.10** findings on the embedded Tempo pseudo-version have primary fix-ancestry evidence, not a scanner exception: installed commit `525d1bab07e0` contains both [CVE-2026-21728 fix `650eb198`](https://github.com/grafana/tempo/commit/650eb1985a0776789c8564122990f588a742356f) and [CVE-2026-28377 fix `bb8ca663`](https://github.com/grafana/tempo/commit/bb8ca663db34a0980c9758b40d918fda3b4dbec3). GitHub comparison reports `behind_by=0` with the exact fix as merge base (337 and 218 commits ahead). Release-tag comparison alone was divergent and was not used as proof. The bounded derivative fixes the other four OpenSSL/gRPC/Thrift findings with real package upgrades; raw scanner policy still fails on these two retained findings. See [the exact signed source, six-module graph change, asset parity and final proof](GRAFANA_DERIVATIVE_2026-09-07.md). Grafana 13.2.1 bundles a different older Tempo plugin commit (`87c2dc380cec`); the 12.4.10 ancestry result does not apply to it.

## Reproducible bounded derivatives

Build from the repository root, sequentially:

```sh
docker build -f infrastructure/runtime-security/builds/Prometheus.Dockerfile -t otziv-prometheus:3.13.3-security1 infrastructure/runtime-security
docker build -f infrastructure/runtime-security/builds/Loki.Dockerfile -t otziv-loki:3.7.7-security1 infrastructure/runtime-security
docker build -f infrastructure/runtime-security/builds/Alloy.Dockerfile -t otziv-alloy:1.19.2-security1 infrastructure/runtime-security
docker build -f infrastructure/runtime-security/builds/Tempo.Dockerfile -t otziv-tempo:2.10.8-security2 infrastructure/runtime-security
docker build -f infrastructure/runtime-security/builds/Grafana.Dockerfile -t otziv-grafana:12.4.10-security2 infrastructure/runtime-security
node infrastructure/runtime-security/scan.mjs image otziv-prometheus:3.13.3-security1 NEW_OUTPUT/prometheus.json
node infrastructure/runtime-security/scan.mjs image otziv-loki:3.7.7-security1 NEW_OUTPUT/loki.json
node infrastructure/runtime-security/scan.mjs image otziv-alloy:1.19.2-security1 NEW_OUTPUT/alloy.json
node infrastructure/runtime-security/scan.mjs image otziv-tempo:2.10.8-security2 NEW_OUTPUT/tempo.json
node infrastructure/runtime-security/scan.mjs image otziv-grafana:12.4.10-security2 NEW_OUTPUT/grafana.json
```

Every Dockerfile pins the exact official source commit, its archive checksum, runtime image and builder. The supported Go builder is `golang:1.27.1-bookworm@sha256:648f440f42a0958804efb24df176f806f9d353b41f1c0627f666428e40310f6b`. Builds use `GOMAXPROCS=2`, a 2 GiB Go memory target and two compiler workers. The latter is a Go target, not a claim that the complete Docker build has a hard 2 GiB memory ceiling.

Original and resulting `go.mod`/`go.sum`, module diffs, binary build info and vendor license files remain inside `/usr/share/otziv-build`. Prometheus also uses the exact official UI release archive (SHA-256 `2647eb2e0b5dbc2bb16bbcec2cea78e4b8fc36f4564f6ce6569627b47f3432bd`) and the source's own `compress_assets.sh` generator; no npm dependency update is involved. Prometheus, Loki, Alloy and Tempo use `GOWORK=off` to select their actual runtime modules. Grafana preserves its official workspace and checks all 1,534 selected module versions, allowing exactly the two requested fixes and four minimum gRPC requirements.

Loki's module guard allows only gRPC 1.82.1в†’1.83.1 and the three exact minimum dependencies required by the [official gRPC 1.83.1 module](https://github.com/grpc/grpc-go/blob/v1.83.1/go.mod): `go-spiffe/v2` 2.6.0в†’2.7.0, OTel GCP detector 1.43.0в†’1.44.0 and GoogleCloud GCP detector 1.32.0в†’1.33.0. It retains the source's `netgo`/CGO-disabled runtime build. The new readiness helper accepts only loopback HTTP and requires a real 200 response; redirects, non-200 responses, connection failures and timeouts fail.

Alloy retains the vendor's pinned CGO/systemd/UI builder, original UI lock file, production tags, checked-in generated collector and checksummed Beyla download. Only its Go distribution and gRPC requirements in the root and collector modules are updated. The source's supported `SKIP_CODE_GENERATION=1` avoids regenerating an unchanged collector while preserving the committed generated source. No integration component is disabled to reduce findings.

Tempo preserves its original AMD64 v2 and CGO-disabled build. Its three direct security changes require 15 exact transitive module updates, all checked against an expected module file before and after compilation. A real shutdown failure required the separate `tempo-queue-shutdown.patch`: it drains already admitted requests, rejects new admission and removes empty queues even after the periodic cleanup has stopped. The full upstream queue package plus four added regressions pass; the added cases also pass five runs with Go race detection. See [the exact source, causal failure and patch evidence](TEMPO_DERIVATIVE_2026-09-07.md). No grace deadline, data schema or common runtime harness was changed.

## Actual persistent-data and configuration drill

```sh
node infrastructure/runtime-security/monitoring-upgrade.mjs prometheus IMMUTABLE_CANDIDATE NEW_OUTPUT/prometheus-upgrade
node infrastructure/runtime-security/monitoring-upgrade.mjs grafana IMMUTABLE_CANDIDATE NEW_OUTPUT/grafana-upgrade
node infrastructure/runtime-security/monitoring-upgrade.mjs loki IMMUTABLE_CANDIDATE NEW_OUTPUT/loki-upgrade
node infrastructure/runtime-security/monitoring-upgrade.mjs tempo IMMUTABLE_CANDIDATE NEW_OUTPUT/tempo-upgrade
node infrastructure/runtime-security/monitoring-upgrade.mjs alloy IMMUTABLE_CANDIDATE NEW_OUTPUT/alloy-upgrade
```

`IMMUTABLE_CANDIDATE` is a pulled `repository@sha256:...` or an existing local `sha256:...`, never a mutable tag. The source image is read from the current production Compose file. The tool rejects a nonempty output directory and nonlocal Docker endpoints. Each run creates only its own labelled containers, internal network and data/backup/rollback volumes; it never opens a deployment volume or publishes ports. Configuration copies and harness hashes are frozen in the evidence. All allocated resources require matching ownership labels before cleanup.

The drill seeds actual source data, verifies it through the real service, stops the source with a deadline and rejects exit codes other than 0/143 or OOM. It captures a file manifest (including content hashes and ownership), copies the stopped volume, and verifies the backup manifest. The candidate opens that same source volume, reads previous data, writes new data, restarts and reads both. Rollback creates a fresh volume from the pre-upgrade backup and runs the previous image; it verifies that previous data exists and new candidate data is absent. No in-place downgrade is claimed.

Prometheus uses actual scrape/query responses and TSDB/WAL. Grafana preserves dashboards and provisioned datasource IDs and proves encrypted datasource secret decryption with a real authenticated proxy request, using the same secret key. Loki pushes and queries real log entries using the existing v13 TSDB schema; no schema history is rewritten. Tempo ingests OTLP traces and reads them before/after upgrade and rollback. The only Tempo configuration adjustment makes the OTLP gRPC/HTTP bind addresses explicit (`0.0.0.0:4317/4318`), because the candidate's localhost default rejected internal collector traffic; these ports are not published.

The report distinguishes the old Compose health command from a candidate's proposed `CMD` image healthcheck. A candidate healthcheck must execute successfully and produce actual Docker `healthy` state. The corresponding production Compose command must be updated atomically with any approved candidate pin; this tool does not deploy that change.

Alloy separately preserves its actual on-disk file positions. A bounded Snappy-decoding sink counts synthetic lines, the candidate and restart must not replay acknowledged bytes, and backup rollback restores the original source file plus positions before reading a new append. This is a healthy-path cursor compatibility test, not an exactly-once guarantee under crashes. The complete Docker/file production configuration is tested separately by the real consumer smoke.

This is **synthetic seeded persistent data**, not a copy of production historical telemetry. Production-size migration time, older historical schema periods not present in this fixture, plugins outside current provisioning, retention changes and external cloud storage are not proved. Before a real major upgrade, capture and test a backup of the actual relevant volumes and secret key, reserve the outage/rollback window, publish the reviewed derivative under an immutable digest, and run the same checks against that release artifact.

## Docker consumers

```sh
node infrastructure/docker-observer/consumer-smoke.mjs otziv-observer-rollout:20260907
```

By default this resolves the actual production Compose pins. Candidate-only overrides are `OTZIV_CONSUMER_PROOF_DOZZLE_IMAGE`, `OTZIV_CONSUMER_PROOF_ALLOY_IMAGE` and `OTZIV_CONSUMER_PROOF_LOKI_IMAGE`; each requires an immutable digest. The smoke preserves every current Alloy configuration component, adding only a filter for owned synthetic containers and a second test sink. It checks actual Dozzle SSE log messages, Docker observer mutation/secret-field denials, Snappy push decoding, Loki query results, all four backend file log sources, and both production deployment probes. The source config, fixture overlay, image references and shared UTF-8 process helper hash are printed in the evidence. No unrelated host logs or real application files are read.


## Opt-in deployment and CI

`compose.monitoring-security-upgrade.yaml` overrides only Prometheus, Loki, Alloy, Tempo and Grafana. It requires all five `OTZIV_{PROMETHEUS,LOKI,ALLOY,TEMPO,GRAFANA}_SECURITY_IMAGE` environment variables. Set them to the **published, verified** `registry/repository@sha256:...` references, then validate before using the override:

```sh
node infrastructure/runtime-security/monitoring-release.mjs
docker compose --env-file YOUR_APPROVED_ENV_FILE -f docker-compose.yaml -f compose.monitoring-security-upgrade.yaml config --quiet
```

The validator rejects missing values, mutable tags, bare local-only IDs and whitespace without printing unrelated environment variables. Run it with the same exported image variables used by Compose. The local model test used reserved `registry.example.invalid` references and `config --quiet`; it proves interpolation and health-command shape, **not publication or a deployed release**. Local built image IDs are recorded in `MONITORING_CANDIDATES_2026-09-07.json`. Do not substitute them for a registry distribution digest without publishing and verifying the artifact.

The `monitoring-candidates` CI matrix builds all five actual Dockerfiles, builds the isolated runner, pulls the current source pin, executes real storage/cursor rollback checks and scans the resulting complete image. The Alloy row also runs the real Docker/file consumer smoke. Raw scans, SBOMs, build logs and configuration/rollback evidence are artifacts. The existing required **Upstream image security gate** now requires both default upstream scans and the five candidate results, rejecting failed, skipped or cancelled work. Its required check name is unchanged. This does not waive the current defaults' findings or Grafana's unresolved candidate findings.

The four-image checkpoint retains its 24 monitoring/observer/process unit tests and 53 storage checks. The fifth-image extension adds Grafana 12/12 actual storage checks, asset parity and a complete raw scan, bringing the five candidates to 65 storage/cursor/trace checks with owned-resource cleanup. Its affected validation passes 19 runtime-security/process tests, 11 aggregate/branch-policy tests, pinned Actionlint and actual Compose model validation with all five missing-image refusals. The prior four candidate records and their shared harness hashes are unchanged; `grafana-derivative/monitoring-registry-before-grafana.json` preserves the previous registry. Both default new-Dozzle and combined built-Alloy/Loki real consumer runs passed. These are local results; a remote GitHub run and branch-policy activation are independently verifiable operational gates.

Alloy's remaining raw HIGH findings are `CVE-2026-41567` and `CVE-2026-42306` on `github.com/docker/docker v28.5.2+incompatible`, with no vendor fix in the scan feed. Its Docker access remains behind the read-only observer boundary, and the real smoke verifies mutation denial; no finding is hidden or deemed accepted solely by that constraint. A release owner must review this residual risk. Grafana 13.2.1 remains an archived rejected alternative with 159 HIGH and three CRITICAL findings. The supported 12.4.10 derivative is the fifth review candidate; its two raw Tempo pseudo-version findings retain primary fix-ancestry evidence, while the unchanged CI scan intentionally remains RED. The unmodified Tempo vendor candidate remains archived with its 11 fixable findings; the new local derivative resolves those and the observed shutdown defect. No component removal or suppression was used.
