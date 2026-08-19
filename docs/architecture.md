# 架构与模块说明

> 本文档基于当前代码状态整理（更新至 Phase 5-b），说明后端模块划分、前端路由、数据库迁移、API 与可观测性边界。它是面向开发者的「地图」，具体某个环节的参数语义见 [RAG 配置参数语义](rag-configuration-parameters.md)，知识入库链路见[知识入库处理管道](knowledge-ingestion-pipeline.md)。

## 1. 后端模块划分

后端是 Spring Boot 4 + Spring Modulith 模块化单体，包根为 `io.veridex`。每个模块通过 `package-info.java` 声明 `displayName` 与 `allowedDependencies`，由 `ArchitectureTest` 强制边界。

| 模块 | 职责 | 允许依赖 |
|---|---|---|
| `shared` | 跨模块基础设施：配置、embedding、messaging、outbox | （无，根模块） |
| `iam` | 身份与访问：用户、角色、Session 登录、当前用户上下文 | `shared` |
| `audit` | 审计日志写入 | `shared`, `iam` |
| `knowledge` | 知识库、文档、文档版本、授权范围 | `shared::config`, `shared::outbox`, `iam::api`, `audit::api` |
| `ingestion` | 文档解析（Tika）、结构分块、异步 worker、Rabbit context/MDC 恢复与 processing-stuck gauge | `shared::messaging`, `shared::observability`, `knowledge::api`, `indexing::api`, `audit::api` |
| `indexing` | 索引发布（IndexRelease）、OpenSearch 写入与别名、发布 observation | `shared::config`, `shared::observability`, `knowledge::api`, `iam::api` |
| `retrieval` | 混合检索（BM25 + 向量 + RRF 融合 + 上下文组装） | `shared`, `knowledge::api`, `indexing::api` |
| `generation` | 生成回答、拒答策略、引用校验 | `shared`, `retrieval::api`, `conversation::api`, `knowledge::api` |
| `conversation` | 会话与消息 | `shared`, `iam`, `generation` |
| `qa` | 问答编排：把授权、检索、生成、trace 串成 SSE 事件流 | `shared`, `iam::api`, `knowledge::api`, `retrieval::api`, `generation::api`, `conversation::api`, `trace::api` |
| `trace` | QueryRun 记录：检索命中、生成、引用证据链 | `shared`, `iam`, `retrieval`, `generation` |
| `configuration` | 不可变 RAG 配置版本（Profile） | `shared`, `iam::api` |
| `evaluation` | 评测集、评测运行、指标计算、版本对比门禁 | `shared`, `iam::api`, `retrieval::api`, `generation::api`, `configuration::api` |
| `feedback` | 点赞/点踩反馈与坏例转评测集 | `shared`, `iam::api` |

模块间的对外契约通过 `@NamedInterface(name = "api")` 暴露（如 `iam::api`、`retrieval::api`、`configuration::api`），跨模块只允许依赖对方声明的 API 包。

## 2. 前端路由

管理控制台（React + Vite）在 `web/src/app/routes.tsx` 中定义工作区路由：

| 路径 | 中文名 | 英文名 | 页面 |
|---|---|---|---|
| `/workbench` | 员工问答 | Workbench | `QaPage`（SSE 流式问答、点赞/点踩） |
| `/knowledge` | 知识管理 | Knowledge | `KnowledgePage`（知识库、文档、版本、索引发布） |
| `/evaluation` | 质量评测 | Evaluation | `EvaluationPage`（评测集、评测运行、对比门禁） |
| `/configuration` | 配置版本 | Configuration | `ConfigurationPage`（五维 RAG 配置草稿 + 发布） |
| `/feedback` | 反馈管理 | Feedback | `FeedbackPage`（点踩列表、转坏例） |
| `/admin` | 平台管理 | Administration | `ApiKeyAdminPage`（仅 PLATFORM_ADMIN / KNOWLEDGE_ADMIN；API key 创建、列表与吊销） |

`/admin` 的导航与路由按当前用户角色过滤。`PLATFORM_ADMIN` 可查看和吊销全部 key，并可通过可选目标用户 UUID 代理签发；`KNOWLEDGE_ADMIN` 仅能查看、签发和吊销自己的 key；`EMPLOYEE` 不显示入口，直接访问也会回到 `/workbench`。

## 3. 数据库迁移

Flyway 迁移位于 `backend/src/main/resources/db/migration/`，当前到 V12：

| 版本 | 内容 |
|---|---|
| V1 | 平台基线：`installation`、`outbox_event`、`audit_event` |
| V2 | 身份：`users`、角色 |
| V3 | 知识：`knowledge_base`、`knowledge_base_grant`、`document`、`document_version` |
| V4 | 索引发布：`index_release` |
| V5 | 知识库全量快照索引发布 |
| V6 | 归一化已回滚发布状态 |
| V7 | Phase 3 问答：`conversation`、`message`、`query_run`、`retrieval_hit`、`generation_run`、`citation` |
| V8 | Phase 4-a 评测集：`evaluation_dataset`、`evaluation_case`、`dataset_version`、`dataset_version_case` |
| V9 | Phase 4-b 配置版本：`configuration_profile`、`configuration_profile_version` |
| V10 | Phase 4-c 反馈：`feedback` |
| V11 | Phase 4-d 评测运行：`evaluation_run`、`evaluation_run_case` |
| V12 | Phase 5-a API key：`api_key`（哈希凭据、scope、吊销与使用时间） |
| V13 | Phase 5-b 可观测性：`trace_body`、Rabbit propagation columns、QueryRun scrub/fingerprint |

## 4. 可观测性边界

`shared::observability` 提供固定 observation/metric 名称、低基数 outcome/stage/error tags、Rabbit propagation 和 fail-open meter 写入。QA、retrieval、generation、outbox、ingestion、indexing 只在稳定业务边界使用这些原语。generation 指标：`veridex.generation.model`（duration）、`veridex.generation.first_token`（首 token 时延）、`veridex.generation.outcome`（success/refused/failed/cancelled），provider 标签只允许 `deterministic`/`ollama`（`BoundedModelTags` 收敛），错误码含 `MODEL_ERROR`/`MODEL_TIMEOUT`/`INVALID_CITATION`；usage 缺失时按字符估算并内部标记 estimated，不伪装为 provider 精确值。metrics、span attributes/events 和普通日志禁止保存 question、prompt、answer、chunk/evidence 文本、凭据、原始异常消息或业务 UUID；run ID 只能作为 span/log correlation，不能作为 metric label。

本地观测栈由 Prometheus、Grafana、OTel Collector 和 Tempo 组成，配置位于 `deploy/compose/observability`，固定镜像版本和校验入口为 `scripts/verify-observability.sh`。应用仍在宿主机运行，Prometheus 从宿主机管理端口 `host.docker.internal:8081/actuator/prometheus` 抓取，RabbitMQ metrics 从 `15692` 抓取。

Trace body 默认 `NONE`。启用 `ERRORS`/`ALL` 时使用环境变量 key ring 配置 AES-256-GCM；body TTL 默认 24 小时、明文上限 256 KiB。只有 `PLATFORM_ADMIN` browser Session 可通过 `GET /api/traces/{runId}/body` 读取，必须提供合法 `X-Trace-Access-Reason`，响应 `no-store`，每次成功/拒绝/not-found/decrypt-failed 都审计。该 retention 不等同于删除 conversation、feedback、evaluation、备份、replica 或派生索引中的副本。

## 5. API 概览

### 5.1 认证（`iam`）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/auth/me` | 当前登录用户 |
| GET | `/api/iam/keys` | PLATFORM_ADMIN 列出全部 key；KNOWLEDGE_ADMIN 仅列出自己的 key（不返回 token 明文） |
| POST | `/api/iam/keys` | 创建 scoped API key；平台管理员可指定 `userId`，知识管理员仅能为自己签发（token 明文仅返回一次） |
| DELETE | `/api/iam/keys/{keyId}` | 平台管理员可吊销任意 key；知识管理员仅能吊销自己的 key |

### 5.2 OpenAPI

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/v3/api-docs` | OpenAPI JSON 文档（仅管理员 Session） |

### 5.3 知识管理（`knowledge`）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/knowledge-bases` | 创建知识库 |
| GET | `/api/knowledge-bases` | 知识库列表 |
| GET | `/api/knowledge-bases/{id}` | 知识库详情 |
| POST | `/api/knowledge-bases/{kbId}/documents` | 上传文档 |
| GET | `/api/knowledge-bases/{kbId}/documents` | 文档列表 |
| GET | `/api/documents/{documentId}/versions` | 文档版本列表 |
| GET | `/api/documents/{documentId}/versions/{versionId}/parsed` | 解析全文预览 |
| GET | `/api/documents/{documentId}/versions/{versionId}/chunks` | 分块预览 |

### 5.4 索引发布（`indexing`）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/knowledge-bases/{kbId}/releases` | 发布列表 |
| POST | `/api/knowledge-bases/{kbId}/releases/publish` | 发布新索引 |
| POST | `/api/knowledge-bases/{kbId}/releases/{releaseId}/make-current` | 设为当前 |
| POST | `/api/knowledge-bases/{kbId}/releases/{releaseId}/offline` | 下架 |
| POST | `/api/knowledge-bases/{kbId}/releases/{releaseId}/delete` | 删除 |

### 5.5 问答（`qa`）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/qa/ask` | SSE 流式问答（真实 token 流） |
| GET | `/api/qa/conversations` | 当前用户会话列表 |

`/api/qa/ask` 由 Controller 返回 `Flux<ServerSentEvent<QaEvent>>`，事件协议：
`run.started` → `retrieval.completed` → `answer.delta`* → `citation.available` → `answer.completed` / `answer.refused` / `run.failed`。
`answer.delta` 是 **provisional**（真实模型增量，不在服务端缓存）；只有 `answer.completed` 表示完整文本已通过
**引用终检**并持久化（assistant 消息 + Citation + GenerationRun）。引用失败发 `run.failed`
（稳定码 `INVALID_CITATION`），不保存不完整回答；模型故障/超时（`MODEL_ERROR`/`MODEL_TIMEOUT`）不回退
deterministic；客户端断开会取消上游 `ChatModel.stream()` 订阅，QueryRun 与 trace 落 `CANCELLED`，
取消收尾幂等。SSE 层 async timeout 固定 120s（≥ `veridex.chat.timeout` 默认 60s + 余量）。
| GET | `/api/qa/conversations/{id}/messages` | 会话消息 |
| POST | `/api/qa/feedback` | 占位（返回 204，实际反馈走 `/api/feedback`） |

### 5.6 配置版本（`configuration`）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/configuration/profiles` | 创建 Profile |
| GET | `/api/configuration/profiles` | Profile 列表 |
| GET | `/api/configuration/profiles/{id}` | Profile 详情（含当前草稿） |
| PUT | `/api/configuration/profiles/{id}` | 更新草稿 |
| POST | `/api/configuration/profiles/{id}/publish` | 发布不可变版本 |
| GET | `/api/configuration/profiles/{id}/versions` | 版本列表 |
| GET | `/api/configuration/profiles/{id}/versions/{versionNo}` | 版本详情 |

### 5.7 评测（`evaluation`）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/evaluation/datasets` | 创建评测集 |
| GET | `/api/evaluation/datasets` | 评测集列表 |
| GET | `/api/evaluation/datasets/{id}` | 评测集详情 |
| POST | `/api/evaluation/datasets/{id}/cases` | 添加用例 |
| PUT | `/api/evaluation/datasets/{id}/cases/{caseId}` | 更新用例 |
| DELETE | `/api/evaluation/datasets/{id}/cases/{caseId}` | 删除用例 |
| GET | `/api/evaluation/datasets/{id}/cases` | 用例列表 |
| POST | `/api/evaluation/datasets/{id}/publish` | 发布不可变数据集版本 |
| GET | `/api/evaluation/datasets/{id}/versions` | 数据集版本列表 |
| GET | `/api/evaluation/datasets/{id}/versions/{versionNo}` | 数据集版本详情 |
| POST | `/api/evaluation/runs` | 发起评测运行 |
| GET | `/api/evaluation/runs` | 运行列表（可按 `datasetId` 过滤） |
| GET | `/api/evaluation/runs/{id}` | 运行详情 |
| POST | `/api/evaluation/comparisons` | 两个运行对比 + 门禁判定 |

### 5.8 反馈（`feedback`）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/feedback` | 记录点赞/点踩反馈 |
| GET | `/api/feedback` | 反馈列表（可按 `rating` 过滤） |
| POST | `/api/feedback/{id}/converted` | 标记为已转坏例 |

## 5. Phase 4 评测闭环的数据流

```text
评测集（EvaluationDataset，可变草稿）
   │ publish
   ▼
DatasetVersion（不可变快照 + DatasetVersionCase 冻结用例）

配置 Profile（可变 draft）
   │ publish
   ▼
ConfigurationProfileVersion（不可变五维快照）

管理员选：DatasetVersion + ProfileVersion + 知识库范围
   │ POST /api/evaluation/runs
   ▼
EvaluationRun（RUNNING → COMPLETED / FAILED）
   └─ 每个 case：检索 → 生成 → 计算 CaseMetrics → 存 EvaluationRunCase

两个 COMPLETED run
   │ POST /api/evaluation/comparisons
   ▼
逐指标对比 + 回归门禁判定（PASS / FAIL / INCOMPLETE）

线上点踩反馈
   │ POST /api/feedback
   ▼
Feedback（rating=DOWN + reasonCode + evidence）
   │ 反馈管理页「转坏例」
   ▼
评测集新 case → 再次发布数据集版本进入评测
```

## 6. 安全加固边界（Phase 5-c）

- Actuator 独立管理端口（默认 `8081`，绑定 `127.0.0.1`），仅暴露 `health`、`info`、`prometheus`；业务端口不暴露 `/actuator/**`。
- 浏览器 Session 写请求启用 CSRF（`XSRF-TOKEN` cookie + `X-XSRF-TOKEN` header）；Bearer API key 走无状态边界，不要求 CSRF。Session cookie 默认 `HttpOnly`、`SameSite=Lax`，生产/试点强制 `Secure`。
- 上传入口校验大小、扩展名、MIME 和魔数一致性；压缩包受展开字节、条目数、嵌套深度和路径穿越约束；解析在独立临时目录内以配置超时执行，失败只使当前版本失败，旧 release 保持可用。
- 出站请求只允许配置的 scheme/host/port，并在 DNS 解析后拒绝 loopback、link-local、RFC1918、CGNAT 与云 metadata 地址；禁用自动重定向并限制响应字节与超时。
- 对象预览/下载在服务端按 actor 与 knowledge scope 授权，不存在与无权均返回 404（不泄露存在性）；预览和 trace-body 响应 `no-store`。
- 安全配置错误 fail-fast；运行时安全检查 fail-closed；错误响应与 telemetry 不泄露正文、凭据、原始异常、磁盘路径或完整 query string。
- 校验入口为 `scripts/verify-security.sh`（随 `scripts/verify.sh` 执行）。

## 7. 部署拓扑（Phase 5-d）

- backend 双端口：`8080` 业务 API 与 `8081` 管理端口（liveness/readiness/prometheus）。管理端口只由独立 Service（带 `veridex.io/management: "true"` 标签）暴露，从不进入 Ingress 或 web 代理。
- web 为 Nginx 静态容器（`8080`）：同源代理 `/api` 与 `/v3/api-docs` 至 backend，转发 `X-Request-Id` 并支持 SSE；`/healthz` 供存活探针，SPA fallback 兜底前端路由，不代理 `/actuator`。
- 镜像安全基线：非 root（backend 10001、web 101）、只读根文件系统、drop ALL capabilities、`seccompProfile: RuntimeDefault`、`allowPrivilegeEscalation: false`、`automountServiceAccountToken: false`；解析临时目录为 emptyDir（2Gi，sizeLimit 约束）。
- chart 只管理应用工作负载；基础设施凭据一律来自 existing Secret（固定 key：`db-username`、`db-password`、`rabbitmq-username`、`rabbitmq-password`、`minio-access-key`、`minio-secret-key`、`session-secret`；trace body 开启时另需 `trace-fingerprint-key` / `trace-current-key-id` / `trace-current-key`），chart 不创建、不落明文。
- NetworkPolicy 默认 deny：web 仅接受 Ingress 流量并只允许 DNS + backend:8080 出站；backend 仅接受 web（8080）与监控（8081）入口，出站限 DNS 与 `networkPolicy.externalEgress` 显式声明的 selector 或 CIDR（禁止 `0.0.0.0/0`）。
- ServiceMonitor（可选）只抓带 management 标签的 Service 的 `management` 命名端口（`/actuator/prometheus`）。
- Chat 生成模型：`veridex.chat.provider`（默认 `deterministic` 测试占位，`ollama` 为首个真实 Chat provider）。Ollama 由安装者预置（不进默认 Compose/chart/离线包），后端经 `VERIDEX_CHAT_PROVIDER/VERIDEX_OLLAMA_BASE_URL/VERIDEX_OLLAMA_CHAT_MODEL/VERIDEX_CHAT_TIMEOUT` 配置；HTTP 时必须显式放行 `VERIDEX_OUTBOUND_ALLOWED_*` 并在 `networkPolicy.externalEgress` 声明 Ollama 的 selector/CIDR + 11434（chart 默认拒绝该出站）。真实模型显式验收：`scripts/verify-ollama-chat.sh`（不进默认 CI）。
- 部署校验入口：`scripts/verify-deployment.sh`（镜像、Compose 栈、Helm lint/template 矩阵、kind 集群验收、离线包；随 `scripts/verify.sh` 执行）。已知限制：backend 为内存 Session，多副本需粘性或后续引入 Spring Session；备份恢复与多架构镜像门禁留给 Phase 5-e。

## 8. 与历史设计文档的关系

`docs/superpowers/specs/` 与 `docs/superpowers/plans/` 下是按日期归档的设计与实施快照（Phase 1–4），保留原始决策过程，不再随代码演进更新。本文档与 [RAG 配置参数语义](rag-configuration-parameters.md) 是随代码更新的「当前态」说明，如两者冲突以代码和本文档为准。
