FROM quay.io/keycloak/keycloak@sha256:ff4257d0d64efbe99ed1ddfaf07765cc3c36dc7518bf8324d41961327f441c54 AS distribution

# Java 21 is supported by Keycloak 26.7.3. Retain the upstream distribution,
# replace the vendor JRE/OS with the tested patched Temurin runtime.
FROM eclipse-temurin:21-jre-jammy@sha256:eebd356ad7358b7094758e5787a6726f332917cfd56feab6457c56dab895cdbf
RUN apt-get update && apt-get upgrade -y && apt-get clean && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 1000 keycloak \
    && useradd --uid 1000 --gid 0 --home-dir /opt/keycloak --no-create-home --shell /usr/sbin/nologin keycloak
COPY --from=distribution --chown=1000:0 /opt/keycloak /opt/keycloak
# Quarkus registers the upstream filename in its serialized build classpath.
# Keep that path, but replace the complete signed vendor JAR with 13.4.0.jre11;
# its manifest, Maven metadata and class bytes are not rewritten.
ADD --chown=1000:0 --checksum=sha256:e36f5237c1267983e5b88dc2169f6b9d7e50eceec6dc1ca31018e3877e14af66 \
    https://repo.maven.apache.org/maven2/com/microsoft/sqlserver/mssql-jdbc/13.4.0.jre11/mssql-jdbc-13.4.0.jre11.jar \
    /opt/keycloak/lib/lib/main/com.microsoft.sqlserver.mssql-jdbc-13.2.1.jre11.jar
COPY --chown=1000:0 target/otziv-security-generation.jar /opt/keycloak/providers/otziv-security-generation.jar
ENV KC_RUN_IN_CONTAINER=true
WORKDIR /opt/keycloak
USER 1000:0
# Preserve the full classpath so ordinary start/import can still augment it.
RUN /opt/keycloak/bin/kc.sh build --db=postgres --health-enabled=true --metrics-enabled=true \
    && /opt/keycloak/bin/kc.sh --version
EXPOSE 8080 8443 9000
ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]
