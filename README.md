# Veridex

Veridex 是面向企业私有化部署的 RAG（Retrieval-Augmented Generation，检索增强生成）平台。目前项目已经完成 **Phase 3：带权限的 RAG 查询**，支持创建知识库、上传文档、异步解析和分块、发布到 OpenSearch，以及授权员工流式问答并校验引用。

> 当前仓库以本地开发和集成验证为目标，尚未提供应用容器镜像或生产部署编排。

## 当前状态

- **Phase 1：可执行基础** — 已完成
- **Phase 2：知识入库垂直切片** — 已完成
- **Phase 3：带权限的 RAG 查询** — 已完成
- **Phase 4：质量评测与运营闭环** — 尚未实现
- **Phase 5：企业试点准备** — 尚未实现

详细阶段目标见[交付路线图](docs/superpowers/plans/2026-08-09-enterprise-rag-delivery-roadmap.md)。

## 已实现能力

- 本地用户、Session 登录和 `PLATFORM_ADMIN` / `KNOWLEDGE_ADMIN` / `EMPLOYEE` 角色
- 知识库创建及 VIEW / MANAGE 授权模型
- PDF、DOCX、TXT、Markdown 文档上传（单文件最大 50 MB）
- MinIO 原始文件和解析结果存储
- PostgreSQL Transactional Outbox + RabbitMQ 异步入库
- Apache Tika 文档解析及结构优先分块
- OpenSearch 全文和向量字段写入
- 不可变 IndexRelease，以及发布、设为当前、离线和删除流程
- 解析文本、Chunk 和文档版本预览
- 重复消息幂等、失败版本隔离及离线立即排除
- 授权知识范围（多知识库交集）上的混合检索（BM25 + 向量 + RRF 融合）
- 有限多轮会话、SSE 流式回答、`[n]` 引用校验与原文预览
- 依据不足时明确拒答（含 `ACCESS_RESTRICTED` 统一文案）

## 当前限制

- 当前 `DeterministicEmbeddingModel` 是 128 维确定性哈希向量，**不具备语义相似度能力**；回答由 `DeterministicChatModel` 模板化生成，两者仅用于打通和验证管道，真实模型通过 provider 接口切换。
- PDF / DOCX 的结构识别较基础；没有 Markdown 标题时主要按文本长度分块。
- 失败消息进入 DLQ；自动重试、退避以及 Outbox 定时恢复仍待增强。
- 点赞/点踩反馈仅前端占位 + 接口占位，坏例转评测集闭环属 Phase 4。
- `deploy/compose/compose.yml` 只启动开发基础设施，不启动 Spring Boot 后端或 React 前端。

## 技术栈

| 区域 | 技术 |
|---|---|
| 后端 | Java 21、Spring Boot 4、Spring Modulith、Spring Data JPA、Spring Security、Spring AI 2 |
| 前端 | React 19、TypeScript、Vite、Vitest |
| 业务数据库 | PostgreSQL 17 |
| 消息队列 | RabbitMQ 4 |
| 对象存储 | MinIO |
| 检索索引 | OpenSearch 3.2 |
| 缓存基础设施 | Redis 8（当前阶段尚未进入主要业务链路） |
| 文档解析 | Apache Tika 3 |
| 集成测试 | JUnit、Testcontainers |

## 项目结构

```text
.
├── backend/                  Spring Boot 模块化单体
├── web/                      React + Vite 管理控制台
├── deploy/compose/           本地基础设施 Compose 配置
├── docs/                     架构、路线图和实施文档
├── scripts/verify.sh         项目一键质量门禁
├── pom.xml                   Maven 父项目
└── mvnw                      Maven Wrapper
```

知识入库的组件职责和数据流详见[知识入库处理管道](docs/knowledge-ingestion-pipeline.md)。

## 环境要求

启动前请准备：

- **Java 21+**
- **Docker Desktop** 或兼容 Docker Engine
- **Docker Compose v2+**（使用 `docker compose` 子命令）
- **Node.js 22.13.0+**
- **npm**
- `curl`（用于命令行健康检查，可选）

检查本机版本：

```bash
java -version
docker version
docker compose version
node --version
npm --version
```

OpenSearch 默认分配 1 GB JVM 堆。建议 Docker 至少可使用 4 GB 内存，运行完整测试套件时建议提供更多内存。

## 快速启动

以下命令均从仓库根目录执行。完整应用由基础设施、后端和前端三部分组成，建议使用三个终端。

### 1. 启动基础设施

```bash
docker compose -f deploy/compose/compose.yml up -d
docker compose -f deploy/compose/compose.yml ps
```

等待 `postgres`、`rabbitmq`、`redis`、`minio` 和 `opensearch` 的状态全部变为 `healthy`。首次启动可能需要下载镜像，OpenSearch 通常最慢。

查看基础设施日志：

```bash
docker compose -f deploy/compose/compose.yml logs -f
```

### 2. 启动后端

在第二个终端执行：

```bash
./mvnw -pl backend spring-boot:run
```

后端默认监听 `http://localhost:8080`。Flyway 会在启动时自动创建或校验数据库结构。

检查健康状态：

```bash
curl --fail http://localhost:8080/actuator/health
```

正常响应包含：

```json
{"status":"UP"}
```

### 3. 安装并启动前端

在第三个终端执行：

```bash
npm --prefix web ci
npm --prefix web run dev
```

前端默认访问地址为 `http://localhost:5173`。Vite 会将 `/api` 和 `/actuator` 请求代理到 `http://localhost:8080`。

依赖已经按 `web/package-lock.json` 安装后，日常启动可以只执行：

```bash
npm --prefix web run dev
```

### 4. 登录并验证知识入库

1. 打开 `http://localhost:5173`。
2. 使用 `admin` / `veridex` 登录。
3. 进入“知识管理”。
4. 创建一个知识库。
5. 上传 PDF、DOCX、TXT 或 Markdown 文件。
6. 等待版本状态从 `UPLOADED`、`PROCESSING` 变为 `READY`。
7. 查看解析预览、Chunk 列表和已发布的 IndexRelease。
8. 可验证 Release 的回滚、离线和删除操作。

处理失败时版本会变为 `FAILED`，消息会进入 RabbitMQ 死信队列 `ingestion.document.dlq`。

## 服务地址与默认账号

### 本地服务

| 服务 | 地址 | 说明 |
|---|---|---|
| Web | `http://localhost:5173` | React 开发服务器 |
| Backend | `http://localhost:8080` | Spring Boot API |
| Backend Health | `http://localhost:8080/actuator/health` | 健康检查 |
| PostgreSQL | `localhost:5432` | 数据库 `veridex` |
| RabbitMQ AMQP | `localhost:5672` | 应用消息连接 |
| RabbitMQ UI | `http://localhost:15672` | 用户名 `veridex`，密码 `veridex-local` |
| MinIO API | `http://localhost:9000` | S3 兼容接口 |
| MinIO Console | `http://localhost:9001` | 用户名 `veridex`，密码 `veridex-local-secret` |
| OpenSearch | `http://localhost:9200` | 本地关闭安全插件 |
| Redis | `localhost:6379` | 当前主要作为后续能力基础设施 |

### 应用种子用户

三个本地开发用户共享密码 `veridex`：

| 用户名 | 密码 | 角色 |
|---|---|---|
| `admin` | `veridex` | `PLATFORM_ADMIN` |
| `kadmin` | `veridex` | `KNOWLEDGE_ADMIN` |
| `employee` | `veridex` | `EMPLOYEE` |

> 这些账号和基础设施密码都是开发默认值，禁止直接用于共享测试环境或生产环境。

## 配置

### Compose 环境变量

Compose 会自动读取 `deploy/compose/.env`。可参考 `deploy/compose/.env.example`：

| 变量 | 默认值 |
|---|---|
| `POSTGRES_DB` | `veridex` |
| `POSTGRES_USER` | `veridex` |
| `POSTGRES_PASSWORD` | `veridex-local` |
| `RABBITMQ_DEFAULT_USER` | `veridex` |
| `RABBITMQ_DEFAULT_PASS` | `veridex-local` |
| `MINIO_ROOT_USER` | `veridex` |
| `MINIO_ROOT_PASSWORD` | `veridex-local-secret` |

### 后端环境变量

Spring Boot 配置位于 `backend/src/main/resources/application.yml`。常用覆盖项：

| 变量 | 默认值 |
|---|---|
| `VERIDEX_DB_URL` | `jdbc:postgresql://localhost:5432/veridex` |
| `VERIDEX_DB_USERNAME` | `veridex` |
| `VERIDEX_DB_PASSWORD` | `veridex-local` |
| `VERIDEX_RABBITMQ_HOST` | `localhost` |
| `VERIDEX_RABBITMQ_PORT` | `5672` |
| `VERIDEX_RABBITMQ_USERNAME` | `veridex` |
| `VERIDEX_RABBITMQ_PASSWORD` | `veridex-local` |
| `VERIDEX_MINIO_ENDPOINT` | `http://localhost:9000` |
| `VERIDEX_MINIO_ACCESS_KEY` | `veridex` |
| `VERIDEX_MINIO_SECRET_KEY` | `veridex-local-secret` |
| `VERIDEX_MINIO_BUCKET` | `veridex-documents` |
| `VERIDEX_OPENSEARCH_URIS` | `http://localhost:9200` |
| `VERIDEX_OPENSEARCH_INDEX_PREFIX` | `veridex` |
| `VERIDEX_EMBEDDING_PROVIDER` | `deterministic` |
| `VERIDEX_EMBEDDING_DIMENSIONS` | `128` |

示例：使用不同的 PostgreSQL 地址启动后端：

```bash
VERIDEX_DB_URL=jdbc:postgresql://db.example.internal:5432/veridex \
VERIDEX_DB_USERNAME=veridex \
VERIDEX_DB_PASSWORD='replace-me' \
./mvnw -pl backend spring-boot:run
```

本地 OpenSearch 设置了 `DISABLE_SECURITY_PLUGIN=true`，仅适合开发环境。

## 测试与构建

运行项目完整质量门禁：

```bash
./scripts/verify.sh
```

该脚本依次执行后端 `clean verify`、前端测试、前端生产构建和 `git diff --check`。后端集成测试使用 Testcontainers，因此需要 Docker 正常运行。

也可以分别执行：

```bash
# 后端全部测试和构建
./mvnw clean verify

# 前端测试
npm --prefix web test

# 前端 ESLint
npm --prefix web run lint

# 前端生产构建
npm --prefix web run build
```

## 停止与清理

先在对应终端使用 `Ctrl+C` 停止前端和后端。

停止并删除基础设施容器，但保留 PostgreSQL、MinIO 和 OpenSearch 数据卷：

```bash
docker compose -f deploy/compose/compose.yml down
```

再次启动时会继续使用原有数据：

```bash
docker compose -f deploy/compose/compose.yml up -d
```

彻底删除容器和本地持久化数据：

```bash
docker compose -f deploy/compose/compose.yml down -v
```

> `down -v` 会永久删除本地 PostgreSQL、MinIO 和 OpenSearch 数据。执行后无法恢复。

## 常见问题

### Docker 或 Testcontainers 无法连接

确认 Docker Desktop 已启动：

```bash
docker info
docker compose -f deploy/compose/compose.yml ps
```

后端完整测试依赖 Docker；只启动应用也要求 Compose 中的 PostgreSQL、RabbitMQ、MinIO 和 OpenSearch 可访问。

### 端口已被占用

默认使用 `5432`、`5672`、`6379`、`9000`、`9001`、`9200`、`15672`、`8080` 和 `5173`。先停止占用端口的进程，或者修改 Compose 端口映射并通过后端环境变量同步连接地址。

### OpenSearch 无法启动或反复重启

OpenSearch 默认使用 `-Xms1g -Xmx1g`。增加 Docker Desktop 可用内存，然后查看日志：

```bash
docker compose -f deploy/compose/compose.yml logs opensearch
```

### 登录返回 401

确认：

- 使用种子用户名和密码 `veridex`；
- 后端已启动并连接到 Compose 中的 PostgreSQL；
- Flyway 已成功应用 V1–V4 迁移；
- 如果数据库来自旧的本地实验数据，可在确认不需要保留数据后执行 `down -v` 重建。

### 文档一直处于 `UPLOADED` 或变成 `FAILED`

依次检查：

```bash
curl --fail http://localhost:8080/actuator/health
docker compose -f deploy/compose/compose.yml ps
docker compose -f deploy/compose/compose.yml logs rabbitmq minio opensearch
```

`UPLOADED` 长期不变化通常表示 RabbitMQ 或 worker 未正常工作；`FAILED` 表示解析、对象读取或索引写入失败。可在 RabbitMQ UI 查看 `ingestion.document` 和 `ingestion.document.dlq`。

### 修改迁移后出现 Flyway checksum 错误

不要修改已经在持久化数据库执行过的迁移。开发期间若无需保留本地数据，可执行：

```bash
docker compose -f deploy/compose/compose.yml down -v
docker compose -f deploy/compose/compose.yml up -d
```

需要保留数据时，应新增更高版本的迁移，而不是修改现有迁移。

## 进一步阅读

- [企业 RAG 平台设计](docs/superpowers/specs/2026-08-09-enterprise-rag-platform-design.md)
- [企业交付路线图](docs/superpowers/plans/2026-08-09-enterprise-rag-delivery-roadmap.md)
- [知识入库处理管道](docs/knowledge-ingestion-pipeline.md)
- [Phase 2 实施计划](docs/superpowers/plans/2026-08-11-knowledge-ingestion-vertical-slice.md)
