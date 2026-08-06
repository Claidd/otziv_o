# syntax=docker/dockerfile:1

FROM maven:3.9.15-eclipse-temurin-26-alpine@sha256:9b0bc1420b93f3eafb7401dc3030eac01e773d8d709686d3c4ac351fcf6f9f42 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp clean package -DskipTests

FROM eclipse-temurin:26-jre-jammy@sha256:3e83fe06950ab45b8fee3c8fccdbb3dc0cc203c4518babdd9e8957c31284c9d5
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl default-mysql-client openssl \
    && rm -rf /var/lib/apt/lists/*

ARG OTZIV_UID=10001
ARG OTZIV_GID=10001
RUN groupadd --system --gid "$OTZIV_GID" otziv \
    && useradd --system --uid "$OTZIV_UID" --gid "$OTZIV_GID" \
        --home-dir /app --shell /usr/sbin/nologin otziv

# MAX and some T-Bank endpoints use chains rooted at the official Russian
# Trusted Root CA. Validate the published certificate fingerprints before adding
# them to the OS and JVM trust stores; TLS verification remains enabled.
COPY docker/certs/russian_trusted_root_ca.crt /usr/local/share/ca-certificates/russian_trusted_root_ca.crt
COPY docker/certs/russian_trusted_sub_ca.crt /usr/local/share/ca-certificates/russian_trusted_sub_ca.crt
COPY docker/certs/russian_trusted_sub_ca_2024.crt /usr/local/share/ca-certificates/russian_trusted_sub_ca_2024.crt
RUN test "$(openssl x509 -in /usr/local/share/ca-certificates/russian_trusted_root_ca.crt -noout -fingerprint -sha256 | cut -d= -f2)" = "D2:6D:2D:02:31:B7:C3:9F:92:CC:73:85:12:BA:54:10:35:19:E4:40:5D:68:B5:BD:70:3E:97:88:CA:8E:CF:31" \
    && test "$(openssl x509 -in /usr/local/share/ca-certificates/russian_trusted_sub_ca.crt -noout -fingerprint -sha256 | cut -d= -f2)" = "BB:BD:E2:10:3E:79:0B:99:9E:C6:2B:D0:3C:F6:25:A5:A2:E7:C3:16:E1:0A:FE:6A:49:0E:ED:EA:D8:B3:FD:9B" \
    && test "$(openssl x509 -in /usr/local/share/ca-certificates/russian_trusted_sub_ca_2024.crt -noout -fingerprint -sha256 | cut -d= -f2)" = "21:55:78:50:36:C9:00:DB:B5:F1:BB:2A:15:69:C8:0C:55:59:5B:D6:BF:94:86:7A:29:BB:DD:BC:7D:88:A3:F2" \
    && update-ca-certificates \
    && keytool -importcert -noprompt -trustcacerts \
        -alias russian-trusted-root-ca \
        -file /usr/local/share/ca-certificates/russian_trusted_root_ca.crt \
        -keystore "$JAVA_HOME/lib/security/cacerts" \
        -storepass changeit \
    && keytool -importcert -noprompt -trustcacerts \
        -alias russian-trusted-sub-ca \
        -file /usr/local/share/ca-certificates/russian_trusted_sub_ca.crt \
        -keystore "$JAVA_HOME/lib/security/cacerts" \
        -storepass changeit \
    && keytool -importcert -noprompt -trustcacerts \
        -alias russian-trusted-sub-ca-2024 \
        -file /usr/local/share/ca-certificates/russian_trusted_sub_ca_2024.crt \
        -keystore "$JAVA_HOME/lib/security/cacerts" \
        -storepass changeit

WORKDIR /app
RUN mkdir -p /app/logs /app/sent-hashes /app/backup /app/mobile-releases \
    && chown -R otziv:otziv /app
COPY --from=build --chown=otziv:otziv /app/target/otziv-1.jar /app/app.jar

VOLUME /app/logs
VOLUME /app/sent-hashes

EXPOSE 8080

USER otziv:otziv

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=5 \
    CMD curl -fsS http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS:-} -jar /app/app.jar"]
