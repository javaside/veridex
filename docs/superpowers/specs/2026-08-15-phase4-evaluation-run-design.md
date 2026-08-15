# Phase 4-d 评测运行与指标 — 设计文档

> 日期：2026-08-15
> 状态：已与用户确认（数值+prompt 真参数化 / 同步运行 / 仅自动指标 / 扩展 evaluation 模块）

## 1. 背景与目标

### 1.1 现状

- P4-a 已建立版本化评测集：`DatasetVersion`（不可变快照）+ `DatasetVersionCase`（冻结用例，含 question / expectedBehavior / expectedAnswer / evidence）。
- P4-b 已建立配置 Profile：`ConfigurationProfileVersion`（不可变快照，五维 `ProfileConfig`）。
- P4-c 已建立反馈转坏例闭环。
- 检索 `HybridSearchServiceImpl`、生成 `GenerationServiceImpl`、拒答 `RefusalPolicy` 的参数目前是硬编码常量，无法用不同配置跑出不同结果。

### 1.2 本轮目标（Phase 4 子项目 d）

知识管理员能对一个已发布的 `DatasetVersion` 发起**同步评测运行**，指定一个已发布的 `ConfigurationProfileVersion` 与知识库范围，对每个 case 执行「检索 → 生成」，计算自动指标，并持久化运行与逐用例结果。

### 1.3 范围

- 自动指标：Recall@K、MRR、NDCG、citation hit、refusal 匹配、latency。
- 检索/生成的数值参数 + prompt 模板**真正参数化**驱动评测；`chatModel`/`embeddingModel` 仅记录到结果（当前只有单一模型实现，不做模型路由）。
- **不涉及**：judge-model 自动评判、人工评审 PASS/MINOR_ISSUE/FAIL、版本对比与回归门禁（P4-e）。

## 2. 已确认的设计决策

| # | 决策 | 说明 |
|---|---|---|
| E1 | **数值 + prompt 真参数化** | 检索 topK/rrfK/contextTopK/perDocumentMax/contextMaxChars、生成 minEvidenceChars/systemTemplate 由 ProfileConfig 驱动；model 标识仅记录。 |
| E2 | **同步运行** | `POST /runs` 同步执行全部 case 后返回结果；不做异步队列。 |
| E3 | **仅自动指标** | Recall@K / MRR / NDCG / citation hit / refusal 匹配 / latency。 |
| E4 | **扩展 evaluation 模块** | `EvaluationRun` 放 evaluation 模块，边界放宽到 `retrieval::api` / `generation::api` / `configuration::api`。 |
| E5 | **参数化接口** | retrieval 新增 `RetrievalParameters`，generation 新增 `GenerationParameters`；各自模块定义自己的参数 record，不 import configuration 类型。 |
| E6 | **configuration 公开值对象** | 把 `ProfileConfig` 及 5 个子 record 移到 `configuration.api` 包，新增 `@NamedInterface("api")` 与只读门面 `ConfigurationProfileQuery`。 |
| E7 | **迁移 V11** | `evaluation_run` + `evaluation_run_case` 2 张表。 |
| E8 | **权限** | 发起/查看评测 = `PLATFORM_ADMIN`/`KNOWLEDGE_ADMIN`。 |
| E9 | **知识范围** | 评测直接使用管理员指定的知识库 id 列表作为检索范围（平台级操作，不套普通用户授权过滤）。 |

## 3. 参数化接口

### 3.1 retrieval.api.RetrievalParameters

```java
public record RetrievalParameters(int topKPerChannel, int rrfK, int contextTopK,
                                  int perDocumentMax, int contextMaxChars) {}
```

`HybridSearchService` 新增重载：

```java
HybridSearchResult search(UUID userId, List<UUID> authorizedKnowledgeBaseIds,
                          List<UUID> requestedKnowledgeBaseIds, String question,
                          RetrievalParameters parameters);
```

原有 `search(...)` 方法保留（在线路径），委托给参数化方法并传入默认参数（当前硬编码值）。

### 3.2 generation.api.GenerationParameters

```java
public record GenerationParameters(int minEvidenceChars, String systemTemplate, String model) {}
```

`GenerationService` 新增重载：

```java
GenerationResult generate(String question, List<EvidencePiece> evidence,
                          List<MessageRecord> history, GenerationParameters parameters);
```

原有 `generate(...)` 保留（在线路径），委托给参数化方法并传入默认参数。

> 说明：`maxHistoryTurns` 属于 QA 编排层（`QuestionAnsweringServiceImpl`），评测每个 case 是独立无历史问答，故不进入 generation 参数；仅记录到 run 的 profile 快照。

## 4. 领域模型

```text
EvaluationRun 1 ──── * EvaluationRunCase
```

- `EvaluationRun`：id、datasetId、datasetVersionId、profileId、profileVersionId、knowledgeScope(JSONB)、status(`COMPLETED`/`FAILED`)、metrics(JSONB 聚合)、createdBy、createdAt、completedAt。
- `EvaluationRunCase`：id、runId、casePosition、question、expectedBehavior(`ANSWER`/`REFUSE`)、groundTruthEvidence(JSONB)、actualBehavior(`ANSWER`/`REFUSE`)、answer(可空)、citations(JSONB)、retrievedChunks(JSONB)、metrics(JSONB 单用例)。

**evidence JSONB**（沿用 P4-a 结构）：`[{"documentVersionId":"<uuid>","chunkIndexes":[3,4]}]`

**retrievedChunks JSONB**：`[{documentVersionId, chunkIndex, rank, score}]`（融合命中的前 K，供指标与展示）。

**citations JSONB**：`[{citationIndex, documentVersionId, chunkIndex, validationStatus}]`。

## 5. 指标定义

ground-truth chunk 集合 `G = {(documentVersionId, chunkIndex)}`；检索融合命中序列 `H`（按 rank 升序）。

- **Recall@K**（K ∈ {1,3,5}）：`|G ∩ H[:K]| / |G|`。
- **MRR**：`max over g∈G of (1 / rank_of_first_g_in_H)`，无命中为 0。
- **NDCG@K**（K=10）：`DCG@K / IDCG@K`，相关性 = binary（chunk ∈ G）。
- **citation hit**：生成 `GenerationResult.citations` 中 VALID 且 chunk ∈ G 的数量 / |G|。
- **refusal match**：expectedBehavior 与 actualBehavior 一致 → 1，否则 0。
- **latency**：检索耗时 + 生成耗时（ms）。

聚合指标（run 级）：各数值指标在全部 case 上的均值、refusalMatchRate、citationHitRate、avgLatencyMs、caseCount。

## 6. 数据模型（V11__evaluation_run.sql）

```sql
CREATE TABLE evaluation_run (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    dataset_id UUID NOT NULL REFERENCES evaluation_dataset(id) ON DELETE CASCADE,
    dataset_version_id UUID NOT NULL REFERENCES dataset_version(id) ON DELETE CASCADE,
    profile_id UUID NOT NULL,
    profile_version_id UUID NOT NULL,
    knowledge_scope JSONB NOT NULL DEFAULT '[]'::jsonb,
    status VARCHAR(20) NOT NULL,
    metrics JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_by UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ
);

CREATE TABLE evaluation_run_case (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id UUID NOT NULL REFERENCES evaluation_run(id) ON DELETE CASCADE,
    case_position INTEGER NOT NULL,
    question TEXT NOT NULL,
    expected_behavior VARCHAR(20) NOT NULL,
    ground_truth_evidence JSONB NOT NULL DEFAULT '[]'::jsonb,
    actual_behavior VARCHAR(20) NOT NULL,
    answer TEXT,
    citations JSONB NOT NULL DEFAULT '[]'::jsonb,
    retrieved_chunks JSONB NOT NULL DEFAULT '[]'::jsonb,
    metrics JSONB NOT NULL DEFAULT '{}'::jsonb
);
```

> `profile_id`/`profile_version_id` 无外键（configuration 与 evaluation 保持模块级弱耦合，仅存 id 引用）。

## 7. API（evaluation/api）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/evaluation/runs` | 发起评测 `{datasetId, datasetVersionNo, profileId, profileVersionNo, knowledgeBaseIds[]}` |
| GET | `/api/evaluation/runs?datasetId=` | 运行列表 |
| GET | `/api/evaluation/runs/{id}` | 运行详情（聚合指标 + case 明细） |

DTO：`StartRunRequest`、`RunView`、`RunDetailView`、`RunCaseView`、`RunMetricsView`。

## 8. 模块边界变更

- `configuration.api` 新增 `@NamedInterface(name="api")`；`ProfileConfig` 等 6 个 record 移到 `configuration.api`；新增 `ConfigurationProfileQuery` 门面：
  - `ProfileConfig requireVersionConfig(UUID profileId, int versionNo)`
- `evaluation/package-info.java`：`allowedDependencies = {"shared", "iam::api", "retrieval::api", "generation::api", "configuration::api"}`。

## 9. 测试

- `MetricsCalculator` 单元测试（Recall/MRR/NDCG/citation hit/refusal match 边界）。
- retrieval/generation 参数化重载的单测（参数生效、默认委托）。
- `EvaluationRunService` 单元测试（mock 检索/生成，验证编排与聚合）。
- `DatabaseMigrationTest` 追加 V11 断言。
- `EvaluationRunIntegrationTest`（Testcontainers，真实检索/生成，最小数据集）。
- `ArchitectureTest` 通过（新边界）。
- 前端 Vitest：发起评测 + 结果展示。

## 10. 验收标准

1. 管理员能对已发布 DatasetVersion 发起评测（指定 ProfileVersion + 知识库），同步返回聚合指标。
2. 不同 Profile 的 topK / 上下文预算 / prompt 模板产生不同的召回/生成结果。
3. 逐用例指标含 Recall@K、MRR、NDCG、citation hit、refusal match、latency。
4. `./scripts/verify.sh` 全绿。
