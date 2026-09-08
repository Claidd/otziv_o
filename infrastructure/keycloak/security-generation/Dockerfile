# Rebuild both official CLI entrypoints from the exact 26.7.3 source; retain the upstream shade recipe.
FROM maven@sha256:527989ca6d3279cc9494d665f617374c428eccd7248dbadf0d03bceaa70b9c5d AS admin-cli-build
ADD --checksum=sha256:74675b6df593843c5f9384c145c04515c293db228ce76afa9d70c61040f69602 \
    https://codeload.github.com/keycloak/keycloak/tar.gz/6d238b6558037085cc25c915893c3d301a80243e /tmp/keycloak-source.tar.gz
RUN mkdir /src && tar -xzf /tmp/keycloak-source.tar.gz --strip-components=1 -C /src
COPY build-patches/cli/keycloak-admin-cli.pom.xml /src/integration/client-cli/admin-cli/pom.xml
COPY build-patches/cli/cli-dependencies.sha256 build-patches/cli/CliJacksonProof.java /cli-patch/
WORKDIR /src/integration/client-cli/admin-cli
RUN mvn --strict-checksums -B -ntp verify \
    org.apache.maven.plugins:maven-dependency-plugin:3.11.0:copy-dependencies -DoutputDirectory=/cli-dependencies \
    && test "$(find /cli-dependencies -type f | wc -l)" -eq 25 \
    && (cd /cli-dependencies && sha256sum --strict -c /cli-patch/cli-dependencies.sha256) \
    && mkdir /cli-proof \
    && javac --release 17 -cp target/keycloak-admin-cli-26.7.3.jar -d /cli-proof /cli-patch/CliJacksonProof.java \
    && java -Xmx256m -cp /cli-proof:target/keycloak-admin-cli-26.7.3.jar CliJacksonProof

# Compile and test generated provider output inside BuildKit, never in the Git checkout.
FROM maven@sha256:d6e32a254897415a445654a3c43c30fbc731a4d946cf5c66d1cb9184141c20c1 AS provider-build
WORKDIR /provider
COPY pom.xml ./pom.xml
COPY src/ ./src/
RUN mvn -B -ntp verify

FROM quay.io/keycloak/keycloak@sha256:ff4257d0d64efbe99ed1ddfaf07765cc3c36dc7518bf8324d41961327f441c54 AS distribution

# Exact annotation-only ordering patch; vendor method Code attributes stay intact.
FROM maven@sha256:9b0bc1420b93f3eafb7401dc3030eac01e773d8d709686d3c4ac351fcf6f9f42 AS descriptor-ordering
COPY --from=distribution /opt/keycloak/lib/lib /vendor
COPY build-patches/QuarkusDescriptorOrderingPatch.java build-patches/DescriptorOrderingProof.java /patch/
RUN mkdir /patch/classes \
    && javac --release 17 -proc:none -classpath '/vendor/boot/*:/vendor/main/*:/vendor/deployment/*' \
       -d /patch/classes /patch/QuarkusDescriptorOrderingPatch.java /patch/DescriptorOrderingProof.java \
    && java -classpath '/patch/classes:/vendor/boot/*:/vendor/main/*:/vendor/deployment/*' DescriptorOrderingProof baseline \
    && java -classpath '/patch/classes:/vendor/boot/*:/vendor/main/*:/vendor/deployment/*' QuarkusDescriptorOrderingPatch \
       /vendor/deployment/io.quarkus.quarkus-hibernate-orm-deployment-3.33.3.1.jar /patch/descriptor-ordering.jar \
    && java -classpath '/patch/classes:/patch/descriptor-ordering.jar:/vendor/boot/*:/vendor/main/*:/vendor/deployment/*' DescriptorOrderingProof patched

# Java 21 is supported by Keycloak 26.7.3. Retain the upstream distribution,
# replace the vendor JRE/OS with the tested patched Temurin runtime.
FROM eclipse-temurin:21-jre-jammy@sha256:eebd356ad7358b7094758e5787a6726f332917cfd56feab6457c56dab895cdbf
RUN apt-get update && apt-get upgrade -y && apt-get clean && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 1000 keycloak \
    && useradd --uid 1000 --gid 0 --home-dir /opt/keycloak --no-create-home --shell /usr/sbin/nologin keycloak
COPY --from=distribution --chown=1000:0 /opt/keycloak /opt/keycloak
COPY --from=admin-cli-build --chown=1000:0 /src/integration/client-cli/admin-cli/target/keycloak-admin-cli-26.7.3.jar /opt/keycloak/bin/client/keycloak-admin-cli-26.7.3.jar
# Validate the final shaded CLI even when scanner advisory data or metadata is incomplete.
RUN --network=none --mount=type=bind,from=admin-cli-build,source=/cli-proof,target=/cli-proof,readonly \
    java -Xmx256m -cp /cli-proof:/opt/keycloak/bin/client/keycloak-admin-cli-26.7.3.jar CliJacksonProof
COPY --from=descriptor-ordering --chown=1000:0 /patch/descriptor-ordering.jar /opt/keycloak/lib/lib/deployment/io.quarkus.quarkus-hibernate-orm-deployment-3.33.3.1.jar
COPY --from=descriptor-ordering --chown=1000:0 /patch/descriptor-ordering.jar.provenance.json /opt/keycloak/otziv-descriptor-ordering-provenance.json
LABEL otziv.quarkus-descriptor-ordering="3.33.3.1-otziv.1"
# Quarkus registers the upstream filename in its serialized build classpath.
# Keep that path, but replace the complete signed vendor JAR with 13.4.0.jre11;
# its manifest, Maven metadata and class bytes are not rewritten.
ADD --chown=1000:0 --checksum=sha256:e36f5237c1267983e5b88dc2169f6b9d7e50eceec6dc1ca31018e3877e14af66 \
    https://repo.maven.apache.org/maven2/com/microsoft/sqlserver/mssql-jdbc/13.4.0.jre11/mssql-jdbc-13.4.0.jre11.jar \
    /opt/keycloak/lib/lib/main/com.microsoft.sqlserver.mssql-jdbc-13.2.1.jre11.jar
# Full unchanged vendor artifacts with verified SHA; registered Quarkus paths remain stable.
# Actual versions and original/replacement hashes are recorded separately; filenames are not version evidence.
ADD --chown=1000:0 --checksum=sha256:1d82027afe574c63e24df72eb109f98de02dff3bb5adf81f0174b27088490067 \
    https://repo.maven.apache.org/maven2/com/fasterxml/jackson/datatype/jackson-datatype-jsr310/2.21.6/jackson-datatype-jsr310-2.21.6.jar \
    /opt/keycloak/lib/lib/main/com.fasterxml.jackson.datatype.jackson-datatype-jsr310-2.21.5.jar
ADD --chown=1000:0 --checksum=sha256:36111c3a4372cd5c2be6f4ec44a050382487920f3fc38ca3015c9c1678bd7c56 \
    https://repo.maven.apache.org/maven2/com/fasterxml/jackson/core/jackson-core/2.21.6/jackson-core-2.21.6.jar \
    /opt/keycloak/lib/lib/main/com.fasterxml.jackson.core.jackson-core-2.21.5.jar
ADD --chown=1000:0 --checksum=sha256:cc0c924b7b534d99bfe7038f6727dabb8b945eddb7832754b9e9b36ee43a0847 \
    https://repo.maven.apache.org/maven2/com/fasterxml/jackson/jakarta/rs/jackson-jakarta-rs-yaml-provider/2.21.6/jackson-jakarta-rs-yaml-provider-2.21.6.jar \
    /opt/keycloak/lib/lib/main/com.fasterxml.jackson.jakarta.rs.jackson-jakarta-rs-yaml-provider-2.21.5.jar
ADD --chown=1000:0 --checksum=sha256:24f5c6f75fad85d7e2e3f600ef4832a839def8a2bf1e6f2fc5a0577cef54e01d \
    https://repo.maven.apache.org/maven2/com/fasterxml/jackson/module/jackson-module-parameter-names/2.21.6/jackson-module-parameter-names-2.21.6.jar \
    /opt/keycloak/lib/lib/main/com.fasterxml.jackson.module.jackson-module-parameter-names-2.21.5.jar
ADD --chown=1000:0 --checksum=sha256:1682391f5deb36acbd34702eeeecd9f2f450c8e468504de05784a27aabdef5e3 \
    https://repo.maven.apache.org/maven2/com/fasterxml/jackson/dataformat/jackson-dataformat-cbor/2.21.6/jackson-dataformat-cbor-2.21.6.jar \
    /opt/keycloak/lib/lib/main/com.fasterxml.jackson.dataformat.jackson-dataformat-cbor-2.21.5.jar
ADD --chown=1000:0 --checksum=sha256:62134a98de12d0a421cd2f5b9854c8e63f10f524b0341e66cfd31a4326f6a329 \
    https://repo.maven.apache.org/maven2/com/fasterxml/jackson/core/jackson-databind/2.21.6/jackson-databind-2.21.6.jar \
    /opt/keycloak/lib/lib/main/com.fasterxml.jackson.core.jackson-databind-2.21.5.jar
ADD --chown=1000:0 --checksum=sha256:e5a4ea8c86f9f8d068bb280b69be5c450f263343b4bf1d81be8586d4e8d5c698 \
    https://repo.maven.apache.org/maven2/com/fasterxml/jackson/dataformat/jackson-dataformat-yaml/2.21.6/jackson-dataformat-yaml-2.21.6.jar \
    /opt/keycloak/lib/lib/main/com.fasterxml.jackson.dataformat.jackson-dataformat-yaml-2.21.5.jar
ADD --chown=1000:0 --checksum=sha256:4e8da867d765d2911d3484ef2f10d7a6f10ac45e1eda6b50c8505d0b4f997b0e \
    https://repo.maven.apache.org/maven2/com/fasterxml/jackson/jakarta/rs/jackson-jakarta-rs-base/2.21.6/jackson-jakarta-rs-base-2.21.6.jar \
    /opt/keycloak/lib/lib/main/com.fasterxml.jackson.jakarta.rs.jackson-jakarta-rs-base-2.21.5.jar
ADD --chown=1000:0 --checksum=sha256:ffcb606cc9fec4ad22c096fdc101bf1b6eb17b5f53c741ab306632ecdf4a438e \
    https://repo.maven.apache.org/maven2/com/fasterxml/jackson/datatype/jackson-datatype-jdk8/2.21.6/jackson-datatype-jdk8-2.21.6.jar \
    /opt/keycloak/lib/lib/main/com.fasterxml.jackson.datatype.jackson-datatype-jdk8-2.21.5.jar
ADD --chown=1000:0 --checksum=sha256:9fe4f941ba14dfa49b6120a0f2c307b06bfd60bce797f9cfed72a2abca8befdf \
    https://repo.maven.apache.org/maven2/com/fasterxml/jackson/module/jackson-module-jakarta-xmlbind-annotations/2.21.6/jackson-module-jakarta-xmlbind-annotations-2.21.6.jar \
    /opt/keycloak/lib/lib/main/com.fasterxml.jackson.module.jackson-module-jakarta-xmlbind-annotations-2.21.5.jar
ADD --chown=1000:0 --checksum=sha256:03dc4dc834a928730d52a50952de0f30c35e0daf048889132be19ee1e5053a6c \
    https://repo.maven.apache.org/maven2/org/eclipse/parsson/parsson/1.1.9/parsson-1.1.9.jar \
    /opt/keycloak/lib/lib/boot/org.eclipse.parsson.parsson-1.1.7.jar
COPY --from=provider-build --chown=1000:0 /provider/target/otziv-security-generation.jar /opt/keycloak/providers/otziv-security-generation.jar
ENV KC_RUN_IN_CONTAINER=true
WORKDIR /opt/keycloak
USER 1000:0
# Preserve the full classpath so ordinary start/import can still augment it.
RUN /opt/keycloak/bin/kc.sh build --db=postgres --health-enabled=true --metrics-enabled=true \
    && /opt/keycloak/bin/kc.sh --version
EXPOSE 8080 8443 9000
ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]
