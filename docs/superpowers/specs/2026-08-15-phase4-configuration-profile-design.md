# Phase 4-b 配置 Profile 版本化 — 设计文档

> 日期：2026-08-15
> 状态：已与用户确认（仅定义不可变配置 / 单一聚合 Profile / 全维度纳入）

## 1. 背景与目标

### 1.1 现状

检索、生成、分块、拒答等参数目前全部是硬编码常量，分散在核心路径中：

| 维度 | 当前硬编码 | 位置 |
|---|---|---|
| chunking | `maxChars=2000`、`overlap=80` | `StructureFirstChunker` |
| retrieval | `topKPerChannel=30`、`rrfK=60`、`contextTopK=6`、`perDocumentMax=3`、`contextMaxChars=4000` | `HybridSearchServiceImpl` |
| generation | `maxHistoryTurns=6`（QA 编排）、`minEvidenceChars=50`（拒答） | `QuestionAnsweringServiceImpl` / `RefusalPolicy` |
| prompt | system 模板写死「你是企业制度问答助手…」 | `GenerationServiceImpl` |
| model | `chatModel="deterministic"`、`embeddingModel="deterministic"` | `GenerationServiceImpl` / `DeterministicEmbeddingModel` |

`trace.generation_run` 已记录 `model` 字符串，但没有 prompt 版本、chunking/retrieval 配置版本等可复现性字段。

### 1.2 本轮目标（Phase 4 子项目 b）

建立 `configuration` 模块，提供**不可变配置版本（Profile）**：知识管理员能定义并版本化一套完整的 RAG 配置（chunking / retrieval / generation / prompt / model），发布后不可变，供后续 P4-d「同一数据集对比两个不可变配置」引用。

### 1.3 范围

- 本轮**只定义 + 版本化 + 查询** Profile，**不接运行时**：硬编码常量保留，检索/生成链路不读取 Profile。
- 五维全量纳入：chunking、retrieval、generation、prompt、model。
- **不涉及**：评测运行（P4-d）、反馈转坏例（P4-c）、版本对比与回归门禁（P4-e）。

## 2. 已确认的设计决策

| # | 决策 | 说明 |
|---|---|---|
| C1 | **仅定义不可变配置** | 本轮不接运行时，Profile 是纯定义 + 版本化 + API；硬编码常量不动。 |
| C2 | **单一聚合 Profile** | 一个 Profile 版本覆盖五维全部字段，`versionNo` 整体递增，直接满足「对比两个不可变配置」。 |
| C3 | **全维度纳入** | chunking / retrieval / generation / prompt / model 五维全部入 Profile，含 system prompt 模板文本与模型标识。 |
| C4 | **版本化语义 = 草稿 + 快照** | Profile 持有可变 `draft`（五维 JSONB）；`publish` 冻结为 `ConfigurationProfileVersion`（`versionNo` 递增）；已发布版本不可改。 |
| C5 | **五维为固定结构 JSONB** | 五维配置是固定结构（非变长列表），草稿与快照共用同一 JSON 结构，存 `jsonb`。 |
| C6 | **模块边界** | 新建 `io.veridex.configuration`，`allowedDependencies = {"shared", "iam::api"}`（CurrentActor）。 |
| C7 | **迁移 V9** | `V9__configuration_profile.sql` 新建 2 张表。 |
| C8 | **API 命名空间** | `/api/configuration/profiles`。 |
| C9 | **权限** | 沿用 `PLATFORM_ADMIN` / `KNOWLEDGE_ADMIN`，controller 内手动校验（同 P4-a）。 |

## 3. 领域模型

```text
ConfigurationProfile 1 ──── * ConfigurationProfileVersion   （不可变，versionNo 递增）
```

- `ConfigurationProfile`：id、name、description、`draft`（五维 JSONB，可变）、createdBy、createdAt、updatedAt。
- `ConfigurationProfileVersion`：id、profileId、versionNo、`config`（五维 JSONB，发布时冻结）、createdBy、createdAt；`(profileId, versionNo)` 唯一。

**五维 JSONB 结构**（草稿与快照共用）：

```json
{
  "chunking": { "maxChars": 2000, "overlap": 80 },
  "retrieval": { "topKPerChannel": 30, "rrfK": 60, "contextTopK": 6, "perDocumentMax": 3, "contextMaxChars": 4000 },
  "generation": { "maxHistoryTurns": 6, "minEvidenceChars": 50 },
  "prompt": { "systemTemplate": "你是企业制度问答助手。只允许使用以下证据回答……" },
  "model": { "chatModel": "deterministic", "embeddingModel": "deterministic" }
}
```

Java record（`configuration.domain`）：

```java
record ChunkingConfig(int maxChars, int overlap)
record RetrievalConfig(int topKPerChannel, int rrfK, int contextTopK, int perDocumentMax, int contextMaxChars)
record GenerationConfig(int maxHistoryTurns, int minEvidenceChars)
record PromptConfig(String systemTemplate)
record ModelConfig(String chatModel, String embeddingModel)
record ProfileConfig(ChunkingConfig chunking, RetrievalConfig retrieval, GenerationConfig generation,
                     PromptConfig prompt, ModelConfig model)
```

内置默认值（`ProfileDefaults`）：取当前硬编码常量作为默认草稿，便于管理员创建后在此基础上修改。

## 4. 数据模型（V9__configuration_profile.sql）

```sql
CREATE TABLE configuration_profile (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(200) NOT NULL,
    description TEXT,
    draft JSONB NOT NULL,
    created_by UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE configuration_profile_version (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    profile_id UUID NOT NULL REFERENCES configuration_profile(id) ON DELETE CASCADE,
    version_no INTEGER NOT NULL,
    config JSONB NOT NULL,
    created_by UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (profile_id, version_no)
);
```

## 5. API（configuration/api）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/configuration/profiles` | 建 Profile `{name, description?, draft?}`；`draft` 缺省用内置默认值 |
| GET | `/api/configuration/profiles` | 列表（含 versionCount、latestVersionNo） |
| GET | `/api/configuration/profiles/{id}` | 详情（含当前 draft） |
| PUT | `/api/configuration/profiles/{id}` | 更新 `{name, description, draft}`（draft 五维必填） |
| POST | `/api/configuration/profiles/{id}/publish` | 冻结 draft 为新的 ConfigurationProfileVersion |
| GET | `/api/configuration/profiles/{id}/versions` | 版本列表 |
| GET | `/api/configuration/profiles/{id}/versions/{versionNo}` | 版本详情（含冻结五维 config） |

DTO：`ProfileView`、`ProfileDetailView`、`VersionView`、`VersionDetailView`、`PublishResult`。发布时若 draft 五维不完整返回 400。

## 6. 版本化语义

- 修改 `draft` 只作用于 `configuration_profile.draft`，不影响任何已发布版本。
- `publish`：事务内读取当前 `draft` → 校验五维完整 → 冻结为 `configuration_profile_version.config` → 写 version（`versionNo = max+1`）。
- 已发布 `ConfigurationProfileVersion` 只读；「当前版本」即最高 `versionNo`。
- `draft` 五维中任一为 null/blank（prompt 模板、模型标识空）则不可发布（400）。

## 7. 前端（/configuration）

- 新增路由 `/configuration`，label「配置版本」、englishLabel「Configuration」，icon `SlidersHorizontal`。
- 列表页：Profile 列表 + 「新建配置」。
- 详情页：五维表单编辑器（chunking / retrieval / generation / prompt / model）+「发布版本」；版本历史 + 版本详情（只读）。
- 新建时用「使用默认值」预填当前硬编码默认配置。

## 8. 测试

- 领域/服务单测：创建（缺省默认值）、更新 draft、发布冻结语义、五维不完整拒绝发布、versionNo 递增。
- `DatabaseMigrationTest` 追加 V9 断言（2 张表存在）。
- 集成测试（Testcontainers Postgres）：`ConfigurationProfileIntegrationTest` 覆盖「发布后改 draft 不影响已发布版本」。
- `ArchitectureTest` 通过（configuration 依赖 shared + iam::api）。
- 前端 Vitest：列表 / 五维编辑 / 发布交互。

## 9. 验收标准

1. 知识管理员能建 Profile、编辑五维 draft、发布不可变版本、列表可见。
2. 发布后修改 draft 不影响已发布版本内容。
3. 五维不完整（含空 prompt 模板 / 空模型标识）的发布被拒绝。
4. `./scripts/verify.sh` 全绿。
