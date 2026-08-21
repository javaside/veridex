# 配置说明

本文档说明 Veridex 的配置项与模型 provider 切换。Spring Boot 配置位于 `backend/src/main/resources/application.yml`，所有敏感项均可通过环境变量覆盖。

## Compose 环境变量

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

## 后端环境变量

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
| `VERIDEX_OLLAMA_BASE_URL` | `http://localhost:11434`（embedding 与 chat 共用） |
| `VERIDEX_OLLAMA_EMBEDDING_MODEL` | `qwen3-embedding` |
| `VERIDEX_CHAT_PROVIDER` | `deepseek`（默认）或 `ollama`；`deterministic` 为测试占位 |
| `VERIDEX_CHAT_TIMEOUT` | `60s`（一次模型生成总时限） |
| `VERIDEX_DEEPSEEK_BASE_URL` | `https://api.deepseek.com` |
| `VERIDEX_DEEPSEEK_API_KEY` | 空；使用 deepseek 时必填（可经 `DEEPSEEK_API_KEY` 传入） |
| `VERIDEX_DEEPSEEK_MODEL` | `deepseek-v4-flash` |
| `VERIDEX_OLLAMA_CHAT_MODEL` | `qwen3:8b`（可用宿主机已装模型覆盖，如 `qwen3.5:9b-mlx`） |
| `VERIDEX_OUTBOUND_ALLOWED_HOSTS` | 空；使用真实 Ollama 时填其 host |
| `VERIDEX_OUTBOUND_ALLOWED_PORTS` | 空；Ollama 为 `11434` |
| `VERIDEX_OUTBOUND_ALLOW_INSECURE_HTTP` | `false`；Ollama 走 HTTP 时置 `true` |
| `VERIDEX_ENVIRONMENT` | `local` |
| `VERIDEX_TRACING_SAMPLING_PROBABILITY` | `0.1` |
| `VERIDEX_OTLP_ENDPOINT` | `http://localhost:4318/v1/traces` |
| `VERIDEX_TRACE_BODY_CAPTURE_POLICY` | `NONE` |
| `VERIDEX_TRACE_BODY_RETENTION` | `24h` |
| `VERIDEX_TRACE_BODY_MAX_PLAINTEXT_SIZE` | `256KB`（约 256 KiB 上限） |
| `VERIDEX_TRACE_BODY_CLEANUP_INTERVAL` | `1h` |
| `VERIDEX_TRACE_BODY_CLEANUP_BATCH_SIZE` | `500` |
| `VERIDEX_TRACE_BODY_FINGERPRINT_KEY` | 空，NONE 模式允许为空 |
| `VERIDEX_TRACE_BODY_CURRENT_KEY_ID` | 空；ERRORS/ALL 时必填 |
| `VERIDEX_TRACE_BODY_CURRENT_KEY` | 空；ERRORS/ALL 时必填，32-byte Base64 |
| `VERIDEX_TRACE_BODY_HISTORICAL_KEYS` | 空；格式 `keyId=Base64Key,...` |

示例：使用不同的 PostgreSQL 地址启动后端：

```bash
VERIDEX_DB_URL=jdbc:postgresql://db.example.internal:5432/veridex \
VERIDEX_DB_USERNAME=veridex \
VERIDEX_DB_PASSWORD='replace-me' \
./mvnw -pl backend spring-boot:run
```

本地 OpenSearch 设置了 `DISABLE_SECURITY_PLUGIN=true`，仅适合开发环境。

## Chat 生成模型（真实流式问答）

对话模型由 `VERIDEX_CHAT_PROVIDER` 决定，启动时后端会打印实际装配的 provider 与模型（例如 `veridex.chat.provider=deepseek (model=..., baseUrl=...)`）。

- 默认 `deepseek`（模型 `deepseek-v4-flash`）为**真实流式模型**，需提供 `VERIDEX_DEEPSEEK_API_KEY`；
  `deterministic` 是测试占位（从证据块模板拼接回答，无外部依赖、CI/离线可重复）。
- 切换为本地 **Ollama**（需安装者预置 Ollama 与模型权重）：

```bash
VERIDEX_CHAT_PROVIDER=ollama \
VERIDEX_OLLAMA_BASE_URL=http://ollama.internal:11434 \
VERIDEX_OLLAMA_CHAT_MODEL=qwen3:8b \
VERIDEX_CHAT_TIMEOUT=60s \
VERIDEX_OUTBOUND_ALLOWED_HOSTS=ollama.internal \
VERIDEX_OUTBOUND_ALLOWED_PORTS=11434 \
VERIDEX_OUTBOUND_ALLOW_INSECURE_HTTP=true \
./mvnw -pl backend spring-boot:run
```

- Ollama 经 HTTP 时必须显式开启 insecure-http 并放行 host/port（后端出站策略 fail-fast）；模型故障/超时**不会**回退 deterministic。
- 流式回答为 **provisional**：`answer.delta` 只是临时文本，只有引用终检通过（`answer.completed`）才持久化；引用失败或模型失败发 `run.failed` 且不保存不完整回答；客户端断开会取消上游模型流并落 `CANCELLED`。
- 真实 Ollama 显式验收入口：`./scripts/verify-ollama-chat.sh`（不进默认 CI）。

## Embedding 向量模型

向量模型由 `VERIDEX_EMBEDDING_PROVIDER` 决定，启动时后端会打印实际装配的 provider、模型与 baseUrl（例如 `veridex.embedding.provider=ollama (model=..., baseUrl=...)`）。

向量模型同时服务于**入库发布**（对 chunk 生成向量写入 OpenSearch）和**在线问答的向量检索路**（把问题转成向量做 kNN），两处共用同一个 `EmbeddingModel` 实例，配置一致。

默认 `deterministic`（128 维确定性哈希，无语义）。切换真实语义模型：

```bash
ollama pull qwen3-embedding
```

以 `VERIDEX_EMBEDDING_PROVIDER=ollama VERIDEX_EMBEDDING_DIMENSIONS=1024` 启动：

```bash
VERIDEX_EMBEDDING_PROVIDER=ollama \
VERIDEX_EMBEDDING_DIMENSIONS=1024 \
./mvnw -pl backend spring-boot:run
```

> 换模型后须发新 release 重建索引（旧 128 维索引不可在新维度下查询）。若 embedding 地址配错（如指向一个已关闭的本地代理端口），发布索引时会报 `EOF` 或连接错误——请以启动日志里的 `veridex.embedding.provider=...` 行为准核对 baseUrl。
