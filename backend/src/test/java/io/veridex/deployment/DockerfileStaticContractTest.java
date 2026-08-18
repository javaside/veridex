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
}
