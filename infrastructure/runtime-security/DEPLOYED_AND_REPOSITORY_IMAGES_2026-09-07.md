# P06: deployed images, repository pins and remaining upgrades

Observed fleet identity and exact-image reports are in [the production registry](PUBLISHED_FLEET_2026-09-07.md). This comparison reads current active Compose/Dockerfiles; historical `diagnostics` snapshots are excluded. An unchanged upstream pin is an implementation/configuration gap when its exact scan has actionable findings. It is not merely a deployment backlog.

| Component | Production → current repository | Remaining work |
|---|---|---|
| Backend | Published `fc191ac` config `dd0585…` has 10 fixed HIGH/CRITICAL. Current `backend/pom.xml` contains the reviewed Netty/Tomcat/HttpCore overrides; the rebuilt application previously passed its exact image scan | Retain the final candidate's own report and runtime proof, then perform a reviewed release. Earlier candidate reports do not certify a different final digest |
| Web | Published config `c67825…` has 34 fixed HIGH/CRITICAL. `frontend/Dockerfile` adds `apk upgrade --no-cache` to the pinned nginx base; the rebuilt web image previously had zero findings | Scan the exact final rebuild and release it. `compose.yaml` development nginx still directly uses the old nginx base; its result is not replaced by the patched application web image |
| WhatsApp | Two deployed instances use old config `d3109b…`: 28 fixed and 103 unfixed HIGH/CRITICAL. Current `Dockerfile.whatsapp` uses the refreshed Node22 base, OS updates, removed build package managers and reviewed Puppeteer25 adapter | Current candidate still has unresolved vendor findings; review and re-scan the final image, then preserve operation/auth volumes during the reviewed rollout. npm audit0 does not close OS findings |
| MySQL | **Same** `mysql@sha256:8b879a…` in production, `docker-compose.yaml:49`, `compose.prod-local.yaml:48`, `compose.yaml:32`, recovery fixture and CI. Its package inventory identifies `mysql-community-server-minimal=9.0.0`, Oracle Linux9.4. Exact scan: **165 fixed HIGH/CRITICAL** | A new DB image and real upgrade/rollback rehearsal are **not yet implemented**. Candidate below is only verified registry metadata until its scan and rehearsal complete |
| PostgreSQL | **Same** `postgres@sha256:a426e4…` in production and all three Compose files | Review its exact fleet scan; select and test a maintained image, including Keycloak restore and restart. No replacement has been prepared by this inventory |
| Prometheus / Grafana / Alloy / Dozzle / Loki / Tempo | All six production RepoDigests remain unchanged in current production/local Compose | Each requires its own patched image choice, configuration/storage compatibility check, exact-image scan and rollout. Consumer smoke proves the observer protocol, not security of these unchanged vendor images |
| Keycloak | Old `488363…` remains the default Compose image. A separate `infrastructure/keycloak/security-generation/Dockerfile` provides the upgraded custom issuer candidate selected through `OTZIV_KEYCLOAK_IMAGE` | Candidate provider/restore/scan acceptance and explicit deployment selection are separate from the old default's fleet findings. Do not treat an unselected image as deployed |
| Worker / observer / signals publisher | Not observed on this production host. Current Dockerfiles and built-image tests exist | Deployment remains separate. Available candidate reports contain vendor findings; new code and readiness smokes do not grant risk acceptance |
| MinIO / auxiliary containers | MinIO was not observed on this host; local Compose/recovery retain `RELEASE.2025-04-22…`. phpMyAdmin/certbot also have checked-in pins outside the observed running fleet | Their absence from the 13-container fleet is not a successful vulnerability scan. Scan those exact images before using them in a release; this inventory does not claim that scope is covered |

The CI `integration-images` matrix scans six **built** images (worker, WhatsApp, observer, backend, web, publisher); issuer has its own job. Root added a separate upstream Compose inventory/scan gate covering16 checked-in images, with20 expected required checks. A successful recovery fixture does not substitute for this scan. The new gate exposes vulnerable checked-in pins; adding it does not make those pins clean. P06 cannot be declared fully implemented while vulnerable pins and missing upgrade rehearsals remain.

## MySQL upgrade candidate and bounded rehearsal

Choose the **9.7 LTS** line for the rehearsal. Oracle's upgrade matrix permits an Innovation release to advance to its next LTS line and recommends Upgrade Checker first; thus 9.0→9.7 is the proposed supported path, subject to the actual source checks. This is not a downgrade to8.4. [Official upgrade paths](https://dev.mysql.com/doc/refman/9.7/en/upgrade-paths.html), [release model](https://dev.mysql.com/doc/refman/9.7/en/mysql-releases.html).

Oracle identifies **9.7.3, 2026-08-18**, as a Docker-only Critical Security Patch Update. Docker Official Images still listed9.7.2 when checked; that different distribution is not silently substituted. [Oracle release note](https://dev.mysql.com/doc/relnotes/mysql/9.7/en/news-9-7-3.html), [Docker Official Images metadata](https://raw.githubusercontent.com/docker-library/official-images/master/library/mysql).

Read-only registry metadata confirmed the official Oracle Community candidate exists:

```text
container-registry.oracle.com/mysql/community-server@sha256:bf955135c21e2c1c153417407fbbbb8efa786f487781b1fb6baaf7aba17d4340
image configuration: sha256:3fa754b144aeb7be887e6c614522aa06a2bbcd8ac2f3b67414884fa79000cdab
platform: linux/amd64
created: 2026-08-19T17:59:39.172598182Z
```

Evidence: `.codex-tmp/finalization-20260907/mysql-9.7.3-candidate-{manifest.txt,manifest.json,config.json}`. Oracle documents the Community image repository and its container contract separately from Docker Official Images. [Official container deployment](https://dev.mysql.com/doc/refman/9.7/en/docker-mysql-getting-started.html).

The **exact candidate scan completed15:25:52 UTC and fails the unchanged gate**: 5HIGH with fixes, 0CRITICAL, 0unfixedHIGH/CRITICAL. Two findings affect `sqlite-libs`3.34.1-10.el9_8 (fixed-11); three affect bundled mysqlsh Python `cryptography`46.0.7 (reported fixes48.0.1/49.0.0/50.0.0). Full raw/SBOM/triage/log hashes are in `.codex-tmp/finalization-20260907/mysql-9.7.3-candidate-scan-summary.json`. This is a large reduction from the old image, **not release approval**. No reachability exemption is granted to the bundled maintenance tool. The isolated upgrade rehearsal remains independent; no Compose/Testcontainers pin was changed by this scan.

The next bounded implementation needs:

1. Scan the immutable candidate above, retaining all findings and SBOM. Review the vendor's container entrypoint, configuration mount, password-file support and non-root permissions against existing scripts.
2. Use a disposable isolated clone of the freshly restored **local** database; preserve the source read-only and keep all outbound providers disabled. Record source engine/image, schema/Flyway, flags and complete backup hashes.
3. Run MySQL Shell Upgrade Checker for9.7 against that clone; resolve actual incompatible tables/options before upgrading. Rehearse the intended logical restore or in-place upgrade on a separate owned copy with an unmodified source backup.
4. Verify engine version, all Flyway checksums/current schema, generated/unique indexes, users/authentication, triggers/events/routines, row/invariant checks and restart persistence. Run actual application startup, financial/queue/session transaction cases and the CTE/query-budget benchmark against the upgraded DB.
5. Rehearse restoring the previous complete database and matching app/issuer/object-storage/secrets restore point into a separate old-version instance. An older binary must not be opened on an upgraded data directory; the earlier app's V298 schema incompatibility remains a separate rollback constraint.
6. Only after proof, coordinate a narrow pin update across active Compose, Testcontainers, recovery/maintenance tools and CI. Retain both exact images and reports. Production promotion remains a separately reviewed action.

No owner assignment, acceptance or expiry is approved by this document. Maintainer-role suggestions in the CVE registry are routing proposals, not authorization to ship unresolved findings.
