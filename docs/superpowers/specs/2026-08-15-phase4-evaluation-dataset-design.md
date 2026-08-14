# Phase 4-a 评测集与用例 — 设计文档

> 日期：2026-08-15
> 状态：已与用户确认（精确证据粒度：document_version_id + chunk_index[]）

## 1. 背景与目标

### 1.1 现状

- `evaluation` 模块目前只有 `package-info.java`（空占位，边界已在 Phase 2 声明）。
- 反馈（点赞/点踩）后端仅 `QaController.feedback()` 返回 204 占位、前端仅按钮占位；评测集、评测运行、指标、版本对比均未实现。
- `trace` 模块已具备 `QueryRun / RetrievalHit / GenerationRun / Citation` 持久化（Phase 3），是后续评测证据链基础。
- 前端 `/evaluation` 路由为 `ComingSoonPage` 占位（Phase 4）。
- 分块/检索/生成参数目前是硬编码常量（`MAX_CHARS=2000`、`TOP_K_PER_CHANNEL=30`、`RRF_K=60`、模型名写死 deterministic 等），尚无不可变配置版本概念。

### 1.2 本轮目标（Phase 4 子项目 a）

知识管理员能创建**版本化评测集**：在草稿中增删改用例（含 ANSWER/REFUSE 期望 + ground-truth 证据），发布生成**不可变 DatasetVersion**；已发布版本不可原地修改。

### 1.3 范围

- 本轮只做 **EvaluationDataset / DatasetVersion / EvaluationCase** 的建模、版本化、API 与前端。
- **不涉及**：评测运行与指标（P4-d）、反馈转坏例（P4-c）、配置/Profile 版本化（P4-b）、版本对比与回归门禁（P4-e）。
- 证据只存 ID 引用，不复制 chunk 文本；文本在展示/评测时从已发布 chunk manifest 实时读取。

## 2. 已确认的设计决策

| # | 决策 | 说明 |
|---|---|---|
| D1 | **evaluation 模块内新增领域模型** | `EvaluationDataset`（容器）、`EvaluationCase`（可变工作集）、`DatasetVersion` + `DatasetVersionCase`（不可变快照）。 |
| D2 | **版本化语义 = 工作集 + 快照** | 草稿即数据集的当前 case 工作集；`publish` 把全部 case 内容**冻结**进 `DatasetVersionCase`，生成新的 `DatasetVersion`（versionNo 递增）；已发布版本永不可改。 |
| D3 | **ground-truth 精确证据** | 每条 ANSWER 用例存 `evidence` JSONB：`[{documentVersionId, chunkIndex[]}]`，与 `retrieval_hit`/`citation` 的 `document_version_id + chunk_index` 对齐，供 P4-d 直接算 Recall@K/MRR/NDCG。 |
| D4 | **期望行为二选一** | `ANSWER`（含 expectedAnswer + evidence）或 `REFUSE`（evidence 为空，expectedAnswer 可空）。 |
| D5 | **证据只存引用** | 不复制 chunk 文本，避免与文档版本漂移；展示/打分时按 `document_version_id` 读 chunk manifest。 |
| D6 | **模块边界更新** | `evaluation/package-info.java` 的 allowedDependencies 收敛为 `{"shared", "iam::api"}`（CurrentActor）；P4-d 再按需扩展 retrieval/generation/trace/knowledge。 |
| D7 | **迁移 V8** | `V8__evaluation.sql` 新建 4 张表。 |
| D8 | **API 命名空间 + 前端替换** | `/api/evaluation/*`；前端 `/evaluation` 从 ComingSoonPage 替换为评测集管理页。 |
| D9 | **权限** | 沿用现有 `PLATFORM_ADMIN` / `KNOWLEDGE_ADMIN` 角色（METHOD_SECURITY）；本轮不新增独立角色。 |

## 3. 领域模型

```text
EvaluationDataset 1 ──── * EvaluationCase        （可变工作集，草稿）
EvaluationDataset 1 ──── * DatasetVersion        （不可变，versionNo 递增）
DatasetVersion    1 ──── * DatasetVersionCase    （发布时冻结的 case 快照）
```

- `EvaluationDataset`：id、name、description、createdBy、createdAt/updatedAt。
- `EvaluationCase`：id、datasetId、question、expectedBehavior(`ANSWER`/`REFUSE`)、expectedAnswer(可空)、evidence(JSONB)、createdAt/updatedAt。
- `DatasetVersion`：id、datasetId、versionNo、caseCount、createdBy、createdAt；`(datasetId, versionNo)` 唯一。
- `DatasetVersionCase`：id、versionId、position、question、expectedBehavior、expectedAnswer、evidence。

**evidence JSONB 结构**（ANSWER 用例）：
```json
[{"documentVersionId":"<uuid>","chunkIndexes":[3,4]}]
```

## 4. 数据模型（V8__evaluation.sql）

```sql
CREATE TABLE evaluation_dataset (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(200) NOT NULL,
    description TEXT,
    created_by UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE evaluation_case (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    dataset_id UUID NOT NULL REFERENCES evaluation_dataset(id) ON DELETE CASCADE,
    question TEXT NOT NULL,
    expected_behavior VARCHAR(20) NOT NULL,
    expected_answer TEXT,
    evidence JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE dataset_version (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    dataset_id UUID NOT NULL REFERENCES evaluation_dataset(id) ON DELETE CASCADE,
    version_no INTEGER NOT NULL,
    case_count INTEGER NOT NULL DEFAULT 0,
    created_by UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (dataset_id, version_no)
);

CREATE TABLE dataset_version_case (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version_id UUID NOT NULL REFERENCES dataset_version(id) ON DELETE CASCADE,
    position INTEGER NOT NULL,
    question TEXT NOT NULL,
    expected_behavior VARCHAR(20) NOT NULL,
    expected_answer TEXT,
    evidence JSONB NOT NULL DEFAULT '[]'::jsonb
);
```

## 5. API（evaluation/api）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/evaluation/datasets` | 建数据集 `{name, description}` |
| GET | `/api/evaluation/datasets` | 列表（含当前 case 数、最新版本号） |
| GET | `/api/evaluation/datasets/{id}` | 详情 |
| POST | `/api/evaluation/datasets/{id}/cases` | 加用例 `{question, expectedBehavior, expectedAnswer?, evidence[]}` |
| PUT | `/api/evaluation/datasets/{id}/cases/{caseId}` | 改用例 |
| DELETE | `/api/evaluation/datasets/{id}/cases/{caseId}` | 删用例 |
| GET | `/api/evaluation/datasets/{id}/cases` | 工作集用例列表 |
| POST | `/api/evaluation/datasets/{id}/publish` | 冻结当前用例为新的 DatasetVersion |
| GET | `/api/evaluation/datasets/{id}/versions` | 版本列表 |
| GET | `/api/evaluation/datasets/{id}/versions/{versionNo}` | 版本详情（含冻结用例） |

DTO：`DatasetView`、`CaseView`、`VersionView`、`PublishResult`。发布时若工作集为空返回 400；ANSWER 用例若 evidence 为空返回 400（D4 约束）。

## 6. 版本化语义

- 用例增删改只作用于 `evaluation_case`（工作集），不影响任何已发布版本。
- `publish`：事务内取当前全部用例 → 按当前顺序冻结为 `DatasetVersionCase` → 写 `DatasetVersion`（versionNo = max+1，caseCount = N）。
- 已发布 `DatasetVersion`/`DatasetVersionCase` 只读；「当前版本」即最高 `versionNo`。
- 空数据集不可发布（caseCount 必须 ≥1）。

## 7. 前端（/evaluation）

- 列表页：数据集列表 + 「新建数据集」。
- 详情页：左侧用例列表（可增删改）、右侧用例编辑（question、期望行为 ANSWER/REFUSE、期望答案、ground-truth 证据选择）；「发布版本」按钮；版本列表/详情。
- 证据选择：选择已发布文档版本 → 加载 chunk 列表（复用 `GET /api/documents/{id}/versions/{vid}/chunks`）→ 勾选 ground-truth chunk → 生成 `[{documentVersionId, chunkIndexes[]}]`。

## 8. 测试

- 领域/服务单测：用例 CRUD、发布冻结语义、空集不可发布、ANSWER 无证据校验。
- `DatabaseMigrationTest` 追加 V8 断言（4 张表存在）。
- 集成测试（Testcontainers Postgres）：`EvaluationDatasetServiceTest` 覆盖「发布后改工作集不影响已发布版本」、版本号递增。
- `ArchitectureTest` 通过（package-info 收敛为 shared + iam::api）。
- 前端 Vitest：列表/详情/发布交互。

## 9. 验收标准

1. 知识管理员能建数据集、加 N 条用例（ANSWER 含 evidence / REFUSE）、发布不可变版本、列表可见。
2. 发布后修改工作集不影响已发布版本内容。
3. 空数据集、ANSWER 无证据的发布被拒绝。
4. `./scripts/verify.sh` 全绿。

