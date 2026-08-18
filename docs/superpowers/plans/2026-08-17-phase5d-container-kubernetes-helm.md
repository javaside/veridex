# Phase 5-d Container, Kubernetes and Helm Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Execute tasks in order and do not delegate them to subagents.

**Goal:** 把 Veridex 后端与前端构建为非 root、只读根文件系统的容器镜像，让 Compose 能启动完整应用栈，并提供只管理 backend/web 的原生 Helm chart（existing Secret 接入外部基础设施、默认 ClusterIP、可选 Ingress、默认 NetworkPolicy）与受限网络离线交付物。

**Architecture:** backend 与 web 各自多阶段构建为独立镜像；web 镜像内 Nginx 同源代理 `/api` 与 `/v3/api-docs`，不代理 `/actuator`，upstream 通过 `VERIDEX_BACKEND_UPSTREAM` 环境变量注入（Compose 指向 `backend:8080`，K8s 指向 `<release>-veridex-backend:8080`）。Helm chart 位于 `deploy/helm/veridex`，只创建应用侧资源，PostgreSQL/RabbitMQ/MinIO/OpenSearch/OTel Collector 通过 values 中的 endpoint 与预先存在的 Secret 接入。Prometheus 抓取与 K8s probes 全部走 8081 管理端口。镜像构建、chart lint/template、kind 集群验收和离线导入全部收敛进 `scripts/verify-deployment.sh`。

**Tech Stack:** Docker BuildKit、Eclipse Temurin JRE 21（alpine）、nginx-unprivileged 1.27（envsubst 模板）、Node 22 npm ci、Docker Compose v2、Helm 3.13+、kind v0.24+、kubectl、Bash、Python 3（YAML 断言）、JUnit 5（Dockerfile/nginx/compose/chart 静态契约测试）。

## Global Constraints

- backend/web 双镜像：backend 为 Java 21 运行时镜像，web 为非 root Nginx 静态镜像；不使用单一胖镜像。
- 两个运行镜像必须：非 root 固定 UID/GID、只读根文件系统、`allowPrivilegeEscalation: false`、drop 全部 capabilities、seccomp `RuntimeDefault`、不含 Maven cache/源码/Node 工具链/测试产物/明文 secret。
- web 只代理 `/api` 与 `/v3/api-docs`；`/actuator` 一律不进入 web 代理和 Ingress；backend 业务端口 8080、管理端口 8081，管理端口只通过独立 ClusterIP Service 暴露。
- Helm chart 只管理 backend/web 资源；不部署任何有状态基础设施；chart 不生成可用生产 secret，不在 values/ConfigMap/annotations/NOTES 保存明文凭据。
- 镜像引用：digest 非空时用 `repository@sha256:...`，否则用非 `latest` tag；`global.imageRegistry` 可重写全部应用镜像仓库；chart 默认值不得为 `latest`。
- existing Secret 固定 key：`db-username`、`db-password`、`rabbitmq-username`、`rabbitmq-password`、`minio-access-key`、`minio-secret-key`、`session-secret`，可选 trace key 与模型 key（trace capture 为 `ERRORS/ALL` 时才必须提供 trace key，默认 `NONE` 不要求）。
- 默认 NetworkPolicy：deny all 后仅放行 web←入口、backend←web（8080）与监控（8081）、DNS 与显式声明的外部依赖（selector 或 CIDR 二选一）；不出现 `0.0.0.0/0` 放行。
- Ingress、ServiceMonitor、HPA 默认关闭；PDB 默认启用；anti-affinity 只用 preferred，不得造成单节点 Pending。
- 离线交付 = 镜像清单 + SHA-256 + export/import/push 脚本 + 打包 chart + registry 覆盖 values；所有脚本 `set -euo pipefail`，缺镜像、校验失败、tag/digest 不一致必须非零退出。
- 本阶段不修改 5-a/5-b/5-c 已交付的应用行为：API key、CSRF、安全头、管理端口白名单、trace 边界、业务 API 契约全部保持；backend 现有测试与生产代码（除新增 deployment 契约测试）不动。
- 每个 Task 先 RED 后 GREEN，独立提交；提交尾注固定为 `Co-Authored-By: CodeTui <noreply@codetui.dev>`；每个任务结束运行 `git diff --check`。

---

## File Structure

**镜像与 Compose：**

- Create `backend/Dockerfile` — backend 多阶段构建（Maven build → JRE alpine 运行层）。
- Create `web/Dockerfile` — web 多阶段构建（Node build → nginx-unprivileged 运行层，含 envsubst 模板）。
- Create `deploy/nginx/nginx.conf` — Nginx 主配置（events/http 骨架、临时目录、代理头）。
- Create `deploy/nginx/default.conf.template` — server 块模板（SPA fallback、API 代理、SSE、缓存、固定错误页），upstream 由 `VERIDEX_BACKEND_UPSTREAM` 注入。
- Create `web/public/error_page.html` — upstream 故障固定 502/503 页面。
- Modify `deploy/compose/compose.yml` — 新增 `backend`、`web` 服务与 tmpfs。
- Modify `deploy/compose/.env.example` — 新增应用栈环境变量（无生产 secret）。
- Modify `deploy/compose/observability/prometheus/prometheus.yml` — veridex job 改抓 `backend:8081`。
- Modify `scripts/verify-security.sh` — Prometheus 抓取断言改为 `backend:8081`。
- Create `deploy/compose/smoke.sh` — Compose/集群共用的 web 冒烟测试。

**Helm chart：**

- Create `deploy/helm/veridex/Chart.yaml`、`values.yaml`、`values.schema.json`、`templates/*`（Task 4：工作负载与 Service；Task 5：Ingress/PDB/HPA/NetworkPolicy/ServiceMonitor）。
- Create `deploy/helm/veridex/ci/default-values.yaml`、`ci/ingress-values.yaml` — `helm lint`/`template` 用 values。

**集群与离线：**

- Create `deploy/kind/test-deps.yaml` — kind 验收用依赖 Pod + Secret。
- Create `deploy/kind/kind-values.yaml` — kind 验收 chart 覆盖 values。
- Create `deploy/kind/run-acceptance.sh` — kind 建群、装依赖、装 chart、smoke、滚动升级、清理。
- Create `deploy/offline/veridex-offline/images.txt`、`images.sha256`、`values/registry-values.yaml`、`scripts/export-images.sh`、`scripts/import-images.sh`、`scripts/push-images.sh`、`INSTALL.txt`。

**门禁与文档：**

- Create `scripts/verify-deployment.sh` — 镜像/Compose/chart/集群/离线五级校验。
- Modify `scripts/verify.sh` — 调用 `verify-deployment.sh`。
- Create `backend/src/test/java/io/veridex/deployment/DockerfileStaticContractTest.java` — Dockerfile/nginx/compose 静态契约。
- Create `backend/src/test/java/io/veridex/deployment/HelmChartStaticContractTest.java` — chart 文件与模板静态契约。
- Create `backend/src/test/java/io/veridex/deployment/DeploymentExitGateTest.java` — 交付物完整性 exit gate。
- Modify `README.md`、`docs/architecture.md` — 部署说明与运维入口。

---

### Task 1: Backend 容器镜像

**Files:**
- Create: `backend/Dockerfile`
- Create: `backend/src/test/java/io/veridex/deployment/DockerfileStaticContractTest.java`
- Create: `scripts/verify-deployment.sh`（本任务只实现 Stage 1）

**Interfaces:**
- Produces 本地镜像 `veridex-backend:0.1.0`：非 root UID/GID `10001/10001`，暴露 `8080`/`8081`，入口为 exec-form `java -XX:MaxRAMPercentage=75.0 -jar /app/veridex-backend.jar`。
- Produces `scripts/verify-deployment.sh`，用法：`./scripts/verify-deployment.sh [stage]`，stage ∈ `1|2|3|4|5|all`（默认 `all`），无 Docker 时自动降级为 host-only 静态检查并以 0 退出（打印 skipped 提示）。

- [ ] **Step 1: Write failing static contract test**

Create `backend/src/test/java/io/veridex/deployment/DockerfileStaticContractTest.java`（本任务先只放 backend 镜像断言，后续任务向该文件追加）：

```java
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
        assertThat(dockerfile).contains("COPY --from=build --chown=10001:10001 /build/backend/target/veridex-backend-*.jar /app/veridex-backend.jar");
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
```

- [ ] **Step 2: Run RED**

Run: `./mvnw -pl backend test -Dtest=DockerfileStaticContractTest`

Expected: FAIL — `backend/Dockerfile` 不存在，`Files.readString` 抛 `NoSuchFileException`。

- [ ] **Step 3: Write the backend Dockerfile**

Create `backend/Dockerfile`（构建上下文为仓库根，见 Step 5 构建命令，因此 `COPY` 路径相对根目录）：

```dockerfile
# syntax=docker/dockerfile:1
# ---------- 构建阶段：固定 Maven/JDK21，可重复 package ----------
FROM maven:3.9-eclipse-temurin-21 AS build
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
EXPOSE 8080 8080
USER 10001:10001
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-Djava.security.egd=file:/dev/./urandom", "-jar", "/app/veridex-backend.jar"]
```

说明：`revision`/`version`/`created` 等动态 label 由发布流程通过 `--build-arg` 补齐（见 Task 7 `INSTALL.txt` 中的多架构构建命令），仓库默认值保持静态可审计。

- [ ] **Step 4: Run GREEN**

Run: `./mvnw -pl backend test -Dtest=DockerfileStaticContractTest`

Expected: PASS。

- [ ] **Step 5: Implement verify-deployment.sh Stage 1**

Create `scripts/verify-deployment.sh`：

```bash
#!/usr/bin/env bash
set -euo pipefail

# Phase 5-d 部署校验门禁。
# 用法: ./scripts/verify-deployment.sh [stage]
#   stage: 1=镜像构建 2=镜像运行时检查 3=Compose 完整栈
#          4=Helm lint/template + kind 集群验收 5=离线包
#          all=1..5（默认）。无 Docker/kind/helm 时自动降级为 host-only 检查。
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
STAGE="${1:-all}"
BACKEND_IMAGE="veridex-backend:0.1.0"
WEB_IMAGE="veridex-web:0.1.0"
COMPOSE=(docker compose --env-file "${ROOT_DIR}/deploy/compose/.env.example" -f "${ROOT_DIR}/deploy/compose/compose.yml")

have_docker() { command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; }

stage1() {
  echo "==> [deploy-1] backend/web 镜像构建"
  have_docker || { echo "Docker unavailable; image build skipped (host-only mode)."; return 0; }
  docker build -f "${ROOT_DIR}/backend/Dockerfile" -t "${BACKEND_IMAGE}" "${ROOT_DIR}"
  echo "verify-deployment stage 1 (backend image) passed."
}

case "${STAGE}" in
  1) stage1 ;;
  all) stage1; echo "verify-deployment: later stages not yet implemented in this task." ;;
  *) echo "unknown or not-yet-implemented stage: ${STAGE}" >&2; exit 2 ;;
esac
```

Run: `chmod +x scripts/verify-deployment.sh && ./scripts/verify-deployment.sh 1`

Expected: 镜像构建成功（首次拉取基础镜像与下载 Maven 依赖耗时较长）；结尾打印 `verify-deployment stage 1 (backend image) passed.`。

- [ ] **Step 6: Commit**

```bash
git add backend/Dockerfile backend/src/test/java/io/veridex/deployment/DockerfileStaticContractTest.java scripts/verify-deployment.sh
git commit -m "feat: build reproducible non-root backend image" -m "Co-Authored-By: CodeTui <noreply@codetui.dev>"
```

---

### Task 2: Web 容器镜像与 Nginx 同源代理

**Files:**
- Create: `web/Dockerfile`
- Create: `deploy/nginx/nginx.conf`
- Create: `deploy/nginx/default.conf.template`
- Create: `web/public/error_page.html`
- Modify: `backend/src/test/java/io/veridex/deployment/DockerfileStaticContractTest.java`（追加 web 断言）
- Modify: `scripts/verify-deployment.sh`（新增 Stage 2）

**Interfaces:**
- Produces 本地镜像 `veridex-web:0.1.0`：非 root（nginx-unprivileged 默认 UID/GID `101/101`），监听 `8080`。
- Produces 环境变量契约 `VERIDEX_BACKEND_UPSTREAM`（默认 `veridex-backend:8080`）：nginx-unprivileged 入口脚本在启动时对 `/etc/nginx/templates/*.template` 做 envsubst 渲染到 `/etc/nginx/conf.d/`（只替换容器已定义的环境变量名，不影响 `$uri` 等 nginx 内建变量）。Compose 在 Task 3 覆盖为 `backend:8080`，K8s 在 Task 4 覆盖为 `<release>-veridex-backend:8080`。
- Produces 本地健康路径 `/healthz`（返回 200 `ok`，无日志）。
- Consumes Task 1 的 `verify-deployment.sh` 结构（`stage2` 追加到 `stage1` 之后，`all` 分支串联）。

- [ ] **Step 1: Append failing web contract assertions**

在 `DockerfileStaticContractTest.java` 中追加：

```java
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
```

- [ ] **Step 2: Run RED**

Run: `./mvnw -pl backend test -Dtest=DockerfileStaticContractTest`

Expected: FAIL — `web/Dockerfile`、`deploy/nginx/nginx.conf`、`deploy/nginx/default.conf.template` 不存在。

- [ ] **Step 3: Write nginx configs and error page**

Create `deploy/nginx/nginx.conf`（主配置：http 骨架、代理头、临时目录；server 块由模板渲染进 `conf.d`）：

```nginx
# Veridex web 镜像主配置。server 块由 /etc/nginx/templates/default.conf.template
# 经 envsubst 渲染到 /etc/nginx/conf.d/default.conf（upstream 来自 VERIDEX_BACKEND_UPSTREAM）。
worker_processes auto;
error_log /dev/stderr warn;
pid /tmp/nginx.pid;

events {
  worker_connections 1024;
}

http {
  include /etc/nginx/mime.types;
  default_type application/octet-stream;
  sendfile on;
  tcp_nopush on;
  keepalive_timeout 65;

  log_format main '$remote_addr - [$time_local] "$request" $status $body_bytes_sent rid=$http_x_request_id';
  access_log /dev/stdout main;

  proxy_temp_path /tmp/proxy_temp;
  client_body_temp_path /tmp/client_body;
  fastcgi_temp_path /tmp/fastcgi_temp;
  uwsgi_temp_path /tmp/uwsgi_temp;
  scgi_temp_path /tmp/scgi_temp;

  proxy_http_version 1.1;
  proxy_set_header Host $host;
  proxy_set_header X-Real-IP $remote_addr;
  proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
  proxy_set_header X-Forwarded-Proto $scheme;
  proxy_set_header X-Forwarded-Host $host;
  proxy_set_header X-Request-Id $http_x_request_id;
  proxy_set_header Connection "";

  include /etc/nginx/conf.d/*.conf;
}
```

Create `deploy/nginx/default.conf.template`：

```nginx
# 渲染变量：VERIDEX_BACKEND_UPSTREAM（默认 veridex-backend:8080，由编排层覆盖）。
upstream veridex_backend {
  server ${VERIDEX_BACKEND_UPSTREAM};
}

server {
  listen 8080;
  server_name _;
  root /usr/share/nginx/html;
  index index.html;

  # 与后端 spring.servlet.multipart max-request-size=55MB 对齐
  client_max_body_size 55m;

  # 本地存活检查（K8s readiness/liveness 与 Compose healthcheck 使用）
  location = /healthz {
    access_log off;
    return 200 "ok\n";
  }

  # SSE 流式问答：关闭缓冲、放宽读超时
  location /api/ {
    proxy_pass http://veridex_backend;
    proxy_buffering off;
    proxy_cache off;
    proxy_read_timeout 3600s;
    proxy_send_timeout 3600s;
  }

  location = /v3/api-docs {
    proxy_pass http://veridex_backend/v3/api-docs;
  }
  location /v3/api-docs/ {
    proxy_pass http://veridex_backend;
  }

  # upstream 故障只回固定错误页，不暴露 backend 地址或内部细节
  error_page 502 503 504 /error_page.html;
  location = /error_page.html {
    internal;
  }

  # SPA fallback 与缓存策略
  location = /index.html {
    add_header Cache-Control "no-cache";
  }
  location ~* \.(?:js|css|png|jpg|jpeg|gif|svg|ico|woff2?)$ {
    add_header Cache-Control "public, max-age=31536000, immutable";
  }
  location / {
    try_files $uri $uri/ /index.html;
  }
}
```

Create `web/public/error_page.html`：

```html
<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title>Veridex 服务暂不可用</title>
  <style>
    body { font-family: system-ui, sans-serif; display: flex; align-items: center; justify-content: center; height: 100vh; margin: 0; color: #1f2933; background: #f8f9fa; }
    main { text-align: center; }
    h1 { font-size: 1.25rem; margin-bottom: .5rem; }
    p { color: #52606d; }
  </style>
</head>
<body>
  <main>
    <h1>Veridex 服务暂不可用</h1>
    <p>请稍后重试；若问题持续，请联系平台管理员。</p>
  </main>
</body>
</html>
```

- [ ] **Step 4: Write the web Dockerfile**

Create `web/Dockerfile`（构建上下文为仓库根，使 `deploy/nginx/*` 可直接 COPY）：

```dockerfile
# syntax=docker/dockerfile:1
# ---------- 构建阶段：Node 22 + npm ci，仅 production build ----------
FROM node:22-alpine AS build
WORKDIR /build
COPY web/package.json web/package-lock.json ./
RUN npm ci
COPY web/ ./
RUN npm run build

# ---------- 运行阶段：非 root nginx，只读根文件系统友好 ----------
FROM nginx-unprivileged:1.27-alpine
# upstream 缺省值：编排层通过 VERIDEX_BACKEND_UPSTREAM 覆盖
ENV VERIDEX_BACKEND_UPSTREAM=veridex-backend:8080
COPY --chown=101:101 web/public/error_page.html /usr/share/nginx/html/error_page.html
COPY --from=build --chown=101:101 /build/dist/ /usr/share/nginx/html/
COPY --chown=101:101 deploy/nginx/nginx.conf /etc/nginx/nginx.conf
COPY --chown=101:101 deploy/nginx/default.conf.template /etc/nginx/templates/default.conf.template
EXPOSE 8080
USER 101:101
```

- [ ] **Step 5: Run GREEN**

Run: `./mvnw -pl backend test -Dtest=DockerfileStaticContractTest`

Expected: PASS。

- [ ] **Step 6: Implement and run verify-deployment Stage 2**

在 `scripts/verify-deployment.sh` 中新增 `stage2` 并把 case 更新为 `2) stage2 ;;`、`all` 串联 `stage1; stage2`：

```bash
stage2() {
  echo "==> [deploy-2] web 镜像构建 + 运行时非 root/只读检查"
  have_docker || { echo "Docker unavailable; image runtime checks skipped (host-only mode)."; return 0; }
  docker build -f "${ROOT_DIR}/web/Dockerfile" -t "${WEB_IMAGE}" "${ROOT_DIR}"
  # 镜像层契约：固定非 root 用户
  docker inspect -f '{{.Config.User}}' "${BACKEND_IMAGE}" | grep -Fxq '10001:10001'
  docker inspect -f '{{.Config.User}}' "${WEB_IMAGE}" | grep -Fxq '101:101'
  # backend：只读根文件系统 + tmpfs 解析目录下 JVM 可启动（java -version 即验证运行层自洽）
  docker run --rm --read-only --tmpfs /tmp/veridex-parser:size=2g,uid=10001,gid=10001 \
    --entrypoint java "${BACKEND_IMAGE}" -version
  # web：只读根文件系统 + /tmp 与 conf.d 可写（envsubst 渲染目标）
  docker run -d --rm --name veridex-web-check --read-only \
    --tmpfs /tmp:rw,noexec,nosuid,size=64m \
    --tmpfs /etc/nginx/conf.d:rw,noexec,nosuid,size=1m \
    -p 127.0.0.1:18090:8080 "${WEB_IMAGE}" >/dev/null
  trap 'docker rm -f veridex-web-check >/dev/null 2>&1 || true' EXIT
  sleep 2
  curl -fsS http://127.0.0.1:18090/healthz | grep -Fxq ok
  curl -fsS http://127.0.0.1:18090/ | grep -q '<div id="root">'
  # SPA fallback：未知路由回 index.html
  test "$(curl -fsS -o /dev/null -w '%{http_code}' http://127.0.0.1:18090/knowledge)" = "200"
  # upstream（默认 veridex-backend）在默认 bridge 网络不可解析 → 固定 502 错误页
  test "$(docker exec veridex-web-check curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/api/auth/csrf)" = "502"
  docker exec veridex-web-check curl -fsS http://127.0.0.1:8080/error_page.html | grep -q '服务暂不可用'
  echo "verify-deployment stage 2 (web image + runtime) passed."
}
```

Run: `./scripts/verify-deployment.sh 2`

Expected: 结尾打印 `verify-deployment stage 2 (web image + runtime) passed.`。

- [ ] **Step 7: Commit**

```bash
git add web/Dockerfile deploy/nginx/nginx.conf deploy/nginx/default.conf.template web/public/error_page.html backend/src/test/java/io/veridex/deployment/DockerfileStaticContractTest.java scripts/verify-deployment.sh
git commit -m "feat: build non-root web image with same-origin proxy" -m "Co-Authored-By: CodeTui <noreply@codetui.dev>"
```

---

### Task 3: Compose 完整应用栈与 smoke test

**Files:**
- Modify: `deploy/compose/compose.yml`
- Modify: `deploy/compose/.env.example`
- Modify: `deploy/compose/observability/prometheus/prometheus.yml`
- Modify: `scripts/verify-security.sh`
- Create: `deploy/compose/smoke.sh`
- Modify: `backend/src/test/java/io/veridex/deployment/DockerfileStaticContractTest.java`（追加 compose 断言）
- Modify: `scripts/verify-deployment.sh`（新增 Stage 3）

**Interfaces:**
- Produces Compose 服务 `backend`（业务 8080 / 管理 8081，均不映射宿主机端口）与 `web`（宿主机 `127.0.0.1:8090->8080`，`VERIDEX_BACKEND_UPSTREAM=backend:8080`）。
- Produces `deploy/compose/smoke.sh`：`./deploy/compose/smoke.sh` 以 0/非 0 表示 web 冒烟通过；环境变量 `VERIDEX_WEB_URL`（默认 `http://127.0.0.1:8090`）、`VERIDEX_PROMETHEUS_URL`（为空时跳过 Prometheus 检查——Task 6 的 kind 验收复用同一脚本）。登录使用 seed 用户 `admin` / `veridex`（`V2__identity.sql` 固定种子）。
- Consumes Task 1/2 镜像（Stage 3 依赖 `veridex-backend:0.1.0`、`veridex-web:0.1.0` 可构建）。

- [ ] **Step 1: Append failing compose contract assertions**

在 `DockerfileStaticContractTest.java` 中追加：

```java
    @Test
    void composeRunsFullStackWithManagedPrometheusTarget() throws IOException {
        String compose = read(Path.of("deploy/compose/compose.yml"));
        assertThat(compose).contains("backend:");
        assertThat(compose).contains("web:");
        // backend 容器管理端口绑 0.0.0.0 供 Prometheus 抓取，解析目录用 tmpfs 限额
        assertThat(compose).contains("VERIDEX_MANAGEMENT_ADDRESS=0.0.0.0");
        assertThat(compose).contains("/tmp/veridex-parser");
        // web upstream 指向 compose 服务名 backend
        assertThat(compose).contains("VERIDEX_BACKEND_UPSTREAM=backend:8080");
        // 只读根文件系统
        assertThat(compose).containsPattern("read_only: *true");
        // Prometheus 不再假设应用在宿主机
        assertThat(compose).doesNotContain("host.docker.internal:8081");
        String prometheus = read(Path.of("deploy/compose/observability/prometheus/prometheus.yml"));
        assertThat(prometheus).contains("backend:8081");
    }
```

注意：该断言与 `scripts/verify-security.sh` 现有的 `host.docker.internal:8081` grep 冲突——本任务必须同步修改（见 Step 4）。

- [ ] **Step 2: Run RED**

Run: `./mvnw -pl backend test -Dtest=DockerfileStaticContractTest`

Expected: FAIL — compose 中无 `backend:` 服务、Prometheus 目标仍是 `host.docker.internal:8081`。

- [ ] **Step 3: Add backend and web services to compose**

在 `deploy/compose/compose.yml` 的 `opensearch` 服务之后追加（build context 为仓库根：相对 compose 文件目录的 `../..`）：

```yaml
  backend:
    build:
      context: ../..
      dockerfile: backend/Dockerfile
    image: veridex-backend:0.1.0
    environment:
      VERIDEX_ENVIRONMENT: local
      VERIDEX_DB_URL: jdbc:postgresql://postgres:5432/${POSTGRES_DB:-veridex}
      VERIDEX_DB_USERNAME: ${POSTGRES_USER:-veridex}
      VERIDEX_DB_PASSWORD: ${POSTGRES_PASSWORD:-veridex-local}
      VERIDEX_RABBITMQ_HOST: rabbitmq
      VERIDEX_RABBITMQ_USERNAME: ${RABBITMQ_DEFAULT_USER:-veridex}
      VERIDEX_RABBITMQ_PASSWORD: ${RABBITMQ_DEFAULT_PASS:-veridex-local}
      VERIDEX_MINIO_ENDPOINT: http://minio:9000
      VERIDEX_MINIO_ACCESS_KEY: ${MINIO_ROOT_USER:-veridex}
      VERIDEX_MINIO_SECRET_KEY: ${MINIO_ROOT_PASSWORD:-veridex-local-secret}
      VERIDEX_MINIO_BUCKET: veridex-documents
      VERIDEX_OPENSEARCH_URIS: http://opensearch:9200
      VERIDEX_OTLP_ENDPOINT: http://otel-collector:4318/v1/traces
      # 容器内必须监听全部接口，否则同网 Prometheus 抓不到管理端口
      VERIDEX_MANAGEMENT_ADDRESS: 0.0.0.0
      VERIDEX_CORS_ALLOWED_ORIGINS: http://localhost:8090
    read_only: true
    tmpfs:
      - /tmp/veridex-parser:size=2g,uid=10001,gid=10001
    depends_on:
      postgres: { condition: service_healthy }
      rabbitmq: { condition: service_healthy }
      minio: { condition: service_healthy }
      opensearch: { condition: service_healthy }
      otel-collector: { condition: service_started }
    healthcheck:
      test: ["CMD-SHELL", "wget -qO- http://127.0.0.1:8081/actuator/health/readiness >/dev/null || exit 1"]
      interval: 10s
      timeout: 5s
      retries: 30
      start_period: 120s
    restart: unless-stopped

  web:
    build:
      context: ../..
      dockerfile: web/Dockerfile
    image: veridex-web:0.1.0
    environment:
      NGINX_ENTRYPOINT_QUIET_LOGS: "1"
      VERIDEX_BACKEND_UPSTREAM: backend:8080
    read_only: true
    tmpfs:
      - /tmp:rw,noexec,nosuid,size=64m
      - /etc/nginx/conf.d:rw,noexec,nosuid,size=1m
    ports:
      - "127.0.0.1:8090:8080"
    depends_on:
      backend: { condition: service_healthy }
    healthcheck:
      test: ["CMD", "curl", "-fsS", "http://127.0.0.1:8080/healthz"]
      interval: 10s
      timeout: 5s
      retries: 10
    restart: unless-stopped
```

并在 `.env.example` 追加（非 secret 的本地编排值）：

```bash
# ---- Phase 5-d 完整应用栈（本地验收用，非生产凭据）----
VERIDEX_WEB_PORT=8090
```

修改 `deploy/compose/observability/prometheus/prometheus.yml` 的 veridex job：

```yaml
  - job_name: veridex
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: [backend:8081]
```

- [ ] **Step 4: Fix verify-security.sh and run GREEN**

`scripts/verify-security.sh` 中把

```bash
grep -Fq 'host.docker.internal:8081' "${ROOT_DIR}/deploy/compose/observability/prometheus/prometheus.yml" \
  || { echo "Prometheus must scrape the management port 8081" >&2; exit 1; }
```

替换为：

```bash
grep -Fq 'backend:8081' "${ROOT_DIR}/deploy/compose/observability/prometheus/prometheus.yml" \
  || { echo "Prometheus must scrape the containerized backend management port" >&2; exit 1; }
```

Run: `./mvnw -pl backend test -Dtest=DockerfileStaticContractTest && ./scripts/verify-security.sh`

Expected: PASS，且 `docker compose config --quiet`（Docker 可用时）通过。

- [ ] **Step 5: Write the shared smoke test**

Create `deploy/compose/smoke.sh`：

```bash
#!/usr/bin/env bash
set -euo pipefail

# Veridex web 冒烟测试（Compose 完整栈与 kind 集群验收共用）。
# 环境变量：
#   VERIDEX_WEB_URL          web 入口（默认 http://127.0.0.1:8090）
#   VERIDEX_PROMETHEUS_URL   Prometheus 入口；为空时跳过第 5 节（kind 验收无 Prometheus）
WEB="${VERIDEX_WEB_URL:-http://127.0.0.1:8090}"
PROM="${VERIDEX_PROMETHEUS_URL:-}"
JAR=$(mktemp)
trap 'rm -f "${JAR}"' EXIT
fail() { echo "smoke: $1" >&2; exit 1; }

# 1. web 与 SPA fallback
curl -fsS "${WEB}/healthz" | grep -Fxq ok || fail "web /healthz not ok"
curl -fsS "${WEB}/" | grep -q '<div id="root">' || fail "SPA index missing"
test "$(curl -fsS -o /dev/null -w '%{http_code}' "${WEB}/knowledge")" = "200" || fail "SPA fallback broken"

# 2. CSRF：GET /api/auth/csrf 下发 XSRF-TOKEN cookie（5-c SPA 语义）
curl -fsS -c "${JAR}" "${WEB}/api/auth/csrf" >/dev/null || fail "csrf token endpoint failed"

# 3. 登录建立 Session（login 豁免 CSRF），/me 需要 Session
curl -fsS -b "${JAR}" -c "${JAR}" -X POST "${WEB}/api/auth/login?username=admin&password=veridex" >/dev/null \
  || fail "login with seed admin failed"
curl -fsS -b "${JAR}" "${WEB}/api/auth/me" | grep -q '"username":"admin"' || fail "session /me failed"

# 4. 浏览器写请求 CSRF：携带 X-XSRF-TOKEN header 调用 logout
XSRF=$(awk '$6 == "XSRF-TOKEN" {print $NF}' "${JAR}" | tail -n1)
test -n "${XSRF}" || fail "XSRF-TOKEN cookie missing"
curl -fsS -b "${JAR}" -H "X-XSRF-TOKEN: ${XSRF}" -X POST "${WEB}/api/auth/logout" >/dev/null \
  || fail "CSRF-protected logout failed"

# 5. web 不代理 Actuator
code=$(curl -s -o /dev/null -w '%{http_code}' "${WEB}/actuator/health")
test "${code}" = "404" || fail "web must not proxy /actuator (got ${code})"

# 6. Prometheus 抓取容器化 backend 管理端口（可选）
if [ -n "${PROM}" ]; then
  curl -fsS --max-time 10 "${PROM}/api/v1/query?query=up" | grep -q 'backend:8081' \
    || fail "Prometheus has no backend:8081 target"
  curl -fsS --max-time 10 "${PROM}/api/v1/query?query=up%7Bjob%3D%22veridex%22%7D" | grep -q '"1"' \
    || fail "veridex prometheus target is not up"
fi

echo "compose smoke: all checks passed."
```

- [ ] **Step 6: Implement and run verify-deployment Stage 3**

在 `scripts/verify-deployment.sh` 新增 `stage3`（case 加 `3) stage3 ;;`，`all` 串联）：

```bash
stage3() {
  echo "==> [deploy-3] Compose 完整应用栈验收"
  have_docker || { echo "Docker unavailable; compose acceptance skipped (host-only mode)."; return 0; }
  "${COMPOSE[@]}" up -d --build backend web
  for attempt in $(seq 1 60); do
    if curl -fsS http://127.0.0.1:8090/healthz >/dev/null 2>&1; then break; fi
    sleep 5
  done
  VERIDEX_WEB_URL=http://127.0.0.1:8090 VERIDEX_PROMETHEUS_URL=http://127.0.0.1:9090 \
    "${ROOT_DIR}/deploy/compose/smoke.sh"
  "${COMPOSE[@]}" stop backend web >/dev/null
  echo "verify-deployment stage 3 (compose stack) passed."
}
```

Run: `./scripts/verify-deployment.sh 3`

Expected: 首次构建耗时较长；结尾打印 `verify-deployment stage 3 (compose stack) passed.`。若 OpenSearch 启动 OOM，参考 compose.yml 中的说明增大 Docker VM 内存后重试。

- [ ] **Step 7: Commit**

```bash
git add deploy/compose/compose.yml deploy/compose/.env.example deploy/compose/smoke.sh deploy/compose/observability/prometheus/prometheus.yml scripts/verify-security.sh scripts/verify-deployment.sh backend/src/test/java/io/veridex/deployment/DockerfileStaticContractTest.java
git commit -m "feat: run full veridex stack in compose" -m "Co-Authored-By: CodeTui <noreply@codetui.dev>"
```

---

### Task 4: Helm chart 骨架与 workload 契约

**Files:**
- Create: `deploy/helm/veridex/Chart.yaml`
- Create: `deploy/helm/veridex/values.yaml`
- Create: `deploy/helm/veridex/values.schema.json`
- Create: `deploy/helm/veridex/templates/_helpers.tpl`
- Create: `deploy/helm/veridex/templates/backend-configmap.yaml`
- Create: `deploy/helm/veridex/templates/backend-deployment.yaml`
- Create: `deploy/helm/veridex/templates/backend-service.yaml`
- Create: `deploy/helm/veridex/templates/backend-management-service.yaml`
- Create: `deploy/helm/veridex/templates/web-deployment.yaml`
- Create: `deploy/helm/veridex/templates/web-service.yaml`
- Create: `deploy/helm/veridex/templates/serviceaccount.yaml`
- Create: `deploy/helm/veridex/templates/NOTES.txt`
- Create: `deploy/helm/veridex/ci/default-values.yaml`
- Create: `backend/src/test/java/io/veridex/deployment/HelmChartStaticContractTest.java`

**Interfaces:**
- Produces chart `veridex`（apiVersion `v2`，version/appVersion `0.1.0`）；全名 helper `veridex.fullname` = `<release>-veridex`（截断 63），资源名 `<fullname>-backend` / `<fullname>-web`，组件 label `app.kubernetes.io/component: backend|web`。
- Produces helper `veridex.image`（入参 `dict "global" .Values.global "image" .Values.backend.image`，输出 `[registry/]repo[:tag]` 或 `[registry/]repo@digest`）、`veridex.checksum/backendConfig`（backend ConfigMap sha256 注入 Pod annotation）。
- Produces values 契约（Task 5/6/7 依赖的键全部在本任务定型，`values.yaml` 逐字写入以下内容）：

```yaml
global:
  imageRegistry: ""
  imagePullSecrets: []

backend:
  replicaCount: 2
  image:
    repository: veridex/backend
    tag: "0.1.0"
    digest: ""
    pullPolicy: IfNotPresent
  existingSecret: veridex-secret
  podAnnotations: {}
  resources:
    requests: { cpu: 500m, memory: 1Gi }
    limits: { cpu: "2", memory: 2Gi }
  parserTemp:
    sizeLimit: 2Gi
  terminationGracePeriodSeconds: 45
  startupProbe: { periodSeconds: 5, failureThreshold: 40 }
  readinessProbe: { periodSeconds: 10, failureThreshold: 3 }
  livenessProbe: { periodSeconds: 10, failureThreshold: 6 }
  env:
    VERIDEX_ENVIRONMENT: pilot
    VERIDEX_DB_URL: jdbc:postgresql://postgres:5432/veridex
    VERIDEX_RABBITMQ_HOST: rabbitmq
    VERIDEX_MINIO_ENDPOINT: http://minio:9000
    VERIDEX_MINIO_BUCKET: veridex-documents
    VERIDEX_OPENSEARCH_URIS: http://opensearch:9200
    VERIDEX_OTLP_ENDPOINT: http://otel-collector:4318/v1/traces
    VERIDEX_MANAGEMENT_ADDRESS: 0.0.0.0

web:
  replicaCount: 2
  image:
    repository: veridex/web
    tag: "0.1.0"
    digest: ""
    pullPolicy: IfNotPresent
  podAnnotations: {}
  resources:
    requests: { cpu: 100m, memory: 128Mi }
    limits: { cpu: 500m, memory: 256Mi }
  terminationGracePeriodSeconds: 30

podSecurityContext:
  runAsNonRoot: true
  runAsUser: 10001
  runAsGroup: 10001
  seccompProfile: { type: RuntimeDefault }

webSecurityContext:
  runAsNonRoot: true
  runAsUser: 101
  runAsGroup: 101
  seccompProfile: { type: RuntimeDefault }

containerSecurityContext:
  allowPrivilegeEscalation: false
  readOnlyRootFilesystem: true
  capabilities: { drop: ["ALL"] }

serviceAccount: { create: true, name: "" }

ingress:
  enabled: false
  className: nginx
  hostname: veridex.example.local
  annotations: {}
  tls: { enabled: false, secretName: veridex-tls }

podDisruptionBudget: { enabled: true, minAvailable: 1 }

autoscaling:
  enabled: false
  minReplicas: 2
  maxReplicas: 4
  targetCPUUtilizationPercentage: 80

networkPolicy:
  enabled: true
  ingressNamespaceSelector: { kubernetes.io/metadata.name: ingress-nginx }
  monitoringSelector: { app.kubernetes.io/name: prometheus }
  externalEgress: []
  # 每项二选一形态（type 必须是 selector 或 cidr）：
  # - { type: selector, namespace: <ns>, podSelector: {...}, port: 5432 }
  # - { type: cidr, cidr: 10.0.0.0/16, port: 5432 }

serviceMonitor:
  enabled: false
  interval: 30s
  scrapeTimeout: 10s
```

- Secret env 固定映射（Deployment 逐 key `secretKeyRef`，本任务定型，Task 5/6 不改）：`VERIDEX_DB_USERNAME←db-username`、`VERIDEX_DB_PASSWORD←db-password`、`VERIDEX_RABBITMQ_USERNAME←rabbitmq-username`、`VERIDEX_RABBITMQ_PASSWORD←rabbitmq-password`、`VERIDEX_MINIO_ACCESS_KEY←minio-access-key`、`VERIDEX_MINIO_SECRET_KEY←minio-secret-key`、`VERIDEX_SESSION_SECRET←session-secret`（应用当前未绑定该变量，映射仅为落实设计 §7.3 的固定 key 契约，保留给后续会话持久化改造）。
- web Deployment 固定 env：`VERIDEX_BACKEND_UPSTREAM: {{ include "veridex.fullname" . }}-backend:8080`。

- [ ] **Step 1: Write failing chart contract test**

Create `backend/src/test/java/io/veridex/deployment/HelmChartStaticContractTest.java`：

```java
package io.veridex.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Phase 5-d Helm chart 文件级静态契约（不依赖 helm 二进制）。 */
class HelmChartStaticContractTest {

    private static final Path CHART = Path.of("..", "deploy", "helm", "veridex");

    private static String read(String relative) throws IOException {
        return Files.readString(CHART.resolve(relative));
    }

    @Test
    void chartDeclaresRequiredStructure() throws IOException {
        for (String file : List.of("Chart.yaml", "values.yaml", "values.schema.json", "NOTES.txt",
                "templates/_helpers.tpl", "templates/backend-deployment.yaml", "templates/web-deployment.yaml",
                "templates/backend-service.yaml", "templates/backend-management-service.yaml",
                "templates/web-service.yaml", "templates/backend-configmap.yaml", "templates/serviceaccount.yaml")) {
            assertThat(CHART.resolve(file)).as("%s", file).exists();
        }
        assertThat(read("Chart.yaml")).contains("apiVersion: v2").contains("name: veridex");
    }

    @Test
    void workloadTemplatesEnforceSecurityContract() throws IOException {
        String backend = read("templates/backend-deployment.yaml");
        String web = read("templates/web-deployment.yaml");
        for (String template : List.of(backend, web)) {
            assertThat(template).contains("runAsNonRoot: true");
            assertThat(template).contains("readOnlyRootFilesystem: true");
            assertThat(template).contains("allowPrivilegeEscalation: false");
            assertThat(template).contains("\"ALL\"");
            assertThat(template).contains("RuntimeDefault");
            assertThat(template).contains("automountServiceAccountToken: false");
            assertThat(template).doesNotContain("hostPath");
            assertThat(template).doesNotContain("privileged: true");
            // 镜像必须走 helper，禁止裸字符串引用
            assertThat(template).contains("image: {{ include \"veridex.image\"");
        }
        // backend：probes 走管理端口 health groups；parser emptyDir 限额；secret 逐 key 注入；配置 checksum 滚动
        assertThat(backend).contains("/actuator/health/liveness");
        assertThat(backend).contains("/actuator/health/readiness");
        assertThat(backend).contains("port: management");
        assertThat(backend).contains("sizeLimit:");
        assertThat(backend).contains("secretKeyRef");
        assertThat(backend).contains("checksum/config");
        // web：本地静态健康路径、代理上游为 backend Service
        assertThat(web).contains("/healthz");
        assertThat(web).contains("{{ include \"veridex.fullname\" . }}-backend:8080");
        // 管理端口独立 Service 且 web Service 不引用它
        String management = read("templates/backend-management-service.yaml");
        assertThat(management).contains("name: management").contains("port: 8081");
        String webSvc = read("templates/web-service.yaml");
        assertThat(webSvc).doesNotContain("8081");
    }

    @Test
    void valuesAndSchemaRejectInsecureDefaults() throws IOException {
        String values = read("values.yaml");
        assertThat(values).doesNotContain("tag: latest");
        assertThat(values).contains("existingSecret: veridex-secret");
        assertThat(values).contains("readOnlyRootFilesystem: true");
        String schema = read("values.schema.json");
        assertThat(schema).contains("\"pullPolicy\"");
        assertThat(schema).contains("IfNotPresent");
        assertThat(schema).contains("\"digest\"");
        assertThat(schema).contains("sha256:");
    }
}
```

- [ ] **Step 2: Run RED**

Run: `./mvnw -pl backend test -Dtest=HelmChartStaticContractTest`

Expected: FAIL — chart 目录不存在。

- [ ] **Step 3: Create chart metadata, values and schema**

Create `deploy/helm/veridex/Chart.yaml`：

```yaml
apiVersion: v2
name: veridex
description: Veridex enterprise RAG platform (backend + web). Stateful dependencies are external.
type: application
version: 0.1.0
appVersion: "0.1.0"
```

Create `deploy/helm/veridex/values.yaml`：逐字写入上文 Interfaces「Produces values 契约」的完整 YAML（保持 `podAnnotations: {}` 等空 map 形式；在 `existingSecret` 与 `externalEgress` 处保留契约注释）。

Create `deploy/helm/veridex/values.schema.json`：

```json
{
  "$schema": "https://json-schema.org/draft-07/schema#",
  "type": "object",
  "additionalProperties": false,
  "required": ["global", "backend", "web", "podSecurityContext", "webSecurityContext", "containerSecurityContext"],
  "properties": {
    "global": {
      "type": "object",
      "additionalProperties": false,
      "properties": {
        "imageRegistry": { "type": "string" },
        "imagePullSecrets": { "type": "array", "items": { "type": "object" } }
      }
    },
    "backend": {
      "type": "object",
      "additionalProperties": false,
      "required": ["image", "existingSecret", "resources", "env"],
      "properties": {
        "replicaCount": { "type": "integer", "minimum": 1 },
        "image": { "$ref": "#/definitions/image" },
        "existingSecret": { "type": "string", "minLength": 1 },
        "podAnnotations": { "type": "object" },
        "resources": { "$ref": "#/definitions/resources" },
        "parserTemp": {
          "type": "object", "additionalProperties": false,
          "required": ["sizeLimit"],
          "properties": { "sizeLimit": { "type": "string", "pattern": "^[0-9]+(Ki|Mi|Gi)$" } }
        },
        "terminationGracePeriodSeconds": { "type": "integer", "minimum": 30 },
        "startupProbe": { "$ref": "#/definitions/probe" },
        "readinessProbe": { "$ref": "#/definitions/probe" },
        "livenessProbe": { "$ref": "#/definitions/probe" },
        "env": {
          "type": "object",
          "required": ["VERIDEX_ENVIRONMENT", "VERIDEX_DB_URL", "VERIDEX_RABBITMQ_HOST",
                        "VERIDEX_MINIO_ENDPOINT", "VERIDEX_OPENSEARCH_URIS", "VERIDEX_OTLP_ENDPOINT",
                        "VERIDEX_MANAGEMENT_ADDRESS"],
          "minProperties": 7,
          "additionalProperties": { "type": "string" }
        }
      }
    },
    "web": {
      "type": "object",
      "additionalProperties": false,
      "required": ["image", "resources"],
      "properties": {
        "replicaCount": { "type": "integer", "minimum": 1 },
        "image": { "$ref": "#/definitions/image" },
        "podAnnotations": { "type": "object" },
        "resources": { "$ref": "#/definitions/resources" },
        "terminationGracePeriodSeconds": { "type": "integer", "minimum": 10 }
      }
    },
    "podSecurityContext": { "$ref": "#/definitions/securityContextLike" },
    "webSecurityContext": { "$ref": "#/definitions/securityContextLike" },
    "containerSecurityContext": {
      "type": "object",
      "additionalProperties": false,
      "required": ["allowPrivilegeEscalation", "readOnlyRootFilesystem", "capabilities"],
      "properties": {
        "allowPrivilegeEscalation": { "const": false },
        "readOnlyRootFilesystem": { "const": true },
        "capabilities": {
          "type": "object",
          "required": ["drop"],
          "properties": { "drop": { "type": "array", "contains": { "const": "ALL" } } }
        }
      }
    },
    "serviceAccount": {
      "type": "object", "additionalProperties": false,
      "properties": { "create": { "type": "boolean" }, "name": { "type": "string" } }
    },
    "ingress": {
      "type": "object", "additionalProperties": false,
      "properties": {
        "enabled": { "type": "boolean" },
        "className": { "type": "string" },
        "hostname": { "type": "string" },
        "annotations": { "type": "object" },
        "tls": {
          "type": "object", "additionalProperties": false,
          "properties": {
            "enabled": { "type": "boolean" },
            "secretName": { "type": "string", "minLength": 1 }
          }
        }
      }
    },
    "podDisruptionBudget": {
      "type": "object", "additionalProperties": false,
      "properties": {
        "enabled": { "type": "boolean" },
        "minAvailable": { "type": ["integer", "string"] }
      }
    },
    "autoscaling": {
      "type": "object", "additionalProperties": false,
      "properties": {
        "enabled": { "type": "boolean" },
        "minReplicas": { "type": "integer", "minimum": 1 },
        "maxReplicas": { "type": "integer", "minimum": 1 },
        "targetCPUUtilizationPercentage": { "type": "integer", "minimum": 1, "maximum": 100 }
      }
    },
    "networkPolicy": {
      "type": "object", "additionalProperties": false,
      "properties": {
        "enabled": { "type": "boolean" },
        "ingressNamespaceSelector": { "type": "object" },
        "monitoringSelector": { "type": "object" },
        "externalEgress": { "type": "array" }
      }
    },
    "serviceMonitor": {
      "type": "object", "additionalProperties": false,
      "properties": {
        "enabled": { "type": "boolean" },
        "interval": { "type": "string" },
        "scrapeTimeout": { "type": "string" }
      }
    }
  },
  "definitions": {
    "image": {
      "type": "object",
      "additionalProperties": false,
      "required": ["repository", "tag", "digest", "pullPolicy"],
      "properties": {
        "repository": { "type": "string", "minLength": 1 },
        "tag": { "type": "string", "not": { "enum": ["latest"] }, "pattern": "^[0-9][0-9A-Za-z.-]*$" },
        "digest": { "type": "string", "pattern": "^(sha256:[0-9a-f]{64})?$" },
        "pullPolicy": { "enum": ["Always", "IfNotPresent", "Never"] }
      }
    },
    "resources": {
      "type": "object",
      "required": ["requests", "limits"],
      "properties": {
        "requests": { "type": "object", "required": ["cpu", "memory"] },
        "limits": { "type": "object", "required": ["cpu", "memory"] }
      }
    },
    "probe": {
      "type": "object", "additionalProperties": false,
      "properties": {
        "periodSeconds": { "type": "integer", "minimum": 1 },
        "failureThreshold": { "type": "integer", "minimum": 1 }
      }
    },
    "securityContextLike": {
      "type": "object",
      "required": ["runAsNonRoot", "runAsUser", "runAsGroup", "seccompProfile"],
      "properties": {
        "runAsNonRoot": { "const": true },
        "runAsUser": { "type": "integer", "minimum": 1 },
        "runAsGroup": { "type": "integer", "minimum": 1 },
        "seccompProfile": {
          "type": "object", "required": ["type"],
          "properties": { "type": { "const": "RuntimeDefault" } }
        }
      }
    }
  }
}
```

Create `deploy/helm/veridex/ci/default-values.yaml`：

```yaml
# CI 最小 values：与默认 values 一致（existingSecret 是名称引用，helm 不校验存在性）。
```

- [ ] **Step 4: Create helpers and templates**

Create `deploy/helm/veridex/templates/_helpers.tpl`：

```yaml
{{- define "veridex.fullname" -}}
{{- printf "%s-veridex" .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "veridex.labels" -}}
app.kubernetes.io/name: veridex
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version }}
{{- end -}}

{{- define "veridex.selectorLabels" -}}
app.kubernetes.io/name: veridex
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/component: {{ .component }}
{{- end -}}

{{- define "veridex.backendSelectorLabels" -}}
{{- include "veridex.selectorLabels" (dict "Release" .Release "component" "backend") -}}
{{- end -}}

{{- define "veridex.webSelectorLabels" -}}
{{- include "veridex.selectorLabels" (dict "Release" .Release "component" "web") -}}
{{- end -}}

{{- define "veridex.serviceAccountName" -}}
{{- if .Values.serviceAccount.create -}}
{{- printf "%s-backend" (include "veridex.fullname" .) -}}
{{- else -}}
{{- required "serviceAccount.name required when create=false" .Values.serviceAccount.name -}}
{{- end -}}
{{- end -}}

{{/* 入参: dict "global" .Values.global "image" .Values.backend.image */}}
{{- define "veridex.image" -}}
{{- $repo := .image.repository -}}
{{- if .global.imageRegistry -}}
{{- $repo = printf "%s/%s" .global.imageRegistry $repo -}}
{{- end -}}
{{- if .image.digest -}}
{{- printf "%s@%s" $repo .image.digest -}}
{{- else -}}
{{- printf "%s:%s" $repo (required "image.tag is required when digest is empty" .image.tag) -}}
{{- end -}}
{{- end -}}

{{- define "veridex.checksum/backendConfig" -}}
checksum/config: {{ include (print $.Template.BasePath "/backend-configmap.yaml") . | sha256sum }}
{{- end -}}
```

Create `deploy/helm/veridex/templates/backend-configmap.yaml`：

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: {{ include "veridex.fullname" . }}-backend-config
  labels:
    {{- include "veridex.labels" . | nindent 4 }}
    app.kubernetes.io/component: backend
data:
{{- range $key, $value := .Values.backend.env }}
  {{ $key }}: {{ $value | quote }}
{{- end }}
```

Create `deploy/helm/veridex/templates/backend-deployment.yaml`：

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: {{ include "veridex.fullname" . }}-backend
  labels:
    {{- include "veridex.labels" . | nindent 4 }}
    app.kubernetes.io/component: backend
spec:
  {{- if not .Values.autoscaling.enabled }}
  replicas: {{ .Values.backend.replicaCount }}
  {{- end }}
  revisionHistoryLimit: 3
  strategy:
    type: RollingUpdate
    rollingUpdate: { maxUnavailable: 0, maxSurge: 1 }
  selector:
    matchLabels:
      {{- include "veridex.backendSelectorLabels" . | nindent 6 }}
  template:
    metadata:
      labels:
        {{- include "veridex.backendSelectorLabels" . | nindent 8 }}
      annotations:
        {{- include "veridex.checksum/backendConfig" . | nindent 8 }}
        {{- with .Values.backend.podAnnotations }}
        {{- toYaml . | nindent 8 }}
        {{- end }}
    spec:
      serviceAccountName: {{ include "veridex.serviceAccountName" . }}
      automountServiceAccountToken: false
      terminationGracePeriodSeconds: {{ .Values.backend.terminationGracePeriodSeconds }}
      securityContext:
        runAsNonRoot: {{ .Values.podSecurityContext.runAsNonRoot }}
        runAsUser: {{ .Values.podSecurityContext.runAsUser }}
        runAsGroup: {{ .Values.podSecurityContext.runAsGroup }}
        seccompProfile:
          type: {{ .Values.podSecurityContext.seccompProfile.type }}
      affinity:
        podAntiAffinity:
          preferredDuringSchedulingIgnoredDuringExecution:
            - weight: 100
              podAffinityTerm:
                topologyKey: kubernetes.io/hostname
                labelSelector:
                  matchLabels:
                    {{- include "veridex.backendSelectorLabels" . | nindent 20 }}
      containers:
        - name: backend
          image: {{ include "veridex.image" (dict "global" .Values.global "image" .Values.backend.image) }}
          imagePullPolicy: {{ .Values.backend.image.pullPolicy }}
          securityContext:
            allowPrivilegeEscalation: {{ .Values.containerSecurityContext.allowPrivilegeEscalation }}
            readOnlyRootFilesystem: {{ .Values.containerSecurityContext.readOnlyRootFilesystem }}
            capabilities:
              drop:
                {{- toYaml .Values.containerSecurityContext.capabilities.drop | nindent 16 }}
          ports:
            - name: http
              containerPort: 8080
            - name: management
              containerPort: 8081
          envFrom:
            - configMapRef:
                name: {{ include "veridex.fullname" . }}-backend-config
          env:
            - name: VERIDEX_BACKEND_UPSTREAM
            - name: VERIDEX_DB_USERNAME
              valueFrom: { secretKeyRef: { name: {{ .Values.backend.existingSecret }}, key: db-username } }
            - name: VERIDEX_DB_PASSWORD
              valueFrom: { secretKeyRef: { name: {{ .Values.backend.existingSecret }}, key: db-password } }
            - name: VERIDEX_RABBITMQ_USERNAME
              valueFrom: { secretKeyRef: { name: {{ .Values.backend.existingSecret }}, key: rabbitmq-username } }
            - name: VERIDEX_RABBITMQ_PASSWORD
              valueFrom: { secretKeyRef: { name: {{ .Values.backend.existingSecret }}, key: rabbitmq-password } }
            - name: VERIDEX_MINIO_ACCESS_KEY
              valueFrom: { secretKeyRef: { name: {{ .Values.backend.existingSecret }}, key: minio-access-key } }
            - name: VERIDEX_MINIO_SECRET_KEY
              valueFrom: { secretKeyRef: { name: {{ .Values.backend.existingSecret }}, key: minio-secret-key } }
            - name: VERIDEX_SESSION_SECRET
              valueFrom: { secretKeyRef: { name: {{ .Values.backend.existingSecret }}, key: session-secret } }
          startupProbe:
            httpGet: { path: /actuator/health/liveness, port: management }
            periodSeconds: {{ .Values.backend.startupProbe.periodSeconds }}
            failureThreshold: {{ .Values.backend.startupProbe.failureThreshold }}
          readinessProbe:
            httpGet: { path: /actuator/health/readiness, port: management }
            periodSeconds: {{ .Values.backend.readinessProbe.periodSeconds }}
            failureThreshold: {{ .Values.backend.readinessProbe.failureThreshold }}
          livenessProbe:
            httpGet: { path: /actuator/health/liveness, port: management }
            periodSeconds: {{ .Values.backend.livenessProbe.periodSeconds }}
            failureThreshold: {{ .Values.backend.livenessProbe.failureThreshold }}
          lifecycle:
            preStop:
              exec: { command: ["sleep", "10"] }
          volumeMounts:
            - name: parser-temp
              mountPath: /tmp/veridex-parser
          resources:
            {{- toYaml .Values.backend.resources | nindent 12 }}
      volumes:
        - name: parser-temp
          emptyDir:
            sizeLimit: {{ .Values.backend.parserTemp.sizeLimit }}
      {{- with .Values.global.imagePullSecrets }}
      imagePullSecrets:
        {{- toYaml . | nindent 8 }}
      {{- end }}
```

（注意上面 `VERIDEX_BACKEND_UPSTREAM` 一行是占位错误示例的反面教材——实际写入时不要在 backend Deployment 中包含该变量；web 上游变量只属于 web Deployment，见下。）

Create `deploy/helm/veridex/templates/web-deployment.yaml`：

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: {{ include "veridex.fullname" . }}-web
  labels:
    {{- include "veridex.labels" . | nindent 4 }}
    app.kubernetes.io/component: web
spec:
  {{- if not .Values.autoscaling.enabled }}
  replicas: {{ .Values.web.replicaCount }}
  {{- end }}
  revisionHistoryLimit: 3
  strategy:
    type: RollingUpdate
    rollingUpdate: { maxUnavailable: 0, maxSurge: 1 }
  selector:
    matchLabels:
      {{- include "veridex.webSelectorLabels" . | nindent 6 }}
  template:
    metadata:
      labels:
        {{- include "veridex.webSelectorLabels" . | nindent 8 }}
      annotations:
        {{- with .Values.web.podAnnotations }}
        {{- toYaml . | nindent 8 }}
        {{- end }}
    spec:
      serviceAccountName: {{ include "veridex.serviceAccountName" . }}
      automountServiceAccountToken: false
      terminationGracePeriodSeconds: {{ .Values.web.terminationGracePeriodSeconds }}
      securityContext:
        runAsNonRoot: {{ .Values.webSecurityContext.runAsNonRoot }}
        runAsUser: {{ .Values.webSecurityContext.runAsUser }}
        runAsGroup: {{ .Values.webSecurityContext.runAsGroup }}
        seccompProfile:
          type: {{ .Values.webSecurityContext.seccompProfile.type }}
      affinity:
        podAntiAffinity:
          preferredDuringSchedulingIgnoredDuringExecution:
            - weight: 100
              podAffinityTerm:
                topologyKey: kubernetes.io/hostname
                labelSelector:
                  matchLabels:
                    {{- include "veridex.webSelectorLabels" . | nindent 20 }}
      containers:
        - name: web
          image: {{ include "veridex.image" (dict "global" .Values.global "image" .Values.web.image) }}
          imagePullPolicy: {{ .Values.web.image.pullPolicy }}
          securityContext:
            allowPrivilegeEscalation: {{ .Values.containerSecurityContext.allowPrivilegeEscalation }}
            readOnlyRootFilesystem: {{ .Values.containerSecurityContext.readOnlyRootFilesystem }}
            capabilities:
              drop:
                {{- toYaml .Values.containerSecurityContext.capabilities.drop | nindent 16 }}
          ports:
            - name: http
              containerPort: 8080
          env:
            - name: VERIDEX_BACKEND_UPSTREAM
              value: {{ include "veridex.fullname" . }}-backend:8080
          readinessProbe:
            httpGet: { path: /healthz, port: http }
            periodSeconds: 10
            failureThreshold: 3
          livenessProbe:
            httpGet: { path: /healthz, port: http }
            periodSeconds: 10
            failureThreshold: 6
          lifecycle:
            preStop:
              exec: { command: ["/bin/sh", "-c", "sleep 5"] }
          volumeMounts:
            - name: nginx-temp
              mountPath: /tmp
            - name: nginx-conf
              mountPath: /etc/nginx/conf.d
          resources:
            {{- toYaml .Values.web.resources | nindent 12 }}
      volumes:
        - name: nginx-temp
          emptyDir:
            sizeLimit: 64Mi
        - name: nginx-conf
          emptyDir:
            sizeLimit: 1Mi
      {{- with .Values.global.imagePullSecrets }}
      imagePullSecrets:
        {{- toYaml . | nindent 8 }}
      {{- end }}
```

Create `deploy/helm/veridex/templates/backend-service.yaml`：

```yaml
apiVersion: v1
kind: Service
metadata:
  name: {{ include "veridex.fullname" . }}-backend
  labels:
    {{- include "veridex.labels" . | nindent 4 }}
    app.kubernetes.io/component: backend
spec:
  type: ClusterIP
  selector:
    {{- include "veridex.backendSelectorLabels" . | nindent 4 }}
  ports:
    - name: http
      port: 8080
      targetPort: http
```

Create `deploy/helm/veridex/templates/backend-management-service.yaml`：

```yaml
apiVersion: v1
kind: Service
metadata:
  name: {{ include "veridex.fullname" . }}-backend-management
  labels:
    {{- include "veridex.labels" . | nindent 4 }}
    app.kubernetes.io/component: backend
    veridex.io/management: "true"
spec:
  type: ClusterIP
  selector:
    {{- include "veridex.backendSelectorLabels" . | nindent 4 }}
  ports:
    - name: management
      port: 8081
      targetPort: management
```

（`veridex.io/management: "true"` 标签供 Task 5 的 ServiceMonitor 精确选择管理 Service，避免误选业务 Service。）

Create `deploy/helm/veridex/templates/web-service.yaml`：

```yaml
apiVersion: v1
kind: Service
metadata:
  name: {{ include "veridex.fullname" . }}-web
  labels:
    {{- include "veridex.labels" . | nindent 4 }}
    app.kubernetes.io/component: web
spec:
  type: ClusterIP
  selector:
    {{- include "veridex.webSelectorLabels" . | nindent 4 }}
  ports:
    - name: http
      port: 80
      targetPort: http
```

Create `deploy/helm/veridex/templates/serviceaccount.yaml`：

```yaml
{{- if .Values.serviceAccount.create }}
apiVersion: v1
kind: ServiceAccount
metadata:
  name: {{ include "veridex.serviceAccountName" . }}
  labels:
    {{- include "veridex.labels" . | nindent 4 }}
{{- end }}
```

Create `deploy/helm/veridex/templates/NOTES.txt`：

```
Veridex {{ .Chart.AppVersion }} 已部署为 {{ include "veridex.fullname" . }}。

应用资源（本 chart 只管理以下内容）：
  - Deployment/Service {{ include "veridex.fullname" . }}-backend（业务 8080）
  - Service {{ include "veridex.fullname" . }}-backend-management（管理 8081，仅集群内部）
  - Deployment/Service {{ include "veridex.fullname" . }}-web（80）

凭据来自预先存在的 Secret "{{ .Values.backend.existingSecret }}"（本 chart 不创建、不回显其内容）。
Secret 值原地变更后请执行：
  kubectl rollout restart deploy/{{ include "veridex.fullname" . }}-backend

{{- if .Values.ingress.enabled }}
入口：{{ .Values.ingress.hostname }}（Ingress 只指向 web Service）
{{- else }}
Ingress 未启用；请通过 web Service（ClusterIP）接入企业入口。
{{- end }}
```

- [ ] **Step 5: Run GREEN and helm template**

Run:

```bash
./mvnw -pl backend test -Dtest=HelmChartStaticContractTest
helm lint deploy/helm/veridex
helm template verify deploy/helm/veridex | grep -c 'kind:'
```

Expected: 测试 PASS；`helm lint` `0 chart(s) failed`；`helm template` 输出 7 个资源（Deployment×2、Service×3、ConfigMap、ServiceAccount）。若本机无 helm，静态测试仍必须 PASS，模板渲染推迟到 Task 6 的 verify-deployment Stage 4。

- [ ] **Step 6: Commit**

```bash
git add deploy/helm/veridex backend/src/test/java/io/veridex/deployment/HelmChartStaticContractTest.java
git commit -m "feat: add veridex helm chart workloads" -m "Co-Authored-By: CodeTui <noreply@codetui.dev>"
```

---

### Task 5: Ingress、PDB、HPA、NetworkPolicy 与 ServiceMonitor

**Files:**
- Create: `deploy/helm/veridex/templates/ingress.yaml`
- Create: `deploy/helm/veridex/templates/poddisruptionbudget.yaml`
- Create: `deploy/helm/veridex/templates/hpa.yaml`
- Create: `deploy/helm/veridex/templates/networkpolicy.yaml`
- Create: `deploy/helm/veridex/templates/servicemonitor.yaml`
- Create: `deploy/helm/veridex/ci/ingress-values.yaml`
- Modify: `backend/src/test/java/io/veridex/deployment/HelmChartStaticContractTest.java`（追加断言）

**Interfaces:**
- Consumes Task 4 的 labels/selector helpers、management Service 标签 `veridex.io/management: "true"`、values 键 `ingress`/`podDisruptionBudget`/`autoscaling`/`networkPolicy`/`serviceMonitor`。
- Produces `networkPolicy.externalEgress` 条目契约（模板逐条渲染，`type` 非 `selector`/`cidr` 时 `fail`）：

```yaml
- type: selector   # 集群内依赖：namespace + 可选 podSelector + port
  namespace: infra
  podSelector: { app: postgres }
  port: 5432
- type: cidr       # 集群外依赖：稳定 CIDR + port
  cidr: 10.20.0.0/16
  port: 5432
```

- [ ] **Step 1: Append failing contract assertions**

在 `HelmChartStaticContractTest.java` 中追加：

```java
    @Test
    void optionalComponentsAreGatedAndMinimal() throws IOException {
        String ingress = read("templates/ingress.yaml");
        assertThat(ingress).contains("{{- if .Values.ingress.enabled }}");
        assertThat(ingress).contains("networking.k8s.io/v1");
        // Ingress 只指向 web Service，绝不引用管理 Service
        assertThat(ingress).contains("{{ include \"veridex.fullname\" . }}-web");
        assertThat(ingress).doesNotContain("-management");
        assertThat(ingress).contains("secretName");
        String pdb = read("templates/poddisruptionbudget.yaml");
        assertThat(pdb).contains("podDisruptionBudget.enabled");
        assertThat(pdb).contains("minAvailable");
        String hpa = read("templates/hpa.yaml");
        assertThat(hpa).contains("autoscaling/v2");
        assertThat(hpa).contains("{{- if .Values.autoscaling.enabled }}");
        assertThat(hpa).contains("kind: Deployment");
        String sm = read("templates/servicemonitor.yaml");
        assertThat(sm).contains("{{- if .Values.serviceMonitor.enabled }}");
        assertThat(sm).contains("path: /actuator/prometheus");
        assertThat(sm).contains("port: management");
        assertThat(sm).contains("veridex.io/management");
    }

    @Test
    void networkPolicyDefaultsToDenyWithExplicitTargets() throws IOException {
        String np = read("templates/networkpolicy.yaml");
        assertThat(np).contains("{{- if .Values.networkPolicy.enabled }}");
        assertThat(np).contains("policyTypes");
        assertThat(np).contains("Ingress");
        assertThat(np).contains("Egress");
        // 不允许全放开出口
        assertThat(np).doesNotContain("0.0.0.0/0");
        // DNS 与显式外部依赖
        assertThat(np).contains("externalEgress");
        assertThat(np).contains("port: 53");
        assertThat(np).contains("port: 8080");
        assertThat(np).contains("port: 8081");
        // selector / cidr 二选一，非法类型直接 fail
        assertThat(np).contains("fail \"networkPolicy.externalEgress");
    }
```

- [ ] **Step 2: Run RED**

Run: `./mvnw -pl backend test -Dtest=HelmChartStaticContractTest`

Expected: FAIL — 五个新模板不存在。

- [ ] **Step 3: Write the gated templates**

Create `deploy/helm/veridex/templates/ingress.yaml`：

```yaml
{{- if .Values.ingress.enabled }}
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: {{ include "veridex.fullname" . }}-web
  labels:
    {{- include "veridex.labels" . | nindent 4 }}
    app.kubernetes.io/component: web
  {{- with .Values.ingress.annotations }}
  annotations:
    {{- toYaml . | nindent 4 }}
  {{- end }}
spec:
  ingressClassName: {{ .Values.ingress.className }}
  {{- if .Values.ingress.tls.enabled }}
  tls:
    - hosts:
        - {{ .Values.ingress.hostname | quote }}
      secretName: {{ required "ingress.tls.secretName is required when tls.enabled" .Values.ingress.tls.secretName }}
  {{- end }}
  rules:
    - host: {{ .Values.ingress.hostname | quote }}
      http:
        paths:
          - path: /
            pathType: Prefix
            backend:
              service:
                name: {{ include "veridex.fullname" . }}-web
                port:
                  number: 80
{{- end }}
```

Create `deploy/helm/veridex/templates/poddisruptionbudget.yaml`（backend 与 web 各一，仅副本数 > 1 时创建）：

```yaml
{{- if and .Values.podDisruptionBudget.enabled (gt (int .Values.backend.replicaCount) 1) }}
apiVersion: policy/v1
kind: PodDisruptionBudget
metadata:
  name: {{ include "veridex.fullname" . }}-backend
  labels:
    {{- include "veridex.labels" . | nindent 4 }}
    app.kubernetes.io/component: backend
spec:
  minAvailable: {{ .Values.podDisruptionBudget.minAvailable }}
  selector:
    matchLabels:
      {{- include "veridex.backendSelectorLabels" . | nindent 6 }}
{{- end }}
{{- if and .Values.podDisruptionBudget.enabled (gt (int .Values.web.replicaCount) 1) }}
---
apiVersion: policy/v1
kind: PodDisruptionBudget
metadata:
  name: {{ include "veridex.fullname" . }}-web
  labels:
    {{- include "veridex.labels" . | nindent 4 }}
    app.kubernetes.io/component: web
spec:
  minAvailable: {{ .Values.podDisruptionBudget.minAvailable }}
  selector:
    matchLabels:
      {{- include "veridex.webSelectorLabels" . | nindent 6 }}
{{- end }}
```

Create `deploy/helm/veridex/templates/hpa.yaml`（只针对 backend；启用时 Deployment 不再固定 replicas，见 Task 4 模板的条件分支）：

```yaml
{{- if .Values.autoscaling.enabled }}
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: {{ include "veridex.fullname" . }}-backend
  labels:
    {{- include "veridex.labels" . | nindent 4 }}
    app.kubernetes.io/component: backend
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: {{ include "veridex.fullname" . }}-backend
  minReplicas: {{ .Values.autoscaling.minReplicas }}
  maxReplicas: {{ .Values.autoscaling.maxReplicas }}
  metrics:
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: {{ .Values.autoscaling.targetCPUUtilizationPercentage }}
{{- end }}
```

Create `deploy/helm/veridex/templates/networkpolicy.yaml`：

```yaml
{{- if .Values.networkPolicy.enabled }}
# web：入口只允许企业 Ingress Controller namespace；出口只允许 DNS 与 backend 业务端口
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: {{ include "veridex.fullname" . }}-web
  labels:
    {{- include "veridex.labels" . | nindent 4 }}
    app.kubernetes.io/component: web
spec:
  podSelector:
    matchLabels:
      {{- include "veridex.webSelectorLabels" . | nindent 6 }}
  policyTypes:
    - Ingress
    - Egress
  ingress:
    - from:
        - namespaceSelector:
            matchLabels:
              {{- toYaml .Values.networkPolicy.ingressNamespaceSelector | nindent 14 }}
      ports:
        - protocol: TCP
          port: 8080
  egress:
    # DNS（kube-system 内 CoreDNS）
    - to:
        - namespaceSelector:
            matchLabels:
              kubernetes.io/metadata.name: kube-system
      ports:
        - protocol: UDP
          port: 53
        - protocol: TCP
          port: 53
    # backend 业务端口
    - to:
        - podSelector:
            matchLabels:
              {{- include "veridex.backendSelectorLabels" . | nindent 14 }}
      ports:
        - protocol: TCP
          port: 8080
---
# backend：业务端口只允许 web；管理端口只允许监控 selector；出口允许 DNS 与显式外部依赖
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: {{ include "veridex.fullname" . }}-backend
  labels:
    {{- include "veridex.labels" . | nindent 4 }}
    app.kubernetes.io/component: backend
spec:
  podSelector:
    matchLabels:
      {{- include "veridex.backendSelectorLabels" . | nindent 6 }}
  policyTypes:
    - Ingress
    - Egress
  ingress:
    - from:
        - podSelector:
            matchLabels:
              {{- include "veridex.webSelectorLabels" . | nindent 14 }}
      ports:
        - protocol: TCP
          port: 8080
    - from:
        - podSelector:
            matchLabels:
              {{- toYaml .Values.networkPolicy.monitoringSelector | nindent 14 }}
      ports:
        - protocol: TCP
          port: 8081
  egress:
    # DNS
    - to:
        - namespaceSelector:
            matchLabels:
              kubernetes.io/metadata.name: kube-system
      ports:
        - protocol: UDP
          port: 53
        - protocol: TCP
          port: 53
    # 外部依赖：安装者必须用 selector 或 CIDR 显式表达，chart 不静默开放全部出站
{{- range $target := .Values.networkPolicy.externalEgress }}
    - to:
        {{- if eq $target.type "selector" }}
        - namespaceSelector:
            matchLabels:
              kubernetes.io/metadata.name: {{ $target.namespace }}
          {{- with $target.podSelector }}
          podSelector:
            matchLabels:
              {{- toYaml . | nindent 14 }}
          {{- end }}
        {{- else if eq $target.type "cidr" }}
        - ipBlock:
            cidr: {{ $target.cidr }}
        {{- else }}
        {{- fail "networkPolicy.externalEgress[*].type must be selector or cidr" }}
        {{- end }}
      ports:
        - protocol: TCP
          port: {{ $target.port }}
{{- end }}
{{- end }}
```

Create `deploy/helm/veridex/templates/servicemonitor.yaml`：

```yaml
{{- if .Values.serviceMonitor.enabled }}
apiVersion: monitoring.coreos.com/v1
kind: ServiceMonitor
metadata:
  name: {{ include "veridex.fullname" . }}-backend
  labels:
    {{- include "veridex.labels" . | nindent 4 }}
    app.kubernetes.io/component: backend
spec:
  selector:
    matchLabels:
      {{- include "veridex.labels" . | nindent 6 }}
      veridex.io/management: "true"
  endpoints:
    - port: management
      path: /actuator/prometheus
      interval: {{ .Values.serviceMonitor.interval }}
      scrapeTimeout: {{ .Values.serviceMonitor.scrapeTimeout }}
{{- end }}
```

Create `deploy/helm/veridex/ci/ingress-values.yaml`：

```yaml
ingress:
  enabled: true
  tls:
    enabled: true
    secretName: veridex-tls-test
```

- [ ] **Step 4: Run GREEN**

Run: `./mvnw -pl backend test -Dtest=HelmChartStaticContractTest`

Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add deploy/helm/veridex backend/src/test/java/io/veridex/deployment/HelmChartStaticContractTest.java
git commit -m "feat: add chart ingress policies and autoscaling" -m "Co-Authored-By: CodeTui <noreply@codetui.dev>"
```

---

### Task 6: Helm 静态校验与 kind 集群验收

**Files:**
- Create: `deploy/kind/test-deps.yaml`
- Create: `deploy/kind/kind-values.yaml`
- Create: `deploy/kind/run-acceptance.sh`
- Modify: `scripts/verify-deployment.sh`（完成 Stage 4：helm lint/template 矩阵 + kind 验收）

**Interfaces:**
- Consumes Task 4/5 chart、Task 1/2 镜像（`veridex-backend:0.1.0`、`veridex-web:0.1.0` 已构建在本地 Docker）、Task 3 `smoke.sh`（`VERIDEX_PROMETHEUS_URL` 为空时自动跳过 Prometheus 节）。
- Produces `deploy/kind/run-acceptance.sh`：`./deploy/kind/run-acceptance.sh`（可选环境变量 `SKIP_KIND_DELETE=1` 保留集群排障、`ENFORCE_NETWORKPOLICY=1` 安装 Calico 并执行 NetworkPolicy 拒绝测试，默认 off——kind 默认 CNI kindnet 不执行 NetworkPolicy）。
- Produces kind 验收 values 契约：依赖 Pod `veridex-test-deps`（单 Pod 多容器：postgres/rabbitmq/minio/opensearch，label `app: deps`，位于安装 namespace）、Secret `veridex-secret`（Task 4 固定 7 个 key）。

- [ ] **Step 1: Create the kind dependency manifest and values**

Create `deploy/kind/test-deps.yaml`：

```yaml
# kind 验收依赖：单 Pod 多容器模拟外部基础设施（PostgreSQL/RabbitMQ/MinIO/OpenSearch）。
# OTLP endpoint 指向本 Pod 的 4318（无监听）——5-b 的 exporter fail-open 语义下业务不受影响。
apiVersion: v1
kind: Secret
metadata:
  name: veridex-secret
  labels: { app: deps }
stringData:
  db-username: veridex
  db-password: veridex-test
  rabbitmq-username: veridex
  rabbitmq-password: veridex-test
  minio-access-key: veridex
  minio-secret-key: veridex-test-secret
  session-secret: veridex-test-session-secret
---
apiVersion: v1
kind: Pod
metadata:
  name: veridex-test-deps
  labels: { app: deps }
spec:
  containers:
    - name: postgres
      image: postgres:17-alpine
      env:
        - { name: POSTGRES_DB, value: veridex }
        - { name: POSTGRES_USER, value: veridex }
        - { name: POSTGRES_PASSWORD, value: veridex-test }
      ports: [{ containerPort: 5432 }]
      readinessProbe:
        exec: { command: ["pg_isready", "-U", "veridex", "-d", "veridex"] }
        interval: 5s
        timeout: 3s
        retries: 30
    - name: rabbitmq
      image: rabbitmq:4-alpine
      env:
        - { name: RABBITMQ_DEFAULT_USER, value: veridex }
        - { name: RABBITMQ_DEFAULT_PASS, value: veridex-test }
      ports: [{ containerPort: 5672 }]
      readinessProbe:
        exec: { command: ["rabbitmq-diagnostics", "-q", "ping"] }
        interval: 5s
        timeout: 5s
        retries: 30
    - name: minio
      image: minio/minio:RELEASE.2025-07-23T15-54-02Z
      command: ["server", "/data"]
      env:
        - { name: MINIO_ROOT_USER, value: veridex }
        - { name: MINIO_ROOT_PASSWORD, value: veridex-test-secret }
      ports: [{ containerPort: 9000 }]
      readinessProbe:
        exec: { command: ["curl", "-fsS", "http://localhost:9000/minio/health/live"] }
        interval: 5s
        timeout: 3s
        retries: 30
    - name: opensearch
      image: opensearchproject/opensearch:3.2.0
      env:
        - { name: discovery.type, value: single-node }
        - { name: DISABLE_SECURITY_PLUGIN, value: "true" }
        - { name: OPENSEARCH_JAVA_OPTS, value: -Xms512m -Xmx512m }
      ports: [{ containerPort: 9200 }]
      readinessProbe:
        exec:
          command: ["sh", "-c", "curl -fsS http://localhost:9200/_cluster/health >/dev/null"]
        interval: 10s
        timeout: 5s
        retries: 30
  restartPolicy: Never
```

Create `deploy/kind/kind-values.yaml`：

```yaml
# kind 验收覆盖：依赖全部指向同 namespace 的 veridex-test-deps Pod。
# NetworkPolicy externalEgress 用 selector 表达（依赖 Pod label app=deps）。
backend:
  env:
    VERIDEX_ENVIRONMENT: local
    VERIDEX_DB_URL: jdbc:postgresql://veridex-test-deps:5432/veridex
    VERIDEX_RABBITMQ_HOST: veridex-test-deps
    VERIDEX_MINIO_ENDPOINT: http://veridex-test-deps:9000
    VERIDEX_OPENSEARCH_URIS: http://veridex-test-deps:9200
    VERIDEX_OTLP_ENDPOINT: http://veridex-test-deps:4318/v1/traces
networkPolicy:
  externalEgress:
    - { type: selector, namespace: veridex-test, podSelector: { app: deps }, port: 5432 }
    - { type: selector, namespace: veridex-test, podSelector: { app: deps }, port: 5672 }
    - { type: selector, namespace: veridex-test, podSelector: { app: deps }, port: 9000 }
    - { type: selector, namespace: veridex-test, podSelector: { app: deps }, port: 9200 }
    - { type: selector, namespace: veridex-test, podSelector: { app: deps }, port: 4318 }
```

- [ ] **Step 2: Create the cluster acceptance script**

Create `deploy/kind/run-acceptance.sh`：

```bash
#!/usr/bin/env bash
set -euo pipefail

# kind 集群验收：临时集群 → 导入本地镜像 → 部署测试依赖 → 安装 chart →
# 冒烟 → Pod 删除恢复 → 滚动升级无不可用窗口 → Ingress 开关 → 清理。
# 依赖: docker、kind、kubectl、helm。
# 环境变量:
#   SKIP_KIND_DELETE=1        结束后保留集群（排障）
#   ENFORCE_NETWORKPOLICY=1   安装 Calico 并执行 NetworkPolicy 拒绝测试
#                             （kind 默认 CNI kindnet 不执行 NetworkPolicy）
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CLUSTER="veridex-acceptance"
NS="veridex-test"
RELEASE="verify"
FULLNAME="${RELEASE}-veridex"
WEB_PORT="18080"

for tool in docker kind kubectl helm; do
  command -v "${tool}" >/dev/null 2>&1 || { echo "run-acceptance: ${tool} not installed" >&2; exit 2; }
done

cleanup() {
  kubectl -n "${NS}" port-forward 2>/dev/null || true
  if [ "${SKIP_KIND_DELETE:-0}" = "1" ]; then
    echo "run-acceptance: keeping cluster ${CLUSTER} (SKIP_KIND_DELETE=1)"
  else
    kind delete cluster --name "${CLUSTER}" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

echo "==> [kind] create cluster"
CLUSTER_CONFIG='
kind: Cluster
apiVersion: kind.x-k8s.io/v1alpha4
networking:
  disableDefaultCNI: '"$([ "${ENFORCE_NETWORKPOLICY:-0}" = "1" ] && echo true || echo false)"'
'
kind create cluster --name "${CLUSTER}" --config - <<<"${CLUSTER_CONFIG}"

if [ "${ENFORCE_NETWORKPOLICY:-0}" = "1" ]; then
  echo "==> [kind] install calico (NetworkPolicy enforcement)"
  kubectl apply -f https://raw.githubusercontent.com/projectcalico/calico/v3.28.2/manifests/calico.yaml
  kubectl -n kube-system wait pods -l k8s-app=calico-node --for=condition=Ready --timeout=300s
fi

echo "==> [kind] load images"
kind load docker-image veridex-backend:0.1.0 veridex-web:0.1.0 --name "${CLUSTER}"

echo "==> [kind] deploy test dependencies"
kubectl create namespace "${NS}"
kubectl -n "${NS}" apply -f "${ROOT_DIR}/deploy/kind/test-deps.yaml"
kubectl -n "${NS}" wait pod/veridex-test-deps --for=condition=Ready --timeout=600s

echo "==> [kind] install chart"
helm upgrade --install "${RELEASE}" "${ROOT_DIR}/deploy/helm/veridex" \
  --namespace "${NS}" -f "${ROOT_DIR}/deploy/kind/kind-values.yaml" \
  --wait --timeout 10m
kubectl -n "${NS}" rollout status "deploy/${FULLNAME}-backend" --timeout=600s
kubectl -n "${NS}" rollout status "deploy/${FULLNAME}-web" --timeout=300s

echo "==> [kind] smoke via port-forward"
kubectl -n "${NS}" port-forward "svc/${FULLNAME}-web" "${WEB_PORT}:80" >/dev/null 2>&1 &
PF_PID=$!
for attempt in $(seq 1 30); do
  curl -fsS "http://127.0.0.1:${WEB_PORT}/healthz" >/dev/null 2>&1 && break
  sleep 1
done
VERIDEX_WEB_URL="http://127.0.0.1:${WEB_PORT}" "${ROOT_DIR}/deploy/compose/smoke.sh"

echo "==> [kind] pod deletion recovery"
kubectl -n "${NS}" delete pod -l "app.kubernetes.io/component=backend,app.kubernetes.io/instance=${RELEASE}" --wait=false >/dev/null
kubectl -n "${NS}" rollout status "deploy/${FULLNAME}-backend" --timeout=600s
curl -fsS "http://127.0.0.1:${WEB_PORT}/healthz" >/dev/null

echo "==> [kind] rolling upgrade without downtime"
FAIL_FILE=$(mktemp)
( for i in $(seq 1 60); do
    curl -fsS -m 2 "http://127.0.0.1:${WEB_PORT}/healthz" >/dev/null || echo down >>"${FAIL_FILE}"
    sleep 1
  done ) &
PROBE_PID=$!
helm upgrade --install "${RELEASE}" "${ROOT_DIR}/deploy/helm/veridex" \
  --namespace "${NS}" -f "${ROOT_DIR}/deploy/kind/kind-values.yaml" \
  --set backend.podAnnotations.rolloutProbe="$(date +%s)" --wait --timeout 10m
wait "${PROBE_PID}"
test ! -s "${FAIL_FILE}" || { echo "rolling upgrade had downtime:" >&2; cat "${FAIL_FILE}" >&2; exit 1; }
rm -f "${FAIL_FILE}"

echo "==> [kind] ingress toggle"
openssl req -x509 -newkey rsa:2048 -nodes -days 1 -subj "/CN=veridex.test" \
  -keyout /tmp/veridex-tls.key -out /tmp/veridex-tls.crt >/dev/null 2>&1
kubectl -n "${NS}" create secret tls veridex-tls --key /tmp/veridex-tls.key --cert /tmp/veridex-tls.crt
rm -f /tmp/veridex-tls.key /tmp/veridex-tls.crt
helm upgrade --install "${RELEASE}" "${ROOT_DIR}/deploy/helm/veridex" \
  --namespace "${NS}" -f "${ROOT_DIR}/deploy/kind/kind-values.yaml" -f "${ROOT_DIR}/deploy/helm/veridex/ci/ingress-values.yaml" \
  --set ingress.hostname=veridex.test --wait --timeout 10m
test "$(kubectl -n "${NS}" get ingress -o name)" = "ingress.networking.k8s.io/${FULLNAME}-web"
# 管理 Service 不出现在任何 Ingress 规则里
! kubectl -n "${NS}" get ingress -o jsonpath='{.items[*].spec.rules[*].http.paths[*].backend.service.name}' | grep -q management

if [ "${ENFORCE_NETWORKPOLICY:-0}" = "1" ]; then
  echo "==> [kind] networkpolicy denial"
  # 未授权 Pod 访问 backend 业务端口必须失败
  if kubectl -n "${NS}" run np-probe --rm -i --restart=Never --image=curlimages/curl:8.10.1 \
      --command -- curl -fsS -m 3 "http://${FULLNAME}-backend:8080/api/auth/csrf" >/dev/null 2>&1; then
    echo "networkpolicy must deny unauthorized ingress to backend" >&2; exit 1
  fi
  # web 到 backend 的合法路径仍然可用
  kubectl -n "${NS}" exec "deploy/${FULLNAME}-web" -c web -- \
    curl -fsS -m 3 "http://${FULLNAME}-backend:8080/api/auth/csrf" >/dev/null
else
  echo "==> [kind] networkpolicy enforcement checks skipped (set ENFORCE_NETWORKPOLICY=1)"
fi

kill "${PF_PID}" 2>/dev/null || true
echo "kind acceptance: all checks passed."
```

Run: `chmod +x deploy/kind/run-acceptance.sh`

- [ ] **Step 3: Complete verify-deployment Stage 4**

在 `scripts/verify-deployment.sh` 中新增（case 加 `4) stage4 ;;`，`all` 串联；`have_helm()` helper：`command -v helm >/dev/null 2>&1`）：

```bash
stage4() {
  echo "==> [deploy-4] Helm lint/template 矩阵 + kind 集群验收"
  if have_helm; then
    helm lint "${ROOT_DIR}/deploy/helm/veridex"
    local out; out="$(mktemp -d)"
    helm template verify "${ROOT_DIR}/deploy/helm/veridex" > "${out}/default.yaml"
    # 默认：2 NetworkPolicy、2 PDB、无 Ingress/HPA/ServiceMonitor
    test "$(grep -c 'kind: NetworkPolicy' "${out}/default.yaml")" = "2"
    test "$(grep -c 'kind: PodDisruptionBudget' "${out}/default.yaml")" = "2"
    ! grep -q 'kind: Ingress' "${out}/default.yaml"
    ! grep -q 'kind: HorizontalPodAutoscaler' "${out}/default.yaml"
    ! grep -q 'kind: ServiceMonitor' "${out}/default.yaml"
    ! grep -q '0.0.0.0/0' "${out}/default.yaml"
    ! grep -q 'tag: latest' "${out}/default.yaml"
    ! grep -q ':latest' "${out}/default.yaml"
    # ingress+TLS：只指向 web Service
    helm template verify "${ROOT_DIR}/deploy/helm/veridex" -f "${ROOT_DIR}/deploy/helm/veridex/ci/ingress-values.yaml" > "${out}/ingress.yaml"
    grep -q 'kind: Ingress' "${out}/ingress.yaml"
    grep -q 'secretName: veridex-tls-test' "${out}/ingress.yaml"
    ! grep -A200 'kind: Ingress' "${out}/ingress.yaml" | grep -q '\-management'
    # HPA：渲染 HPA 且 Deployment 不再固定 replicas
    helm template verify "${ROOT_DIR}/deploy/helm/veridex" --set autoscaling.enabled=true > "${out}/hpa.yaml"
    grep -q 'kind: HorizontalPodAutoscaler' "${out}/hpa.yaml"
    ! grep -q 'replicas: 2' "${out}/hpa.yaml"
    # ServiceMonitor：只抓管理 Service 的命名端口
    helm template verify "${ROOT_DIR}/deploy/helm/veridex" --set serviceMonitor.enabled=true > "${out}/sm.yaml"
    grep -q 'kind: ServiceMonitor' "${out}/sm.yaml"
    grep -q 'port: management' "${out}/sm.yaml"
    grep -q 'veridex.io/management: "true"' "${out}/sm.yaml"
    # NetworkPolicy 关闭
    ! helm template verify "${ROOT_DIR}/deploy/helm/veridex" --set networkPolicy.enabled=false | grep -q 'kind: NetworkPolicy'
    # registry 重写与 digest 锁定
    helm template verify "${ROOT_DIR}/deploy/helm/veridex" --set global.imageRegistry=registry.corp.example \
      | grep -q 'registry.corp.example/veridex/backend:0.1.0'
    helm template verify "${ROOT_DIR}/deploy/helm/veridex" \
      --set backend.image.digest="sha256:$(printf 'a%.0s' $(seq 1 64))" \
      | grep -q 'veridex/backend@sha256:'
    # schema 负例：latest tag / 空 TLS secretName / 非法 egress type 必须渲染失败
    if helm template verify "${ROOT_DIR}/deploy/helm/veridex" --set backend.image.tag=latest >/dev/null 2>&1; then
      echo "values.schema must reject tag=latest" >&2; exit 1
    fi
    if helm template verify "${ROOT_DIR}/deploy/helm/veridex" \
        --set ingress.enabled=true --set ingress.tls.enabled=true --set ingress.tls.secretName= >/dev/null 2>&1; then
      echo "values.schema/required must reject empty tls.secretName" >&2; exit 1
    fi
    if helm template verify "${ROOT_DIR}/deploy/helm/veridex" \
        --set 'networkPolicy.externalEgress[0].type=bogus' >/dev/null 2>&1; then
      echo "networkpolicy template must reject unknown egress type" >&2; exit 1
    fi
    rm -rf "${out}"
  else
    echo "helm unavailable; lint/template matrix skipped (host-only mode)."
  fi
  # kind 集群验收（工具齐备时执行；ENFORCE_NETWORKPOLICY 可选）
  if have_docker && command -v kind >/dev/null 2>&1 && command -v kubectl >/dev/null 2>&1 && have_helm; then
    "${ROOT_DIR}/deploy/kind/run-acceptance.sh"
  else
    echo "kind/kubectl unavailable; cluster acceptance skipped (host-only mode)."
  fi
  echo "verify-deployment stage 4 (helm + cluster) passed."
}
```

- [ ] **Step 4: Run Stage 4**

Run: `./scripts/verify-deployment.sh 4`

Expected: lint/template 矩阵全部断言通过；kind 可用时集群验收结尾打印 `kind acceptance: all checks passed.` 与 `verify-deployment stage 4 (helm + cluster) passed.`；工具缺失时打印 skipped 并以 0 退出。OpenSearch 依赖容器较重，Docker 内存不足时需增大 Docker VM 内存后重试。

- [ ] **Step 5: Commit**

```bash
git add deploy/kind scripts/verify-deployment.sh
git commit -m "test: accept chart on kind cluster" -m "Co-Authored-By: CodeTui <noreply@codetui.dev>"
```

---

### Task 7: 离线交付包

**Files:**
- Create: `deploy/offline/veridex-offline/images.txt`
- Create: `deploy/offline/veridex-offline/images.sha256`
- Create: `deploy/offline/veridex-offline/values/registry-values.yaml`
- Create: `deploy/offline/scripts/export-images.sh`
- Create: `deploy/offline/scripts/import-images.sh`
- Create: `deploy/offline/scripts/push-images.sh`
- Create: `deploy/offline/veridex-offline/INSTALL.txt`
- Modify: `scripts/verify-deployment.sh`（新增 Stage 5）

**Interfaces:**
- Produces `deploy/offline/scripts/*.sh`：均接受环境变量 `OFFLINE_DIR`（默认 `<repo>/deploy/offline/veridex-offline`）；`push-images.sh` 还要求 `REGISTRY`（非空，否则非零退出）。
- Produces `images.txt` 契约：每行一个镜像引用（`#` 开头为注释），第一版固定两行 `veridex-backend:0.1.0`、`veridex-web:0.1.0`；export 时生成 `images/<转义名>.tar` 与 `charts/veridex-<version>.tgz`，并在 `images.sha256` 追加 `<sha256>  <相对路径>`（GNU `sha256sum -c` 与 BSD `shasum -a 256 -c` 兼容格式；`#` 注释行在 import 时过滤）。
- Consumes Task 1/2 本地镜像与 Task 4/5 chart。

- [ ] **Step 1: Write manifest, registry values and INSTALL**

Create `deploy/offline/veridex-offline/images.txt`：

```text
# Veridex 离线镜像清单（受限网络安装的基础镜像由企业提供；本清单只含应用镜像）
veridex-backend:0.1.0
veridex-web:0.1.0
```

Create `deploy/offline/veridex-offline/images.sha256`（占位，export 时整体重写）：

```text
# 由 export-images.sh 生成：<sha256>  <相对路径>；import-images.sh 校验后 load
```

Create `deploy/offline/veridex-offline/values/registry-values.yaml`：

```yaml
# 受限网络安装：把应用镜像重写到企业私有 registry。
# 安装时替换 imageRegistry（不要把真实 registry 提交回仓库）。
global:
  imageRegistry: ""   # 例: registry.corp.example:5000
```

Create `deploy/offline/veridex-offline/INSTALL.txt`：

```text
Veridex 受限网络安装指南
========================

前置：目标环境已有 Kubernetes 1.28+、Helm 3.13+、docker（或 nerdctl/containerd 工具链）、
PostgreSQL 17 / RabbitMQ 4 / MinIO / OpenSearch 3.2 /（可选）OTel Collector，以及企业镜像仓库。

1. 生成离线包（联网机器，仓库根目录）：
     ./scripts/verify-deployment.sh 1          # 构建应用镜像
     OFFLINE_DIR=/path/to/veridex-offline deploy/offline/scripts/export-images.sh
   多架构发布镜像（amd64/arm64 manifest，可选但验收标准要求该路径可用）：
     docker buildx build --platform linux/amd64,linux/arm64 \
       -f backend/Dockerfile -t <REGISTRY>/veridex-backend:0.1.0 --push .
     docker buildx build --platform linux/amd64,linux/arm64 \
       -f web/Dockerfile -t <REGISTRY>/veridex-web:0.1.0 --push .

2. 转移 veridex-offline/ 目录到目标环境。

3. 导入镜像（二选一）：
   a. docker load：deploy/offline/scripts/import-images.sh
   b. 推送到私有 registry：
        REGISTRY=registry.corp.example:5000 deploy/offline/scripts/push-images.sh

4. 准备 existing Secret（chart 不创建，禁止明文入库）：
     kubectl create namespace veridex
     kubectl -n veridex create secret generic veridex-secret \
       --from-literal=db-username=... \
       --from-literal=db-password=... \
       --from-literal=rabbitmq-username=... \
       --from-literal=rabbitmq-password=... \
       --from-literal=minio-access-key=... \
       --from-literal=minio-secret-key=... \
       --from-literal=session-secret=...
   若启用 trace body capture（ERRORS/ALL），还需追加 trace-fingerprint-key /
   trace-current-key-id / trace-current-key（以及可选 trace-historical-keys）。

5. 编写 values（endpoint 指向企业基础设施；出口依赖用 selector 或 CIDR 显式声明，
   参见 deploy/helm/veridex/values.yaml 注释），并合并 registry 覆盖：
     helm upgrade --install veridex deploy/offline/veridex-offline/charts/veridex-<version>.tgz \
       -n veridex -f my-values.yaml -f veridex-offline/values/registry-values.yaml

6. 等待 rollout 并冒烟：
     kubectl -n veridex rollout status deploy/veridex-veridex-backend
     kubectl -n veridex port-forward svc/veridex-veridex-web 8080:80
     VERIDEX_WEB_URL=http://127.0.0.1:8080 deploy/compose/smoke.sh

排障：Secret 缺 key 或 backend 安全配置非法时 Pod 不 ready，日志只输出固定配置错误码；
Secret 值原地变更后执行 kubectl -n veridex rollout restart deploy/veridex-veridex-backend。
```

- [ ] **Step 2: Write the three offline scripts**

Create `deploy/offline/scripts/export-images.sh`：

```bash
#!/usr/bin/env bash
set -euo pipefail

# 导出离线交付物：docker save + SHA-256 清单 + helm package。
# 输出目录 OFFLINE_DIR（默认仓库内 deploy/offline/veridex-offline）。
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
OFFLINE_DIR="${OFFLINE_DIR:-${ROOT_DIR}/deploy/offline/veridex-offline}"
IMAGES_FILE="${OFFLINE_DIR}/images.txt"

checksum() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1"; else shasum -a 256 "$1"; fi
}

command -v docker >/dev/null 2>&1 || { echo "export-images: docker required" >&2; exit 1; }
command -v helm >/dev/null 2>&1 || { echo "export-images: helm required" >&2; exit 1; }
test -f "${IMAGES_FILE}" || { echo "export-images: missing ${IMAGES_FILE}" >&2; exit 1; }

mkdir -p "${OFFLINE_DIR}/images" "${OFFLINE_DIR}/charts"
: > "${OFFLINE_DIR}/images.sha256"
echo "# generated by export-images.sh $(date -u +%Y-%m-%dT%H:%M:%SZ)" >> "${OFFLINE_DIR}/images.sha256"

while read -r image; do
  case "${image}" in '' | '#'*) continue ;; esac
  docker image inspect "${image}" >/dev/null 2>&1 \
    || { echo "export-images: image not built locally: ${image}" >&2; exit 1; }
  file="${OFFLINE_DIR}/images/$(echo "${image}" | tr '/:' '__').tar"
  docker save -o "${file}" "${image}"
  checksum "${file}" | sed "s|${OFFLINE_DIR}/||" >> "${OFFLINE_DIR}/images.sha256"
  echo "exported ${image} -> ${file}"
done < "${IMAGES_FILE}"

helm package "${ROOT_DIR}/deploy/helm/veridex" -d "${OFFLINE_DIR}/charts" >/dev/null
chart_tgz="$(ls "${OFFLINE_DIR}"/charts/veridex-*.tgz | head -n1)"
checksum "${chart_tgz}" | sed "s|${OFFLINE_DIR}/||" >> "${OFFLINE_DIR}/images.sha256"
echo "packaged ${chart_tgz}"
echo "export-images: done -> ${OFFLINE_DIR}"
```

Create `deploy/offline/scripts/import-images.sh`：

```bash
#!/usr/bin/env bash
set -euo pipefail

# 校验 SHA-256 后 docker load 离线镜像，并确认 images.txt 全部就位。
# 目录 OFFLINE_DIR（默认仓库内 deploy/offline/veridex-offline）。
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
OFFLINE_DIR="${OFFLINE_DIR:-${ROOT_DIR}/deploy/offline/veridex-offline}"
IMAGES_FILE="${OFFLINE_DIR}/images.txt"

sum_check() {
  if command -v sha256sum >/dev/null 2>&1; then (cd "${OFFLINE_DIR}" && sha256sum -c "$1"); \
  else (cd "${OFFLINE_DIR}" && shasum -a 256 -c "$1"); fi
}

command -v docker >/dev/null 2>&1 || { echo "import-images: docker required" >&2; exit 1; }
test -f "${OFFLINE_DIR}/images.sha256" || { echo "import-images: missing images.sha256" >&2; exit 1; }

# 过滤注释行后校验
checks="$(mktemp)"
grep -v '^#' "${OFFLINE_DIR}/images.sha256" > "${checks}"
test -s "${checks}" || { echo "import-images: images.sha256 has no checksum entries" >&2; exit 1; }
sum_check "${checks}"
rm -f "${checks}"

for tar in "${OFFLINE_DIR}"/images/*.tar; do
  test -f "${tar}" || continue
  docker load -i "${tar}"
done

# images.txt 与本地镜像一致性
while read -r image; do
  case "${image}" in '' | '#'*) continue ;; esac
  docker image inspect "${image}" >/dev/null 2>&1 \
    || { echo "import-images: image missing after load: ${image}" >&2; exit 1; }
done < "${IMAGES_FILE}"
echo "import-images: all images verified and loaded."
```

Create `deploy/offline/scripts/push-images.sh`：

```bash
#!/usr/bin/env bash
set -euo pipefail

# 把 images.txt 中的本地镜像重标记并推送到私有 registry（REGISTRY 必填）。
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
OFFLINE_DIR="${OFFLINE_DIR:-${ROOT_DIR}/deploy/offline/veridex-offline}"
IMAGES_FILE="${OFFLINE_DIR}/images.txt"

: "${REGISTRY:?push-images: REGISTRY must be set, e.g. REGISTRY=registry.corp.example:5000 $0}"
command -v docker >/dev/null 2>&1 || { echo "push-images: docker required" >&2; exit 1; }
test -f "${IMAGES_FILE}" || { echo "push-images: missing ${IMAGES_FILE}" >&2; exit 1; }

while read -r image; do
  case "${image}" in '' | '#'*) continue ;; esac
  docker image inspect "${image}" >/dev/null 2>&1 \
    || { echo "push-images: image not built locally: ${image}" >&2; exit 1; }
  target="${REGISTRY}/${image}"
  docker tag "${image}" "${target}"
  docker push "${target}"
  echo "pushed ${target}"
done < "${IMAGES_FILE}"

cat <<EOF
push-images: done。
安装 chart 时合并 values/registry-values.yaml 并设置 global.imageRegistry=${REGISTRY}
（注意：registry 前缀不要包含协议 scheme）。
EOF
```

Run: `chmod +x deploy/offline/scripts/*.sh`

- [ ] **Step 3: Implement and run verify-deployment Stage 5**

在 `scripts/verify-deployment.sh` 中新增（case 加 `5) stage5 ;;`，`all` 串联）：

```bash
stage5() {
  echo "==> [deploy-5] 离线交付包校验"
  local off="${ROOT_DIR}/deploy/offline/veridex-offline"
  for file in images.txt images.sha256 values/registry-values.yaml INSTALL.txt \
              ../scripts/export-images.sh ../scripts/import-images.sh ../scripts/push-images.sh; do
    test -f "${off}/${file}" || { echo "missing offline artifact: ${off}/${file}" >&2; exit 1; }
  done
  for script in export-images.sh import-images.sh push-images.sh; do
    grep -Fq 'set -euo pipefail' "${ROOT_DIR}/deploy/offline/scripts/${script}"
    test -x "${ROOT_DIR}/deploy/offline/scripts/${script}"
    bash -n "${ROOT_DIR}/deploy/offline/scripts/${script}"
  done
  grep -Fq 'veridex-backend:' "${off}/images.txt"
  grep -Fq 'veridex-web:' "${off}/images.txt"
  # 有镜像时的 export→import 往返验收
  if have_docker && have_helm \
     && docker image inspect "${BACKEND_IMAGE}" >/dev/null 2>&1 \
     && docker image inspect "${WEB_IMAGE}" >/dev/null 2>&1; then
    local tmp; tmp="$(mktemp -d)"
    OFFLINE_DIR="${tmp}" "${ROOT_DIR}/deploy/offline/scripts/export-images.sh" >/dev/null
    OFFLINE_DIR="${tmp}" "${ROOT_DIR}/deploy/offline/scripts/import-images.sh" >/dev/null
    rm -rf "${tmp}"
  else
    echo "offline roundtrip skipped (images/helm unavailable)."
  fi
  echo "verify-deployment stage 5 (offline bundle) passed."
}
```

Run: `./scripts/verify-deployment.sh 5`

Expected: 结尾打印 `verify-deployment stage 5 (offline bundle) passed.`（含 export→import 往返）。

- [ ] **Step 4: Commit**

```bash
git add deploy/offline scripts/verify-deployment.sh
git commit -m "feat: package offline delivery bundle" -m "Co-Authored-By: CodeTui <noreply@codetui.dev>"
```

---

### Task 8: 部署 exit gate、verify.sh 集成与文档

**Files:**
- Create: `backend/src/test/java/io/veridex/deployment/DeploymentExitGateTest.java`
- Modify: `scripts/verify.sh`
- Modify: `README.md`
- Modify: `docs/architecture.md`

**Interfaces:**
- Consumes Task 1–7 的全部交付物。
- Produces `scripts/verify.sh` 新步骤 `[6/7] Deployment artifacts checks` → `./scripts/verify-deployment.sh all`（原 `[6/6] git diff --check` 顺延为 `[7/7]`）。

- [ ] **Step 1: Write failing exit gate test**

Create `backend/src/test/java/io/veridex/deployment/DeploymentExitGateTest.java`：

```java
package io.veridex.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Phase 5-d exit gate：部署交付物完整且已接入统一门禁。 */
class DeploymentExitGateTest {

    private static final Path ROOT = Path.of("..");

    @Test
    void offlineDeliveryBundleIsComplete() throws IOException {
        Path offline = ROOT.resolve("deploy/offline/veridex-offline");
        for (String file : List.of("images.txt", "images.sha256", "values/registry-values.yaml",
                "scripts/export-images.sh", "scripts/import-images.sh", "scripts/push-images.sh",
                "../scripts/export-images.sh", "../scripts/import-images.sh", "../scripts/push-images.sh",
                "INSTALL.txt")) {
            assertThat(offline.resolve(file)).as("%s", file).exists();
        }
        String images = Files.readString(offline.resolve("images.txt"));
        assertThat(images).contains("veridex-backend:").contains("veridex-web:");
        assertThat(Files.readString(offline.resolve("INSTALL.txt")))
                .contains("existing Secret")
                .contains("trace-current-key");
    }

    @Test
    void clusterAcceptanceAndVerifyGateAreWired() throws IOException {
        Path script = ROOT.resolve("deploy/kind/run-acceptance.sh");
        assertThat(script).exists();
        assertThat(Files.isExecutable(script)).isTrue();
        assertThat(Files.readString(script)).contains("ENFORCE_NETWORKPOLICY");
        assertThat(Files.readString(ROOT.resolve("scripts/verify.sh"))).contains("verify-deployment.sh");
    }

    @Test
    void operatorDocsCoverDeployment() throws IOException {
        String readme = Files.readString(ROOT.resolve("README.md"));
        assertThat(readme).contains("deploy/helm/veridex");
        assertThat(readme).contains("verify-deployment.sh");
        assertThat(readme).contains("deploy/compose/smoke.sh");
        String architecture = Files.readString(ROOT.resolve("docs/architecture.md"));
        assertThat(architecture).contains("8081");
        assertThat(architecture).contains("existing Secret");
    }
}
```

- [ ] **Step 2: Run RED**

Run: `./mvnw -pl backend test -Dtest=DeploymentExitGateTest`

Expected: FAIL — `verify.sh` 未引用 `verify-deployment.sh`、README/architecture 尚无部署章节。

- [ ] **Step 3: Wire verify.sh and write docs**

`scripts/verify.sh`：把 `[4/6]`/`[5/6]`/`[6/6]` 序号改为 `[4/7]`/`[5/7]`，并在 `[5/7]` 之后插入：

```bash
echo "==> [6/7] Deployment artifacts checks"
"${ROOT_DIR}/scripts/verify-deployment.sh" all
```

原 `git diff --check` 段改为 `[7/7]`。

`README.md` 修改三处：

1. 第 5 行的引言说明替换为：

```markdown
> 应用已提供 backend/web 双容器镜像、Compose 完整应用栈、原生 Helm chart 与受限网络离线交付；详见下文「容器化与部署」。
```

2. 「当前限制」中 `deploy/compose/compose.yml` 只启动开发基础设施一条，替换为：

```markdown
- `deploy/compose/compose.yml` 可启动完整应用栈（基础设施 + backend + web）：`docker compose -f deploy/compose/compose.yml up -d --build backend web`；Prometheus 从 `backend:8081` 抓取，冒烟验收执行 `deploy/compose/smoke.sh`。
```

3. 「快速启动」末尾新增小节：

```markdown
### 5. 容器化与部署（Phase 5-d）

- 完整应用栈（本地验收）：`./scripts/verify-deployment.sh 3`
- 镜像构建与运行时检查：`./scripts/verify-deployment.sh 1` / `2`
- Helm chart 校验与 kind 集群验收：`./scripts/verify-deployment.sh 4`（chart 位于 `deploy/helm/veridex`，凭据必须使用 existing Secret）
- 受限网络离线交付：`./scripts/verify-deployment.sh 5` 与 `deploy/offline/veridex-offline/INSTALL.txt`
```

`docs/architecture.md` 新增「部署拓扑」小节：backend 8080（业务）/8081（管理，独立 Service，不进 Ingress）、web Nginx 8080 同源代理 `/api` 与 `/v3/api-docs`、镜像非 root/只读根文件系统、existing Secret 固定 key 清单、NetworkPolicy 默认 deny 与 selector/CIDR 二选一、ServiceMonitor 只抓管理 Service、备份恢复留给 Phase 5-e。

- [ ] **Step 4: Run GREEN**

Run: `./mvnw -pl backend test -Dtest=DeploymentExitGateTest`

Expected: PASS。

- [ ] **Step 5: Run focused deployment gate**

Run:

```bash
./mvnw -pl backend test "-Dtest=DockerfileStaticContractTest,HelmChartStaticContractTest,DeploymentExitGateTest"
./scripts/verify-deployment.sh all
```

Expected: 三个契约测试 PASS；`verify-deployment.sh all` 五个 stage 全部通过（工具缺失的 stage 打印 skipped 并通过）。

- [ ] **Step 6: Run complete repository verification**

Run:

```bash
./scripts/verify.sh
./mvnw -pl backend test -Dtest=ArchitectureTest
npm --prefix web run lint
npm --prefix web test
npm --prefix web run build
git diff --check
git status --short
```

Expected: backend、前端、observability/security/deployment 校验与 diff 检查全部通过；status 只含本任务预期文件。

- [ ] **Step 7: Commit**

```bash
git add scripts/verify.sh README.md docs/architecture.md backend/src/test/java/io/veridex/deployment/DeploymentExitGateTest.java
git commit -m "docs: complete phase5d deployment exit gate" -m "Co-Authored-By: CodeTui <noreply@codetui.dev>"
```

---

## Execution Order and Review Gates

Execute Tasks 1-8 in order. Task 1-2 establish the two images; Task 3 makes Compose the first real consumer of both and fixes the Prometheus target. Task 4 locks the chart values contract and workload security baseline; Task 5 only adds gated optional templates on top of it. Task 6 validates chart + cluster; it depends on every earlier template decision. Task 7 packages what 1-5 produced. Task 8 is the only documentation and repository gate task.

After every Task:

1. run the task-specific tests / stage command;
2. run `git diff --check`;
3. inspect rendered manifests/logs for forbidden content (明文凭据、`latest`、`0.0.0.0/0`、actuator 暴露);
4. commit only a green, independently reviewable task.

## Acceptance-Criteria Mapping

| 设计验收标准（§16） | Task(s) |
|---|---|
| 1 双镜像可重复构建 + amd64/arm64 发布路径 | 1, 2, 7 |
| 2 非 root、只读根文件系统、最小 capability、无构建工具/secret | 1, 2, 4 |
| 3 Web 同源代理保持 Session、CSRF、request ID、SSE | 2, 3, 6 |
| 4 Compose 完整栈 + Prometheus 抓 `backend:8081` | 3 |
| 5 chart 只管理应用 + existing Secret 接入外部设施 | 4 |
| 6 ClusterIP 默认与 Ingress/TLS 可选均过 lint/template/集群验收 | 4, 5, 6 |
| 7 startup/readiness/liveness、rolling update、grace、PDB、HPA | 4, 5, 6 |
| 8 管理端口不进 Ingress；ServiceMonitor 只抓 management Service | 4, 5, 6 |
| 9 默认 NetworkPolicy 最小入口/出站权限 | 5, 6 |
| 10 values schema 拒绝非法镜像/端口/Secret/Ingress/HPA/PDB/安全组合 | 4, 5, 6 |
| 11 镜像清单、校验和、导入/推送脚本、registry values、chart 包 | 7 |
| 12 verify.sh、镜像检查、Compose/Helm/集群/离线验收、git diff --check 全绿 | 1-8 |

## Plan Self-Review

- 设计 §16 的 12 条验收标准全部映射到 Task 1-8；§17 固定决策逐条落在 Global Constraints 与对应模板中，实施中无需重新选择。
- 占位扫描：无 TBD/TODO/「后续实现」步骤；所有代码步骤给出完整代码，所有命令给出预期输出。Task 4 Step 4 backend Deployment 代码块中的 `VERIDEX_BACKEND_UPSTREAM` 行附有明确警告（该变量只属于 web Deployment），不是待办。
- 类型/命名一致性：`VERIDEX_BACKEND_UPSTREAM` 在 Dockerfile 默认值、Compose、web Deployment、静态断言四处一致；`veridex.image`/`veridex.fullname`/selector helpers 的签名与 Task 5/6 的调用一致；externalEgress 条目的 `type/namespace/podSelector/cidr/port` 字段在 values 注释、schema、模板、kind-values 四处一致；smoke.sh 的 `VERIDEX_WEB_URL`/`VERIDEX_PROMETHEUS_URL` 契约在 Task 3 定义、Task 6 复用。
- 与 5-a/5-b/5-c 契约无冲突：管理端口 8081 与 `management.endpoints` 白名单不改；Prometheus 抓取目标仅从 `host.docker.internal:8081` 改为 `backend:8081`（同一管理端口的容器化地址）；`verify-security.sh` 断言同步；业务测试代码不动。
- 已知环境取舍已在文中标注：kind 默认 CNI 不执行 NetworkPolicy（`ENFORCE_NETWORKPOLICY=1` 时装 Calico 才跑拒绝测试）；OpenSearch 依赖容器内存较大；多架构 manifest 构建命令放入 INSTALL.txt 而非本机门禁。
