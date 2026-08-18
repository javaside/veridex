package io.veridex.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Phase 5-d 镜像/部署静态契约：文件级断言不依赖 Docker，
 * 任何破坏非 root、只读根文件系统或代理边界的改动都会在此失败。
 */
class DockerfileStaticContractTest {

    private static final Path ROOT = Path.of("..");

    private static String read(Path path) throws IOException {
        return Files.readString(ROOT.resolve(path));
    }

    @Test
    void backendDockerfileUsesMultiStageBuildWithPinnedImages() throws IOException {
        String dockerfile = read(Path.of("backend/Dockerfile"));
        assertThat(dockerfile).contains("FROM maven:3.9-eclipse-temurin-21 AS build");
        assertThat(dockerfile).contains("FROM eclipse-temurin:21-jre-alpine");
        // 运行层只复制可执行 JAR，不带 Maven cache / 源码 / 测试产物
        assertThat(dockerfile)
                .contains(
                        "COPY --from=build --chown=10001:10001 /build/backend/target/veridex-backend-*.jar"
                                + " /app/veridex-backend.jar");
        // 非 root 固定 UID/GID
        assertThat(dockerfile).contains("USER 10001:10001");
        // 容器感知 JVM 参数，不写死堆大小
        assertThat(dockerfile).contains("MaxRAMPercentage");
        // exec-form entrypoint 保证 SIGTERM 转发给 JVM 优雅停机
        assertThat(dockerfile).containsPattern("ENTRYPOINT \\[");
        assertThat(dockerfile).doesNotContain("ENTRYPOINT sh");
        // 唯一可写路径 /tmp/veridex-parser 由编排层提供
        assertThat(dockerfile).contains("VERIDEX_PARSER_TEMP_ROOT=/tmp/veridex-parser");
        // OCI 供应链标签
        assertThat(dockerfile).contains("org.opencontainers.image");
        assertThat(dockerfile).contains("EXPOSE 8080 8081");
    }

    @Test
    void webDockerfileBuildsStaticNginxImageWithoutToolchain() throws IOException {
        String dockerfile = read(Path.of("web/Dockerfile"));
        assertThat(dockerfile).contains("FROM node:22-alpine AS build");
        assertThat(dockerfile).contains("npm ci");
        // 镜像构建只做 production build，不重复跑 lint/test（仓库门禁已覆盖）
        assertThat(dockerfile).doesNotContain("npm run lint");
        assertThat(dockerfile).doesNotContain("npm test");
        assertThat(dockerfile).contains("nginx-unprivileged:1.27-alpine");
        // 运行层只有 dist、nginx 配置/模板与固定错误页
        assertThat(dockerfile).contains("COPY --from=build");
        assertThat(dockerfile).contains("deploy/nginx/nginx.conf");
        assertThat(dockerfile).contains("deploy/nginx/default.conf.template");
        assertThat(dockerfile).contains("VERIDEX_BACKEND_UPSTREAM=veridex-backend:8080");
        assertThat(dockerfile).contains("USER 101:101");
        assertThat(dockerfile).contains("EXPOSE 8080");
    }

    @Test
    void nginxConfigPreservesSameOriginProxyBoundary() throws IOException {
        String main = read(Path.of("deploy/nginx/nginx.conf"));
        String server = read(Path.of("deploy/nginx/default.conf.template"));
        // 主配置：非特权端口、/tmp 临时目录（只读根文件系统 + tmpfs/emptyDir 承载）、include 渲染产物
        assertThat(main).contains("pid /tmp/nginx.pid");
        assertThat(main).contains("proxy_temp_path /tmp/proxy_temp");
        assertThat(main).contains("client_body_temp_path /tmp/client_body");
        assertThat(main).contains("fastcgi_temp_path /tmp/fastcgi_temp");
        assertThat(main).contains("uwsgi_temp_path /tmp/uwsgi_temp");
        assertThat(main).contains("scgi_temp_path /tmp/scgi_temp");
        assertThat(main).contains("include /etc/nginx/conf.d/*.conf;");
        // server 模板：只代理 /api 与 /v3/api-docs，绝不代理 /actuator
        assertThat(server).contains("listen 8080");
        assertThat(server).contains("location /api/");
        assertThat(server).contains("/v3/api-docs");
        assertThat(server).doesNotContain("location /actuator");
        // upstream 由环境变量注入，不写死编排层服务名
        assertThat(server).contains("${VERIDEX_BACKEND_UPSTREAM}");
        // SPA fallback 与缓存策略
        assertThat(server).contains("try_files $uri $uri/ /index.html");
        assertThat(server).contains("no-cache");
        assertThat(server).contains("immutable");
        // SSE：API 代理关闭缓冲并放宽读超时
        assertThat(server).contains("proxy_buffering off");
        assertThat(server).contains("proxy_read_timeout 3600s");
        // 保留 request id 与转发头；上传预算与后端 multipart 55MB 对齐
        assertThat(main).contains("X-Request-Id");
        assertThat(main).contains("X-Forwarded-For");
        assertThat(server).contains("client_max_body_size 55m");
        // upstream 故障只回固定错误页
        assertThat(server).contains("error_page 502 503 504");
        assertThat(server).contains("internal");
    }
}
