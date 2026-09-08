# Actual VPS PostgreSQL continuity and Keycloak migration boundary

Release acceptance remains **BLOCKED by the target Keycloak migration**. PostgreSQL 17.10 to the exact C14 17.11 candidate passed on a protected copy of the actual VPS database. Restoring the pre-upgrade backup into a fresh 17.10 instance with the original Keycloak also passed. The new Keycloak image failed twice during an upstream realm migration; this is not a successful target migration receipt.

The authorized source access used strict known-host SSH, Docker inspect and PostgreSQL read-only transactions/pg_dump. The custom dump streamed directly into a unique external directory whose ACL permits only the current Windows user and SYSTEM. No source temporary files, SQL writes, container changes, copied-user resets or production HTTP requests occurred. Dump, role DDL, existing administrator credentials and raw container diagnostics remain private. This directory contains only counts, hashes, image identities and an exception stack stripped of record identifiers.

## Observed source and matching runtime footprint

- PostgreSQL 17.10 Debian trixie; UTF8, libc `en_US.utf8`, recorded and actual collation version 2.41. Source PostgreSQL digest `postgres@sha256:a426e44bac0b759c95894d68e1a0ac03ecc20b619f498a91aae373bf06d8508d`.
- Keycloak 26.2.5, digest `quay.io/keycloak/keycloak@sha256:4883630ef9db14031cde3e60700c9a9a8eaf1b5c24db1589d6a2d43de38ba2a9`.
- 88 tables, 196 indexes, approximately 36.8 MiB; only `plpgsql` installed. No custom collation, tablespace, foreign server, preload library, replication slot or subscription. Time zone `Asia/Irkutsk`.
- Critical records: 2 realms, 32 users, 16 clients, 31 credentials, 89 roles and 67 user-role mappings. No names, secret values or record identifiers are published.
- C14 retains the applicable locale and extension footprint. The previously approved optional difference remains JIT unavailable in C14 versus available on the source; no performance equivalence is claimed.

## Executed acceptance

`actual-rehearsal.json` retains the failed overall result and 26 successful preceding checks. The 1,873,669-byte custom dump restored successfully; all 88 source tables were stable during capture and matched the restored table hashes. The six critical families also matched using canonical projections and SHA-256, including credential secret and configuration fields without revealing them.

The original Keycloak passed OIDC discovery, readiness and an admin read using the captured, already existing administrator. After cleanly stopping it and PostgreSQL, the same disposable data volume started under C14 OCI manifest `sha256:ebf646d4a2ed114de7138af57159508770872d10b35ce56f3f5a9193c87f719e`. Every table hash matched across that stopped boundary. The original Keycloak again passed discovery/readiness/admin read, and all critical record hashes remained unchanged.

Target Keycloak digest `ghcr.io/claidd/otziv-security@sha256:93dd42a5379a80fc0673bc1d0c5601d3b35009c8250394b1e2b45082388c82cc` then failed before readiness with `org.hibernate.LazyInitializationException` on `org.keycloak.models.jpa.entities.RealmEntity.components`. A fresh restore and verbose second run reproduced it. Relevant stack:

```text
RoleStorageManager.getClientRole(RoleStorageManager.java:245)
RealmCacheSession.getClientRole(RealmCacheSession.java:886)
ClientAdapter.getRole(ClientAdapter.java:556)
MigrationUtils.addAdminRole(MigrationUtils.java:45)
MigrateTo26_7_0.addOrganizationAdminRoles(MigrateTo26_7_0.java:140)
MigrateTo26_7_0.migrate(MigrateTo26_7_0.java:42)
DefaultMigrationManager.migrate(DefaultMigrationManager.java:172)
QuarkusJpaConnectionProviderFactory.initSchema(QuarkusJpaConnectionProviderFactory.java:226)
```

The observed failure is in the upstream realm migration path. The safe stack contains no application generation-provider frame. It matches the reported upstream [issue 51304](https://github.com/keycloak/keycloak/issues/51304), which describes stale session-managed realm adapters after a dump/restore. [PR 51945](https://github.com/keycloak/keycloak/pull/51945) was open when reviewed on 2026-09-08; it is a proposed fix, not evidence that the published target is repaired. Catching and ignoring this migration error would not satisfy acceptance.

`rollback-and-diagnostic.json` records 12 successful checks, including the separate rollback and reproduction of the target failure. Its PASS means those checks passed; `targetFailureReproduced: true` continues to block the release. The pre-upgrade dump restored into a fresh original 17.10 volume with all 88 table hashes, role attributes and critical hashes intact; the original Keycloak passed discovery/readiness/admin read. This did not attempt a binary downgrade on a migrated Keycloak schema and did not replay production writes made after capture. Keycloak's [upgrading guide](https://www.keycloak.org/docs/latest/upgrading/index.html) requires restoring the earlier database backup for rollback of schema changes.

The previous synthetic PostgreSQL 21/21 and new-issuer 20/20 checks remain valid within their scopes. They do not close this actual old-Keycloak database migration failure. A corrected, separately identified target must pass the actual-data migration, preserved critical records, provider contract and rollback checks before release acceptance.

All rehearsal containers used an internal Docker network without published ports or outside egress. Only one database writer owned each disposable volume. Local resources were removed after recording private diagnostics; a final label-scoped inventory found no remaining owned containers, volumes or networks. Source containers and configuration/role/schema metadata were unchanged across capture.

An initial local harness incorrectly rejected Keycloak's normal SIGTERM exit 143; its private failure receipt was retained before rerun. A separate ad-hoc role query failed to execute and was not a valid role observation; the successful authoritative role comparisons are in `rollback-and-diagnostic.json`. Neither harness issue is the reproduced upstream target migration failure.
