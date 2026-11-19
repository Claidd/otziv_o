# C55 Go toolchain and C57/C58/C59 runtime refresh

## Final release candidates

The fresh CI scan also identified Oracle Kerberos libraries in MySQL, the Go
compiler used for PostgreSQL's gosu helper, and Nginx. C57 supersedes the prepared
C56 candidate; production did not receive C56. C58 and C59 replace the active
C54 PostgreSQL and C52 Nginx images. No scanner gate or secret rule is disabled.

C57 starts from the actual production C46 MySQL image, retaining MySQL 9.7.3 and
its byte-identical server. It updates the four util-linux libraries listed below
and krb5-libs to 1.21.1-10.0.1.el9_9.2; every other RPM must remain identical.
The published digest passed 28 isolated checks against the fresh VPS dump,
including all data/schema/account checks, restart, rollback and fresh restore.
Its raw scan has zero HIGH/CRITICAL findings and no adjudications.

C58 changes only `/usr/local/bin/gosu`, rebuilt from the same gosu 1.19 commit
with Go 1.27.4. PostgreSQL remains 17.12. CI compares the complete filesystem,
packages and runtime configuration against C54 and binds the actual Go build
metadata. The previous exact-source PostgreSQL reviews remain mandatory; new
findings are not covered. The active Keycloak image is unchanged. A real local
capture and replay passed 55 checks, including existing-user login, all table
fingerprints, index validity, restart, rollback with new writes and fresh restore.
Only hashes and counts are release evidence; private captures are removed.

C59 installs signed Nginx 1.30.7-r1 and its matching acme, geoip, njs and xslt
modules. All other packages remain unchanged. Both the default configuration
and loading every installed dynamic module are tested; raw HIGH/CRITICAL is zero.

The exact-image dispatchers select `coordinated_mysql_kerberos_refresh.py` and
`coordinated_postgres_gosu_refresh.py`. Both retain the deploy lock, writer
fences, verified encrypted backups, exact proof hashes, data comparison and
rollback. Deploy uses `-ImageTransport Direct -CoordinatedMysqlRefresh
-CoordinatedPostgresRefresh`, without a full rollout. Recovery defaults move to
C58 while older backups retain their exact earlier runtime identities.

## C55 and the superseded C56 preparation

C55 rebuilds Prometheus/promtool, Loki, Tempo, Alloy, Grafana, Dozzle, MC and
the local S3 gateway with Go 1.27.4 (CVE-2026-97054). The reviewed upstream
sources, dependency versions, build checks and runtime parents remain unchanged.
Recipe regression tests enforce that scope. Activation additionally requires
the fixed standard library in every scanned Go executable, even if the scanner
database has no matching vulnerability entry.

Grafana 12.4.10+otziv.7 has a separately inspected executable identity. Its complete
canonical module graph matches C50. The two existing Tempo pseudoversion findings
retain the byte-checked source review and module checksum; no extra finding can
inherit that verdict. Alloy's C55 label selects a fresh inspection of its actual
functions and import closure with Go 1.27.4. The existing Docker daemon-only proof
still requires positive client controls and absence of daemon code.

C56 updates only Oracle Linux libblkid, libmount, libsmartcols and libuuid to
2.37.4-28.0.1.el9_9 (CVE-2026-13595 and CVE-2026-53613). MySQL remains 9.7.3 with
the identical server executable. The build and rehearsal reject changes to
unrelated packages. Raw scanner reports must contain all four fixed packages
and zero HIGH/CRITICAL findings; MySQL accepts no adjudications.

The published C56 digest passed 26 isolated checks against a fresh VPS dump:
all table counts/checksums, schema, migration history, accounts and settings;
existing-user login; writes surviving restart; same-volume rollback preserving
new writes; and recovery into an empty volume. Only hashes and counts are retained
in source. The private dump is not release evidence. Rehearsal containers have no
network or published ports, and all owned resources are removed afterwards.

The existing deployment entry point selects `coordinated_mysql_util_linux_refresh.py`
only for the exact accepted C56 digest. It requires the deploy lock, paused
self-heal service, exact rehearsal/source hashes, stopped writers, a verified
encrypted backup and unchanged data checksums. Failure rolls back on the same
volume while retaining any valid new writes. Ordinary database-image replacement
remains rejected. This C56 preparation is superseded by the candidates above.

Publication, anonymous downloads, OCI provenance/SBOM, raw security evidence,
application and monitoring compatibility tests and exact-main CI remain required.
Deploy from the main checkout using `deploy.ps1 -ImageTransport Direct` with
both coordinated database switches above; selective rollout and backup remain active.
The contact-health UI must also pass on the fresh prod-like database with external
messages and real payments disabled, followed by read-only production verification.

Four unmodified public CI scan/SPDX payloads are retained as gzip under the
existing evidence reader: their commit URLs and SBOM digests match the secret
scanner pattern. Each decoded file is bounded and bound to its exact SHA-256
before validation. The complete new-commit history scan remains blocking.
For four original C55 public reports already present in that history, Gitleaks
exceptions require the exact rule, path and complete reviewed public hash.
A SHA-256-bound JSON-context inventory includes percent-decoded advisory URLs;
real-scanner negative controls still reject changed values, token prefixes and
other paths. No directory, commit or scanner rule is excluded.
