# PostgreSQL 17.11 Alpine: concrete local incompatibility

**Result: not eligible as a semantics-preserving replacement for the current local database.** The official image exists and starts correctly, but matching the locale name does not preserve the current database's ordering and case conversion. Real-data copying stopped before creating a dump. No C/ICU substitution, constraint removal, application startup or authentication-flow test was performed.

The read-only catalog query identifies the actual local source as PostgreSQL 17.10 Debian, UTF8, libc `en_US.utf8`, recorded and actual collation version 2.41. It has 102 tables, 248 indexes, one used collation and only the `plpgsql` extension. Names, role details and grant metadata remain in an ACL-protected local catalog; the public summary has aggregate counts. Source container `aea1758b7e9ee95501a92cdce8574604bff15e1ab2254bf6bedfe70ebd953296`, image `sha256:a426e44bac0b759c95894d68e1a0ac03ecc20b619f498a91aae373bf06d8508d`, original volume and StartedAt were verified unchanged after the proof.

| Candidate identity | Value |
| --- | --- |
| Official immutable index | `postgres@sha256:18cfe3ef5e6815560c98237d6216d1e5119702fb0f3894c8785dd58b8bbe5d73` |
| linux/amd64 config from prior verified manifest and actual scan | `sha256:1bea307dfb3ee30541a7acf7de14b58bcd6948da98e5d31a04c627c4d35ec64b` |
| Actual server/runtime | PostgreSQL 17.11, Alpine 3.24.1, postmaster UID70 |
| Owned fixture | `otziv-c12-pg-alpine-2ba082966867-candidate`, fresh `-candidate-data` volume |
| Limits | network none, no published ports, 512MiB, one CPU, 128 PIDs; no source-volume mount |

The candidate explicitly initialized UTF8 and **the same** libc `en_US.utf8` collation/ctype. Forty-one synthetic text controls were evaluated through read-only SELECT on the current source and through the candidate. Ordering differs; case conversion differs for control 20 (`ß`, uppercase remains `ß` on the source but becomes `ẞ` on Alpine); pairwise equality for those controls matches. Candidate collation version is null. An index created only in the owned candidate follows the candidate's differing order. These differences are a positive incompatibility result, not a missing benchmark. Reindexing would reconstruct Alpine ordering; it would not restore the source's semantics.

Twelve boundary/startup/identity/cleanup checks passed. The candidate stopped cleanly and its exact owner-labelled container and volume were removed. No current-data dump, current-data restore, compressed-data rollback, old-image rollback or application-compatibility result is claimed. Those dependent migration steps were intentionally not executed once the required locale condition failed. The original database and Keycloak lifecycle were untouched. The first ACL preflight attempt failed before SQL because PowerShell could not load its Get-Acl module; the retry used .NET ACL inspection and verified the same current-user/SYSTEM-only protected directory before collecting catalog metadata.

The full pinned Trivy 0.74.0 scan and CycloneDX SBOM completed against the exact official image. Raw findings remain:

| Target | High/Critical findings | Disposition |
| --- | ---: | --- |
| Alpine OS packages | 9 | libcrypto3/libssl3 3.5.7-r0 and libuuid 2.42.1-r0 have reported patched distro versions |
| gosu, Go standard library 1.24.6 | 22 | Reported Go fixes available; the project's rebuilt gosu proof does not apply to this upstream binary |
| Total | **30 High + 1 Critical** | All 31 report a fix; zero unfixed findings; gate FAIL |

The scan has two artifact results and retains all package/language findings, scanner diagnostics and SBOM. No suppression or risk acceptance was added. A normal distro-package update and verified rebuilt gosu could be evaluated later for security, but would not resolve the proven glibc/musl semantics difference. Parent directed preserving Debian semantics and closing this attempt as an incompatibility; no derived-image build was started.

Files: `catalog-summary.json`, `locale-result.json`, source/candidate Unicode controls, exact proof scripts, `upstream-vulnerabilities.json`, `.sbom.cdx.json`, `.triage.json`, `.scanner.log`, and `scan-execution.json`. `result.json` and `freeze.json` bind all safe evidence hashes. Only the protected catalog remains locally; it contains no row data or password fields. Pulled image cache remains; no fixture container, volume or network remains.
