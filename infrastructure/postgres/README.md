# PostgreSQL 17 security candidate

The Dockerfile keeps the official PostgreSQL 17.11 engine and Debian trixie
family. It applies available distribution updates and rebuilds **unmodified
gosu 1.19** from its verified upstream source with Go 1.27.1. Original module
metadata, license, source commit and archive checksum remain in the image.

```sh
docker build -t otziv-postgres:17.11-proof infrastructure/postgres
node infrastructure/runtime-security/scan.mjs image otziv-postgres:17.11-proof /secure/evidence/postgres-vulnerabilities.json
node infrastructure/postgres/upgrade-proof.mjs otziv-postgres:17.11-proof /secure/evidence/postgres-upgrade
```

The upgrade proof owns only its labelled, network-isolated containers/volumes.
It creates 1,000 synthetic relational/JSON records, constraints, an index, a view
and a restricted role on the pinned 17.10 image. It records a logical backup,
requires a clean stop, opens the **same volume** with 17.11, verifies the data,
locale metadata and actual role permissions, writes a new record and restarts.
Rollback restores the original backup into a separate fresh 17.10 volume and
verifies that the later mutation is absent. It does not touch production or
the stock local database. A proof directory must be new and stays immutable.

On 2026-09-07 the final proof passed all 10 checks with cleanup PASS:
`.codex-tmp/finalization-20260907/postgres-upgrade-v6/proof.json`.
Earlier preparation/comparison failures remain in v1–v4. The intermittent UTF-8
comparison failure led to a separate subprocess decoder fix and a deterministic
regression test; no integrity assertion was relaxed.
The final readiness check also requires PostgreSQL as PID1, so the entrypoint's
temporary initialization server cannot be mistaken for the running service.

The actual candidate scan has **zero fixed HIGH/CRITICAL** findings, but retains
**86 vendor-unfixed HIGH/CRITICAL package rows**. Their raw records and SBOM are
in `postgres-17.11-hardened-vulnerabilities*` in the same evidence directory.
This is not an accepted-risk decision or a vulnerability-free claim.

The issuer protocol also passed 20 actual Keycloak/PostgreSQL scenarios on this
candidate. CI builds/scans it, runs this minor-upgrade proof and uses it in the
paired system-recovery drill. Production image selection still requires a
published digest, reviewed unresolved findings and the coordinated recovery
point described in the recovery runbook. Existing active Compose pins are not
silently promoted by a local image tag.
