# Bouncy Castle 1.85 refresh

This image inherits the accepted C15 Keycloak 26.7.3 image and replaces the four
physical Bouncy Castle runtime/client JARs with checksum-pinned 1.85 artifacts.
The existing classpath filenames are retained. The actual embedded Maven
versions and file hashes are checked independently of the filenames.

The refresh fixes CVE-2026-8763 and CVE-2026-13506. Upstream release and advisory:

- https://www.bouncycastle.org/resources/new-release-bouncy-castle-java-1-85/
- https://github.com/bcgit/bc-java/wiki/CVE%E2%80%902026%E2%80%908763
- https://github.com/bcgit/bc-java/wiki/CVE%E2%80%902026%E2%80%9013506

Publication run 35445523866 built commit 07e348429f605040bab786f5650d4d032521ba2a.
Its immutable public digest, raw scan, SBOM/provenance and independent anonymous
download are under `infrastructure/runtime-security/proofs/c19-keycloak-published`.
The raw scan reports zero HIGH/CRITICAL findings; no exception was added.

The acceptance keeps the historical C14/C15 migration evidence and PostgreSQL
C16 acceptance intact. It adds a stopped-container inventory comparison, fresh
read-only production database capture and isolated rehearsal: existing login,
roles/credentials, unchanged schema, same-volume rollback and fresh-dump restore.
All 54 rehearsal checks, 20 issuer authentication checks and four start/import
checks passed. The private database dump and credentials remain outside Git.
The PostgreSQL image and custom Keycloak providers are unchanged.

Only these four JARs and three generated Quarkus files differ under
`/opt/keycloak`; parent layers and runtime configuration are preserved. Activation
requires the exact publication and acceptance hashes, all three Compose pins,
and the same PostgreSQL image used in the rehearsal.
