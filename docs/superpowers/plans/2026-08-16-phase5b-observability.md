# Phase 5-b 可观测性 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Per user instruction, do not delegate this plan to subagents.

**Goal:** 为 Veridex 建立企业试点可用的 metrics、distributed traces、dashboard、alert rules 与默认关闭的加密 trace-body 调试能力，同时保证 telemetry 默认不记录用户或文档正文。

**Architecture:** 在 `shared::observability` 提供类型安全、低基数的 Micrometer Observation 和 Rabbit context propagation；QA、retrieval、generation、outbox、ingestion、indexing 只在稳定业务边界埋点。`trace` 模块通过单一 V13 迁移新增 AES-256-GCM 短期正文表、平台管理员审计读取 API 与清理任务。Prometheus 抓应用和 RabbitMQ metrics；应用经 OTLP 向 Collector 导出 span，再由 Tempo 存储并在 Grafana 展示。

**Tech Stack:** Java 21、Spring Boot 4.0.0、Micrometer Observation/Prometheus、Micrometer Tracing OpenTelemetry bridge、OpenTelemetry OTLP、Flyway V13、PostgreSQL 17、RabbitMQ 4 management、Prometheus 3.13.2、Grafana 13.1.3、OpenTelemetry Collector Contrib 0.158.0、Tempo 3.0.3、Docker Compose、JUnit 5、Testcontainers。

**Design:** `docs/superpowers/specs/2026-08-16-phase5b-observability-design.md`。该文档的固定决策不得在实现中重新选择。

## Global Constraints

- Trace-body capture policy 只能是 `NONE`、`ERRORS`、`ALL`；默认 `NONE`。
- 默认正文 TTL 为 `24h`，最大 UTF-8 明文为 `256KiB`，清理间隔为 `1h`。
- Trace body 使用 AES-256-GCM、每行随机 12-byte nonce、128-bit tag；AAD 为 `veridex-trace-body:<runId>:<schemaVersion>:<keyId>`。
- 密钥只来自环境变量 key ring；仓库无默认密钥。`NONE` 可无密钥，`ERRORS/ALL` 配置非法必须启动失败。
- 新 `query_run` 永不保存原始 question、normalized question 或异常 message；固定占位为 `[REDACTED]`，error 仅保存固定 error code 或 null。
- Metrics、span attributes/events 和普通日志禁止出现 question、prompt、answer、chunk/evidence text、文件名/标题、凭据、原始异常 message，以及 user/KB/document/chunk/release UUID。`runId` 只允许 span/log correlation，不得成为 metric tag。
- Observation tag 只能通过固定枚举或 bounded provider/model resolver 产生；不允许调用方传任意 Map。
- Access reason 正则固定为 `[\p{L}\p{N} .,_:;()/#@+\-]{1,200}`，不 trim 后接受；无效原文不得写 audit。
- 仅 `PLATFORM_ADMIN` browser Session 可读 trace body；所有 API key、`KNOWLEDGE_ADMIN`、`EMPLOYEE` 拒绝；每次成功或拒绝尝试必须 audit。
- Rabbit business JSON 不变；context 只放 headers，并随 outbox row 持久化以支持未来 replay。
- 固定 ingestion failure stages：`message_decode,status_check,storage_read,parse,chunk,artifact_write,state_update,audit,ack,unknown`。
- Rabbit transport observation 与 `veridex.ingestion.run` 业务 observation 可并存，但不得使用同名 timer/span。
- Telemetry exporter、meter 或 capture 失败不得改变业务结果；只有启用 capture 的非法安全配置 fail-fast。
- 普通测试用 in-memory meter registry/span exporter，不依赖 Tempo。
- 所有 `RestTestClient` 请求必须消费响应体。
- 每个 Task 先 RED 后 GREEN，独立提交；提交尾注固定为 `Co-Authored-By: CodeTui <noreply@codetui.dev>`。
- 最终必须通过 `./scripts/verify.sh`、Compose/config/rule/dashboard 静态校验和本地观测栈 acceptance。

---

## File Structure

**Shared observability:**
- Create `backend/src/main/java/io/veridex/shared/observability/package-info.java`
- Create `backend/src/main/java/io/veridex/shared/observability/ObservationName.java`
- Create `backend/src/main/java/io/veridex/shared/observability/MetricName.java`
- Create `backend/src/main/java/io/veridex/shared/observability/TelemetryTag.java`
- Create `backend/src/main/java/io/veridex/shared/observability/TelemetryOutcome.java`
- Create `backend/src/main/java/io/veridex/shared/observability/TelemetryErrorCode.java`
- Create `backend/src/main/java/io/veridex/shared/observability/BoundedModelTags.java`
- Create `backend/src/main/java/io/veridex/shared/observability/VeridexObservability.java`
- Create `backend/src/main/java/io/veridex/shared/observability/RabbitPropagationContext.java`
- Create `backend/src/main/java/io/veridex/shared/observability/RabbitContextPropagation.java`

**Trace body:**
- Create `backend/src/main/resources/db/migration/V13__phase5b_observability.sql` (the only V13)
- Create trace domain/application/api/infrastructure files named in Tasks 4-8
- Modify `QueryRun`, `QueryRunRecorder`, generation result, QA orchestration, SecurityConfig and module descriptors

**Async/index paths:**
- Modify outbox entity/writer/publisher/repository
- Modify Rabbit topology and ingestion worker
- Modify document processing state/query
- Modify indexing publish service and OpenSearch gateway

**Observability stack:**
- Create `deploy/compose/observability/**`
- Modify `deploy/compose/compose.yml`, `.env.example`, `README.md`, `docs/architecture.md`

---

### Task 1: Prometheus、OTel 依赖与安全默认配置

**Files:**
- Modify `backend/pom.xml`
- Modify `backend/src/main/resources/application.yml`
- Create `backend/src/test/java/io/veridex/ObservabilityEndpointIntegrationTest.java`
- Modify `backend/src/test/java/io/veridex/ConfigurationSafetyTest.java`
- Modify `backend/src/test/java/io/veridex/shared/RateLimitIntegrationTest.java`

**Interfaces:**
- Produces: `MeterRegistry`, `ObservationRegistry` and Micrometer Tracer beans; `/actuator/prometheus`; properties `management.tracing.sampling.probability`, `management.opentelemetry.tracing.export.otlp.endpoint`, `veridex.observability.environment`.
- Consumed by: Tasks 2-13.

- [ ] **Step 1: Add failing endpoint and defaults tests**

`ObservabilityEndpointIntegrationTest` follows `VeridexApplicationTest` container annotations and consumes the body:

```java
@AutoConfigureRestTestClient
class ObservabilityEndpointIntegrationTest extends PostgresIntegrationTest {
    @Autowired RestTestClient rest;

    @Test void prometheusEndpointContainsFrameworkMetrics() {
        String body = rest.get().uri("/actuator/prometheus").exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith("text/plain")
                .expectBody(String.class).returnResult().getResponseBody();
        assertThat(body).contains("jvm_").contains("process_uptime");
        assertThat(body).doesNotContain("prompt").doesNotContain("completion");
    }
}
```

Extend `ConfigurationSafetyTest` to assert YAML/property binding defaults:

```java
assertThat(environment.getProperty("management.tracing.sampling.probability"))
        .isEqualTo("0.1");
assertThat(environment.getProperty("veridex.trace.body.capture-policy"))
        .isEqualTo("NONE");
assertThat(environment.getProperty("spring.ai.chat.client.observations.log-prompt"))
        .isEqualTo("false");
```

- [ ] **Step 2: Run RED**

Run: `./mvnw -pl backend test "-Dtest=ObservabilityEndpointIntegrationTest,ConfigurationSafetyTest"`
Expected: endpoint 404 or no Prometheus registry.

- [ ] **Step 3: Add dependencies and configuration**

Add BOM-managed dependencies:

```xml
<dependency><groupId>io.micrometer</groupId><artifactId>micrometer-registry-prometheus</artifactId></dependency>
<dependency><groupId>io.micrometer</groupId><artifactId>micrometer-tracing-bridge-otel</artifactId></dependency>
<dependency><groupId>io.opentelemetry</groupId><artifactId>opentelemetry-exporter-otlp</artifactId></dependency>
```

Add configuration; verify exact Boot 4 binding with `./mvnw -pl backend spring-boot:run --debug` if metadata rejects a key:

```yaml
management:
  tracing:
    sampling:
      probability: ${VERIDEX_TRACING_SAMPLING_PROBABILITY:0.1}
  opentelemetry:
    tracing:
      export:
        otlp:
          endpoint: ${VERIDEX_OTLP_ENDPOINT:http://localhost:4318/v1/traces}
  metrics:
    tags:
      application: ${spring.application.name}
      environment: ${VERIDEX_ENVIRONMENT:local}
    distribution:
      percentiles-histogram:
        http.server.requests: true
        veridex.qa.run: true
        veridex.generation.model: true
veridex:
  observability:
    environment: ${VERIDEX_ENVIRONMENT:local}
  trace:
    body:
      capture-policy: ${VERIDEX_TRACE_BODY_CAPTURE_POLICY:NONE}
      retention: ${VERIDEX_TRACE_BODY_RETENTION:24h}
      max-plaintext-size: ${VERIDEX_TRACE_BODY_MAX_PLAINTEXT_SIZE:256KiB}
      cleanup-interval: ${VERIDEX_TRACE_BODY_CLEANUP_INTERVAL:1h}
      cleanup-batch-size: ${VERIDEX_TRACE_BODY_CLEANUP_BATCH_SIZE:500}
```

Do not add a key value or fallback secret.

- [ ] **Step 4: Run GREEN and regression**

Run:

```bash
./mvnw -pl backend test "-Dtest=ObservabilityEndpointIntegrationTest,ConfigurationSafetyTest,RateLimitIntegrationTest"
./mvnw -pl backend dependency:tree -Dincludes=io.micrometer,io.opentelemetry
```

Expected: endpoint 200, defaults pass, actuator remains excluded from rate limiting, dependency tree has one managed version per artifact.

- [ ] **Step 5: Commit**

```bash
git add backend/pom.xml backend/src/main/resources/application.yml backend/src/test/java/io/veridex/ObservabilityEndpointIntegrationTest.java backend/src/test/java/io/veridex/ConfigurationSafetyTest.java backend/src/test/java/io/veridex/shared/RateLimitIntegrationTest.java
git commit -m "feat: enable Prometheus and OpenTelemetry export" -m "Co-Authored-By: CodeTui <noreply@codetui.dev>"
```

---

### Task 2: `shared::observability` 类型安全原语

**Files:**
- Create all `shared/observability` files listed above except Rabbit propagation files
- Test `backend/src/test/java/io/veridex/shared/observability/ObservabilityPrimitivesTest.java`
- Test `backend/src/test/java/io/veridex/shared/observability/VeridexObservabilityTest.java`

**Interfaces:**
- Produces:
  - `ObservationName` values `QA_RUN`, `RETRIEVAL_RUN`, `GENERATION_MODEL`, `OUTBOX_PUBLISH`, `INGESTION_RUN`, `INDEXING_PUBLISH`.
  - `TelemetryTag` static factories only; no public arbitrary `(String,String)` constructor.
  - `VeridexObservability.start(ObservationName, TelemetryTag...) -> ObservationScope`.
  - `VeridexObservability.increment(MetricName, TelemetryTag...)` and `record(MetricName,double,TelemetryTag...)`.
- `ObservationScope` implements `AutoCloseable`: `success(TelemetryTag...)`, `failure(TelemetryErrorCode)`, `close()`.

- [ ] **Step 1: Write failing primitive tests**

```java
@Test void exposesOnlyFixedNamesAndNormalizedValues() {
    assertThat(ObservationName.QA_RUN.value()).isEqualTo("veridex.qa.run");
    assertThat(TelemetryTag.qaOutcome(TelemetryOutcome.Qa.COMPLETED).value()).isEqualTo("completed");
    assertThat(TelemetryTag.ingestionStage(TelemetryOutcome.IngestionStage.MESSAGE_DECODE).value())
            .isEqualTo("message_decode");
}

@Test void modelNamesAreBounded() {
    BoundedModelTags tags = new BoundedModelTags(Set.of("deterministic"));
    assertThat(tags.resolve("deterministic").model()).isEqualTo("deterministic");
    assertThat(tags.resolve("tenant-secret-model").model()).isEqualTo("unknown");
}
```

Use reflection to assert `TelemetryTag` has no public arbitrary constructor/factory.

- [ ] **Step 2: Run RED**

Run: `./mvnw -pl backend test -Dtest=ObservabilityPrimitivesTest`
Expected: package/classes missing.

- [ ] **Step 3: Implement fixed types**

Core API:

```java
@NamedInterface(name = "observability")
package io.veridex.shared.observability;

public final class TelemetryTag {
    private final String key;
    private final String value;
    private TelemetryTag(String key, String value) { this.key = key; this.value = value; }
    public String key() { return key; }
    public String value() { return value; }
    public static TelemetryTag qaOutcome(TelemetryOutcome.Qa v) { return fixed("outcome", v); }
    public static TelemetryTag conversation(TelemetryOutcome.Conversation v) { return fixed("conversation", v); }
    public static TelemetryTag retrievalOutcome(TelemetryOutcome.Retrieval v) { return fixed("outcome", v); }
    static TelemetryTag model(String v) { return new TelemetryTag("model", v); }
    public static TelemetryTag ingestionStage(TelemetryOutcome.IngestionStage v) { return fixed("failure_stage", v); }
    private static TelemetryTag fixed(String key, Enum<?> v) {
        return new TelemetryTag(key, v.name().toLowerCase(Locale.ROOT));
    }
}
```

`BoundedModelTags` 位于同包，只有它能调用 package-private `TelemetryTag.model(...)`。业务调用方无法构造任意 key/value。

`VeridexObservability` must fail open:

```java
public ObservationScope start(ObservationName name, TelemetryTag... tags) {
    try { return ObservationScope.started(name, registry, tags); }
    catch (RuntimeException e) { return ObservationScope.noop(); }
}
public void increment(MetricName name, TelemetryTag... tags) {
    try { Counter.builder(name.value()).tags(toTags(tags)).register(meters).increment(); }
    catch (RuntimeException ignored) { }
}
```

No exception message/class is attached.

- [ ] **Step 4: Test actual meter/observation behavior**

Use `SimpleMeterRegistry`, `ObservationRegistry`, and `DefaultMeterObservationHandler`; assert success/error timers, fixed tags and fail-open with a throwing registry. Run:

`./mvnw -pl backend test "-Dtest=ObservabilityPrimitivesTest,VeridexObservabilityTest,ArchitectureTest"`

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/io/veridex/shared/observability backend/src/test/java/io/veridex/shared/observability
git commit -m "feat: add bounded observability primitives" -m "Co-Authored-By: CodeTui <noreply@codetui.dev>"
```

---

### Task 3: QA、retrieval 与 generation 业务观测

**Files:**
- Modify `qa/package-info.java`, `retrieval/package-info.java`, `generation/package-info.java`
- Modify `QuestionAnsweringServiceImpl.java`, `HybridSearchServiceImpl.java`, `GenerationServiceImpl.java`
- Modify corresponding tests

**Interfaces:**
- Consumes Task 2 `VeridexObservability` and bounded types.
- Preserves public service signatures; constructor injection gains observability dependencies.
- Produces meters/spans `veridex.qa.run`, `veridex.retrieval.run`, `veridex.generation.model`.

- [ ] **Step 1: Add RED tests using real in-memory registry**

For each service manually construct `VeridexObservability` and assert:

```java
assertThat(meters.find("veridex.qa.run").tag("outcome", "completed").timer().count()).isEqualTo(1);
assertThat(meters.find("veridex.retrieval.run").tag("outcome", "degraded").timer().count()).isEqualTo(1);
assertThat(meters.find("veridex.generation.model").tag("outcome", "error").timer().count()).isEqualTo(1);
```

Also iterate all meter tags and assert they do not contain question text, IDs, exception messages, titles or chunk text. Refusal test asserts no model timer.

- [ ] **Step 2: Run RED**

Run: `./mvnw -pl backend test "-Dtest=QuestionAnsweringServiceTest,HybridSearchServiceTest,GenerationServiceImplTest"`
Expected: missing meters/constructor args.

- [ ] **Step 3: Instrument stable boundaries**

Use try-with-resources:

```java
try (var span = observability.start(ObservationName.QA_RUN,
        TelemetryTag.conversation(request.conversationId() == null ? NEW : EXISTING))) {
    List<QaEvent> result = execute(...);
    span.success(TelemetryTag.qaOutcome(classify(result)));
    return result;
} catch (Exception e) {
    span.failure(TelemetryErrorCode.classify(e));
    ...
}
```

Retrieval marks degraded if any fixed channel failure occurred. Generation starts observation only immediately around `model.call(new Prompt(messages))`; provider is `deterministic`, unapproved model becomes `unknown`. Distributions record numeric values, never tags.

Replace telemetry/log use of raw degradation/errors with fixed `TelemetryErrorCode`; do not change user-facing SSE behavior in this task except that `recorder.fail` conversion occurs in Task 6.

- [ ] **Step 4: Run GREEN + architecture**

```bash
./mvnw -pl backend test "-Dtest=QuestionAnsweringServiceTest,HybridSearchServiceTest,GenerationServiceImplTest,ArchitectureTest"
```

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/io/veridex/{qa,retrieval,generation} backend/src/test/java/io/veridex/{qa,retrieval,generation}
git commit -m "feat: instrument online RAG stages" -m "Co-Authored-By: CodeTui <noreply@codetui.dev>"
```

---

### Task 4: 单一 V13 可观测性迁移

**Files:**
- Create `backend/src/main/resources/db/migration/V13__phase5b_observability.sql`
- Modify `QueryRun.java`, `OutboxEventEntity.java`, `DocumentVersion.java`
- Modify `DatabaseMigrationTest.java`

**Interfaces:**
- Produces table `trace_body`, `query_run.question_fingerprint`, outbox columns `traceparent/tracestate/request_id`, and `document_version.processing_started_at`.
- No second V13 file is permitted.

- [ ] **Step 1: Add failing migration assertions**

Assert version 13, `trace_body` columns/FK cascade/index, fingerprint `varchar(64)`, outbox context columns, and processing timestamp. Insert a pre-V13-style query row in a migration fixture or verify migrated seeded row is scrubbed.

- [ ] **Step 2: Run RED**

Run: `./mvnw -pl backend test -Dtest=DatabaseMigrationTest`
Expected: version/table/columns missing.

- [ ] **Step 3: Write the only V13 SQL**

```sql
ALTER TABLE query_run ADD COLUMN question_fingerprint VARCHAR(64);
UPDATE query_run SET question='[REDACTED]', normalized_question='[REDACTED]', error=NULL;
ALTER TABLE outbox_event ADD COLUMN traceparent VARCHAR(100);
ALTER TABLE outbox_event ADD COLUMN tracestate VARCHAR(512);
ALTER TABLE outbox_event ADD COLUMN request_id VARCHAR(100);
ALTER TABLE document_version ADD COLUMN processing_started_at TIMESTAMPTZ;
CREATE TABLE trace_body (
  query_run_id UUID PRIMARY KEY REFERENCES query_run(id) ON DELETE CASCADE,
  capture_policy VARCHAR(20) NOT NULL,
  encrypted_body BYTEA NOT NULL,
  encryption_key_id VARCHAR(100) NOT NULL,
  nonce BYTEA NOT NULL,
  schema_version SMALLINT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_trace_body_expires_at ON trace_body(expires_at);
```

Map entity fields exactly. `DocumentVersion.markProcessing()` sets `processingStartedAt=Instant.now()`; later Task 10 replaces the clock with injected time only if deterministic testing requires it.

- [ ] **Step 4: Run GREEN and JPA validation**

`./mvnw -pl backend test "-Dtest=DatabaseMigrationTest,VeridexApplicationTest"`

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/resources/db/migration/V13__phase5b_observability.sql backend/src/main/java/io/veridex/{trace/domain/QueryRun.java,shared/outbox/OutboxEventEntity.java,knowledge/domain/DocumentVersion.java} backend/src/test/java/io/veridex/support/DatabaseMigrationTest.java
git commit -m "feat: add phase5b observability schema" -m "Co-Authored-By: CodeTui <noreply@codetui.dev>"
```

---

### Task 5: Trace-body properties、key ring 与 AES-GCM

**Files:**
- Create `trace/infrastructure/TraceBodyProperties.java`
- Create `TraceBodyConfiguration.java`, `TraceBodyKeyRing.java`, `TraceBodyCrypto.java`
- Modify `backend/src/main/resources/application.yml`
- Test `trace/TraceBodyPropertiesTest.java`, `trace/TraceBodyCryptoTest.java`

**Interfaces:**
- `CapturePolicy { NONE, ERRORS, ALL }`.
- `TraceBodyCrypto.encrypt(UUID,short,byte[]) -> EncryptedPayload(String keyId,byte[] nonce,byte[] ciphertext)`.
- `decrypt(UUID,short,String,byte[],byte[]) -> byte[]`.
- `fingerprint(String) -> Optional<String>`.

- [ ] **Step 1: Write RED configuration and crypto tests**

Cover NONE without keys; enabled policy missing/malformed/31-byte/duplicate/current-not-in-ring failures; 32-byte Base64 success; historical-key decrypt; unique 12-byte nonce; wrong AAD/tampering failure; fingerprint is 64 hex and absent without key.

- [ ] **Step 2: Run RED**

`./mvnw -pl backend test "-Dtest=TraceBodyPropertiesTest,TraceBodyCryptoTest"`

- [ ] **Step 3: Implement properties and validation**

```java
@ConfigurationProperties("veridex.trace.body")
public record TraceBodyProperties(CapturePolicy capturePolicy, Duration retention,
 DataSize maxPlaintextSize, Duration cleanupInterval, int cleanupBatchSize,
 String fingerprintKey, String currentKeyId, String currentKey,
 List<String> historicalKeys) { }
```

配置键和环境变量固定如下：

```yaml
veridex.trace.body:
  fingerprint-key: ${VERIDEX_TRACE_BODY_FINGERPRINT_KEY:}
  current-key-id: ${VERIDEX_TRACE_BODY_CURRENT_KEY_ID:}
  current-key: ${VERIDEX_TRACE_BODY_CURRENT_KEY:}
  historical-keys: ${VERIDEX_TRACE_BODY_HISTORICAL_KEYS:}
```

`current-key` 是当前 key 的 Base64 32-byte 值；`historical-keys` 是逗号分隔的 `keyId=base64Key` 列表，例如 `k2025=<base64>,k2024=<base64>`，空串绑定为空列表。解析器拒绝缺少 `=`、重复 ID、重复 current ID、非法 ID、非法 Base64 和非 32-byte key。测试必须同时覆盖 canonical Spring properties 与四个环境变量映射。

Crypto:

```java
Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
cipher.updateAAD(aad(runId, schema, keyId));
byte[] ciphertext = cipher.doFinal(plaintext);
```

HMAC uses `HmacSHA256`; never plain SHA-256.

- [ ] **Step 4: Run GREEN**

`./mvnw -pl backend test "-Dtest=TraceBodyPropertiesTest,TraceBodyCryptoTest,ConfigurationSafetyTest"`

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/io/veridex/trace/infrastructure backend/src/test/java/io/veridex/trace/TraceBody{Properties,Crypto}Test.java
git commit -m "feat: add trace body encryption key ring" -m "Co-Authored-By: CodeTui <noreply@codetui.dev>"
```

---

### Task 6: QueryRun scrub 与终态正文捕获

**Files:**
- Create `trace/domain/TraceBody.java`, `TraceBodyRepository.java`
- Create `trace/api/TraceBodyCapture.java`
- Create `trace/application/TraceBodyCaptureService.java`, `TraceBodyWriter.java`
- Modify `QueryRunRecorder.java`, `QueryRunRecorderImpl.java`, `QueryRun.java`
- Create `generation/api/PromptMessageView.java`; modify `GenerationResult.java`, `GenerationServiceImpl.java`
- Modify `QuestionAnsweringServiceImpl.java`
- Test capture, recorder, generation and QA tests

**Interfaces:**
- `QueryRunRecorder.start(UUID userId, UUID conversationId, List<UUID> knowledgeScope, String normalizedQuestion)`.
- `QueryRunRecorder.fail(UUID runId, String errorCode)` accepts fixed code only.
- `TraceBodyCapture.capture(UUID runId, TerminalOutcome outcome, String errorCode, TraceBodyMaterial material)`.
- `GenerationResult.promptMessages()` returns actual sent prompt; refusal returns empty list.

- [ ] **Step 1: Write RED tests**

Cover raw query fields are `[REDACTED]`, error is fixed code, HMAC optional; NONE does not serialize/write; ERRORS captures refused/failed/cancelled only; ALL captures completed; exact `256KiB` accepted and `256KiB+1` skipped; writer failure is fail-open; prompt list equals actual model Prompt and refusal is empty.

- [ ] **Step 2: Run RED**

```bash
./mvnw -pl backend test "-Dtest=QueryRunRecorderImplTest,TraceBodyCaptureServiceTest,GenerationServiceImplTest,QuestionAnsweringServiceTest"
```

- [ ] **Step 3: Implement envelope/capture**

```java
public interface TraceBodyCapture {
 enum TerminalOutcome { COMPLETED, REFUSED, FAILED, CANCELLED }
 record PromptMessage(String role,String content) {}
 record EvidenceSnapshot(int citationIndex, UUID documentVersionId, int chunkIndex,
   String title,String structurePath,String text) {}
 record CitationSnapshot(int citationIndex,UUID documentVersionId,int chunkIndex,
   String sourceLocation,String citationText,String validationStatus) {}
 record TraceBodyMaterial(String question,List<PromptMessage> promptMessages,String answer,
   List<EvidenceSnapshot> evidence,List<CitationSnapshot> citations) {}
 void capture(UUID runId, TerminalOutcome outcome, String errorCode, TraceBodyMaterial material);
}
```

`TraceBodyCaptureService` checks policy before serialization; uses UTF-8 byte length; saves with `expiresAt=clock.instant().plus(retention)` via `@Transactional(REQUIRES_NEW)` writer. Catch capture exceptions, record fixed skip reason, log only fixed code.

Update QA terminal branches to supply actual search evidence, actual generation prompt messages and citations. Refused prompt list is empty if model was not called. On exception call `TelemetryErrorCode.classify(e).wireValue()` for QueryRun, but preserve existing user-facing generic behavior until tests define it.

- [ ] **Step 4: Run GREEN + no-plaintext integration assertion**

```bash
./mvnw -pl backend test "-Dtest=QueryRunRecorderImplTest,TraceBodyCaptureServiceTest,GenerationServiceImplTest,QuestionAnsweringServiceTest,QaApiIntegrationTest"
```

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/io/veridex/{trace,generation,qa} backend/src/test/java/io/veridex/{trace,generation,qa}
git commit -m "feat: capture encrypted terminal trace bodies" -m "Co-Authored-By: CodeTui <noreply@codetui.dev>"
```

---

### Task 7: Platform-admin trace-body read API 与拒绝审计

**Files:**
- Create `trace/api/TraceBodyController.java`, `TraceBodyResponse.java`, `TraceBodyExceptionHandler.java`
- Create `trace/application/TraceBodyReadService.java`, `TraceBodyAccessAuditor.java`
- Create `trace/infrastructure/TraceBodyDeniedAccessFilter.java`
- Modify `trace/package-info.java`, `SecurityConfig.java`, `ApiKeyScopeAuthorizationManager.java`
- Test `TraceBodyReadIntegrationTest.java`

**Interfaces:**
- `GET /api/traces/{runId}/body`, required `X-Trace-Access-Reason`, `Cache-Control: no-store`.
- `TraceBodyReadService.read(UUID runId, UUID actorId, String reason, String requestId)`.
- `ApiKeyScopeAuthorizationManager.authorizePlatformAdminSession(Supplier<? extends Authentication>, RequestAuthorizationContext)` remains in `iam` and returns an authorization result without importing `trace`.
- `TraceBodyDeniedAccessFilter` is registered by the trace infrastructure as an outer `FilterRegistrationBean`; `SecurityConfig` does not inject or import a trace class, preventing an `iam -> trace` dependency.

- [ ] **Step 1: Write RED HTTP matrix**

Tests: platform admin 200/decrypted/no-store; missing/invalid reason 400; expired/missing 404; tampered/missing key generic 500; knowledge admin/employee/API key 403; anonymous current entry point. Query audit table after each case for fixed actions and assert no plaintext/details leakage.

- [ ] **Step 2: Run RED**

`./mvnw -pl backend test -Dtest=TraceBodyReadIntegrationTest`

- [ ] **Step 3: Implement read and authorization**

Reason validation uses exact regex with `matcher.matches()` and no trim. Read flow: authorize at Security, validate reason, load, delete if expired, decrypt, deserialize, audit, return no-store.

Security configuration explicitly matches `GET /api/traces/**` before `.anyRequest()`. Add a narrow once-per-request filter after authorization exception translation to audit 401/403 that never reach controller. The filter must not duplicate service-level audit and must never record raw reason if invalid.

Exception advice is restricted with `assignableTypes=TraceBodyController.class`.

- [ ] **Step 4: Run GREEN + security regression**

```bash
./mvnw -pl backend test "-Dtest=TraceBodyReadIntegrationTest,ApiKeyBearerIntegrationTest,AuthFlowIntegrationTest,ArchitectureTest"
```

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/io/veridex/{trace,iam/infrastructure} backend/src/test/java/io/veridex/trace/TraceBodyReadIntegrationTest.java
git commit -m "feat: add audited trace body read api" -m "Co-Authored-By: CodeTui <noreply@codetui.dev>"
```

---

### Task 8: Trace-body retention cleanup 与 telemetry

**Files:**
- Create `trace/infrastructure/JdbcTraceBodyMaintenanceRepository.java`
- Create `trace/application/TraceBodyCleanupJob.java`
- Modify `TraceBodyCaptureService.java`, `TraceBodyReadService.java`
- Test `TraceBodyCleanupIntegrationTest.java`

**Interfaces:**
- `int deleteExpired(Instant now,int limit)`.
- `int scrubQueryRuns(int limit)`.
- scheduled `cleanup()` uses configured delay/batch.
- Meters: capture/s skipped/read/denied/cleanup with fixed reasons only.

- [ ] **Step 1: Write RED cleanup tests**

Insert >batch expired rows plus unexpired row; run repeatedly; assert bounded deletion, idempotence, no unexpired deletion. Insert accidental plaintext query fields and assert scrub. Simulate concurrent claim with two transactions if test support permits.

- [ ] **Step 2: Run RED**

`./mvnw -pl backend test -Dtest=TraceBodyCleanupIntegrationTest`

- [ ] **Step 3: Implement PostgreSQL bounded maintenance**

Use CTE:

```sql
WITH doomed AS (
 SELECT query_run_id FROM trace_body WHERE expires_at < ?
 ORDER BY expires_at LIMIT ? FOR UPDATE SKIP LOCKED
)
DELETE FROM trace_body b USING doomed d WHERE b.query_run_id=d.query_run_id
```

Scrub only rows where values differ from placeholders or error is not a bounded code; never rewrite all rows each hour. Scheduler loops at most a configured safe number of batches per invocation to avoid monopolizing DB.

- [ ] **Step 4: Run GREEN**

`./mvnw -pl backend test "-Dtest=TraceBodyCleanupIntegrationTest,TraceBodyCaptureServiceTest,TraceBodyReadIntegrationTest"`

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/io/veridex/trace backend/src/test/java/io/veridex/trace
git commit -m "feat: expire trace bodies and scrub query runs" -m "Co-Authored-By: CodeTui <noreply@codetui.dev>"
```

---

### Task 9: Rabbit context persistence、Outbox metrics 与 transport observations

**Files:**
- Create Rabbit propagation files in `shared::observability`
- Modify `OutboxEventEntity.java`, `OutboxWriter.java`, `OutboxEventRepository.java`, `OutboxPublisher.java`, `RabbitTopology.java`
- Create `OutboxObservability.java`
- Test propagation/outbox/topology/integration tests

**Interfaces:**
- `RabbitPropagationContext(String traceparent,String tracestate,String requestId)`.
- `captureCurrent()`, `writeHeaders(context,eventId,MessageProperties)`, `extract(MessageProperties)`.
- Outbox `record(...)` public signature unchanged; context persisted with row.
- Gauges `veridex.outbox.pending`, `veridex.outbox.oldest.unpublished.age`.

- [ ] **Step 1: Write RED header and outbox tests**

Verify valid W3C/request ID roundtrip, malformed/overlong/unknown schema ignored, business body byte-for-byte unchanged, persisted context survives clearing current span before publish, one failed event does not stop later events, failures use fixed telemetry code.

- [ ] **Step 2: Run RED**

```bash
./mvnw -pl backend test "-Dtest=RabbitContextPropagationTest,OutboxPublisherTest,OutboxPublishingIntegrationTest,RabbitTopologyTest"
```

- [ ] **Step 3: Implement propagation and outbox observability**

Headers: `traceparent`, `tracestate`, `x-request-id`, `x-outbox-event-id`, `x-veridex-telemetry-version=1`. Validate lengths and characters. Capture context in `OutboxWriter` before transaction commits; publisher uses stored values and `MessagePostProcessor`.

Remove unused `JsonMapper` from publisher. Replace UUID/raw-message logs with fixed redacted messages. Enable Spring AMQP template/listener observations via actual Boot 4 RabbitTemplate/container properties/customizers discovered from compiled API; keep transport names distinct from business observations.

- [ ] **Step 4: Run GREEN**

Same command as Step 2 plus `ArchitectureTest`.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/io/veridex/shared backend/src/test/java/io/veridex/shared
git commit -m "feat: propagate traces through the outbox" -m "Co-Authored-By: CodeTui <noreply@codetui.dev>"
```

---

### Task 10: Ingestion observations、request ID 与 stuck gauge

**Files:**
- Create `ingestion/infrastructure/IngestionObservability.java`
- Modify `DocumentIngestionWorker.java`, `ingestion/package-info.java`
- Modify `DocumentVersionProcessing.java`, repository/service implementations
- Modify ingestion tests

**Interfaces:**
- Worker accepts `org.springframework.amqp.core.Message` plus channel/delivery tag.
- `countProcessingStartedBefore(Instant cutoff) -> long`.
- root tags use fixed result/stage only.

- [ ] **Step 1: Write RED stage/result tests**

Cover success, READY duplicate, each fixed failure stage, malformed headers creating root, MDC cleanup, propagated request ID in audit, ack/reject counters, no UUID/message/text tags, stuck threshold exact boundary.

- [ ] **Step 2: Run RED**

`./mvnw -pl backend test "-Dtest=DocumentIngestionWorkerTest,IngestionWorkerIntegrationTest,IngestionExitGateTest"`

- [ ] **Step 3: Implement worker observation**

Track stage in a local enum variable before each operation. Extract remote parent; run delivery inside propagation scope; set MDC request ID and clear in finally. On failure persist fixed error code to `markFailed`, log fixed stage only, reject. Audit success with propagated request ID.

`processing_started_at` is set entering PROCESSING. Repository count query uses status and timestamp without loading rows. Register gauge through a weak/safe supplier that fails open.

- [ ] **Step 4: Run GREEN + architecture**

Same command plus `ArchitectureTest` and `OutboxPublishingIntegrationTest`.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/io/veridex/{ingestion,knowledge} backend/src/test/java/io/veridex/{ingestion,knowledge}
git commit -m "feat: instrument asynchronous ingestion" -m "Co-Authored-By: CodeTui <noreply@codetui.dev>"
```

---

### Task 11: Indexing release observations

**Files:**
- Create `indexing/application/IndexingObservability.java`
- Modify `KnowledgeBasePublishService.java`, `OpenSearchIndexGateway.java`, relevant release service, `indexing/package-info.java`
- Modify/create indexing tests

**Interfaces:**
- Root `veridex.indexing.publish` outcome success/error.
- Nested fixed operations `embed_batch`, `bulk`, `refresh`, `alias_switch`.
- No index/alias/KB/release/document identifier in telemetry.

- [ ] **Step 1: Write RED tests**

Test root success/error, chunks distribution, prepare/index/publish failure, cleanup failure preserves primary exception, embedding batch/bulk/refresh/alias counters, and inspect all tags/span attributes for forbidden content.

- [ ] **Step 2: Run RED**

```bash
./mvnw -pl backend test "-Dtest=KnowledgeBasePublishServiceTest,OpenSearchIndexGatewayBatchTest,IndexReleaseServiceTest"
```

- [ ] **Step 3: Instrument external boundaries**

Inject `IndexingObservability`; wrap service publish root. Wrap actual model embedding, OpenSearch bulk, refresh and alias calls in nested observations. Record counts as values. Replace raw objectKey/release ID/error logs with fixed operation/error code; preserve thrown business exception.

- [ ] **Step 4: Run GREEN**

Same command plus `ArchitectureTest` and existing indexing integration tests.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/io/veridex/indexing backend/src/test/java/io/veridex/indexing
git commit -m "feat: instrument index release publishing" -m "Co-Authored-By: CodeTui <noreply@codetui.dev>"
```

---

### Task 12: Prometheus、Grafana、Collector、Tempo 与 alerts

**Files:**
- Modify `deploy/compose/compose.yml`, `deploy/compose/.env.example`
- Create all `deploy/compose/observability/**` files from File Structure
- Create `scripts/verify-observability.sh`

**Interfaces:**
- Pinned images: `prom/prometheus:v3.13.2`, `grafana/grafana:13.1.3`, `otel/opentelemetry-collector-contrib:0.158.0`, `grafana/tempo:3.0.3`, existing RabbitMQ 4 management image.
- Ports: Grafana 3000, Prometheus 9090, Tempo query 3200, Collector 4317/4318, Rabbit metrics 15692.

- [ ] **Step 1: Add failing static verifier**

`scripts/verify-observability.sh` must run:

```bash
docker compose --env-file deploy/compose/.env.example -f deploy/compose/compose.yml config --quiet
docker run --rm --entrypoint /bin/promtool -v "$PWD/deploy/compose/observability/prometheus:/etc/prometheus:ro" prom/prometheus:v3.13.2 check config /etc/prometheus/prometheus.yml
docker run --rm --entrypoint /bin/promtool -v "$PWD/deploy/compose/observability/prometheus/rules:/rules:ro" prom/prometheus:v3.13.2 check rules /rules/veridex-alerts.yml
python3 -m json.tool deploy/compose/observability/grafana/dashboards/veridex-overview.json >/dev/null
```

Validate Collector with its supported `validate --config=/etc/otelcol-contrib/config.yaml` command. Tempo 3.0.3 does not document a standalone config-check flag; validate it by starting only the Tempo Compose service, waiting for `http://localhost:3200/ready`, then stopping it. A malformed configuration must fail startup and the script must print `docker compose logs tempo` before returning non-zero.

- [ ] **Step 2: Run RED**

Run: `./scripts/verify-observability.sh`
Expected: missing services/config files.

- [ ] **Step 3: Add Compose services and configs**

Prometheus jobs:

```yaml
scrape_configs:
  - job_name: veridex
    metrics_path: /actuator/prometheus
    static_configs: [{ targets: ['host.docker.internal:8080'] }]
  - job_name: rabbitmq
    static_configs: [{ targets: ['rabbitmq:15692'] }]
rule_files: ['/etc/prometheus/rules/*.yml']
```

Collector pipeline:

```yaml
receivers:
  otlp:
    protocols: { grpc: { endpoint: 0.0.0.0:4317 }, http: { endpoint: 0.0.0.0:4318 } }
processors:
  memory_limiter: { check_interval: 1s, limit_mib: 256 }
  batch: {}
exporters:
  otlp/tempo: { endpoint: tempo:4317, tls: { insecure: true } }
service:
  pipelines:
    traces: { receivers: [otlp], processors: [memory_limiter, batch], exporters: [otlp/tempo] }
```

Provision Prometheus and Tempo UIDs `prometheus` and `tempo`. Dashboard panels cover every design section and use actual Prometheus names observed after Tasks 3/9/10/11, not guessed names.

Alert rules include all required families with pilot thresholds and `for` durations. Queue matchers are fixed to `ingestion.document` and `ingestion.document.dlq`; no dynamic resource labels.

Rabbit command enables plugin offline:

```yaml
command: ["sh","-c","rabbitmq-plugins enable --offline rabbitmq_prometheus && rabbitmq-server"]
```

- [ ] **Step 4: Run GREEN**

`./scripts/verify-observability.sh`
Expected: all config/rule/JSON validators exit 0.

- [ ] **Step 5: Commit**

```bash
git add deploy/compose scripts/verify-observability.sh
git commit -m "feat: add local observability stack and alerts" -m "Co-Authored-By: CodeTui <noreply@codetui.dev>"
```

---

### Task 13: Documentation、integrated privacy tests 与 exit gate

**Files:**
- Create `backend/src/test/java/io/veridex/observability/ObservabilityPrivacyIntegrationTest.java`
- Modify `README.md`, `docs/architecture.md`, `docs/knowledge-ingestion-pipeline.md`
- Modify `scripts/verify.sh` to call `verify-observability.sh` only when Docker is available, or call syntax-only host checks unconditionally and document container validation separately.

**Interfaces:**
- Produces operator instructions, exact environment properties, local service URLs and acceptance procedure.
- Final gate consumes all prior tasks.

- [ ] **Step 1: Add integrated privacy and metric tests**

Run one QA and one ingestion operation using unique sentinel plaintexts such as `SENSITIVE_QUESTION_5B` and `SENSITIVE_CHUNK_5B`. Scrape Prometheus, inspect all meter tags and in-memory exported spans/log capture; assert sentinels, UUIDs, credentials and raw exceptions absent. Under capture `ALL` query DB: ciphertext does not contain sentinel; admin API decrypts it; after cleanup returns 404.

- [ ] **Step 2: Run focused backend gate**

```bash
./mvnw -pl backend test "-Dtest=ObservabilityEndpointIntegrationTest,ObservabilityPrivacyIntegrationTest,TraceBodyReadIntegrationTest,TraceBodyCleanupIntegrationTest,OutboxPublishingIntegrationTest,IngestionWorkerIntegrationTest"
```

Fix any failure before docs.

- [ ] **Step 3: Update operator docs**

Document:
- ports and pinned images;
- `VERIDEX_OTLP_ENDPOINT`, sampling, environment;
- capture policies and exact key-ring property/environment mapping with generated example key command but no committed key;
- `/api/traces/{runId}/body` curl using Session cookie and reason header;
- local startup, Prometheus targets, Grafana datasources, Tempo search, alert query;
- fail-open test by stopping Collector/Tempo;
- explicit retention exclusions for conversations/feedback/evaluation/backups/replicas;
- actuator exposure is local-only and Phase 5-c will harden it;
- known ingestion retry/DLQ recovery limitations.

- [ ] **Step 4: Run local acceptance**

```bash
./scripts/verify-observability.sh
docker compose --env-file deploy/compose/.env.example -f deploy/compose/compose.yml up -d
curl --fail http://localhost:9090/-/ready
curl --fail http://localhost:3000/api/health
curl --fail http://localhost:15692/metrics
curl --fail 'http://localhost:9090/api/v1/query?query=up%7Bjob%3D%22veridex%22%7D'
```

Start application on host, execute one QA and one ingestion request, confirm Tempo query returns traces and dashboard queries produce data. Stop Collector, repeat a business request, confirm it is not blocked. Record exact commands/results in the commit message body or implementation report, not a generated artifact file.

- [ ] **Step 5: Run final repository gate**

```bash
./scripts/verify.sh
./scripts/verify-observability.sh
git diff --check
git status --short
```

Expected: backend, frontend, build, config/rules/dashboard and diff checks all pass; status contains only intended files before commit.

- [ ] **Step 6: Commit**

```bash
git add README.md docs/architecture.md docs/knowledge-ingestion-pipeline.md backend/src/test/java/io/veridex/observability scripts/verify.sh
git commit -m "docs: complete phase5b observability exit gate" -m "Co-Authored-By: CodeTui <noreply@codetui.dev>"
```

---

## Execution Order and Review Gates

Execute Tasks 1-13 strictly in order. Task 4 creates the only V13 and must precede any entity that requires its columns. Task 5 establishes crypto before Task 6 capture. Task 6 establishes persisted body before Task 7 read. Task 9 establishes Rabbit context before Task 10 ingestion restoration. Task 12 must use actual meter names emitted by completed business instrumentation.

After every Task:

1. run the task-specific tests;
2. run `ArchitectureTest` whenever module imports change;
3. run `git diff --check`;
4. review for sensitive/high-cardinality telemetry;
5. commit only a green, independently reviewable task.

## Acceptance-Criteria Mapping

| Spec criterion | Task(s) |
|---|---|
| Prometheus endpoint/framework + Veridex metrics | 1, 2, 3, 9-11, 13 |
| Six bounded business observations | 2, 3, 9-11 |
| QA and ingestion traces through Collector/Tempo | 9, 10, 12, 13 |
| No content/forbidden IDs in telemetry | 2, 3, 6, 9-11, 13 |
| Rabbit context/request ID without payload change | 9, 10 |
| Dashboard/rules/alerts load | 12, 13 |
| All alert families present | 12 |
| Capture defaults NONE/no key | 1, 5, 6 |
| ERRORS/ALL AES-GCM/size/TTL | 5, 6, 8 |
| QueryRun raw fields scrubbed | 4, 6, 8 |
| Platform-admin reasoned/audited read | 7 |
| API key/non-admin denied | 7 |
| Telemetry backend failure is fail-open | 2, 6, 9-13 |
| Invalid enabled key config fails startup | 5 |
| All repository/deployment gates pass | 13 |

## Plan Self-Review Checklist

- [x] All 15 acceptance criteria map to concrete tasks.
- [x] Exactly one V13 migration is created.
- [x] All cross-task types and property names are defined before use.
- [x] No task requires Tempo for ordinary tests.
- [x] Every code-producing task has RED, implementation, GREEN and commit steps.
- [x] No plaintext, UUID, request ID, exception message, filename or dynamic model is used as a metric tag.
- [x] Placeholder scan completed with no unresolved implementation markers.
- [x] Compose images and validation commands are pinned.
