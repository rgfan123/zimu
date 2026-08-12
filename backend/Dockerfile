# syntax=docker/dockerfile:1.7
FROM maven:3.9.11-eclipse-temurin-21 AS build

WORKDIR /workspace
COPY backend/pom.xml backend/pom.xml
COPY backend/libs backend/libs
COPY backend/src backend/src
RUN --mount=type=cache,target=/root/.m2 \
    mvn -f backend/pom.xml -Dmaven.test.skip=true package

FROM eclipse-temurin:21-jre-alpine

RUN addgroup -S app \
    && adduser -S -G app app \
    && mkdir -p /var/lib/zimu-fulfillment/files \
    && chown -R app:app /var/lib/zimu-fulfillment
WORKDIR /app
COPY --from=build /workspace/backend/target/fulfillment-hub-0.1.0-SNAPSHOT.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
