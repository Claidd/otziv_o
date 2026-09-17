# MySQL September OS security refresh

The unchanged reviewed recipe was republished in workflow 35222126122 from
commit 7dfa53ac3089664a80d05892dc8c5e2b637b7c46, with OCI provenance, SBOM and
independent anonymous digest-pull evidence retained in this directory.
The raw Trivy report has zero HIGH/CRITICAL findings and no adjudications.

The isolated rehearsal restored the protected production capture from
2026-09-17 into the previous image, compared all 332 base tables, column schema,
Flyway history, fixture accounts and semantic settings before and after the
same-volume refresh. MySQL remains 9.7.3 and its server executable is byte
identical; OpenSSL is 3.5.8. Rehearsal also checks native UID 999, no external
network/ports, existing fixture login, persistent new JSON writes after restart,
same-volume rollback and a fresh restore of the post-refresh dump.
Only hashes/counts are retained; private rows and credentials are not published.
Rehearsal uses clone-only credentials, not exported production account secrets.

The proof prepares the exact candidate. Ordinary deployment still refuses to
change the image of an existing database or provision the native database from
scratch. Production activation requires a separate coordinated write fence,
verified encrypted backup, unchanged volume/configuration, data comparison and
application health check. Historical 9.0-to-9.7.3 evidence remains unchanged.
