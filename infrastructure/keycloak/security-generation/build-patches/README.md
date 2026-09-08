# Keycloak 26.7.3 / Quarkus 3.33.3.1 descriptor ordering

The first hosted build reached a real `ConcurrentModificationException` in `JpaModelPersistenceUnitContributionBuildItem:25`, called by `HibernateOrmProcessor.contributePersistenceXmlToJpaModel:456–463`. Keycloak's `configurePersistenceUnits` modifies the same descriptor's managed-class list after descriptor publication. Both steps consume `PersistenceXmlDescriptorBuildItem`, so publication alone does not order mutation before the snapshot.

This downstream change adds only `@Consume(AdditionalJpaModelBuildItem.class)` to the Quarkus snapshot step. Keycloak's configuration step already declares that output, and Quarkus's entity-index step already consumes it. The new dependency makes the snapshot wait for registration to finish. It does not serialize the whole build, retry a failed build, discard concurrent modifications or change the registered entities.

Primary sources:

- [Keycloak 26.7.3 processor](https://github.com/keycloak/keycloak/blob/26.7.3/quarkus/deployment/src/main/java/org/keycloak/quarkus/deployment/KeycloakProcessor.java): configuration at lines 518–554; entity registration at lines 642–657. Downloaded source SHA-256: `10fca5987ce2ed64dd2749c3cb132a618c3697ac44086b6d7ec1c566585ff1a0`.
- [Quarkus 3.33.3.1 Hibernate processor](https://github.com/quarkusio/quarkus/blob/3.33.3.1/extensions/hibernate-orm/deployment/src/main/java/io/quarkus/hibernate/orm/deployment/HibernateOrmProcessor.java): snapshot at lines 455–465. Source SHA-256: `b252f429bcf3e8e3f203b17e252ac0d76c426871ebf6fc7d14c7398d382e796a`.
- [Quarkus extension loader](https://github.com/quarkusio/quarkus/blob/3.33.3.1/core/deployment/src/main/java/io/quarkus/deployment/ExtensionLoader.java): runtime `@Consume` annotations become `afterProduce` dependencies at lines 797–802.
- [Quarkus build executor](https://github.com/quarkusio/quarkus/blob/3.33.3.1/core/builder/src/main/java/io/quarkus/builder/Execution.java): its real core/max pool properties control concurrency, not dependency ordering. Reducing the pool alone would not prove that the entity snapshot is complete.

`descriptor-ordering.patch` is the source-equivalent change to upstream Apache-2.0 code. The builder uses upstream ASM to add that runtime annotation to the exact vendor class. The vendor JAR must have SHA-256 `d2efdcb37188c16d2e8916516553c2cce4b98c7abeec23f2532dd065b896e39f`; another version fails before any output is written. A signed input is refused rather than silently retaining invalid signatures. The resulting JAR is an identified downstream derivative, **not a vendor-signed binary**.

The patch tool verifies that only `HibernateOrmProcessor.class` changes and that all 73 method `Code` attributes remain byte-identical, including exception tables, stack maps and debugging sub-attributes. ASM's normalization of the visited method's sub-attribute order is undone only after validating unchanged constant-pool indices and equal attribute sizes. Every other JAR entry, including upstream license/notice files and Maven metadata, remains byte-identical. The runtime image records the change in `otziv.quarkus-descriptor-ordering` and `/opt/keycloak/otziv-descriptor-ordering-provenance.json`, including each method hash.

Every image build executes `DescriptorOrderingProof` twice against the actual vendor classes and the real Quarkus build scheduler. A causal barrier reproduces the original exception while the actual Keycloak entity-registration method mutates a descriptor during the actual Quarkus snapshot. The patched graph must return the complete four-class fixture model (two original and two added entities) and exclude an entity assigned to another persistence unit. This is separate from the real issuer protocol and ordinary startup proofs below.

Reproduce from the repository root after packaging the provider:

```sh
docker build --progress plain -t otziv-issuer-ordering-review infrastructure/keycloak/security-generation
OTZIV_ISSUER_PROOF_POSTGRES_IMAGE=otziv-postgres:17.11-proof node infrastructure/keycloak/security-generation/container-proof.mjs otziv-issuer-ordering-review /new-private-output/protocol.json
OTZIV_ISSUER_PROOF_POSTGRES_IMAGE=otziv-postgres:17.11-proof node infrastructure/keycloak/security-generation/image-startup-smoke.mjs otziv-issuer-ordering-review /new-private-output/startup.json
node infrastructure/runtime-security/scan.mjs image otziv-issuer-ordering-review /new-private-output/vulnerabilities.json
```

The scripts allocate isolated synthetic realms and PostgreSQL containers. They must pass before accepting a rebuilt image; a compile-only or graph-only result is insufficient. Production rollout and compatibility with a different Keycloak/Quarkus version require separate review. Existing image IDs and prior proof artifacts are retained unchanged.
