# ---------- 构建阶段：固定 Maven/JDK21，可重复 package ----------
# 注意：必须安装 unzip——mvnw 在无 unzip 时会改下载 .tar.gz 发行版，
# 与 maven-wrapper.properties 固定的 .zip SHA-256 校验和不匹配导致构建失败。
FROM maven:3.9-eclipse-temurin-21 AS build
RUN microdnf install -y unzip || apt-get update -qq && apt-get install -y -qq unzip
WORKDIR /build
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY backend/pom.xml backend/
RUN --mount=type=cache,target=/root/.m2 ./mvnw -pl backend -am dependency:go-offline -B -DskipTests
COPY backend/src backend/src
RUN --mount=type=cache,target=/root/.m2 ./mvnw -pl backend package -B -DskipTests

# ---------- 运行阶段：精简 JRE alpine（busybox wget 供 healthcheck/probes）----------
FROM eclipse-temurin:21-jre-alpine
ENV TZ=UTC \
    VERIDEX_PARSER_TEMP_ROOT=/tmp/veridex-parser
RUN addgroup -g 10001 -S veridex && adduser -u 10001 -S -G veridex -H -D veridex \
    && mkdir -p /app /tmp/veridex-parser \
    && chown -R 10001:10001 /app /tmp/veridex-parser
COPY --from=build --chown=10001:10001 /build/backend/target/veridex-backend-*.jar /app/veridex-backend.jar
LABEL org.opencontainers.image.title="veridex-backend" \
      org.opencontainers.image.source="https://github.com/veridex/veridex" \
      org.opencontainers.image.description="Veridex RAG platform backend" \
      org.opencontainers.image.licenses="UNLICENSED"
EXPOSE 8080 8081
USER 10001:10001
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-Djava.security.egd=file:/dev/./urandom", "-jar", "/app/veridex-backend.jar"]
