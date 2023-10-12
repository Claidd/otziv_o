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




# Многоэтапная сборка
# Этап 1: Сборка приложения с помощью Maven
FROM maven:3.8.5-openjdk-18 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests

# Этап 2: Создание контейнера для выполнения приложения
FROM openjdk:19-jdk-alpine
WORKDIR /app
COPY --from=build /app/target/otziv-1.jar /app/app.jar
EXPOSE 8080

# Установите метку BUILD_NO_CACHE
LABEL BUILD_NO_CACHE="1"

CMD ["java", "-jar", "app.jar"]




#FROM ubuntu:latest
#LABEL authors="Hunt"
#
#ENTRYPOINT ["top", "-b"]