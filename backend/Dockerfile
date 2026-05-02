# syntax=docker/dockerfile:1

FROM maven:3.9.8-eclipse-temurin-22-alpine AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests

FROM eclipse-temurin:22-jdk-jammy
RUN apt-get update \
    && apt-get install -y --no-install-recommends mysql-client \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY --from=build /app/target/otziv-1.jar /app/app.jar

VOLUME /app/logs
VOLUME /app/sent-hashes

EXPOSE 8080

CMD ["java", "-jar", "app.jar"]
