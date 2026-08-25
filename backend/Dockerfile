FROM maven:3.9.11-eclipse-temurin-21 AS build

WORKDIR /workspace
COPY backend/pom.xml backend/pom.xml
COPY backend/libs backend/libs
COPY backend/src backend/src
RUN --mount=type=cache,target=/root/.m2 \
    mvn -f backend/pom.xml -Dmaven.test.skip=true package \
    && jar tf backend/target/fulfillment-hub-0.1.0-SNAPSHOT.jar \
        | grep -q 'BOOT-INF/lib/lop-opensdk-support-1.0.30.jar' \
    && jar tf backend/target/fulfillment-hub-0.1.0-SNAPSHOT.jar \
        | grep -q 'BOOT-INF/lib/integrated-supply-chain-isc-6.1-20260707185402.jar'

FROM eclipse-temurin:21-jre-alpine

# 三平台订单刷新/回传通道（platform-pull/push）：容器内直接执行拉取与回填脚本（python3 + requests）。
# 回传文件的 xlsx 解析由 Java 侧（POI）完成，脚本只收结构化输入，无需容器内 openpyxl。
RUN apk add --no-cache python3 py3-requests \
    && addgroup -S app \
    && adduser -S -G app app \
    && mkdir -p /var/lib/zimu-fulfillment/files \
    && mkdir -p /app/platform-pull-work \
    && chown -R app:app /var/lib/zimu-fulfillment /app/platform-pull-work
WORKDIR /app
COPY --from=build /workspace/backend/target/fulfillment-hub-0.1.0-SNAPSHOT.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
