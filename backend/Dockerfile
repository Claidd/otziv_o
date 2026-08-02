# syntax=docker/dockerfile:1

FROM maven:3.9.15-eclipse-temurin-26-alpine AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp clean package -DskipTests

FROM eclipse-temurin:26-jre-jammy
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl default-mysql-client openssl \
    && rm -rf /var/lib/apt/lists/*

# MAX moved its API to a chain rooted at the official Russian Trusted Root CA.
# Validate the published certificate fingerprint before adding it to the OS and
# JVM trust stores; TLS verification remains enabled for every request.
COPY docker/certs/russian_trusted_root_ca.crt /usr/local/share/ca-certificates/russian_trusted_root_ca.crt
RUN test "$(openssl x509 -in /usr/local/share/ca-certificates/russian_trusted_root_ca.crt -noout -fingerprint -sha256 | cut -d= -f2)" = "D2:6D:2D:02:31:B7:C3:9F:92:CC:73:85:12:BA:54:10:35:19:E4:40:5D:68:B5:BD:70:3E:97:88:CA:8E:CF:31" \
    && update-ca-certificates \
    && keytool -importcert -noprompt -trustcacerts \
        -alias russian-trusted-root-ca \
        -file /usr/local/share/ca-certificates/russian_trusted_root_ca.crt \
        -keystore "$JAVA_HOME/lib/security/cacerts" \
        -storepass changeit

WORKDIR /app
COPY --from=build /app/target/otziv-1.jar /app/app.jar

VOLUME /app/logs
VOLUME /app/sent-hashes

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=5 \
    CMD curl -fsS http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS:-} -jar /app/app.jar"]
