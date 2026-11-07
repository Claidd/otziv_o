# Official Debian zlib update of the Keycloak PostgreSQL image (C51)

Debian's `zlib1g` `1:1.3.dfsg+really1.3.1-1+deb13u1` (trixie-security) fixes
CVE-2026-76844 and CVE-2026-85091. The accepted C36 PostgreSQL 17.11 image
carried `1:1.3.dfsg+really1.3.1-1+b1`.

C51 inherits the exact C36 digest and overlays only the official package,
pinned by SHA-256 `8c27c342230503b73dfde629642c3b352e87b554e06d60cf58772f236a305ceb`
against the signed trixie-security index. The image has no package manager:
the `.deb` is unpacked offline and only the `zlib1g` dpkg status and info
entries are replaced.

Publication run `42400457854` (attempt 1) built commit
`1cdbc395dcfb2e882836a9c6592538c18a628df7`, passed the fresh scan of the exact
registry digest and a separate anonymous digest download:

- PostgreSQL: `ghcr.io/claidd/otziv-security@sha256:13d2e1ba01b593f1f284d5d7869a9994f5251f036355e90064406ca4e7d809fc`
  (config `sha256:ceffd72a35bc4a20a9588f71deaf98cc145ba6832104a8b539a63395ce341975`).
  No zlib finding remains. The 17 fixed and 15 unfixed HIGH/CRITICAL
  PostgreSQL findings carry the decisions re-derived from the unchanged C36
  review; nothing else is adjudicated.

The stopped-image comparison (`postgres-c51-inspection.py`, reusing the
hash-bound C23 inspector) covers the complete filesystem. Exactly nine paths
changed: the official `libz.so.1.3.1`, its two changelogs, five dpkg info
files and `var/lib/dpkg/status`. Every other file, layer, label and launch
setting of C36 is unchanged.

Data, authentication and rollback were proved locally with the unchanged C36
protocol (`postgres-c51-rehearsal.py`) and the active C48 Keycloak. The replay
passed 54 isolated checks: restore of the local prod-like C36/C48 issuer data,
same-volume C36 -> C51 upgrade with all 102 tables exact, existing password
login, unchanged roles and credentials, denied unauthenticated provider access,
no schema change, and both rollback paths to C36 (same volume and fresh
post-upgrade restore). Server version, encoding, locale, cluster identifier,
extensions and configuration hashes are identical. No production records or
private credentials are published.

The capture guard requires the source issuer and database to share exactly one
`Internal` network. As for C48, `postgres-c51-capture-window.py` opened a
27-second window (18:24:24-18:24:51 UTC on 07.11): the running local C36
database and C48 issuer moved to the labelled temporary `--internal` network
`otziv-postgres-c51-capture-6d577c56a47b` with their original aliases, the
unchanged capture ran, and both returned with the same aliases and addresses.
Nothing restarted and Keycloak discovery answered 200.

`proofs/c51-postgres/acceptance.json` binds the publication, the runtime proof,
the three rehearsal records and all five executed sources. Activation requires
it together with the frozen C36 entry (`c51-parent-postgres.json`), whose own
C23 -> C36 lineage is replayed, and the active C48 issuer acceptance.

Production uses the explicit `-CoordinatedPostgresRefresh` release step
(`coordinated_postgres_zlib_refresh.py`). After the mandatory main DB backup it
stops Keycloak, fingerprints all 102 tables, writes an additional encrypted
PostgreSQL backup, recreates only `keycloak-postgres` on the same volume and
requires identical fingerprints before Keycloak starts again. Any failed check
restores C36 on the same volume. The ordinary database image guard still
refuses replacing C36 with C51 in any other deployment.
