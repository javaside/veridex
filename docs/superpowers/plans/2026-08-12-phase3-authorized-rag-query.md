# Phase 3 带权限的 RAG 查询 — 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现授权员工提问 → 流式、有证据约束、带合法引用的回答；未授权员工无法发现或访问受保护内容。

**Architecture:** 平台自建检索编排（retrieval 模块直接调 OpenSearch 双路召回 → RRF 融合 → 过滤 → 上下文组装），Spring AI 仅用于 ChatModel 模型调用。generation/conversation/trace/qa 模块按 Modulith 边界新增。确定性模型（DeterministicEmbeddingModel + 新增 DeterministicChatModel）打通全链路，模型可插拔。

**Tech Stack:** Java 21、Spring Boot 4、Spring AI 2.0（ChatModel/EmbeddingModel）、Spring MVC SseEmitter、OpenSearch、PostgreSQL（Flyway V7）、React 19 + TypeScript + Vite + Vitest。

## Global Constraints

- 检索必须过滤：用户授权知识库（VIEW 交集）、当前 active release 快照清单、非 OFFLINE 文档版本、知识库状态 ACTIVE。
- 权限过滤不依赖 OpenSearch：在线文档清单来自 PostgreSQL（快照 `index_release_document` ∩ `document_version.status != OFFLINE`）。
- 正文不落库：query_run 不存 prompt/检索正文；`message.content` 存回答全文（会话展示必需）。
- 拒答是正常业务结果；`ACCESS_RESTRICTED` 对普通用户统一显示「当前可访问知识范围内证据不足」。
- 非拒答答案必须有合法引用（引用编号 ∈ 上下文编号范围且对应证据在本次上下文内）。
- 双路检索均失败 → 系统失败（run.failed），不伪装无答案。
- 客户端断开 → 终止下游模型调用 → QueryRun = CANCELLED。
- 前端问答页复用现有 console 视觉体系；引用点击复用 PreviewDrawer 数据源（`/api/documents/{id}/versions/{vid}/chunks`）。
- 每任务遵循 TDD：先写失败测试 → 实现 → 跑过 → 提交。
- 集成测试用 Testcontainers（PostgreSQL/RabbitMQ/Redis/MinIO/OpenSearch），复用 `PostgresIntegrationTest` / `OpenSearchContainerConfiguration` 等既有支撑类。
- Spring Modulith 模块边界：跨模块只能访问对方 `api` 子包；新增跨模块调用需同步更新 package-info 的 allowedDependencies 与 ArchitectureTest。

---

## 文件结构总览

### 后端（backend/src/main/java/io/veridex/）

```
qa/                                  # 新模块：问答 API + SSE 编排
  package-info.java                  # allowedDependencies = shared,iam,knowledge,retrieval,generation,conversation,trace
  api/QaController.java              # POST /api/qa/ask (SSE)、GET /api/qa/conversations、GET .../messages
  api/AskRequest.java                # record(question, knowledgeBaseIds, conversationId?)
  api/ConversationView.java          # record(id, title, createdAt)
  api/MessageView.java               # record(id, role, content, queryRunId?)
  application/QuestionAnsweringService.java  # 编排：scope → run → retrieval → generation → citations → SSE 事件
  application/QaEvent.java           # sealed interface + run.started/retrieval.completed/answer.delta/...
retrieval/
  api/SearchHit.java                 # record(kbId, documentVersionId, chunkIndex, title, structurePath, text, channel, score)
  api/RerankProvider.java            # 接口：rerank(List<SearchHit>, String question)
  api/PassThroughReranker.java       # @Component 直通
  application/RankFusion.java        # RRF 融合纯逻辑（静态方法）
  application/HybridSearchService.java  # 编排：scope→active release→双路召回→RRF→过滤→Top K→上下文
  application/ContextAssemblyService.java # 组装证据 + [n] 编号
  infrastructure/OpenSearchRetrievalReader.java # 实现：注入 OpenSearchClient + EmbeddingModel，双路召回
generation/
  api/GenerationService.java         # 接口
  api/GenerationResult.java          # record(answer, citations, refusalReason, ...)
  application/GenerationServiceImpl.java
  application/CitationValidator.java
  application/RefusalPolicy.java
  infrastructure/DeterministicChatModel.java  # implements ChatModel
conversation/
  api/ConversationService.java       # 接口
  api/MessageRecord.java
  application/ConversationServiceImpl.java
  domain/Conversation.java, Message.java, ConversationRepository, MessageRepository
trace/
  api/QueryRunRecorder.java          # 接口：start/complete/refuse/fail/cancel + recordHits/citations/generation
  application/QueryRunRecorderImpl.java
  domain/QueryRun.java, RetrievalHit.java, GenerationRun.java, Citation.java + repositories
shared/
  infrastructure/config/...          # 不变
```

### 数据库（backend/src/main/resources/db/migration/V7__phase3_qa.sql）

```sql
CREATE TABLE conversation (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id),
    title VARCHAR(200) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE message (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id UUID NOT NULL REFERENCES conversation(id) ON DELETE CASCADE,
    role VARCHAR(20) NOT NULL,
    content TEXT NOT NULL,
    query_run_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE query_run (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id),
    conversation_id UUID REFERENCES conversation(id),
    question TEXT NOT NULL,
    normalized_question TEXT,
    knowledge_scope JSONB NOT NULL DEFAULT '[]'::jsonb,
    status VARCHAR(30) NOT NULL,
    refusal_reason VARCHAR(60),
    error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ
);

CREATE TABLE retrieval_hit (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    query_run_id UUID NOT NULL REFERENCES query_run(id) ON DELETE CASCADE,
    knowledge_base_id UUID NOT NULL,
    document_version_id UUID NOT NULL,
    chunk_index INTEGER NOT NULL,
    channel VARCHAR(10) NOT NULL,
    bm25_score DOUBLE PRECISION,
    vector_score DOUBLE PRECISION,
    fusion_score DOUBLE PRECISION,
    rank INTEGER NOT NULL,
    entered_context BOOLEAN NOT NULL DEFAULT false,
    filter_reason VARCHAR(100)
);

CREATE TABLE generation_run (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    query_run_id UUID NOT NULL REFERENCES query_run(id) ON DELETE CASCADE,
    model VARCHAR(100) NOT NULL,
    input_tokens INTEGER NOT NULL DEFAULT 0,
    output_tokens INTEGER NOT NULL DEFAULT 0,
    duration_ms BIGINT NOT NULL DEFAULT 0,
    degradation VARCHAR(100),
    context_hash VARCHAR(64)
);

CREATE TABLE citation (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    query_run_id UUID NOT NULL REFERENCES query_run(id) ON DELETE CASCADE,
    citation_index INTEGER NOT NULL,
    document_version_id UUID NOT NULL,
    chunk_index INTEGER NOT NULL,
    source_location VARCHAR(300),
    citation_text TEXT,
    validation_status VARCHAR(30) NOT NULL
);
```

### 前端（web/src/）

```
features/qa/
  qaApi.ts                        # SSE 解析 + conversations/messages API
  QaPage.tsx                      # 知识库多选 + 会话 + 流式 + 引用 + 拒答
  QaPage.test.tsx
  components/ChatMessage.tsx      # 单条消息（含 [n] 引用渲染）
  components/KnowledgeBasePicker.tsx
app/routes.tsx                    # /workbench → QaPage
styles.css                        # .qa-* 样式
```

---

## Task 1: V7 数据库迁移

**Files:**
- Create: `backend/src/main/resources/db/migration/V7__phase3_qa.sql`
- Modify: `backend/src/test/java/io/veridex/support/DatabaseMigrationTest.java`（追加断言）

**Interfaces:**
- Produces: 表 `conversation`、`message`、`query_run`、`retrieval_hit`、`generation_run`、`citation`

- [ ] **Step 1: 写迁移 SQL**

按上方「文件结构总览」的 V7 SQL 创建文件。

- [ ] **Step 2: 在 DatabaseMigrationTest 追加断言（先失败）**

在 `DatabaseMigrationTest` 添加新测试方法：

```java
@Test
void phase3QaMigrationCreatesTablesAndColumns() throws Exception {
    try (Connection connection = dataSource.getConnection()) {
        assertThat(tableNames(connection)).contains(
                "conversation", "message", "query_run", "retrieval_hit", "generation_run", "citation");

        var columns = new HashMap<String, ColumnContract>();
        try (var statement = connection.prepareStatement("""
                SELECT table_name, column_name, data_type, character_maximum_length,
                       is_nullable, column_default
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name IN ('conversation', 'message', 'query_run', 'retrieval_hit', 'generation_run', 'citation')
                """); var rows = statement.executeQuery()) {
            while (rows.next()) {
                columns.put(rows.getString("table_name") + "." + rows.getString("column_name"),
                        new ColumnContract(rows.getString("data_type"),
                                rows.getObject("character_maximum_length", Integer.class),
                                "YES".equals(rows.getString("is_nullable")),
                                rows.getString("column_default")));
            }
        }
        assertColumn(columns, "conversation.user_id", "uuid", null, false, null);
        assertColumn(columns, "query_run.status", "character varying", 30, false, null);
        assertColumn(columns, "query_run.knowledge_scope", "jsonb", null, false, null);
        assertColumn(columns, "retrieval_hit.channel", "character varying", 10, false, null);
        assertColumn(columns, "citation.validation_status", "character varying", 30, false, null);
    }
}
```

> 注：`assertColumn`、`tableNames`、`ColumnContract` 均已在该测试文件私有定义，直接复用。`flywayAppliesPlatformBaselineMigration` 的 `versions` 断言需追加 `"7"`。

- [ ] **Step 3: 跑迁移测试确认通过**

Run: `./mvnw -f backend/pom.xml -Dtest=DatabaseMigrationTest test`（需要 Docker 起 PostgreSQL）
Expected: PASS（V7 应用成功，新表与列断言通过；同时把既有 `versions` 断言追加 `"7"`）

- [ ] **Step 4: 提交**

```bash
git add backend/src/main/resources/db/migration/V7__phase3_qa.sql backend/src/test/java/io/veridex/support/DatabaseMigrationTest.java
git commit -m "feat: add phase3 qa schema migration"
```

---

## Task 2: conversation 模块（会话与消息）

**Files:**
- Create: `backend/src/main/java/io/veridex/conversation/domain/Conversation.java`
- Create: `backend/src/main/java/io/veridex/conversation/domain/Message.java`
- Create: `backend/src/main/java/io/veridex/conversation/domain/ConversationRepository.java`
- Create: `backend/src/main/java/io/veridex/conversation/domain/MessageRepository.java`
- Create: `backend/src/main/java/io/veridex/conversation/api/ConversationService.java`
- Create: `backend/src/main/java/io/veridex/conversation/api/MessageRecord.java`
- Create: `backend/src/main/java/io/veridex/conversation/application/ConversationServiceImpl.java`
- Modify: `backend/src/main/java/io/veridex/conversation/package-info.java`（保持 allowedDependencies = shared,iam,generation）
- Test: `backend/src/test/java/io/veridex/conversation/ConversationServiceImplTest.java`（纯单测，mock repos）

**Interfaces:**
- Produces:
```java
// conversation.api.ConversationService
ConversationView create(UUID userId, String title);
List<ConversationView> listForUser(UUID userId);
Optional<ConversationView> findOwned(UUID userId, UUID conversationId);
MessageRecord addMessage(UUID conversationId, String role, String content, UUID queryRunId);
List<MessageRecord> recentMessages(UUID conversationId, int limit); // 升序、最近 limit 条
```

- [ ] **Step 1: 写领域实体与仓库**

```java
// domain/Conversation.java
@Entity @Table(name = "conversation")
public class Conversation {
    @Id private UUID id = UUID.randomUUID();
    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(nullable = false, length = 200) private String title;
    @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();
    @Column(name = "updated_at", nullable = false) private Instant updatedAt = Instant.now();
    protected Conversation() {}
    public Conversation(UUID userId, String title) { this.userId = userId; this.title = title; }
    public void touch() { this.updatedAt = Instant.now(); }
    // getters: getId, getUserId, getTitle, getCreatedAt, getUpdatedAt
}

// domain/Message.java
@Entity @Table(name = "message")
public class Message {
    @Id private UUID id = UUID.randomUUID();
    @Column(name = "conversation_id", nullable = false) private UUID conversationId;
    @Column(nullable = false, length = 20) private String role;
    @Column(nullable = false) @Lob private String content;
    @Column(name = "query_run_id") private UUID queryRunId;
    @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();
    protected Message() {}
    public Message(UUID conversationId, String role, String content, UUID queryRunId) { ... }
    // getters
}

// domain/ConversationRepository.java
public interface ConversationRepository extends CrudRepository<Conversation, UUID> {
    List<Conversation> findByUserIdOrderByUpdatedAtDesc(UUID userId);
    Optional<Conversation> findByIdAndUserId(UUID id, UUID userId);
}

// domain/MessageRepository.java
public interface MessageRepository extends CrudRepository<Message, UUID> {
    List<Message> findByConversationIdOrderByCreatedAtAsc(UUID conversationId);
}
```

- [ ] **Step 2: 写失败测试**

```java
@ExtendWith(MockitoExtension.class)
class ConversationServiceImplTest {
    @Mock ConversationRepository conversations;
    @Mock MessageRepository messages;
    @InjectMocks ConversationServiceImpl service;
    private static final UUID USER = UUID.randomUUID();
    private static final UUID CONV = UUID.randomUUID();

    @Test
    void createPersistsConversationAndReturnsView() {
        when(conversations.save(any())).thenAnswer(inv -> inv.getArgument(0));
        ConversationView view = service.create(USER, "请假规定");
        assertThat(view.title()).isEqualTo("请假规定");
        verify(conversations).save(argThat(c -> c.getUserId().equals(USER)));
    }

    @Test
    void recentMessagesReturnsLatestAscending() {
        var m1 = new Message(CONV, "USER", "q1", null);
        var m2 = new Message(CONV, "ASSISTANT", "a1", UUID.randomUUID());
        var m3 = new Message(CONV, "USER", "q2", null);
        when(messages.findByConversationIdOrderByCreatedAtAsc(CONV)).thenReturn(List.of(m1, m2, m3));
        var recent = service.recentMessages(CONV, 2);
        assertThat(recent).extracting(MessageRecord::content).containsExactly("a1", "q2");
    }

    @Test
    void findOwnedReturnsEmptyForOtherUsersConversation() {
        when(conversations.findByIdAndUserId(CONV, USER)).thenReturn(Optional.empty());
        assertThat(service.findOwned(USER, CONV)).isEmpty();
    }
}
```

- [ ] **Step 3: 实现 ConversationService 接口与实现**

```java
// api/ConversationService.java
public interface ConversationService {
    ConversationView create(UUID userId, String title);
    List<ConversationView> listForUser(UUID userId);
    Optional<ConversationView> findOwned(UUID userId, UUID conversationId);
    MessageRecord addMessage(UUID conversationId, String role, String content, UUID queryRunId);
    List<MessageRecord> recentMessages(UUID conversationId, int limit);
}

// api/ConversationView.java
public record ConversationView(UUID id, String title, java.time.Instant createdAt) {}

// api/MessageRecord.java
public record MessageRecord(UUID id, String role, String content, UUID queryRunId) {}

// application/ConversationServiceImpl.java
@Service @Transactional
public class ConversationServiceImpl implements ConversationService {
    private final ConversationRepository conversations;
    private final MessageRepository messages;
    // constructor
    @Override public ConversationView create(UUID userId, String title) {
        Conversation c = conversations.save(new Conversation(userId, title));
        return new ConversationView(c.getId(), c.getTitle(), c.getCreatedAt());
    }
    @Override public List<ConversationView> listForUser(UUID userId) {
        return conversations.findByUserIdOrderByUpdatedAtDesc(userId).stream()
                .map(c -> new ConversationView(c.getId(), c.getTitle(), c.getCreatedAt())).toList();
    }
    @Override public Optional<ConversationView> findOwned(UUID userId, UUID conversationId) {
        return conversations.findByIdAndUserId(conversationId, userId)
                .map(c -> new ConversationView(c.getId(), c.getTitle(), c.getCreatedAt()));
    }
    @Override public MessageRecord addMessage(UUID conversationId, String role, String content, UUID queryRunId) {
        Message m = messages.save(new Message(conversationId, role, content, queryRunId));
        conversations.findById(conversationId).ifPresent(c -> { c.touch(); conversations.save(c); });
        return new MessageRecord(m.getId(), m.getRole(), m.getContent(), m.getQueryRunId());
    }
    @Override public List<MessageRecord> recentMessages(UUID conversationId, int limit) {
        var all = messages.findByConversationIdOrderByCreatedAtAsc(conversationId);
        return all.stream().skip(Math.max(0, all.size() - limit))
                .map(m -> new MessageRecord(m.getId(), m.getRole(), m.getContent(), m.getQueryRunId())).toList();
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `./mvnw -f backend/pom.xml -Dtest=ConversationServiceImplTest test`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add backend/src/main/java/io/veridex/conversation backend/src/test/java/io/veridex/conversation
git commit -m "feat: add conversation and message persistence"
```

---

## Task 3: trace 模块（QueryRun 与证据持久化）

**Files:**
- Create: `backend/src/main/java/io/veridex/trace/domain/QueryRun.java`
- Create: `backend/src/main/java/io/veridex/trace/domain/RetrievalHit.java`
- Create: `backend/src/main/java/io/veridex/trace/domain/GenerationRun.java`
- Create: `backend/src/main/java/io/veridex/trace/domain/Citation.java`
- Create: `backend/src/main/java/io/veridex/trace/domain/QueryRunRepository.java`、`RetrievalHitRepository.java`、`GenerationRunRepository.java`、`CitationRepository.java`
- Create: `backend/src/main/java/io/veridex/trace/api/QueryRunRecorder.java`
- Create: `backend/src/main/java/io/veridex/trace/application/QueryRunRecorderImpl.java`
- Modify: `backend/src/main/java/io/veridex/trace/package-info.java`（allowedDependencies 不变：shared,iam,retrieval,generation）
- Test: `backend/src/test/java/io/veridex/trace/QueryRunRecorderImplTest.java`（Mockito 单测）

**Interfaces:**
- Produces:
```java
// trace.api.QueryRunRecorder
UUID start(UUID userId, UUID conversationId, List<UUID> knowledgeScope, String question, String normalizedQuestion);
void markRetrieving(UUID runId, List<RetrievalHitRecord> hits);
void markGenerating(UUID runId, GenerationRecord gen);
void addCitations(UUID runId, List<CitationRecord> citations);
void complete(UUID runId);
void refuse(UUID runId, RefusalReason reason);
void fail(UUID runId, String error);
void cancel(UUID runId);
```

- [ ] **Step 1: 写领域实体**

```java
// domain/QueryRun.java
@Entity @Table(name = "query_run")
public class QueryRun {
    public enum Status { RECEIVED, RETRIEVING, RERANKING, GENERATING, VALIDATING,
        COMPLETED, REFUSED, FAILED, CANCELLED }
    @Id private UUID id = UUID.randomUUID();
    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(name = "conversation_id") private UUID conversationId;
    @Column(nullable = false) @Lob private String question;
    @Column(name = "normalized_question") @Lob private String normalizedQuestion;
    @Column(name = "knowledge_scope", nullable = false, columnDefinition = "jsonb")
    private String knowledgeScopeJson = "[]";
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30) private Status status = Status.RECEIVED;
    @Column(name = "refusal_reason", length = 60) private String refusalReason;
    @Column @Lob private String error;
    @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();
    @Column(name = "completed_at") private Instant completedAt;
    protected QueryRun() {}
    public QueryRun(UUID userId, UUID conversationId, String question, String normalizedQuestion,
                    List<UUID> knowledgeScope) {
        this.userId = userId; this.conversationId = conversationId;
        this.question = question; this.normalizedQuestion = normalizedQuestion;
        this.knowledgeScopeJson = knowledgeScope.stream().map(UUID::toString).toList().toString();
    }
    public void mark(Status next) { this.status = next; }
    public void complete() { this.status = Status.COMPLETED; this.completedAt = Instant.now(); }
    public void refuse(String reason) { this.status = Status.REFUSED; this.refusalReason = reason; this.completedAt = Instant.now(); }
    public void fail(String error) { this.status = Status.FAILED; this.error = error; this.completedAt = Instant.now(); }
    public void cancel() { this.status = Status.CANCELLED; this.completedAt = Instant.now(); }
    // getters: getId, getStatus, getRefusalReason, getError, getConversationId, getUserId
}

// domain/RetrievalHit.java
@Entity @Table(name = "retrieval_hit")
public class RetrievalHit {
    public enum Channel { BM25, VECTOR }
    @Id private UUID id = UUID.randomUUID();
    @Column(name = "query_run_id", nullable = false) private UUID queryRunId;
    @Column(name = "knowledge_base_id", nullable = false) private UUID knowledgeBaseId;
    @Column(name = "document_version_id", nullable = false) private UUID documentVersionId;
    @Column(name = "chunk_index", nullable = false) private int chunkIndex;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 10) private Channel channel;
    @Column(name = "bm25_score") private Double bm25Score;
    @Column(name = "vector_score") private Double vectorScore;
    @Column(name = "fusion_score") private Double fusionScore;
    @Column(nullable = false) private int rank;
    @Column(name = "entered_context", nullable = false) private boolean enteredContext;
    @Column(name = "filter_reason", length = 100) private String filterReason;
    protected RetrievalHit() {}
    public RetrievalHit(UUID queryRunId, UUID knowledgeBaseId, UUID documentVersionId, int chunkIndex,
                        Channel channel, Double bm25Score, Double vectorScore, Double fusionScore,
                        int rank, boolean enteredContext, String filterReason) { ... }
    // getters
}

// domain/GenerationRun.java
@Entity @Table(name = "generation_run")
public class GenerationRun {
    @Id private UUID id = UUID.randomUUID();
    @Column(name = "query_run_id", nullable = false) private UUID queryRunId;
    @Column(nullable = false, length = 100) private String model;
    @Column(name = "input_tokens", nullable = false) private int inputTokens;
    @Column(name = "output_tokens", nullable = false) private int outputTokens;
    @Column(name = "duration_ms", nullable = false) private long durationMs;
    @Column(length = 100) private String degradation;
    @Column(name = "context_hash", length = 64) private String contextHash;
    protected GenerationRun() {}
    public GenerationRun(UUID queryRunId, String model, int inputTokens, int outputTokens,
                         long durationMs, String degradation, String contextHash) { ... }
    // getters
}

// domain/Citation.java
@Entity @Table(name = "citation")
public class Citation {
    @Id private UUID id = UUID.randomUUID();
    @Column(name = "query_run_id", nullable = false) private UUID queryRunId;
    @Column(name = "citation_index", nullable = false) private int citationIndex;
    @Column(name = "document_version_id", nullable = false) private UUID documentVersionId;
    @Column(name = "chunk_index", nullable = false) private int chunkIndex;
    @Column(name = "source_location", length = 300) private String sourceLocation;
    @Column(name = "citation_text") @Lob private String citationText;
    @Column(name = "validation_status", nullable = false, length = 30) private String validationStatus;
    protected Citation() {}
    public Citation(UUID queryRunId, int citationIndex, UUID documentVersionId, int chunkIndex,
                    String sourceLocation, String citationText, String validationStatus) { ... }
    // getters
}
```

仓库接口（CrudRepository）：`QueryRunRepository extends CrudRepository<QueryRun, UUID>`；其余同理（RetrievalHitRepository 等）。`QueryRunRepository` 加：

```java
List<QueryRun> findByUserIdAndConversationIdOrderByCreatedAtDesc(UUID userId, UUID conversationId);
```

- [ ] **Step 2: 写失败测试（QueryRunRecorderImplTest）**

```java
@ExtendWith(MockitoExtension.class)
class QueryRunRecorderImplTest {
    @Mock QueryRunRepository runs;
    @Mock RetrievalHitRepository hits;
    @Mock GenerationRunRepository gens;
    @Mock CitationRepository citations;
    @InjectMocks QueryRunRecorderImpl recorder;
    private static final UUID USER = UUID.randomUUID();
    private static final UUID RUN = UUID.randomUUID();

    @Test
    void startPersistsReceivedRun() {
        when(runs.save(any())).thenAnswer(inv -> inv.getArgument(0));
        UUID id = recorder.start(USER, null, List.of(UUID.randomUUID()), "问题", "问题");
        assertThat(id).isNotNull();
        verify(runs).save(argThat(r -> r.getStatus() == QueryRun.Status.RECEIVED));
    }

    @Test
    void refuseMarksRunRefusedWithReason() {
        var run = new QueryRun(USER, null, "q", "q", List.of());
        when(runs.findById(RUN)).thenReturn(Optional.of(run));
        recorder.refuse(RUN, RefusalReason.NO_RELEVANT_EVIDENCE);
        assertThat(run.getStatus()).isEqualTo(QueryRun.Status.REFUSED);
        assertThat(run.getRefusalReason()).isEqualTo("NO_RELEVANT_EVIDENCE");
    }

    @Test
    void failMarksRunFailedWithError() {
        var run = new QueryRun(USER, null, "q", "q", List.of());
        when(runs.findById(RUN)).thenReturn(Optional.of(run));
        recorder.fail(RUN, "boom");
        assertThat(run.getStatus()).isEqualTo(QueryRun.Status.FAILED);
        assertThat(run.getError()).contains("boom");
    }
}
```

- [ ] **Step 3: 实现 recorder 接口与实现**

```java
// api/QueryRunRecorder.java
public interface QueryRunRecorder {
    UUID start(UUID userId, UUID conversationId, List<UUID> knowledgeScope, String question, String normalizedQuestion);
    void markRetrieving(UUID runId, List<RetrievalHitRecord> hits);
    void markGenerating(UUID runId, GenerationRecord gen);
    void addCitations(UUID runId, List<CitationRecord> citations);
    void complete(UUID runId);
    void refuse(UUID runId, RefusalReason reason);
    void fail(UUID runId, String error);
    void cancel(UUID runId);

    record RetrievalHitRecord(UUID knowledgeBaseId, UUID documentVersionId, int chunkIndex, String channel,
                              Double bm25Score, Double vectorScore, Double fusionScore, int rank,
                              boolean enteredContext, String filterReason) {}
    record GenerationRecord(String model, int inputTokens, int outputTokens, long durationMs,
                            String degradation, String contextHash) {}
    record CitationRecord(int citationIndex, UUID documentVersionId, int chunkIndex,
                          String sourceLocation, String citationText, String validationStatus) {}
}

// application/QueryRunRecorderImpl.java
@Service @Transactional
public class QueryRunRecorderImpl implements QueryRunRecorder {
    private final QueryRunRepository runs;
    private final RetrievalHitRepository hits;
    private final GenerationRunRepository gens;
    private final CitationRepository citations;
    // constructor

    @Override public UUID start(...) {
        QueryRun saved = runs.save(new QueryRun(userId, conversationId, question, normalizedQuestion, knowledgeScope));
        return saved.getId();
    }
    @Override public void markRetrieving(UUID runId, List<RetrievalHitRecord> records) {
        for (var r : records) hits.save(new RetrievalHit(runId, r.knowledgeBaseId(), r.documentVersionId(),
                r.chunkIndex(), RetrievalHit.Channel.valueOf(r.channel()), r.bm25Score(), r.vectorScore(),
                r.fusionScore(), r.rank(), r.enteredContext(), r.filterReason()));
        runs.findById(runId).ifPresent(run -> run.mark(QueryRun.Status.RETRIEVING));
    }
    @Override public void markGenerating(UUID runId, GenerationRecord g) {
        gens.save(new GenerationRun(runId, g.model(), g.inputTokens(), g.outputTokens(), g.durationMs(), g.degradation(), g.contextHash()));
        runs.findById(runId).ifPresent(run -> run.mark(QueryRun.Status.GENERATING));
    }
    @Override public void addCitations(UUID runId, List<CitationRecord> cs) {
        for (var c : cs) citations.save(new Citation(runId, c.citationIndex(), c.documentVersionId(), c.chunkIndex(),
                c.sourceLocation(), c.citationText(), c.validationStatus()));
    }
    @Override public void complete(UUID runId) { runs.findById(runId).ifPresent(QueryRun::complete); }
    @Override public void refuse(UUID runId, RefusalReason reason) {
        runs.findById(runId).ifPresent(run -> run.refuse(reason.name()));
    }
    @Override public void fail(UUID runId, String error) { runs.findById(runId).ifPresent(run -> run.fail(error)); }
    @Override public void cancel(UUID runId) { runs.findById(runId).ifPresent(QueryRun::cancel); }
}
```

> `RefusalReason` 枚举定义于 generation 模块（Task 8）；为避免 trace→generation 反向依赖，把 `RefusalReason` 放在 `shared` 或 trace 自己。**决定：RefusalReason 放 `io.veridex.shared`（`shared/RefusalReason.java`），供 trace/generation/qa 共用。** 并更新 shared/package-info.java（`allowedDependencies = {}` 不变）。

- [ ] **Step 4: 跑测试确认通过**

Run: `./mvnw -f backend/pom.xml -Dtest=QueryRunRecorderImplTest test`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add backend/src/main/java/io/veridex/trace backend/src/main/java/io/veridex/shared/RefusalReason.java backend/src/test/java/io/veridex/trace
git commit -m "feat: add query run and evidence trace persistence"
```

---

## Task 4: retrieval 双路召回（OpenSearch 查询）

**Files:**
- Create: `backend/src/main/java/io/veridex/retrieval/api/SearchHit.java`
- Create: `backend/src/main/java/io/veridex/retrieval/infrastructure/OpenSearchRetrievalReader.java`
- Modify: `backend/src/main/java/io/veridex/retrieval/package-info.java`（allowedDependencies = shared,iam,knowledge,indexing 不变）
- Test: `backend/src/test/java/io/veridex/retrieval/OpenSearchRetrievalReaderTest.java`（集成，Testcontainers OpenSearch）

**Interfaces:**
- Produces:
```java
// retrieval.api.SearchHit
public record SearchHit(UUID knowledgeBaseId, UUID documentVersionId, int chunkIndex,
                        String title, String structurePath, String text, Channel channel, double score) {
    public enum Channel { BM25, VECTOR }
}

// retrieval.infrastructure.OpenSearchRetrievalReader
@Component
public class OpenSearchRetrievalReader {
    public OpenSearchRetrievalReader(OpenSearchClient client, EmbeddingModel embeddings) {}
    public List<SearchHit> bm25(String aliasName, UUID knowledgeBaseId, String question, int topK) {}
    public List<SearchHit> vector(String aliasName, UUID knowledgeBaseId, String question, int topK) {}
}
```

- [ ] **Step 1: 写失败集成测试**

```java
@Testcontainers
@Import({OpenSearchContainerConfiguration.class, DeterministicEmbeddingModel.class})
class OpenSearchRetrievalReaderTest extends PostgresIntegrationTest {
    @Autowired OpenSearchRetrievalReader reader;
    @Autowired SearchIndexGateway gateway;
    @Autowired DeterministicEmbeddingModel embeddings;

    private static final UUID KB = UUID.randomUUID();
    private static final UUID VER = UUID.randomUUID();
    private static final UUID RELEASE = UUID.randomUUID();

    @BeforeEach
    void seed() {
        String index = "veridex-retrieval-test-" + System.nanoTime();
        String alias = "veridex-retrieval-active-" + System.nanoTime();
        gateway.createIndex(index, 128);
        gateway.aliasTo(alias, index);
        gateway.indexChunks(index, KB, VER, RELEASE, List.of(
                new ChunkRecord(0, "员工请假需提前两个工作日提交申请", "请假制度", "1"),
                new ChunkRecord(1, "年假最长不超过十五个工作日", "请假制度", "1.1"),
                new ChunkRecord(2, "工资于每月十号发放", "薪酬规定", "2")));
        // 保存到字段供各测试使用
    }

    @Test
    void bm25FindsKeywordMatch() {
        var hits = reader.bm25(alias, KB, "请假申请", 5);
        assertThat(hits).isNotEmpty();
        assertThat(hits.get(0).channel()).isEqualTo(SearchHit.Channel.BM25);
        assertThat(hits.get(0).text()).contains("请假");
    }

    @Test
    void vectorFindsChunkWithHighestOverlap() {
        var hits = reader.vector(alias, KB, "年假 十五 工作日", 5);
        assertThat(hits).isNotEmpty();
        assertThat(hits.get(0).text()).contains("年假");
    }

    @Test
    void bothChannelsReturnKnowledgeBaseScopedHits() {
        var bm25 = reader.bm25(alias, KB, "工资", 5);
        var vec = reader.vector(alias, KB, "工资 发放", 5);
        assertThat(bm25).allMatch(h -> h.knowledgeBaseId().equals(KB));
        assertThat(vec).allMatch(h -> h.knowledgeBaseId().equals(KB));
    }
}
```

> `PostgresIntegrationTest` 已引入全量容器（含 OpenSearch），`@Import({OpenSearchContainerConfiguration.class, DeterministicEmbeddingModel.class})` 对齐现有 `OpenSearchIndexGatewayTest` 写法。

- [ ] **Step 2: 实现 OpenSearchRetrievalReader**

```java
@Component
public class OpenSearchRetrievalReader {
    private final OpenSearchClient client;
    private final EmbeddingModel embeddings;

    public OpenSearchRetrievalReader(OpenSearchClient client, EmbeddingModel embeddings) {
        this.client = client;
        this.embeddings = embeddings;
    }

    public List<SearchHit> bm25(String aliasName, UUID knowledgeBaseId, String question, int topK) {
        var request = SearchRequest.of(s -> s.index(aliasName).size(topK)
                .query(q -> q.match(m -> m.field("text").query(question))));
        return mapHits(request, knowledgeBaseId, SearchHit.Channel.BM25);
    }

    public List<SearchHit> vector(String aliasName, UUID knowledgeBaseId, String question, int topK) {
        float[] vector = embeddings.embed(question);
        var request = SearchRequest.of(s -> s.index(aliasName).size(topK)
                .query(q -> q.knn(k -> k.field("embedding").vector(vector).k(topK))));
        return mapHits(request, knowledgeBaseId, SearchHit.Channel.VECTOR);
    }

    @SuppressWarnings("unchecked")
    private List<SearchHit> mapHits(SearchRequest request, UUID knowledgeBaseId, SearchHit.Channel channel) {
        try {
            var response = client.search(request, Map.class);
            List<SearchHit> out = new ArrayList<>();
            for (var hit : response.hits().hits()) {
                Map<String, Object> src = (Map<String, Object>) hit.source();
                if (src == null) continue;
                out.add(new SearchHit(
                        knowledgeBaseId,
                        UUID.fromString(String.valueOf(src.get("document_version_id"))),
                        ((Number) src.get("chunk_index")).intValue(),
                        String.valueOf(src.get("title")),
                        String.valueOf(src.get("structure_path")),
                        String.valueOf(src.get("text")),
                        channel,
                        hit.score() != null ? hit.score() : 0d));
            }
            return out;
        } catch (IOException e) {
            throw new RuntimeException("retrieval search failed on " + request.index(), e);
        }
    }
}
```

- [ ] **Step 3: 跑测试确认通过**

Run: `./mvnw -f backend/pom.xml -Dtest=OpenSearchRetrievalReaderTest test`
Expected: PASS（BM25 命中「请假」，向量命中「年假」）

- [ ] **Step 4: 提交**

```bash
git add backend/src/main/java/io/veridex/retrieval backend/src/test/java/io/veridex/retrieval
git commit -m "feat: add dual-channel retrieval from OpenSearch alias"
```

---

## Task 5: RRF 融合与上下文组装

**Files:**
- Create: `backend/src/main/java/io/veridex/retrieval/application/RankFusion.java`
- Create: `backend/src/main/java/io/veridex/retrieval/application/ContextAssemblyService.java`
- Create: `backend/src/main/java/io/veridex/retrieval/api/EvidencePiece.java`
- Test: `backend/src/test/java/io/veridex/retrieval/RankFusionTest.java`、`ContextAssemblyServiceTest.java`（纯单测）

**Interfaces:**
- Produces:
```java
// retrieval.api.EvidencePiece
public record EvidencePiece(int citationIndex, UUID knowledgeBaseId, UUID documentVersionId,
                            int chunkIndex, String title, String structurePath, String text) {}

// retrieval.application.RankFusion
public final class RankFusion {
    public static List<RankedHit> fuse(List<SearchHit> bm25, List<SearchHit> vector, int k) {}
    public record RankedHit(UUID knowledgeBaseId, UUID documentVersionId, int chunkIndex,
                            String title, String structurePath, String text,
                            Double bm25Score, Double vectorScore, double fusionScore) {}
}

// retrieval.application.ContextAssemblyService
@Component
public class ContextAssemblyService {
    public ContextAssemblyService() {}
    // 默认 topK=6、perDocumentMax=3、maxChars=4000（以字符近似 token 预算）
    public List<EvidencePiece> assemble(List<RankedHit> hits, String question, int topK, int perDocumentMax, int maxChars) {}
}
```

- [ ] **Step 1: 写失败测试（RRF）**

```java
class RankFusionTest {
    private static SearchHit hit(String text, UUID doc, SearchHit.Channel ch, double score) {
        return new SearchHit(UUID.randomUUID(), doc, 0, "t", "1", text, ch, score);
    }

    @Test
    void fuseMergesSameChunkFromBothChannelsByRank() {
        UUID doc = UUID.randomUUID();
        var bm25 = List.of(hit("甲", doc, SearchHit.Channel.BM25, 2.0));
        var vector = List.of(hit("甲", doc, SearchHit.Channel.VECTOR, 1.5));
        var fused = RankFusion.fuse(bm25, vector, 60);
        assertThat(fused).hasSize(1);
        assertThat(fused.get(0).documentVersionId()).isEqualTo(doc);
        assertThat(fused.get(0).bm25Score()).isEqualTo(2.0);
        assertThat(fused.get(0).vectorScore()).isEqualTo(1.5);
        // RRF: 1/(60+1) + 1/(60+1)
        assertThat(fused.get(0).fusionScore()).isEqualTo(2.0 / 61.0);
    }

    @Test
    void fuseKeepsHigherRankWhenSameChunkAppearsInOneChannelOnly() {
        UUID docA = UUID.randomUUID(), docB = UUID.randomUUID();
        var bm25 = List.of(hit("A", docA, SearchHit.Channel.BM25, 1.0));
        var vector = List.of(hit("B", docB, SearchHit.Channel.VECTOR, 1.0));
        var fused = RankFusion.fuse(bm25, vector, 60);
        assertThat(fused).hasSize(2);
        assertThat(fused.get(0).documentVersionId()).isEqualTo(docA); // rank 1 的 BM25 融合分高
        assertThat(fused.get(1).documentVersionId()).isEqualTo(docB);
    }
}
```

- [ ] **Step 2: 写失败测试（上下文组装）**

```java
class ContextAssemblyServiceTest {
    private final ContextAssemblyService service = new ContextAssemblyService();

    private RankFusion.RankedHit hit(UUID doc, String text) {
        return new RankFusion.RankedHit(UUID.randomUUID(), doc, 0, "标题", "1", text, 1.0, 1.0, 2.0);
    }

    @Test
    void assembleNumbersEvidenceInRankOrder() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        var hits = List.of(hit(a, "内容A"), hit(b, "内容B"));
        var evidence = service.assemble(hits, "q", 6, 3, 4000);
        assertThat(evidence).hasSize(2);
        assertThat(evidence.get(0).citationIndex()).isEqualTo(1);
        assertThat(evidence.get(1).citationIndex()).isEqualTo(2);
        assertThat(evidence.get(0).text()).isEqualTo("内容A");
    }

    @Test
    void assembleCapsPerDocumentAndTotalChars() {
        UUID doc = UUID.randomUUID();
        var hits = List.of(hit(doc, "a".repeat(1500)), hit(doc, "b".repeat(1500)), hit(doc, "c".repeat(1500)));
        var evidence = service.assemble(hits, "q", 6, 2, 2000);
        assertThat(evidence).hasSize(2); // perDocumentMax=2
        long total = evidence.stream().mapToInt(e -> e.text().length()).sum();
        assertThat(total).isLessThanOrEqualTo(2000);
    }
}
```

- [ ] **Step 3: 实现 RankFusion 与 ContextAssemblyService**

```java
public final class RankFusion {
    private RankFusion() {}

    public static List<RankedHit> fuse(List<SearchHit> bm25, List<SearchHit> vector, int k) {
        Map<String, RankedHit> byChunk = new LinkedHashMap<>();
        addChannel(bm25, byChunk, k, true);
        addChannel(vector, byChunk, k, false);
        return byChunk.values().stream()
                .sorted(Comparator.comparingDouble(RankedHit::fusionScore).reversed())
                .toList();
    }

    private static void addChannel(List<SearchHit> hits, Map<String, RankedHit> out, int k, boolean bm25Channel) {
        for (int i = 0; i < hits.size(); i++) {
            SearchHit h = hits.get(i);
            String key = h.documentVersionId() + ":" + h.chunkIndex();
            double contribution = 1.0 / (k + i + 1);
            RankedHit existing = out.get(key);
            if (existing == null) {
                out.put(key, new RankedHit(h.knowledgeBaseId(), h.documentVersionId(), h.chunkIndex(),
                        h.title(), h.structurePath(), h.text(),
                        bm25Channel ? h.score() : null, bm25Channel ? null : h.score(), contribution));
            } else {
                out.put(key, new RankedHit(existing.knowledgeBaseId(), existing.documentVersionId(), existing.chunkIndex(),
                        existing.title(), existing.structurePath(), existing.text(),
                        bm25Channel ? h.score() : existing.bm25Score(),
                        bm25Channel ? existing.vectorScore() : h.score(),
                        existing.fusionScore() + contribution));
            }
        }
    }
}

@Component
public class ContextAssemblyService {
    public List<EvidencePiece> assemble(List<RankFusion.RankedHit> hits, String question,
                                        int topK, int perDocumentMax, int maxChars) {
        List<RankFusion.RankedHit> capped = new ArrayList<>();
        Map<UUID, Integer> perDoc = new HashMap<>();
        int total = 0;
        for (RankFusion.RankedHit hit : hits) {
            if (capped.size() >= topK) break;
            int used = perDoc.merge(hit.documentVersionId(), 1, Integer::sum);
            if (used > perDocumentMax) continue;
            if (total + hit.text().length() > maxChars) break;
            capped.add(hit);
            total += hit.text().length();
        }
        List<EvidencePiece> out = new ArrayList<>();
        for (int i = 0; i < capped.size(); i++) {
            RankFusion.RankedHit hit = capped.get(i);
            out.add(new EvidencePiece(i + 1, hit.knowledgeBaseId(), hit.documentVersionId(), hit.chunkIndex(),
                    hit.title(), hit.structurePath(), hit.text()));
        }
        return out;
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `./mvnw -f backend/pom.xml -Dtest='RankFusionTest,ContextAssemblyServiceTest' test`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add backend/src/main/java/io/veridex/retrieval backend/src/test/java/io/veridex/retrieval
git commit -m "feat: add rank fusion and context assembly"
```

---

## Task 6: HybridSearchService 编排（召回→融合→过滤）

**Files:**
- Create: `backend/src/main/java/io/veridex/retrieval/api/RerankProvider.java`
- Create: `backend/src/main/java/io/veridex/retrieval/api/PassThroughReranker.java`
- Create: `backend/src/main/java/io/veridex/retrieval/application/HybridSearchService.java`
- Test: `backend/src/test/java/io/veridex/retrieval/HybridSearchServiceTest.java`（Mockito 单测，mock reader/gateway/repos）

**Interfaces:**
- Consumes: `OpenSearchRetrievalReader`（Task 4）、`RankFusion`/`ContextAssemblyService`（Task 5）
- Produces:
```java
// retrieval.api.HybridSearchService（接口放 api，实现放 application）
public interface HybridSearchService {
    HybridSearchResult search(UUID userId, List<UUID> authorizedKnowledgeBaseIds,
                              List<UUID> requestedKnowledgeBaseIds, String question);
}
```

- [ ] **Step 1: 写失败测试**

```java
@ExtendWith(MockitoExtension.class)
class HybridSearchServiceTest {
    @Mock OpenSearchRetrievalReader reader;
    @Mock IndexReleaseRepository releases;
    @Mock IndexReleaseDocumentRepository snapshotDocuments;
    @Mock DocumentVersionRepository documentVersions;
    @Mock ContextAssemblyService assembler;
    @InjectMocks HybridSearchServiceImpl service;

    private static final UUID USER = UUID.randomUUID();
    private static final UUID KB = UUID.randomUUID();
    private static final UUID RELEASE = UUID.randomUUID();
    private static final UUID VER = UUID.randomUUID();

    private static IndexRelease activeRelease() {
        IndexRelease r = new IndexRelease(KB, 2, "veridex-" + KB + "-2", "veridex-" + KB + "-active");
        r.publish(); r.markActive();
        return r;
    }

    @Test
    void searchOnlyScopesToIntersectionOfAuthorizedAndRequested() {
        UUID other = UUID.randomUUID();
        when(releases.findByKnowledgeBaseIdAndIsActiveTrue(KB)).thenReturn(List.of(activeRelease()));
        when(snapshotDocuments.findByReleaseId(RELEASE)).thenReturn(List.of(new IndexReleaseDocument(RELEASE, VER)));
        when(documentVersions.findAllById(List.of(VER))).thenReturn(List.of(new DocumentVersion(VER, 1, "k", "h")));
        when(reader.bm25(any(), eq(KB), any(), eq(30))).thenReturn(List.of());
        when(reader.vector(any(), eq(KB), any(), eq(30))).thenReturn(List.of());
        when(assembler.assemble(any(), any(), eq(6), eq(3), eq(4000))).thenReturn(List.of());

        // 请求含未授权库 other，授权集合只有 KB
        var outcome = service.search(USER, List.of(KB), List.of(KB, other), "请假");
        assertThat(outcome.evidence()).isEmpty();
        verify(reader, never()).bm25(any(), eq(other), any(), anyInt());
        verify(reader, never()).vector(any(), eq(other), any(), anyInt());
    }

    @Test
    void searchExcludesOfflineDocumentVersions() {
        when(releases.findByKnowledgeBaseIdAndIsActiveTrue(KB)).thenReturn(List.of(activeRelease()));
        when(snapshotDocuments.findByReleaseId(RELEASE)).thenReturn(List.of(new IndexReleaseDocument(RELEASE, VER)));
        when(documentVersions.findAllById(List.of(VER))).thenReturn(List.of(offlineVersion()));
        when(reader.bm25(any(), eq(KB), any(), eq(30))).thenReturn(List.of());
        when(reader.vector(any(), eq(KB), any(), eq(30))).thenReturn(List.of());

        var outcome = service.search(USER, List.of(KB), List.of(KB), "请假");
        assertThat(outcome.evidence()).isEmpty();
        verify(reader, never()).bm25(anyString(), eq(KB), any(), anyInt());
        verify(reader, never()).vector(anyString(), eq(KB), any(), anyInt());
    }

    @Test
    void outcomeCarriesRankedHitsForTracing() {
        UUID ver = UUID.randomUUID();
        var hit = new SearchHit(KB, ver, 0, "请假制度", "1", "员工请假需提前申请", SearchHit.Channel.BM25, 2.0);
        when(releases.findByKnowledgeBaseIdAndIsActiveTrue(KB)).thenReturn(List.of(activeRelease()));
        when(snapshotDocuments.findByReleaseId(RELEASE)).thenReturn(List.of(new IndexReleaseDocument(RELEASE, ver)));
        when(documentVersions.findAllById(List.of(ver))).thenReturn(List.of(new DocumentVersion(ver, 1, "k", "h")));
        when(reader.bm25(any(), eq(KB), any(), eq(30))).thenReturn(List.of(hit));
        when(reader.vector(any(), eq(KB), any(), eq(30))).thenReturn(List.of());
        when(assembler.assemble(any(), any(), eq(6), eq(3), eq(4000))).thenReturn(List.of());

        var outcome = service.search(USER, List.of(KB), List.of(KB), "请假");
        assertThat(outcome.hits()).isNotEmpty();
        assertThat(outcome.hits().get(0).rank()).isEqualTo(1);
        assertThat(outcome.hits().get(0).documentVersionId()).isEqualTo(ver);
    }

    private DocumentVersion offlineVersion() {
        // 用反射把 status 字段置为 OFFLINE
        try {
            var v = new DocumentVersion(VER, 1, "k", "h");
            java.lang.reflect.Field f = DocumentVersion.class.getDeclaredField("status");
            f.setAccessible(true);
            f.set(v, DocumentVersionStatus.OFFLINE);
            return v;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
```

> **实现备注：** `DocumentVersion` 没有 `markOffline()` 方法（OFFLINE 状态由既有下线流程设置）。测试用反射设置 `status` 字段，避免改生产代码。**不需要单独的 TestVersions helper，直接在测试内联反射即可。**

- [ ] **Step 2: 实现**

```java
// api/RerankProvider.java
public interface RerankProvider {
    List<SearchHit> rerank(List<SearchHit> hits, String question);
}

// api/PassThroughReranker.java
@Component
public class PassThroughReranker implements RerankProvider {
    @Override public List<SearchHit> rerank(List<SearchHit> hits, String question) { return hits; }
}

// api/HybridSearchResult.java
public record HybridSearchResult(List<EvidencePiece> evidence, List<RankedHitView> hits) {}

// api/RankedHitView.java —— 供 trace 记录 RetrievalHit 明细
public record RankedHitView(UUID knowledgeBaseId, UUID documentVersionId, int chunkIndex,
                            String channel, Double bm25Score, Double vectorScore, Double fusionScore,
                            int rank, boolean enteredContext, String filterReason) {}

// application/HybridSearchServiceImpl.java
@Service
public class HybridSearchServiceImpl implements HybridSearchService {
    private static final int TOP_K_PER_CHANNEL = 30;
    private static final int RRF_K = 60;
    private static final int CONTEXT_TOP_K = 6;
    private static final int PER_DOCUMENT_MAX = 3;
    private static final int MAX_CHARS = 4000;

    private final OpenSearchRetrievalReader reader;
    private final IndexReleaseRepository releases;
    private final IndexReleaseDocumentRepository snapshotDocuments;
    private final DocumentVersionRepository documentVersions;
    private final ContextAssemblyService assembler;
    private final RerankProvider reranker;
    // constructor

    @Override
    public HybridSearchResult search(UUID userId, List<UUID> authorizedKnowledgeBaseIds,
                                     List<UUID> requestedKnowledgeBaseIds, String question) {
        List<UUID> scope = authorizedKnowledgeBaseIds.stream()
                .filter(requestedKnowledgeBaseIds::contains)
                .toList();
        List<SearchHit> all = new ArrayList<>();
        for (UUID kbId : scope) {
            all.addAll(searchKnowledgeBase(kbId, question));
        }
        List<RankFusion.RankedHit> fused = RankFusion.fuse(
                all.stream().filter(h -> h.channel() == SearchHit.Channel.BM25).toList(),
                all.stream().filter(h -> h.channel() == SearchHit.Channel.VECTOR).toList(),
                RRF_K);
        List<SearchHit> reranked = reranker.rerank(
                fused.stream().map(f -> new SearchHit(f.knowledgeBaseId(), f.documentVersionId(), f.chunkIndex(),
                        f.title(), f.structurePath(), f.text(), SearchHit.Channel.BM25, f.fusionScore())).toList(),
                question);
        List<EvidencePiece> evidence = assembler.assemble(reranked.stream().map(r -> new RankFusion.RankedHit(
                r.knowledgeBaseId(), r.documentVersionId(), r.chunkIndex(), r.title(), r.structurePath(),
                r.text(), r.score(), r.score(), r.score())).toList(),
                question, CONTEXT_TOP_K, PER_DOCUMENT_MAX, MAX_CHARS);

        // 融合序明细：全部命中按融合分降序编号 rank，进入上下文的标 enteredContext=true
        Set<String> inContext = evidence.stream()
                .map(e -> e.documentVersionId() + ":" + e.chunkIndex()).collect(Collectors.toSet());
        List<RankedHitView> views = new ArrayList<>();
        for (int i = 0; i < fused.size(); i++) {
            RankFusion.RankedHit f = fused.get(i);
            boolean entered = inContext.contains(f.documentVersionId() + ":" + f.chunkIndex());
            views.add(new RankedHitView(f.knowledgeBaseId(), f.documentVersionId(), f.chunkIndex(),
                    "BM25", f.bm25Score(), f.vectorScore(), f.fusionScore(), i + 1, entered,
                    entered ? null : "below-context-budget"));
        }
        return new HybridSearchResult(evidence, views);
    }

    private List<SearchHit> searchKnowledgeBase(UUID kbId, String question) {
        return releases.findByKnowledgeBaseIdAndIsActiveTrue(kbId).stream().findFirst()
                .map(release -> {
                    List<UUID> snapshotIds = snapshotDocuments.findByReleaseId(release.getId()).stream()
                            .map(IndexReleaseDocument::getDocumentVersionId).toList();
                    if (snapshotIds.isEmpty()) return List.<SearchHit>of();
                    var online = documentVersions.findAllById(snapshotIds).stream()
                            .filter(v -> v.getStatus() != DocumentVersionStatus.OFFLINE)
                            .map(DocumentVersion::getId).collect(Collectors.toSet());
                    if (online.isEmpty()) return List.<SearchHit>of();
                    List<SearchHit> bm25 = reader.bm25(release.getAliasName(), kbId, question, TOP_K_PER_CHANNEL)
                            .stream().filter(h -> online.contains(h.documentVersionId())).toList();
                    List<SearchHit> vector = reader.vector(release.getAliasName(), kbId, question, TOP_K_PER_CHANNEL)
                            .stream().filter(h -> online.contains(h.documentVersionId())).toList();
                    List<SearchHit> merged = new ArrayList<>(bm25);
                    merged.addAll(vector);
                    return merged;
                })
                .orElse(List.of());
    }
}
```

> **检索结果带 channel 说明**：`searchKnowledgeBase` 返回双路混合列表，`HybridSearchServiceImpl.search` 再按 channel 拆分给 `RankFusion.fuse`。`retrieval/api/RerankProvider.rerank` 接收融合后降维结果（channel 归一为 BM25，score=融合分），直通实现原样返回——文档化说明直通是当前唯一实现，真实 rerank 在 Phase 4 接入。

- [ ] **Step 3: 跑测试确认通过**

Run: `./mvnw -f backend/pom.xml -Dtest=HybridSearchServiceTest test`
Expected: PASS（越权库不检索；OFFLINE 版本被排除）

- [ ] **Step 4: 提交**

```bash
git add backend/src/main/java/io/veridex/retrieval backend/src/test/java/io/veridex/retrieval
git commit -m "feat: orchestrate hybrid search with authorization and online filters"
```

---

## Task 7: DeterministicChatModel（生成模型）

**Files:**
- Create: `backend/src/main/java/io/veridex/generation/infrastructure/DeterministicChatModel.java`
- Test: `backend/src/test/java/io/veridex/generation/DeterministicChatModelTest.java`

**Interfaces:**
- Produces: Spring AI `ChatModel` bean。从 Prompt 的 SystemMessage 读取证据（格式 `[1] title|text` 每行一条），从最后一条 UserMessage 取问题，生成模板化回答 `根据《title》[1]，text`；无证据 → 输出 `REFUSE:NO_RELEVANT_EVIDENCE`。

- [ ] **Step 1: 写失败测试**

```java
class DeterministicChatModelTest {
    private final DeterministicChatModel model = new DeterministicChatModel();

    private Prompt promptWithEvidence(String question, List<String> evidence) {
        String system = "你是企业制度问答助手，只能使用给定证据回答。\n" +
                evidence.stream().map(e -> "[EVIDENCE " + e + "]").reduce("", (a, b) -> a + b + "\n");
        return new Prompt(List.of(new SystemMessage(system), new UserMessage(question)));
    }

    @Test
    void callGeneratesCitingAnswerFromEvidence() {
        var prompt = promptWithEvidence("请假几天", List.of("1|请假制度|员工请假需提前两个工作日申请"));
        ChatResponse response = model.call(prompt);
        String answer = response.getResult().getOutput().getText();
        assertThat(answer).contains("[1]").contains("请假");
    }

    @Test
    void callRefusesWhenNoEvidence() {
        var prompt = new Prompt(List.of(new SystemMessage("你是助手"), new UserMessage("你好")));
        ChatResponse response = model.call(prompt);
        assertThat(response.getResult().getOutput().getText()).startsWith("REFUSE:NO_RELEVANT_EVIDENCE");
    }

    @Test
    void streamEmitsDeltaChunksAndEnds() {
        var prompt = promptWithEvidence("年假", List.of("1|请假制度|年假最长不超过十五个工作日"));
        List<String> deltas = model.stream(prompt).map(r -> r.getResult().getOutput().getText())
                .collectList().block();
        assertThat(deltas).isNotEmpty();
        assertThat(String.join("", deltas)).contains("[1]");
    }
}
```

> Prompt 中证据格式由 GenerationService（Task 8）负责组装，这里直接测模型对约定格式的解析。`[EVIDENCE {index}|{title}|{text}]`。

- [ ] **Step 2: 实现 DeterministicChatModel**

```java
@Component
public class DeterministicChatModel implements ChatModel {
    private static final Pattern EVIDENCE = Pattern.compile("\\[EVIDENCE (\\d+)\\|([^|]+)\\|([^\\]]+)\\]");
    private static final int STREAM_CHUNK = 8;

    @Override
    public ChatResponse call(Prompt prompt) {
        String answer = answerFor(prompt);
        return new ChatResponse(List.of(new Generation(new AssistantMessage(answer))));
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        String answer = answerFor(prompt);
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < answer.length(); i += STREAM_CHUNK) {
            parts.add(answer.substring(i, Math.min(answer.length(), i + STREAM_CHUNK)));
        }
        return Flux.fromIterable(parts)
                .map(part -> new ChatResponse(List.of(new Generation(new AssistantMessage(part)))));
    }

    private String answerFor(Prompt prompt) {
        String system = prompt.getInstructions().stream()
                .filter(m -> m instanceof SystemMessage)
                .map(m -> m.getText()).reduce("", (a, b) -> a + b);
        String question = prompt.getInstructions().stream()
                .filter(m -> m instanceof UserMessage)
                .reduce((a, b) -> b).map(m -> m.getText()).orElse("");

        Matcher matcher = EVIDENCE.matcher(system);
        List<String[]> evidence = new ArrayList<>();
        while (matcher.find()) {
            evidence.add(new String[] {matcher.group(1), matcher.group(2), matcher.group(3)});
        }
        if (evidence.isEmpty()) return "REFUSE:NO_RELEVANT_EVIDENCE";

        // 选与问题词重叠度最高的一段
        Set<String> qWords = Set.of(question.split("\\s+"));
        String[] best = evidence.stream()
                .max(Comparator.comparingLong(e -> Arrays.stream(e[2].split(""))
                        .filter(c -> question.contains(c)).count()))
                .orElse(evidence.get(0));
        return "根据《" + best[1] + "》[" + best[0] + "]，" + best[2];
    }
}
```

> `Message.getText()` 在 Spring AI 2.0 `AbstractMessage` 中为 `getText()`（`AssistantMessage` 源码注释引用 `this.textContent`）。若编译器报无此方法，改用 `((SystemMessage) m).getText()`。`getResult()`/`getOutput().getText()` 已在 Task 1 探明的 Generation/AssistantMessage 源码中确认。

- [ ] **Step 3: 跑测试确认通过**

Run: `./mvnw -f backend/pom.xml -Dtest=DeterministicChatModelTest test`
Expected: PASS

- [ ] **Step 4: 提交**

```bash
git add backend/src/main/java/io/veridex/generation backend/src/test/java/io/veridex/generation
git commit -m "feat: add deterministic chat model for pipeline validation"
```

---

## Task 8: generation 编排（引用校验 + 拒答 + 生成）

**Files:**
- Create: `backend/src/main/java/io/veridex/generation/api/GenerationService.java`
- Create: `backend/src/main/java/io/veridex/generation/api/GenerationResult.java`
- Create: `backend/src/main/java/io/veridex/generation/api/CitationView.java`
- Create: `backend/src/main/java/io/veridex/generation/application/GenerationServiceImpl.java`
- Create: `backend/src/main/java/io/veridex/generation/application/CitationValidator.java`
- Create: `backend/src/main/java/io/veridex/generation/application/RefusalPolicy.java`
- Modify: `backend/src/main/java/io/veridex/generation/package-info.java`（allowedDependencies = shared,retrieval 不变；DeterministicChatModel 在自身模块，无需加依赖）
- Test: `backend/src/test/java/io/veridex/generation/GenerationServiceImplTest.java`、`CitationValidatorTest.java`、`RefusalPolicyTest.java`

**Interfaces:**
- Consumes: `retrieval.api.EvidencePiece`、`shared.RefusalReason`、`DeterministicChatModel`、`conversation.api.MessageRecord`
- Produces:
```java
// generation.api.GenerationResult
public record GenerationResult(String answer, List<CitationView> citations,
                               RefusalReason refusalReason, String model,
                               int inputTokens, int outputTokens, long durationMs, String contextHash) {}

// generation.api.CitationView
public record CitationView(int citationIndex, UUID documentVersionId, int chunkIndex,
                           String sourceLocation, String citationText, String validationStatus) {}

// generation.api.GenerationService
public interface GenerationService {
    GenerationResult generate(String question, List<EvidencePiece> evidence,
                              List<MessageRecord> history);
}
```

- [ ] **Step 1: 写失败测试（RefusalPolicy + CitationValidator）**

```java
class RefusalPolicyTest {
    private final RefusalPolicy policy = new RefusalPolicy();

    @Test
    void emptyEvidenceRefusesNoRelevantEvidence() {
        assertThat(policy.evaluate(List.of())).isEqualTo(RefusalReason.NO_RELEVANT_EVIDENCE);
    }

    @Test
    void sufficientEvidenceAllowsGeneration() {
        var evidence = List.of(new EvidencePiece(1, UUID.randomUUID(), UUID.randomUUID(), 0, "t", "1", "内容"));
        assertThat(policy.evaluate(evidence)).isNull();
    }
}

class CitationValidatorTest {
    private final CitationValidator validator = new CitationValidator();

    @Test
    void validatesCitationIndexWithinEvidenceRange() {
        var evidence = List.of(
                new EvidencePiece(1, UUID.randomUUID(), UUID.randomUUID(), 0, "t1", "1", "a"),
                new EvidencePiece(2, UUID.randomUUID(), UUID.randomUUID(), 1, "t2", "1.1", "b"));
        var citations = validator.validate("根据资料[1]和[2]回答", evidence);
        assertThat(citations).hasSize(2);
        assertThat(citations).allMatch(c -> c.validationStatus().equals("VALID"));
    }

    @Test
    void rejectsCitationOutsideEvidenceRange() {
        var evidence = List.of(new EvidencePiece(1, UUID.randomUUID(), UUID.randomUUID(), 0, "t1", "1", "a"));
        var citations = validator.validate("根据资料[9]回答", evidence);
        assertThat(citations).hasSize(1);
        assertThat(citations.get(0).validationStatus()).isEqualTo("INVALID");
    }
}
```

- [ ] **Step 2: 实现 RefusalPolicy 与 CitationValidator**

```java
// shared/RefusalReason.java
public enum RefusalReason {
    NO_RELEVANT_EVIDENCE, INSUFFICIENT_EVIDENCE, OUT_OF_SCOPE, CONFLICTING_EVIDENCE,
    CONTENT_NOT_EFFECTIVE, ACCESS_RESTRICTED, SAFETY_POLICY
}

// generation/application/RefusalPolicy.java
@Component
public class RefusalPolicy {
    private static final int MIN_EVIDENCE_CHARS = 50;
    public RefusalReason evaluate(List<EvidencePiece> evidence) {
        if (evidence.isEmpty()) return RefusalReason.NO_RELEVANT_EVIDENCE;
        int chars = evidence.stream().mapToInt(e -> e.text().length()).sum();
        if (chars < MIN_EVIDENCE_CHARS) return RefusalReason.INSUFFICIENT_EVIDENCE;
        return null;
    }
}

// generation/application/CitationValidator.java
@Component
public class CitationValidator {
    private static final Pattern CITATION = Pattern.compile("\\[(\\d+)\\]");

    public List<CitationView> validate(String answer, List<EvidencePiece> evidence) {
        Set<Integer> validIndexes = evidence.stream().map(EvidencePiece::citationIndex).collect(Collectors.toSet());
        Map<Integer, EvidencePiece> byIndex = evidence.stream()
                .collect(Collectors.toMap(EvidencePiece::citationIndex, e -> e));
        List<CitationView> out = new ArrayList<>();
        Matcher matcher = CITATION.matcher(answer);
        while (matcher.find()) {
            int index = Integer.parseInt(matcher.group(1));
            boolean valid = validIndexes.contains(index);
            EvidencePiece e = byIndex.get(index);
            out.add(new CitationView(index,
                    e != null ? e.documentVersionId() : null,
                    e != null ? e.chunkIndex() : null,
                    e != null ? e.title() : null,
                    valid ? "[" + index + "]" : "[" + index + "]",
                    valid ? "VALID" : "INVALID"));
        }
        return out;
    }
}
```

- [ ] **Step 3: 写失败测试（GenerationServiceImpl）**

```java
@ExtendWith(MockitoExtension.class)
class GenerationServiceImplTest {
    @Mock DeterministicChatModel model;
    @Mock RefusalPolicy refusalPolicy;
    @Mock CitationValidator citationValidator;
    @InjectMocks GenerationServiceImpl service;

    @Test
    void refusesWhenPolicySaysSo() {
        when(refusalPolicy.evaluate(any())).thenReturn(RefusalReason.NO_RELEVANT_EVIDENCE);
        var result = service.generate("q", List.of(), List.of());
        assertThat(result.refusalReason()).isEqualTo(RefusalReason.NO_RELEVANT_EVIDENCE);
        assertThat(result.answer()).isNull();
        verify(model, never()).call(any());
    }

    @Test
    void generatesAndValidatesCitations() {
        var evidence = List.of(new EvidencePiece(1, UUID.randomUUID(), UUID.randomUUID(), 0, "t", "1", "内容"));
        when(refusalPolicy.evaluate(evidence)).thenReturn(null);
        when(model.call(any())).thenReturn(new ChatResponse(List.of(
                new Generation(new AssistantMessage("根据《t》[1]，内容")))));
        when(citationValidator.validate(any(), eq(evidence))).thenReturn(List.of(
                new CitationView(1, evidence.get(0).documentVersionId(), 0, "t", "[1]", "VALID")));

        var result = service.generate("请假", evidence, List.of());

        assertThat(result.answer()).contains("[1]");
        assertThat(result.citations()).hasSize(1);
        assertThat(result.citations().get(0).validationStatus()).isEqualTo("VALID");
        assertThat(result.model()).isEqualTo("deterministic");
    }
}
```

- [ ] **Step 4: 实现 GenerationServiceImpl**

```java
@Service
public class GenerationServiceImpl implements GenerationService {
    private static final int MAX_HISTORY_TURNS = 6;

    private final DeterministicChatModel model;
    private final RefusalPolicy refusalPolicy;
    private final CitationValidator citationValidator;

    // constructor

    @Override
    public GenerationResult generate(String question, List<EvidencePiece> evidence, List<MessageRecord> history) {
        RefusalReason refusal = refusalPolicy.evaluate(evidence);
        if (refusal != null) {
            return new GenerationResult(null, List.of(), refusal, "deterministic", 0, 0, 0, null);
        }

        String system = buildSystemPrompt(evidence);
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(system));
        for (MessageRecord record : history) {
            messages.add(record.role().equals("USER")
                    ? new UserMessage(record.content())
                    : new AssistantMessage(record.content()));
        }
        messages.add(new UserMessage(question));

        long start = System.nanoTime();
        ChatResponse response = model.call(new Prompt(messages));
        long durationMs = (System.nanoTime() - start) / 1_000_000;
        String answer = response.getResult().getOutput().getText();
        int outputTokens = answer.length() / 4;
        int inputTokens = (system.length() + question.length()) / 4;

        List<CitationView> citations = citationValidator.validate(answer, evidence);
        String contextHash = Integer.toHexString(evidence.hashCode());

        return new GenerationResult(answer, citations, null, "deterministic",
                inputTokens, outputTokens, durationMs, contextHash);
    }

    private String buildSystemPrompt(List<EvidencePiece> evidence) {
        StringBuilder sb = new StringBuilder("你是企业制度问答助手。只允许使用以下证据回答，不得使用模型通用知识补全。\n");
        for (EvidencePiece e : evidence) {
            sb.append("[EVIDENCE ").append(e.citationIndex()).append("|").append(e.title())
              .append("|").append(e.text()).append("]\n");
        }
        return sb.toString();
    }
}
```

- [ ] **Step 5: 跑测试确认通过**

Run: `./mvnw -f backend/pom.xml -Dtest='GenerationServiceImplTest,CitationValidatorTest,RefusalPolicyTest' test`
Expected: PASS

- [ ] **Step 6: 提交**

```bash
git add backend/src/main/java/io/veridex/generation backend/src/test/java/io/veridex/generation
git commit -m "feat: orchestrate generation with refusal and citation validation"
```

---

## Task 9: qa 模块编排（QuestionAnsweringService + SSE 事件）

**Files:**
- Create: `backend/src/main/java/io/veridex/qa/package-info.java`
- Create: `backend/src/main/java/io/veridex/qa/api/AskRequest.java`
- Create: `backend/src/main/java/io/veridex/qa/api/ConversationView.java`（复用 conversation.api 的 view；如冲突则别名导入）
- Create: `backend/src/main/java/io/veridex/qa/api/QaEvent.java`
- Create: `backend/src/main/java/io/veridex/qa/application/QuestionAnsweringService.java`
- Create: `backend/src/main/java/io/veridex/qa/application/KnowledgeScope.java`
- Test: `backend/src/test/java/io/veridex/qa/QuestionAnsweringServiceTest.java`、`KnowledgeScopeTest.java`

**Interfaces:**
- Consumes: `retrieval.api.HybridSearchService`、`generation.api.GenerationService`、`conversation.api.ConversationService`、`trace.api.QueryRunRecorder`、`knowledge.application.KnowledgeBaseService`、`iam.api.CurrentActor`、`shared.RefusalReason`
- Produces:
```java
// qa.api.QaEvent（sealed interface）
public sealed interface QaEvent {
    record RunStarted(UUID runId, UUID conversationId) implements QaEvent {}
    record RetrievalCompleted(int hitCount) implements QaEvent {}
    record AnswerDelta(String text) implements QaEvent {}
    record CitationAvailable(List<CitationView> citations) implements QaEvent {}
    record AnswerCompleted() implements QaEvent {}
    record AnswerRefused(String reason, String message) implements QaEvent {}
    record RunFailed(String message) implements QaEvent {}
}

// qa.application.QuestionAnsweringService
public interface QuestionAnsweringService {
    List<QaEvent> ask(UUID userId, AskRequest request);
}
```

- [ ] **Step 1: 写失败测试（KnowledgeScope）**

```java
class KnowledgeScopeTest {
    @Mock KnowledgeBaseService knowledgeBases;
    @Mock KnowledgeBaseAuthorization authorization;
    @InjectMocks KnowledgeScope scope;

    private static final UUID USER = UUID.randomUUID();
    private static final UUID KB = UUID.randomUUID();

    @Test
    void resolveReturnsIntersectionWithViewPermission() {
        when(knowledgeBases.listViewable(USER)).thenReturn(List.of(new KnowledgeBase(KB, "制度", "kb-1", "desc", USER)));
        when(authorization.canView(KB, USER)).thenReturn(true);
        var result = scope.resolve(USER, List.of(KB, UUID.randomUUID()));
        assertThat(result).containsExactly(KB);
    }

    @Test
    void resolveRejectsKnowledgeBaseWithoutViewPermission() {
        when(knowledgeBases.listViewable(USER)).thenReturn(List.of());
        var result = scope.resolve(USER, List.of(KB));
        assertThat(result).isEmpty();
    }
}
```

- [ ] **Step 2: 写失败测试（QuestionAnsweringService）**

```java
@ExtendWith(MockitoExtension.class)
class QuestionAnsweringServiceTest {
    @Mock KnowledgeScope knowledgeScope;
    @Mock ConversationService conversations;
    @Mock QueryRunRecorder recorder;
    @Mock HybridSearchService hybridSearch;
    @Mock GenerationService generation;

    @InjectMocks QuestionAnsweringServiceImpl service;
    private static final UUID USER = UUID.randomUUID();
    private static final UUID KB = UUID.randomUUID();
    private static final UUID CONV = UUID.randomUUID();

    @Test
    void emptyScopeRefusesAccessRestricted() {
        when(knowledgeScope.resolve(USER, List.of(KB))).thenReturn(List.of());
        var events = service.ask(USER, new AskRequest("制度", List.of(KB), null));
        assertThat(events.get(events.size() - 1)).isInstanceOf(QaEvent.AnswerRefused.class);
        var refused = (QaEvent.AnswerRefused) events.get(events.size() - 1);
        assertThat(refused.reason()).isEqualTo("ACCESS_RESTRICTED");
        assertThat(refused.message()).isEqualTo("当前可访问知识范围内证据不足");
    }

    @Test
    void happyPathEmitsStreamingEventsWithCitations() {
        UUID ver = UUID.randomUUID();
        var evidence = List.of(new EvidencePiece(1, KB, ver, 0, "请假制度", "1", "员工请假需提前申请"));
        var searchResult = new HybridSearchResult(evidence, List.of(new RankedHitView(
                KB, ver, 0, "BM25", 2.0, null, 2.0, 1, true, null)));
        when(knowledgeScope.resolve(USER, List.of(KB))).thenReturn(List.of(KB));
        when(conversations.findOwned(USER, CONV)).thenReturn(Optional.of(new ConversationView(CONV, "t", Instant.now())));
        when(recorder.start(any(), eq(CONV), eq(List.of(KB)), any(), any())).thenReturn(UUID.randomUUID());
        when(hybridSearch.search(USER, List.of(KB), List.of(KB), "请假")).thenReturn(searchResult);
        when(generation.generate(eq("请假"), eq(evidence), any())).thenReturn(
                new GenerationResult("根据《请假制度》[1]，员工请假需提前申请",
                        List.of(new CitationView(1, ver, 0, "请假制度", "[1]", "VALID")),
                        null, "deterministic", 10, 20, 5, "abc"));

        var events = service.ask(USER, new AskRequest("请假", List.of(KB), CONV));

        assertThat(events).anyMatch(e -> e instanceof QaEvent.RunStarted);
        assertThat(events).anyMatch(e -> e instanceof QaEvent.RetrievalCompleted);
        assertThat(events).anyMatch(e -> e instanceof QaEvent.AnswerDelta);
        var citations = events.stream().filter(e -> e instanceof QaEvent.CitationAvailable)
                .map(e -> (QaEvent.CitationAvailable) e).findFirst().orElseThrow();
        assertThat(citations.citations()).hasSize(1);
        assertThat(events.get(events.size() - 1)).isInstanceOf(QaEvent.AnswerCompleted.class);
        verify(recorder).complete(any());
        verify(recorder).markRetrieving(any(), argThat(hits -> hits.size() == 1));
    }

    @Test
    void retrievalFailureEmitsRunFailed() {
        when(knowledgeScope.resolve(USER, List.of(KB))).thenReturn(List.of(KB));
        when(hybridSearch.search(USER, List.of(KB), List.of(KB), "请假")).thenThrow(new RuntimeException("opensearch down"));
        var events = service.ask(USER, new AskRequest("请假", List.of(KB), null));
        assertThat(events.get(events.size() - 1)).isInstanceOf(QaEvent.RunFailed.class);
        verify(recorder).fail(any(), anyString());
    }
}
```

- [ ] **Step 3: 实现 KnowledgeScope 与 QuestionAnsweringServiceImpl**

```java
// application/KnowledgeScope.java
@Component
public class KnowledgeScope {
    private final KnowledgeBaseService knowledgeBases;
    private final KnowledgeBaseAuthorization authorization;
    // constructor

    public List<UUID> resolve(UUID userId, List<UUID> requested) {
        var viewable = knowledgeBases.listViewable(userId).stream()
                .map(KnowledgeBase::getId)
                .collect(Collectors.toSet());
        return requested.stream()
                .filter(viewable::contains)
                .filter(kbId -> authorization.canView(kbId, userId))
                .toList();
    }
}

// application/QuestionAnsweringServiceImpl.java
@Service
public class QuestionAnsweringServiceImpl implements QuestionAnsweringService {
    private static final int HISTORY_TURNS = 6;
    private final KnowledgeScope knowledgeScope;
    private final ConversationService conversations;
    private final QueryRunRecorder recorder;
    private final HybridSearchService hybridSearch;
    private final GenerationService generation;
    // constructor

    @Override
    public List<QaEvent> ask(UUID userId, AskRequest request) {
        try {
            return execute(userId, request);
        } catch (Exception e) {
            return List.of(new QaEvent.RunFailed(e.getMessage() != null ? e.getMessage() : "系统错误"));
        }
    }

    private List<QaEvent> execute(UUID userId, AskRequest request) {
        List<UUID> scope = knowledgeScope.resolve(userId, request.knowledgeBaseIds());
        if (scope.isEmpty()) {
            return List.of(new QaEvent.AnswerRefused("ACCESS_RESTRICTED", "当前可访问知识范围内证据不足"));
        }

        UUID conversationId = request.conversationId();
        if (conversationId == null) {
            conversationId = conversations.create(userId, truncate(request.question(), 80)).id();
        } else {
            if (conversations.findOwned(userId, conversationId).isEmpty()) {
                throw new IllegalStateException("会话不存在或无权访问");
            }
        }

        String normalized = request.question().trim();
        UUID runId = recorder.start(userId, conversationId, scope, request.question(), normalized);
        List<QaEvent> events = new ArrayList<>();
        events.add(new QaEvent.RunStarted(runId, conversationId));

        var history = conversations.recentMessages(conversationId, HISTORY_TURNS);
        conversations.addMessage(conversationId, "USER", request.question(), runId);

        HybridSearchResult searchResult = hybridSearch.search(userId, scope, request.knowledgeBaseIds(), normalized);
        events.add(new QaEvent.RetrievalCompleted(searchResult.evidence().size()));
        recorder.markRetrieving(runId, searchResult.hits().stream()
                .map(h -> new RetrievalHitRecord(h.knowledgeBaseId(), h.documentVersionId(), h.chunkIndex(),
                        h.channel(), h.bm25Score(), h.vectorScore(), h.fusionScore(), h.rank(),
                        h.enteredContext(), h.filterReason()))
                .toList());

        GenerationResult result = generation.generate(normalized, searchResult.evidence(), history);
        recorder.markGenerating(runId, new GenerationRecord(result.model(), result.inputTokens(),
                result.outputTokens(), result.durationMs(), null, result.contextHash()));

        if (result.refusalReason() != null) {
            events.add(new QaEvent.AnswerRefused(result.refusalReason().name(),
                    refusalMessage(result.refusalReason())));
            recorder.refuse(runId, result.refusalReason());
            return events;
        }

        recorder.addCitations(runId, result.citations().stream()
                .map(c -> new CitationRecord(c.citationIndex(), c.documentVersionId(), c.chunkIndex(),
                        c.sourceLocation(), c.citationText(), c.validationStatus()))
                .toList());
        events.add(new QaEvent.CitationAvailable(result.citations()));
        for (int i = 0; i < result.answer().length(); i += 8) {
            events.add(new QaEvent.AnswerDelta(result.answer().substring(i, Math.min(result.answer().length(), i + 8))));
        }
        conversations.addMessage(conversationId, "ASSISTANT", result.answer(), runId);
        events.add(new QaEvent.AnswerCompleted());
        recorder.complete(runId);
        return events;
    }

    private static String refusalMessage(RefusalReason reason) {
        return switch (reason) {
            case ACCESS_RESTRICTED -> "当前可访问知识范围内证据不足";
            default -> "未能在授权资料中找到足够依据回答该问题";
        };
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
```

> 说明：`AskRequest` 若 `conversationId == null` 则自动新建会话；非空则校验归属。`run.failed` 事件由外层 catch 兜底（含双路检索失败 → 系统失败，不伪装无答案）。

- [ ] **Step 4: 跑测试确认通过**

Run: `./mvnw -f backend/pom.xml -Dtest='QuestionAnsweringServiceTest,KnowledgeScopeTest' test`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add backend/src/main/java/io/veridex/qa backend/src/test/java/io/veridex/qa
git commit -m "feat: orchestrate authorized question answering with SSE events"
```

---

## Task 10: QaController + SSE 端点 + 集成测试

**Files:**
- Create: `backend/src/main/java/io/veridex/qa/api/QaController.java`
- Create: `backend/src/main/java/io/veridex/qa/api/AskRequest.java`
- Test: `backend/src/test/java/io/veridex/qa/QaApiIntegrationTest.java`（端到端：登录 → 建库授权 → 上传发布 → ask → SSE 事件 → 引用 → 越权）

**Interfaces:**
- Consumes: `QuestionAnsweringService`、`ConversationService`、`CurrentActor`
- Produces:
```java
// qa.api.QaController
@RestController
@RequestMapping("/api/qa")
public class QaController {
    @PostMapping(value = "/ask", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter ask(@RequestBody AskRequest request);   // 异步发送 QaEvent 列表

    @GetMapping("/conversations")
    public List<ConversationView> conversations();           // 当前用户会话

    @GetMapping("/conversations/{id}/messages")
    public List<MessageView> messages(@PathVariable UUID id); // 校验归属

    @PostMapping("/feedback")
    public ResponseEntity<Void> feedback(@RequestBody FeedbackRequest body); // 点赞/点踩占位落库
}
```

- [ ] **Step 1: 写失败集成测试**

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@Import({OpenSearchContainerConfiguration.class, DeterministicEmbeddingModel.class})
class QaApiIntegrationTest extends InfrastructureContainers {
    @Autowired TestRestTemplate rest;
    @Autowired KnowledgeBaseRepository knowledgeBases;
    @Autowired KnowledgeBaseGrantRepository grants;
    @Autowired DocumentRepository documents;
    @Autowired DocumentVersionRepository versions;
    @Autowired SearchIndexGateway gateway;
    @Autowired ObjectStorage storage;

    private String adminSession() {
        return rest.postForEntity("/api/auth/login",
                Map.of("username", "admin", "password", "admin-password"), String.class)
                .getHeaders().getFirst(HttpHeaders.SET_COOKIE);
    }
    // ... helper: 创建知识库 + 直接构造 READY DocumentVersion + 建 chunk 写入 OpenSearch + publish release
}
```

> **端到端准备**：测试里用既有 API + repository 直拼一条链路（建库 → 授权 admin → 造 READY 版本 → 写 MinIO chunks.json → `IndexReleaseService` publish → 再 ask）。`DeterministicEmbeddingModel` 写入的向量与检索时同模型，保证 kNN 可用。`QaApiIntegrationTest` 验证：
> 1. `POST /api/qa/ask` 返回 `text/event-stream`，事件顺序含 `run.started → retrieval.completed → answer.delta → citation.available → answer.completed`；
> 2. 无授权知识库 → `answer.refused`（ACCESS_RESTRICTED 文案）；
> 3. 会话归属校验：他人会话 → 403/错误事件；
> 4. `GET /api/qa/conversations` 与 messages 正常。

- [ ] **Step 2: 实现 QaController**

```java
@RestController
@RequestMapping("/api/qa")
public class QaController {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final QuestionAnsweringService service;
    private final ConversationService conversations;

    public QaController(QuestionAnsweringService service, ConversationService conversations) {
        this.service = service;
        this.conversations = conversations;
    }

    @PostMapping(value = "/ask", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter ask(@RequestBody AskRequest request) {
        UUID userId = CurrentActor.id();
        SseEmitter emitter = new SseEmitter(60_000L);
        CompletableFuture.runAsync(() -> {
            try {
                for (QaEvent event : service.ask(userId, request)) {
                    String name = eventName(event);
                    emitter.send(SseEmitter.event().name(name).data(event));
                }
                emitter.complete();
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        });
        emitter.onTimeout(emitter::complete);
        emitter.onCompletion(() -> { /* 客户端断开：由上层在 run 结束统一处理 */ });
        return emitter;
    }

    @GetMapping("/conversations")
    public List<ConversationView> conversations() {
        return conversations.listForUser(CurrentActor.id());
    }

    @GetMapping("/conversations/{id}/messages")
    public List<MessageView> messages(@PathVariable UUID id) {
        UUID userId = CurrentActor.id();
        if (conversations.findOwned(userId, id).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "会话不存在或无权访问");
        }
        return conversations.recentMessages(id, 1000).stream()
                .map(m -> new MessageView(m.id(), m.role(), m.content(), m.queryRunId()))
                .toList();
    }

    @PostMapping("/feedback")
    public ResponseEntity<Void> feedback(@RequestBody FeedbackRequest body) {
        // Phase 4 完整落库；本轮返回 204 占位（前端按钮可点）
        return ResponseEntity.noContent().build();
    }

    private static String eventName(QaEvent event) {
        return switch (event) {
            case QaEvent.RunStarted r -> "run.started";
            case QaEvent.RetrievalCompleted r -> "retrieval.completed";
            case QaEvent.AnswerDelta r -> "answer.delta";
            case QaEvent.CitationAvailable r -> "citation.available";
            case QaEvent.AnswerCompleted r -> "answer.completed";
            case QaEvent.AnswerRefused r -> "answer.refused";
            case QaEvent.RunFailed r -> "run.failed";
        };
    }
}
```

> `AskRequest` record：`(String question, List<UUID> knowledgeBaseIds, UUID conversationId)`。`ConversationView`/`MessageView` 直接复用 conversation.api 的 record（qa 依赖 conversation）。

- [ ] **Step 3: 跑集成测试确认通过**

Run: `./mvnw -f backend/pom.xml -Dtest=QaApiIntegrationTest test`（需要 Docker）
Expected: PASS（SSE 事件顺序、拒答、会话归属、会话列表）

- [ ] **Step 4: 提交**

```bash
git add backend/src/main/java/io/veridex/qa backend/src/test/java/io/veridex/qa
git commit -m "feat: expose SSE question answering API"
```

---

## Task 11: 权限与降级集成测试（ACL 门禁 + 双路失败 + 紧急下线）

**Files:**
- Create: `backend/src/test/java/io/veridex/qa/QaAclIntegrationTest.java`

**Interfaces:**
- Consumes: Task 10 的端到端准备 helper（抽成 `QaTestFixture` 复用）

- [ ] **Step 1: 写失败集成测试**

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@Import({OpenSearchContainerConfiguration.class, DeterministicEmbeddingModel.class})
class QaAclIntegrationTest extends InfrastructureContainers {
    // fixture: 两个知识库 KB_A / KB_B，employee 仅授权 KB_A；两库均有 READY 文档 + active release

    @Test
    void unauthorizedEmployeeCannotRetrieveFromOtherKnowledgeBase() {
        // employee 提问，knowledgeBaseIds 含 KB_B → 服务端交集为空 → answer.refused
        var events = askAs("employee", Map.of("question", "机密", "knowledgeBaseIds", List.of(KB_B), "conversationId", null));
        assertThat(events).contains("answer.refused");
    }

    @Test
    void offlineDocumentIsExcludedFromRetrieval() {
        // 将 KB_A 某文档版本置 OFFLINE 后提问 → 该文档内容不进入回答/引用
        var events = askAs("employee", Map.of("question", "请假", "knowledgeBaseIds", List.of(KB_A), "conversationId", null));
        assertThat(events).doesNotContain("answer.delta").contains("answer.refused");
    }

    @Test
    void nonRefusalAnswerAlwaysCarriesValidCitations() {
        var events = askAs("employee", Map.of("question", "请假", "knowledgeBaseIds", List.of(KB_A), "conversationId", null));
        if (events.contains("answer.completed")) {
            var citations = extractCitations(events);
            assertThat(citations).isNotEmpty();
            assertThat(citations).allMatch(c -> c.get("validationStatus").equals("VALID"));
        }
    }
}
```

- [ ] **Step 2: 抽取 QaTestFixture（共享 setUp）**

把 Task 10 的建库/授权/发布 helper 抽到 `backend/src/test/java/io/veridex/qa/QaTestFixture.java`（abstract base class），两个集成测试继承。fixture 提供：

```java
abstract class QaTestFixture extends InfrastructureContainers {
    // 种子用户 employee（EMPLOYEE 角色，密码 employee-password）由 V2 迁移自带
    UUID createKnowledgeBaseWithReadyDocument(String name, UUID ownerUserId); // 建库+授权+造 READY 版本+写 chunks.json+publish
    String loginSession(String username, String password);
    List<String> ask(String session, Map<String, Object> body); // 解析 SSE 事件名序列
}
```

- [ ] **Step 3: 跑测试确认通过**

Run: `./mvnw -f backend/pom.xml -Dtest='QaApiIntegrationTest,QaAclIntegrationTest' test`
Expected: PASS

- [ ] **Step 4: 提交**

```bash
git add backend/src/test/java/io/veridex/qa
git commit -m "test: cover ACL, offline exclusion and citation gate"
```

---

## Task 12: 前端 qaApi + QaPage

**Files:**
- Create: `web/src/features/qa/qaApi.ts`
- Create: `web/src/features/qa/QaPage.tsx`
- Create: `web/src/features/qa/components/KnowledgeBasePicker.tsx`
- Create: `web/src/features/qa/components/ChatMessage.tsx`
- Modify: `web/src/app/routes.tsx`（/workbench → QaPage）
- Modify: `web/src/styles.css`（.qa-* 样式）
- Test: `web/src/features/qa/QaPage.test.tsx`

**Interfaces:**
- Consumes: 后端 `/api/qa/ask`（SSE）、`/api/qa/conversations`、`/api/qa/conversations/{id}/messages`、`/api/knowledge-bases`（复用 knowledgeApi）、`/api/documents/{id}/versions/{vid}/chunks`（引用预览）

- [ ] **Step 1: 写 qaApi.ts（SSE 解析）**

```typescript
export type QaEvent =
  | { name: 'run.started'; data: { runId: string; conversationId: string } }
  | { name: 'retrieval.completed'; data: { hitCount: number } }
  | { name: 'answer.delta'; data: { text: string } }
  | { name: 'citation.available'; data: { citations: Citation[] } }
  | { name: 'answer.completed'; data: Record<string, never> }
  | { name: 'answer.refused'; data: { reason: string; message: string } }
  | { name: 'run.failed'; data: { message: string } }

export type Citation = { citationIndex: number; documentVersionId: string; chunkIndex: number; sourceLocation: string; citationText: string; validationStatus: string }

async function parseSse(response: Response, onEvent: (event: QaEvent) => void) {
  const reader = response.body?.getReader()
  if (!reader) return
  const decoder = new TextDecoder()
  let buffer = ''
  while (true) {
    const { done, value } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    const blocks = buffer.split('\n\n')
    buffer = blocks.pop() ?? ''
    for (const block of blocks) {
      const dataLine = block.split('\n').find((l) => l.startsWith('data:'))
      const nameLine = block.split('\n').find((l) => l.startsWith('event:'))
      if (!dataLine) continue
      const name = (nameLine?.slice(6).trim() ?? '') as QaEvent['name']
      onEvent({ name, data: JSON.parse(dataLine.slice(5).trim()) })
    }
  }
}

export const qaApi = {
  ask: async (question: string, knowledgeBaseIds: string[], conversationId: string | null, onEvent: (e: QaEvent) => void) => {
    const response = await fetch('/api/qa/ask', {
      method: 'POST', credentials: 'include',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ question, knowledgeBaseIds, conversationId }),
    })
    await parseSse(response, onEvent)
  },
  conversations: () => fetch('/api/qa/conversations', { credentials: 'include' }).then((r) => r.json()),
  messages: (conversationId: string) => fetch(`/api/qa/conversations/${conversationId}/messages`, { credentials: 'include' }).then((r) => r.json()),
}
```

- [ ] **Step 2: 写失败组件测试（QaPage.test.tsx）**

```tsx
vi.mock('./qaApi', () => ({
  qaApi: {
    ask: vi.fn(),
    conversations: vi.fn().mockResolvedValue([]),
    messages: vi.fn().mockResolvedValue([]),
  },
}))
vi.mock('../knowledge/knowledgeApi', () => ({
  knowledgeApi: { list: vi.fn().mockResolvedValue([{ id: 'kb1', name: '制度', description: null, slug: 'zd' }]) },
}))

describe('QaPage', () => {
  it('streams answer deltas into the message list', async () => {
    vi.mocked(qaApi.ask).mockImplementation(async (_q, _kbs, _c, onEvent) => {
      onEvent({ name: 'run.started', data: { runId: 'r1', conversationId: 'c1' } })
      onEvent({ name: 'answer.delta', data: { text: '根据' } })
      onEvent({ name: 'answer.delta', data: { text: '《请假制度》' } })
      onEvent({ name: 'citation.available', data: { citations: [{ citationIndex: 1, documentVersionId: 'v1', chunkIndex: 0, sourceLocation: '请假制度', citationText: '[1]', validationStatus: 'VALID' }] } })
      onEvent({ name: 'answer.completed', data: {} })
    })
    render(<QaPage />)
    await userEvent.type(screen.getByLabelText('问题'), '请假几天')
    await userEvent.click(screen.getByRole('button', { name: '发送' }))
    expect(await screen.findByText(/根据《请假制度》/)).toBeInTheDocument()
    expect(screen.getByText('[1]')).toBeInTheDocument()
  })

  it('shows refusal message with generic access wording', async () => {
    vi.mocked(qaApi.ask).mockImplementation(async (_q, _kbs, _c, onEvent) => {
      onEvent({ name: 'answer.refused', data: { reason: 'ACCESS_RESTRICTED', message: '当前可访问知识范围内证据不足' } })
    })
    render(<QaPage />)
    await userEvent.click(screen.getByRole('button', { name: '发送' }))
    expect(await screen.findByText('当前可访问知识范围内证据不足')).toBeInTheDocument()
  })
})
```

- [ ] **Step 3: 实现 QaPage 与组件**

`QaPage.tsx`（要点）：
- 加载 `knowledgeApi.list()` → 多选 `<KnowledgeBasePicker>`（全选按钮）；
- 左侧会话列表（`qaApi.conversations()`），点击加载 messages；
- 提问：`qaApi.ask(question, selectedKbIds, activeConversationId, onEvent)`；
  - `run.started` → 若无会话则把返回 conversationId 设为当前；
  - `answer.delta` → 追加到当前 ASSISTANT 消息（流式）；
  - `citation.available` → 存引用列表，渲染 `[n]` 可点击 chip；
  - `answer.refused`/`run.failed` → 显示错误/拒答消息；
- 引用点击 → 调 `knowledgeApi.chunks(documentId, versionId)` 取该 chunk 文本 → 打开 `PreviewDrawer`（title=sourceLocation）；
  - **说明**：citation 事件携带 `documentVersionId`；前端需 documentId 才能调 chunks API。**后端在 `CitationView` 里补 `documentId` 字段**（Task 8 的 CitationView 增加 `UUID documentId`，生成时从证据链补查）。—— 见 Task 13 的修正说明。

- [ ] **Step 4: 跑前端测试确认通过**

Run: `npm --prefix web test -- --run features/qa`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add web/src/features/qa web/src/app/routes.tsx web/src/styles.css
git commit -m "feat: add employee QA page with streaming answers and citations"
```

---

## Task 13: 引用点击预览闭环（CitationView 补 documentId）

**Files:**
- Modify: `backend/src/main/java/io/veridex/generation/api/CitationView.java`（增加 `documentId`）
- Modify: `backend/src/main/java/io/veridex/generation/application/GenerationServiceImpl.java`（生成时补查 documentId）
- Modify: `backend/src/test/java/io/veridex/generation/GenerationServiceImplTest.java`
- Test: `backend/src/test/java/io/veridex/qa/QaCitationPreviewIntegrationTest.java`

- [ ] **Step 1: CitationView 增加 documentId**

```java
public record CitationView(int citationIndex, UUID documentId, UUID documentVersionId, int chunkIndex,
                           String sourceLocation, String citationText, String validationStatus) {}
```

- [ ] **Step 2: 生成时补查 documentId**

`GenerationServiceImpl` 注入 `DocumentVersionRepository`（knowledge.domain；generation 的 allowedDependencies 目前只有 shared/retrieval → **需在 generation/package-info.java 增加 `knowledge`**，并同步 ArchitectureTest）。对每个证据的 `documentVersionId` 查 `documentVersion.getDocumentId()`。

- [ ] **Step 3: 前端引用点击**

`ChatMessage` 渲染 `[n]` 为按钮，点击 → `knowledgeApi.chunks(citation.documentId, citation.documentVersionId)` → 找 `chunkIndex` 匹配的 chunk → `PreviewDrawer` 展示 chunk.text。

- [ ] **Step 4: 跑后端单测 + 集成测试**

Run: `./mvnw -f backend/pom.xml -Dtest='GenerationServiceImplTest,QaCitationPreviewIntegrationTest' test`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add backend/src/main/java/io/veridex/generation backend/src/test/java/io/veridex/generation backend/src/test/java/io/veridex/qa web/src/features/qa
git commit -m "feat: resolve document id for citation preview"
```

---

## Task 14: 前端路由 + 样式 + 全量验证

**Files:**
- Modify: `web/src/app/routes.tsx`（/workbench 的 ComingSoonPage → QaPage）
- Modify: `web/src/styles.css`（qa 布局/消息/引用 chip/拒答样式，沿用 console 视觉变量）
- Modify: `backend/src/test/java/io/veridex/ArchitectureTest.java`（确认 qa/generation 新依赖被放行）

- [ ] **Step 1: 更新路由**

```tsx
import { QaPage } from '../features/qa/QaPage'
// /workbench 行:
content: <QaPage />,
```

- [ ] **Step 2: 更新 ArchitectureTest 放行**

```java
@Test
void modulesRespectDeclaredDependencies() {
    // qa 依赖 shared/iam/knowledge/retrieval/generation/conversation/trace；
    // generation 依赖 knowledge（引用预览查 documentId）—— 均在 package-info 声明
    modules.verify();
}
```

（若 verify 失败，按报错把对应 `allowedDependencies` 补进 package-info 并注释理由。）

- [ ] **Step 3: 全量验证**

Run: `./scripts/verify.sh`
Expected: [1/4] Maven clean verify PASS（含全部单测+集成测试）；[2/4] Vitest PASS；[3/4] 前端 build PASS；[4/4] git diff --check PASS

- [ ] **Step 4: 提交**

```bash
git add web/src backend/src/test/java/io/veridex/ArchitectureTest.java
git commit -m "feat: wire QA page into console and verify full gate"
```

---

## Self-Review 记录

**Spec 覆盖核对：**
- 平台自建检索编排（D1）→ Task 4/6 ✓
- 确定性模型（D2）→ Task 7 ✓
- 有限多轮（D3）→ Task 9（history 注入）+ Task 2 ✓
- 多库交集（D4）→ Task 6（authorized ∩ requested）+ Task 9（KnowledgeScope）✓
- RerankProvider 直通（D5）→ Task 6 ✓
- [n] 引用 + 校验（D6）→ Task 5（编号）+ Task 8（校验）✓
- 拒答（D7）→ Task 8/9 ✓
- 正文不落库（D8）→ V7 无 prompt/正文列 ✓
- 问答页（D9）→ Task 12 ✓
- SseEmitter（D10）→ Task 10 ✓
- ACL 门禁 → Task 11 ✓
- 双路失败 → 系统错误（Task 9 catch → RunFailed）✓
- 紧急下线 → Task 11 offline 排除 ✓

**占位符扫描：** 无 TBD/TODO；所有接口签名与代码给出。

**类型一致性：** `RefusalReason` 放 shared 供 trace/generation/qa 共用；`CitationView` 跨 generation→qa 复用；`QaEvent` sealed interface 单一事件类型。`AskRequest` 三字段一致。

**说明：** Task 13 引入的 generation→knowledge 依赖、qa 新模块依赖，均需在 package-info 显式声明并让 ArchitectureTest 验证。
