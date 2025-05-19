## Используйте официальный образ OpenJDK для Java 19
#FROM openjdk:19-jdk-alpine
## Установите рабочую директорию внутри контейнера
#WORKDIR /app
## Скопируйте JAR-файл приложения в контейнер
#COPY target/otziv-1.jar /app/app.jar
## Экспонируйте порт, на котором работает приложение
#EXPOSE 8080
## Запустите приложение при старте контейнера
#CMD ["java", "-jar", "app.jar"]


# Этап 1: Сборка
FROM maven:3.9.8-eclipse-temurin-22-alpine AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests
VOLUME /app/logs

# Этап 2: Финальный образ для выполнения
FROM eclipse-temurin:22-jdk-alpine
RUN apk add --no-cache curl
WORKDIR /app
COPY --from=build /app/target/otziv-1.jar /app/app.jar

VOLUME /app/logs
EXPOSE 8080

## Установите метку BUILD_NO_CACHE
LABEL BUILD_NO_CACHE="1"

CMD ["java", "-jar", "app.jar"]


# Многоэтапная сборка

## Этап 1: Сборка приложения с помощью Maven
#FROM maven:3.9.8-eclipse-temurin-22-alpine AS build
#WORKDIR /app
#COPY pom.xml .
#COPY src ./src
##RUN apt-get update && apt-get install -y maven
#RUN mvn clean package -DskipTests
## ENTRYPOINT команда, указывающая, какой shell использовать
#ENTRYPOINT ["/bin/sh", "-c", "java -jar your-application.jar"]
#VOLUME /app/logs
#
## Этап 2: Создание контейнера для выполнения приложения
#FROM maven:3.9.8-eclipse-temurin-22-alpine
#WORKDIR /app
#COPY --from=build /app/target/otziv-1.jar /app/app.jar
#EXPOSE 8080
## Установите метку BUILD_NO_CACHE
#LABEL BUILD_NO_CACHE="1"
#
#CMD ["java", "-jar", "app.jar"]













#FROM mysql:latest
#
## Установка необходимых пакетов
#RUN apt-get update && apt-get install -y \
#    mariadb-client \
#    && rm -rf /var/lib/apt/lists/*

# Любые другие настройки или команды, если нужно


# Установка Maven
#RUN apt-get update && apt-get install -y maven



#maven:3.9.8-eclipse-temurin-22-jammy


#FROM ubuntu:latest
#LABEL authors="Hunt"
#
#ENTRYPOINT ["top", "-b"]


#Как изменить dockerfile на java 22? # Многоэтапная сборка
## Этап 1: Сборка приложения с помощью Maven
#FROM maven:3.8.5-openjdk-18 AS build
#WORKDIR /app
#COPY pom.xml .
#COPY src ./src
#RUN mvn clean package -DskipTests
#
## Этап 2: Создание контейнера для выполнения приложения
#FROM openjdk:19-jdk-alpine
#WORKDIR /app
#COPY --from=build /app/target/otziv-1.jar /app/app.jar
#EXPOSE 8080
#
## Установите метку BUILD_NO_CACHE
#LABEL BUILD_NO_CACHE="1"
#
#CMD ["java", "-jar", "app.jar"]