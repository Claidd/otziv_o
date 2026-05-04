# syntax=docker/dockerfile:1

FROM maven:3.9.8-eclipse-temurin-22-alpine AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp clean package -DskipTests

FROM eclipse-temurin:22-jre-jammy
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl default-mysql-client \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY --from=build /app/target/otziv-1.jar /app/app.jar

VOLUME /app/logs
VOLUME /app/sent-hashes

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=5 \
    CMD curl -fsS http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS:-} -jar /app/app.jar"]
