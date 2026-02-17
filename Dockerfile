# syntax=docker/dockerfile:1
FROM maven:3.9.6-eclipse-temurin-11 AS build

WORKDIR /workspace
COPY . .

RUN mvn -pl Mustang-API -am -DskipTests package
RUN cp Mustang-API/target/Mustang-API-*.jar /tmp/mustang-api.jar

FROM eclipse-temurin:11-jre

WORKDIR /app
COPY --from=build /tmp/mustang-api.jar /app/mustang-api.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/mustang-api.jar"]
