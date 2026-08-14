# Ollama Embedding Provider 接入 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 通过 `veridex.embedding.provider` 配置在 deterministic 与 Ollama（`qwen3-embedding`，1024 维）之间切换向量模型，让检索/入库具备真实语义能力。

**Architecture:** 保持 Spring AI `EmbeddingModel` 作为唯一端口；`DeterministicEmbeddingModel` 加条件注解、Ollama 用 core 模块手接 bean，二者互斥装配；维度收敛到 `EmbeddingProperties.dimensions`，启动期校验兜底；入库按 bulk 批次批量 embedding。

**Tech Stack:** Java 21、Spring Boot 4、Spring AI 2.0.0（`spring-ai-ollama` core）、OpenSearch、JUnit 5 + Mockito + Testcontainers。

## Global Constraints

- 版本：Spring AI 2.0.0（由 `spring-ai-bom` 管理）；只加 `spring-ai-ollama` core 模块，不加 starter/autoconfigure（其 2.0.0 未发正式版，且会带 chat autoconfig）。
- 端口唯一：全平台只有一个 `EmbeddingModel` bean（两个 provider 互斥，避免注入歧义）。
- 维度单一来源：`veridex.embedding.dimensions`；删除 `veridex.search.opensearch.dimensions`。
- 默认 provider=deterministic、dimensions=128：零配置下现有测试与行为不变。
- 切 Ollama 需显式 `VERIDEX_EMBEDDING_PROVIDER=ollama` 且 `VERIDEX_EMBEDDING_DIMENSIONS=1024`。
- qwen3-embedding 默认 1024 维（0.6B）；实现后可用 `ollama show qwen3-embedding` 复核。
- 换模型维度必须重建索引（发新 release 重导）；跨模型维度回切不受支持。
- 每任务 TDD：先写失败测试 → 跑过 → 实现 → 跑过 → 提交。
- 已核对（javap）：`Embedding(float[], Integer)`、`Embedding.getOutput():float[]`、`EmbeddingRequest(List<String>, EmbeddingOptions)`、`EmbeddingResponse(List<Embedding>)`、`EmbeddingResponse.getResults():List<Embedding>`、`EmbeddingOptions.builder().build()`、`OllamaApi.builder().baseUrl(String).build()`、`OllamaEmbeddingOptions.builder().model(String).build()`、`OllamaEmbeddingModel.builder().ollamaApi().options().build()`。

---

### Task 1: 维度配置收敛（单一来源）

**Files:**
- Modify: `backend/src/main/java/io/veridex/shared/infrastructure/config/EmbeddingProperties.java`
- Modify: `backend/src/main/java/io/veridex/shared/infrastructure/config/OpenSearchProperties.java`
- Modify: `backend/src/main/resources/application.yml`
- Modify: `backend/src/main/java/io/veridex/indexing/infrastructure/IndexReleaseService.java`
- Modify: `backend/src/test/java/io/veridex/ConfigurationPropertiesBindingTest.java`
- Modify: `backend/src/test/java/io/veridex/indexing/IndexReleaseServiceTest.java`

**Interfaces:**
- Produces: `EmbeddingProperties(String provider, int dimensions, Ollama ollama)` 与嵌套 `EmbeddingProperties.Ollama(String baseUrl, String model)`（Task 2 消费 `ollama()`；Task 4 消费 `dimensions()`）。
- Produces: `OpenSearchProperties(List<String> uris, String indexPrefix)`（删除 `dimensions`）。
- `IndexReleaseService` 新增构造参数 `EmbeddingProperties embeddingProperties`，`prepare()` 用它取维度。

- [ ] **Step 1: 改测试到目标形态（会编译失败）**

`ConfigurationPropertiesBindingTest.java` 两处：

```java
    @Test
    void openSearchPropertiesBindWithDefaults() {
        assertThat(openSearch.uris()).containsExactly("http://localhost:9200");
        assertThat(openSearch.indexPrefix()).isEqualTo("veridex");
    }

    @Test
    void embeddingPropertiesBindWithDefaults() {
        assertThat(embedding.provider()).isEqualTo("deterministic");
        assertThat(embedding.dimensions()).isEqualTo(128);
        assertThat(embedding.ollama().baseUrl()).isEqualTo("http://localhost:11434");
        assertThat(embedding.ollama().model()).isEqualTo("qwen3-embedding");
    }
```

`IndexReleaseServiceTest.java`：加字段与桩，并把维度桩从 OpenSearch 挪到 Embedding：

```java
import io.veridex.shared.infrastructure.config.EmbeddingProperties;
import io.veridex.shared.infrastructure.config.OpenSearchProperties;
...
    @Mock OpenSearchProperties properties;
    @Mock EmbeddingProperties embeddingProperties;
    @InjectMocks IndexReleaseService service;
...
    void prepareCreatesIndexAndPublishAliasesItAndMarksPublished() {
        when(properties.indexPrefix()).thenReturn("veridex");
        when(embeddingProperties.dimensions()).thenReturn(128);
        ...
        verify(gateway).createIndex(draft.indexName(), 128);
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./mvnw -pl backend test -Dtest=ConfigurationPropertiesBindingTest,IndexReleaseServiceTest`
Expected: 编译失败（`EmbeddingProperties` 无 `ollama()`）。

- [ ] **Step 3: 实现主代码**

`EmbeddingProperties.java` 整体替换为：

```java
package io.veridex.shared.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "veridex.embedding")
public record EmbeddingProperties(
        String provider,
        int dimensions,
        Ollama ollama) {

    public record Ollama(String baseUrl, String model) {
    }
}
```

`OpenSearchProperties.java` 整体替换为：

```java
package io.veridex.shared.infrastructure.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "veridex.search.opensearch")
public record OpenSearchProperties(
        List<String> uris,
        String indexPrefix) {
}
```

`IndexReleaseService.java`：加 import、字段、构造参数，并改 `prepare()`：

```java
import io.veridex.shared.infrastructure.config.EmbeddingProperties;
import io.veridex.shared.infrastructure.config.OpenSearchProperties;
...
    private final OpenSearchProperties properties;
    private final EmbeddingProperties embeddingProperties;

    public IndexReleaseService(IndexReleaseRepository releases,
                               IndexReleaseDocumentRepository snapshotDocuments,
                               SearchIndexGateway gateway,
                               OpenSearchProperties properties,
                               EmbeddingProperties embeddingProperties) {
        this.releases = releases;
        this.snapshotDocuments = snapshotDocuments;
        this.gateway = gateway;
        this.properties = properties;
        this.embeddingProperties = embeddingProperties;
    }
...
    public void prepare(UUID releaseId) {
        IndexRelease release = require(releaseId);
        gateway.createIndex(release.getIndexName(), embeddingProperties.dimensions());
    }
```

`application.yml`：删除 `search.opensearch.dimensions`，在 `embedding` 下加 `ollama`：

```yaml
  search:
    opensearch:
      uris: ${VERIDEX_OPENSEARCH_URIS:http://localhost:9200}
      index-prefix: ${VERIDEX_OPENSEARCH_INDEX_PREFIX:veridex}
  embedding:
    provider: ${VERIDEX_EMBEDDING_PROVIDER:deterministic}
    dimensions: ${VERIDEX_EMBEDDING_DIMENSIONS:128}
    ollama:
      base-url: ${VERIDEX_OLLAMA_BASE_URL:http://localhost:11434}
      model: ${VERIDEX_OLLAMA_EMBEDDING_MODEL:qwen3-embedding}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `./mvnw -pl backend test -Dtest=ConfigurationPropertiesBindingTest,IndexReleaseServiceTest`
Expected: 全绿。

- [ ] **Step 5: 提交**

```bash
git add backend/src/main/java/io/veridex/shared/infrastructure/config/ backend/src/main/resources/application.yml backend/src/main/java/io/veridex/indexing/infrastructure/IndexReleaseService.java backend/src/test/java/io/veridex/ConfigurationPropertiesBindingTest.java backend/src/test/java/io/veridex/indexing/IndexReleaseServiceTest.java
git commit -m "refactor: consolidate embedding dimensions into single config"
```

---

### Task 2: provider 条件装配（deterministic 默认 / ollama 互斥）

**Files:**
- Modify: `backend/pom.xml`（加 `spring-ai-ollama` 依赖）
- Modify: `backend/src/main/java/io/veridex/shared/infrastructure/embedding/DeterministicEmbeddingModel.java`
- Create: `backend/src/main/java/io/veridex/shared/infrastructure/embedding/OllamaEmbeddingConfiguration.java`
- Test: `backend/src/test/java/io/veridex/EmbeddingProviderSelectionTest.java`

**Interfaces:**
- Consumes: `EmbeddingProperties.ollama()`（Task 1）。
- Produces: 互斥的两个 `EmbeddingModel` bean（deterministic 或 ollama），供网关/reader/校验器注入。

- [ ] **Step 1: 写失败测试**

`EmbeddingProviderSelectionTest.java`：

```java
package io.veridex;

import static org.assertj.core.api.Assertions.assertThat;

import io.veridex.shared.infrastructure.config.EmbeddingProperties;
import io.veridex.shared.infrastructure.embedding.DeterministicEmbeddingModel;
import io.veridex.shared.infrastructure.embedding.OllamaEmbeddingConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class EmbeddingProviderSelectionTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean("embeddingProperties", EmbeddingProperties.class, () -> new EmbeddingProperties(
                    "deterministic", 128,
                    new EmbeddingProperties.Ollama("http://localhost:11434", "qwen3-embedding")))
            .withUserConfiguration(DeterministicEmbeddingModel.class, OllamaEmbeddingConfiguration.class);

    @Test
    void deterministicProviderRegistersOnlyDeterministicModel() {
        runner.withPropertyValues("veridex.embedding.provider=deterministic").run(context -> {
            assertThat(context).hasSingleBean(EmbeddingModel.class);
            assertThat(context.getBean(EmbeddingModel.class)).isInstanceOf(DeterministicEmbeddingModel.class);
        });
    }

    @Test
    void ollamaProviderRegistersOnlyOllamaModel() {
        runner.withPropertyValues("veridex.embedding.provider=ollama").run(context -> {
            assertThat(context).hasSingleBean(EmbeddingModel.class);
            assertThat(context.getBean(EmbeddingModel.class)).isInstanceOf(OllamaEmbeddingModel.class);
        });
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./mvnw -pl backend test -Dtest=EmbeddingProviderSelectionTest`
Expected: 编译失败（`OllamaEmbeddingConfiguration` 不存在）。

- [ ] **Step 3: 加依赖并实现**

`backend/pom.xml` 在 `spring-ai-client-chat` 依赖之后插入：

```xml
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-ollama</artifactId>
        </dependency>
```

`DeterministicEmbeddingModel.java` 加 import 与注解（其余不变）：

```java
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
...
@Component
@ConditionalOnProperty(name = "veridex.embedding.provider",
        havingValue = "deterministic", matchIfMissing = true)
public class DeterministicEmbeddingModel implements EmbeddingModel {
```

`OllamaEmbeddingConfiguration.java`（新增）：

```java
package io.veridex.shared.infrastructure.embedding;

import io.veridex.shared.infrastructure.config.EmbeddingProperties;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaEmbeddingOptions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "veridex.embedding.provider", havingValue = "ollama")
public class OllamaEmbeddingConfiguration {

    @Bean
    EmbeddingModel ollamaEmbeddingModel(EmbeddingProperties properties) {
        var api = OllamaApi.builder()
                .baseUrl(properties.ollama().baseUrl())
                .build();
        var options = OllamaEmbeddingOptions.builder()
                .model(properties.ollama().model())
                .build();
        return OllamaEmbeddingModel.builder()
                .ollamaApi(api)
                .options(options)
                .build();
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `./mvnw -pl backend test -Dtest=EmbeddingProviderSelectionTest`
Expected: 2 个测试全绿。

- [ ] **Step 5: 提交**

```bash
git add backend/pom.xml backend/src/main/java/io/veridex/shared/infrastructure/embedding/ backend/src/test/java/io/veridex/EmbeddingProviderSelectionTest.java
git commit -m "feat: add ollama embedding provider wiring"
```

---

### Task 3: 批量 embedding（入库）

**Files:**
- Modify: `backend/src/main/java/io/veridex/indexing/infrastructure/OpenSearchIndexGateway.java`
- Test: `backend/src/test/java/io/veridex/indexing/OpenSearchIndexGatewayBatchTest.java`

**Interfaces:**
- Consumes: `EmbeddingModel.call(EmbeddingRequest): EmbeddingResponse`（每 bulk 批次一次）。
- 保持 `SearchIndexGateway.createIndex(String, int)` 签名不变（维度仍由调用方传入）。

- [ ] **Step 1: 写失败测试**

`OpenSearchIndexGatewayBatchTest.java`（Mockito 深桩 OpenSearchClient + mock EmbeddingModel）：

```java
package io.veridex.indexing;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.veridex.indexing.api.ChunkRecord;
import io.veridex.indexing.infrastructure.OpenSearchIndexGateway;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

@ExtendWith(MockitoExtension.class)
class OpenSearchIndexGatewayBatchTest {

    @Mock(answer = Answers.RETURNS_DEEP_STUBS) OpenSearchClient client;
    @Mock EmbeddingModel embeddings;

    private OpenSearchIndexGateway gateway;

    @BeforeEach
    void setUp() {
        gateway = new OpenSearchIndexGateway(client, embeddings);
        when(embeddings.call(any(EmbeddingRequest.class))).thenAnswer(inv -> {
            var request = inv.<EmbeddingRequest>getArgument(0);
            List<Embedding> results = request.getInstructions().stream()
                    .map(text -> new Embedding(new float[]{1.0f}, 0))
                    .toList();
            return new EmbeddingResponse(results);
        });
    }

    @Test
    void embedsInBulkBatchesInsteadOfPerChunk() {
        List<ChunkRecord> chunks = IntStream.range(0, 120)
                .mapToObj(i -> new ChunkRecord(i, "chunk-" + i, "title", "1"))
                .toList();

        gateway.indexChunks("idx", UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), chunks);

        verify(embeddings, times(3)).call(any(EmbeddingRequest.class)); // 50 + 50 + 20
        verify(embeddings, never()).embed(anyString());
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./mvnw -pl backend test -Dtest=OpenSearchIndexGatewayBatchTest`
Expected: 失败（`verify times(3)` 不满足——当前逐 chunk 调 `embed()`）。

- [ ] **Step 3: 实现批量 embedding**

`OpenSearchIndexGateway.java`：加 import，替换 `indexChunks` 内循环，并加私有 `embedBatch`：

```java
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.embedding.EmbeddingRequest;
...
    @Override
    public void indexChunks(String indexName, UUID knowledgeBaseId, UUID documentVersionId,
                            UUID releaseId, List<ChunkRecord> chunks) {
        for (int start = 0; start < chunks.size(); start += BULK_BATCH) {
            List<ChunkRecord> batch = chunks.subList(start, Math.min(start + BULK_BATCH, chunks.size()));
            List<float[]> vectors = embedBatch(batch.stream().map(ChunkRecord::text).toList());
            var request = new BulkRequest.Builder();
            for (int i = 0; i < batch.size(); i++) {
                ChunkRecord chunk = batch.get(i);
                float[] vector = vectors.get(i);
                String docId = documentVersionId + ":" + chunk.index();
                request.operations(op -> op.index(idx -> idx
                        .index(indexName).id(docId)
                        .document(Map.of(
                                "text", chunk.text(),
                                "embedding", vector,
                                "document_version_id", documentVersionId.toString(),
                                "knowledge_base_id", knowledgeBaseId.toString(),
                                "release_id", releaseId.toString(),
                                "chunk_index", chunk.index(),
                                "structure_path", chunk.structurePath(),
                                "title", chunk.title()))));
            }
            try {
                var response = client.bulk(request.build());
                if (response.errors()) {
                    throw new IllegalStateException("bulk index reported errors for " + indexName);
                }
            } catch (IOException e) {
                throw new RuntimeException("bulk index failed for " + indexName, e);
            }
        }
        try {
            client.indices().refresh(r -> r.index(indexName));
        } catch (IOException e) {
            throw new RuntimeException("refresh failed for " + indexName, e);
        }
    }

    private List<float[]> embedBatch(List<String> texts) {
        var response = embeddings.call(new EmbeddingRequest(texts, EmbeddingOptions.builder().build()));
        return response.getResults().stream().map(Embedding::getOutput).toList();
    }
```

- [ ] **Step 4: 跑测试确认通过**

Run: `./mvnw -pl backend test -Dtest=OpenSearchIndexGatewayBatchTest,OpenSearchIndexGatewayTest,OpenSearchRetrievalReaderTest`
Expected: 全绿（新增单测 + 既有 OpenSearch 集成测试）。

- [ ] **Step 5: 提交**

```bash
git add backend/src/main/java/io/veridex/indexing/infrastructure/OpenSearchIndexGateway.java backend/src/test/java/io/veridex/indexing/OpenSearchIndexGatewayBatchTest.java
git commit -m "perf: batch embedding calls in index gateway"
```

---

### Task 4: 启动期维度校验

**Files:**
- Create: `backend/src/main/java/io/veridex/shared/infrastructure/embedding/EmbeddingDimensionValidator.java`
- Test: `backend/src/test/java/io/veridex/shared/infrastructure/embedding/EmbeddingDimensionValidatorTest.java`

**Interfaces:**
- Consumes: `EmbeddingModel.embed(String): float[]`、`EmbeddingProperties.dimensions()`（Task 1）。
- 实现 `ApplicationRunner`：context 启动后校验探针向量长度 == 配置维度，不一致抛 `IllegalStateException`。

- [ ] **Step 1: 写失败测试**

`EmbeddingDimensionValidatorTest.java`：

```java
package io.veridex.shared.infrastructure.embedding;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.veridex.shared.infrastructure.config.EmbeddingProperties;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;

class EmbeddingDimensionValidatorTest {

    @Test
    void passesWhenProbeMatchesConfiguredDimension() {
        var embeddings = mock(EmbeddingModel.class);
        when(embeddings.embed(anyString())).thenReturn(new float[1024]);
        var props = new EmbeddingProperties("ollama", 1024,
                new EmbeddingProperties.Ollama("http://localhost:11434", "qwen3-embedding"));

        var validator = new EmbeddingDimensionValidator(embeddings, props);

        assertThatCode(() -> validator.run(null)).doesNotThrowAnyException();
    }

    @Test
    void failsWhenProbeDiffersFromConfiguredDimension() {
        var embeddings = mock(EmbeddingModel.class);
        when(embeddings.embed(anyString())).thenReturn(new float[768]);
        var props = new EmbeddingProperties("ollama", 1024,
                new EmbeddingProperties.Ollama("http://localhost:11434", "qwen3-embedding"));

        var validator = new EmbeddingDimensionValidator(embeddings, props);

        assertThatThrownBy(() -> validator.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("1024");
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./mvnw -pl backend test -Dtest=EmbeddingDimensionValidatorTest`
Expected: 编译失败（`EmbeddingDimensionValidator` 不存在）。

- [ ] **Step 3: 实现**

`EmbeddingDimensionValidator.java`：

```java
package io.veridex.shared.infrastructure.embedding;

import io.veridex.shared.infrastructure.config.EmbeddingProperties;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class EmbeddingDimensionValidator implements ApplicationRunner {

    private final EmbeddingModel embeddings;
    private final EmbeddingProperties properties;

    public EmbeddingDimensionValidator(EmbeddingModel embeddings, EmbeddingProperties properties) {
        this.embeddings = embeddings;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        float[] probe = embeddings.embed("veridex:dimension-probe");
        if (probe.length != properties.dimensions()) {
            throw new IllegalStateException(
                    "Embedding model returned " + probe.length + " dimensions but veridex.embedding.dimensions="
                            + properties.dimensions() + ". Fix the dimension config (and rebuild indices) before starting.");
        }
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `./mvnw -pl backend test -Dtest=EmbeddingDimensionValidatorTest`
Expected: 2 个测试全绿。

- [ ] **Step 5: 提交**

```bash
git add backend/src/main/java/io/veridex/shared/infrastructure/embedding/EmbeddingDimensionValidator.java backend/src/test/java/io/veridex/shared/infrastructure/embedding/EmbeddingDimensionValidatorTest.java
git commit -m "feat: validate embedding dimension at startup"
```

---

### Task 5: 文档 + 全量门禁

**Files:**
- Modify: `README.md`

- [ ] **Step 1: 更新 README**

1. 「当前限制」第一条改为：默认 `DeterministicEmbeddingModel` 为 128 维确定性哈希（无语义）；可通过 `VERIDEX_EMBEDDING_PROVIDER=ollama` 切换 Ollama `qwen3-embedding`（真实语义，1024 维）。
2. 「后端环境变量」表追加两行：`VERIDEX_OLLAMA_BASE_URL`（默认 `http://localhost:11434`）、`VERIDEX_OLLAMA_EMBEDDING_MODEL`（默认 `qwen3-embedding`）。
3. 在「进一步阅读」前加一节「使用真实向量模型（Ollama）」，内容：`ollama pull qwen3-embedding`；以 `VERIDEX_EMBEDDING_PROVIDER=ollama VERIDEX_EMBEDDING_DIMENSIONS=1024` 启动；换模型后须发新 release 重建索引（旧 128 维索引不可在新维度下查询）。

- [ ] **Step 2: 全量质量门禁**

Run: `./scripts/verify.sh`
Expected: 后端 `BUILD SUCCESS`（含全部既有 113 测试 + 新增测试）、前端测试通过、前端构建通过、`git diff --check` 通过。

- [ ] **Step 3: 提交**

```bash
git add README.md
git commit -m "docs: document ollama embedding provider"
```

---

## 手动验收（不进 CI，真实 Ollama）

1. `ollama pull qwen3-embedding`（或用 `ollama show qwen3-embedding` 复核维度为 1024）。
2. `VERIDEX_EMBEDDING_PROVIDER=ollama VERIDEX_EMBEDDING_DIMENSIONS=1024 ./mvnw -pl backend spring-boot:run`。
3. 观察启动日志无维度校验异常；上传文档 → 新 release 索引 `knn_vector.dimension=1024`。
4. 员工问答页提问，验证向量路召回语义正确、引用预览正常。

