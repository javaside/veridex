# Veridex Developer Onboarding README Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在项目根目录交付一份可执行、可验证的中文 `README.md`，让新开发者能够理解当前阶段并在本地启动、使用和验证 Veridex。

**Architecture:** 以根目录 README 作为开发者单一入口，运行参数和命令直接引用当前 Compose、Spring Boot、Vite、数据库迁移与验证脚本。README 只概述架构，详细知识入库设计链接到现有专题文档，避免重复维护。

**Tech Stack:** Markdown、Docker Compose、Java 21、Maven Wrapper、Spring Boot 4、Node.js 22.13+、npm、Vite、React、PostgreSQL、RabbitMQ、Redis、MinIO、OpenSearch。

## Global Constraints

- README 面向首次克隆项目的开发者，覆盖完整本地上手流程，不承担生产部署手册职责。
- 当前 Compose 只启动 PostgreSQL、RabbitMQ、Redis、MinIO 和 OpenSearch；后端与前端必须单独启动。
- 所有命令必须可从仓库根目录执行。
- Java 最低版本为 21；Node.js 最低版本为 22.13.0。
- 默认凭据、关闭安全插件的 OpenSearch 和种子用户只允许用于本地开发。
- 当前确定性 Embedding 不具备语义相似度；Phase 3 RAG 查询尚未交付。
- README 不复制完整知识入库架构，必须链接 `docs/knowledge-ingestion-pipeline.md`。
- 不增加新的运行脚本、依赖或部署配置；只新增 README 并验证现有命令。

---

## 文件结构

- Create: `README.md` — 项目介绍、启动、使用、配置、验证、清理与故障排查的单一入口。
- Reference: `deploy/compose/compose.yml` — 基础设施服务、镜像、端口、健康检查和默认凭据。
- Reference: `backend/src/main/resources/application.yml` — 后端环境变量和默认连接信息。
- Reference: `backend/src/main/resources/db/migration/V2__identity.sql` — 默认开发用户。
- Reference: `web/package.json` — Node.js 版本和前端脚本。
- Reference: `web/vite.config.ts` — Vite 代理目标。
- Reference: `scripts/verify.sh` — 一键验证入口。
- Reference: `docs/knowledge-ingestion-pipeline.md` — Phase 2 入库架构细节。
- Reference: `docs/superpowers/plans/2026-08-09-enterprise-rag-delivery-roadmap.md` — 阶段路线图。

---

### Task 1: 编写并验证开发者 README

**Files:**
- Create: `README.md`

**Interfaces:**
- Consumes: `docker compose -f deploy/compose/compose.yml ...`、`./mvnw -pl backend spring-boot:run`、`npm --prefix web ...`、`./scripts/verify.sh`。
- Produces: 根目录开发者入口 `README.md`；后续开发者按其中命令启动和验证项目。

- [ ] **Step 1: 建立文档事实基线**

逐项核对以下事实，不修改源文件：

```text
Java: 21
Node.js: >=22.13.0
Backend: http://localhost:8080
Web: http://localhost:5173
PostgreSQL: localhost:5432
RabbitMQ AMQP: localhost:5672
RabbitMQ UI: http://localhost:15672
MinIO API: http://localhost:9000
MinIO Console: http://localhost:9001
OpenSearch: http://localhost:9200
Actuator health: http://localhost:8080/actuator/health
```

默认用户应记录为：

```text
admin / admin       PLATFORM_ADMIN
kadmin / admin      KNOWLEDGE_ADMIN
employee / admin    EMPLOYEE
```

说明：密码由 V2 中相同 BCrypt hash 对应的开发密码 `admin` 提供，必须通过现有登录集成测试或实际登录验证后才能写入 README。

- [ ] **Step 2: 创建完整 README**

创建 `README.md`，使用以下一级/二级结构，正文必须给出具体命令和解释：

```markdown
# Veridex

企业私有化 RAG 平台。当前完成 Phase 2：知识入库垂直切片。

## 当前状态
## 已实现能力
## 当前限制
## 技术栈
## 项目结构
## 环境要求
## 快速启动
### 1. 启动基础设施
### 2. 启动后端
### 3. 安装并启动前端
### 4. 登录并验证知识入库
## 服务地址与默认账号
## 配置
### Compose 环境变量
### 后端环境变量
## 测试与构建
## 停止与清理
## 常见问题
## 进一步阅读
```

快速启动命令必须使用：

```bash
# Terminal 1
docker compose -f deploy/compose/compose.yml up -d
docker compose -f deploy/compose/compose.yml ps

# Terminal 2
./mvnw -pl backend spring-boot:run

# Terminal 3（首次运行先安装依赖）
npm --prefix web ci
npm --prefix web run dev
```

测试命令必须包括：

```bash
./scripts/verify.sh
./mvnw clean verify
npm --prefix web test
npm --prefix web run lint
npm --prefix web run build
```

停止与清理必须区分：

```bash
# 停止并删除容器，保留数据卷
docker compose -f deploy/compose/compose.yml down

# 同时删除 PostgreSQL、MinIO、OpenSearch 本地数据卷
docker compose -f deploy/compose/compose.yml down -v
```

- [ ] **Step 3: 校验 Compose 配置和工具版本**

运行：

```bash
docker compose -f deploy/compose/compose.yml config --quiet
java -version
node --version
npm --version
```

预期：Compose 配置返回 0；Java 主版本至少 21；Node 主版本满足 `>=22.13.0`；npm 可用。

- [ ] **Step 4: 验证基础设施启动命令**

运行：

```bash
docker compose -f deploy/compose/compose.yml up -d
docker compose -f deploy/compose/compose.yml ps
```

轮询直至 `postgres`、`rabbitmq`、`redis`、`minio`、`opensearch` 全部为 healthy。若已有容器，允许复用，但必须确认端口与 README 一致。

- [ ] **Step 5: 验证后端可启动和健康检查**

在后台启动后端，将日志保存到临时文件：

```bash
./mvnw -pl backend spring-boot:run
```

等待启动后运行：

```bash
curl --fail http://localhost:8080/actuator/health
```

预期：HTTP 200，响应包含 `"status":"UP"`。验证后停止该 Maven 进程，不修改项目文件。

- [ ] **Step 6: 验证默认账号登录**

使用 cookie 文件验证 README 推荐账号：

```bash
curl --fail \
  -c /tmp/veridex-cookie.txt \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  --data 'username=admin&password=admin' \
  http://localhost:8080/api/auth/login

curl --fail \
  -b /tmp/veridex-cookie.txt \
  http://localhost:8080/api/auth/me
```

预期：登录返回 HTTP 200；`/api/auth/me` 返回 `username=admin` 和 `role=PLATFORM_ADMIN`。验证后删除 `/tmp/veridex-cookie.txt`。

- [ ] **Step 7: 验证前端命令**

运行：

```bash
npm --prefix web ci
npm --prefix web test
npm --prefix web run lint
npm --prefix web run build
```

预期：安装、测试、Lint 和构建均返回 0。若 ESLint 仅输出当前已知 warning 且返回 0，README 不应宣称“零 warning”，只写“Lint 通过”。

- [ ] **Step 8: 运行项目完整验证门禁**

运行：

```bash
./scripts/verify.sh
```

预期：输出 `verify.sh: all checks passed`，退出码为 0。

- [ ] **Step 9: 自审 README**

执行：

```bash
git diff --check
grep -nE 'TODO|TBD|待定|localhost:[0-9]+' README.md
```

逐项确认：

```text
- 无 TODO/TBD/待定占位内容
- 所有端口都能在 Compose、application.yml 或 Vite 默认配置中找到依据
- 明确 Compose 不启动应用本身
- 明确默认凭据和 OpenSearch 配置仅供本地开发
- 明确确定性 Embedding 无语义能力
- 明确 Phase 3 尚未实现
- 所有链接指向仓库内现有文件
- 所有命令都以仓库根目录为工作目录
```

- [ ] **Step 10: 提交 README**

```bash
git add README.md
git commit -m "docs: add developer onboarding readme"
```
