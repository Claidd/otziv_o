# C55 Go toolchain and C56 MySQL libraries

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
remains rejected. PostgreSQL stays on its accepted C54 image.

Publication, anonymous downloads, OCI provenance/SBOM, raw security evidence,
application and monitoring compatibility tests and exact-main CI remain required.
Deploy from the main checkout using `deploy.ps1 -ImageTransport Direct` with
`-CoordinatedMysqlRefresh`; the standard selective rollout and backup remain active.
The contact-health UI must also pass on the fresh prod-like database with external
messages and real payments disabled, followed by read-only production verification.
