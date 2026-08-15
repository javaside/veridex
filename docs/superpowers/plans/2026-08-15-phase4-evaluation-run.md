# Phase 4-d 评测运行与指标 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 管理员对已发布 DatasetVersion 发起同步评测（指定 ConfigurationProfileVersion + 知识库），对每个 case 跑「检索→生成」，计算 Recall@K/MRR/NDCG/citation hit/refusal match/latency，持久化运行与逐用例结果。

**Architecture:** 扩展 evaluation 模块承载 `EvaluationRun`/`EvaluationRunCase`；给 retrieval/generation 增加参数化重载；把 configuration 的 ProfileConfig 值对象公开为 `::api` 并新增只读门面。

**Tech Stack:** Java 21、Spring Boot 4、Spring Modulith、Spring Data JPA、PostgreSQL (Flyway)、Jackson 3 (`tools.jackson`)、React 19 + TypeScript + Vite + Vitest。

## Global Constraints

- 检索/生成**数值 + prompt 真参数化**；`chatModel`/`embeddingModel` 仅记录，不做模型路由。
- 参数 record 各自模块定义（retrieval.api.RetrievalParameters / generation.api.GenerationParameters），**不**跨模块 import configuration 类型。
- evaluation 模块 `allowedDependencies` 收敛为 `{"shared", "iam::api", "retrieval::api", "generation::api", "configuration::api"}`。
- configuration 的 `ProfileConfig` 及 5 个子 record 移到 `configuration.api` 包（值对象公开共享），新增 `@NamedInterface("api")`。
- 评测知识范围 = 管理员指定的 knowledgeBaseIds，检索时 authorized=requested=该列表（平台级，不套普通用户授权）。
- 迁移 `V11`（当前最新 `V10`），只增不改。
- TDD，每 Task 独立提交。

---

## File Structure

**参数化接口：**

- Create `backend/src/main/java/io/veridex/retrieval/api/RetrievalParameters.java`
- Modify `backend/src/main/java/io/veridex/retrieval/api/HybridSearchService.java`
- Modify `backend/src/main/java/io/veridex/retrieval/application/HybridSearchServiceImpl.java`
- Create `backend/src/main/java/io/veridex/generation/api/GenerationParameters.java`
- Modify `backend/src/main/java/io/veridex/generation/api/GenerationService.java`
- Modify `backend/src/main/java/io/veridex/generation/application/GenerationServiceImpl.java`
- Modify `backend/src/main/java/io/veridex/generation/application/RefusalPolicy.java`

**configuration 公开值对象：**

- Move 6 个 record 到 `configuration/api`（`ChunkingConfig`/`RetrievalConfig`/`GenerationConfig`/`PromptConfig`/`ModelConfig`/`ProfileConfig`）
- Create `backend/src/main/java/io/veridex/configuration/api/package-info.java` — `@NamedInterface("api")`
- Create `backend/src/main/java/io/veridex/configuration/api/ConfigurationProfileQuery.java`
- Create `backend/src/main/java/io/veridex/configuration/application/ConfigurationProfileQueryImpl.java`
- Modify `ProfileDefaults.java` / `ConfigurationProfileService.java` / api DTO 的 import

**evaluation 运行模型：**

- Create `backend/src/main/resources/db/migration/V11__evaluation_run.sql`
- Create `backend/src/main/java/io/veridex/evaluation/domain/EvaluationRun.java`
- Create `backend/src/main/java/io/veridex/evaluation/domain/EvaluationRunCase.java`
- Create `backend/src/main/java/io/veridex/evaluation/domain/EvaluationRunRepository.java`
- Create `backend/src/main/java/io/veridex/evaluation/domain/EvaluationRunCaseRepository.java`
- Create `backend/src/main/java/io/veridex/evaluation/domain/CaseMetrics.java`
- Create `backend/src/main/java/io/veridex/evaluation/domain/RunMetrics.java`
- Create `backend/src/main/java/io/veridex/evaluation/application/MetricsCalculator.java`
- Create `backend/src/main/java/io/veridex/evaluation/application/EvaluationRunService.java`
- Create `backend/src/main/java/io/veridex/evaluation/api/StartRunRequest.java`
- Create `backend/src/main/java/io/veridex/evaluation/api/RunView.java`
- Create `backend/src/main/java/io/veridex/evaluation/api/RunDetailView.java`
- Create `backend/src/main/java/io/veridex/evaluation/api/RunCaseView.java`
- Create `backend/src/main/java/io/veridex/evaluation/api/EvaluationRunController.java`
- Modify `backend/src/main/java/io/veridex/evaluation/package-info.java`

**后端测试：**

- Modify `backend/src/test/java/io/veridex/support/DatabaseMigrationTest.java`
- Create `backend/src/test/java/io/veridex/evaluation/MetricsCalculatorTest.java`
- Create `backend/src/test/java/io/veridex/evaluation/EvaluationRunServiceTest.java`
- Create `backend/src/test/java/io/veridex/evaluation/EvaluationRunIntegrationTest.java`
- Modify 检索/生成参数化的既有单测（如需要）

**前端：**

- Modify `web/src/features/evaluation/evaluationApi.ts`
- Modify `web/src/features/evaluation/EvaluationPage.tsx`
- Create `web/src/features/evaluation/components/RunPanel.tsx`
- Modify `web/src/styles.css`
- Create `web/src/features/evaluation/RunPanel.test.tsx`（或并入 EvaluationPage.test）

---

### Task 1: V11 迁移与迁移测试

**Files:**
- Create: `backend/src/main/resources/db/migration/V11__evaluation_run.sql`
- Modify: `backend/src/test/java/io/veridex/support/DatabaseMigrationTest.java`

- [ ] **Step 1: 写失败测试**

版本断言追加 `"11"`：

```java
assertThat(versions).contains("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11");
```

新增测试方法 `evaluationRunMigrationCreatesTwoTables`：断言 `evaluation_run`、`evaluation_run_case` 存在，且关键列：

```java
assertColumn(columns, "evaluation_run.status", "character varying", 20, false, null);
assertColumn(columns, "evaluation_run.metrics", "jsonb", null, false, "'{}'::jsonb");
assertColumn(columns, "evaluation_run.knowledge_scope", "jsonb", null, false, "'[]'::jsonb");
assertColumn(columns, "evaluation_run_case.case_position", "integer", null, false, null);
assertColumn(columns, "evaluation_run_case.metrics", "jsonb", null, false, "'{}'::jsonb");
```

> 注意：两表分别查询时 map key 不带表名前缀（单表查询）。或复用带前缀的多表查询模式（两张表放一个 IN）。

- [ ] **Step 2: 运行确认失败** — `./mvnw -pl backend test -Dtest=DatabaseMigrationTest`（FAIL）

- [ ] **Step 3: 写 V11 迁移**

```sql
-- Phase 4-d 评测运行与指标

CREATE TABLE evaluation_run (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    dataset_id UUID NOT NULL REFERENCES evaluation_dataset(id) ON DELETE CASCADE,
    dataset_version_id UUID NOT NULL REFERENCES dataset_version(id) ON DELETE CASCADE,
    profile_id UUID NOT NULL,
    profile_version_id UUID NOT NULL,
    knowledge_scope JSONB NOT NULL DEFAULT '[]'::jsonb,
    status VARCHAR(20) NOT NULL,
    metrics JSONB NOT NULL DEFAULT '{}'::jsonb,
    error TEXT,
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

- [ ] **Step 4: 运行确认通过** — PASS

- [ ] **Step 5: 提交**

```bash
git add backend/src/main/resources/db/migration/V11__evaluation_run.sql backend/src/test/java/io/veridex/support/DatabaseMigrationTest.java
git commit -m "feat: add V11 evaluation run tables"
```

---

### Task 2: 检索参数化

**Files:**
- Create: `backend/src/main/java/io/veridex/retrieval/api/RetrievalParameters.java`
- Modify: `backend/src/main/java/io/veridex/retrieval/api/HybridSearchService.java`
- Modify: `backend/src/main/java/io/veridex/retrieval/application/HybridSearchServiceImpl.java`

- [ ] **Step 1: 创建 `RetrievalParameters`**

```java
package io.veridex.retrieval.api;

public record RetrievalParameters(int topKPerChannel, int rrfK, int contextTopK,
                                  int perDocumentMax, int contextMaxChars) {
}
```

- [ ] **Step 2: 扩展接口**

`HybridSearchService` 新增方法：

```java
HybridSearchResult search(UUID userId, List<UUID> authorizedKnowledgeBaseIds,
                          List<UUID> requestedKnowledgeBaseIds, String question,
                          RetrievalParameters parameters);
```

- [ ] **Step 3: 实现参数化 + 默认委托**

`HybridSearchServiceImpl`：
- 删除硬编码常量 `TOP_K_PER_CHANNEL/RRF_K/CONTEXT_TOP_K/PER_DOCUMENT_MAX/MAX_CHARS`（或保留为默认值常量）。
- 原 `search(...)` 委托：

```java
@Override
public HybridSearchResult search(UUID userId, List<UUID> authorizedKnowledgeBaseIds,
                                 List<UUID> requestedKnowledgeBaseIds, String question) {
    return search(userId, authorizedKnowledgeBaseIds, requestedKnowledgeBaseIds, question,
            new RetrievalParameters(30, 60, 6, 3, 4000));
}
```

- 参数化方法把 `parameters.topKPerChannel()` 传入 `reader.bm25/vector`、`parameters.rrfK()` 传入 `RankFusion.fuse`、`parameters.contextTopK()/perDocumentMax()/contextMaxChars()` 传入 `assembler.assemble`。

- [ ] **Step 4: 编译 + 检索单测**

Run: `./mvnw -pl backend test-compile && ./mvnw -pl backend test -Dtest=HybridSearchServiceTest`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add backend/src/main/java/io/veridex/retrieval
git commit -m "feat: parameterize hybrid search"
```

---

### Task 3: 生成参数化

**Files:**
- Create: `backend/src/main/java/io/veridex/generation/api/GenerationParameters.java`
- Modify: `backend/src/main/java/io/veridex/generation/api/GenerationService.java`
- Modify: `backend/src/main/java/io/veridex/generation/application/GenerationServiceImpl.java`
- Modify: `backend/src/main/java/io/veridex/generation/application/RefusalPolicy.java`

- [ ] **Step 1: 创建 `GenerationParameters`**

```java
package io.veridex.generation.api;

public record GenerationParameters(int minEvidenceChars, String systemTemplate, String model) {
}
```

- [ ] **Step 2: 扩展接口**

```java
GenerationResult generate(String question, List<EvidencePiece> evidence,
                          List<MessageRecord> history, GenerationParameters parameters);
```

- [ ] **Step 3: 参数化 RefusalPolicy**

`RefusalPolicy.evaluate(List<EvidencePiece> evidence, int minEvidenceChars)`；删除硬编码 `MIN_EVIDENCE_CHARS=50`。保留 `evaluate(evidence)` 委托默认值（供在线路径）。

- [ ] **Step 4: 参数化 GenerationServiceImpl**

`buildSystemPrompt(evidence, systemTemplate)`；`model` 用 `parameters.model()`；`minEvidenceChars` 传给 refusalPolicy。原 `generate(...)` 委托默认参数：

```java
return generate(question, evidence, history,
        new GenerationParameters(50, DEFAULT_SYSTEM_TEMPLATE, "deterministic"));
```

`DEFAULT_SYSTEM_TEMPLATE` 保留当前模板字符串常量。

- [ ] **Step 5: 编译 + 生成单测**

Run: `./mvnw -pl backend test-compile && ./mvnw -pl backend test -Dtest=GenerationServiceImplTest,RefusalPolicyTest`
Expected: PASS。

- [ ] **Step 6: 提交**

```bash
git add backend/src/main/java/io/veridex/generation
git commit -m "feat: parameterize generation and refusal policy"
```

---

### Task 4: configuration 公开值对象与只读门面

**Files:**
- Move 6 个 record 到 `configuration/api`
- Create: `configuration/api/package-info.java`
- Create: `configuration/api/ConfigurationProfileQuery.java`
- Create: `configuration/application/ConfigurationProfileQueryImpl.java`
- Modify import：`ProfileDefaults.java`、`ConfigurationProfileService.java`、api DTO（同包后删除冗余 import）

- [ ] **Step 1: 移动 record**

用 `git mv` 把 6 个 record 从 `domain/` 移到 `api/`，包名改为 `io.veridex.configuration.api`。

- [ ] **Step 2: 新增 `@NamedInterface`**

```java
@org.springframework.modulith.NamedInterface(name = "api")
package io.veridex.configuration.api;
```

- [ ] **Step 3: 创建 `ConfigurationProfileQuery` 门面**

```java
package io.veridex.configuration.api;

import java.util.UUID;

public interface ConfigurationProfileQuery {
    ProfileConfig requireVersionConfig(UUID profileId, int versionNo);
}
```

- [ ] **Step 4: 创建实现 `ConfigurationProfileQueryImpl`**

```java
package io.veridex.configuration.application;

import io.veridex.configuration.api.ConfigurationProfileQuery;
import io.veridex.configuration.api.ProfileConfig;
import io.veridex.configuration.domain.ConfigurationProfileVersionRepository;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class ConfigurationProfileQueryImpl implements ConfigurationProfileQuery {

    private final ConfigurationProfileVersionRepository versions;
    private final JsonMapper jsonMapper;

    public ConfigurationProfileQueryImpl(ConfigurationProfileVersionRepository versions, JsonMapper jsonMapper) {
        this.versions = versions;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public ProfileConfig requireVersionConfig(UUID profileId, int versionNo) {
        var version = versions.findByProfileIdAndVersionNo(profileId, versionNo)
                .orElseThrow(() -> new IllegalArgumentException(
                        "unknown profile version " + versionNo + " for " + profileId));
        try {
            return jsonMapper.readValue(version.getConfigJson(), ProfileConfig.class);
        } catch (Exception e) {
            throw new IllegalStateException("failed to deserialize profile config", e);
        }
    }
}
```

- [ ] **Step 5: 更新 import**

`ProfileDefaults`（domain）与 `ConfigurationProfileService`（application）改为 import `configuration.api.*`；api 包内 DTO 删除对 `domain.ProfileConfig` 的 import（同包）。

- [ ] **Step 6: 编译 + ArchitectureTest**

Run: `./mvnw -pl backend test-compile && ./mvnw -pl backend test -Dtest=ArchitectureTest,ConfigurationProfileServiceTest,ConfigurationProfileIntegrationTest`
Expected: PASS。

- [ ] **Step 7: 提交**

```bash
git add backend/src/main/java/io/veridex/configuration
git commit -m "feat: expose profile config as configuration api and add read query"
```

---

### Task 5: 评测运行领域模型与仓储

**Files:**
- Create: `evaluation/domain/EvaluationRun.java`
- Create: `evaluation/domain/EvaluationRunCase.java`
- Create: `evaluation/domain/EvaluationRunRepository.java`
- Create: `evaluation/domain/EvaluationRunCaseRepository.java`
- Create: `evaluation/domain/CaseMetrics.java`
- Create: `evaluation/domain/RunMetrics.java`

- [ ] **Step 1: 创建指标 record**

`CaseMetrics.java`：

```java
package io.veridex.evaluation.domain;

public record CaseMetrics(double recallAt1, double recallAt3, double recallAt5,
                          double mrr, double ndcgAt10, double citationHit,
                          boolean refusalMatch, long latencyMs) {
}
```

`RunMetrics.java`：

```java
package io.veridex.evaluation.domain;

public record RunMetrics(double avgRecallAt1, double avgRecallAt3, double avgRecallAt5,
                         double avgMrr, double avgNdcgAt10, double citationHitRate,
                         double refusalMatchRate, double avgLatencyMs,
                         int caseCount, int completedCount) {
}
```

- [ ] **Step 2: 创建 `EvaluationRun` 实体**

```java
package io.veridex.evaluation.domain;

import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "evaluation_run")
public class EvaluationRun {

    public enum Status { RUNNING, COMPLETED, FAILED }

    @Id
    private UUID id = UUID.randomUUID();

    @Column(name = "dataset_id", nullable = false)
    private UUID datasetId;

    @Column(name = "dataset_version_id", nullable = false)
    private UUID datasetVersionId;

    @Column(name = "profile_id", nullable = false)
    private UUID profileId;

    @Column(name = "profile_version_id", nullable = false)
    private UUID profileVersionId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "knowledge_scope", nullable = false, columnDefinition = "jsonb")
    private String knowledgeScopeJson = "[]";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.RUNNING;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String metricsJson = "{}";

    @Column(columnDefinition = "text")
    private String error;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "completed_at")
    private Instant completedAt;

    protected EvaluationRun() {}

    public EvaluationRun(UUID datasetId, UUID datasetVersionId, UUID profileId, UUID profileVersionId,
                         List<UUID> knowledgeScope, UUID createdBy) {
        this.datasetId = datasetId;
        this.datasetVersionId = datasetVersionId;
        this.profileId = profileId;
        this.profileVersionId = profileVersionId;
        this.knowledgeScopeJson = knowledgeScope.stream().map(id -> "\"" + id + "\"").toList().toString();
        this.createdBy = createdBy;
    }

    public void complete(String metricsJson) {
        this.status = Status.COMPLETED;
        this.metricsJson = metricsJson;
        this.completedAt = Instant.now();
    }

    public void fail(String error) {
        this.status = Status.FAILED;
        this.error = error;
        this.completedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getDatasetId() { return datasetId; }
    public UUID getDatasetVersionId() { return datasetVersionId; }
    public UUID getProfileId() { return profileId; }
    public UUID getProfileVersionId() { return profileVersionId; }
    public String getKnowledgeScopeJson() { return knowledgeScopeJson; }
    public Status getStatus() { return status; }
    public String getMetricsJson() { return metricsJson; }
    public String getError() { return error; }
    public UUID getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getCompletedAt() { return completedAt; }
}
```

（需要 `import java.util.List;`）

- [ ] **Step 3: 创建 `EvaluationRunCase` 实体**

字段：runId、casePosition、question、expectedBehavior（String，存 `ANSWER`/`REFUSE`）、groundTruthEvidenceJson、actualBehavior（String）、answer、citationsJson、retrievedChunksJson、metricsJson。全部 JSONB 用 `@JdbcTypeCode(SqlTypes.JSON)`。

- [ ] **Step 4: 创建仓储**

`EvaluationRunRepository extends CrudRepository<EvaluationRun, UUID>`：`List<EvaluationRun> findByDatasetIdOrderByCreatedAtDesc(UUID datasetId)`。
`EvaluationRunCaseRepository extends CrudRepository<EvaluationRunCase, UUID>`：`List<EvaluationRunCase> findByRunIdOrderByCasePositionAsc(UUID runId)`。

- [ ] **Step 5: 编译验证** — `./mvnw -pl backend test-compile`

- [ ] **Step 6: 提交**

```bash
git add backend/src/main/java/io/veridex/evaluation/domain
git commit -m "feat: add evaluation run domain model and repositories"
```

---

### Task 6: 指标计算器与单测

**Files:**
- Create: `evaluation/application/MetricsCalculator.java`
- Create: `backend/src/test/java/io/veridex/evaluation/MetricsCalculatorTest.java`

- [ ] **Step 1: 写失败测试**

覆盖：空 ground-truth（REFUSE 用例）、Recall@K 边界、MRR、NDCG、citation hit、refusal match 的典型用例。

- [ ] **Step 2: 实现 `MetricsCalculator`**

核心签名：

```java
package io.veridex.evaluation.application;

import io.veridex.evaluation.domain.CaseMetrics;
import io.veridex.evaluation.domain.RunMetrics;
import io.veridex.evaluation.domain.EvidenceRef;
import io.veridex.generation.api.CitationView;
import io.veridex.retrieval.api.RankedHitView;
import java.util.*;

@Component
public class MetricsCalculator {

    public CaseMetrics compute(List<EvidenceRef> groundTruth, List<RankedHitView> hits,
                               List<CitationView> citations, boolean expectedRefuse,
                               boolean actualRefuse, long latencyMs) {
        Set<String> g = groundTruthChunks(groundTruth);
        List<String> ranked = hits.stream()
                .sorted(Comparator.comparingInt(RankedHitView::rank))
                .map(h -> h.documentVersionId() + ":" + h.chunkIndex())
                .toList();
        double recallAt1 = recallAtK(g, ranked, 1);
        double recallAt3 = recallAtK(g, ranked, 3);
        double recallAt5 = recallAtK(g, ranked, 5);
        double mrr = mrr(g, ranked);
        double ndcgAt10 = ndcgAtK(g, ranked, 10);
        double citationHit = citationHit(g, citations);
        boolean refusalMatch = expectedRefuse == actualRefuse;
        return new CaseMetrics(recallAt1, recallAt3, recallAt5, mrr, ndcgAt10,
                citationHit, refusalMatch, latencyMs);
    }

    public RunMetrics aggregate(List<CaseMetrics> all) {
        // 对 completed 的 case 求均值；completedCount = all.size()
    }

    private static Set<String> groundTruthChunks(List<EvidenceRef> groundTruth) {
        Set<String> out = new HashSet<>();
        for (EvidenceRef ref : groundTruth) {
            for (int idx : ref.chunkIndexes()) {
                out.add(ref.documentVersionId() + ":" + idx);
            }
        }
        return out;
    }

    private static double recallAtK(Set<String> g, List<String> ranked, int k) {
        if (g.isEmpty()) return 0.0;
        Set<String> topK = new HashSet<>(ranked.subList(0, Math.min(k, ranked.size())));
        long hit = g.stream().filter(topK::contains).count();
        return (double) hit / g.size();
    }

    private static double mrr(Set<String> g, List<String> ranked) {
        for (int i = 0; i < ranked.size(); i++) {
            if (g.contains(ranked.get(i))) return 1.0 / (i + 1);
        }
        return 0.0;
    }

    private static double ndcgAtK(Set<String> g, List<String> ranked, int k) {
        double dcg = 0.0;
        for (int i = 0; i < Math.min(k, ranked.size()); i++) {
            if (g.contains(ranked.get(i))) {
                dcg += 1.0 / (Math.log(i + 2) / Math.log(2)); // log2(i+2)
            }
        }
        int idealRelevant = Math.min(g.size(), k);
        double idcg = 0.0;
        for (int i = 0; i < idealRelevant; i++) {
            idcg += 1.0 / (Math.log(i + 2) / Math.log(2));
        }
        return idcg == 0.0 ? 0.0 : dcg / idcg;
    }

    private static double citationHit(Set<String> g, List<CitationView> citations) {
        if (g.isEmpty()) return 0.0;
        long hit = citations.stream()
                .filter(c -> "VALID".equals(c.validationStatus()))
                .filter(c -> g.contains(c.documentVersionId() + ":" + c.chunkIndex()))
                .count();
        return (double) hit / g.size();
    }
}
```

- [ ] **Step 3: 运行确认通过** — `./mvnw -pl backend test -Dtest=MetricsCalculatorTest`

- [ ] **Step 4: 提交**

```bash
git add backend/src/main/java/io/veridex/evaluation/application/MetricsCalculator.java backend/src/test/java/io/veridex/evaluation/MetricsCalculatorTest.java
git commit -m "feat: add evaluation metrics calculator"
```

---

### Task 7: 评测执行器与单测

**Files:**
- Create: `evaluation/application/EvaluationRunService.java`
- Create: `backend/src/test/java/io/veridex/evaluation/EvaluationRunServiceTest.java`

- [ ] **Step 1: 写失败测试**

用 Mockito mock `HybridSearchService`、`GenerationService`、`ConfigurationProfileQuery`，验证：
- 成功运行：每个 case 产生一个 `EvaluationRunCase`，run 状态 COMPLETED，聚合指标正确。
- 检索/生成抛异常：run 状态 FAILED。

- [ ] **Step 2: 实现 `EvaluationRunService`**

```java
@Service
@Transactional
public class EvaluationRunService {

    private final EvaluationDatasetRepository datasets;
    private final DatasetVersionRepository versions;
    private final DatasetVersionCaseRepository versionCases;
    private final EvaluationRunRepository runs;
    private final EvaluationRunCaseRepository runCases;
    private final HybridSearchService hybridSearch;
    private final GenerationService generation;
    private final ConfigurationProfileQuery configurationProfile;
    private final MetricsCalculator metrics;
    private final JsonMapper jsonMapper;

    // 构造注入全部依赖

    public EvaluationRun start(UUID datasetId, int versionNo, UUID profileId, UUID profileVersionNo,
                               List<UUID> knowledgeBaseIds, UUID actorId) {
        DatasetVersion version = versions.findByDatasetIdAndVersionNo(datasetId, versionNo)
                .orElseThrow(() -> new IllegalArgumentException("unknown dataset version " + versionNo));
        ProfileConfig config = configurationProfile.requireVersionConfig(profileId, profileVersionNo);

        EvaluationRun run = runs.save(new EvaluationRun(datasetId, version.getId(), profileId,
                profileVersionNo, knowledgeBaseIds, actorId));
        try {
            List<DatasetVersionCase> cases = versionCases.findByVersionIdOrderByPositionAsc(version.getId());
            List<CaseMetrics> caseMetrics = new ArrayList<>();
            for (DatasetVersionCase c : cases) {
                long startNs = System.nanoTime();
                var searchResult = hybridSearch.search(actorId, knowledgeBaseIds, knowledgeBaseIds,
                        c.getQuestion(), toRetrieval(config.retrieval()));
                var generationResult = generation.generate(c.getQuestion(), searchResult.evidence(),
                        List.of(), toGeneration(config));
                long latencyMs = (System.nanoTime() - startNs) / 1_000_000;

                List<EvidenceRef> groundTruth = deserializeEvidence(c.getEvidenceJson());
                boolean expectedRefuse = c.getExpectedBehavior() == ExpectedBehavior.REFUSE;
                boolean actualRefuse = generationResult.refusalReason() != null;
                CaseMetrics cm = metrics.compute(groundTruth, searchResult.hits(),
                        generationResult.citations(), expectedRefuse, actualRefuse, latencyMs);
                caseMetrics.add(cm);

                runCases.save(new EvaluationRunCase(run.getId(), c.getPosition(), c.getQuestion(),
                        c.getExpectedBehavior().name(), c.getEvidenceJson(),
                        actualRefuse ? "REFUSE" : "ANSWER", generationResult.answer(),
                        serializeCitations(generationResult.citations()),
                        serializeRetrievedChunks(searchResult.hits()),
                        jsonMapper.writeValueAsString(cm)));
            }
            RunMetrics rm = metrics.aggregate(caseMetrics);
            run.complete(jsonMapper.writeValueAsString(rm));
            return runs.save(run);
        } catch (RuntimeException e) {
            run.fail(e.getMessage() == null ? "evaluation failed" : e.getMessage());
            runs.save(run);
            throw e;
        }
    }
}
```

关键辅助：`toRetrieval(RetrievalConfig)`、`toGeneration(ProfileConfig)`、`deserializeEvidence`、`serializeCitations`、`serializeRetrievedChunks`。

- [ ] **Step 3: 运行确认通过** — `./mvnw -pl backend test -Dtest=EvaluationRunServiceTest`

- [ ] **Step 4: 提交**

```bash
git add backend/src/main/java/io/veridex/evaluation/application/EvaluationRunService.java backend/src/test/java/io/veridex/evaluation/EvaluationRunServiceTest.java
git commit -m "feat: add evaluation run orchestration service"
```

---

### Task 8: 评测运行 REST API 与模块边界

**Files:**
- Modify: `evaluation/package-info.java` — 扩展 allowedDependencies
- Create: `evaluation/api/StartRunRequest.java`
- Create: `evaluation/api/RunView.java`
- Create: `evaluation/api/RunDetailView.java`
- Create: `evaluation/api/RunCaseView.java`
- Create: `evaluation/api/EvaluationRunController.java`

- [ ] **Step 1: 扩展边界**

```java
@org.springframework.modulith.ApplicationModule(
        displayName = "Evaluation",
        allowedDependencies = {"shared", "iam::api", "retrieval::api", "generation::api", "configuration::api"}
)
package io.veridex.evaluation;
```

- [ ] **Step 2: 创建 DTO**

`StartRunRequest(datasetId, datasetVersionNo, profileId, profileVersionNo, knowledgeBaseIds)`。
`RunView(id, datasetId, datasetVersionId, profileId, profileVersionId, status, metrics, createdAt, completedAt)`。
`RunDetailView(id, ..., cases)`。
`RunCaseView(position, question, expectedBehavior, actualBehavior, answer, groundTruthEvidence, citations, retrievedChunks, metrics)`。

- [ ] **Step 3: 创建 `EvaluationRunController`**

```java
@RestController
@RequestMapping("/api/evaluation/runs")
public class EvaluationRunController {
    // POST /  -> 发起评测（requireAdmin）
    // GET /?datasetId= -> 列表（requireAdmin）
    // GET /{id} -> 详情（requireAdmin）
}
```

- [ ] **Step 4: 编译 + ArchitectureTest**

Run: `./mvnw -pl backend test-compile && ./mvnw -pl backend test -Dtest=ArchitectureTest`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add backend/src/main/java/io/veridex/evaluation
git commit -m "feat: expose evaluation run REST API"
```

---

### Task 9: 评测运行集成测试

**Files:**
- Create: `backend/src/test/java/io/veridex/evaluation/EvaluationRunIntegrationTest.java`

- [ ] **Step 1: 写测试**

Testcontainers Postgres + 真实检索/生成（最小数据集：建 1 个知识库 + 1 个文档版本 + 1 个 active release + 1 个 dataset/version/case + 1 个 profile/version）。验证：
- 发起评测成功，run 状态 COMPLETED。
- 每个 case 有 RunCase + 指标。

> 该测试依赖较完整的知识入库/发布链路，若搭建成本过高，可降级为「用 mock 检索/生成的 service 层 + 真库」测试（`EvaluationRunServiceTest` 已覆盖编排），本集成测试聚焦持久化 + 迁移验证。

- [ ] **Step 2: 运行确认通过** — `./mvnw -pl backend test -Dtest=EvaluationRunIntegrationTest`

- [ ] **Step 3: 提交**

```bash
git add backend/src/test/java/io/veridex/evaluation/EvaluationRunIntegrationTest.java
git commit -m "test: cover evaluation run persistence against Postgres"
```

---

### Task 10: 前端评测运行面板

**Files:**
- Modify: `web/src/features/evaluation/evaluationApi.ts`
- Modify: `web/src/features/evaluation/EvaluationPage.tsx`
- Create: `web/src/features/evaluation/components/RunPanel.tsx`
- Modify: `web/src/styles.css`

- [ ] **Step 1: 扩展 `evaluationApi.ts`**

新增类型 `RunMetrics`/`RunCaseView`/`RunDetail`/`RunView`，方法 `startRun`/`runs`/`run`。

- [ ] **Step 2: 创建 `RunPanel`**

在数据集详情页展示：发起评测表单（选版本号 + Profile + 知识库）→ 结果列表 + 聚合指标 + 逐用例指标。

- [ ] **Step 3: 接入 `EvaluationPage`**

在版本详情面板下方加入 `RunPanel`。

- [ ] **Step 4: lint + build** — `npm --prefix web run lint && npm --prefix web run build`

- [ ] **Step 5: 提交**

```bash
git add web/src/features/evaluation web/src/styles.css
git commit -m "feat: add evaluation run panel"
```

---

### Task 11: 前端测试与全量门禁

**Files:**
- Create/Modify: `web/src/features/evaluation/EvaluationPage.test.tsx`（或 `RunPanel.test.tsx`）
- Modify: `web/src/app/App.test.tsx`（如路由有变化）

- [ ] **Step 1: 写/更新前端测试** — 覆盖发起评测 + 结果展示

- [ ] **Step 2: 运行前端测试** — `npm --prefix web test`

- [ ] **Step 3: 提交测试**

```bash
git add web/src/features/evaluation web/src/app
git commit -m "test: cover evaluation run panel"
```

- [ ] **Step 4: 全量门禁** — `./scripts/verify.sh`（四段全绿）

- [ ] **Step 5: 确认工作树** — `git status`

---

## Self-Review

**Spec coverage：**
- E1 数值+prompt 真参数化 → Task 2/3
- E2 同步运行 → EvaluationRunService 同步执行
- E3 仅自动指标 → MetricsCalculator（Recall/MRR/NDCG/citation hit/refusal match/latency）
- E4 扩展 evaluation 模块 → Task 8 边界
- E5 参数化接口 → RetrievalParameters/GenerationParameters
- E6 configuration 公开值对象 → Task 4
- E7 V11 迁移 → Task 1
- E8 权限 → controller requireAdmin
- E9 知识范围 → 执行器直接传 knowledgeBaseIds

**验收对照：**
- 发起评测返回聚合指标 → Task 7/8/10
- 不同 Profile 产生不同结果 → Task 2/3 参数化
- 逐用例指标齐全 → Task 6/7
- verify.sh 全绿 → Task 11

**风险与注意：**
- 评测执行同步跑全部 case，数据量大时慢；本轮接受（E2 同步）。
- 检索 `userId` 参数当前未被 `HybridSearchServiceImpl` 使用，评测传 actorId 无副作用。
- configuration 移动 6 个 record 到 api 包会影响既有 import，Task 4 需全部更新并跑 ConfigurationProfile 测试。
- `EvaluationRunCase.expectedBehavior`/`actualBehavior` 用 String 存（避免枚举迁移耦合），与 evaluation_case 的枚举列名一致。
