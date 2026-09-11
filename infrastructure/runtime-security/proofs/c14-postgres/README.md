# C14 PostgreSQL: Debian locale continuity and exact source remediation

This candidate preserves the PostgreSQL 17 data format and the Debian/glibc locale used by the existing Keycloak store. It is not the rejected C12 Alpine candidate. The latter changed ordering/case behavior under musl despite the same textual locale name; its evidence remains in `../c12-postgres-alpine/`.

Tested local image: `otziv-c14-postgres:source-runtime-v6`, OCI manifest `sha256:ebf646d4a2ed114de7138af57159508770872d10b35ce56f3f5a9193c87f719e`, config `sha256:22ce4f32257efd2935cd254d2bc197d71029c8c64e1f1d7c9c3e6204732c75e3`. Build context: `../../builds/postgres-c14/`. The main release coordinator independently passed the actual Keycloak protocol/generation harness against this exact local image. Publication, anonymous digest verification and VPS activation remain separate release gates.

## Construction and maintenance contract

The pinned official PostgreSQL 17.11 Debian trixie image supplies glibc, the original locale archive, TLS/GSS/LDAP/PAM/SELinux libraries, UID/GID and entrypoint. A separate build stage compiles verified PostgreSQL 17.11, libxml2 2.15.4, libxslt 1.1.45 and gzip 1.14 with the two recorded GNU security fixes. Runtime `.deb` records use real upstream versions and source names; gzip records the explicit revision `1.14+otziv.cve202641992.1`. PostgreSQL does not pretend to have a Debian/PGDG packaging revision.

The final immutable rootfs imports 53 Debian components with explicit per-file ownership and preserves their original package/version records. The four rebuilt components are installed as actual `.deb` artifacts. `runtime/upstream.cdx.json` supplements the scanner's Debian package view with upstream source URLs, SHA-256, generic PURLs/CPEs, patch provenance and artifact hashes. No pre-existing image package database was erased. Partial imports are explicit in `runtime/debian-components.json`; this image is maintained by rebuilding it, not by running APT inside it.

All originally available PostgreSQL extensions remain available. SQL/XML, ICU, OpenSSL, GSS, LDAP, PAM, SELinux, UUID, lz4, zstd, EXSLT crypto and timezone data are retained. The agreed optional differences are `pg_jit_available() = false` and no PostgreSQL systemd notification integration. LLVM would reintroduce the obsolete libxml2 ABI. No compatibility symlink is used: PostgreSQL and libxslt are compiled against the actual new XML ABI. No performance equivalence is claimed.

The official container's fresh-cluster `listen_addresses = '*'` sample setting is retained. This affects only initialization of a new cluster; the build never modifies a data volume. The unchanged entrypoint remains responsible for authentication configuration. PostgreSQL still requires the caller's network isolation and password configuration.

## Results

| Check | Result | Evidence |
| --- | --- | --- |
| Official PostgreSQL core regression | 225/225 pass | `upstream-tests/postgresql-check.log.gz` |
| Official contrib/xml2 regression | 1/1 pass | `upstream-tests/postgresql-xml2-check.log.gz` |
| libxml2 upstream `make check` | Pass, including 2,229 fuzz seed inputs | `upstream-tests/libxml2-check.log.gz` |
| libxslt upstream `make check`, including EXSLT crypto | 750 tests, no errors | `upstream-tests/libxslt-check.log.gz` |
| gzip upstream `make check` | 29 pass, 1 upstream skip, 0 failure/error | `upstream-tests/gzip-check.log.gz` |
| Exact gzip initializer state control | Original returns 42, fixed returns 0 | `upstream-tests/gzip-patch-result.json` |
| Same-volume minor upgrade, restart and logical rollback | 21/21 pass | `continuity.json` |
| Isolated bridge TCP/password/restart contract | 5/5 pass | `tcp.json` |
| Actual Keycloak login/refresh, nested OTP flow and durable issuer generation | 20/20 pass, independently run by release coordinator | `issuer-generation.json` |
| Same database Trivy comparison, all four custom packages detected | Pass | `paired-scan.json`, `raw/*.json.gz` |
| Exact runtime adjudication causal suite | 12/12 pass, no skip | `../../postgres-c14-adjudication.test.mjs` |

The continuity fixture exercises 1,000 relational/JSON rows, constraints, indexes, identity sequences, privileges, XML/xpath and malformed XML rejection. All 41 Unicode ordering/case controls and 820 equality comparisons match the source UTF-8 libc `en_US.utf8`, actual collation version 2.41. It verifies both the pre-upgrade backup and a new 17.11 backup on separate fresh 17.10 volumes, including a row written after upgrade. It does not demonstrate an unsupported binary downgrade on a modified volume.

TCP acceptance runs a genuine separate client container on a fresh internal bridge: final PID 1 must be PostgreSQL, the correct password succeeds, an incorrect password is rejected, a transaction commits, and the committed row survives restart. No host port is published. All disposable resources carry a random owner label and are checked before cleanup; existing stack names and volumes are never opened.

The gzip regression calls the exact source initializer body against valid, in-bounds arrays. It proves the missing state reset and the effect of the two upstream changes without executing an exploit payload. The complete gzip upstream suite runs on the newly compiled binary separately.

## Scanner decisions, without deleting raw findings

Pinned Trivy 0.74.0 scans exported actual images; the candidate uses the same frozen vulnerability database as the source. The genuine source PostgreSQL 17.10 is detected with the advisories fixed by 17.11, including CVE-2026-14662 and CVE-2026-6464. Source raw result: 159 HIGH + 14 CRITICAL. Final candidate raw result: 26 HIGH + 1 CRITICAL. All records, packages and severities are preserved losslessly in `raw/`.

| Exact candidate rows | Reason supported by retained primary evidence |
| --- | --- |
| PostgreSQL 17.11: 27 rows, including 17 HIGH | Upstream release is fixed. Debian's `17.11-0+deb13u1` comparison does not describe the unmodified upstream source build. CVE-2026-6473 was already fixed in 17.10; it is not used as the old-version vulnerable control. |
| libxml2 2.15.4: 2 rows, 1 HIGH + 1 CRITICAL | Both upstream fixes are ancestors of the immutable 2.15.4 tag; the release's actual `parser.c` and `valid.c` match that tag byte for byte. |
| gzip explicit security revision: 1 HIGH | The original 1.14 archive is still affected. Both published GNU fixes are applied with exact checksums; source control fails before and passes after. This is a remediation, not a version-only exemption. |
| libsystemd0: 1 HIGH | Advisory concerns the absent systemd-homed/homework implementation. The shared library imported for PAM remains inventoried. |
| libtinfo6 and ncurses-base: 2 HIGH | Advisory identifies the absent infocmp CLI's `analyze_string`; the terminal library/data remain inventoried. |
| libuuid1: 4 HIGH | Advisories identify absent mount/nsenter/libmount implementations. libuuid is preserved as a PostgreSQL dependency. |

`../../postgres-c14-adjudication.mjs` verifies the stopped image export without executing its binaries. It binds report and inspected rootfs, the container execution contract, all path names, 1,358 executable/library/script/configuration payload records, actual source/package/revision metadata and GNU patch evidence. Rebuilt config IDs may change only while these facts remain identical. Unknown CVEs or package versions, changed source/binary hashes, additional implementations, incorrect report identity, missing coverage, ambiguous duplicates or expiry retain the gate. The fixed source/absent implementation review expires on 2027-01-01. The accepted set accounts for all 27 raw HIGH/CRITICAL rows; effective counts are zero only after this complete verification. No residual risk was accepted.

Primary references: [PostgreSQL 17.11 release](https://www.postgresql.org/docs/17/release-17-11.html), [supported version policy](https://www.postgresql.org/support/versioning/), [CVE-2026-6473 fixed version](https://www.postgresql.org/support/security/CVE-2026-6473/), [libxml2 UAF](https://security-tracker.debian.org/tracker/CVE-2026-6653), [libxml2 validation fix](https://security-tracker.debian.org/tracker/CVE-2026-86140), [GNU gzip fixes](https://security-tracker.debian.org/tracker/CVE-2026-41992), [systemd-homed scope](https://github.com/systemd/systemd/security/advisories/GHSA-jm29-p7hh-vjhv), [infocmp scope](https://security-tracker.debian.org/tracker/CVE-2025-69720), [util-linux mount scope](https://security-tracker.debian.org/tracker/CVE-2026-78409). Captured primary documents and immutable comparisons are under `primary/` and hash-bound by `review.json`.

## Reproduction and release handoff

Run from the repository root with local Docker. Build limits used here were 2 CPU/3 GiB; the scanner was limited to 2 GiB, each disposable database to 1 CPU/512 MiB. Use fresh empty output directories. Do not replace these names with a live volume or the production Compose project.

```text
docker buildx build --platform linux/amd64 --load --tag otziv-c14-postgres:review infrastructure/runtime-security/builds/postgres-c14
node infrastructure/runtime-security/proofs/c14-postgres/continuity.mjs otziv-c14-postgres:review <new-output>/continuity
node infrastructure/runtime-security/proofs/c14-postgres/tcp-contract.mjs otziv-c14-postgres:review <new-output>/tcp
node infrastructure/runtime-security/proofs/c14-postgres/scan-pair.mjs otziv-c14-postgres:review <new-output>/paired-scan
node infrastructure/runtime-security/proofs/c14-postgres/collect-runtime.mjs otziv-c14-postgres:review <new-output>/runtime
node --test infrastructure/runtime-security/postgres-c14-adjudication.test.mjs
```

Before activating a published image, retain the previous immutable digest and tested backup, capture the actual database's extensions/collations/configuration, and check the PostgreSQL 17.11 migration notes against that inventory. Confirm the backup is complete and the planned restore has sufficient disk space. Rehearse against an isolated copy, including actual Keycloak login/refresh/generation flows, and verify source continuity before and after the controlled downtime window. Stop the previous writer cleanly before attaching the reviewed minor-version image to its volume. Keep credentials and external issuer URLs in the existing managed configuration.

Rollback is a controlled service cutover to the previous digest and a separately restored volume. If writes occurred after upgrade, stop/drain writes and use a verified compatible post-upgrade logical backup or reconcile those writes explicitly; restoring the older snapshot alone loses them. Do not run two database writers against one volume or treat a missing/old backup as a successful rollback. This proof establishes the local technical path, not permission to mutate the VPS or an assertion that its backup and free-space gates have already passed.
