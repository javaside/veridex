# Phase 4-c 反馈转坏例 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 员工对问答回答点赞/点踩（点踩带固定 reason code）；知识管理员查看点踩反馈并把反馈转成评测集工作集中的坏例（ANSWER/REFUSE）。

**Architecture:** 新建 Spring Modulith 模块 `io.veridex.feedback`，边界 `{"shared", "iam::api"}`。反馈落库为自包含快照（question/answer/evidence），不跨模块读 trace/evaluation。转坏例由前端编排：调 `evaluation.addCase`（已有端点）成功后调 `feedback.markConverted`。SSE `answer.completed` 事件扩展携带 `runId`。

**Tech Stack:** Java 21、Spring Boot 4、Spring Modulith、Spring Data JPA、PostgreSQL (Flyway)、Jackson 3 (`tools.jackson`)、React 19 + TypeScript + Vite + Vitest。

## Global Constraints

- feedback 模块 `allowedDependencies = {"shared", "iam::api"}`；**不依赖** trace/evaluation/conversation 的 Java 类。
- evidence JSONB 结构 = `[{documentVersionId, chunkIndexes[]}]`，与 P4-a `EvidenceRef` 对齐（feedback 模块用自己的 `FeedbackEvidence` record）。
- reason code 固定枚举：`WRONG_ANSWER` / `HALLUCINATION` / `MISSING_EVIDENCE` / `OUTDATED` / `WRONG_REFUSAL` / `OTHER`。
- rating 固定 `UP` / `DOWN`；`DOWN` 时 `reasonCode` 必填；`UP` 时 `reasonCode`/`evidence` 归一化为空。
- 迁移 `V10`（当前最新 `V9`），只增不改。
- 权限：反馈记录 = 所有认证用户；反馈列表/标记已转 = `PLATFORM_ADMIN`/`KNOWLEDGE_ADMIN`（controller 手动校验）。
- SSE `answer.completed` 由空 record 改为 `AnswerCompleted(UUID runId)`；不破坏 `answer.refused`/`run.failed`。
- TDD，每 Task 独立提交。

---

## File Structure

**后端（模块 `io.veridex.feedback`）：**

- Create `backend/src/main/resources/db/migration/V10__feedback.sql` — 1 张表
- Create `backend/src/main/java/io/veridex/feedback/domain/FeedbackRating.java`
- Create `backend/src/main/java/io/veridex/feedback/domain/FeedbackReasonCode.java`
- Create `backend/src/main/java/io/veridex/feedback/domain/FeedbackEvidence.java`
- Create `backend/src/main/java/io/veridex/feedback/domain/Feedback.java`
- Create `backend/src/main/java/io/veridex/feedback/domain/FeedbackRepository.java`
- Create `backend/src/main/java/io/veridex/feedback/application/FeedbackService.java`
- Create `backend/src/main/java/io/veridex/feedback/api/FeedbackAuthorization.java`
- Create `backend/src/main/java/io/veridex/feedback/api/RecordFeedbackRequest.java`
- Create `backend/src/main/java/io/veridex/feedback/api/MarkConvertedRequest.java`
- Create `backend/src/main/java/io/veridex/feedback/api/FeedbackView.java`
- Create `backend/src/main/java/io/veridex/feedback/api/FeedbackController.java`
- Create `backend/src/main/java/io/veridex/feedback/package-info.java`

**后端（QA 模块 SSE 扩展）：**

- Modify `backend/src/main/java/io/veridex/qa/api/QaEvent.java` — `AnswerCompleted(UUID runId)`
- Modify `backend/src/main/java/io/veridex/qa/application/QuestionAnsweringServiceImpl.java` — 构造处传 runId

**后端测试：**

- Modify `backend/src/test/java/io/veridex/support/DatabaseMigrationTest.java` — V10 断言
- Create `backend/src/test/java/io/veridex/feedback/FeedbackServiceTest.java`
- Create `backend/src/test/java/io/veridex/feedback/FeedbackIntegrationTest.java`

**前端：**

- Modify `web/src/features/qa/qaApi.ts` — `answer.completed` data 加 `runId`；加 `feedback` 调用
- Modify `web/src/features/qa/components/ChatMessage.tsx` — 点赞/点踩按钮
- Modify `web/src/features/qa/QaPage.tsx` — 反馈提交状态
- Create `web/src/features/feedback/feedbackApi.ts`
- Create `web/src/features/feedback/FeedbackPage.tsx`
- Create `web/src/features/feedback/components/FeedbackList.tsx`
- Create `web/src/features/feedback/components/ConvertToCaseDialog.tsx`
- Modify `web/src/app/routes.tsx` — 新增 `/feedback`
- Modify `web/src/app/App.test.tsx` — workspaces 断言
- Modify `web/src/styles.css` — 反馈样式
- Modify `web/src/features/qa/QaPage.test.tsx` — answer.completed data
- Create `web/src/features/feedback/FeedbackPage.test.tsx`

---

### Task 1: V10 迁移与迁移测试

**Files:**
- Create: `backend/src/main/resources/db/migration/V10__feedback.sql`
- Modify: `backend/src/test/java/io/veridex/support/DatabaseMigrationTest.java`

- [ ] **Step 1: 写失败测试**

修改 `DatabaseMigrationTest.java` 版本断言追加 `"10"`，并新增测试方法：

```java
assertThat(versions).contains("1", "2", "3", "4", "5", "6", "7", "8", "9", "10");
```

```java
@Test
void feedbackMigrationCreatesTable() throws Exception {
    try (Connection connection = dataSource.getConnection()) {
        assertThat(tableNames(connection)).contains("feedback");

        var columns = new HashMap<String, ColumnContract>();
        try (var statement = connection.prepareStatement("""
                SELECT table_name, column_name, data_type, character_maximum_length,
                       is_nullable, column_default
                FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'feedback'
                """); var rows = statement.executeQuery()) {
            while (rows.next()) {
                columns.put(rows.getString("column_name"),
                        new ColumnContract(rows.getString("data_type"),
                                rows.getObject("character_maximum_length", Integer.class),
                                "YES".equals(rows.getString("is_nullable")),
                                rows.getString("column_default")));
            }
        }
        assertColumn(columns, "rating", "character varying", 10, false, null);
        assertColumn(columns, "reason_code", "character varying", 40, true, null);
        assertColumn(columns, "question", "text", null, false, null);
        assertColumn(columns, "answer", "text", null, false, null);
        assertColumn(columns, "evidence", "jsonb", null, false, "'[]'::jsonb");
    }
}
```

> 注意：此处 `assertColumn` 的 map key 不带表名前缀（因为只查 `feedback` 一张表），需按现有 `assertColumn` 的签名使用（map key = 列名）。

- [ ] **Step 2: 运行测试确认失败**

Run: `./mvnw -pl backend test -Dtest=DatabaseMigrationTest`
Expected: FAIL（`versions` 不含 `"10"`，feedback 表不存在）。

- [ ] **Step 3: 写 V10 迁移**

```sql
-- Phase 4-c 反馈转坏例

CREATE TABLE feedback (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id),
    query_run_id UUID,
    rating VARCHAR(10) NOT NULL,
    reason_code VARCHAR(40),
    question TEXT NOT NULL,
    answer TEXT NOT NULL,
    evidence JSONB NOT NULL DEFAULT '[]'::jsonb,
    converted_case_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

- [ ] **Step 4: 运行测试确认通过**

Run: `./mvnw -pl backend test -Dtest=DatabaseMigrationTest`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add backend/src/main/resources/db/migration/V10__feedback.sql backend/src/test/java/io/veridex/support/DatabaseMigrationTest.java
git commit -m "feat: add V10 feedback table"
```

---

### Task 2: 反馈领域模型与仓储

**Files:**
- Create: `backend/src/main/java/io/veridex/feedback/domain/FeedbackRating.java`
- Create: `backend/src/main/java/io/veridex/feedback/domain/FeedbackReasonCode.java`
- Create: `backend/src/main/java/io/veridex/feedback/domain/FeedbackEvidence.java`
- Create: `backend/src/main/java/io/veridex/feedback/domain/Feedback.java`
- Create: `backend/src/main/java/io/veridex/feedback/domain/FeedbackRepository.java`

- [ ] **Step 1: 创建枚举与 record**

`FeedbackRating.java`：

```java
package io.veridex.feedback.domain;

public enum FeedbackRating {
    UP, DOWN
}
```

`FeedbackReasonCode.java`：

```java
package io.veridex.feedback.domain;

public enum FeedbackReasonCode {
    WRONG_ANSWER, HALLUCINATION, MISSING_EVIDENCE, OUTDATED, WRONG_REFUSAL, OTHER
}
```

`FeedbackEvidence.java`：

```java
package io.veridex.feedback.domain;

import java.util.List;
import java.util.UUID;

public record FeedbackEvidence(UUID documentVersionId, List<Integer> chunkIndexes) {
}
```

- [ ] **Step 2: 创建 `Feedback` 实体**

```java
package io.veridex.feedback.domain;

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
@Table(name = "feedback")
public class Feedback {

    @Id
    private UUID id = UUID.randomUUID();

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "query_run_id")
    private UUID queryRunId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private FeedbackRating rating;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason_code", length = 40)
    private FeedbackReasonCode reasonCode;

    @Column(nullable = false, columnDefinition = "text")
    private String question;

    @Column(nullable = false, columnDefinition = "text")
    private String answer;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String evidenceJson = "[]";

    @Column(name = "converted_case_id")
    private UUID convertedCaseId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected Feedback() {
    }

    public Feedback(UUID userId, UUID queryRunId, FeedbackRating rating, FeedbackReasonCode reasonCode,
                    String question, String answer, String evidenceJson) {
        this.userId = userId;
        this.queryRunId = queryRunId;
        this.rating = rating;
        this.reasonCode = reasonCode;
        this.question = question;
        this.answer = answer;
        this.evidenceJson = evidenceJson;
    }

    public void markConverted(UUID caseId) {
        this.convertedCaseId = caseId;
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public UUID getQueryRunId() { return queryRunId; }
    public FeedbackRating getRating() { return rating; }
    public FeedbackReasonCode getReasonCode() { return reasonCode; }
    public String getQuestion() { return question; }
    public String getAnswer() { return answer; }
    public String getEvidenceJson() { return evidenceJson; }
    public UUID getConvertedCaseId() { return convertedCaseId; }
    public Instant getCreatedAt() { return createdAt; }
}
```

- [ ] **Step 3: 创建仓储**

```java
package io.veridex.feedback.domain;

import java.util.List;
import java.util.UUID;
import org.springframework.data.repository.CrudRepository;

public interface FeedbackRepository extends CrudRepository<Feedback, UUID> {
    List<Feedback> findAllByOrderByCreatedAtDesc();
    List<Feedback> findByRatingOrderByCreatedAtDesc(FeedbackRating rating);
}
```

- [ ] **Step 4: 编译验证**

Run: `./mvnw -pl backend test-compile`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add backend/src/main/java/io/veridex/feedback/domain
git commit -m "feat: add feedback domain model and repository"
```

---

### Task 3: 反馈服务与校验

**Files:**
- Create: `backend/src/main/java/io/veridex/feedback/application/FeedbackService.java`
- Create: `backend/src/test/java/io/veridex/feedback/FeedbackServiceTest.java`

**Interfaces:**
- Consumes: Task 2 实体/仓储；Jackson 3 `JsonMapper`。
- Produces: `FeedbackService` 公开方法：
  - `Feedback record(UUID userId, UUID queryRunId, FeedbackRating rating, FeedbackReasonCode reasonCode, String question, String answer, List<FeedbackEvidence> evidence)`
  - `List<Feedback> listAll()`
  - `List<Feedback> listByRating(FeedbackRating rating)`
  - `Feedback require(UUID feedbackId)`
  - `Feedback markConverted(UUID feedbackId, UUID caseId)`

- [ ] **Step 1: 写失败测试**

```java
package io.veridex.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.veridex.feedback.application.FeedbackService;
import io.veridex.feedback.domain.Feedback;
import io.veridex.feedback.domain.FeedbackEvidence;
import io.veridex.feedback.domain.FeedbackRating;
import io.veridex.feedback.domain.FeedbackReasonCode;
import io.veridex.feedback.domain.FeedbackRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class FeedbackServiceTest {

    private FeedbackRepository repository;
    private FeedbackService service;

    private static final UUID USER = UUID.randomUUID();
    private static final UUID FEEDBACK = UUID.randomUUID();
    private static final FeedbackEvidence EVIDENCE = new FeedbackEvidence(UUID.randomUUID(), List.of(0, 1));

    @BeforeEach
    void setUp() {
        repository = mock(FeedbackRepository.class);
        service = new FeedbackService(repository, new JsonMapper());
    }

    @Test
    void downRequiresReasonCode() {
        assertThatThrownBy(() -> service.record(USER, null, FeedbackRating.DOWN, null, "问题", "答案", List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reasonCode");
    }

    @Test
    void upNormalizesReasonAndEvidence() {
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        Feedback saved = service.record(USER, null, FeedbackRating.UP, null, "问题", "答案", List.of(EVIDENCE));
        assertThat(saved.getReasonCode()).isNull();
        assertThat(saved.getEvidenceJson()).isEqualTo("[]");
    }

    @Test
    void downRequiresQuestion() {
        assertThatThrownBy(() -> service.record(USER, null, FeedbackRating.DOWN, FeedbackReasonCode.WRONG_ANSWER, "  ", "答案", List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("question");
    }

    @Test
    void markConvertedSetsCaseId() {
        Feedback feedback = new Feedback(USER, null, FeedbackRating.DOWN, FeedbackReasonCode.WRONG_ANSWER,
                "问题", "答案", "[]");
        when(repository.findById(FEEDBACK)).thenReturn(Optional.of(feedback));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Feedback converted = service.markConverted(FEEDBACK, UUID.randomUUID());
        assertThat(converted.getConvertedCaseId()).isNotNull();
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./mvnw -pl backend test -Dtest=FeedbackServiceTest`
Expected: FAIL（`FeedbackService` 不存在）。

- [ ] **Step 3: 实现服务**

```java
package io.veridex.feedback.application;

import io.veridex.feedback.domain.Feedback;
import io.veridex.feedback.domain.FeedbackEvidence;
import io.veridex.feedback.domain.FeedbackRating;
import io.veridex.feedback.domain.FeedbackReasonCode;
import io.veridex.feedback.domain.FeedbackRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
@Transactional
public class FeedbackService {

    private final FeedbackRepository repository;
    private final JsonMapper jsonMapper;

    public FeedbackService(FeedbackRepository repository, JsonMapper jsonMapper) {
        this.repository = repository;
        this.jsonMapper = jsonMapper;
    }

    public Feedback record(UUID userId, UUID queryRunId, FeedbackRating rating,
                           FeedbackReasonCode reasonCode, String question, String answer,
                           List<FeedbackEvidence> evidence) {
        if (rating == null) {
            throw new IllegalArgumentException("rating is required");
        }
        String normalizedQuestion = question == null ? "" : question.trim();
        if (normalizedQuestion.isBlank()) {
            throw new IllegalArgumentException("question is required");
        }
        String normalizedAnswer = answer == null ? "" : answer;
        List<FeedbackEvidence> normalizedEvidence = evidence == null ? List.of() : evidence;
        if (rating == FeedbackRating.DOWN) {
            if (reasonCode == null) {
                throw new IllegalArgumentException("reasonCode is required for DOWN feedback");
            }
        } else {
            reasonCode = null;
            normalizedEvidence = List.of();
        }
        return repository.save(new Feedback(userId, queryRunId, rating, reasonCode,
                normalizedQuestion, normalizedAnswer, serializeEvidence(normalizedEvidence)));
    }

    public List<Feedback> listAll() {
        return repository.findAllByOrderByCreatedAtDesc();
    }

    public List<Feedback> listByRating(FeedbackRating rating) {
        return repository.findByRatingOrderByCreatedAtDesc(rating);
    }

    public Feedback require(UUID feedbackId) {
        return repository.findById(feedbackId)
                .orElseThrow(() -> new IllegalArgumentException("unknown feedback " + feedbackId));
    }

    public Feedback markConverted(UUID feedbackId, UUID caseId) {
        Feedback existing = require(feedbackId);
        if (caseId == null) {
            throw new IllegalArgumentException("caseId is required");
        }
        existing.markConverted(caseId);
        return repository.save(existing);
    }

    private String serializeEvidence(List<FeedbackEvidence> evidence) {
        try {
            return jsonMapper.writeValueAsString(evidence);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize feedback evidence", e);
        }
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `./mvnw -pl backend test -Dtest=FeedbackServiceTest`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add backend/src/main/java/io/veridex/feedback/application/FeedbackService.java backend/src/test/java/io/veridex/feedback/FeedbackServiceTest.java
git commit -m "feat: add feedback service with validation and conversion marker"
```

---

### Task 4: SSE answer.completed 扩展

**Files:**
- Modify: `backend/src/main/java/io/veridex/qa/api/QaEvent.java`
- Modify: `backend/src/main/java/io/veridex/qa/application/QuestionAnsweringServiceImpl.java`

- [ ] **Step 1: 改 `QaEvent.AnswerCompleted`**

```java
record AnswerCompleted(UUID runId) implements QaEvent {
}
```

- [ ] **Step 2: 改构造处**

`QuestionAnsweringServiceImpl.java`：

```java
events.add(new QaEvent.AnswerCompleted(runId));
```

- [ ] **Step 3: 编译验证**

Run: `./mvnw -pl backend test-compile`
Expected: PASS（`QaController` 的模式匹配 `case QaEvent.AnswerCompleted r` 不受影响）。

- [ ] **Step 4: 运行 QA 相关测试确认不破坏**

Run: `./mvnw -pl backend test -Dtest=QuestionAnsweringServiceTest`
Expected: PASS（`isInstanceOf(QaEvent.AnswerCompleted.class)` 不受 record 字段影响）。

- [ ] **Step 5: 提交**

```bash
git add backend/src/main/java/io/veridex/qa/api/QaEvent.java backend/src/main/java/io/veridex/qa/application/QuestionAnsweringServiceImpl.java
git commit -m "feat: carry runId in answer.completed SSE event"
```

---

### Task 5: 反馈 REST API 与模块边界

**Files:**
- Create: `backend/src/main/java/io/veridex/feedback/package-info.java`
- Create: `backend/src/main/java/io/veridex/feedback/api/FeedbackAuthorization.java`
- Create: `backend/src/main/java/io/veridex/feedback/api/RecordFeedbackRequest.java`
- Create: `backend/src/main/java/io/veridex/feedback/api/MarkConvertedRequest.java`
- Create: `backend/src/main/java/io/veridex/feedback/api/FeedbackView.java`
- Create: `backend/src/main/java/io/veridex/feedback/api/FeedbackController.java`

- [ ] **Step 1: 创建 `package-info`**

```java
@org.springframework.modulith.ApplicationModule(
        displayName = "Feedback",
        allowedDependencies = {"shared", "iam::api"}
)
package io.veridex.feedback;
```

- [ ] **Step 2: 创建 `FeedbackAuthorization`**

```java
package io.veridex.feedback.api;

import io.veridex.iam.api.Role;
import io.veridex.iam.api.SecurityContextRole;
import org.springframework.stereotype.Component;

@Component
public class FeedbackAuthorization {

    public boolean isAdmin() {
        Role role = SecurityContextRole.currentRole();
        return role == Role.PLATFORM_ADMIN || role == Role.KNOWLEDGE_ADMIN;
    }
}
```

- [ ] **Step 3: 创建请求与视图 DTO**

`RecordFeedbackRequest.java`：

```java
package io.veridex.feedback.api;

import io.veridex.feedback.domain.FeedbackEvidence;
import io.veridex.feedback.domain.FeedbackRating;
import io.veridex.feedback.domain.FeedbackReasonCode;
import java.util.List;
import java.util.UUID;

public record RecordFeedbackRequest(UUID queryRunId, FeedbackRating rating, FeedbackReasonCode reasonCode,
                                    String question, String answer, List<FeedbackEvidence> evidence) {
}
```

`MarkConvertedRequest.java`：

```java
package io.veridex.feedback.api;

import java.util.UUID;

public record MarkConvertedRequest(UUID caseId) {
}
```

`FeedbackView.java`：

```java
package io.veridex.feedback.api;

import io.veridex.feedback.domain.FeedbackEvidence;
import java.util.List;
import java.util.UUID;

public record FeedbackView(UUID id, UUID queryRunId, String rating, String reasonCode,
                           String question, String answer, List<FeedbackEvidence> evidence,
                           UUID convertedCaseId, String createdAt) {
}
```

- [ ] **Step 4: 创建 `FeedbackController`**

```java
package io.veridex.feedback.api;

import io.veridex.feedback.application.FeedbackService;
import io.veridex.feedback.domain.Feedback;
import io.veridex.feedback.domain.FeedbackEvidence;
import io.veridex.feedback.domain.FeedbackRating;
import io.veridex.iam.api.CurrentActor;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping("/api/feedback")
public class FeedbackController {

    private final FeedbackService service;
    private final FeedbackAuthorization authorization;
    private final JsonMapper jsonMapper;

    public FeedbackController(FeedbackService service, FeedbackAuthorization authorization, JsonMapper jsonMapper) {
        this.service = service;
        this.authorization = authorization;
        this.jsonMapper = jsonMapper;
    }

    @PostMapping
    public ResponseEntity<FeedbackView> record(@RequestBody RecordFeedbackRequest body) {
        Feedback feedback = service.record(CurrentActor.id(), body.queryRunId(), body.rating(),
                body.reasonCode(), body.question(), body.answer(), body.evidence());
        return ResponseEntity.status(HttpStatus.CREATED).body(toView(feedback));
    }

    @GetMapping
    public List<FeedbackView> list(@RequestParam(required = false) FeedbackRating rating) {
        requireAdmin();
        List<Feedback> feedbacks = rating == null ? service.listAll() : service.listByRating(rating);
        return feedbacks.stream().map(this::toView).toList();
    }

    @PostMapping("/{id}/converted")
    public ResponseEntity<FeedbackView> markConverted(@PathVariable UUID id, @RequestBody MarkConvertedRequest body) {
        requireAdmin();
        return ResponseEntity.ok(toView(service.markConverted(id, body.caseId())));
    }

    private void requireAdmin() {
        if (!authorization.isAdmin()) {
            throw new SecurityException("feedback management requires admin role");
        }
    }

    private FeedbackView toView(Feedback f) {
        return new FeedbackView(f.getId(), f.getQueryRunId(), f.getRating().name(),
                f.getReasonCode() == null ? null : f.getReasonCode().name(),
                f.getQuestion(), f.getAnswer(), deserializeEvidence(f.getEvidenceJson()),
                f.getConvertedCaseId(), f.getCreatedAt().toString());
    }

    private List<FeedbackEvidence> deserializeEvidence(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return jsonMapper.readValue(json, new TypeReference<List<FeedbackEvidence>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("failed to deserialize feedback evidence", e);
        }
    }
}
```

- [ ] **Step 5: 编译 + ArchitectureTest**

Run: `./mvnw -pl backend test-compile && ./mvnw -pl backend test -Dtest=ArchitectureTest`
Expected: PASS。

- [ ] **Step 6: 提交**

```bash
git add backend/src/main/java/io/veridex/feedback
git commit -m "feat: expose feedback REST API"
```

---

### Task 6: 反馈真库集成测试

**Files:**
- Create: `backend/src/test/java/io/veridex/feedback/FeedbackIntegrationTest.java`

- [ ] **Step 1: 写测试**

```java
package io.veridex.feedback;

import static org.assertj.core.api.Assertions.assertThat;

import io.veridex.feedback.application.FeedbackService;
import io.veridex.feedback.domain.Feedback;
import io.veridex.feedback.domain.FeedbackRating;
import io.veridex.feedback.domain.FeedbackReasonCode;
import io.veridex.support.PostgresIntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class FeedbackIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    FeedbackService service;

    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000003");

    @Test
    void recordListAndMarkConverted() {
        Feedback recorded = service.record(USER, null, FeedbackRating.DOWN,
                FeedbackReasonCode.WRONG_ANSWER, "制度问题", "错误答案", List.of());
        assertThat(recorded.getRating()).isEqualTo(FeedbackRating.DOWN);

        Feedback converted = service.markConverted(recorded.getId(), UUID.randomUUID());
        assertThat(converted.getConvertedCaseId()).isNotNull();

        List<Feedback> down = service.listByRating(FeedbackRating.DOWN);
        assertThat(down).extracting(Feedback::getId).contains(recorded.getId());
    }
}
```

- [ ] **Step 2: 运行测试确认通过**

Run: `./mvnw -pl backend test -Dtest=FeedbackIntegrationTest`
Expected: PASS。

- [ ] **Step 3: 提交**

```bash
git add backend/src/test/java/io/veridex/feedback/FeedbackIntegrationTest.java
git commit -m "test: cover feedback record and conversion against Postgres"
```

---

### Task 7: 前端反馈提交（问答页）

**Files:**
- Modify: `web/src/features/qa/qaApi.ts`
- Modify: `web/src/features/qa/components/ChatMessage.tsx`
- Modify: `web/src/features/qa/QaPage.tsx`

- [ ] **Step 1: 改 `qaApi.ts`**

`answer.completed` 的 data 从 `Record<string, never>` 改为 `{ runId: string }`，并新增 `feedback` 调用：

```ts
export type QaEvent =
  // ...
  | { name: 'answer.completed'; data: { runId: string } }
  // ...

export type FeedbackRating = 'UP' | 'DOWN'
export type FeedbackReasonCode =
  | 'WRONG_ANSWER' | 'HALLUCINATION' | 'MISSING_EVIDENCE'
  | 'OUTDATED' | 'WRONG_REFUSAL' | 'OTHER'
export type FeedbackEvidence = { documentVersionId: string; chunkIndexes: number[] }

const json = async <T>(response: Response): Promise<T> => {
  if (!response.ok) {
    const message = await response.text().catch(() => '')
    throw new Error(message || `请求失败 (${response.status})`)
  }
  return response.json() as T
}

// qaApi 增加
feedback: (payload: {
  queryRunId: string | null
  rating: FeedbackRating
  reasonCode: FeedbackReasonCode | null
  question: string
  answer: string
  evidence: FeedbackEvidence[]
}): Promise<void> =>
  fetch('/api/feedback', {
    method: 'POST',
    credentials: 'include',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  }).then((r) => { if (!r.ok) throw new Error(`提交反馈失败 (${r.status})`) }),
```

- [ ] **Step 2: 改 `ChatMessage.tsx`**

assistant 消息下加点赞/点踩按钮（受控 props）：

```tsx
import { ThumbsDown, ThumbsUp } from '@phosphor-icons/react'
import type { Citation } from '../qaApi'

export function ChatMessage({ role, content, citations, onCitationClick, onFeedback }: {
  role: 'USER' | 'ASSISTANT' | 'SYSTEM' | 'ERROR'
  content: string
  citations?: Citation[]
  onCitationClick?: (citation: Citation) => void
  onFeedback?: (rating: 'UP' | 'DOWN') => void
}) {
  // ... 在 assistant 消息的 chat-bubble 后追加：
  // {role === 'ASSISTANT' && onFeedback && (
  //   <div className="chat-feedback">
  //     <button type="button" aria-label="有帮助" onClick={() => onFeedback('UP')}><ThumbsUp size={15} aria-hidden="true" /></button>
  //     <button type="button" aria-label="有问题" onClick={() => onFeedback('DOWN')}><ThumbsDown size={15} aria-hidden="true" /></button>
  //   </div>
  // )}
}
```

- [ ] **Step 3: 改 `QaPage.tsx`**

扩展 `LocalMessage` 增加 `runId`/`question`，维护当前问题 ref，处理 `answer.completed` 保存 runId，点踩弹 reason 选择并提交。

```tsx
type LocalMessage = {
  id: string
  role: 'USER' | 'ASSISTANT' | 'SYSTEM' | 'ERROR'
  content: string
  citations?: Citation[]
  runId?: string
  question?: string
}
```

关键改动：
- 新增 `lastQuestionRef = useRef('')`，`ask()` 时 `lastQuestionRef.current = trimmed`。
- `finalizeStream` 创建 assistant 消息时附带 `runId`（来自新增的 `lastRunIdRef`）与 `question`（来自 `lastQuestionRef`）。
- `case 'answer.completed': setLastRunId(event.data.runId); finalizeStream(); break`。
- 新增点踩 state `feedbackTarget`（message + rating）+ reason 选择弹层；提交时构造 `evidence`（VALID citations 按 documentVersionId 分组）并调 `qaApi.feedback`。
- `ChatMessage` 传 `onFeedback`（仅 assistant 且 `runId` 存在的消息）。

- [ ] **Step 4: 前端类型检查 + 构建**

Run: `npm --prefix web run lint && npm --prefix web run build`
Expected: 0 errors（QaPage 既有 1 warning 可忽略）。

> 注意：此步骤会同时引入 `answer.completed` data 变更，需同步更新 `QaPage.test.tsx`（Task 9 一并处理，但 build 不依赖测试）。

- [ ] **Step 5: 提交**

```bash
git add web/src/features/qa/qaApi.ts web/src/features/qa/components/ChatMessage.tsx web/src/features/qa/QaPage.tsx
git commit -m "feat: add thumbs up/down feedback to QA page"
```

---

### Task 8: 前端反馈管理页与路由

**Files:**
- Create: `web/src/features/feedback/feedbackApi.ts`
- Create: `web/src/features/feedback/components/FeedbackList.tsx`
- Create: `web/src/features/feedback/components/ConvertToCaseDialog.tsx`
- Create: `web/src/features/feedback/FeedbackPage.tsx`
- Modify: `web/src/app/routes.tsx`
- Modify: `web/src/styles.css`

- [ ] **Step 1: 创建 `feedbackApi.ts`**

```ts
import { evaluationApi } from '../evaluation/evaluationApi'

export type FeedbackRating = 'UP' | 'DOWN'
export type FeedbackReasonCode =
  | 'WRONG_ANSWER' | 'HALLUCINATION' | 'MISSING_EVIDENCE'
  | 'OUTDATED' | 'WRONG_REFUSAL' | 'OTHER'
export type FeedbackEvidence = { documentVersionId: string; chunkIndexes: number[] }

export type FeedbackView = {
  id: string
  queryRunId: string | null
  rating: FeedbackRating
  reasonCode: FeedbackReasonCode | null
  question: string
  answer: string
  evidence: FeedbackEvidence[]
  convertedCaseId: string | null
  createdAt: string
}

const json = async <T>(response: Response): Promise<T> => {
  if (!response.ok) throw new Error((await response.text().catch(() => '')) || `请求失败 (${response.status})`)
  return response.json() as T
}

export const feedbackApi = {
  listDown: (): Promise<FeedbackView[]> =>
    fetch('/api/feedback?rating=DOWN', { credentials: 'include' }).then((r) => json<FeedbackView[]>(r)),
  markConverted: (id: string, caseId: string): Promise<FeedbackView> =>
    fetch(`/api/feedback/${id}/converted`, {
      method: 'POST',
      credentials: 'include',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ caseId }),
    }).then((r) => json<FeedbackView>(r)),
}
```

- [ ] **Step 2: 创建 `FeedbackList` + `ConvertToCaseDialog`**

`FeedbackList` 展示 DOWN 反馈（question、answer 截断、reasonCode、是否已转），每条带「转坏例」按钮。

`ConvertToCaseDialog`：选目标数据集（`evaluationApi.list()`）+ 期望行为 ANSWER/REFUSE → 提交：
- REFUSE：`evaluationApi.addCase(datasetId, { question, expectedBehavior: 'REFUSE', expectedAnswer: null, evidence: [] })`
- ANSWER：`evaluationApi.addCase(datasetId, { question, expectedBehavior: 'ANSWER', expectedAnswer: null, evidence })`
- 成功后 `feedbackApi.markConverted(feedbackId, createdCase.id)`。

- [ ] **Step 3: 创建 `FeedbackPage`**

编排列表加载 + 转坏例 dialog 状态 + toast。

- [ ] **Step 4: 新增路由**

`routes.tsx` import `ChatCircleDots` + `FeedbackPage`，插入：

```tsx
{
  path: '/feedback', label: '反馈管理', englishLabel: 'Feedback', icon: ChatCircleDots,
  content: <FeedbackPage />,
},
```

- [ ] **Step 5: 追加样式**

`.chat-feedback` / `.feedback-reason-dialog` 等。

- [ ] **Step 6: lint + build**

Run: `npm --prefix web run lint && npm --prefix web run build`
Expected: 0 errors。

- [ ] **Step 7: 提交**

```bash
git add web/src/features/feedback web/src/app/routes.tsx web/src/styles.css
git commit -m "feat: add feedback management page and conversion flow"
```

---

### Task 9: 前端测试更新与全量门禁

**Files:**
- Modify: `web/src/features/qa/QaPage.test.tsx` — `answer.completed` data 加 `runId`
- Modify: `web/src/app/App.test.tsx` — workspaces 加 `/feedback`
- Create: `web/src/features/feedback/FeedbackPage.test.tsx`

- [ ] **Step 1: 更新 `QaPage.test.tsx`**

三处 `onEvent({ name: 'answer.completed', data: {} })` 改为 `data: { runId: 'run-1' }`。

- [ ] **Step 2: 更新 `App.test.tsx`**

`workspaces` 数组加入 `['/feedback', '反馈管理']`，并补 `/feedback` 路由断言（heading「反馈管理」level 1 + 「反馈」level 2）。

- [ ] **Step 3: 写 `FeedbackPage.test.tsx`**

覆盖：空列表、列表渲染 DOWN 反馈、转坏例（REFUSE）调用 evaluation addCase + feedback markConverted。

- [ ] **Step 4: 运行前端测试**

Run: `npm --prefix web test`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add web/src/features/qa/QaPage.test.tsx web/src/app/App.test.tsx web/src/features/feedback/FeedbackPage.test.tsx
git commit -m "test: cover feedback conversion and route wiring"
```

- [ ] **Step 6: 全量门禁**

Run: `./scripts/verify.sh`
Expected: 四段全部通过，输出 `verify.sh: all checks passed`。

- [ ] **Step 7: 确认工作树**

Run: `git status`
Expected: 只有本计划文件变更。

---

## Self-Review

**Spec coverage：**
- F1 新建 feedback 模块 → Task 2/5 `package-info` + ArchitectureTest
- F2 自包含快照 → Feedback 实体存 question/answer/evidence
- F3 管理员选 ANSWER/REFUSE → Task 8 ConvertToCaseDialog
- F4 固定 reason code 枚举 → FeedbackReasonCode
- F5 完整前端闭环 → Task 7 员工按钮 + Task 8 管理页
- F6 SSE answer.completed 扩展 → Task 4
- F7 V10 迁移 → Task 1
- F8 权限 → FeedbackAuthorization（记录公开，列表/标记 admin）

**验收标准对照：**
- 点赞/点踩 + reason code 落库 → Task 3/7
- 管理员列表 + 转坏例 → Task 5/6/8
- 已转标记 → Feedback.markConverted
- verify.sh 全绿 → Task 9

**风险与注意：**
- 转坏例由前端编排（非后端事务）：若 addCase 成功但 markConverted 失败，会产生未标记的坏例（可接受，管理员可重试；失败会 toast）。
- `FeedbackEvidence` 与 `EvaluationEvidence`（P4-a `EvidenceRef`）结构等价但模块独立，不互相 import。
- `answer.completed` data 变更会破坏旧前端测试，Task 9 一并修正。
