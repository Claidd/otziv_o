# syntax=docker/dockerfile:1

FROM maven:3.9.15-eclipse-temurin-25-alpine@sha256:dc63612f353a8500d46ece1a86e4a736d65836328c57d13eed8dd1ef46b8dad7 AS build
WORKDIR /app
COPY build-support ./build-support
COPY pom.xml .
COPY src ./src
# A cached RUN layer does not restore the mutable Maven cache mount. Install
# downstream build artifacts in the same execution that consumes them, including
# after cache eviction; serialize users of the shared local Maven repository.
RUN --mount=type=cache,target=/root/.m2,sharing=locked \
    mvn -B -ntp -f build-support/pom.xml install \
    && mvn -B -ntp clean package -DskipTests

# PostgreSQL 17 client for the encrypted Keycloak database backup: pg_dump refuses a
# newer server, and keycloak-postgres runs PostgreSQL 17.11. Ubuntu jammy ships only 14.
# Exact PGDG jammy packages are pinned by SHA-256 taken from the jammy-pgdg index signed
# by the PGDG key B97B0AFCAA1A47F044F244A07FCC7D46ACCC4CF8; apt-archive keeps every version.
FROM eclipse-temurin:25-jre-jammy@sha256:6a11121e5419a1eb4def2bba2f74a4c1b560395b9c26b52e37485d2bf58f7958 AS pgdg-client
ADD --checksum=sha256:5ec37493c1512b83095ac02f774fe8b6620a3442c5abfd0b6d9b268e49b0d0d9 \
    https://apt-archive.postgresql.org/pub/repos/apt/pool/main/p/postgresql-common/postgresql-client-common_293.pgdg22.04%2B1_all.deb \
    /pgdg/postgresql-client-common.deb
ADD --checksum=sha256:8af0b458834e219f3aa7c904fa1237ea484950486e3165a3a90204878e060530 \
    https://apt-archive.postgresql.org/pub/repos/apt/pool/main/p/postgresql-18/libpq5_18.6-1.pgdg22.04%2B2_amd64.deb \
    /pgdg/libpq5.deb
ADD --checksum=sha256:3719f32e1484b723c225cfb8c2cfdbf31bb56b8593c20c5264bab0376326e22f \
    https://apt-archive.postgresql.org/pub/repos/apt/pool/main/p/postgresql-17/postgresql-client-17_17.11-1.pgdg22.04%2B2_amd64.deb \
    /pgdg/postgresql-client-17.deb

FROM eclipse-temurin:25-jre-jammy@sha256:6a11121e5419a1eb4def2bba2f74a4c1b560395b9c26b52e37485d2bf58f7958
RUN --mount=type=bind,from=pgdg-client,source=/pgdg,target=/tmp/pgdg \
    test "$(dpkg --print-architecture)" = amd64 \
    && apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl default-mysql-client libssl3 openssl libbrotli1=1.0.9-2ubuntu0.1 apt=2.4.15ubuntu0.1 libapt-pkg6.0=2.4.15ubuntu0.1 \
        /tmp/pgdg/postgresql-client-common.deb /tmp/pgdg/libpq5.deb /tmp/pgdg/postgresql-client-17.deb \
    && rm -rf /var/lib/apt/lists/* \
    && /usr/lib/postgresql/17/bin/pg_dump --version | grep -Fx "pg_dump (PostgreSQL) 17.11 (Ubuntu 17.11-1.pgdg22.04+2)"

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
RUN mkdir -p /app/logs /app/backup /app/mobile-releases \
    && chown -R otziv:otziv /app
COPY --from=build --chown=otziv:otziv /app/target/otziv-1.jar /app/app.jar

VOLUME /app/logs

EXPOSE 8080

USER otziv:otziv

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=5 \
    CMD curl -fsS http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "exec java ${JAVA_OPTS:-} -jar /app/app.jar"]
