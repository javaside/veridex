# Phase 3 带权限的 RAG 查询 — 设计文档

> 日期：2026-08-12
> 状态：已与产品负责人逐节确认

## 1. 背景与目标

### 1.1 现状

Phase 2 已完成知识入库垂直切片：知识库 + VIEW/MANAGE 授权、文档上传、异步解析分块、Embedding、不可变快照发布（alias 原子切换 + `is_active` + `index_release_document` 文档清单）。OpenSearch 索引已包含 `text`（BM25）与 `embedding`（knn_vector）字段。

`retrieval`、`generation`、`conversation`、`trace` 四个模块目前为空占位（仅 package-info）。无混合检索、无问答 API、无员工问答页。

### 1.2 本轮目标（Roadmap Phase 3 出口）

授权员工提问 → 收到**流式的、有证据约束的、带合法引用**的回答；未授权员工**无法发现或访问**受保护内容。

### 1.3 范围

- 本轮只做**在线问答链路**：混合检索 → 融合 → 过滤 → 上下文 → 生成 → 引用校验 → SSE。
- **不涉及**：评测闭环（Phase 4）、开放 API / API Key（Phase 5）、完整 Trace 正文存储（Phase 5）、真实模型接入（模型 provider 可插拔，业务代码不变）。
- 后端沿用现有模块化单体；前端新增员工问答页。

## 2. 已确认的设计决策

| # | 决策 | 说明 |
|---|---|---|
| D1 | **平台自建检索编排** | retrieval 模块自建 `HybridSearchService`：直接调 OpenSearch 双路召回 → RRF 融合 → 过滤 → 上下文组装。Spring AI 仅用于 `ChatClient` 模型调用，不拥有检索/权限/引用领域模型（符合设计文档 §8.2）。 |
| D2 | **确定性模型打通链路** | 继续用 `DeterministicEmbeddingModel`（128 维哈希向量，无语义能力）；新增自建 `DeterministicChatModel`（Spring AI `ChatModel` 实现，模板化回答）。无外部依赖、测试可复现。真实模型 = 新增 provider 实现，切换配置即可。 |
| D3 | **有限多轮** | 会话 + 消息落库；生成/检索注入最近 N 轮历史（本轮 N=6）。 |
| D4 | **多选知识库 + 服务端取交集** | 员工可选自己有 VIEW 权限的多个知识库（或全选）；服务端以「授权 ∩ 请求」为准。检索一次查询打多个 alias。 |
| D5 | **RerankProvider 接口 + 直通降级** | 定义平台自有 `RerankProvider` 接口，默认实现返回融合序（直通）；真实 rerank 后续按接口接入。 |
| D6 | **`[n]` 编号引用 + 校验** | Prompt 约定模型输出 `[1][2]` 编号；生成后解析并校验（编号存在 / 属于上下文 / 仍有权限 / 文档未下线）；前端可点击定位原文。 |
| D7 | **拒答是正常业务结果** | 内部原因枚举；`ACCESS_RESTRICTED` 对普通用户统一显示「当前可访问知识范围内证据不足」，不泄露无权限内容存在性。 |
| D8 | **正文不落库** | query_run 只存问题与元数据；prompt、回答全文、检索正文默认不持久化（受控 Trace 正文存储属 Phase 5）。 |
| D9 | **含员工问答页** | 新路由 `/qa`：知识库多选 + SSE 流式回答 + 引用点击定位（复用 PreviewDrawer 数据源）+ 反馈占位。 |
| D10 | **SSE 用 Spring MVC `SseEmitter`** | 贴合现有阻塞栈，不引入 WebFlux。 |

## 3. 在线问答链路

```
员工提交 { 问题, 知识库[], 会话id }
  → 认证 + 知识范围计算（授权 VIEW ∩ 请求库，服务端取交集）
  → 创建 QueryRun（RECEIVED）
  → 历史注入（最近 N 轮）
  → 双路召回：BM25 关键词 + kNN 向量（多 alias 一次 _search，各 Top K）
  → RRF 融合（基于排名，跨索引可比）→ 去重
  → 过滤：knowledge_base_id ∈ 授权交集 + document_version_id ∈ 快照在线清单
  → RerankProvider（本轮直通）
  → 上下文组装（Token 预算、来源编号 [1][2]…）
  → 生成：DeterministicChatModel
  → 引用校验（编号存在/属于上下文/权限/未下线）
  → SSE 流式输出 → QueryRun COMPLETED / REFUSED / FAILED / CANCELLED
```

**多库检索**：一次查询打多个 alias（`alias1,alias2/_search`）。BM25 与 kNN 各自基于**排名**用 RRF 融合，规避跨索引分数不可比。同 chunk 双路命中去重（保留较高融合分）。

**权限/状态过滤不依赖 OpenSearch**：`document_version_id ∈ 快照在线清单`（来自 PostgreSQL，快照内非 OFFLINE 的在线版本）——避免权限缓存一致性问题，与「PostgreSQL 是事实源」一致。在线版本清单量级 = 单知识库当前在线版本数，可控。

**检索质量为 0 或低于阈值 → 直接拒答**（不调生成），避免 stub 模型硬编答案。

## 4. 领域模型（V7 迁移新增表）

| 表 | 关键字段 | 说明 |
|---|---|---|
| `query_run` | user_id、conversation_id、knowledge_scope（多库 JSON）、question（原+规范化）、status、refusal_reason、error | 一次完整执行；状态机 `RECEIVED→RETRIEVING→RERANKING→GENERATING→VALIDATING→COMPLETED/REFUSED/FAILED/CANCELLED` |
| `retrieval_hit` | query_run_id、document_version_id、chunk_index、knowledge_base_id、channel(BM25/VECTOR)、bm25_score、vector_score、fusion_score、rank、entered_context、filter_reason | 召回与排序全记录，可回放 |
| `generation_run` | query_run_id、model、input/output_tokens、duration_ms、degradation、context_hash | 生成侧记录（正文不存） |
| `citation` | query_run_id、citation_index、document_version_id、chunk_index、source_location、citation_text、validation_status | 引用与校验状态 |
| `conversation` | user_id、title、created_at、updated_at | 会话 |
| `message` | conversation_id、role(USER/ASSISTANT/SYSTEM)、content、query_run_id | 消息；content 存回答全文（会话展示必需），问题存原文 |

**正文不落库的范围界定**：`message.content` 存回答全文（前端会话展示必需，属「会话」而非「Trace 正文」）；`query_run` 不存 prompt/检索正文；`retrieval_hit` 不存 chunk 正文（引用原文经 MinIO 按需读取，受权限检查）。

## 5. 检索细节

### 5.1 双路召回

- **BM25 路**：`match` 查询打在 `text` 字段；
- **kNN 路**：`knn` 查询打在 `embedding` 字段（维度 128 与现有索引一致）；
- 每路 Top 30（对齐设计文档 §11.3）；返回 `_id`、`_score`、`document_version_id`、`knowledge_base_id`、`chunk_index`、`title`、`structure_path`。

### 5.2 融合与过滤

- **RRF**：`k=60`，按排名融合 BM25 + kNN；
- **去重**：同 chunk 双路命中保留较高融合分；
- **过滤**：`knowledge_base_id IN (授权交集)` + `document_version_id IN (快照在线清单)`；
- **RerankProvider**：接口 + 默认直通实现（返回融合序）；超时/未接入时退化为此实现（对齐设计文档 §15.1「Rerank 超时退回融合排序」）。

### 5.3 上下文组装

- 按融合分取 Top K（默认 6，可配）；单文档最多 N 条（默认 3，来源多样性）；
- Token 预算裁剪：本轮以字符数近似 token，超限截断；
- 每个证据编号 `[1][2]…`，随上下文传给模型，构成引用编号合法范围。

## 6. DeterministicChatModel（生成）

- 实现 Spring AI `ChatModel` 接口（`call` / `stream`），无外部依赖、测试可复现；
- 行为：从上下文中提取证据，按 Prompt 约定的 `[n]` 格式生成模板化回答；拒答场景输出固定拒答标记；
- 定位：验证链路（检索→融合→上下文→生成→引用校验→SSE），非高质量答案；真实模型 = 新增 provider 实现 `ChatModel`，切换配置，业务代码不变。

## 7. 拒答与引用校验

### 7.1 拒答原因

`NO_RELEVANT_EVIDENCE` / `INSUFFICIENT_EVIDENCE` / `OUT_OF_SCOPE` / `CONFLICTING_EVIDENCE` / `CONTENT_NOT_EFFECTIVE` / `ACCESS_RESTRICTED` / `SAFETY_POLICY`

- 普通用户从 `ACCESS_RESTRICTED` 只能看到「当前可访问知识范围内证据不足」；
- 拒答是正常业务结果，非系统失败（对齐设计文档 §11.6）。

### 7.2 引用校验（生成后）

1. 编号是否存在（∈ 上下文编号范围）；
2. 编号对应 chunk 是否属于本次上下文；
3. 用户对该 chunk 的知识库仍有 VIEW 权限；
4. 文档未在生成期间紧急下线。

任一失败 → 保守回答 / 有限重试 / 拒答（按严重度）。校验结果写入 `citation.validation_status`。

## 8. SSE 协议

事件（对齐设计文档 §11.7）：

```
run.started
retrieval.completed
answer.delta        (流式)
citation.available
answer.completed    /  answer.refused (+ 拒答原因)  /  run.failed
```

- 实现：Spring MVC `SseEmitter`；
- 客户端断开 → 终止下游模型调用 → QueryRun = CANCELLED，记录已发生 Token。

## 9. 前端员工问答页（`/qa`）

- 知识库多选（列出用户有 VIEW 权限的库，可全选）→ 提问框；
- 会话：左侧最近会话列表 + 右侧对话区；有限多轮（最近 6 轮注入历史）；
- SSE 流式渲染 `answer.delta`；引用编号 `[n]` 可点击 → 打开原文预览（复用 PreviewDrawer 数据源：MinIO parsed.json / chunks.json）；
- 拒答状态显示拒答提示 + 原因文案（ACCESS_RESTRICTED 用通用文案）；
- 点赞/点踩：本轮做**前端占位 + 落库 QueryRun 关联**（表已建），完整坏例转评测闭环属 Phase 4；
- 视觉沿用现有 console 体系。

## 10. 模块划分

| 模块 | 职责 | 关键类（新增） |
|---|---|---|
| `retrieval` | 混合检索、融合、过滤、上下文组装、RerankProvider | `HybridSearchService`、`RankFusion`、`ContextAssemblyService`、`RerankProvider`(+直通实现) |
| `generation` | 生成编排、引用校验、拒答判定 | `GenerationService`、`CitationValidator`、`RefusalPolicy`、`DeterministicChatModel` |
| `conversation` | 会话/消息持久化、历史注入 | `ConversationService`、`ConversationRepository`、`MessageRepository` |
| `trace` | QueryRun / RetrievalHit / GenerationRun / Citation 持久化 | `QueryRunRepository`、`RetrievalHitRepository`、`GenerationRunRepository`、`CitationRepository` |
| `qa`（新 API 层，挂 conversation 下或独立） | 问答 API + SSE | `QaController`（POST 问答、GET 会话历史） |
| `iam` | 当前用户上下文（actor/role） | `CurrentActor`、`SecurityContextRole`（已有） |
| `knowledge` | 知识范围计算（授权 VIEW 交集） | `KnowledgeScope`（新增，复用 `KnowledgeBaseService.listViewable` + grant 模型；因 iam 不能依赖 knowledge grant，本能力归属 knowledge） |
| `indexing` | 暴露当前 alias 查询能力（只读） | 复用现有 `IndexReleaseService` |

模块依赖遵循现有 Spring Modulith 边界，新增跨模块调用需 ArchitectureTest 放行并注释理由。

## 11. 测试策略

**单测**：
- RRF 融合、去重、权限交集计算、Token 预算裁剪、引用校验规则（编号不存在/越权/下线）、拒答判定。

**集成测试**（Testcontainers 全家桶 + DeterministicEmbeddingModel）：
- 双路召回 → 融合 → 过滤 → 回答 → 引用校验全链路；
- 多知识库授权交集：未授权库召回数 = 0（**ACL 门禁**）；
- 检索质量为 0 → 拒答（不调生成）；
- 文档紧急下线 → 引用校验失败处理；
- 客户端断开 → QueryRun CANCELLED。

**前端 Vitest**：
- 知识库多选、SSE 流式渲染、引用点击定位、拒答展示。

**退出门禁**：
- 非拒答答案必须有合法引用；
- 越权库召回为 0；
- 双路检索失败 → 报系统错误（不伪装无答案）。

## 12. 非目标 / 后续（Phase 4/5）

- 评测与坏例闭环、反馈转评测集（Phase 4）；
- 开放 API / API Key / 限流（Phase 5）；
- 受控 Trace 正文存储与保留期（Phase 5）；
- 真实 Embedding / Chat / Rerank 模型接入（任何阶段，按 provider 接口替换）；
- 查询改写、多轮指代消解、术语词典（Phase 4+ 候选，见设计文档 §22）。
