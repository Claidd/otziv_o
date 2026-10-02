# Downstream Testcontainers transport security rebuild

This module retains docker-java 3.7.1 transport behavior and Testcontainers 2.0.5 discovery. It rebuilds the official zerodep packaging with HttpClient 5.6.4 and HttpCore 5.4.3. The downstream coordinate is `com.hunt.build:docker-java-transport-zerodep:3.7.1-otziv.1`; it does not overwrite or impersonate a vendor release.

## Provenance and scope

The only Java source is the unchanged upstream `ZerodepDockerHttpClient.java`, git blob `fcacc6d1b753f4a703287abb76a403769be66938`, from docker-java tag 3.7.1. All other transport classes come from the official Maven Central artifact `docker-java-transport-httpclient5:3.7.1`. The relocations and exclusions follow upstream zerodep packaging. Full patched classic and async HTTP implementations remain packaged. The Apache license and HTTP component notices are retained.

At verification time Maven Central and the upstream release API still identified docker-java 3.7.1 and Testcontainers 2.0.5 as latest releases. Testcontainers' `httpclient5` selection constructs `ZerodepDockerHttpClient` directly. Selecting the official unshaded artifact alone does not replace this path. This downstream rebuild avoids a custom discovery strategy, altered Ryuk lifecycle, and changes to application code.

Normal bootstrap with JDK 26 and Maven 3.9.15:

```sh
mvn -B -ntp -f backend/build-support/test-transport/pom.xml install
```

The backend integration excludes the vendor zerodep artifact from both direct Testcontainers dependencies and adds this coordinate with **test scope**. `backend-integration.patch` was prepared against publication C5 POM SHA-256 `46a1a8160f7cced473772e44518bfb5b173860f1fe76cbc0702540b325d6ea00`; it must be merged with other reviewed build-support changes, never blindly applied to a different POM.

## Verification

- CVE-2026-64607: the same loopback invalid-encoding response leaves one leased connection in upstream 5.5.1 and exhausts its one-slot pool. The patched shaded artifact releases the connection and the next benign request completes.
- CVE-2026-71290: a trusted fixture certificate with the wrong hostname is accepted by upstream async BUILTIN verification and rejected by the patched artifact. The matching hostname works in both. The fixture trusts only its test certificate; trust validation is not disabled.
- Windows npipe/MySQL: original and derivative both pass 32 checks using the normal Testcontainers strategy and Ryuk. Coverage includes startup/readiness, SQL commit/rollback, archive upload, stdout/stderr, framed stdin, logs, 16 concurrent Docker/JDBC calls, and owned-resource removal.
- Linux Unix socket: 25 checks pass with normal Testcontainers/Ryuk, including framed stdin and concurrent operations. The child fixture has no network. The outer Docker Desktop test runner uses its supported bridge/host override; Linux CI should use its own normal socket discovery.
- Seven transport class instruction streams/descriptors are identical to upstream after constant-pool index normalization; five raw class files are identical. No provider behavior is rewritten.
- Two Windows builds and an offline Linux JDK26/Maven3.9.15 build produce the identical entire JAR SHA-256 `bb5d1b9166dacb96a338a0c36b91120046268263982906055370c4968631a86c`.
- Backend baseline/candidate runtime dependency paths and bytes are identical across 351 artifacts. Neither Testcontainers nor the downstream transport appears in that graph. An actual repackaged production archive comparison remains an integration check, not a claim from this graph proof.

Windows npipe preserves an existing upstream diagnostic: peer EOF can surface as an IOCP broken-pipe exception after a complete framed stdin payload. Both artifacts show this; the test only accepts it when inspected process exit is zero and stdout exactly matches all expected bytes. Linux completes normally. This is a documented preserved behavior, not a claimed new fix.

## Maintained evidence

The proposal manifest records the exact source, artifact and evidence hashes. Standalone Java fixtures `TransportCveProof.java`, `TestcontainersTransportSmoke.java` and `UnixTransportSmoke.java` are retained beside the proposal, together with their dependency POM and results. TLS private fixture material is local-only and must not be published. All real Docker resources were scoped synthetic fixtures. No production deployment, external messenger, Sonatype call, scanner suppression or threshold change was performed.

Primary sources:

- https://github.com/docker-java/docker-java/tree/3.7.1/docker-java-transport-zerodep
- https://github.com/testcontainers/testcontainers-java/blob/2.0.5/core/src/main/java/org/testcontainers/dockerclient/DockerClientProviderStrategy.java
- https://github.com/apache/httpcomponents-client/blob/rel/v5.6.4/RELEASE_NOTES.txt
- https://www.cve.org/CVERecord?id=CVE-2026-64607
- https://www.cve.org/CVERecord?id=CVE-2026-71290
