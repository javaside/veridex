# Veridex 发布包安装与运行指南

本包是 **Compose 单机发布包**：不需要源码、不需要 Java/Maven、不需要 Node.js 构建环境，只需要一台装有 Docker 的机器。导入应用镜像、配好 `.env`、跑一条命令，即可启动完整应用栈。

## 1. 环境要求

| 依赖 | 版本 | 说明 |
|---|---|---|
| Docker Engine | 20.10+ | 推荐 Docker Desktop 或 Linux 上的 Docker Engine |
| Docker Compose | v2+ | 使用 `docker compose` 子命令（本包脚本已默认使用） |

验证：

```bash
docker version
docker compose version
```

> 建议 Docker 至少可分配 **4 GB 内存**：OpenSearch 默认分配 1 GB JVM 堆，加上 PostgreSQL / RabbitMQ / MinIO / 后端 JVM，4 GB 以下容易 OOM。

## 2. 目录结构

```text
veridex-<版本>-compose/
├── README.md            本文件
├── .env.example         环境变量模板（复制为 .env 后填写）
├── compose.yml          Compose 编排（基础设施 + backend + web）
├── images/
│   ├── veridex-backend.tar   后端应用镜像
│   └── veridex-web.tar       前端应用镜像
├── load-images.sh       导入应用镜像
├── start.sh             一键启动 + 冒烟验证
├── stop.sh              停止
└── smoke.sh             冒烟验证脚本
```

## 3. 安装步骤

### 3.1 解压

```bash
tar -xzf veridex-<版本>-compose.tar.gz
cd veridex-<版本>-compose
```

### 3.2 配置 `.env`

```bash
cp .env.example .env
```

编辑 `.env`，**至少完成模型配置**（否则问答检索跑不出真实结果）。配置项分三类：

**① 基础设施凭据（可选，本地默认值即可跑）**

```bash
POSTGRES_DB=veridex
POSTGRES_USER=veridex
POSTGRES_PASSWORD=veridex-local
MINIO_ROOT_USER=veridex
MINIO_ROOT_PASSWORD=veridex-local-secret
RABBITMQ_DEFAULT_USER=veridex
RABBITMQ_DEFAULT_PASS=veridex-local
GRAFANA_ADMIN_PASSWORD=veridex-local
```

**② Chat 对话模型（二选一，必填）**

方案 A：DeepSeek（需 API key）

```bash
VERIDEX_CHAT_PROVIDER=deepseek
VERIDEX_DEEPSEEK_API_KEY=<你的 API key>
VERIDEX_OUTBOUND_ALLOWED_HOSTS=api.deepseek.com
VERIDEX_OUTBOUND_ALLOWED_PORTS=443
```

方案 B：Ollama（宿主机预置 Ollama 与模型）

```bash
VERIDEX_CHAT_PROVIDER=ollama
VERIDEX_OLLAMA_BASE_URL=http://host.docker.internal:11434
VERIDEX_OLLAMA_CHAT_MODEL=qwen3:8b
VERIDEX_OUTBOUND_ALLOWED_HOSTS=host.docker.internal
VERIDEX_OUTBOUND_ALLOWED_PORTS=11434
VERIDEX_OUTBOUND_ALLOW_INSECURE_HTTP=true
```

**③ Embedding 向量模型（必填，切 Ollama 才有真实语义向量）**

```bash
VERIDEX_EMBEDDING_PROVIDER=ollama
VERIDEX_EMBEDDING_DIMENSIONS=1024
VERIDEX_OLLAMA_BASE_URL=http://host.docker.internal:11434
VERIDEX_OLLAMA_EMBEDDING_MODEL=qwen3-embedding:0.6b
```

> ⚠️ **维度必须与模型匹配**：`qwen3-embedding:0.6b` 是 1024 维；`qwen3-embedding:latest`（不带 tag 的 `qwen3-embedding` 也解析到它）是 **4096 维**，用它时须把 `VERIDEX_EMBEDDING_DIMENSIONS` 改成 `4096`。维度不符后端会直接拒绝启动（`Embedding model returned N dimensions but ...`）。

> ⚠️ **不要用默认的 `deterministic`**：它是 128 维无语义哈希，向量检索基本失效，只能用于无外部依赖的冒烟。真实使用必须把 Chat 与 Embedding 都配成真实 provider。
>
> Ollama 与模型权重（qwen3:8b / qwen3-embedding:0.6b 或自定义）由你自行在宿主机预置，本包**不打包 Ollama 服务与权重**。使用 Ollama 时请确认宿主机 11434 端口可达（容器内用 `host.docker.internal` 而非 `localhost`）。

### 3.3 启动

```bash
./start.sh
```

`start.sh` 会依次：导入应用镜像 → `docker compose up -d` → 冒烟验证。

也可以手动分步执行：

```bash
./load-images.sh                  # 1. 导入应用镜像
docker compose up -d              # 2. 启动全栈
docker compose ps                 # 3. 查看状态
./smoke.sh                        # 4. 冒烟验证
```

首次启动需要从公共 registry 拉取**基础设施镜像**（PostgreSQL、RabbitMQ、MinIO、OpenSearch、Prometheus、Grafana、Tempo、OTel Collector），需要联网；OpenSearch 通常最慢。等待各服务进入 `healthy` 状态。

## 4. 验证

冒烟脚本会验证：web 存活与 SPA 回退、CSRF 下发、admin 登录会话、CSRF 保护的登出、web 不代理 Actuator、（可选）Prometheus 抓取后端。

也可手动检查：

```bash
curl -fsS http://127.0.0.1:8090/healthz        # 期望 ok
docker compose ps                               # 期望各服务 healthy/running
```

## 5. 访问地址与默认账号

| 服务 | 地址 |
|---|---|
| Web 控制台 | http://localhost:8090 |
| Backend API | 经 Web 的 `/api` 反向代理（不直接对外） |
| RabbitMQ UI | http://localhost:15672（`veridex` / `veridex-local`） |
| MinIO Console | http://localhost:9001（`veridex` / `veridex-local-secret`） |
| Grafana | http://localhost:3000 |
| Prometheus | http://localhost:9090 |

**种子用户**（密码均为 `veridex`）：

| 用户名 | 角色 |
|---|---|
| `admin` | PLATFORM_ADMIN |
| `kadmin` | KNOWLEDGE_ADMIN |
| `employee` | EMPLOYEE |

> 这些账号与基础设施密码均为**开发默认值**，禁止用于共享测试或生产环境。生产部署请改 `.env` 中的全部凭据。

## 6. 停止与清理

```bash
./stop.sh          # 停止并删除容器，保留数据卷
```

彻底删除容器与本地持久化数据（PostgreSQL / MinIO / OpenSearch）：

```bash
docker compose down -v
```

> `down -v` 会永久删除数据，执行后无法恢复。

## 7. 常见问题

- **后端 Pod/容器一直不 ready**：多半是 `.env` 里模型配置缺失或安全配置非法。查看后端日志 `docker compose logs backend`，日志只输出固定配置错误码；改完 `.env` 后 `docker compose restart backend` 使其生效。
- **向量检索没有结果/质量差**：确认 `VERIDEX_EMBEDDING_PROVIDER=ollama` 且 `VERIDEX_EMBEDDING_DIMENSIONS=1024`，并核对宿主机 Ollama 的 `qwen3-embedding:0.6b` 模型已拉取（注意 `latest` 是 4096 维，与 1024 不匹配）。
- **DeepSeek 调用失败**：确认 `VERIDEX_DEEPSEEK_API_KEY` 已填，且 `VERIDEX_OUTBOUND_ALLOWED_HOSTS=api.deepseek.com`、`VERIDEX_OUTBOUND_ALLOWED_PORTS=443`（后端出站策略 fail-fast）。
- **Ollama 走 HTTP 被拦**：必须同时设 `VERIDEX_OUTBOUND_ALLOWED_HOSTS` / `VERIDEX_OUTBOUND_ALLOWED_PORTS` / `VERIDEX_OUTBOUND_ALLOW_INSECURE_HTTP=true`。

## 8. 生产部署提示

本包面向单机/试运行。需要多副本、滚动发布、Ingress/TLS、NetworkPolicy、HPA、ServiceMonitor、备份恢复等企业能力时，请使用仓库内的 **Helm chart**（`deploy/helm/veridex`）与离线交付包（`deploy/offline/`）。完整说明见仓库 `README.md` 与 `docs/`。
