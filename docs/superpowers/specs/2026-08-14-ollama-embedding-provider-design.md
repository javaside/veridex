# Ollama Embedding Provider 接入 — 设计文档

> 日期：2026-08-14
> 状态：已与用户确认方案 A（provider 条件装配 + EmbeddingModel 单端口）

## 1. 背景与目标

### 1.1 现状

- 全平台只有一个 `EmbeddingModel` 实现：`DeterministicEmbeddingModel`（`shared/infrastructure/embedding`），128 维确定性哈希向量，**不具备语义相似度能力**，仅用于打通管道。
- `EmbeddingProperties` 已声明 `provider` 字段，但**没有任何切换逻辑**；该 record 当前无人注入使用。
- 索引 `knn_vector.dimension` 来自 `OpenSearchProperties.dimensions`（`veridex.search.opensearch.dimensions`，默认 128），由 `IndexReleaseService.prepare()` 传给 `createIndex(indexName, dimensions)`。
- 向量模型只有两个消费点，都通过 Spring AI `EmbeddingModel` 接口注入：
  1. `OpenSearchIndexGateway.indexChunks()` —— 入库时逐 chunk 调 `embeddings.embed(chunk.text())`
  2. `OpenSearchRetrievalReader.vector()` —— 查询时调 `embeddings.embed(question)`

### 1.2 本轮目标

支持真实向量模型：**Ollama + `qwen3-embedding`（1024 维）**，通过 `veridex.embedding.provider` 配置在 deterministic 与 ollama 之间切换。换真实模型后，检索/入库的语义质量生效。

### 1.3 范围

- 本轮只做 **embedding provider 接入**；chat 模型仍为 `DeterministicChatModel`，不在范围内。
- **不涉及**：评测闭环（Phase 4）、rerank 真实模型、OpenAI/其他 provider、Ollama 入 dev compose。
- 沿用 Spring AI 作为集成框架，不新建平台自有 embedding 抽象。

## 2. 已确认的设计决策

| # | 决策 | 说明 |
|---|---|---|
| D1 | **`EmbeddingModel` 唯一端口** | 不新建平台自有抽象；换 bean 实现即可，两个消费点零改动（符合设计文档「Spring AI 只作集成框架」）。 |
| D2 | **provider 条件装配** | `DeterministicEmbeddingModel` 加 `@ConditionalOnProperty(provider=deterministic, matchIfMissing=true)`；新增 Ollama bean 用 `@ConditionalOnProperty(provider=ollama)`。二者互斥，避免两个 `EmbeddingModel` 造成注入歧义。 |
| D3 | **用 `spring-ai-ollama` core 模块手接** | 不加 starter（starter 会带 chat autoconfig、且 2.0.0 autoconfigure 未发正式版）；手接 `OllamaEmbeddingModel`，与现有 `spring-ai-client-chat` + `DeterministicChatModel` 风格一致。 |
| D4 | **维度收敛为单一配置** | 删 `OpenSearchProperties.dimensions`；`EmbeddingProperties.dimensions` 成为索引 `knn_vector.dimension` 的唯一来源。 |
| D5 | **启动期维度校验** | provider 任意取值下，embed 一个探针字符串，断言返回向量长度 == `veridex.embedding.dimensions`，不一致 fail-fast（防止「索引按错维度建好、bulk 全失败」的隐蔽坑）。 |
| D6 | **批量 embedding** | `indexChunks` 每个 bulk 批次（50）收集文本后一次 `call()` 拿 50 个向量，网络往返从 N 降到 N/50。 |
| D7 | **错误处理复用现有链路** | 入库 embedding 失败 → 抛 RuntimeException → 现有 DLQ + 版本 FAILED；检索向量路失败 → 现有单路降级（BM25 兜底）。不新增错误机制。 |
| D8 | **索引迁移走 IndexRelease 快照** | 128→1024 后旧索引不可用；换模型 = 发布新 release 重建索引。旧 128 维索引保留但不可在新模型下查询（回切需连旧模型）。 |
| D9 | **默认 provider 仍 deterministic** | 离线/测试路径不变；切 Ollama 用环境变量显式开启。 |
| D10 | **Ollama 不进 compose** | macOS 原生 Ollama 跑在 `localhost:11434`（容器内无 GPU）；`base-url` 可配指向本地或远程。 |

## 3. 组件与装配

### 3.1 依赖（backend/pom.xml）

新增（版本由 `spring-ai-bom:2.0.0` 管理，本地 m2 已确认存在）：

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-ollama</artifactId>
</dependency>
```

### 3.2 DeterministicEmbeddingModel 条件化

`shared/infrastructure/embedding/DeterministicEmbeddingModel.java` 加：

```java
@Component
@ConditionalOnProperty(name = "veridex.embedding.provider",
        havingValue = "deterministic", matchIfMissing = true)
public class DeterministicEmbeddingModel implements EmbeddingModel { ... }
```

### 3.3 OllamaEmbeddingConfiguration（新增）

`shared/infrastructure/embedding/OllamaEmbeddingConfiguration.java`：

```java
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

（类名/构造已用 javap 核对：`OllamaApi.builder().baseUrl(String).build()`、`OllamaEmbeddingOptions.builder().model(String)`、`OllamaEmbeddingModel.builder().ollamaApi().options().build()`。）

### 3.4 EmbeddingProperties 扩展

`shared/infrastructure/config/EmbeddingProperties.java` 扩展为：

```java
@ConfigurationProperties(prefix = "veridex.embedding")
public record EmbeddingProperties(
        String provider,
        int dimensions,
        Ollama ollama) {
    public record Ollama(String baseUrl, String model) {}
}
```

删除 `OpenSearchProperties` 的 `dimensions` 字段。

## 4. 配置（application.yml）

```yaml
veridex:
  embedding:
    provider: ${VERIDEX_EMBEDDING_PROVIDER:deterministic}
    dimensions: ${VERIDEX_EMBEDDING_DIMENSIONS:128}
    ollama:
      base-url: ${VERIDEX_OLLAMA_BASE_URL:http://localhost:11434}
      model: ${VERIDEX_OLLAMA_EMBEDDING_MODEL:qwen3-embedding}
```

- 默认 `deterministic` + `128` 维：零配置下现有行为、测试完全不变。
- 切 Ollama：`VERIDEX_EMBEDDING_PROVIDER=ollama` 且 `VERIDEX_EMBEDDING_DIMENSIONS=1024`（qwen3-embedding 实际维度；忘设维度会被启动校验拦下）。

## 5. 维度一致性

- `IndexReleaseService.prepare()` 改用 `EmbeddingProperties.dimensions()` 建索引（注入 `EmbeddingProperties` 取代 `OpenSearchProperties` 传维度）。
- 新增 `EmbeddingDimensionValidator`（`ApplicationRunner`）：启动时 `embeddings.embed(probe)`，断言 `vector.length == embedding.dimensions`；不一致抛出带清晰提示的异常（例如「Ollama 模型返回 1024 维，但 veridex.embedding.dimensions=128，请修正配置后重建索引」）。
- 对 provider=ollama，校验失败即启动失败（配置了 Ollama 就要求其可达且维度正确）。

## 6. 批量 embedding

`OpenSearchIndexGateway.indexChunks()` 由逐条 `embed()` 改为按 `BULK_BATCH`（50）批内一次 `call()`：

```java
List<String> texts = batch.stream().map(ChunkRecord::text).toList();
var response = embeddings.call(new EmbeddingRequest(texts, EmbeddingOptions.EMPTY));
List<float[]> vectors = response.getResults().stream().map(Embedding::getOutput).toList();
```

（`DeterministicEmbeddingModel.call()` 已支持多指令批量；`OllamaEmbeddingModel.call()` 走 Ollama `/api/embed` 批量接口。）

## 7. 错误处理

- 入库：embedding 抛异常 → 现有 `RuntimeException` → RabbitMQ DLQ + 版本 `FAILED`（不新增逻辑）。
- 检索：向量路失败 → `dac674e` 已实现的单路降级（BM25 兜底），双路都失败才系统失败。
- 客户端/超时：Ollama 连接失败在启动校验与运行期都会以明确异常暴露，不静默伪装结果。

## 8. 索引迁移

- 现有 128 维索引与 1024 维向量不兼容：换模型后**必须发一个新 release**（新索引按 1024 维 mapping 建立）并重导文档。
- 旧 release 索引保留不删（可回滚），但只能在与之一致的旧模型维度下查询；跨模型维度回切不受支持，文档写明。
- 本地开发数据可按 README「`down -v` 重建 / 重新上传」处理。

## 9. 测试

- 现有测试不受影响：默认 provider=deterministic 经 `matchIfMissing` 继续生效。
- 新增：
  1. `EmbeddingProviderSelectionTest`：`ApplicationContextRunner` 断言 provider=deterministic 时只有 DeterministicEmbeddingModel、provider=ollama 时只有 OllamaEmbeddingModel（且 deterministic 缺席）。
  2. `OpenSearchIndexGatewayTest`：mock `EmbeddingModel` 断言每 bulk 批次只 `call()` 一次、返回向量与 chunk 一一对应。
  3. `EmbeddingDimensionValidatorTest`：维度一致通过、不一致启动失败。
- 真实 Ollama 验证（手动，不进默认 CI）：`ollama pull qwen3-embedding` → 以 `VERIDEX_EMBEDDING_PROVIDER=ollama VERIDEX_EMBEDDING_DIMENSIONS=1024` 启动 → 确认索引 1024 维 mapping、检索召回语义正确。

## 10. 运维 / 验证步骤

1. `ollama pull qwen3-embedding`（或指向已有远程 Ollama，改 `VERIDEX_OLLAMA_BASE_URL`）。
2. 启动后端：`VERIDEX_EMBEDDING_PROVIDER=ollama VERIDEX_EMBEDDING_DIMENSIONS=1024 ./mvnw -pl backend spring-boot:run`。
3. 观察启动校验通过；上传文档 → 新 release 索引 mapping 维度为 1024。
4. 员工问答页提问，验证向量路召回与引用预览。

