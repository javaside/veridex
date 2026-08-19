# Phase 5-e 备份恢复与容量验证 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Execute tasks in order and do not delegate them to subagents.

**Goal:** 落地 Phase 5-e 设计——PG+MinIO 备份/恢复脚本与一键演练（OpenSearch 源重建）、Spring Session JDBC 多副本就绪（移除闲置 redis）、1M chunk 容量实测工具与 k6 负载、容量报告，构成 Phase 5 退出门禁证据。

**Architecture:** 备份 = `pg_dump -Fc` + MinIO 对象导出 + SHA-256 manifest；恢复 = 校验 → pg_restore → 对象回灌 → 一次性 `reindex` profile 容器复用 `KnowledgeBasePublishService.publish` 全量重建 → smoke 验收。Session 外部化到现有 PG（Spring Session JDBC + Flyway V15），compose 删除零引用的 redis。容量工具为独立 maven 模块 `tools/capacity`（不进根构建链）：Java 生成器直连 PG/MinIO/OpenSearch 灌 1M chunk，k6 以普通 POST 读完整 SSE 流测全链路时延（首 token 时延从 Prometheus `veridex.generation.first_token` 查询），报告汇总 1M 实测 + 5M 外推 + 评测干扰量化 + 恢复演练记录。

**Tech Stack:** Bash（`set -euo pipefail`）、`pg_dump`/`pg_restore`、MinIO `mc`（或 aws cli s3 兼容面）、Spring Session JDBC（Boot BOM）、Flyway V15、JUnit5 静态契约测试（DeploymentExitGateTest 风格）、独立 Maven 模块 + PostgreSQL JDBC + OpenSearch Java Client + MinIO SDK、k6、Prometheus remote-write、Grafana。

**Spec:** `docs/superpowers/specs/2026-08-19-phase5e-backup-recovery-capacity-design.md`（状态：已确认）。本计划逐条落实该 spec §3 决策 D1–D7、§4 组件设计、§5 失败边界、§6 部署契约、§7 测试策略与 §8 验收标准。

## Global Constraints

- 备份只覆盖 PG + MinIO；OpenSearch 一律源重建，不引入 snapshot repository（spec D1）。
- 所有脚本 `set -euo pipefail`；备份失败不留半成品目录；无 manifest 即无有效备份。
- restore 无 `--fresh` 时拒绝在非空数据卷上恢复；`--fresh` 需二次确认输入 `YES`；迁移版本不兼容（目标 schema_version > 备份版本）拒绝恢复。
- 恢复后「PG 有记录但 MinIO 缺对象」的文档走现有 ingestion 失败路径显式标记，不静默跳过。
- 不新增常驻 reindex API；重建经 `reindex` profile 的一次性容器完成，跑完即退（成功退出码 0，失败 1）。
- Spring Session：`store-type=jdbc`、`initialize-schema=never`（Flyway V15 建表）、清理用内建每分钟任务（`cleanup-cron` 默认 `0 * * * * *`）、不自造清理器；新环境变量仅 `VERIDEX_SESSION_TIMEOUT`（默认 8h）。
- redis 从 compose 与测试基建移除后，全量门禁必须保持绿。
- `tools/capacity` 为独立 maven 模块（parent 不指向根 pom、不进根 `<modules>`），verify.sh 不触发它。
- 容量实测 embedding 用 deterministic 128 维（可复现）；deterministic 与 Ollama（本机 `qwen3.5:9b-mlx`）双曲线，结论不混。
- k6 用普通 `http.post` 读完整 SSE 流测全链路时延（流关闭才算一次迭代完成）；首 token 时延取自 Prometheus `veridex.generation.first_token` 的 P99，不依赖 k6 实验性 SSE API。
- 5M 外推在报告标注「非实测」；verify-recovery 与 1M 实测是**显式门禁步骤**（不进 verify.sh 自动门禁），结果人工记录进容量报告。
- 每个 Task 先 RED 后 GREEN，独立提交；提交尾注固定 `Co-Authored-By: CodeTui <noreply@codetui.dev>`；每个任务结束运行 `git diff --check`。
- 环境约束（记忆）：本机 DNS 污染、docker hub 走 daocloud mirror、helm 走 docker 包装；集成测试依赖 compose 基础设施容器在运行（`docker compose -f deploy/compose/compose.yml up -d`）。

---

## File Structure

**Session 外部化（Task 1/2）：**

- Modify `backend/pom.xml` — 加 `spring-session-jdbc`。
- Modify `backend/src/main/resources/application.yml` — `spring.session.*` + `VERIDEX_SESSION_TIMEOUT`。
- Create `backend/src/main/resources/db/migration/V15__spring_session_jdbc.sql` — Spring Session 官方 PG schema。
- Create `backend/src/test/java/io/veridex/iam/SessionPersistenceIntegrationTest.java` — 登录后 SPRING_SESSION 表断言。
- Modify `deploy/compose/compose.yml` — 删 redis 服务与卷引用。
- Modify `backend/src/test/java/io/veridex/support/InfrastructureContainers.java` — 删 REDIS 容器。
- Modify `backend/src/test/java/io/veridex/support/InfrastructureSmokeTest.java` — 删 redis 探活断言。

**备份/恢复（Task 3/4/5）：**

- Create `deploy/backup/backup.sh` — PG dump + MinIO 导出 + manifest。
- Create `deploy/backup/restore.sh` — 校验/恢复/触发重建。
- Create `backend/src/main/java/io/veridex/indexing/application/ReindexRunner.java` — `@Profile("reindex")` 一次性全量重建 runner。
- Create `deploy/backup/verify-recovery.sh` — 一键演练（起栈→种子→备份→销毁→恢复→smoke→RTO 报告）。
- Create `backend/src/test/java/io/veridex/deployment/BackupScriptStaticContractTest.java` — 脚本静态契约。
- Modify `deploy/compose/compose.yml` — backend 支持传 `SPRING_PROFILES_ACTIVE`（reindex 模式覆盖入口）。
- Modify `docs/architecture.md`、`deploy/offline/veridex-offline/INSTALL.txt` — 备份恢复说明。

**容量工具（Task 6/7）：**

- Create `tools/capacity/pom.xml` — 独立模块。
- Create `tools/capacity/src/main/java/io/veridex/capacity/SeedGenerator.java` — 1M chunk 合成数据。
- Create `tools/capacity/src/main/java/io/veridex/capacity/DeterministicEmbedding.java` — 复刻 backend 128 维算法。
- Create `tools/capacity/src/main/java/io/veridex/capacity/ReindexBenchmark.java` — 全量重建计时。
- Create `tools/capacity/README.md` — 执行手册。
- Create `tools/capacity/k6/qa-load.js` + `tools/capacity/k6/run-matrix.sh` — 负载脚本与梯度矩阵。

**报告与门禁（Task 8）：**

- Create `docs/capacity-report-2026-08.md` — 报告（模板先行、实测数据随执行填充）。
- Modify `README.md`、`docs/architecture.md` — 备份入口、Session 语义、报告索引。
- Modify `scripts/verify.sh` — 无改动（静态契约已随 backend 测试进 Maven）；确认不误触发 tools/capacity。

---

### Task 1: Spring Session JDBC

**Files:**
- Modify: `backend/pom.xml`
- Modify: `backend/src/main/resources/application.yml`
- Create: `backend/src/main/resources/db/migration/V15__spring_session_jdbc.sql`
- Test: Create `backend/src/test/java/io/veridex/iam/SessionPersistenceIntegrationTest.java`

**Interfaces:**
- Consumes: 现有 `PostgresIntegrationTest` 基类、`InfrastructureContainers`（PG 容器）、登录端点 `POST /api/auth/login?username=...&password=veridex`。
- Produces: Session 存储外部化到 PG（`SPRING_SESSION`/`SPRING_SESSION_ATTRIBUTES` 表）；环境变量 `VERIDEX_SESSION_TIMEOUT`（默认 `8h`）。后续任务（kind 滚动升级断言、容量负载登录态）依赖此行为。

- [ ] **Step 1: 写失败的 Session 持久化集成测试**

Create `SessionPersistenceIntegrationTest.java`：

```java
package io.veridex.iam;

import static org.assertj.core.api.Assertions.assertThat;

import io.veridex.support.PostgresIntegrationTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Session 外部化（spec §4.4）：登录后 Session 必须落到 SPRING_SESSION 表，
 * 属性（用户）落到 SPRING_SESSION_ATTRIBUTES——多副本/重启共享登录态的物理证据。
 */
class SessionPersistenceIntegrationTest extends PostgresIntegrationTest {

    @LocalServerPort int port;

    @Autowired JdbcTemplate jdbc;

    @Test
    void loginPersistsSessionToDatabase() throws Exception {
        var http = java.net.http.HttpClient.newHttpClient();
        var request = java.net.http.HttpRequest.newBuilder(
                        java.net.URI.create("http://localhost:" + port
                                + "/api/auth/login?username=admin&password=veridex"))
                .POST(java.net.http.HttpRequest.BodyPublishers.noBody())
                .build();
        var response = http.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isLessThan(300);

        // Session 行落库，且未被标记过期
        Integer active = jdbc.queryForObject(
                "SELECT COUNT(*) FROM SPRING_SESSION WHERE EXPIRY_TIME > NOW()", Integer.class);
        assertThat(active).isGreaterThanOrEqualTo(1);

        // 登录用户属性进入属性表（principal_name = admin）
        List<String> principals = jdbc.queryForList(
                "SELECT PRINCIPAL_NAME FROM SPRING_SESSION WHERE PRINCIPAL_NAME = 'admin'", String.class);
        assertThat(principals).isNotEmpty();
    }

    @Test
    void sessionSchemaHasExpiryIndex() {
        // 官方 schema 的 IX2 索引（EXPIRY_TIME）支撑每分钟清理任务
        Integer indexes = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pg_indexes WHERE indexname = 'SPRING_SESSION_IX2'", Integer.class);
        assertThat(indexes).isEqualTo(1);
    }
}
```

- [ ] **Step 2: 运行确认 RED**

Run: `./mvnw -f backend/pom.xml test -Dtest=SessionPersistenceIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL——`SPRING_SESSION` 表不存在（Relation does not exist）。

- [ ] **Step 3: 加依赖、配置与 V15 迁移**

`backend/pom.xml`（dependencies 段，spring-boot-starter-test 之前）：

```xml
        <!-- Session 外部化到 PostgreSQL（Phase 5-e spec D2）：多副本滚动/故障不丢登录态 -->
        <dependency>
            <groupId>org.springframework.session</groupId>
            <artifactId>spring-session-jdbc</artifactId>
        </dependency>
```

`application.yml` 的 `spring:` 块（`mvc.async` 之后）：

```yaml
  session:
    store-type: jdbc
    timeout: ${VERIDEX_SESSION_TIMEOUT:8h}
    jdbc:
      initialize-schema: never
      table-name: SPRING_SESSION
```

Create `V15__spring_session_jdbc.sql`（Spring Session 官方 PostgreSQL schema，5..x）：

```sql
-- Spring Session JDBC 官方 PostgreSQL schema（spring-session 5.x，spec §4.4）。
-- initialize-schema=never：由 Flyway 管理；cleanup-cron 默认每分钟按 IX2 索引删除过期行，
-- 属性表经外键 ON DELETE CASCADE 级联删除。
CREATE TABLE SPRING_SESSION (
    PRIMARY_ID CHAR(36) NOT NULL,
    SESSION_ID CHAR(36) NOT NULL,
    CREATION_TIME BIGINT NOT NULL,
    LAST_ACCESS_TIME BIGINT NOT NULL,
    MAX_INACTIVE_INTERVAL INT NOT NULL,
    EXPIRY_TIME BIGINT NOT NULL,
    PRINCIPAL_NAME VARCHAR(100),
    CONSTRAINT SPRING_SESSION_PK PRIMARY KEY (PRIMARY_ID)
);

CREATE UNIQUE INDEX SPRING_SESSION_IX1 ON SPRING_SESSION (SESSION_ID);
CREATE INDEX SPRING_SESSION_IX2 ON SPRING_SESSION (EXPIRY_TIME);
CREATE INDEX SPRING_SESSION_IX3 ON SPRING_SESSION (PRINCIPAL_NAME);

CREATE TABLE SPRING_SESSION_ATTRIBUTES (
    SESSION_PRIMARY_ID CHAR(36) NOT NULL,
    ATTRIBUTE_NAME VARCHAR(200) NOT NULL,
    ATTRIBUTE_BYTES BYTEA NOT NULL,
    CONSTRAINT SPRING_SESSION_ATTRIBUTES_PK PRIMARY KEY (SESSION_PRIMARY_ID, ATTRIBUTE_NAME),
    CONSTRAINT SPRING_SESSION_ATTRIBUTES_FK FOREIGN KEY (SESSION_PRIMARY_ID)
        REFERENCES SPRING_SESSION (PRIMARY_ID) ON DELETE CASCADE
);

CREATE INDEX SPRING_SESSION_ATTRIBUTES_IX1 ON SPRING_SESSION_ATTRIBUTES (SESSION_PRIMARY_ID);
```

- [ ] **Step 4: 运行测试确认 GREEN + 登录/CSRF 回归**

Run: `./mvnw -f backend/pom.xml test -Dtest='SessionPersistenceIntegrationTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS。
Run: `./mvnw -f backend/pom.xml test -Dtest='*Auth*,*Csrf*,*Login*' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS（现有认证/CSRF 测试不受存储层替换影响；若有失败逐个修复——典型问题是 Session 属性需可序列化，检查 iam 模块放入 Session 的对象）。

- [ ] **Step 5: 提交**

```bash
git add backend/pom.xml backend/src/main/resources/application.yml \
  backend/src/main/resources/db/migration/V15__spring_session_jdbc.sql \
  backend/src/test/java/io/veridex/iam/SessionPersistenceIntegrationTest.java
git commit -m "$(cat <<'EOF'
feat: externalize sessions to postgresql via spring session jdbc

Co-Authored-By: CodeTui <noreply@codetui.dev>
EOF
)"
git diff --check
```

---

### Task 2: 移除闲置 redis

**Files:**
- Modify: `deploy/compose/compose.yml`（redis 服务块，约 30-38 行）
- Modify: `backend/src/test/java/io/veridex/support/InfrastructureContainers.java`
- Modify: `backend/src/test/java/io/veridex/support/InfrastructureSmokeTest.java`

**Interfaces:**
- Consumes: Task 1 已确认 Session 不依赖 redis。
- Produces: compose 栈与测试基建无 redis；后续容量负载与恢复演练的环境更小。

- [ ] **Step 1: 删 compose redis 服务**

删除 `deploy/compose/compose.yml` 中整个 `redis:` 服务块（image redis:8-alpine、command、healthcheck、ports）。`volumes:` 顶层无 redis 卷（确认后无需改）。检查 `depends_on` 无引用 redis（当前无，grep 确认）。

- [ ] **Step 2: 删测试容器与断言**

`InfrastructureContainers.java` 删：

```java
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:8-alpine"))
            .withExposedPorts(6379);
```

`InfrastructureSmokeTest.java` 删 `assertThat(REDIS.isRunning()).isTrue();` 一行（及未用 import）。

- [ ] **Step 3: 全量回归确认**

Run: `docker compose -f deploy/compose/compose.yml config -q && echo "compose OK"`
Run: `./mvnw -f backend/pom.xml test -Dtest='InfrastructureSmokeTest,ContainerLifecycleTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS。

- [ ] **Step 4: 提交**

```bash
git add deploy/compose/compose.yml backend/src/test/java/io/veridex/support
git commit -m "$(cat <<'EOF'
chore: drop unused redis from compose and test infrastructure

Co-Authored-By: CodeTui <noreply@codetui.dev>
EOF
)"
git diff --check
```

---

### Task 3: backup.sh

**Files:**
- Create: `deploy/backup/backup.sh`
- Test: Create `backend/src/test/java/io/veridex/deployment/BackupScriptStaticContractTest.java`

**Interfaces:**
- Consumes: 运行中的 compose 栈（postgres + minio 容器）；compose `.env` 的凭据变量（`POSTGRES_USER/POSTGRES_PASSWORD/POSTGRES_DB`、`MINIO_ROOT_USER/MINIO_ROOT_PASSWORD`）。
- Produces: `backups/<timestamp>/{manifest.json, postgres.dump, objects/...}`；`backup.sh [--stack <dir>] [--out <dir>]`，退出码 0/非 0。Task 4/5 消费该产物结构与 manifest 字段。

- [ ] **Step 1: 写失败的静态契约测试**

Create `BackupScriptStaticContractTest.java`：

```java
package io.veridex.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 备份/恢复脚本静态契约（spec §6/§7）：存在、fail-fast、manifest 字段、--fresh 保护。
 */
class BackupScriptStaticContractTest {

    private static final Path BACKUP = Path.of("..", "deploy", "backup");

    private String script(String name) throws Exception {
        Path p = BACKUP.resolve(name);
        assertThat(p).as("%s", name).exists();
        return Files.readString(p);
    }

    @Test
    void backupScriptIsFailFastAndWritesManifest() throws Exception {
        String s = script("backup.sh");
        assertThat(s).contains("set -euo pipefail");
        assertThat(s).contains("pg_dump");
        assertThat(s).contains("manifest.json");
        assertThat(s).contains("shasum -a 256");   // SHA-256 校验和
        assertThat(s).contains("flyway_schema_history"); // 记录迁移版本
        // 失败清理：不留半成品
        assertThat(s).contains("trap");
        assertThat(s).contains("rm -rf");
    }

    @Test
    void restoreScriptGuardsNonFreshAndIncompatibleVersions() throws Exception {
        String s = script("restore.sh");
        assertThat(s).contains("set -euo pipefail");
        assertThat(s).contains("pg_restore");
        // --fresh 二次确认
        assertThat(s).contains("--fresh");
        assertThat(s).contains("YES");
        // 版本兼容校验（目标 schema_version 不得高于备份版本）
        assertThat(s).contains("schema_version");
        // 一次性重建容器
        assertThat(s).contains("reindex");
        assertThat(s).contains("SPRING_PROFILES_ACTIVE=reindex");
    }

    @Test
    void recoveryDrillScriptDrivesFullCycle() throws Exception {
        String s = script("verify-recovery.sh");
        assertThat(s).contains("set -euo pipefail");
        assertThat(s).contains("backup.sh");
        assertThat(s).contains("restore.sh");
        assertThat(s).contains("smoke.sh");
        assertThat(s).contains("--fresh");
        assertThat(s).contains("RTO");
    }
}
```

- [ ] **Step 2: 运行确认 RED**

Run: `./mvnw -f backend/pom.xml test -Dtest=BackupScriptStaticContractTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL（脚本不存在）。

- [ ] **Step 3: 实现 backup.sh**

Create `deploy/backup/backup.sh`：

```bash
#!/usr/bin/env bash
set -euo pipefail

# PG + MinIO 一致性备份（Phase 5-e spec §4.1）。OpenSearch 不备份（源重建，spec D1）。
# 用法: ./deploy/backup/backup.sh [--stack deploy/compose] [--out ./backups]
# 产物: <out>/<timestamp>/{manifest.json, postgres.dump, objects/<对象键>}
# 失败: 非零退出并清理半成品目录（trap）。

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
STACK="${ROOT_DIR}/deploy/compose"
OUT_DIR="${ROOT_DIR}/backups"
COMPOSE=(docker compose -f "${STACK}/compose.yml" --env-file "${STACK}/.env")

while [ $# -gt 0 ]; do
  case "$1" in
    --stack) STACK="$2"; shift 2 ;;
    --out) OUT_DIR="$2"; shift 2 ;;
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
done
COMPOSE=(docker compose -f "${STACK}/compose.yml")

STAMP="$(date +%Y%m%dT%H%M%S)"
BACKUP_DIR="${OUT_DIR}/${STAMP}"
TMP_DIR=""

cleanup() {
  if [ -n "${TMP_DIR:-}" ] && [ -d "${TMP_DIR}" ]; then
    echo "backup: 失败，清理半成品 ${TMP_DIR}" >&2
    rm -rf "${TMP_DIR}"
  fi
}
trap cleanup ERR

fail() { echo "backup: $1" >&2; exit 1; }

echo "==> 校验目标栈在运行"
"${COMPOSE[@]}" ps postgres --status running >/dev/null 2>&1 \
  || "${COMPOSE[@]}" ps --format '{{.Name}} {{.State}}' | grep -q "postgres.*running" \
  || fail "postgres 容器未运行（先 docker compose up -d）"

TMP_DIR="$(mktemp -d "${OUT_DIR}/.partial-${STAMP}-XXXXXX")"
mkdir -p "${TMP_DIR}/objects"

echo "==> 1/4 PostgreSQL 逻辑备份（pg_dump -Fc）"
"${COMPOSE[@]}" exec -T postgres sh -c \
  'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc' > "${TMP_DIR}/postgres.dump" \
  || fail "pg_dump 失败"
[ -s "${TMP_DIR}/postgres.dump" ] || fail "pg_dump 产物为空"

echo "==> 2/4 读取迁移版本与活跃发布状态"
SCHEMA_VERSION="$("${COMPOSE[@]}" exec -T postgres sh -c \
  "psql -U \"\$POSTGRES_USER\" -d \"\$POSTGRES_DB\" -tAc 'SELECT COALESCE(MAX(version),0) FROM flyway_schema_history WHERE success'" \
  | tr -d '[:space:]')"
ACTIVE_RELEASES="$("${COMPOSE[@]}" exec -T postgres sh -c \
  "psql -U \"\$POSTGRES_USER\" -d \"\$POSTGRES_DB\" -tAc \"SELECT knowledge_base_id || ':' || index_name FROM index_release WHERE is_active\"" \
  | tr '\n' ';' )"

echo "==> 3/4 MinIO 对象导出（veridex-documents bucket）"
# 逐对象下载，保留对象键结构；用 mc（容器内或宿主机）
export MC_HOST_veridex="http://${MINIO_ROOT_USER:-veridex}:${MINIO_ROOT_PASSWORD:-veridex-local-secret}@localhost:9000"
# MinIO 在 compose 网络内；通过 compose exec 在 minio 容器内列对象，宿主机逐个拉取不可行——
# 改为在容器内打包对象键清单，再逐键 docker cp 语义导出：
"${COMPOSE[@]}" exec -T minio sh -c \
  "ls -1 /data/veridex-documents/**/* 2>/dev/null || find /data/veridex-documents -type f | sort" \
  > "${TMP_DIR}/.object-keys.txt" || fail "枚举 MinIO 对象失败"
OBJECT_COUNT=0
while IFS= read -r objkey; do
  [ -n "${objkey}" ] || continue
  rel="${objkey#/data/veridex-documents/}"
  mkdir -p "${TMP_DIR}/objects/$(dirname "${rel}")"
  docker cp "$("${COMPOSE[@]}" ps -q minio):${objkey}" "${TMP_DIR}/objects/${rel}" \
    || fail "导出对象失败: ${rel}"
  OBJECT_COUNT=$((OBJECT_COUNT + 1))
done < "${TMP_DIR}/.object-keys.txt"
rm -f "${TMP_DIR}/.object-keys.txt"
echo "    导出 ${OBJECT_COUNT} 个对象"

echo "==> 4/4 manifest 与校验和"
( cd "${TMP_DIR}" && find . -type f ! -name manifest.json -print0 | xargs -0 shasum -a 256 > .sums )
( cd "${TMP_DIR}" && find . -type f ! -name manifest.json ! -name .sums | sort )

APP_VERSION="$( (cd "${ROOT_DIR}" && git rev-parse --short HEAD 2>/dev/null) || echo unknown )"
cat > "${TMP_DIR}/manifest.json" <<EOF
{
  "created_at": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "app_version": "${APP_VERSION}",
  "schema_version": ${SCHEMA_VERSION:-0},
  "active_releases": "${ACTIVE_RELEASES}",
  "postgres_dump": "postgres.dump",
  "object_count": ${OBJECT_COUNT},
  "checksums_file": ".sums"
}
EOF
mv "${TMP_DIR}/.sums" "${TMP_DIR}/SHA256SUMS"

echo "==> 原子发布备份目录"
mv "${TMP_DIR}" "${BACKUP_DIR}"
trap - ERR
echo "backup: 完成 ${BACKUP_DIR}（schema_version=${SCHEMA_VERSION}, objects=${OBJECT_COUNT}）"
```

注意：MinIO 对象导出的具体机制在执行时验证——上面用 `docker cp` 逐对象拷贝（可靠但慢，容量场景的 1M chunk 对象由生成器直接写 MinIO，备份数量以种子数据量级为准，可接受）；若 `.env` 中无 MINIO 凭据变量，回退到 compose.yml 默认值 `veridex/veridex-local-secret`（脚本里写死回退，与 compose 默认一致）。`chmod +x deploy/backup/backup.sh`。

- [ ] **Step 4: 运行契约测试确认 GREEN**

Run: `./mvnw -f backend/pom.xml test -Dtest=BackupScriptStaticContractTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: backupScript 断言 PASS（restore/verify-recovery 仍 FAIL——Task 4/5 实现）。

- [ ] **Step 5: 对运行中的 compose 栈实测一次**

前置：`docker compose -f deploy/compose/compose.yml up -d`（postgres/minio 运行中）。
Run: `./deploy/backup/backup.sh`
Expected: 退出 0，`backups/<stamp>/` 含 manifest.json、postgres.dump、objects/、SHA256SUMS；`cat manifest.json` 字段齐全。失败则修脚本至通过。

- [ ] **Step 6: 提交**

```bash
git add deploy/backup/backup.sh backend/src/test/java/io/veridex/deployment/BackupScriptStaticContractTest.java
git commit -m "$(cat <<'EOF'
feat: add pg and minio backup script with sha256 manifest

Co-Authored-By: CodeTui <noreply@codetui.dev>
EOF
)"
git diff --check
```

---

### Task 4: ReindexRunner + restore.sh

**Files:**
- Create: `backend/src/main/java/io/veridex/indexing/application/ReindexRunner.java`
- Modify: `deploy/compose/compose.yml`（backend environment 支持 `SPRING_PROFILES_ACTIVE` 透传）
- Create: `deploy/backup/restore.sh`
- Test: Modify `backend/src/test/java/io/veridex/deployment/BackupScriptStaticContractTest.java`（Step 1 已含 restore 断言）；Create `backend/src/test/java/io/veridex/indexing/ReindexRunnerTest.java`

**Interfaces:**
- Consumes: `KnowledgeBaseRepository.findAll()`、`KnowledgeBasePublishService.publish(UUID knowledgeBaseId)`（返回 `PublishResult`）、`ApplicationExitCodeGenerator`（Spring Boot 退出码协议）。
- Produces:
  - `ReindexRunner`：`@Profile("reindex")` + `ApplicationRunner`；对每个 KB 调 `publish`，全部成功 `System.exit` 码 0，任一失败码 1（经 `ExitCodeGenerator`）。
  - `restore.sh --backup <dir> [--fresh]`：Task 5 的 verify-recovery 消费。

- [ ] **Step 1: 写失败的 ReindexRunner 测试**

Create `ReindexRunnerTest.java`：

```java
package io.veridex.indexing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.veridex.indexing.api.ReleaseView;
import io.veridex.indexing.application.KnowledgeBasePublishService;
import io.veridex.indexing.application.ReindexRunner;
import io.veridex.knowledge.domain.KnowledgeBase;
import io.veridex.knowledge.domain.KnowledgeBaseRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 一次性全量重建（spec §4.2）：reindex profile 下对全部 KB 逐个 publish；
 * 任一失败返回退出码 1，全部成功 0。publish 幂等保证重跑安全。
 */
@ExtendWith(MockitoExtension.class)
class ReindexRunnerTest {

    @Mock KnowledgeBaseRepository knowledgeBases;
    @Mock KnowledgeBasePublishService publisher;
    @InjectMocks ReindexRunner runner;

    private static KnowledgeBase kb(String name) {
        return new KnowledgeBase(name, "容量/恢复");
    }

    @Test
    void republishesEveryKnowledgeBaseAndExitsZero() throws Exception {
        KnowledgeBase a = kb("A");
        KnowledgeBase b = kb("B");
        when(knowledgeBases.findAll()).thenReturn(List.of(a, b));
        when(publisher.publish(any())).thenReturn(publishResult());

        runner.run(null);

        assertThat(runner.getExitCode()).isZero();
        org.mockito.Mockito.verify(publisher).publish(a.getId());
        org.mockito.Mockito.verify(publisher).publish(b.getId());
    }

    @Test
    void exitsNonZeroWhenAnyPublishFails() throws Exception {
        when(knowledgeBases.findAll()).thenReturn(List.of(kb("A"), kb("B")));
        when(publisher.publish(any()))
                .thenReturn(publishResult())
                .thenThrow(new IllegalStateException("no READY document versions to publish"));

        runner.run(null);

        assertThat(runner.getExitCode()).isEqualTo(1);
    }

    private static ReleaseView publishResult() {
        return new ReleaseView(UUID.randomUUID(), 1, "veridex-x-active", "veridex-x-1",
                "veridex-x-active", IndexReleaseStatus.ACTIVE, null, null, 0, 0);
    }
}
```

（`ReleaseView`/`KnowledgeBase` 构造签名以实际代码为准——执行时先读这两个类，按真实字段调整测试构造，断言语义不变。）

- [ ] **Step 2: 运行确认 RED**

Run: `./mvnw -f backend/pom.xml test -Dtest=ReindexRunnerTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL（ReindexRunner 不存在）。

- [ ] **Step 3: 实现 ReindexRunner**

Create `ReindexRunner.java`：

```java
package io.veridex.indexing.application;

import io.veridex.knowledge.domain.KnowledgeBaseRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 一次性全量重建（spec §4.2）：仅 reindex profile 装配。恢复脚本用它起一次性容器，
 * 对全部知识库逐个全量发布（幂等、alias 切换、状态机均复用 publish 语义），跑完即退。
 * 不暴露任何 HTTP/常驻 API。成功退出码 0，任一 KB 失败 1（重跑安全）。
 */
@Component
@Profile("reindex")
public class ReindexRunner implements ApplicationRunner, ExitCodeGenerator {

    private static final Logger log = LoggerFactory.getLogger(ReindexRunner.class);

    private final KnowledgeBaseRepository knowledgeBases;
    private final KnowledgeBasePublishService publisher;
    private volatile int exitCode = 0;

    public ReindexRunner(KnowledgeBaseRepository knowledgeBases, KnowledgeBasePublishService publisher) {
        this.knowledgeBases = knowledgeBases;
        this.publisher = publisher;
    }

    @Override
    public void run(ApplicationArguments args) {
        int ok = 0;
        int failed = 0;
        for (var kb : knowledgeBases.findAll()) {
            try {
                publisher.publish(kb.getId());
                ok++;
                log.info("reindex: kb={} published", kb.getId());
            } catch (RuntimeException e) {
                failed++;
                // 消息只含 KB id 与异常类名，不落证据/内容（可观测性边界）
                log.warn("reindex: kb={} failed: {}", kb.getId(), e.getClass().getSimpleName());
            }
        }
        if (failed > 0) {
            exitCode = 1;
        }
        log.info("reindex: done ok={} failed={}", ok, failed);
        // Boot 3.3：runner 内直接以退出码结束进程（ExitCodeGenerator 兜底异常路径）
        System.exit(exitCode);
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }
}
```

- [ ] **Step 4: 运行测试确认 GREEN + reindex profile 不影响默认启动**

Run: `./mvnw -f backend/pom.xml test -Dtest=ReindexRunnerTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS。
Run: `./mvnw -f backend/pom.xml test -Dtest='VeridexApplicationTest,ArchitectureTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS（默认 profile 不装配 runner；模块边界不变）。

- [ ] **Step 5: 实现 restore.sh**

Create `deploy/backup/restore.sh`：

```bash
#!/usr/bin/env bash
set -euo pipefail

# 恢复 + OpenSearch 源重建（spec §4.2）。
# 用法: ./deploy/backup/restore.sh --backup backups/<stamp> [--fresh]
# --fresh: 删除并重建 postgres/opensearch 数据卷（需输入 YES 确认）
# 无 --fresh: 目标数据卷非空则拒绝（防误覆盖）。

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
STACK="${ROOT_DIR}/deploy/compose"
COMPOSE=(docker compose -f "${STACK}/compose.yml")
BACKUP_DIR=""
FRESH=0

while [ $# -gt 0 ]; do
  case "$1" in
    --backup) BACKUP_DIR="$2"; shift 2 ;;
    --fresh) FRESH=1; shift ;;
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
done

fail() { echo "restore: $1" >&2; exit 1; }
[ -n "${BACKUP_DIR}" ] && [ -d "${BACKUP_DIR}" ] || fail "--backup <dir> 必填且存在"
[ -f "${BACKUP_DIR}/manifest.json" ] || fail "manifest.json 缺失（无 manifest = 无有效备份）"
[ -f "${BACKUP_DIR}/postgres.dump" ] || fail "postgres.dump 缺失"
[ -d "${BACKUP_DIR}/objects" ] || fail "objects/ 缺失"

echo "==> 1/6 校验备份完整性"
( cd "${BACKUP_DIR}" && shasum -a 256 -c SHA256SUMS --quiet ) || fail "SHA-256 校验失败"
BACKUP_SCHEMA="$(python3 -c "import json;print(json.load(open('${BACKUP_DIR}/manifest.json'))['schema_version'])")"

echo "==> 2/6 目标卷状态检查"
if [ "${FRESH}" = "1" ]; then
  echo "警告: --fresh 将删除 postgres/opensearch 全部数据。输入 YES 确认:"
  read -r CONFIRM
  [ "${CONFIRM}" = "YES" ] || fail "未确认，中止"
  "${COMPOSE[@]}" down -v postgres opensearch >/dev/null 2>&1 || true
  docker volume rm -f "${COMPOSE_PROJECT_VOLUME_PREFIX:-veridex}_postgres-data" \
    "${COMPOSE_PROJECT_VOLUME_PREFIX:-veridex}_opensearch-data" >/dev/null 2>&1 || true
else
  # 非空卷拒绝恢复
  PG_ROWS="$("${COMPOSE[@]}" exec -T postgres sh -c \
    "psql -U \"\$POSTGRES_USER\" -d \"\$POSTGRES_DB\" -tAc 'SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=current_schema'" 2>/dev/null | tr -d '[:space:]')" || PG_ROWS=""
  [ -z "${PG_ROWS}" ] || [ "${PG_ROWS}" = "0" ] || fail "目标数据库非空（${PG_ROWS} 张表）；确要覆盖请用 --fresh"
fi

echo "==> 3/6 起基础设施并恢复 PostgreSQL"
"${COMPOSE[@]}" up -d postgres minio rabbitmq opensearch
"${COMPOSE[@]}" exec -T postgres sh -c 'pg_isready -U "$POSTGRES_USER" -d "$POSTGRES_DB"' >/dev/null
"${COMPOSE[@]}" exec -T postgres sh -c \
  'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname=current_database() AND pid<>pg_backend_pid()" >/dev/null; \
   psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "DROP SCHEMA public CASCADE; CREATE SCHEMA public"' >/dev/null
"${COMPOSE[@]}" exec -T postgres sh -c \
  'pg_restore -U "$POSTGRES_USER" -d "$POSTGRES_DB" --no-owner --no-privileges' < "${BACKUP_DIR}/postgres.dump" \
  || fail "pg_restore 失败"

echo "==> 4/6 校验迁移版本兼容"
RESTORED_SCHEMA="$("${COMPOSE[@]}" exec -T postgres sh -c \
  "psql -U \"\$POSTGRES_USER\" -d \"\$POSTGRES_DB\" -tAc 'SELECT COALESCE(MAX(version),0) FROM flyway_schema_history WHERE success'" | tr -d '[:space:]')"
[ "${RESTORED_SCHEMA}" -le "${BACKUP_SCHEMA}" ] \
  || fail "恢复出的 schema(${RESTORED_SCHEMA}) 高于备份(${BACKUP_SCHEMA})，数据异常"

echo "==> 5/6 MinIO 对象回灌"
MINIO_CID="$("${COMPOSE[@]}" ps -q minio)"
find "${BACKUP_DIR}/objects" -type f | while IFS= read -r local; do
  rel="${local#${BACKUP_DIR}/objects/}"
  docker exec "${MINIO_CID}" mkdir -p "/data/veridex-documents/$(dirname "${rel}")" 
  docker cp "${local}" "${MINIO_CID}:/data/veridex-documents/${rel}" || fail "对象回灌失败: ${rel}"
done
echo "    回灌 $(find "${BACKUP_DIR}/objects" -type f | wc -l | tr -d ' ') 个对象"

echo "==> 6/6 OpenSearch 源重建（一次性 reindex 容器）"
START_NS=$(date +%s)
# compose run 用镜像默认 entrypoint（java -jar），仅注入 profile；跑完 --rm 即退
"${COMPOSE[@]}" run --rm --no-deps \
  -e SPRING_PROFILES_ACTIVE=reindex \
  -e VERIDEX_MANAGEMENT_ADDRESS=127.0.0.1 \
  backend \
  || fail "reindex 容器失败（可重跑，publish 幂等）"
END_NS=$(date +%s)
echo "restore: 完成（reindex 耗时 $((END_NS - START_NS))s，RTO 主体）"
```

执行时验证点：compose 卷名前缀（`docker volume ls | grep postgres` 实际名称，默认 `veridex_postgres-data`，脚本里按实际调整 project 前缀逻辑，或直接 `docker compose down -v` 后 `docker volume rm` 用 `docker compose config | grep volume_name` 确认）；`docker cp` 进运行中容器的写法；`compose run` 传 entrypoint 覆盖的语法（`docker compose run --rm backend` 会用镜像默认 entrypoint，无需覆盖 entrypoint——删掉 `--entrypoint java` 及其后两行参数，直接环境变量即可）。**以实测为准修正，断言语义（校验/保护/重建）不变。**

- [ ] **Step 6: 契约测试全绿**

Run: `./mvnw -f backend/pom.xml test -Dtest='BackupScriptStaticContractTest,ReindexRunnerTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS（backup + restore 断言全绿；verify-recovery 断言仍红，Task 5 补）。

- [ ] **Step 7: 提交**

```bash
git add deploy/backup/restore.sh backend/src/main/java/io/veridex/indexing/application/ReindexRunner.java \
  backend/src/test/java/io/veridex/indexing/ReindexRunnerTest.java deploy/compose/compose.yml
git commit -m "$(cat <<'EOF'
feat: add restore script with source-rebuild reindex runner

Co-Authored-By: CodeTui <noreply@codetui.dev>
EOF
)"
git diff --check
```

---

### Task 5: verify-recovery.sh 一键演练

**Files:**
- Create: `deploy/backup/verify-recovery.sh`
- Modify: `docs/capacity-report-2026-08.md`（Task 8 创建；本任务产出演练记录数据待填）

**Interfaces:**
- Consumes: `backup.sh`、`restore.sh`、`deploy/compose/smoke.sh`（现有 web 冒烟）、QaTestFixture 同义流程（登录/建库/发布，此处用 HTTP 直调）。
- Produces: 一键演练命令与 RTO 输出；演练记录（含 RTO 实测）供容量报告 §5 引用。

- [ ] **Step 1: 实现 verify-recovery.sh**

Create `deploy/backup/verify-recovery.sh`：

```bash
#!/usr/bin/env bash
set -euo pipefail

# 恢复演练（spec §4.3，5-e 退出门禁显式步骤，不进 verify.sh）：
# 起栈 → 种子数据（建库/传文档/发布/问答）→ 备份 → --fresh 销毁 → 恢复 → smoke + 抽查 → RTO 报告。

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
STACK="${ROOT_DIR}/deploy/compose"
COMPOSE=(docker compose -f "${STACK}/compose.yml")
WEB="${VERIDEX_WEB_URL:-http://127.0.0.1:8090}"
JAR=$(mktemp)
DRILL_LOG="$(mktemp)"
trap 'rm -f "${JAR}" "${DRILL_LOG}"' EXIT
fail() { echo "verify-recovery: $1" >&2; exit 1; }

echo "==> [drill-1] 起完整应用栈"
"${COMPOSE[@]}" up -d --build backend web
for i in $(seq 1 60); do curl -fsS "${WEB}/healthz" >/dev/null 2>&1 && break; sleep 5; done
curl -fsS "${WEB}/healthz" >/dev/null || fail "web 未就绪"

echo "==> [drill-2] 灌种子数据"
curl -fsS -c "${JAR}" "${WEB}/api/auth/csrf" >/dev/null
curl -fsS -b "${JAR}" -c "${JAR}" -X POST "${WEB}/api/auth/login?username=admin&password=veridex" >/dev/null
XSRF=$(awk '$6 == "XSRF-TOKEN" {print $NF}' "${JAR}" | tail -n1)
# 建库
KB_ID=$(curl -fsS -b "${JAR}" -H "X-XSRF-TOKEN: ${XSRF}" -H "Content-Type: application/json" \
  -X POST "${WEB}/api/knowledge-bases" -d '{"name":"恢复演练库","description":"drill"}' \
  | python3 -c "import json,sys;print(json.load(sys.stdin)['id'])")
# 上传文档（markdown，走现有解析/摄取）
printf '员工请假需提前两个工作日提交书面申请，经审批后生效。\n' > /tmp/drill-doc.md
DOC_ID=$(curl -fsS -b "${JAR}" -H "X-XSRF-TOKEN: ${XSRF}" -F "file=@/tmp/drill-doc.md;type=text/markdown" \
  "${WEB}/api/knowledge-bases/${KB_ID}/documents" \
  | python3 -c "import json,sys;print(json.load(sys.stdin)['id'])")
# 等待摄取 READY（轮询文档状态）
for i in $(seq 1 60); do
  STATUS=$(curl -fsS -b "${JAR}" "${WEB}/api/knowledge-bases/${KB_ID}/documents" | \
    python3 -c "import json,sys;docs=json.load(sys.stdin);print(next((d['status'] for d in docs if d['id']=='${DOC_ID}'),''))")
  [ "${STATUS}" = "READY" ] && break
  sleep 2
done
[ "${STATUS}" = "READY" ] || fail "文档未 READY（status=${STATUS}）"
# 发布
curl -fsS -b "${JAR}" -H "X-XSRF-TOKEN: ${XSRF}" -X POST \
  "${WEB}/api/knowledge-bases/${KB_ID}/releases/publish" >/dev/null || fail "发布失败"
echo "    种子完成: kb=${KB_ID}"

echo "==> [drill-3] 备份"
"${ROOT_DIR}/deploy/backup/backup.sh" --out "${ROOT_DIR}/backups" || fail "备份失败"
BACKUP_DIR="$(ls -1dt "${ROOT_DIR}/backups"/[0-9]* | head -1)"

echo "==> [drill-4] 销毁数据卷并恢复"
DRILL_START=$(date +%s)
echo YES | "${ROOT_DIR}/deploy/backup/restore.sh" --backup "${BACKUP_DIR}" --fresh 2>&1 | tee "${DRILL_LOG}" \
  || fail "恢复失败"
DRILL_END=$(date +%s)
grep -q "restore: 完成" "${DRILL_LOG}" || fail "恢复日志缺少完成标记"

echo "==> [drill-5] 恢复后验收"
"${COMPOSE[@]}" up -d backend web
for i in $(seq 1 60); do curl -fsS "${WEB}/healthz" >/dev/null 2>&1 && break; sleep 5; done
VERIDEX_WEB_URL="${WEB}" "${STACK}/smoke.sh" || fail "恢复后 smoke 失败"
# 抽查：恢复的库可检索问答（引用链路自证）
SSE=$(curl -NsS -b "${JAR}" -H "X-XSRF-TOKEN: ${XSRF}" -H "Content-Type: application/json" \
  -d "{\"question\":\"请假提前几天\",\"knowledgeBaseIds\":[\"${KB_ID}\"]}" "${WEB}/api/qa/ask") \
  || fail "恢复后问答失败"
echo "${SSE}" | grep -q "event:answer" || fail "恢复后问答未返回答案（SSE 无 answer 事件）"
echo "${SSE}" | grep -q "event:answer.completed" || echo "warn: 未见 answer.completed（可能是拒答，人工核对）"

RTO=$((DRILL_END - DRILL_START))
echo "verify-recovery: 全流程通过。RTO=${RTO}s（备份目录 ${BACKUP_DIR}）"
echo "    请将本次演练时间与 RTO 记录到 docs/capacity-report-2026-08.md §5"
```

执行时验证点：种子数据走的具体 API 路径/响应字段（读 `backend/.../knowledge/api` 的 Controller 确认 `/api/knowledge-bases` 与文档上传/状态字段名）；上传端点 multipart 字段名；`restore.sh` 内 reindex 耗时已打印，演练 RTO 取销毁→smoke 通过的总时长（上面 DRILL_START/END 位置按此语义放对）。**命令语义以实测为准微调，断言链（备份→销毁→恢复→smoke→问答）不变。**`chmod +x`。

- [ ] **Step 2: 实跑演练**

Run: `./deploy/backup/verify-recovery.sh`
Expected: 全流程通过并输出 RTO。任何一步失败修到通过——这是 spec 验收标准 3 的直接证据。

- [ ] **Step 3: 契约测试全绿 + 提交**

Run: `./mvnw -f backend/pom.xml test -Dtest=BackupScriptStaticContractTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS（三个脚本断言全绿）。

```bash
git add deploy/backup/verify-recovery.sh
git commit -m "$(cat <<'EOF'
feat: add one-shot recovery drill script

Co-Authored-By: CodeTui <noreply@codetui.dev>
EOF
)"
git diff --check
```

---

### Task 6: 容量生成器（tools/capacity）

**Files:**
- Create: `tools/capacity/pom.xml`
- Create: `tools/capacity/src/main/java/io/veridex/capacity/DeterministicEmbedding.java`
- Create: `tools/capacity/src/main/java/io/veridex/capacity/SeedGenerator.java`
- Create: `tools/capacity/README.md`

**Interfaces:**
- Consumes: 直连基础设施（`VERIDEX_DB_URL` 族环境变量、MinIO 9000、OpenSearch 9200，默认 localhost compose 栈）；表结构 `knowledge_base`/`document`/`document_version`（V3 迁移）、`index_release`（V4/V5）；OpenSearch 索引 mapping 与 `OpenSearchIndexGateway` 一致（text/embedding/document_version_id/kb_id 等字段）。
- Produces:
  - `DeterministicEmbedding.embed(String text): float[128]`——复刻 `DeterministicEmbeddingModel` 算法（一致性由抽查检索命中验证）。
  - `SeedGenerator`：`--kbs N --docs-per-kb M --chunks-per-doc K`，写 PG 元数据 + MinIO 对象（原文/parsed/chunks.json）+ 对每个 KB 创建 release 并 bulk 索引（复刻 publish 语义：建索引、写 chunks、alias 切换）。输出统计（chunk 总数、耗时）。
  - `ReindexBenchmark`（Task 6 内一并交付）：清空该 KB 索引后重跑 bulk，计时输出。

- [ ] **Step 1: 建 maven 模块骨架**

Create `tools/capacity/pom.xml`（独立，parent 不指向根 pom）：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <groupId>io.veridex</groupId>
    <artifactId>veridex-capacity-tools</artifactId>
    <version>0.1.0</version>
    <packaging>jar</packaging>

    <properties>
        <maven.compiler.release>21</maven.compiler.release>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    </properties>

    <dependencies>
        <dependency>
            <groupId>org.postgresql</groupId>
            <artifactId>postgresql</artifactId>
            <version>42.7.4</version>
        </dependency>
        <dependency>
            <groupId>org.opensearch.client</groupId>
            <artifactId>opensearch-java</artifactId>
            <version>3.2.0</version>
        </dependency>
        <dependency>
            <groupId>org.opensearch.client</groupId>
            <artifactId>opensearch-rest-client</artifactId>
            <version>3.2.0</version>
        </dependency>
        <dependency>
            <groupId>io.minio</groupId>
            <artifactId>minio</artifactId>
            <version>8.5.17</version>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-shade-plugin</artifactId>
                <version>3.6.0</version>
                <executions>
                    <execution>
                        <phase>package</phase>
                        <goals><goal>shade</goal></goals>
                        <configuration>
                            <transformers>
                                <transformer implementation="org.apache.maven.plugins.shade.resource.ManifestResourceTransformer">
                                    <mainClass>io.veridex.capacity.SeedGenerator</mainClass>
                                </transformer>
                            </transformers>
                        </configuration>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</project>
```

（依赖版本与 backend 锁定的版本对齐——执行时从 `backend/pom.xml`/BOM 解析树确认 `opensearch-java`/`minio`/`postgresql` 实际版本，写入此处。）

- [ ] **Step 2: DeterministicEmbedding 复刻**

从 `backend/src/main/java/io/veridex/shared/infrastructure/embedding/DeterministicEmbeddingModel.java` 读出算法（哈希分桶 + 归一化），在 `tools/capacity/src/main/java/io/veridex/capacity/DeterministicEmbedding.java` 以无依赖纯 Java 复刻（public static float[128] embed(String text)）。文件头注释注明「必须与 backend DeterministicEmbeddingModel 保持一致；漂移会导致检索 miss，抽查验证兜底」。

- [ ] **Step 3: 实现 SeedGenerator**

`SeedGenerator.main` 流程（单文件 ~300 行，执行时按 V3 迁移的真实列名写 INSERT）：

```
1. 解析参数: --kbs 20 --docs-per-kb 50 --chunks-per-doc 1000（默认即 1M chunk）
2. JDBC 连 PG（env VERIDEX_DB_URL/USERNAME/PASSWORD，默认 compose 值）
   - INSERT knowledge_base（uuid 生成、slug/name 按「容量库-{n}」）
   - 每 doc: INSERT document + document_version（status=READY, chunk_count）
3. MinIO SDK putObject:
   - <kbId>/<docId>/v1/doc-{i}.md（原文，拼接 K 个 chunk 段落）
   - .../doc-{i}.parsed.json（纯文本）
   - .../doc-{i}.chunks.json（[{index,text,title,structurePath}]）
4. OpenSearch bulk（每批 1000）:
   - 索引 veridex-<kbId>-<versionNo>（mapping 同 OpenSearchIndexGateway: shards=1, replicas=0, knn=true,
     text/embedding(128)/document_version_id/chunk_index/... 字段）
   - 文本用 8 类中文制度模板 × 参数化（请假/报销/差旅/保密/考勤/采购/安全/培训），每 chunk 200-500 字
   - embedding = DeterministicEmbedding.embed(chunk text)，批量并行
5. PG INSERT index_release（is_active=true）+ alias 切换 veridex-<kbId>-active → 新索引
6. 输出: 总 chunk 数、各阶段耗时、PG 行数、OpenSearch doc count（GET _count 对账）
```

关键实现细节：bulk 请求体手拼 NDJSON（opensearch-java client 的 BulkRequest 也可）；并行度固定 `Runtime.getRuntime().availableProcessors()`；每 KB 一个索引（与生产一致）。

- [ ] **Step 4: 自校验（1K chunk 小规模）**

Run: `cd tools/capacity && ../../mvnw -q package`
Run: `java -jar target/veridex-capacity-tools-0.1.0.jar --kbs 2 --docs-per-kb 5 --chunks-per-doc 100`
Expected: 输出 1000 chunk、对账 `OpenSearch doc count == 1000`。然后起 backend 走 HTTP 问答（admin 登录 → 对「容量库-0」提问模板内问题），验证检索命中真实 chunk（引用含 VALID）。命中失败 = embedding 复刻漂移或 mapping 不一致，修到命中。

- [ ] **Step 5: ReindexBenchmark**

同包 `ReindexBenchmark.java`：`--kb <kbId>`，删除该 KB 索引 → 重新 bulk 全量（复用生成器写入逻辑，从 MinIO 读 chunks.json）→ 计时输出 `reindex_seconds`。shade mainClass 切换为参数化（`-Dexec.mainClass` 或 shade 不绑 main 由 `java -cp` 运行——执行时选简单方式并在 README 写明命令）。

- [ ] **Step 6: README 执行手册**

Create `tools/capacity/README.md`：前置（compose 栈、容器内存建议）、构建命令、1K 自校验、1M 生成命令（预期耗时 <1h）、ReindexBenchmark、k6（Task 7 补）、结果落盘位置。明确「本模块不进 verify.sh」。

- [ ] **Step 7: 提交**

```bash
git add tools/capacity
git commit -m "$(cat <<'EOF'
feat: add capacity seed generator and reindex benchmark tooling

Co-Authored-By: CodeTui <noreply@codetui.dev>
EOF
)"
git diff --check
```

---

### Task 7: k6 负载脚本与评测干扰量化

**Files:**
- Create: `tools/capacity/k6/qa-load.js`
- Create: `tools/capacity/k6/run-matrix.sh`
- Modify: `tools/capacity/README.md`

**Interfaces:**
- Consumes: Task 6 的种子数据（KB 与 chunk 在位）；backend/web compose 栈；Prometheus（`veridex.generation.first_token` 指标）；评测 API（`POST /api/evaluation/...` 现有端点，读 `evaluation/api` 确认）。
- Produces: 三档负载结果（P50/P95/P99 全链路、吞吐、错误率）+ first_token P99（PromQL）+ 评测干扰对比数据，供 Task 8 报告引用。

- [ ] **Step 1: qa-load.js**

```javascript
import http from 'k6/http';
import { check, sleep } from 'k6';

// 在线问答负载（spec §4.5）：普通 POST 读完整 SSE 流（连接关闭 = answer.completed/failed），
// 全链路时延即 http_resp_duration；首 token 时延从 Prometheus veridex.generation.first_token 查询。
// 环境变量: BASE_URL(默认 http://127.0.0.1:8090), KB_IDS(逗号分隔)

const BASE_URL = __ENV.BASE_URL || 'http://127.0.0.1:8090';
const KB_IDS = (__ENV.KB_IDS || '').split(',').filter(Boolean);
const QUESTIONS = [
  '请假需要提前几个工作日申请',
  '差旅报销的流程是什么',
  '保密制度对文档分类的要求',
  '考勤异常如何处理',
  '新员工培训时长规定',
];

export const options = {
  scenarios: {
    qa: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '30s', target: Number(__ENV.VUS || 5) },
        { duration: __ENV.DURATION || '5m', target: Number(__ENV.VUS || 5) },
        { duration: '30s', target: 0 },
      ],
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.02'],
  },
};

export function setup() {
  const jar = http.cookieJar();
  http.get(`${BASE_URL}/api/auth/csrf`);
  const login = http.post(`${BASE_URL}/api/auth/login?username=admin&password=veridex`);
  check(login, { 'login ok': (r) => r.status < 300 });
  const xsrf = jar.cookiesForURL(BASE_URL)['XSRF-TOKEN'];
  if (!xsrf) throw new Error('XSRF-TOKEN missing');
  return { xsrf: xsrf[0] || xsrf };
}

export default function (data) {
  const question = QUESTIONS[Math.floor(Math.random() * QUESTIONS.length)];
  // k6 http.post 会读完整响应体——SSE 流在服务端 complete 后关闭，duration 即全链路时延
  const resp = http.post(
    `${BASE_URL}/api/qa/ask`,
    JSON.stringify({ question, knowledgeBaseIds: KB_IDS, conversationId: null }),
    {
      headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': data.xsrf },
      timeout: '120s',
    }
  );
  check(resp, {
    'sse ok': (r) => r.status === 200,
    'answer delivered': (r) => r.body && (r.body.includes('answer.completed')
        || r.body.includes('answer.refused')),
  });
  sleep(1);
}
```

- [ ] **Step 2: run-matrix.sh**

```bash
#!/usr/bin/env bash
set -euo pipefail
# 梯度矩阵（spec §4.5）：deterministic 与 ollama 双曲线 × 5/20/50 VU；可选 --with-eval 干扰组。
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
OUT_DIR="${ROOT_DIR}/results/$(date +%Y%m%dT%H%M%S)"
mkdir -p "${OUT_DIR}"
KB_IDS="${KB_IDS:?需要 KB_IDS（逗号分隔，来自 SeedGenerator 输出）}"
BASE_URL="${BASE_URL:-http://127.0.0.1:8090}"
PROVIDER="${VERIDEX_CHAT_PROVIDER:-deterministic}"

for vus in 5 20 50; do
  echo "==> ${PROVIDER} ${vus} VU"
  k6 run "${ROOT_DIR}/k6/qa-load.js" \
    -e BASE_URL="${BASE_URL}" -e KB_IDS="${KB_IDS}" -e VUS="${vus}" -e DURATION=5m \
    --summary-export "${OUT_DIR}/${PROVIDER}-vus${vus}.json"
  # first_token P99（Prometheus）
  curl -fsSG "http://localhost:9090/api/v1/query" \
    --data-urlencode "query=histogram_quantile(0.99, sum(rate(veridex_generation_first_token_bucket[5m])) by (le))" \
    -o "${OUT_DIR}/${PROVIDER}-vus${vus}-first-token.json"
done

if [ "${WITH_EVAL:-0}" = "1" ]; then
  echo "==> 评测干扰组: 50 VU + 评测运行"
  # 后台触发一个 200 case 评测（评测 dataset/运行 API 以 evaluation/api 实际端点为准）
  ( curl -fsS -b <(echo) -X POST "${BASE_URL}/api/evaluation/runs" \
      -H "Content-Type: application/json" \
      -d '{"datasetId":"'"${EVAL_DATASET_ID:?}"'","concurrency":1}' >/dev/null ) &
  EVAL_PID=$!
  k6 run "${ROOT_DIR}/k6/qa-load.js" \
    -e BASE_URL="${BASE_URL}" -e KB_IDS="${KB_IDS}" -e VUS=50 -e DURATION=5m \
    --summary-export "${OUT_DIR}/${PROVIDER}-vus50-with-eval.json"
  wait "${EVAL_PID}" || true
fi

echo "run-matrix: 结果在 ${OUT_DIR}（供容量报告引用）"
```

（评测触发端点执行时读 `evaluation/api` 的 Controller 确认真实路径与请求体；若评测需要预置 dataset，README 写明前置步骤。干扰组对比的是同 VU 档有/无评测的 P99 漂移。）

- [ ] **Step 3: smoke 验证（1 VU × 30s）**

前置：compose 栈运行、SeedGenerator 1K 小规模数据在位、k6 已安装（`brew install k6`）。
Run: `k6 run tools/capacity/k6/qa-load.js -e VUS=1 -e DURATION=30s -e KB_IDS=<自校验 KB>`
Expected: http_req_failed=0、`answer delivered` 全过。SSE 读取或 CSRF 处理失败则修脚本。

- [ ] **Step 4: 提交**

```bash
git add tools/capacity/k6 tools/capacity/README.md
git commit -m "$(cat <<'EOF'
feat: add k6 qa load matrix with evaluation interference scenario

Co-Authored-By: CodeTui <noreply@codetui.dev>
EOF
)"
git diff --check
```

---

### Task 8: 容量实测、报告与门禁收口

**Files:**
- Create: `docs/capacity-report-2026-08.md`
- Modify: `README.md`、`docs/architecture.md`
- Modify: `deploy/offline/veridex-offline/INSTALL.txt`（备份说明附录）

**Interfaces:**
- Consumes: Task 1-7 全部产出。
- Produces: Phase 5 退出门禁证据（容量报告 + 演练记录）与文档收口。

- [ ] **Step 1: 报告骨架**

Create `docs/capacity-report-2026-08.md`：

```markdown
# Veridex 容量与恢复报告（2026-08）

> 环境：macOS Docker Desktop 单节点（记录 CPU/内存/磁盘）、compose 栈、镜像版本 <commit>。
> 规模：1M chunk 实测（20 KB × 50 doc/KB × 1000 chunk/doc），5M 为外推（§4 标注非实测）。

## 1. 数据生成（1M）
<!-- SeedGenerator 输出：总耗时、PG/MinIO/OpenSearch 各阶段耗时、磁盘占用 -->

## 2. 全量重建 RTO（源重建路径）
<!-- ReindexBenchmark 与恢复演练 reindex 计时，两处互证 -->

## 3. 在线负载（三档 × 双模型）
<!-- deterministic 与 ollama(qwen3.5:9b-mlx) 的 5/20/50 VU:
     P50/P95/P99 全链路、吞吐、错误率、first_token P99 -->

## 4. 5M 外推（非实测）
<!-- 磁盘线性、HNSW 检索曲线引用+实测锚点、bulk 吞吐线性；
     结论 + 「生产前建议目标硬件 5M 校准」 -->

## 5. 恢复演练记录
<!-- verify-recovery.sh 实跑日期、RTO、抽查结果 -->

## 6. 评测干扰量化
<!-- 50 VU ± 评测运行 的 P99 漂移；部署建议（评测并发上限） -->

## 7. 结论与生产部署建议
<!-- 资源配额、副本数、瓶颈项、Session/限流多副本语义 -->
```

- [ ] **Step 2: 执行 1M 实测（显式门禁步骤，数小时）**

```bash
# 前置: compose 栈 up -d；Docker Desktop 内存调至 ≥8GB（OpenSearch+PG+backend）
cd tools/capacity && ../../mvnw -q package
java -jar target/veridex-capacity-tools-0.1.0.jar --kbs 20 --docs-per-kb 50 --chunks-per-doc 1000
# 记录输出到报告 §1；重跑一次 ReindexBenchmark 计时到 §2
```

- [ ] **Step 3: 执行负载矩阵（双曲线）**

```bash
# deterministic（默认 provider）
KB_IDS=<Step2 输出> ./tools/capacity/k6/run-matrix.sh
# ollama 曲线（本机 qwen3.5:9b-mlx）
VERIDEX_CHAT_PROVIDER=ollama VERIDEX_OLLAMA_CHAT_MODEL=qwen3.5:9b-mlx \
VERIDEX_OLLAMA_BASE_URL=http://host.docker.internal:11434 \
VERIDEX_OUTBOUND_ALLOWED_HOSTS=host.docker.internal VERIDEX_OUTBOUND_ALLOWED_PORTS=11434 \
VERIDEX_OUTBOUND_ALLOW_INSECURE_HTTP=true \
KB_IDS=<同上> WITH_EVAL=1 EVAL_DATASET_ID=<预置> ./tools/capacity/k6/run-matrix.sh
```

（ollama 栈需重启 backend 使 provider 生效——compose 环境变量加到 backend.environment 后 `docker compose up -d backend`。）结果填报告 §3/§6。

- [ ] **Step 4: 恢复演练实跑并记录**

Run: `./deploy/backup/verify-recovery.sh`
把演练日期、RTO、抽查结果填报告 §5（若 Task 5 已跑过且环境未变，可复用该次记录并注明）。

- [ ] **Step 5: 文档收口**

- `README.md`：新增「备份与恢复」小节（backup.sh/restore.sh/verify-recovery.sh 入口、k8s 侧 CronJob 自行调度说明）、「多副本语义」补充（Session 已 PG 外部化；限流为每副本语义，总限额=限额×副本数）、容量报告索引链接。
- `docs/architecture.md`：§3 数据库迁移补 V15；§7 部署拓扑补备份恢复入口与 Session 语义；报告链接。
- `deploy/offline/veridex-offline/INSTALL.txt`：追加「备份/恢复（k8s）」附录——pg_dump/对象导出由安装者调度，恢复步骤引用仓库脚本语义。

- [ ] **Step 6: 全量门禁**

Run: `./scripts/verify.sh`
Expected: 全绿（含 BackupScriptStaticContractTest、Session 测试、无 redis 的 compose 契约；tools/capacity 不被触发）。回归失败修复到绿。

- [ ] **Step 7: 提交**

```bash
git add docs/capacity-report-2026-08.md README.md docs/architecture.md deploy/offline
git commit -m "$(cat <<'EOF'
docs: record capacity and recovery evidence for phase5e gate

Co-Authored-By: CodeTui <noreply@codetui.dev>
EOF
)"
git diff --check
```

- [ ] **Step 8: 阶段收口**

对照 spec §8 验收标准 1-11 逐条给出结论（写入本计划执行记录）；更新记忆 `project_phase5_status.md`——Phase 5 全部完成，进入企业试点准备就绪状态。
