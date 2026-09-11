# MySQL 9.7.3 security candidate

The Dockerfile derives from Oracle's pinned MySQL 9.7.3 Community Server image,
applies available Oracle Linux updates and removes the separate `mysql-shell`
administration package from the server runtime. The actual `mysqld`, `mysql`
and `mysqldump` binaries remain 9.7.3 and are checked during the build.

```sh
docker build -t otziv-mysql:9.7.3-proof infrastructure/mysql
node infrastructure/runtime-security/scan.mjs image otziv-mysql:9.7.3-proof /secure/evidence/mysql-vulnerabilities.json
```

The 2026-09-07 candidate scan reported **0 HIGH and 0 CRITICAL**, with no ignored
findings. Full package inventory, original report and SBOM are retained as
`.codex-tmp/finalization-20260907/mysql-9.7.3-hardened-vulnerabilities*`.
The upstream candidate's five fixed HIGH findings were two Oracle SQLite
advisories and three findings in the removed Shell's bundled Python package.
No package metadata was rewritten to conceal a vulnerable installed component.

Before a server upgrade, run the **separate mandatory Upgrade Checker** using
the pinned upstream tool image, as described in
[MYSQL_UPGRADE_REHEARSAL.md](../runtime-security/MYSQL_UPGRADE_REHEARSAL.md).
That tool image still has its separately recorded findings; removing Shell from
the server does not certify the tool. No checker exclusions or forced upgrade
are permitted by the harness.

The original same-volume 9.0 → 9.7.3 rehearsal passed 33 checks against a fresh
sanitized VPS-derived copy: 320 tables, 1,048,612 rows, exact Flyway history,
representative checksums, all JSON-column aggregates and actual application
startup/shutdown. Its original failed view-definer preparation and subsequent
full checker result remain evidence. A separate hardened-image follow-up is
recorded in `mysql-upgrade-hardened-v1`; consult its final result before using it.

The source image uses UID999 while Oracle defaults to UID27. The rehearsal
retains numeric UID999, a writable dedicated runtime tmpfs and an explicit
configuration; merely replacing an active Compose digest does not implement
that migration. Production needs the same account/configuration review, an
independent verified backup, a write fence and a published image digest.
There is no supported binary downgrade of the upgraded volume to MySQL 9.0.
Restore the paired pre-upgrade backup for rollback.
