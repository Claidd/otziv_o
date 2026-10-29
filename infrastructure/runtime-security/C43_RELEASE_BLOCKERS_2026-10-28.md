# C43 patch candidates required by the manager audit release

The 28 October exact-head CI detected newly indexed fixed findings in accepted
infrastructure images: CVE-2026-116587 in go-jose/v4 4.1.4 (Tempo, Loki, Alloy,
Grafana) and CVE-2026-102554 in Guava 33.5.0-jre (Keycloak). The automatic gates
remain blocking. No risk exception is created.

Candidates retain the accepted vendor release, patches and service identity.
Go services compile with go-jose/v4 4.1.6. Grafana inherits the C41 runtime to
retain the curl and Brotli fixes. Keycloak inherits the accepted C35 issuer and
replaces only the Guava runtime JAR at its registered classpath path.

The exact C43 Keycloak image passed its fresh scan, anonymous pull, complete
filesystem comparison, and 54 isolated authentication/data/rollback checks with
the accepted C36 PostgreSQL runtime. Its C35 provider JAR and all 102 tables are
preserved. The retained acceptance proof also binds the original C35 parent and
the earlier database cohort acceptance; it does not authorize a database change.

Tempo, Loki and Alloy passed publication and anonymous digest checks. Grafana's
C43 executable was independently inspected: binary and complete build-info
hashes bind the unchanged fixed Tempo module and go-jose/v4 4.1.6. The first raw
failed scan remains in `proofs/c43-grafana-discovery`. C44 retains that exact
executable in a metadata-only image and requires a new full scan and anonymous
pull before activation; it does not rebuild or silently rewrite the first result.

Publication, fresh anonymous digest download, exact scans and functional
acceptance are required before changing deployment defaults. The new Grafana
executable requires its own inspected identity before the existing fixed Tempo
source ancestry proof may apply. Until that identity is reviewed, the two Tempo
pseudoversion findings remain blockers. Alloy's daemon-code absence proof is
repeated against the new binary and import closure, with an additional check
that the binary contains go-jose/v4 4.1.6.

Primary advisories:

- https://github.com/go-jose/go-jose/security/advisories/GHSA-mgcc-cww5-fp2q
- https://github.com/google/guava/security/advisories/GHSA-xxph-c9ww-hj94

All five candidates are now registered only after successful publication and
fresh anonymous download. Grafana C44 run 40791273064 passed the fresh scan with
the inspected C43 executable; both retained Tempo decisions are exact binary
adjudications and the original failed discovery report remains unchanged.

The frontend development server also required @hono/node-server 2.1.3 for
GHSA-rmxm-3fg6-px4f, newly indexed by the required npm audit. Its override and
lockfile are updated without changing frontend application code.
