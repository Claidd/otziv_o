# C43 patch candidates required by the manager audit release

The 28 October exact-head CI detected newly indexed fixed findings in accepted
infrastructure images: CVE-2026-116587 in go-jose/v4 4.1.4 (Tempo, Loki, Alloy,
Grafana) and CVE-2026-102554 in Guava 33.5.0-jre (Keycloak). The automatic gates
remain blocking. No risk exception is created.

Candidates retain the accepted vendor release, patches and service identity.
Go services compile with go-jose/v4 4.1.6. Grafana inherits the C41 runtime to
retain the curl and Brotli fixes. Keycloak inherits the accepted C35 issuer and
replaces only the Guava runtime JAR at its registered classpath path.

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
