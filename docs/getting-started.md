# 快速启动指南

本文档说明如何从零启动 Veridex 并验证基本链路。

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

等待 `postgres`、`rabbitmq`、`minio` 和 `opensearch` 的状态全部变为 `healthy`。首次启动可能需要下载镜像，OpenSearch 通常最慢。

查看基础设施日志：

```bash
docker compose -f deploy/compose/compose.yml logs -f
```

### 2. 启动后端

> ⚠️ **启动前必须先配好模型，否则问答检索无法给出真实结果。**
>
> - **Embedding 向量模型**：默认 `deterministic` 是 128 维无语义哈希，**不能用于真实检索**。必须切换到 `ollama`（`qwen3-embedding:0.6b`，1024 维），并同步设 `VERIDEX_EMBEDDING_DIMENSIONS=1024`。
> - **Chat 对话模型**：默认 `deepseek` 是真实模型，但**必须提供 `VERIDEX_DEEPSEEK_API_KEY`**，否则调用会失败；也可切 `ollama`。
>
> 两者都要显式配置，**不要用默认的 `deterministic`**。具体变量与示例见 [配置说明](configuration.md) 的「Chat 生成模型」与「Embedding 向量模型」两节。

在第二个终端执行（先导出模型相关环境变量再启动）：

```bash
# 示例：deepseek 对话 + Ollama 向量（替换为你的真实值）
export VERIDEX_CHAT_PROVIDER=deepseek
export VERIDEX_DEEPSEEK_API_KEY='<你的 API key>'
export VERIDEX_EMBEDDING_PROVIDER=ollama
export VERIDEX_EMBEDDING_DIMENSIONS=1024
export VERIDEX_OLLAMA_BASE_URL=http://localhost:11434
export VERIDEX_OLLAMA_EMBEDDING_MODEL=qwen3-embedding:0.6b
export VERIDEX_OUTBOUND_ALLOWED_HOSTS=api.deepseek.com,localhost
export VERIDEX_OUTBOUND_ALLOWED_PORTS=443,11434
export VERIDEX_OUTBOUND_ALLOW_INSECURE_HTTP=true

./mvnw -pl backend spring-boot:run
```

启动日志会打印实际装配的 provider 与模型（`veridex.chat.provider=...`、`veridex.embedding.provider=...`），启动后请先核对这两行，确认配的是真实模型而非 `deterministic`。

后端业务 API 默认监听 `http://localhost:8080`，Actuator 管理端点独立监听 `http://127.0.0.1:8081`（仅 `health`、`info`、`prometheus`）。Flyway 会在启动时自动创建或校验数据库结构。

检查健康状态：

```bash
curl --fail http://localhost:8081/actuator/health
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

前端默认访问地址为 `http://localhost:5173`。Vite 会将 `/api` 请求代理到 `http://localhost:8080`；Actuator 管理端点不通过 Vite 代理，直接访问 `http://localhost:8081`。

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
| Backend Health | `http://localhost:8081/actuator/health` | 管理端口健康检查 |
| PostgreSQL | `localhost:5432` | 数据库 `veridex` |
| RabbitMQ AMQP | `localhost:5672` | 应用消息连接 |
| RabbitMQ UI | `http://localhost:15672` | 用户名 `veridex`，密码 `veridex-local` |
| MinIO API | `http://localhost:9000` | S3 兼容接口 |
| MinIO Console | `http://localhost:9001` | 用户名 `veridex`，密码 `veridex-local-secret` |
| OpenSearch | `http://localhost:9200` | 本地关闭安全插件 |
| Prometheus | `http://localhost:9090` | 应用与 RabbitMQ metrics |
| Grafana | `http://localhost:3000` | 预置 Veridex Overview dashboard |
| Tempo | `http://localhost:3200` | trace 查询 API |
| OTLP Collector | `localhost:4317/4318` | gRPC/HTTP trace 接收 |

### 应用种子用户

三个本地开发用户共享密码 `veridex`：

| 用户名 | 密码 | 角色 |
|---|---|---|
| `admin` | `veridex` | `PLATFORM_ADMIN` |
| `kadmin` | `veridex` | `KNOWLEDGE_ADMIN` |
| `employee` | `veridex` | `EMPLOYEE` |

> 这些账号和基础设施密码都是开发默认值，禁止直接用于共享测试环境或生产环境。

## 测试与构建

运行项目完整质量门禁：

```bash
./scripts/verify.sh
```

该脚本依次执行后端 `clean verify`、前端测试、前端生产构建和 `git diff --check`。后端集成测试使用 Testcontainers，因此需要 Docker 正常运行。

也可以分别执行：

```bash
# 后端全部测试和构建
./mvnw -pl backend clean verify

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
