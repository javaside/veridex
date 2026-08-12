# 知识库全量索引发布 — 设计文档

> 日期：2026-08-12
> 状态：已与产品负责人逐节确认

## 1. 背景与问题

### 1.1 现状

Phase 2 把「索引发布」实现为**文档版本级**：

- worker 每处理一条消息（一次上传 → 一个 `documentVersionId`）就触发一次完整发布：`createDraft → prepare → 写 chunk → publish`；
- `IndexRelease` 绑定单个 `documentVersionId`；
- `publish` 时 `aliasTo` 先 `remove("*")` 摘掉旧 alias，再 `add(新索引)`。

### 1.2 由此产生的两个真实缺陷

1. **多文档被顶掉**：一个知识库上传多份文档时，每传一份就发布一次并切走 alias，「当前可检索」始终只有**最后上传的那一份**。数据库里多份文档都在，但检索范围只有一份——不符合「知识库 = 多文档可检索集合」的预期，也与平台设计文档「查看当前索引发布版本」的知识库级概念不符。
2. **无法区分当前生效版本**：`ReleaseView` 只有 `status`，`publish` 新版本不会把旧的改成其它状态，DB 里 v1~v4 全是 `PUBLISHED`。谁真正被 alias 指向只存在于 OpenSearch 中且未暴露，页面无法判断哪条是当前检索生效的。

### 1.3 范围

本次只改**索引发布语义层**（发布粒度、active 标记、前端列表呈现、数据迁移）。**不涉及**：

- Phase 3 检索查询（BM25/向量混合检索、权限过滤的查询侧代码尚未实现，届时基于本设计落地）；
- 文档解析 / 分块 / embedding / 上传链路（保持不变）。

## 2. 已确认的设计决策

| # | 决策 | 说明 |
|---|---|---|
| D1 | **手动发布** | 知识管理员整理好一批文档后点击「发布」，把当前知识库全部就绪文档固化为一个不可变快照（新索引 + 原子切 alias）。多文档一次生效，整库可回滚。 |
| D2 | **变更等发布，下架立即** | 新增/更新/删除文档只改变「待发布」状态，下次手动发布时全量快照生效；紧急下架（offline）例外——检索时按文档状态实时过滤，立即从结果排除，不破坏已发布快照的不可变性。 |
| D3 | **发布只含就绪文档** | 发布快照 = 当前所有 READY 文档版本；PROCESSING/FAILED 文档不进入本次快照，页面明确提示「本次发布未包含 X 个文档」。 |
| D4 | **方案 A：单快照索引 + alias** | 每次发布全量重建该知识库一个不可变索引 → alias 原子切换；检索查 alias 指向的索引 + 状态/权限过滤。与设计文档「当前索引发布版本」概念吻合。 |

## 3. 领域模型变化

### 3.1 `IndexRelease`（表 `index_release`）

- **保留**：`knowledgeBaseId`、`versionNo`、`indexName`、`aliasName`、`status`、`createdAt`、`publishedAt`
- **删除**：`documentVersionId`（不再一个发布绑定一个文档）
- **新增**：
  - `documentCount`：快照包含的文档版本数
  - `chunkCount`：快照总 chunk 数
- 状态机 `DRAFT → PUBLISHED → (ROLLED_BACK | OFFLINE)` 保留，语义改为**知识库快照级**：
  - `PUBLISHED` = 当前或历史上某个已上线的知识库全量快照；**同一时刻只有一个 PUBLISHED 被 alias 指向（active）**
  - `ROLLED_BACK` = 曾被上线、后被回滚（内容仍在，可重新发布）
  - `OFFLINE` = 曾上线、被手动下架（alias 移除，内容仍在，可恢复）

### 3.2 新表 `index_release_document`

记录该快照**包含哪些文档版本**（`releaseId + documentVersionId`）：

- 让「该知识库当前上线了哪几份文档」可查、可审计；
- 支持对比两个快照的文档差异；
- 同一文档可出现在多个快照中，每个快照记录「当时引用的文档版本」（如快照 N 引用 A.v3 + B.v1；快照 N+1 引用 A.v4 + B.v1 + C.v1）。

### 3.3 知识库级「当前生效」标记

新增 `knowledge_base.current_release_id`（或等价字段）：

- `publish / rollback / offline` 时同步更新「当前生效版本」；
- `listReleases` 据此计算 `isActive`，**不依赖运行时查询 OpenSearch**；
- 与 OpenSearch alias 状态的一致性由上述写路径保证，并以集成测试验证。

## 4. 发布流程

**新增知识库级「发布」动作**（前端按钮 + 后端 API）：

```
1. 列出知识库当前全部 READY 文档版本（处理完成、未被删除、未离线）
2. 若有 PROCESSING/FAILED 文档 → 快照仍创建，页面提示「本次发布未包含 X 个文档」
3. 建新索引 veridex-{kbId}-{N+1}（不可变）
4. 写入这些文档版本的全部 chunk（_id = documentVersionId:chunkIndex，幂等覆盖写）
5. 原子切 alias → 新索引，状态 PUBLISHED
6. 记录快照文档清单（index_release_document）+ documentCount + chunkCount + 审计
7. 更新 knowledge_base.current_release_id
```

**幂等与失败**：发布失败 → 丢弃新索引（复用 `discardDraft`），alias 仍指向旧的；重复点击/重试不产生重复 chunk（`_id` 幂等 + 状态机校验）。

## 5. 检索语义

```
当前可检索 = alias 指向的快照索引，且满足：
  - 权限过滤：用户对知识库有 VIEW 权限（Phase 3 落地查询侧）
  - 状态过滤：文档未被 OFFLINE（紧急下架立即生效，检索时按 document_version 状态实时过滤）
  - 版本过滤：只含快照清单（index_release_document）里记录的文档版本
```

- **历史快照语义**：v1/v2/v3 都是「历史快照」，只有被 alias 指向的是「当前生效」；其余显示「已发布」但未被当前检索命中。
- **回滚语义修正**：回滚 = alias 切回**上一个知识库全量快照**（而非「上一份文档」）。

## 6. 回滚 / 删除 / 下架

| 操作 | 行为（快照语义） | 前端呈现 |
|---|---|---|
| **回滚 rollback** | 仅当前 active 的 PUBLISHED 可回滚：alias 切回上一个 PUBLISHED 快照（无则摘 alias）→ `ROLLED_BACK`，索引保留 | 「回滚」按钮仅出现在当前 active 行 |
| **下架 offline** | 移除 alias（该快照立即从检索消失，索引保留）→ `OFFLINE`；检索按文档状态实时排除 | 「离线」按钮仅出现在当前 active 行 |
| **删除 delete** | 物理删除索引 + 记录（幂等）；**当前 active 快照须先离线/回滚再删除**（阻止删除 active，避免检索变空） | 删除按钮对所有行可见，active 行禁用并提示 |

## 7. 前端 UI 变化

- 知识库工作区新增「发布」主按钮（知识库级）；点击前展示将包含的 READY 文档清单、未就绪文档提示、确认发布；
- 「索引发布」列表按新语义呈现：
  - 当前 active 行标「当前检索」角标；
  - 其余行显示「历史版本 / 已回滚 / 已离线」；
  - 行内展示 `X 份文档 / Y chunks` 统计；
  - 回滚/离线仅对当前 active 行；删除对全部行（active 行禁用并提示）；
- 文档列表保持不变（处理状态/预览），增加「已进入哪个快照 / 待下次发布」提示；
- 空态/错误态沿用现有风格；无 READY 文档时禁用发布。

## 8. 数据迁移策略

**目标**：不丢历史、不断服务，把现有「文档级发布」平滑升级到「知识库快照发布」，无需重建索引即可继续检索。

1. 迁移 SQL：`index_release` 加列（`document_count`、`chunk_count`），新增表 `index_release_document`，新增 `knowledge_base.current_release_id`；
2. **写迁移**：对每个知识库，把当前 alias 指向的 release 标记为 active，并在 `index_release_document` 补一条该 release 对应的文档记录（旧 release 只绑定单文档，清单如实记录为那一个文档，不虚构）；
3. 旧 v1~v4 **不重建**：作为「历史快照」存在，alias 仍指向 v4 → 当前检索范围不变，服务不中断；
4. 迁移 SQL 需**幂等**（可重复执行）；迁移失败 → 回滚代码 + SQL，旧逻辑照常工作。

## 9. 测试影响

**后端**：

- `IndexReleaseServiceTest`：改为知识库全量快照语义（发布含 READY、失败不切 alias、回滚切回上一快照、active 标记更新）；
- `IngestionExitGateTest`：语义对齐（失败新版本不影响旧快照、离线立即排除）；
- 新增：数据迁移测试（幂等、active 标记正确）；手动发布测试（只含 READY、未就绪提示、active 更新）。

**前端**：

- `KnowledgePage.test.tsx`：发布按钮、当前标记、回滚/删除按钮权限断言；
- `ReleaseList` 相关断言更新为新的 active 语义。

## 10. 非目标 / 后续（Phase 3+）

- Phase 3 基于 alias + 快照清单落地检索查询（BM25/向量混合、权限过滤、引用）；
- 超大规模（1-5M chunk）下发布全量重建的性能优化（增量索引、后台重建）属 Phase 5 容量工作；
- 上传链路、解析、分块、embedding 保持不变。
