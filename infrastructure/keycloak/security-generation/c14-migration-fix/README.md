# Keycloak 26.7.3 realm migration backport

This separate candidate fixes the actual old-Keycloak database migration failure recorded in `infrastructure/runtime-security/proofs/c14-postgres-vps/`. The original C7 recipe, provider and failure/rollback receipts are unchanged. Publication and deployment require their own new immutable candidate and security/continuity gates.

The source change follows the still-open upstream [PR 51945](https://github.com/keycloak/keycloak/pull/51945), pinned to proposal commit `85a8379427a1d031afdd24f8c688bbcd2917fcca`. It is a reviewed local backport, not a claim of upstream approval. The matching upstream [issue 51304](https://github.com/keycloak/keycloak/issues/51304) describes cached model adapters retaining detached JPA entities across realm migration steps. The actual failure reached `MigrateTo26_7_0.addOrganizationAdminRoles` through `RealmEntity.components`.

The 17 added production source lines introduce `CacheRealmProvider.clearManagedModels()`, clear the five session-managed realm/client/client-scope/role/group maps in `RealmCacheSession`, and invoke it immediately after `EntityManagers.flush(session, true)` in `RealmMigration`. Pending key/list/cluster invalidations and transaction completion behavior remain intact. The change does not catch migration failures, reorder SQL or skip creation of organization administration roles. Its scope is the explicit per-realm persistence-context clearing boundary; it is not a general fix for arbitrary batch-mode or other JPA clearing paths.

## Source and package integrity

The derived image starts from the exact reviewed C7 image `ghcr.io/claidd/otziv-security@sha256:93dd42a5379a80fc0673bc1d0c5601d3b35009c8250394b1e2b45082388c82cc`. The verified original Keycloak source commit is `6d238b6558037085cc25c915893c3d301a80243e`, archive SHA-256 `74675b6df593843c5f9384c145c04515c293db228ce76afa9d70c61040f69602`.

`ApplyPatch.java` applies the unified diff with exact line positions and context, no offsets or fuzz. Full-file SHA-256 checks run both before and after application. The three affected source files compile with `javac --release 17` against the exact original runtime JAR classpath. Class-file major version 61 is checked for the original and generated classes.

`ModulePatch.java` replaces only two class families in `keycloak-model-storage-private` and the complete outer/three-inner-class family in `keycloak-model-infinispan`. Every unchanged JAR entry is hashed and verified. Any obsolete generated nested class can be removed only within those explicit source families and must be recorded; this build removes none. The original unsigned manifests and Maven source-version metadata remain byte-identical. The backport has separate provenance and image labels, without inventing a new vendor version. ZIP entry order, timestamps and nested provenance-map serialization are deterministic. All compiler classpath JAR hashes are recorded.

The normal `kc.sh build` augmentation runs again after replacing the two source-built JARs. New generated Quarkus output must therefore be checked alongside the unchanged provider, CLI and runtime dependencies. The old C7 security receipt is not reused as a scan of this candidate.

Final local prototype v3: manifest `sha256:367c57af165ab4e46a36eaaea592a81575e782ce4d0a1a6695b13cdc99d4be2b`, configuration `sha256:b1b695fb74f983c010819b25ef5f402622f20cf865fbbb31133d9a7c69b2538b`.

## Behavioral evidence

The public boundary unit proof executes the real `RealmMigration`, `EntityManagers.flush` and five-map clearing method with controlled persistence/model boundaries. The original JARs reproduce detached adapters in both realm orders. The patched JARs pass both orders, absent-cache behavior and failure propagation with context restoration. Separate checks execute the actual commit and rollback callbacks after clearing, preserving pending invalidation sets/events. These are boundary unit checks, not a substitute for a real database migration.

`proofs/actual-source-sha256-367c57af165ab4e46.json` contains **52 passing checks** on the protected actual VPS database copy and the exact published PostgreSQL C14 image `ghcr.io/claidd/otziv-security@sha256:07834abbfd80ed7183afa9096db99afc4d93b99fd51aae726e550d07ade65dbf`:

- The clean 26.2.5 schema migrates successfully; readiness and password login/admin read using the existing copied administrator pass without resetting credentials.
- All original realm/user/client/credential projections and hashes remain identical: 2 realms, 32 users, 16 clients and 31 credentials. All 89 original roles and 67 user-role mappings remain present; only the expected nine organization roles are added.
- All three relevant admin clients have the three organization roles, nine required admin composite links and three `view-organizations` to `query-organizations` links. These checks establish expected new composite semantics, not byte-preservation of every historical composite link.
- A second start preserves the critical records and organization role/composite results. The provider schema is present and its unauthenticated endpoint rejects access.
- A database trigger rejects the fourth organization-role insertion, after three actual inserts in the transaction. Startup fails; a separate SQL connection observes no partial changes in the six protected critical families. Removing only the injected fault allows a fresh process to complete migration with the required roles and composites. Liquibase schema commits are a separate boundary; this does not claim that every schema DDL change rolls back with model writes.
- A separate fresh original PostgreSQL 17.10 instance restores the pre-upgrade dump with all 88 table hashes matching. Original Keycloak 26.2.5 readiness and existing-admin login/read pass, with the original critical hashes preserved. This is backup restoration, not binary downgrade on a migrated Keycloak schema, and does not replay post-capture production writes.

Each disposable volume has a newly generated owner/name and is created by the executed source before initialization and `pg_restore --exit-on-error --single-transaction` without `--clean`. Freshness evidence is tied to that source and successful restoration; this harness does not claim a separate pre-restore empty-volume inventory assertion. The deployment coordinator must retain its own explicit empty-volume preflight.

The PostgreSQL engine acceptance is composed from the earlier actual same-volume 17.10 to local C14 17.11 phase, the independently verified exact runtime parity of published C14 PostgreSQL, and this fixed-Keycloak migration on a fresh restored published PostgreSQL instance. This runner does not claim to repeat the same-volume PostgreSQL engine upgrade.

`proofs/source-continuity-and-cleanup.json` records the post-run read-only VPS observation: source containers, images, configuration hashes, volume metadata, cluster identifier, locale and role/schema footprint remain unchanged. It also records an actual label-scoped inventory of zero remaining rehearsal containers, volumes and networks. It does not claim that arbitrary production rows cannot change while the live application runs. The source dump and credentials stay outside the repository under an exclusive current-user/SYSTEM ACL; all local database traffic stays on internal networks without published ports or outside egress.

`replay-actual.py <candidate-image> <protected-private-capture-directory>` and `private-runtime.py` are the standalone local replay sources. They contain no SSH/capture path and require the previously authorized protected artifacts; they do not create or reset copied users. Final review bindings identify the exact executed source bytes and private dump hash.

The unchanged C7 issuer/provisioning scenario was also repeated against final v3 and published PostgreSQL: `proofs/issuer-protocol-v3.json` records **20/20 passing checks**, including browser PKCE/password login, refresh/offline tokens, revocation, restart and failed-journal rollback. The only harness adjustment was explicit CPU limits, recorded in `issuer-harness-v3.json`. Provisional and v2 reports retain their original intermediate/history roles; the exact final v3 report is the migration acceptance input.

The initial build failed because the pinned build-tool image did not contain the external `patch` program. That failure log was preserved privately; the final recipe uses the strict Java applier and adds no build-time package installation. A temporary local `__pycache__` cleanup was denied by automatic approval review; the directory remains ignored by Git and excluded from the build context.

## Independent security review and publication boundary

`proofs/security-v3/review.json` binds the exact v3 configuration, 25 evidence files and all nine build inputs. The fresh full image scan reports zero HIGH and zero CRITICAL findings, with 49 lower-severity findings retained, 143 OS and 512 JAR package records; the SBOM contains 656 components. The existing runtime dependency policy passes. Independent source/archive review verifies all original classpath hashes and the precise two/four changed class entries. No CVE exception was introduced for this migration fix.

The two patched vendor JARs and `quarkus-application.dat` are byte-identical between local v2 and v3. However, `generated-bytecode.jar` differs in 138 uncompressed entries, while `transformed-bytecode.jar` differs only in ZIP packaging. Deterministic patched JARs do not establish deterministic Quarkus augmentation. A future published candidate therefore requires its own exact runtime inventory and the actual-source migration replay when generated payload differs; v3 behavior is not transferred solely from source or vendor-JAR parity. The local review is a candidate-preparation result, not authorization or acceptance of a VPS deployment.

`proofs/review.json` seals the final local candidate evidence graph. Earlier failure evidence remains unchanged and is referenced only for its explicitly successful PostgreSQL engine phase and the causal unpatched-Keycloak failure. The actual migration and rollback proof applies to the captured database boundary; deployment still requires a current protected backup, explicit destination-volume checks and the deployment coordinator’s source-continuity preflight.
