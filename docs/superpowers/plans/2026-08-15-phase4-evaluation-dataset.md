# Phase 4-a 版本化评测集与用例 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 知识管理员能够创建版本化评测集，在草稿工作集中增删改用例（含 ANSWER/REFUSE 期望 + ground-truth 证据），发布生成不可变的 DatasetVersion，已发布版本不可原地修改。

**Architecture:** 在 Spring Modulith 模块化单体的 `io.veridex.evaluation` 包内新增领域模型（可变工作集 `EvaluationDataset`/`EvaluationCase` + 不可变快照 `DatasetVersion`/`DatasetVersionCase`）、V8 数据库迁移、`/api/evaluation/*` REST API，以及替换 `/evaluation` 占位页的 React 管理页。证据只存 `documentVersionId + chunkIndex[]` 引用，不复制 chunk 文本。

**Tech Stack:** Java 21、Spring Boot 4、Spring Modulith、Spring Data JPA、PostgreSQL (Flyway)、Jackson 3 (`tools.jackson`)、React 19 + TypeScript + Vite + Vitest。

## Global Constraints

- 证据粒度精确到 `document_version_id + chunk_index[]`（与 `retrieval_hit`/`citation` 对齐），JSONB 结构为 `[{"documentVersionId":"<uuid>","chunkIndexes":[3,4]}]`。
- `evaluation` 模块 `allowedDependencies` 收敛为 `{"shared", "iam::api"}`（本计划实现中只用 `iam::api` 的 `CurrentActor` / `Role` / `SecurityContextRole`）。
- 权限沿用现有 `PLATFORM_ADMIN` / `KNOWLEDGE_ADMIN` 角色；项目当前约定是 controller 内手动校验角色（`SecurityContextRole.currentRole()`），不使用 `@PreAuthorize`。
- `IllegalArgumentException` → HTTP 400、`SecurityException` → HTTP 403（由已有的全局 `KnowledgeApiExceptionHandler` 统一处理，evaluation 模块不新增重复 advice，避免 Spring 抛 Ambiguous handler）。
- 迁移编号为 `V8`（当前最新为 `V7__phase3_qa.sql`），只增不改已执行迁移。
- 每个步骤先写失败测试再实现（TDD），每个 Task 结束独立提交。

---

## File Structure

**后端（模块 `io.veridex.evaluation`，包结构 `domain` / `application` / `api`）：**

- Create `backend/src/main/resources/db/migration/V8__evaluation.sql` — 4 张表
- Create `backend/src/main/java/io/veridex/evaluation/domain/ExpectedBehavior.java` — 枚举 `ANSWER`/`REFUSE`
- Create `backend/src/main/java/io/veridex/evaluation/domain/EvidenceRef.java` — 证据引用 record
- Create `backend/src/main/java/io/veridex/evaluation/domain/EvaluationDataset.java` — 数据集实体
- Create `backend/src/main/java/io/veridex/evaluation/domain/EvaluationCase.java` — 可变工作集用例实体
- Create `backend/src/main/java/io/veridex/evaluation/domain/DatasetVersion.java` — 不可变版本实体
- Create `backend/src/main/java/io/veridex/evaluation/domain/DatasetVersionCase.java` — 冻结快照用例实体
- Create `backend/src/main/java/io/veridex/evaluation/domain/EvaluationDatasetRepository.java`
- Create `backend/src/main/java/io/veridex/evaluation/domain/EvaluationCaseRepository.java`
- Create `backend/src/main/java/io/veridex/evaluation/domain/DatasetVersionRepository.java`
- Create `backend/src/main/java/io/veridex/evaluation/domain/DatasetVersionCaseRepository.java`
- Create `backend/src/main/java/io/veridex/evaluation/application/EvaluationDatasetService.java` — 业务服务（CRUD + 发布冻结 + 校验 + 证据 JSON 序列化）
- Create `backend/src/main/java/io/veridex/evaluation/api/EvaluationAuthorization.java` — 角色校验门面
- Create `backend/src/main/java/io/veridex/evaluation/api/EvaluationController.java` — REST 控制器
- Create `backend/src/main/java/io/veridex/evaluation/api/CreateDatasetRequest.java`
- Create `backend/src/main/java/io/veridex/evaluation/api/DatasetView.java`
- Create `backend/src/main/java/io/veridex/evaluation/api/CaseView.java`
- Create `backend/src/main/java/io/veridex/evaluation/api/CreateCaseRequest.java`
- Create `backend/src/main/java/io/veridex/evaluation/api/UpdateCaseRequest.java`
- Create `backend/src/main/java/io/veridex/evaluation/api/VersionView.java`
- Create `backend/src/main/java/io/veridex/evaluation/api/VersionDetailView.java`
- Create `backend/src/main/java/io/veridex/evaluation/api/VersionCaseView.java`
- Create `backend/src/main/java/io/veridex/evaluation/api/PublishResult.java`
- Modify `backend/src/main/java/io/veridex/evaluation/package-info.java` — 收敛 `allowedDependencies`

**后端测试：**

- Modify `backend/src/test/java/io/veridex/support/DatabaseMigrationTest.java` — 追加 V8 断言
- Create `backend/src/test/java/io/veridex/evaluation/EvaluationDatasetServiceTest.java` — 服务单测（Mockito）
- Create `backend/src/test/java/io/veridex/evaluation/EvaluationDatasetIntegrationTest.java` — 真库集成测试（Testcontainers Postgres）

**前端：**

- Create `web/src/features/evaluation/evaluationApi.ts` — API 客户端 + 类型
- Create `web/src/features/evaluation/EvaluationPage.tsx` — 页面编排 + 版本面板
- Create `web/src/features/evaluation/components/DatasetList.tsx` — 数据集列表 + 新建
- Create `web/src/features/evaluation/components/CaseWorkspace.tsx` — 用例列表 + 编辑器
- Create `web/src/features/evaluation/components/EvidencePicker.tsx` — ground-truth 证据级联选择
- Modify `web/src/app/routes.tsx` — 将 `/evaluation` 从 ComingSoonPage 替换为 EvaluationPage
- Modify `web/src/styles.css` — 追加评测页样式
- Modify `web/src/app/App.test.tsx` — 更新 `/evaluation` 路由断言
- Create `web/src/features/evaluation/EvaluationPage.test.tsx` — 前端 Vitest

---

### Task 1: V8 迁移与迁移测试

**Files:**
- Create: `backend/src/main/resources/db/migration/V8__evaluation.sql`
- Modify: `backend/src/test/java/io/veridex/support/DatabaseMigrationTest.java`

**Interfaces:**
- Consumes: 无（首个任务）。
- Produces: 4 张表 `evaluation_dataset`、`evaluation_case`、`dataset_version`、`dataset_version_case`；`DatabaseMigrationTest` 断言 flyway version 列表包含 `"8"`。

- [ ] **Step 1: 写失败测试**

修改 `DatabaseMigrationTest.java`，把版本断言加入 `"8"`：

```java
assertThat(versions).contains("1", "2", "3", "4", "5", "6", "7", "8");
```

新增一个测试方法（参考现有 `phase3QaMigrationCreatesTablesAndColumns` 的写法）：

```java
@Test
void evaluationMigrationCreatesFourTables() throws Exception {
    try (Connection connection = dataSource.getConnection()) {
        assertThat(tableNames(connection)).contains(
                "evaluation_dataset", "evaluation_case", "dataset_version", "dataset_version_case");

        var evaluationColumns = new HashMap<String, ColumnContract>();
        try (var statement = connection.prepareStatement("""
                SELECT table_name, column_name, data_type, character_maximum_length,
                       is_nullable, column_default
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name IN ('evaluation_dataset', 'evaluation_case', 'dataset_version', 'dataset_version_case')
                """);
             var rows = statement.executeQuery()) {
            while (rows.next()) {
                evaluationColumns.put(rows.getString("table_name") + "." + rows.getString("column_name"),
                        new ColumnContract(rows.getString("data_type"),
                                rows.getObject("character_maximum_length", Integer.class),
                                "YES".equals(rows.getString("is_nullable")),
                                rows.getString("column_default")));
            }
        }
        assertColumn(evaluationColumns, "evaluation_dataset.name", "character varying", 200, false, null);
        assertColumn(evaluationColumns, "evaluation_case.question", "text", null, false, null);
        assertColumn(evaluationColumns, "evaluation_case.expected_behavior", "character varying", 20, false, null);
        assertColumn(evaluationColumns, "evaluation_case.evidence", "jsonb", null, false, "'[]'::jsonb");
        assertColumn(evaluationColumns, "dataset_version.version_no", "integer", null, false, null);
        assertColumn(evaluationColumns, "dataset_version_case.position", "integer", null, false, null);
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./mvnw -pl backend test -Dtest=DatabaseMigrationTest`
Expected: FAIL，`versions` 不含 `"8"`，且新表不存在。

- [ ] **Step 3: 写 V8 迁移**

创建 `V8__evaluation.sql`：

```sql
-- Phase 4-a 版本化评测集与用例

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

- [ ] **Step 4: 运行测试确认通过**

Run: `./mvnw -pl backend test -Dtest=DatabaseMigrationTest`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add backend/src/main/resources/db/migration/V8__evaluation.sql backend/src/test/java/io/veridex/support/DatabaseMigrationTest.java
git commit -m "feat: add V8 evaluation dataset tables"
```

---

### Task 2: 领域模型与仓储

**Files:**
- Create: `backend/src/main/java/io/veridex/evaluation/domain/ExpectedBehavior.java`
- Create: `backend/src/main/java/io/veridex/evaluation/domain/EvidenceRef.java`
- Create: `backend/src/main/java/io/veridex/evaluation/domain/EvaluationDataset.java`
- Create: `backend/src/main/java/io/veridex/evaluation/domain/EvaluationCase.java`
- Create: `backend/src/main/java/io/veridex/evaluation/domain/DatasetVersion.java`
- Create: `backend/src/main/java/io/veridex/evaluation/domain/DatasetVersionCase.java`
- Create: `backend/src/main/java/io/veridex/evaluation/domain/EvaluationDatasetRepository.java`
- Create: `backend/src/main/java/io/veridex/evaluation/domain/EvaluationCaseRepository.java`
- Create: `backend/src/main/java/io/veridex/evaluation/domain/DatasetVersionRepository.java`
- Create: `backend/src/main/java/io/veridex/evaluation/domain/DatasetVersionCaseRepository.java`

**Interfaces:**
- Consumes: V8 表（Task 1）。
- Produces: 实体 + 仓储，供 Task 3 服务与 Task 5 集成测试使用。仓储方法签名如下（后续 Task 严格沿用）：
  - `EvaluationDatasetRepository.findAllByOrderByCreatedAtDesc() -> List<EvaluationDataset>`
  - `EvaluationCaseRepository.findByDatasetIdOrderByCreatedAtAsc(UUID) -> List<EvaluationCase>`、`countByDatasetId(UUID) -> long`
  - `DatasetVersionRepository.findByDatasetIdOrderByVersionNoDesc(UUID) -> List<DatasetVersion>`、`findTopByDatasetIdOrderByVersionNoDesc(UUID) -> Optional<DatasetVersion>`、`findByDatasetIdAndVersionNo(UUID,int) -> Optional<DatasetVersion>`
  - `DatasetVersionCaseRepository.findByVersionIdOrderByPositionAsc(UUID) -> List<DatasetVersionCase>`

- [ ] **Step 1: 创建枚举 `ExpectedBehavior`**

```java
package io.veridex.evaluation.domain;

public enum ExpectedBehavior {
    ANSWER, REFUSE
}
```

- [ ] **Step 2: 创建证据引用 `EvidenceRef`**

```java
package io.veridex.evaluation.domain;

import java.util.List;
import java.util.UUID;

public record EvidenceRef(UUID documentVersionId, List<Integer> chunkIndexes) {
}
```

- [ ] **Step 3: 创建 `EvaluationDataset` 实体**

```java
package io.veridex.evaluation.domain;

import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "evaluation_dataset")
public class EvaluationDataset {

    @Id
    private UUID id = UUID.randomUUID();

    @Column(nullable = false, length = 200)
    private String name;

    @Column(columnDefinition = "text")
    private String description;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected EvaluationDataset() {
    }

    public EvaluationDataset(String name, String description, UUID createdBy) {
        this.name = name;
        this.description = description;
        this.createdBy = createdBy;
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public UUID getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
```

- [ ] **Step 4: 创建 `EvaluationCase` 实体**

```java
package io.veridex.evaluation.domain;

import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "evaluation_case")
public class EvaluationCase {

    @Id
    private UUID id = UUID.randomUUID();

    @Column(name = "dataset_id", nullable = false)
    private UUID datasetId;

    @Column(nullable = false, columnDefinition = "text")
    private String question;

    @Enumerated(EnumType.STRING)
    @Column(name = "expected_behavior", nullable = false, length = 20)
    private ExpectedBehavior expectedBehavior;

    @Column(name = "expected_answer", columnDefinition = "text")
    private String expectedAnswer;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String evidenceJson = "[]";

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected EvaluationCase() {
    }

    public EvaluationCase(UUID datasetId, String question, ExpectedBehavior expectedBehavior,
                          String expectedAnswer, String evidenceJson) {
        this.datasetId = datasetId;
        this.question = question;
        this.expectedBehavior = expectedBehavior;
        this.expectedAnswer = expectedAnswer;
        this.evidenceJson = evidenceJson;
    }

    public void update(String question, ExpectedBehavior expectedBehavior,
                       String expectedAnswer, String evidenceJson) {
        this.question = question;
        this.expectedBehavior = expectedBehavior;
        this.expectedAnswer = expectedAnswer;
        this.evidenceJson = evidenceJson;
        this.updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getDatasetId() { return datasetId; }
    public String getQuestion() { return question; }
    public ExpectedBehavior getExpectedBehavior() { return expectedBehavior; }
    public String getExpectedAnswer() { return expectedAnswer; }
    public String getEvidenceJson() { return evidenceJson; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
```

- [ ] **Step 5: 创建 `DatasetVersion` 实体**

```java
package io.veridex.evaluation.domain;

import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "dataset_version")
public class DatasetVersion {

    @Id
    private UUID id = UUID.randomUUID();

    @Column(name = "dataset_id", nullable = false)
    private UUID datasetId;

    @Column(name = "version_no", nullable = false)
    private int versionNo;

    @Column(name = "case_count", nullable = false)
    private int caseCount;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected DatasetVersion() {
    }

    public DatasetVersion(UUID datasetId, int versionNo, int caseCount, UUID createdBy) {
        this.datasetId = datasetId;
        this.versionNo = versionNo;
        this.caseCount = caseCount;
        this.createdBy = createdBy;
    }

    public UUID getId() { return id; }
    public UUID getDatasetId() { return datasetId; }
    public int getVersionNo() { return versionNo; }
    public int getCaseCount() { return caseCount; }
    public UUID getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
}
```

- [ ] **Step 6: 创建 `DatasetVersionCase` 实体**

```java
package io.veridex.evaluation.domain;

import java.util.UUID;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "dataset_version_case")
public class DatasetVersionCase {

    @Id
    private UUID id = UUID.randomUUID();

    @Column(name = "version_id", nullable = false)
    private UUID versionId;

    @Column(nullable = false)
    private int position;

    @Column(nullable = false, columnDefinition = "text")
    private String question;

    @Enumerated(EnumType.STRING)
    @Column(name = "expected_behavior", nullable = false, length = 20)
    private ExpectedBehavior expectedBehavior;

    @Column(name = "expected_answer", columnDefinition = "text")
    private String expectedAnswer;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String evidenceJson = "[]";

    protected DatasetVersionCase() {
    }

    public DatasetVersionCase(UUID versionId, int position, String question,
                              ExpectedBehavior expectedBehavior, String expectedAnswer,
                              String evidenceJson) {
        this.versionId = versionId;
        this.position = position;
        this.question = question;
        this.expectedBehavior = expectedBehavior;
        this.expectedAnswer = expectedAnswer;
        this.evidenceJson = evidenceJson;
    }

    public UUID getId() { return id; }
    public UUID getVersionId() { return versionId; }
    public int getPosition() { return position; }
    public String getQuestion() { return question; }
    public ExpectedBehavior getExpectedBehavior() { return expectedBehavior; }
    public String getExpectedAnswer() { return expectedAnswer; }
    public String getEvidenceJson() { return evidenceJson; }
}
```

- [ ] **Step 7: 创建 4 个仓储接口**

`EvaluationDatasetRepository.java`：

```java
package io.veridex.evaluation.domain;

import java.util.List;
import java.util.UUID;
import org.springframework.data.repository.CrudRepository;

public interface EvaluationDatasetRepository extends CrudRepository<EvaluationDataset, UUID> {
    List<EvaluationDataset> findAllByOrderByCreatedAtDesc();
}
```

`EvaluationCaseRepository.java`：

```java
package io.veridex.evaluation.domain;

import java.util.List;
import java.util.UUID;
import org.springframework.data.repository.CrudRepository;

public interface EvaluationCaseRepository extends CrudRepository<EvaluationCase, UUID> {
    List<EvaluationCase> findByDatasetIdOrderByCreatedAtAsc(UUID datasetId);
    long countByDatasetId(UUID datasetId);
}
```

`DatasetVersionRepository.java`：

```java
package io.veridex.evaluation.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.CrudRepository;

public interface DatasetVersionRepository extends CrudRepository<DatasetVersion, UUID> {
    List<DatasetVersion> findByDatasetIdOrderByVersionNoDesc(UUID datasetId);
    Optional<DatasetVersion> findTopByDatasetIdOrderByVersionNoDesc(UUID datasetId);
    Optional<DatasetVersion> findByDatasetIdAndVersionNo(UUID datasetId, int versionNo);
}
```

`DatasetVersionCaseRepository.java`：

```java
package io.veridex.evaluation.domain;

import java.util.List;
import java.util.UUID;
import org.springframework.data.repository.CrudRepository;

public interface DatasetVersionCaseRepository extends CrudRepository<DatasetVersionCase, UUID> {
    List<DatasetVersionCase> findByVersionIdOrderByPositionAsc(UUID versionId);
}
```

- [ ] **Step 8: 编译验证**

Run: `./mvnw -pl backend test-compile`
Expected: PASS（`BUILD SUCCESS`）。

- [ ] **Step 9: 提交**

```bash
git add backend/src/main/java/io/veridex/evaluation/domain
git commit -m "feat: add evaluation domain model and repositories"
```

---

### Task 3: 评测集服务与校验

**Files:**
- Create: `backend/src/main/java/io/veridex/evaluation/application/EvaluationDatasetService.java`
- Create: `backend/src/test/java/io/veridex/evaluation/EvaluationDatasetServiceTest.java`

**Interfaces:**
- Consumes: Task 2 的实体与仓储；Jackson 3 `tools.jackson.databind.json.JsonMapper`（Spring Boot 自动配置的 bean）。
- Produces: `EvaluationDatasetService` 的以下公开方法（Task 4 控制器严格沿用签名）：
  - `EvaluationDataset create(UUID actorId, String name, String description)`
  - `EvaluationDataset require(UUID datasetId)`
  - `List<EvaluationDataset> listAll()`
  - `long caseCount(UUID datasetId)`
  - `Integer latestVersionNo(UUID datasetId)`
  - `EvaluationCase addCase(UUID datasetId, String question, ExpectedBehavior behavior, String expectedAnswer, List<EvidenceRef> evidence)`
  - `EvaluationCase updateCase(UUID datasetId, UUID caseId, String question, ExpectedBehavior behavior, String expectedAnswer, List<EvidenceRef> evidence)`
  - `void deleteCase(UUID datasetId, UUID caseId)`
  - `List<EvaluationCase> listCases(UUID datasetId)`
  - `DatasetVersion publish(UUID datasetId, UUID actorId)`
  - `List<DatasetVersion> listVersions(UUID datasetId)`
  - `DatasetVersion requireVersion(UUID datasetId, int versionNo)`
  - `List<DatasetVersionCase> listVersionCases(UUID versionId)`

- [ ] **Step 1: 写失败测试**

创建 `EvaluationDatasetServiceTest.java`：

```java
package io.veridex.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.veridex.evaluation.application.EvaluationDatasetService;
import io.veridex.evaluation.domain.DatasetVersion;
import io.veridex.evaluation.domain.DatasetVersionCase;
import io.veridex.evaluation.domain.DatasetVersionCaseRepository;
import io.veridex.evaluation.domain.DatasetVersionRepository;
import io.veridex.evaluation.domain.EvaluationCase;
import io.veridex.evaluation.domain.EvaluationCaseRepository;
import io.veridex.evaluation.domain.EvaluationDataset;
import io.veridex.evaluation.domain.EvaluationDatasetRepository;
import io.veridex.evaluation.domain.ExpectedBehavior;
import io.veridex.evaluation.domain.EvidenceRef;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class EvaluationDatasetServiceTest {

    private EvaluationDatasetRepository datasets;
    private EvaluationCaseRepository cases;
    private DatasetVersionRepository versions;
    private DatasetVersionCaseRepository versionCases;
    private EvaluationDatasetService service;

    private static final UUID ACTOR = UUID.randomUUID();
    private static final UUID DATASET = UUID.randomUUID();
    private static final UUID CASE = UUID.randomUUID();
    private static final EvidenceRef EVIDENCE = new EvidenceRef(UUID.randomUUID(), List.of(0, 1));

    @BeforeEach
    void setUp() {
        datasets = mock(EvaluationDatasetRepository.class);
        cases = mock(EvaluationCaseRepository.class);
        versions = mock(DatasetVersionRepository.class);
        versionCases = mock(DatasetVersionCaseRepository.class);
        service = new EvaluationDatasetService(datasets, cases, versions, versionCases, new JsonMapper());
    }

    @Test
    void createTrimsNameAndRejectsBlankName() {
        when(datasets.save(any())).thenAnswer(inv -> inv.getArgument(0));
        EvaluationDataset created = service.create(ACTOR, "  回归集  ", "desc");
        assertThat(created.getName()).isEqualTo("回归集");
        assertThatThrownBy(() -> service.create(ACTOR, "   ", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void addAnswerCaseWithoutEvidenceIsRejected() {
        when(datasets.findById(DATASET)).thenReturn(Optional.of(new EvaluationDataset("d", null, ACTOR)));
        assertThatThrownBy(() -> service.addCase(DATASET, "问题", ExpectedBehavior.ANSWER, "答案", List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("evidence");
    }

    @Test
    void addRefuseCaseNormalizesEvidenceToEmpty() {
        when(datasets.findById(DATASET)).thenReturn(Optional.of(new EvaluationDataset("d", null, ACTOR)));
        when(cases.save(any())).thenAnswer(inv -> inv.getArgument(0));
        EvaluationCase saved = service.addCase(DATASET, "问题", ExpectedBehavior.REFUSE, null, List.of(EVIDENCE));
        assertThat(saved.getEvidenceJson()).isEqualTo("[]");
    }

    @Test
    void updateCaseRejectsCaseFromAnotherDataset() {
        when(datasets.findById(DATASET)).thenReturn(Optional.of(new EvaluationDataset("d", null, ACTOR)));
        EvaluationCase other = new EvaluationCase(UUID.randomUUID(), "q", ExpectedBehavior.ANSWER, "a", "[]");
        when(cases.findById(CASE)).thenReturn(Optional.of(other));
        assertThatThrownBy(() -> service.updateCase(DATASET, CASE, "q", ExpectedBehavior.ANSWER, "a", List.of(EVIDENCE)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not belong");
    }

    @Test
    void publishRejectsEmptyWorkSet() {
        when(datasets.findById(DATASET)).thenReturn(Optional.of(new EvaluationDataset("d", null, ACTOR)));
        when(cases.findByDatasetIdOrderByCreatedAtAsc(DATASET)).thenReturn(List.of());
        assertThatThrownBy(() -> service.publish(DATASET, ACTOR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty");
    }

    @Test
    void publishFreezesWorkSetIntoVersionCases() {
        when(datasets.findById(DATASET)).thenReturn(Optional.of(new EvaluationDataset("d", null, ACTOR)));
        EvaluationCase c = new EvaluationCase(DATASET, "q", ExpectedBehavior.ANSWER, "a",
                "[{\"documentVersionId\":\"" + EVIDENCE.documentVersionId() + "\",\"chunkIndexes\":[0,1]}]");
        when(cases.findByDatasetIdOrderByCreatedAtAsc(DATASET)).thenReturn(List.of(c));
        when(versions.findTopByDatasetIdOrderByVersionNoDesc(DATASET)).thenReturn(Optional.empty());
        when(versions.save(any())).thenAnswer(inv -> inv.getArgument(0));

        DatasetVersion version = service.publish(DATASET, ACTOR);

        assertThat(version.getVersionNo()).isEqualTo(1);
        assertThat(version.getCaseCount()).isEqualTo(1);
        verify(versionCases).save(argThat(vc -> vc.getPosition() == 1
                && vc.getQuestion().equals("q")
                && vc.getExpectedBehavior() == ExpectedBehavior.ANSWER));
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./mvnw -pl backend test -Dtest=EvaluationDatasetServiceTest`
Expected: FAIL（`EvaluationDatasetService` 不存在，编译失败）。

- [ ] **Step 3: 实现服务**

创建 `EvaluationDatasetService.java`：

```java
package io.veridex.evaluation.application;

import io.veridex.evaluation.domain.DatasetVersion;
import io.veridex.evaluation.domain.DatasetVersionCase;
import io.veridex.evaluation.domain.DatasetVersionCaseRepository;
import io.veridex.evaluation.domain.DatasetVersionRepository;
import io.veridex.evaluation.domain.EvaluationCase;
import io.veridex.evaluation.domain.EvaluationCaseRepository;
import io.veridex.evaluation.domain.EvaluationDataset;
import io.veridex.evaluation.domain.EvaluationDatasetRepository;
import io.veridex.evaluation.domain.ExpectedBehavior;
import io.veridex.evaluation.domain.EvidenceRef;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
@Transactional
public class EvaluationDatasetService {

    private final EvaluationDatasetRepository datasets;
    private final EvaluationCaseRepository cases;
    private final DatasetVersionRepository versions;
    private final DatasetVersionCaseRepository versionCases;
    private final JsonMapper jsonMapper;

    public EvaluationDatasetService(EvaluationDatasetRepository datasets,
                                    EvaluationCaseRepository cases,
                                    DatasetVersionRepository versions,
                                    DatasetVersionCaseRepository versionCases,
                                    JsonMapper jsonMapper) {
        this.datasets = datasets;
        this.cases = cases;
        this.versions = versions;
        this.versionCases = versionCases;
        this.jsonMapper = jsonMapper;
    }

    public EvaluationDataset create(UUID actorId, String name, String description) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        return datasets.save(new EvaluationDataset(trimmed, description, actorId));
    }

    public EvaluationDataset require(UUID datasetId) {
        return datasets.findById(datasetId)
                .orElseThrow(() -> new IllegalArgumentException("unknown dataset " + datasetId));
    }

    public List<EvaluationDataset> listAll() {
        return datasets.findAllByOrderByCreatedAtDesc();
    }

    public long caseCount(UUID datasetId) {
        return cases.countByDatasetId(datasetId);
    }

    public Integer latestVersionNo(UUID datasetId) {
        return versions.findTopByDatasetIdOrderByVersionNoDesc(datasetId)
                .map(DatasetVersion::getVersionNo)
                .orElse(null);
    }

    public EvaluationCase addCase(UUID datasetId, String question, ExpectedBehavior behavior,
                                  String expectedAnswer, List<EvidenceRef> evidence) {
        require(datasetId);
        String normalizedQuestion = normalizeQuestion(question);
        List<EvidenceRef> normalizedEvidence = normalizeEvidence(behavior, evidence);
        return cases.save(new EvaluationCase(datasetId, normalizedQuestion, behavior,
                expectedAnswer, serializeEvidence(normalizedEvidence)));
    }

    public EvaluationCase updateCase(UUID datasetId, UUID caseId, String question, ExpectedBehavior behavior,
                                     String expectedAnswer, List<EvidenceRef> evidence) {
        require(datasetId);
        EvaluationCase existing = requireCase(datasetId, caseId);
        String normalizedQuestion = normalizeQuestion(question);
        List<EvidenceRef> normalizedEvidence = normalizeEvidence(behavior, evidence);
        existing.update(normalizedQuestion, behavior, expectedAnswer, serializeEvidence(normalizedEvidence));
        return cases.save(existing);
    }

    public void deleteCase(UUID datasetId, UUID caseId) {
        require(datasetId);
        cases.delete(requireCase(datasetId, caseId));
    }

    public List<EvaluationCase> listCases(UUID datasetId) {
        require(datasetId);
        return cases.findByDatasetIdOrderByCreatedAtAsc(datasetId);
    }

    public DatasetVersion publish(UUID datasetId, UUID actorId) {
        require(datasetId);
        List<EvaluationCase> workSet = cases.findByDatasetIdOrderByCreatedAtAsc(datasetId);
        if (workSet.isEmpty()) {
            throw new IllegalArgumentException("cannot publish an empty dataset");
        }
        int nextVersionNo = versions.findTopByDatasetIdOrderByVersionNoDesc(datasetId)
                .map(v -> v.getVersionNo() + 1)
                .orElse(1);
        DatasetVersion version = versions.save(
                new DatasetVersion(datasetId, nextVersionNo, workSet.size(), actorId));
        int position = 1;
        for (EvaluationCase c : workSet) {
            versionCases.save(new DatasetVersionCase(version.getId(), position++,
                    c.getQuestion(), c.getExpectedBehavior(), c.getExpectedAnswer(), c.getEvidenceJson()));
        }
        return version;
    }

    public List<DatasetVersion> listVersions(UUID datasetId) {
        require(datasetId);
        return versions.findByDatasetIdOrderByVersionNoDesc(datasetId);
    }

    public DatasetVersion requireVersion(UUID datasetId, int versionNo) {
        return versions.findByDatasetIdAndVersionNo(datasetId, versionNo)
                .orElseThrow(() -> new IllegalArgumentException(
                        "unknown version " + versionNo + " for dataset " + datasetId));
    }

    public List<DatasetVersionCase> listVersionCases(UUID versionId) {
        return versionCases.findByVersionIdOrderByPositionAsc(versionId);
    }

    private EvaluationCase requireCase(UUID datasetId, UUID caseId) {
        EvaluationCase existing = cases.findById(caseId)
                .orElseThrow(() -> new IllegalArgumentException("unknown case " + caseId));
        if (!existing.getDatasetId().equals(datasetId)) {
            throw new IllegalArgumentException("case " + caseId + " does not belong to dataset " + datasetId);
        }
        return existing;
    }

    private String normalizeQuestion(String question) {
        if (question == null || question.trim().isBlank()) {
            throw new IllegalArgumentException("question is required");
        }
        return question.trim();
    }

    private List<EvidenceRef> normalizeEvidence(ExpectedBehavior behavior, List<EvidenceRef> evidence) {
        if (behavior == null) {
            throw new IllegalArgumentException("expectedBehavior is required");
        }
        List<EvidenceRef> normalized = evidence == null ? List.of() : evidence;
        if (behavior == ExpectedBehavior.ANSWER && normalized.isEmpty()) {
            throw new IllegalArgumentException("ANSWER case requires at least one evidence chunk");
        }
        if (behavior == ExpectedBehavior.REFUSE) {
            return List.of();
        }
        return normalized;
    }

    private String serializeEvidence(List<EvidenceRef> evidence) {
        try {
            return jsonMapper.writeValueAsString(evidence);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize evidence", e);
        }
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `./mvnw -pl backend test -Dtest=EvaluationDatasetServiceTest`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add backend/src/main/java/io/veridex/evaluation/application/EvaluationDatasetService.java backend/src/test/java/io/veridex/evaluation/EvaluationDatasetServiceTest.java
git commit -m "feat: add evaluation dataset service with publish freeze semantics"
```

---

### Task 4: REST API 与模块边界收敛

**Files:**
- Create: `backend/src/main/java/io/veridex/evaluation/api/EvaluationAuthorization.java`
- Create: `backend/src/main/java/io/veridex/evaluation/api/CreateDatasetRequest.java`
- Create: `backend/src/main/java/io/veridex/evaluation/api/DatasetView.java`
- Create: `backend/src/main/java/io/veridex/evaluation/api/CaseView.java`
- Create: `backend/src/main/java/io/veridex/evaluation/api/CreateCaseRequest.java`
- Create: `backend/src/main/java/io/veridex/evaluation/api/UpdateCaseRequest.java`
- Create: `backend/src/main/java/io/veridex/evaluation/api/VersionView.java`
- Create: `backend/src/main/java/io/veridex/evaluation/api/VersionDetailView.java`
- Create: `backend/src/main/java/io/veridex/evaluation/api/VersionCaseView.java`
- Create: `backend/src/main/java/io/veridex/evaluation/api/PublishResult.java`
- Create: `backend/src/main/java/io/veridex/evaluation/api/EvaluationController.java`
- Modify: `backend/src/main/java/io/veridex/evaluation/package-info.java`

**Interfaces:**
- Consumes: Task 3 的 `EvaluationDatasetService` 全部方法；`iam.api` 的 `CurrentActor` / `Role` / `SecurityContextRole`；Jackson 3 `JsonMapper`（反序列化证据）。
- Produces: `/api/evaluation/datasets` 全套接口与 DTO 形状（前端 Task 6/7 严格沿用字段名）：
  - `DatasetView { id, name, description, caseCount, latestVersionNo }`
  - `CaseView { id, question, expectedBehavior, expectedAnswer, evidence }`，其中 `expectedBehavior` 为 `"ANSWER"|"REFUSE"`，`evidence` 为 `[{documentVersionId, chunkIndexes}]`
  - `VersionView { id, versionNo, caseCount, createdAt }`
  - `VersionDetailView { id, versionNo, caseCount, createdAt, cases }`
  - `VersionCaseView { position, question, expectedBehavior, expectedAnswer, evidence }`
  - `PublishResult { versionId, versionNo, caseCount }`

- [ ] **Step 1: 收敛模块边界声明**

修改 `package-info.java`：

```java
@org.springframework.modulith.ApplicationModule(
        displayName = "Evaluation",
        allowedDependencies = {"shared", "iam::api"}
)
package io.veridex.evaluation;
```

> 说明：`ArchitectureTest`（`modules.verify()`）会在 Task 4 全部代码就位后运行；若本模块意外 import 了 `knowledge`/`retrieval`/`generation`/`trace`，该测试会失败。Task 4 末尾会运行它验证。

- [ ] **Step 2: 创建 `EvaluationAuthorization`**

```java
package io.veridex.evaluation.api;

import io.veridex.iam.api.Role;
import io.veridex.iam.api.SecurityContextRole;
import org.springframework.stereotype.Component;

@Component
public class EvaluationAuthorization {

    public boolean isAdmin() {
        Role role = SecurityContextRole.currentRole();
        return role == Role.PLATFORM_ADMIN || role == Role.KNOWLEDGE_ADMIN;
    }
}
```

- [ ] **Step 3: 创建请求与视图 DTO**

`CreateDatasetRequest.java`：

```java
package io.veridex.evaluation.api;

public record CreateDatasetRequest(String name, String description) {
}
```

`DatasetView.java`：

```java
package io.veridex.evaluation.api;

import java.util.UUID;

public record DatasetView(UUID id, String name, String description, int caseCount, Integer latestVersionNo) {
}
```

`CaseView.java`：

```java
package io.veridex.evaluation.api;

import io.veridex.evaluation.domain.EvidenceRef;
import java.util.List;
import java.util.UUID;

public record CaseView(UUID id, String question, String expectedBehavior,
                       String expectedAnswer, List<EvidenceRef> evidence) {
}
```

`CreateCaseRequest.java`：

```java
package io.veridex.evaluation.api;

import io.veridex.evaluation.domain.EvidenceRef;
import io.veridex.evaluation.domain.ExpectedBehavior;
import java.util.List;

public record CreateCaseRequest(String question, ExpectedBehavior expectedBehavior,
                                String expectedAnswer, List<EvidenceRef> evidence) {
}
```

`UpdateCaseRequest.java`：

```java
package io.veridex.evaluation.api;

import io.veridex.evaluation.domain.EvidenceRef;
import io.veridex.evaluation.domain.ExpectedBehavior;
import java.util.List;

public record UpdateCaseRequest(String question, ExpectedBehavior expectedBehavior,
                                String expectedAnswer, List<EvidenceRef> evidence) {
}
```

`VersionView.java`：

```java
package io.veridex.evaluation.api;

import java.util.UUID;

public record VersionView(UUID id, int versionNo, int caseCount, String createdAt) {
}
```

`VersionCaseView.java`：

```java
package io.veridex.evaluation.api;

import io.veridex.evaluation.domain.EvidenceRef;
import java.util.List;

public record VersionCaseView(int position, String question, String expectedBehavior,
                              String expectedAnswer, List<EvidenceRef> evidence) {
}
```

`VersionDetailView.java`：

```java
package io.veridex.evaluation.api;

import java.util.List;
import java.util.UUID;

public record VersionDetailView(UUID id, int versionNo, int caseCount, String createdAt,
                                List<VersionCaseView> cases) {
}
```

`PublishResult.java`：

```java
package io.veridex.evaluation.api;

import java.util.UUID;

public record PublishResult(UUID versionId, int versionNo, int caseCount) {
}
```

- [ ] **Step 4: 创建 `EvaluationController`**

```java
package io.veridex.evaluation.api;

import io.veridex.evaluation.application.EvaluationDatasetService;
import io.veridex.evaluation.domain.DatasetVersion;
import io.veridex.evaluation.domain.DatasetVersionCase;
import io.veridex.evaluation.domain.EvaluationCase;
import io.veridex.evaluation.domain.EvaluationDataset;
import io.veridex.evaluation.domain.EvidenceRef;
import io.veridex.iam.api.CurrentActor;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping("/api/evaluation/datasets")
public class EvaluationController {

    private final EvaluationDatasetService service;
    private final EvaluationAuthorization authorization;
    private final JsonMapper jsonMapper;

    public EvaluationController(EvaluationDatasetService service,
                                EvaluationAuthorization authorization,
                                JsonMapper jsonMapper) {
        this.service = service;
        this.authorization = authorization;
        this.jsonMapper = jsonMapper;
    }

    @PostMapping
    public ResponseEntity<DatasetView> create(@RequestBody CreateDatasetRequest body) {
        requireAdmin();
        EvaluationDataset dataset = service.create(CurrentActor.id(), body.name(), body.description());
        return ResponseEntity.status(HttpStatus.CREATED).body(toDatasetView(dataset));
    }

    @GetMapping
    public List<DatasetView> list() {
        requireAdmin();
        return service.listAll().stream().map(this::toDatasetView).toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<DatasetView> get(@PathVariable UUID id) {
        requireAdmin();
        return ResponseEntity.ok(toDatasetView(service.require(id)));
    }

    @PostMapping("/{id}/cases")
    public ResponseEntity<CaseView> addCase(@PathVariable UUID id, @RequestBody CreateCaseRequest body) {
        requireAdmin();
        EvaluationCase c = service.addCase(id, body.question(), body.expectedBehavior(),
                body.expectedAnswer(), body.evidence());
        return ResponseEntity.status(HttpStatus.CREATED).body(toCaseView(c));
    }

    @PutMapping("/{id}/cases/{caseId}")
    public ResponseEntity<CaseView> updateCase(@PathVariable UUID id, @PathVariable UUID caseId,
                                               @RequestBody UpdateCaseRequest body) {
        requireAdmin();
        EvaluationCase c = service.updateCase(id, caseId, body.question(), body.expectedBehavior(),
                body.expectedAnswer(), body.evidence());
        return ResponseEntity.ok(toCaseView(c));
    }

    @DeleteMapping("/{id}/cases/{caseId}")
    public ResponseEntity<Void> deleteCase(@PathVariable UUID id, @PathVariable UUID caseId) {
        requireAdmin();
        service.deleteCase(id, caseId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/cases")
    public List<CaseView> listCases(@PathVariable UUID id) {
        requireAdmin();
        return service.listCases(id).stream().map(this::toCaseView).toList();
    }

    @PostMapping("/{id}/publish")
    public ResponseEntity<PublishResult> publish(@PathVariable UUID id) {
        requireAdmin();
        DatasetVersion version = service.publish(id, CurrentActor.id());
        return ResponseEntity.ok(new PublishResult(version.getId(), version.getVersionNo(), version.getCaseCount()));
    }

    @GetMapping("/{id}/versions")
    public List<VersionView> listVersions(@PathVariable UUID id) {
        requireAdmin();
        return service.listVersions(id).stream().map(this::toVersionView).toList();
    }

    @GetMapping("/{id}/versions/{versionNo}")
    public ResponseEntity<VersionDetailView> version(@PathVariable UUID id, @PathVariable int versionNo) {
        requireAdmin();
        DatasetVersion version = service.requireVersion(id, versionNo);
        List<VersionCaseView> cases = service.listVersionCases(version.getId()).stream()
                .map(this::toVersionCaseView)
                .toList();
        return ResponseEntity.ok(new VersionDetailView(version.getId(), version.getVersionNo(),
                version.getCaseCount(), version.getCreatedAt().toString(), cases));
    }

    private void requireAdmin() {
        if (!authorization.isAdmin()) {
            throw new SecurityException("evaluation management requires admin role");
        }
    }

    private DatasetView toDatasetView(EvaluationDataset d) {
        return new DatasetView(d.getId(), d.getName(), d.getDescription(),
                (int) service.caseCount(d.getId()), service.latestVersionNo(d.getId()));
    }

    private CaseView toCaseView(EvaluationCase c) {
        return new CaseView(c.getId(), c.getQuestion(), c.getExpectedBehavior().name(),
                c.getExpectedAnswer(), deserializeEvidence(c.getEvidenceJson()));
    }

    private VersionView toVersionView(DatasetVersion v) {
        return new VersionView(v.getId(), v.getVersionNo(), v.getCaseCount(), v.getCreatedAt().toString());
    }

    private VersionCaseView toVersionCaseView(DatasetVersionCase c) {
        return new VersionCaseView(c.getPosition(), c.getQuestion(), c.getExpectedBehavior().name(),
                c.getExpectedAnswer(), deserializeEvidence(c.getEvidenceJson()));
    }

    private List<EvidenceRef> deserializeEvidence(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return jsonMapper.readValue(json, new TypeReference<List<EvidenceRef>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("failed to deserialize evidence", e);
        }
    }
}
```

- [ ] **Step 5: 编译验证**

Run: `./mvnw -pl backend test-compile`
Expected: PASS（`BUILD SUCCESS`）。

- [ ] **Step 6: 运行 ArchitectureTest 确认模块边界**

Run: `./mvnw -pl backend test -Dtest=ArchitectureTest`
Expected: PASS。若失败，说明 `evaluation` 模块 import 了未声明的模块，检查 import 修正。

- [ ] **Step 7: 提交**

```bash
git add backend/src/main/java/io/veridex/evaluation
git commit -m "feat: expose versioned evaluation dataset REST API"
```

---

### Task 5: 发布语义真库集成测试

**Files:**
- Create: `backend/src/test/java/io/veridex/evaluation/EvaluationDatasetIntegrationTest.java`

**Interfaces:**
- Consumes: Task 3 的 `EvaluationDatasetService`；Task 1 的 V8 迁移；`io.veridex.support.PostgresIntegrationTest`。
- Produces: 验证「发布后改工作集不影响已发布版本」「版本号递增」「空集不可发布」「ANSWER 无证据被拒」在真实 Postgres 上的行为。

- [ ] **Step 1: 写失败测试**

创建 `EvaluationDatasetIntegrationTest.java`：

```java
package io.veridex.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.veridex.evaluation.application.EvaluationDatasetService;
import io.veridex.evaluation.domain.DatasetVersion;
import io.veridex.evaluation.domain.DatasetVersionCase;
import io.veridex.evaluation.domain.EvaluationCase;
import io.veridex.evaluation.domain.EvaluationDataset;
import io.veridex.evaluation.domain.ExpectedBehavior;
import io.veridex.evaluation.domain.EvidenceRef;
import io.veridex.support.PostgresIntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class EvaluationDatasetIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    EvaluationDatasetService service;

    private static final UUID ACTOR = UUID.randomUUID();
    private static final EvidenceRef EVIDENCE = new EvidenceRef(UUID.randomUUID(), List.of(0, 1));

    @Test
    void publishFreezesWorkSetAndIncrementsVersionNo() {
        EvaluationDataset dataset = service.create(ACTOR, "回归集", "描述");
        service.addCase(dataset.getId(), "问题一", ExpectedBehavior.ANSWER, "答案一", List.of(EVIDENCE));

        DatasetVersion v1 = service.publish(dataset.getId(), ACTOR);
        assertThat(v1.getVersionNo()).isEqualTo(1);
        assertThat(v1.getCaseCount()).isEqualTo(1);

        EvaluationCase c = service.listCases(dataset.getId()).get(0);
        service.updateCase(dataset.getId(), c.getId(), "问题一(改)", ExpectedBehavior.ANSWER, "答案一(改)", List.of(EVIDENCE));
        DatasetVersion v2 = service.publish(dataset.getId(), ACTOR);
        assertThat(v2.getVersionNo()).isEqualTo(2);

        List<DatasetVersionCase> v1Cases = service.listVersionCases(v1.getId());
        assertThat(v1Cases).hasSize(1);
        assertThat(v1Cases.get(0).getQuestion()).isEqualTo("问题一");
        assertThat(v1Cases.get(0).getExpectedAnswer()).isEqualTo("答案一");

        List<DatasetVersionCase> v2Cases = service.listVersionCases(v2.getId());
        assertThat(v2Cases).hasSize(1);
        assertThat(v2Cases.get(0).getQuestion()).isEqualTo("问题一(改)");
        assertThat(v2Cases.get(0).getExpectedAnswer()).isEqualTo("答案一(改)");
    }

    @Test
    void publishEmptyDatasetIsRejected() {
        EvaluationDataset dataset = service.create(ACTOR, "空集", null);
        assertThatThrownBy(() -> service.publish(dataset.getId(), ACTOR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty");
    }

    @Test
    void answerCaseWithoutEvidenceIsRejected() {
        EvaluationDataset dataset = service.create(ACTOR, "回归集", null);
        assertThatThrownBy(() -> service.addCase(dataset.getId(), "问题", ExpectedBehavior.ANSWER, "答案", List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("evidence");
    }
}
```

- [ ] **Step 2: 运行测试确认通过**

Run: `./mvnw -pl backend test -Dtest=EvaluationDatasetIntegrationTest`
Expected: PASS（需要 Docker 运行，Testcontainers 会复用共享 Postgres 容器）。

- [ ] **Step 3: 提交**

```bash
git add backend/src/test/java/io/veridex/evaluation/EvaluationDatasetIntegrationTest.java
git commit -m "test: cover evaluation publish freeze semantics against Postgres"
```

---

### Task 6: 前端 API 客户端

**Files:**
- Create: `web/src/features/evaluation/evaluationApi.ts`

**Interfaces:**
- Consumes: Task 4 的 `/api/evaluation/*` DTO 字段名。
- Produces: `evaluationApi` 对象，Task 7/8 使用。导出的类型名与字段必须与后端完全一致。

- [ ] **Step 1: 创建 `evaluationApi.ts`**

```ts
export type EvidenceRef = { documentVersionId: string; chunkIndexes: number[] }

export type DatasetView = {
  id: string
  name: string
  description: string | null
  caseCount: number
  latestVersionNo: number | null
}

export type ExpectedBehavior = 'ANSWER' | 'REFUSE'

export type CaseView = {
  id: string
  question: string
  expectedBehavior: ExpectedBehavior
  expectedAnswer: string | null
  evidence: EvidenceRef[]
}

export type VersionView = { id: string; versionNo: number; caseCount: number; createdAt: string }

export type VersionCaseView = {
  position: number
  question: string
  expectedBehavior: ExpectedBehavior
  expectedAnswer: string | null
  evidence: EvidenceRef[]
}

export type VersionDetail = {
  id: string
  versionNo: number
  caseCount: number
  createdAt: string
  cases: VersionCaseView[]
}

export type PublishResult = { versionId: string; versionNo: number; caseCount: number }

export type CaseInput = {
  question: string
  expectedBehavior: ExpectedBehavior
  expectedAnswer: string | null
  evidence: EvidenceRef[]
}

const json = async <T>(response: Response): Promise<T> => {
  if (!response.ok) {
    const message = await response.text().catch(() => '')
    throw new Error(message || `请求失败 (${response.status})`)
  }
  return (response.status === 204 ? null : await response.json()) as T
}

const post = (payload: unknown): RequestInit => ({
  method: 'POST',
  credentials: 'include',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify(payload),
})

const put = (payload: unknown): RequestInit => ({
  method: 'PUT',
  credentials: 'include',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify(payload),
})

export const evaluationApi = {
  list: (): Promise<DatasetView[]> =>
    fetch('/api/evaluation/datasets', { credentials: 'include' }).then((r) => json<DatasetView[]>(r)),
  get: (id: string): Promise<DatasetView> =>
    fetch(`/api/evaluation/datasets/${id}`, { credentials: 'include' }).then((r) => json<DatasetView>(r)),
  create: (name: string, description?: string): Promise<DatasetView> =>
    fetch('/api/evaluation/datasets', post({ name, description })).then((r) => json<DatasetView>(r)),
  cases: (id: string): Promise<CaseView[]> =>
    fetch(`/api/evaluation/datasets/${id}/cases`, { credentials: 'include' }).then((r) => json<CaseView[]>(r)),
  addCase: (id: string, payload: CaseInput): Promise<CaseView> =>
    fetch(`/api/evaluation/datasets/${id}/cases`, post(payload)).then((r) => json<CaseView>(r)),
  updateCase: (id: string, caseId: string, payload: CaseInput): Promise<CaseView> =>
    fetch(`/api/evaluation/datasets/${id}/cases/${caseId}`, put(payload)).then((r) => json<CaseView>(r)),
  deleteCase: (id: string, caseId: string): Promise<null> =>
    fetch(`/api/evaluation/datasets/${id}/cases/${caseId}`, { method: 'DELETE', credentials: 'include' }).then((r) => json<null>(r)),
  publish: (id: string): Promise<PublishResult> =>
    fetch(`/api/evaluation/datasets/${id}/publish`, { method: 'POST', credentials: 'include' }).then((r) => json<PublishResult>(r)),
  versions: (id: string): Promise<VersionView[]> =>
    fetch(`/api/evaluation/datasets/${id}/versions`, { credentials: 'include' }).then((r) => json<VersionView[]>(r)),
  version: (id: string, versionNo: number): Promise<VersionDetail> =>
    fetch(`/api/evaluation/datasets/${id}/versions/${versionNo}`, { credentials: 'include' }).then((r) => json<VersionDetail>(r)),
}
```

- [ ] **Step 2: 类型检查**

Run: `npm --prefix web run build`
Expected: PASS（当前文件尚未被引用，仅做类型检查）。

- [ ] **Step 3: 提交**

```bash
git add web/src/features/evaluation/evaluationApi.ts
git commit -m "feat: add evaluation API client"
```

---

### Task 7: 前端评测集管理页

**Files:**
- Create: `web/src/features/evaluation/components/EvidencePicker.tsx`
- Create: `web/src/features/evaluation/components/CaseWorkspace.tsx`
- Create: `web/src/features/evaluation/components/DatasetList.tsx`
- Create: `web/src/features/evaluation/EvaluationPage.tsx`
- Modify: `web/src/styles.css`

**Interfaces:**
- Consumes: Task 6 的 `evaluationApi` 类型；`knowledgeApi`（`features/knowledge/knowledgeApi.ts` 的 `list`/`documents`/`versions`/`chunks`）用于证据级联选择；`PageHeader`、`Toast` 等既有组件。
- Produces: `EvaluationPage` 组件，供 Task 8 的 `routes.tsx` 挂载。Props 契约：
  - `EvidencePicker({ onSelect }: { onSelect: (evidence: EvidenceRef[]) => void })` — 内部无外部受控输入，选择完成后回调。
  - `CaseWorkspace({ dataset, refreshKey, onChanged, onNotify })` — 由 `EvaluationPage` 调用。
  - `DatasetList({ datasets, selectedId, loading, error, creating, onSelect, onRetry, onCreate })`。

- [ ] **Step 1: 创建 `EvidencePicker`**

```tsx
import { useCallback, useEffect, useState } from 'react'
import { knowledgeApi, type ChunkPreview, type DocumentSummary, type DocumentVersion } from '../../knowledge/knowledgeApi'
import type { EvidenceRef } from '../evaluationApi'

type KnowledgeBase = { id: string; name: string }

export function EvidencePicker({ onSelect }: { onSelect: (evidence: EvidenceRef[]) => void }) {
  const [bases, setBases] = useState<KnowledgeBase[]>([])
  const [baseId, setBaseId] = useState<string>('')
  const [documents, setDocuments] = useState<DocumentSummary[]>([])
  const [documentId, setDocumentId] = useState<string>('')
  const [versions, setVersions] = useState<DocumentVersion[]>([])
  const [versionId, setVersionId] = useState<string>('')
  const [chunks, setChunks] = useState<ChunkPreview[]>([])
  const [checked, setChecked] = useState<number[]>([])
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    knowledgeApi.list().then(setBases).catch((e) => setError(e instanceof Error ? e.message : '加载知识库失败'))
  }, [])

  const loadDocuments = useCallback((id: string) => {
    setBaseId(id)
    setDocumentId('')
    setVersionId('')
    setChunks([])
    setChecked([])
    knowledgeApi.documents(id).then(setDocuments).catch((e) => setError(e instanceof Error ? e.message : '加载文档失败'))
  }, [])

  const loadVersions = useCallback((id: string) => {
    setDocumentId(id)
    setVersionId('')
    setChunks([])
    setChecked([])
    knowledgeApi.versions(id).then(setVersions).catch((e) => setError(e instanceof Error ? e.message : '加载版本失败'))
  }, [])

  const loadChunks = useCallback((id: string) => {
    setVersionId(id)
    setChecked([])
    knowledgeApi.chunks(documentId, id).then(setChunks).catch((e) => setError(e instanceof Error ? e.message : '加载分块失败'))
  }, [documentId])

  const toggle = (index: number) => {
    setChecked((current) => (current.includes(index) ? current.filter((i) => i !== index) : [...current, index].sort((a, b) => a - b)))
  }

  const apply = () => {
    if (versionId && checked.length > 0) {
      onSelect([{ documentVersionId: versionId, chunkIndexes: checked }])
    }
  }

  return (
    <div className="evidence-picker">
      {error && <p className="row-error" role="alert">{error}</p>}
      <label>知识库<select value={baseId} onChange={(e) => loadDocuments(e.target.value)}><option value="">选择知识库</option>{bases.map((b) => <option key={b.id} value={b.id}>{b.name}</option>)}</select></label>
      <label>文档<select value={documentId} onChange={(e) => loadVersions(e.target.value)} disabled={!baseId}><option value="">选择文档</option>{documents.map((d) => <option key={d.id} value={d.id}>{d.filename}</option>)}</select></label>
      <label>文档版本<select value={versionId} onChange={(e) => loadChunks(e.target.value)} disabled={!documentId}><option value="">选择版本</option>{versions.filter((v) => v.status === 'READY').map((v) => <option key={v.id} value={v.id}>v{v.versionNo}</option>)}</select></label>
      {chunks.length > 0 && (
        <div className="evidence-chunks">
          {chunks.map((chunk) => (
            <label key={chunk.index} className="evidence-chunk"><input type="checkbox" checked={checked.includes(chunk.index)} onChange={() => toggle(chunk.index)} /><span><strong>#{chunk.index}</strong> {chunk.text.slice(0, 80)}{chunk.text.length > 80 ? '…' : ''}</span></label>
          ))}
        </div>
      )}
      <button className="secondary-button" type="button" onClick={apply} disabled={!versionId || checked.length === 0}>确认证据</button>
    </div>
  )
}
```

- [ ] **Step 2: 创建 `CaseWorkspace`**

```tsx
import { useCallback, useEffect, useState } from 'react'
import { evaluationApi, type CaseInput, type CaseView, type DatasetView, type EvidenceRef, type ExpectedBehavior } from '../evaluationApi'
import { EvidencePicker } from './EvidencePicker'

const emptyInput = (): CaseInput => ({ question: '', expectedBehavior: 'ANSWER', expectedAnswer: '', evidence: [] })

export function CaseWorkspace({ dataset, refreshKey, onChanged, onNotify }: { dataset: DatasetView; refreshKey: number; onChanged: () => void; onNotify: (type: 'success' | 'error', message: string) => void }) {
  const [cases, setCases] = useState<CaseView[]>([])
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [input, setInput] = useState<CaseInput>(emptyInput())
  const [saving, setSaving] = useState(false)

  const load = useCallback(async () => {
    const next = await evaluationApi.cases(dataset.id)
    setCases(next)
    setSelectedId((current) => next.find((c) => c.id === current)?.id ?? next[0]?.id ?? null)
  }, [dataset.id])

  useEffect(() => { void load() }, [load, refreshKey])

  useEffect(() => {
    const current = cases.find((c) => c.id === selectedId)
    if (current) {
      setInput({ question: current.question, expectedBehavior: current.expectedBehavior, expectedAnswer: current.expectedAnswer ?? '', evidence: current.evidence })
    }
  }, [selectedId, cases])

  const save = async () => {
    setSaving(true)
    try {
      const payload: CaseInput = {
        question: input.question,
        expectedBehavior: input.expectedBehavior,
        expectedAnswer: input.expectedBehavior === 'ANSWER' ? input.expectedAnswer : null,
        evidence: input.expectedBehavior === 'ANSWER' ? input.evidence : [],
      }
      if (selectedId) {
        await evaluationApi.updateCase(dataset.id, selectedId, payload)
      } else {
        await evaluationApi.addCase(dataset.id, payload)
      }
      onNotify('success', selectedId ? '用例已更新' : '用例已创建')
      await load()
      onChanged()
    } catch (e) {
      onNotify('error', e instanceof Error ? e.message : '保存失败')
    } finally {
      setSaving(false)
    }
  }

  const remove = async () => {
    if (!selectedId) return
    try {
      await evaluationApi.deleteCase(dataset.id, selectedId)
      onNotify('success', '用例已删除')
      await load()
      onChanged()
    } catch (e) {
      onNotify('error', e instanceof Error ? e.message : '删除失败')
    }
  }

  const setBehavior = (behavior: ExpectedBehavior) => setInput((i) => ({ ...i, expectedBehavior: behavior }))

  return (
    <div className="case-workspace">
      <div className="case-list-panel panel">
        <div className="panel-header"><div><h2>用例工作集</h2><p>草稿中的用例，发布时冻结为版本。</p></div><button className="text-button" type="button" onClick={() => { setSelectedId(null); setInput(emptyInput()) }}>新增用例</button></div>
        <ul className="case-list">
          {cases.map((c) => (
            <li key={c.id}><button className={c.id === selectedId ? 'active' : ''} type="button" onClick={() => setSelectedId(c.id)}><strong>{c.question.slice(0, 40)}{c.question.length > 40 ? '…' : ''}</strong><small>{c.expectedBehavior}</small></button></li>
          ))}
          {cases.length === 0 && <li className="case-list-empty">还没有用例</li>}
        </ul>
      </div>
      <div className="case-editor panel">
        <div className="panel-header"><h2>{selectedId ? '编辑用例' : '新增用例'}</h2></div>
        <div className="case-editor-body">
          <label>问题<textarea value={input.question} onChange={(e) => setInput((i) => ({ ...i, question: e.target.value }))} rows={2} /></label>
          <div className="behavior-toggle" role="radiogroup" aria-label="期望行为">
            <button type="button" className={input.expectedBehavior === 'ANSWER' ? 'active' : ''} onClick={() => setBehavior('ANSWER')}>ANSWER（有证据）</button>
            <button type="button" className={input.expectedBehavior === 'REFUSE' ? 'active' : ''} onClick={() => setBehavior('REFUSE')}>REFUSE（拒答）</button>
          </div>
          {input.expectedBehavior === 'ANSWER' && (
            <>
              <label>期望答案<textarea value={input.expectedAnswer} onChange={(e) => setInput((i) => ({ ...i, expectedAnswer: e.target.value }))} rows={3} /></label>
              <div className="evidence-section"><strong>Ground-truth 证据</strong><EvidencePicker onSelect={(evidence) => setInput((i) => ({ ...i, evidence }))} /><div className="evidence-summary">{input.evidence.length > 0 ? `已选 ${input.evidence[0].chunkIndexes.length} 个分块` : '尚未选择证据'}</div></div>
            </>
          )}
          <div className="case-editor-actions">
            <button className="primary-button" type="button" onClick={() => void save()} disabled={saving}>{saving ? '保存中' : '保存用例'}</button>
            {selectedId && <button className="text-button danger" type="button" onClick={() => void remove()}>删除用例</button>}
          </div>
        </div>
      </div>
    </div>
  )
}
```

- [ ] **Step 3: 创建 `DatasetList`**

```tsx
import { useState } from 'react'
import type { DatasetView } from '../evaluationApi'

export function DatasetList({ datasets, selectedId, loading, error, creating, onSelect, onRetry, onCreate }: { datasets: DatasetView[]; selectedId: string | null; loading: boolean; error: string | null; creating: boolean; onSelect: (d: DatasetView) => void; onRetry: () => void; onCreate: (name: string) => Promise<void> }) {
  const [name, setName] = useState('')

  const submit = async () => {
    if (!name.trim()) return
    await onCreate(name.trim())
    setName('')
  }

  return (
    <div className="panel knowledge-base-panel">
      <div className="panel-header"><h2>数据集</h2></div>
      <form className="inline-create-form" onSubmit={(e) => { e.preventDefault(); void submit() }}>
        <input value={name} onChange={(e) => setName(e.target.value)} placeholder="数据集名称" aria-label="数据集名称" />
        <button className="primary-button" type="submit" disabled={creating || !name.trim()}>{creating ? '创建中' : '新建数据集'}</button>
      </form>
      {loading && <div role="status" className="loading-copy" style={{ padding: '14px' }}>正在加载数据集</div>}
      {error && <div className="state-block compact"><p role="alert">{error}</p><button className="secondary-button" type="button" onClick={onRetry}>重试</button></div>}
      <ul className="knowledge-base-list">
        {datasets.map((d) => (
          <li key={d.id}><button className={d.id === selectedId ? 'active' : ''} type="button" onClick={() => onSelect(d)}><span className="kb-icon">E</span><span><strong>{d.name}</strong><small>{d.caseCount} 用例 · v{d.latestVersionNo ?? 0}</small></span></button></li>
        ))}
        {!loading && !error && datasets.length === 0 && <li className="state-block compact"><h3>创建第一个数据集</h3><p>评测集用于管理 ground-truth 用例并发布不可变版本。</p></li>}
      </ul>
    </div>
  )
}
```

- [ ] **Step 4: 创建 `EvaluationPage`**

```tsx
import { FolderOpen, RocketLaunch } from '@phosphor-icons/react'
import { useCallback, useEffect, useState } from 'react'
import { PageHeader } from '../../app/PageHeader'
import { Toast } from '../../components/Toast'
import { evaluationApi, type DatasetView, type VersionDetail, type VersionView } from './evaluationApi'
import { CaseWorkspace } from './components/CaseWorkspace'
import { DatasetList } from './components/DatasetList'

export function EvaluationPage() {
  const [datasets, setDatasets] = useState<DatasetView[]>([])
  const [selected, setSelected] = useState<DatasetView | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [creating, setCreating] = useState(false)
  const [publishing, setPublishing] = useState(false)
  const [refreshKey, setRefreshKey] = useState(0)
  const [versions, setVersions] = useState<VersionView[]>([])
  const [versionDetail, setVersionDetail] = useState<VersionDetail | null>(null)
  const [toast, setToast] = useState<{ type: 'success' | 'error'; message: string } | null>(null)

  const loadDatasets = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const next = await evaluationApi.list()
      setDatasets(next)
      setSelected((current) => next.find((d) => d.id === current?.id) ?? next[0] ?? null)
    } catch (e) {
      setError(e instanceof Error ? e.message : '数据集加载失败')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => { void loadDatasets() }, [loadDatasets])

  useEffect(() => {
    if (!selected) return
    evaluationApi.versions(selected.id).then(setVersions).catch(() => setVersions([]))
  }, [selected, refreshKey])

  const createDataset = async (name: string) => {
    setCreating(true)
    try {
      const created = await evaluationApi.create(name)
      setDatasets((current) => [created, ...current])
      setSelected(created)
    } catch (e) {
      setToast({ type: 'error', message: e instanceof Error ? e.message : '创建失败' })
    } finally {
      setCreating(false)
    }
  }

  const publish = async () => {
    if (!selected) return
    setPublishing(true)
    setToast(null)
    try {
      const result = await evaluationApi.publish(selected.id)
      setToast({ type: 'success', message: `已发布版本 v${result.versionNo}` })
      setRefreshKey((k) => k + 1)
    } catch (e) {
      setToast({ type: 'error', message: e instanceof Error ? e.message : '发布失败' })
    } finally {
      setPublishing(false)
    }
  }

  const openVersion = async (versionNo: number) => {
    if (!selected) return
    try {
      setVersionDetail(await evaluationApi.version(selected.id, versionNo))
    } catch (e) {
      setToast({ type: 'error', message: e instanceof Error ? e.message : '版本加载失败' })
    }
  }

  return (
    <section className="workspace-page evaluation-page">
      <PageHeader title="质量评测" description="管理版本化评测集与 ground-truth 用例。" meta={`${datasets.length} 个数据集`} />
      <div className="knowledge-layout">
        <DatasetList datasets={datasets} selectedId={selected?.id ?? null} loading={loading} error={error} creating={creating} onSelect={setSelected} onRetry={() => void loadDatasets()} onCreate={createDataset} />
        <div className="knowledge-workspace">
          {selected ? (
            <>
              <header className="knowledge-workspace-header"><div><p className="section-kicker">当前数据集</p><h2>{selected.name}</h2><p>{selected.description || '管理用例并发布不可变版本。'}</p></div><div className="workspace-header-actions"><button className="primary-button" type="button" onClick={() => void publish()} disabled={publishing}><RocketLaunch size={18} aria-hidden="true" />{publishing ? '正在发布' : '发布版本'}</button></div></header>
              <CaseWorkspace dataset={selected} refreshKey={refreshKey} onChanged={() => setRefreshKey((k) => k + 1)} onNotify={(type, message) => setToast({ type, message })} />
              <div className="panel version-panel">
                <div className="panel-header"><h2>版本历史</h2></div>
                <ul className="version-list">
                  {versions.map((v) => <li key={v.id}><button type="button" onClick={() => void openVersion(v.versionNo)}><strong>v{v.versionNo}</strong><small>{v.caseCount} 用例 · {v.createdAt}</small></button></li>)}
                  {versions.length === 0 && <li className="case-list-empty">尚未发布版本</li>}
                </ul>
              </div>
              {versionDetail && (
                <div className="panel version-detail">
                  <div className="panel-header"><h2>版本 v{versionDetail.versionNo}（已冻结）</h2><button className="text-button" type="button" onClick={() => setVersionDetail(null)}>关闭</button></div>
                  <ol>{versionDetail.cases.map((c) => <li key={c.position}><strong>{c.question}</strong><small>{c.expectedBehavior}</small></li>)}</ol>
                </div>
              )}
            </>
          ) : (
            <div className="panel state-block workspace-empty"><FolderOpen size={34} aria-hidden="true" /><h2>选择一个数据集</h2><p>从左侧选择数据集，或创建第一个数据集开始管理用例。</p></div>
          )}
        </div>
      </div>
      {toast && <Toast type={toast.type} message={toast.message} onDismiss={() => setToast(null)} />}
    </section>
  )
}
```

- [ ] **Step 5: 追加评测页样式**

在 `styles.css` 末尾（`@media (prefers-reduced-motion: reduce)` 之前）追加：

```css
/* Evaluation */
.case-workspace { display: grid; grid-template-columns: minmax(220px, .8fr) minmax(0, 1.2fr); gap: 14px; align-items: start; }
.case-list { margin: 0; padding: 8px; list-style: none; display: grid; gap: 3px; }
.case-list button { width: 100%; min-height: 52px; padding: 8px; display: grid; gap: 3px; border: 1px solid transparent; border-radius: var(--radius-control); background: transparent; text-align: left; cursor: pointer; }
.case-list button:hover { background: var(--surface-muted); }
.case-list button.active { border-color: #bad1cb; background: var(--surface-accent); }
.case-list strong { overflow: hidden; color: var(--text-primary); font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }
.case-list small { color: var(--text-muted); font-size: 10px; }
.case-list-empty { padding: 16px; color: var(--text-muted); font-size: 12px; }
.case-editor-body { padding: 16px; display: grid; gap: 14px; }
.case-editor-body label { display: grid; gap: 7px; color: var(--text-primary); font-size: 12px; font-weight: 650; }
.case-editor-body textarea { width: 100%; padding: 9px 11px; border: 1px solid var(--border-strong); border-radius: var(--radius-control); background: #f8fafa; color: var(--text-primary); font: inherit; resize: vertical; }
.behavior-toggle { display: flex; gap: 8px; }
.behavior-toggle button { min-height: 36px; padding: 0 12px; border: 1px solid var(--border-strong); border-radius: var(--radius-control); background: var(--surface-panel); font-size: 12px; font-weight: 650; cursor: pointer; }
.behavior-toggle button.active { border-color: var(--accent); background: var(--surface-accent); color: var(--accent); }
.evidence-section { display: grid; gap: 9px; }
.evidence-picker { display: grid; gap: 9px; padding: 12px; border: 1px solid var(--border); border-radius: var(--radius-control); background: var(--surface-muted); }
.evidence-picker label { display: grid; gap: 5px; font-size: 11px; }
.evidence-picker select { min-height: 36px; padding: 0 9px; border: 1px solid var(--border-strong); border-radius: var(--radius-control); background: var(--surface-panel); color: var(--text-primary); }
.evidence-chunks { max-height: 170px; overflow-y: auto; display: grid; gap: 4px; }
.evidence-chunk { display: flex; gap: 8px; align-items: flex-start; font-size: 11px; color: var(--text-secondary); }
.evidence-chunk span { display: grid; gap: 1px; }
.evidence-summary { color: var(--text-secondary); font-size: 11px; }
.case-editor-actions { display: flex; gap: 8px; align-items: center; }
.version-panel, .version-detail { overflow: hidden; }
.version-list { margin: 0; padding: 8px; list-style: none; display: grid; gap: 3px; }
.version-list button { width: 100%; min-height: 44px; padding: 7px 9px; display: grid; gap: 2px; border: 1px solid transparent; border-radius: var(--radius-control); background: transparent; text-align: left; cursor: pointer; }
.version-list button:hover { background: var(--surface-muted); }
.version-list strong { color: var(--text-primary); font-size: 12px; }
.version-list small { color: var(--text-muted); font-size: 10px; }
.version-detail ol { margin: 0; padding: 8px 16px 16px 32px; }
.version-detail li { padding: 7px 0; border-bottom: 1px solid var(--border); }
.version-detail strong { display: block; font-size: 13px; }
.version-detail small { color: var(--text-muted); font-size: 11px; }
```

- [ ] **Step 6: 前端测试 + 构建**

Run: `npm --prefix web run lint && npm --prefix web run build`
Expected: PASS（无 lint 错误、无类型错误）。

- [ ] **Step 7: 提交**

```bash
git add web/src/features/evaluation web/src/styles.css
git commit -m "feat: add evaluation dataset management page"
```

---

### Task 8: 路由替换与前端测试

**Files:**
- Modify: `web/src/app/routes.tsx`
- Modify: `web/src/app/App.test.tsx`
- Create: `web/src/features/evaluation/EvaluationPage.test.tsx`

**Interfaces:**
- Consumes: Task 7 的 `EvaluationPage`；Task 6 的 `evaluationApi`（经 fetch 被 mock）。
- Produces: `/evaluation` 路由渲染真实管理页，`App.test.tsx` 与 `EvaluationPage.test.tsx` 全绿。

- [ ] **Step 1: 替换路由**

修改 `routes.tsx`：新增 `EvaluationPage` import，并将 `/evaluation` 的 `content` 改为 `<EvaluationPage />`：

```tsx
import { ChatCircleText, Database, Gauge, ShieldCheck } from '@phosphor-icons/react'
import type { ComponentType, ReactNode } from 'react'
import { KnowledgePage } from '../features/knowledge/KnowledgePage'
import { QaPage } from '../features/qa/QaPage'
import { EvaluationPage } from '../features/evaluation/EvaluationPage'
import { ComingSoonPage } from './ComingSoonPage'
```

```tsx
  {
    path: '/evaluation', label: '质量评测', englishLabel: 'Evaluation', icon: Gauge,
    content: <EvaluationPage />,
  },
```

- [ ] **Step 2: 更新 `App.test.tsx`**

把原 `workspaces.filter(([p]) => p !== '/knowledge' && p !== '/workbench')` 的 `test.each` 覆盖 `/evaluation` 与 `/admin` 的逻辑拆开：`/evaluation` 断言真实页面，`/admin` 仍断言 ComingSoonPage。替换原 `test.each` 块为：

```tsx
  test('renders the evaluation workspace page for /evaluation', async () => {
    render(
      <MemoryRouter initialEntries={['/evaluation']}>
        <App />
      </MemoryRouter>,
    )

    expect(await screen.findByRole('heading', { name: '质量评测', level: 1 })).toBeInTheDocument()
    expect(await screen.findByRole('heading', { name: '数据集', level: 2 })).toBeInTheDocument()
  })

  test('renders the administration coming soon page for /admin', async () => {
    render(
      <MemoryRouter initialEntries={['/admin']}>
        <App />
      </MemoryRouter>,
    )

    expect(await screen.findByRole('heading', { name: '平台管理', level: 1 })).toBeInTheDocument()
    expect(screen.getAllByText('Phase 5')).not.toHaveLength(0)
    expect(screen.getByRole('link', { name: '前往知识管理' })).toHaveAttribute('href', '/knowledge')
  })
```

- [ ] **Step 3: 写失败测试 `EvaluationPage.test.tsx`**

```tsx
import { fireEvent, render, screen } from '@testing-library/react'
import { beforeEach, expect, test, vi } from 'vitest'
import { EvaluationPage } from './EvaluationPage'

beforeEach(() => vi.restoreAllMocks())

test('shows the empty evaluation workspace after loading', async () => {
  vi.stubGlobal('fetch', vi.fn(() => Promise.resolve(new Response('[]', { status: 200, headers: { 'Content-Type': 'application/json' } }))))

  render(<EvaluationPage />)

  expect(await screen.findByRole('heading', { name: '创建第一个数据集' })).toBeInTheDocument()
  expect(screen.getByRole('heading', { name: '选择一个数据集' })).toBeInTheDocument()
})

test('creates a dataset from the inline form', async () => {
  const fetchMock = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input)
    const json = (body: unknown) => Promise.resolve(new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } }))
    if (url === '/api/evaluation/datasets' && init?.method === 'POST') {
      return json({ id: 'ds-1', name: '回归集', description: null, caseCount: 0, latestVersionNo: null })
    }
    if (url === '/api/evaluation/datasets') return json([])
    if (url.endsWith('/versions')) return json([])
    return json([])
  })
  vi.stubGlobal('fetch', fetchMock)

  render(<EvaluationPage />)

  fireEvent.change(await screen.findByRole('textbox', { name: '数据集名称' }), { target: { value: '回归集' } })
  fireEvent.click(screen.getByRole('button', { name: '新建数据集' }))

  expect(await screen.findByRole('heading', { name: '回归集' })).toBeInTheDocument()
})

test('publishes the selected dataset and refreshes versions', async () => {
  let published = false
  const fetchMock = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input)
    const json = (body: unknown) => Promise.resolve(new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } }))
    if (url === '/api/evaluation/datasets') return json([{ id: 'ds-1', name: '回归集', description: null, caseCount: 1, latestVersionNo: null }])
    if (url.endsWith('/cases')) return json([{ id: 'c-1', question: '问题一', expectedBehavior: 'ANSWER', expectedAnswer: '答案一', evidence: [] }])
    if (url.endsWith('/publish') && init?.method === 'POST') {
      published = true
      return json({ versionId: 'v-1', versionNo: 1, caseCount: 1 })
    }
    if (url.endsWith('/versions')) return json(published ? [{ id: 'v-1', versionNo: 1, caseCount: 1, createdAt: '2026-08-15T00:00:00Z' }] : [])
    return json([])
  })
  vi.stubGlobal('fetch', fetchMock)

  render(<EvaluationPage />)

  fireEvent.click(await screen.findByRole('button', { name: '发布版本' }))

  expect(await screen.findByRole('status')).toHaveTextContent('已发布版本 v1')
  expect(await screen.findByText('v1')).toBeInTheDocument()
})
```

- [ ] **Step 4: 运行测试确认通过**

Run: `npm --prefix web test`
Expected: PASS（`EvaluationPage.test.tsx`、`App.test.tsx` 全绿）。

- [ ] **Step 5: 提交**

```bash
git add web/src/app/routes.tsx web/src/app/App.test.tsx web/src/features/evaluation/EvaluationPage.test.tsx
git commit -m "feat: wire evaluation page into console and cover interactions"
```

---

### Task 9: 全量质量门禁

**Files:**
- 无新增文件。

**Interfaces:**
- Consumes: 所有前置 Task。

- [ ] **Step 1: 运行完整门禁**

Run: `./scripts/verify.sh`
Expected: 四段全部通过——`[1/4] Maven clean verify`、`[2/4] Frontend tests (Vitest)`、`[3/4] Frontend production build`、`[4/4] git diff --check`，最终输出 `verify.sh: all checks passed`。

- [ ] **Step 2: 若有失败，修复后重跑**

不要局部跳过；任何一段失败都要修复对应代码后重跑完整 `./scripts/verify.sh` 直到全绿。

- [ ] **Step 3: 确认工作树状态**

Run: `git status`
Expected: 只有本计划的文件变更，无意外文件。

---

## Self-Review

**Spec coverage：**
- D1 领域模型 → Task 2（4 实体 + EvidenceRef + ExpectedBehavior）
- D2 版本化语义 = draft 工作集 + publish 冻结 → Task 3 `publish` / Task 5 集成测试
- D3 ground-truth 证据 = JSONB `[{documentVersionId, chunkIndexes[]}]` → EvidenceRef + V8 `evidence jsonb`
- D4 期望行为 = ANSWER/REFUSE 互斥 → `normalizeEvidence` 校验
- D5 模块边界收敛 → Task 4 `package-info` + ArchitectureTest
- D6 迁移 V8 → Task 1
- D7 API 命名空间 `/api/evaluation/*` → Task 4
- D8 前端替换 ComingSoonPage → Task 7/8
- D9 权限复用现有角色 → `EvaluationAuthorization`

**验收标准对照：**
- 创建数据集/用例/发布 → Task 7 UI + Task 8 测试
- 发布后改工作集不影响版本 → Task 5 集成测试
- 空集 / ANSWER 无证据被拒 → Task 3 单测 + Task 5 集成测试
- `./scripts/verify.sh` 全绿 → Task 9

**风险与注意：**
- Jackson 3 (`tools.jackson`) 的 `JsonMapper` 由 Spring Boot 自动配置提供 bean，本计划不新增 `@Bean`，避免与现有 `SecurityConfig`/`OutboxWriter` 等注入冲突。
- 模块边界若在 `evaluation` 内误 import `knowledge`（前端证据级联选择仅在 web 层，后端不依赖 `knowledge`）会导致 `ArchitectureTest` 失败，Task 4 Step 6 会捕获。
- 前端 `EvidencePicker` 依赖 `knowledgeApi` 的 `documents`/`versions`/`chunks` 方法，这些方法在既有 `knowledgeApi.ts` 中已存在（已确认）。
