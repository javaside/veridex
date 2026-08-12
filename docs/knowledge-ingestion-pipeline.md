# 知识入库处理管道 — 架构说明

> 本文档基于 `feature/phase2-ingestion` 分支当前实现（2026-08-11）整理，解释一次「上传文件 → 可被检索」的完整处理链路：每个环节做了什么、数据存在哪里、各组件职责边界。

---

## 1. 一次上传发生了什么（总览）

```text
┌─────────┐   ①同步      ┌──────────────────────────────┐
│  前端    │ ──────────> │  上传 API（DocumentController）│
│ (知识管理)│   202 受理   │  └ DocumentUploadHandler      │
└─────────┘             └──────────────┬───────────────┘
                                       │ 同一事务内
              ┌────────────────────────┼────────────────────────┐
              ▼                        ▼                        ▼
        校验/指纹                  MinIO（原文件）           PostgreSQL
        白名单/SHA-256                                   outbox_event 记事件
                                                         audit_event 记审计
                                       │
                                       ▼  事务提交后（AFTER_COMMIT）
                                RabbitMQ（ingestion.document 队列）
                                       │
                                       ▼  异步消费（worker）
                        ┌──────────────────────────────┐
                        │   DocumentIngestionWorker     │
                        │  ① 幂等检查（状态机）           │
                        │  ② Tika 解析 → 纯文本          │
                        │  ③ 结构优先分块 → chunk 列表    │
                        │  ④ 解析结果写回 MinIO(预览用)   │
                        │  ⑤ markReady → READY（不发布）  │
                        └──────────────┬───────────────┘
                                       ▼
                               OpenSearch（向量+全文索引）
```

失败路径：任一步异常 → 版本 `FAILED` → 消息进死信队列 `ingestion.document.dlq`，不影响其它已发布版本。

---

## 2. 各组件职责

### 2.1 PostgreSQL — 唯一业务事实源（Source of Truth）

**存**：所有业务状态与元数据，不含文件内容、不含向量。

| 表（迁移） | 内容 |
|---|---|
| `users`（V2） | 用户、角色（PLATFORM_ADMIN / KNOWLEDGE_ADMIN / EMPLOYEE）、BCrypt 密码哈希 |
| `knowledge_base`（V3） | 知识库（名称、slug、owner） |
| `knowledge_base_grant`（V3） | 用户对知识库的 VIEW/MANAGE 授权 |
| `document`（V3） | 文档元数据（文件名、类型、大小、所属知识库） |
| `document_version`（V3） | **版本状态机**：UPLOADED → PROCESSING → READY / FAILED / OFFLINE；chunk 数、解析结果对象 key、错误信息 |
| `index_release`（V4） | 索引发布记录（见第 4 节） |
| `outbox_event`（V1） | 事务发件箱：待投递到 RabbitMQ 的事件 |
| `audit_event`（V1） | 审计日志（document.upload / ingestion.completed 等） |
| `installation`（V1） | 安装信息（占位） |

**关键设计**：PostgreSQL 是**事实源**。Redis/OpenSearch 只是派生数据，不拥有业务事实。处理状态、版本号、chunk 数都以这里为准。

### 2.2 MinIO — 对象存储（S3 兼容）

**存两类对象**：

1. **原始文件**（上传时写入）：
   `objectKey = {kbId}/{documentId}/v{版本号}/{filename}`
2. **解析产物**（worker 处理时写入，供预览）：
   - `{objectKey}.parsed.json` — 解析出的全文
   - `{objectKey}.chunks.json` — 分块列表

**不存**：向量、状态、元数据。文件内容本体只在这里（PostgreSQL 只存 `object_key` 引用和 SHA-256）。

### 2.3 Apache Tika — 文档解析

`TikaDocumentParser` 用 Tika 的 `AutoDetectParser`（自动识别格式）从 PDF / DOCX / TXT / Markdown 中**提取纯文本**，返回 `ParsedDocument(text, sourceFilename, contentType, metadata)`。

- 只做"内容 → 文本"，不做分块、不做理解
- 解析失败（如损坏文件）→ 抛异常 → worker 标记 FAILED

### 2.4 结构优先分块器 — 把长文本切成检索单元

`StructureFirstChunker`（`MAX_CHARS=2000`，`OVERLAP=80`）：

1. **按 Markdown 标题切章节**：`^(#{1,3})\s+(.+)$`（1-3 级标题）命中即开新章节，标题同时作为该 chunk 的 `title` 和序号 `structurePath`（1、2、3…）
2. **无标题的纯文本**：整篇聚合为一个章节（标题用文件名）
3. **超长块硬切**：章节正文 > 2000 字符时，按 2000 字符切段，段间重叠 80 字符（保证跨段语义不丢）

每个 chunk = `Chunk(index, text, title, structurePath)`。示例：

```markdown
# 入职指南            → chunk0: title=入职指南, path=1
## 第一天             → chunk1: title=第一天, path=2
  欢迎加入公司…        （并入 chunk1）
# 薪酬福利            → chunk2: title=薪酬福利, path=3
```

> 说明：v1 只识别 Markdown 标题；纯文本（如 DOCX/PDF 无标题结构）按段落聚合 + 硬切。后续可增强标题启发式。

### 2.5 Embedding 模型 — 文本 → 向量

**当前实现：`DeterministicEmbeddingModel`（确定性哈希向量，128 维）**。

```java
for (i in bytes) vector[i % 128] += (byte - 128) / 128f;  // 然后 L2 归一化
```

- 输出 128 维归一化向量
- **这不是语义模型**：它只是把字节确定性映射到向量，相同文本必得相同向量，但**不包含语义相似度**（"苹果"和"水果"不会更接近）
- **定位**：开发与集成测试的本地默认，**无需外部 API、无成本、可复现**——用于打通管道和验证幂等/检索流程
- **生产**：按配置 `veridex.embedding.provider`（默认 `deterministic`）切换真实模型（OpenAI / 本地 ONNX 等，Phase 3+ 实现）。OpenSearch 索引的 `dimension` 由 `veridex.embedding.dimensions`（默认 128）决定，两者必须一致

### 2.6 OpenSearch — 检索索引（向量 + 全文）

**用途**：Phase 3「RAG 查询」的检索底座。每个发布版本建一个**不可变索引**（index），通过**别名（alias）**对外提供"当前可用"视图。

索引 mapping 字段：

| 字段 | 类型 | 用途 |
|---|---|---|
| `text` | text | BM25 全文检索 |
| `embedding` | knn_vector(dim) | 向量相似度检索 |
| `document_version_id` / `knowledge_base_id` / `release_id` | keyword | 过滤/归属 |
| `chunk_index` / `structure_path` / `title` | keyword/int | 定位与展示 |

**文档 `_id` = `{documentVersionId}:{chunkIndex}`** → 重复投递幂等（bulk 覆盖写同一文档，不产生重复 chunk）。

**不存**：业务事实（PostgreSQL 才是事实源）。OpenSearch 可在故障时重建（从 MinIO 的 chunks.json 重放）。

### 2.7 RabbitMQ — 异步解耦

- Exchange `veridex.ingestion`（direct）→ Queue `ingestion.document`（routing key `document.ingest`）
- 死信：Queue 绑定 DLX `veridex.dlx` → DLQ `ingestion.document.dlq`
- **Outbox 模式**：业务事务内先持久化 `outbox_event`，提交后再尝试投递；发送失败会保留未发布记录和错误信息，避免业务数据已提交却没有可恢复的事件记录。当前阶段由提交事件触发投递，定时扫描和启动恢复将在后续增强

---

## 3. 向量数据最终存在哪里？

**OpenSearch 的 `embedding` 字段（knn_vector，128 维 float 数组）**。

写入路径：手动发布时，`KnowledgeBasePublishService` 对每个 chunk 调 `EmbeddingModel.embed(text)` 得到 `float[128]` → bulk 写入新快照索引的 `embedding` 字段。

```text
chunk.text ──> DeterministicEmbeddingModel ──> float[128] ──> OpenSearch index.embedding
```

要点：
- 向量**不落 PostgreSQL**，也不落 MinIO（MinIO 只存文本 chunk 列表，可用于重建索引）
- 索引按知识库和版本隔离（默认 `veridex-{kbId}-{N}`，前缀可配置），alias 切换决定哪个版本对外可见

---

## 4. 页面上的「索引发布」是什么？

对应 `index_release` 表 + `KnowledgeBasePublishService` 工作流，语义是：**「把当前知识库的全部就绪文档，固化为一个可切换的检索快照」**。

### 概念
- **Index（索引）**：`veridex-{kbId}-1`、`veridex-{kbId}-2`…每个知识库的发布版本一个，不可变，装该版本全部 chunk
- **Alias（别名）**：`veridex-{kbId}-active`，指向当前对外可用的索引。查询只打 alias，不关心具体 index
- **Snapshot（快照）**：一次发布 = 一个不可变索引，包含发布时该知识库全部 READY 文档版本的 chunk；快照文档清单记录在 `index_release_document` 表

### 生命周期（发布由知识管理员手动触发；页面提供设为当前、下架和删除操作）

状态：`DRAFT → PUBLISHED → OFFLINE`；「当前检索」由 `is_active` 唯一决定（同一知识库至多一个）。

| 操作 | 行为 |
|---|---|
| **发布 publish**（手动） | 列出全部 READY 文档版本 → 建 index → 写入全部 chunk → alias 原子切换 → PUBLISHED + 标记为当前检索（is_active） |
| **设为当前 make-current** | 对任意非当前快照（历史版本或已下架）：alias 原子切到它 → 原当前降级、它成为当前检索；已下架的自动恢复为 PUBLISHED |
| **下架 offline** | 移除 alias（当前快照立即从检索中消失，但 index 保留）→ OFFLINE |
| **删除 delete** | 删除 index（幂等）→ 记录删除；当前检索快照需先下架才能删除 |

### 为什么要这一层？
- **多文档同时可检索**：一次发布覆盖知识库全部就绪文档，而非单文档；PROCESSING/FAILED 文档不进入快照（页面提示「本次发布未包含 X 个文档」）
- **不可变性**：快照一旦发布，其内容不可变 → 检索结果可复现、可审计
- **原子切换**：alias 一次性指向新快照，新旧切换无窗口期，**失败的发布不会影响已发布的旧快照**
- **离线即排除**：紧急下架某快照，offline 后查询立刻查不到——这正是出口门禁验证过的场景
- **明确的当前版本**：`is_active` 标记唯一标示「当前检索」快照，页面高亮显示；其余历史快照显示「历史版本/已下架」
- **空态保护**：当没有任何「当前检索」快照（如全部下架）时，列表顶部显示「当前无检索版本，点击发布恢复」引导

页面「索引发布」列表展示的 `v1/v2…` + 状态（当前检索/历史版本/已下架）+ 文档/chunk 统计 + index 名，就是这个工作流的外在表现；「发布/设为当前/下架/删除」按钮直接调用上述操作。

---

## 5. 一次完整上传 + 发布的端到端时序

```text
用户上传 intro.md
  │
  ▼
[同步] 校验白名单/大小/权限 → SHA-256 → 原文件存 MinIO
      → document_version(UPLOADED) → outbox 记事件 → 返回 202
  │
  ▼ [事务提交后]
OutboxPublisher 投递 → RabbitMQ(ingestion.document)
  │
  ▼ [异步]
Worker: 状态→PROCESSING
  ① 从 MinIO 取原文件
  ② Tika 解析 → 纯文本
  ③ 结构分块 → 若干 Chunk
  ④ parsed.json / chunks.json 写回 MinIO（预览数据源）
  ⑤ markReady（状态 READY + 审计 + ack）→ 不自动发布
  │
  ▼ [知识管理员手动]
知识管理页点击「发布」→ POST /api/knowledge-bases/{kbId}/releases/publish
  ① 列出该知识库全部 READY 文档版本（每文档取最新版本）
  ② 建 release 草稿 → OpenSearch 建索引 veridex-{kbId}-{N+1}
  ③ 逐文档读 chunks.json → 逐 chunk 生成向量 → bulk 写入 index（_id 幂等）
  ④ setStats(documentCount, chunkCount) → publish（alias 原子切换）→ is_active=true
  ⑤ 记录快照文档清单（index_release_document）+ 审计
  │
  ▼
前端看到：版本 vN+1 状态「已发布」+「当前检索」角标；旧快照变为「历史版本」
```

---

## 6. 数据流向速查表

| 数据 | 存哪 | 谁写 | 用途 |
|---|---|---|---|
| 原始文件 | MinIO | 上传 API | 解析、重处理、下载 |
| 解析全文 | MinIO（parsed.json） | worker | 前端预览 |
| 分块列表 | MinIO（chunks.json） | worker | 前端预览、索引重建 |
| 向量 | OpenSearch（embedding 字段） | 手动发布（KnowledgeBasePublishService） | Phase 3 语义检索 |
| 全文（BM25） | OpenSearch（text 字段） | 手动发布（KnowledgeBasePublishService） | Phase 3 关键词检索 |
| 状态/元数据/版本/授权 | PostgreSQL | 各服务 | 业务事实源 |
| 索引发布记录 | PostgreSQL（index_release） | KnowledgeBasePublishService | 发布/回滚/离线控制 |
| 待投递事件 | PostgreSQL（outbox_event） | 上传 API | 可靠消息投递 |
| 审计日志 | PostgreSQL（audit_event） | 上传/worker | 合规追踪 |

---

## 7. 当前限制与后续（Phase 3+）

- **确定性 embedding 无语义**：当前向量仅供管道打通与流程验证；真实 RAG 检索前需按 `veridex.embedding.provider` 接入语义模型，且 OpenSearch 维度需同步调整
- **纯文本分块启发式待增强**：DOCX/PDF 无 Markdown 标题时按段落聚合，后续可加标题/编号启发式
- **重试策略**：目前失败进 DLQ（人工处置），自动重试与退避是后续增强点
- **查询侧**：Phase 3 将基于 alias 实现「BM25 + 向量混合检索 → 排序融合 → 带引用答案」，复用本管道产出的索引与 release 语义

---

## 附：相关代码位置

| 环节 | 文件 |
|---|---|
| 上传入口 | `backend/.../knowledge/api/DocumentController.java`、`DocumentUploadHandler.java` |
| 解析 | `backend/.../ingestion/infrastructure/TikaDocumentParser.java` |
| 分块 | `backend/.../ingestion/domain/StructureFirstChunker.java` |
| 向量 | `backend/.../shared/infrastructure/embedding/DeterministicEmbeddingModel.java` |
| 索引网关 | `backend/.../indexing/infrastructure/OpenSearchIndexGateway.java` |
| 发布工作流 | `backend/.../indexing/infrastructure/IndexReleaseService.java`、`domain/IndexRelease.java` |
| worker | `backend/.../ingestion/infrastructure/DocumentIngestionWorker.java` |
| 发件箱 | `backend/.../shared/outbox/*` |
| 迁移 | `backend/src/main/resources/db/migration/V1..V4__*.sql` |
| 端到端验证 | `backend/src/test/java/io/veridex/ingestion/IngestionExitGateTest.java` |

