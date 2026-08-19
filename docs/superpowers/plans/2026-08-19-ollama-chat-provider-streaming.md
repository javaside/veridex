# Ollama Chat Provider 与真实流式问答 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Execute tasks in order and do not delegate them to subagents.

**Goal:** 把在线问答从「同步生成 + 按 8 字符伪造 answer.delta」改造为「启动期互斥装配 deterministic/Ollama ChatModel + 端到端 `Flux<QaEvent>` 真实 SSE 流」，并把引用终检、客户端取消、模型超时/故障与 trace/metrics 收尾全部接到真实模型语义上。

**Architecture:** `veridex.chat.provider`（默认 `deterministic`）通过 `@ConditionalOnProperty` 互斥装配 `DeterministicChatModel` 或手工组装的 `OllamaChatModel`（复用 `spring-ai-ollama`，不引入 starter；装配前经 `OutboundAccessPolicy.validate`）。`GenerationService` 增加 `Flux<GenerationEvent> stream(...)`，与同步 `generate` 共享拒答/prompt/引用/usage/观测逻辑；`QuestionAnsweringService.ask` 返回 cold `Flux<QaEvent>`，由 `QaController` 直接映射为 `Flux<ServerSentEvent<QaEvent>>`（弃用 SseEmitter+CompletableFuture）。模型流完成后再做引用终检，未通过则发 `run.failed`（INVALID_CITATION）；客户端断开经 `sink.onCancel` 传播为上游取消并落 `CANCELLED`。前端把流式回答当 provisional，`run.failed`/取消时丢弃临时消息，`qaApi.ask` 接受 `AbortSignal`。

**Tech Stack:** Java 21、Spring Boot 3.3.8、Spring AI 2.0.0（`spring-ai-client-chat` + `spring-ai-ollama`，手工 `OllamaApi/OllamaChatOptions/OllamaChatModel` builder 装配）、Reactor 3、JPA/Flyway（V14 迁移）、Micrometer Observation、Vitest + Testing Library、Docker Compose、Helm 3。

**Spec:** `docs/superpowers/specs/2026-08-18-ollama-chat-provider-streaming-design.md`（状态：已确认）。本计划逐条落实该设计 §3 决策 D1–D12、§4 组件设计、§5 失败/超时/取消、§6 可观测性、§7 前端协议、§8 部署契约与 §10 验收标准。

## Global Constraints

- provider 只允许 `deterministic` 与 `ollama`；默认 `deterministic`（零配置、CI/离线可重复）；非法 provider 启动失败。
- 业务代码只依赖 Spring AI `ChatModel` 接口，不得依赖具体 provider 类型；不引入 Ollama starter。
- `answer.delta` 是 provisional；只有 `answer.completed` 表示文本已通过引用终检并持久化。引用失败发 `run.failed`（稳定码 `INVALID_CITATION`），不保存 assistant 消息、不落 Citation，QueryRun 标 `FAILED`。
- 模型故障/超时/空响应统一 `MODEL_ERROR` 族，超时保留稳定子码 `MODEL_TIMEOUT`；**无** deterministic 静默回退。
- 客户端取消：取消 `ChatModel.stream()` 上游订阅、`recorder.cancel(runId)`、trace 落 `CANCELLED`、不保存不完整 assistant 消息、不再发终端 SSE；取消收尾必须幂等，不得覆盖已完成/拒答/失败。
- 在线问答 Flux 是 cold 的、单次订阅只执行一次问答流程，禁止隐式重试（否则重复写用户消息/QueryRun）。
- 指标标签：provider 标签只允许 `deterministic`/`ollama`；模型名标签受控/归一化（非法字符/URL → `unknown`）；禁止把 URL、prompt、异常文本放进标签。
- 优先读最终 `ChatResponse` metadata 的 usage；缺失时按字符数估算并内部标记 `usageEstimated=true`。
- SSE 层 async timeout（`spring.mvc.async.request-timeout`）不得短于 `veridex.chat.timeout`（默认 60s）加收尾余量，固定为 120s。
- Ollama 经 HTTP 时必须 `VERIDEX_OUTBOUND_ALLOW_INSECURE_HTTP=true`，host/port 进 `VERIDEX_OUTBOUND_ALLOWED_HOSTS/PORTS`；沿用 fail-fast，不为 localhost/内网加隐式豁免。Helm NetworkPolicy 只用 `networkPolicy.externalEgress` 显式放行，chart 默认不开放 Ollama 出站。
- 默认 Compose 保持 deterministic、不新增 Ollama 容器；真实 Ollama 不进默认 CI，只提供显式验收脚本。
- 本阶段不动 embedding provider、rerank provider、评测指标定义；不做 OpenAI provider、请求级动态路由、多模型负载均衡、故障转移；不做 5-e 的备份恢复/Spring Session/容量报告。
- 回归约束：默认 provider 下现有 backend/web 测试全绿；ACL、prompt injection、敏感输出、citation preview re-authorization 与 trace body 保护测试必须覆盖新的流式入口。
- 每个 Task 先 RED 后 GREEN，独立提交；提交尾注固定为 `Co-Authored-By: CodeTui <noreply@codetui.dev>`；每个任务结束运行 `git diff --check`。

---

## File Structure

**配置与装配（Task 1）：**

- Create `backend/src/main/java/io/veridex/generation/infrastructure/ChatProperties.java` — `veridex.chat` 配置（provider/timeout/ollama.baseUrl/model）。
- Create `backend/src/main/java/io/veridex/generation/infrastructure/ChatProviderConfiguration.java` — `@EnableConfigurationProperties(ChatProperties.class)` + provider 合法值校验。
- Modify `backend/src/main/java/io/veridex/generation/infrastructure/DeterministicChatModel.java` — 加 `@ConditionalOnProperty(veridex.chat.provider=deterministic, matchIfMissing=true)`。
- Create `backend/src/main/java/io/veridex/generation/infrastructure/OllamaChatConfiguration.java` — `veridex.chat.provider=ollama` 时手工装配 `OllamaChatModel`，装配前 outbound 校验。
- Modify `backend/src/main/resources/application.yml` — 新增 `veridex.chat.*` 与 `spring.mvc.async.request-timeout`。
- Modify `backend/src/main/java/io/veridex/shared/observability/BoundedModelTags.java` — provider 白名单 {deterministic, ollama} + 模型名归一化。
- Test: `backend/src/test/java/io/veridex/generation/ChatProviderSelectionTest.java`、`ChatProviderVerifierTest.java`、`ChatConfigContractTest.java`；Modify `backend/src/test/java/io/veridex/shared/observability/ObservabilityPrimitivesTest.java`。

**GenerationService 同步/流式统一（Task 2）：**

- Create `backend/src/main/java/io/veridex/generation/api/GenerationEvent.java` — sealed：Delta/Completed/Refused。
- Modify `backend/src/main/java/io/veridex/generation/api/GenerationResult.java` — 增 provider、firstTokenLatencyMs、usageEstimated；保留 8 参便捷构造。
- Modify `backend/src/main/java/io/veridex/generation/api/GenerationService.java` — 增 `Flux<GenerationEvent> stream(...)`。
- Modify `backend/src/main/java/io/veridex/generation/application/GenerationServiceImpl.java` — 注入 `ChatModel` + `ChatProperties`；抽取共享私有逻辑；实现流式（超时/首 token/usage 提取/引用终检/空响应）。
- Create `backend/src/main/java/io/veridex/generation/application/GenerationModelException.java` — `ModelTimeoutException` / `ModelEmptyException` / `InvalidCitationException`。
- Modify `backend/src/main/java/io/veridex/shared/observability/TelemetryErrorCode.java` — 增 `MODEL_TIMEOUT`、`INVALID_CITATION` + persisted 集合。
- Modify `backend/src/main/java/io/veridex/shared/observability/MetricName.java` — 增 `GENERATION_FIRST_TOKEN`、`GENERATION_OUTCOME`。
- Modify `backend/src/main/java/io/veridex/shared/observability/TelemetryOutcome.java` — `Generation` 增 `REFUSED`、`CANCELLED`。
- Create `backend/src/main/resources/db/migration/V14__chat_provider_streaming.sql` — generation_run 增列。
- Modify `backend/src/main/java/io/veridex/trace/domain/GenerationRun.java` — 增 provider、firstTokenLatencyMs、applyMetrics。
- Modify `backend/src/main/java/io/veridex/trace/domain/GenerationRunRepository.java` — 增 `findFirstByQueryRunId`。
- Modify `backend/src/main/java/io/veridex/trace/domain/QueryRun.java` — `cancel()`/`fail()` 终端态保护。
- Modify `backend/src/main/java/io/veridex/trace/api/QueryRunRecorder.java` — `GenerationRecord` 增字段；`markGenerating(runId)` + `recordGeneration(runId, gen)`。
- Modify `backend/src/main/java/io/veridex/trace/application/QueryRunRecorderImpl.java` — 实现新方法。
- Test: Modify `backend/src/test/java/io/veridex/generation/GenerationServiceImplTest.java`；Create `GenerationStreamingTest.java`（或并入前者）；Modify `backend/src/test/java/io/veridex/trace/TraceQueryRunTest.java`（若有）。

**QA 编排与 Controller（Task 3）：**

- Modify `backend/src/main/java/io/veridex/qa/application/QuestionAnsweringService.java` — `Flux<QaEvent> ask(...)`。
- Modify `backend/src/main/java/io/veridex/qa/application/QuestionAnsweringServiceImpl.java` — cold Flux.create + 取消传播 + 错误收尾。
- Modify `backend/src/main/java/io/veridex/qa/api/QaController.java` — 返回 `Flux<ServerSentEvent<QaEvent>>`。
- Test: Modify `backend/src/test/java/io/veridex/qa/QuestionAnsweringServiceTest.java`（StepVerifier）；Modify `backend/src/test/java/io/veridex/qa/QaTestFixture.java`（增量读取辅助）；Modify `backend/src/test/java/io/veridex/qa/QaApiIntegrationTest.java`（首 delta 先于 EOF）。

**前端（Task 4）：**

- Modify `web/src/features/qa/qaApi.ts` — `ask` 增 `signal` 参数，abort 不伪造 run.failed。
- Modify `web/src/features/qa/QaPage.tsx` — AbortController、run.failed/refused 丢弃 provisional、卸载取消。
- Test: Modify `web/src/features/qa/QaPage.test.tsx`。

**部署（Task 5）：**

- Modify `deploy/compose/compose.yml`、`deploy/compose/.env.example` — 注释形式 Ollama Chat 环境变量。
- Modify `deploy/helm/veridex/values.yaml` — backend.env 增 chat 键；externalEgress 增 Ollama 示例注释。
- Modify `deploy/offline/veridex-offline/INSTALL.txt` — Ollama 由安装者预置说明。
- Create `scripts/verify-ollama-chat.sh` — 真实 Ollama 显式验收入口（不进入默认 CI）。

**文档与门禁（Task 6）：**

- Modify `README.md`、`docs/architecture.md` — deterministic=测试占位、Ollama=首个真实 Chat provider、流式协议、配置说明。
- 运行 `scripts/verify.sh` 全量门禁（含 deploy 契约、前端 build）。

---

### Task 1: Chat provider 配置与条件装配

**Files:**
- Create: `backend/src/main/java/io/veridex/generation/infrastructure/ChatProperties.java`
- Create: `backend/src/main/java/io/veridex/generation/infrastructure/ChatProviderConfiguration.java`
- Modify: `backend/src/main/java/io/veridex/generation/infrastructure/DeterministicChatModel.java`
- Create: `backend/src/main/java/io/veridex/generation/infrastructure/OllamaChatConfiguration.java`
- Modify: `backend/src/main/resources/application.yml`
- Modify: `backend/src/main/java/io/veridex/shared/observability/BoundedModelTags.java`
- Create: `backend/src/test/java/io/veridex/generation/ChatProviderSelectionTest.java`
- Create: `backend/src/test/java/io/veridex/generation/ChatProviderVerifierTest.java`
- Create: `backend/src/test/java/io/veridex/generation/ChatConfigContractTest.java`
- Modify: `backend/src/test/java/io/veridex/shared/observability/ObservabilityPrimitivesTest.java`

**Interfaces:**
- Consumes: 现有 `OutboundAccessPolicy.validate(URI)`（`shared/infrastructure/security`）、`EmbeddingProperties` 的 conditional 装配模式。
- Produces:
  - `ChatProperties`（record，prefix `veridex.chat`）：`String provider`、`Duration timeout`、`Ollama ollama`；`Ollama(String baseUrl, String model)`。
  - `ChatProviderConfiguration`：注册 ChatProperties + `ChatProviderVerifier`（`InitializingBean`，provider ∉ {deterministic, ollama} 抛 `IllegalStateException`，消息含 provider 值）。
  - `OllamaChatConfiguration`：`@ConditionalOnProperty(name="veridex.chat.provider", havingValue="ollama")`，产出 `ChatModel` Bean（OllamaChatModel）。
  - `DeterministicChatModel` 增加 `@ConditionalOnProperty(name="veridex.chat.provider", havingValue="deterministic", matchIfMissing=true)`。
  - `BoundedModelTags(Set<String> approvedProviders)`：2 参 `resolve(provider, model)` 改为 provider 必须命中白名单、模型名走归一化；1 参 `resolve(model)` 行为不变。
  - 环境变量：`VERIDEX_CHAT_PROVIDER`、`VERIDEX_CHAT_TIMEOUT`、`VERIDEX_OLLAMA_BASE_URL`、`VERIDEX_OLLAMA_CHAT_MODEL`；`spring.mvc.async.request-timeout` 固定 `120s`。

- [ ] **Step 1: 写失败的 ChatProperties 绑定测试（先建属性类）**

Create `ChatProperties.java`：

```java
package io.veridex.generation.infrastructure;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "veridex.chat")
public record ChatProperties(String provider, Duration timeout, Ollama ollama) {

    public record Ollama(String baseUrl, String model) {
    }
}
```

Create `ChatProviderConfiguration.java`：

```java
package io.veridex.generation.infrastructure;

import java.util.Set;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ChatProperties.class)
public class ChatProviderConfiguration {

    /** 启动期校验：非法 provider 必须失败，不能静默降级或装配歧义。 */
    @org.springframework.context.annotation.Bean
    ChatProviderVerifier chatProviderVerifier(ChatProperties properties) {
        return new ChatProviderVerifier(properties);
    }

    static final class ChatProviderVerifier implements org.springframework.beans.factory.InitializingBean {
        private static final Set<String> ALLOWED = Set.of("deterministic", "ollama");
        private final ChatProperties properties;

        ChatProviderVerifier(ChatProperties properties) {
            this.properties = properties;
        }

        @Override
        public void afterPropertiesSet() {
            String provider = properties.provider();
            if (provider == null || !ALLOWED.contains(provider)) {
                throw new IllegalStateException("veridex.chat.provider must be one of " + ALLOWED
                        + " but was " + provider);
            }
        }
    }
}
```

Create `ChatConfigContractTest.java`（文本契约，避免起全量上下文）：

```java
package io.veridex.generation;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ChatConfigContractTest {

    private static final Path APP_YML = Path.of("src/main/resources/application.yml");

    private String propertyLine(String prefix) throws Exception {
        return Files.readAllLines(APP_YML).stream()
                .map(String::trim)
                .filter(line -> line.startsWith(prefix))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing property " + prefix));
    }

    @Test
    void sseAsyncTimeoutIsLongerThanChatTimeoutPlusMargin() throws Exception {
        // 设计 §5.2：HTTP/SSE 层超时不得短于模型总时限加收尾余量；固定 120s ≥ 60s + 60s 余量。
        String async = propertyLine("request-timeout:").replace("request-timeout:", "").trim();
        String chat = propertyLine("timeout:").replace("timeout:", "").trim();
        assertThat(async).isEqualTo("${VERIDEX_SSE_ASYNC_TIMEOUT:120s}");
        assertThat(chat).isEqualTo("${VERIDEX_CHAT_TIMEOUT:60s}");
    }

    @Test
    void chatProviderDefaultsToDeterministic() throws Exception {
        assertThat(propertyLine("provider:").replace("provider:", "").trim())
                .isEqualTo("${VERIDEX_CHAT_PROVIDER:deterministic}");
    }
}
```

- [ ] **Step 2: 在 application.yml 添加 chat 配置与 async timeout**

在 `spring:` 顶层新增：

```yaml
  mvc:
    async:
      request-timeout: ${VERIDEX_SSE_ASYNC_TIMEOUT:120s}
```

在 `veridex:` 块内新增（放在 `embedding:` 之后）：

```yaml
  chat:
    provider: ${VERIDEX_CHAT_PROVIDER:deterministic}
    timeout: ${VERIDEX_CHAT_TIMEOUT:60s}
    ollama:
      base-url: ${VERIDEX_OLLAMA_BASE_URL:http://localhost:11434}
      model: ${VERIDEX_OLLAMA_CHAT_MODEL:qwen3:8b}
```

- [ ] **Step 3: 运行契约测试确认通过**

Run: `./mvnw -f backend/pom.xml -pl . test -Dtest=ChatConfigContractTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS（属性占位符逐字匹配）。若属性行格式不匹配，以文件实际缩进为准调整断言。

- [ ] **Step 4: 给 DeterministicChatModel 加条件装配**

Modify `DeterministicChatModel.java`：在 `@Component` 下加

```java
@Component
@ConditionalOnProperty(name = "veridex.chat.provider", havingValue = "deterministic", matchIfMissing = true)
public class DeterministicChatModel implements ChatModel {
```

import `org.springframework.boot.autoconfigure.condition.ConditionalOnProperty`。其余代码不动。

- [ ] **Step 5: 写 provider 互斥选择测试（RED）**

Create `ChatProviderSelectionTest.java`：

```java
package io.veridex.generation;

import static org.assertj.core.api.Assertions.assertThat;

import io.veridex.generation.infrastructure.ChatProperties;
import io.veridex.generation.infrastructure.ChatProviderConfiguration;
import io.veridex.generation.infrastructure.DeterministicChatModel;
import io.veridex.generation.infrastructure.OllamaChatConfiguration;
import io.veridex.shared.infrastructure.security.OutboundAccessPolicy;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ChatProviderSelectionTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(ChatProperties.class, () -> new ChatProperties("deterministic", Duration.ofSeconds(60),
                    new ChatProperties.Ollama("http://localhost:11434", "qwen3:8b")))
            .withBean(OutboundAccessPolicy.class,
                    () -> new OutboundAccessPolicy(Set.of("localhost"), Set.of(11434), true))
            .withUserConfiguration(ChatProviderConfiguration.class, OllamaChatConfiguration.class);

    @Test
    void deterministicProviderAssemblesOnlyDeterministicChatModel() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(DeterministicChatModel.class);
            assertThat(context.getBean(ChatModel.class)).isInstanceOf(DeterministicChatModel.class);
        });
    }

    @Test
    void ollamaProviderAssemblesOnlyOllamaChatModel() {
        runner.withBean(ChatProperties.class, () -> new ChatProperties("ollama", Duration.ofSeconds(60),
                        new ChatProperties.Ollama("http://localhost:11434", "qwen3:8b")))
                .run(context -> {
                    assertThat(context).hasSingleBean(ChatModel.class);
                    assertThat(context).doesNotHaveBean(DeterministicChatModel.class);
                    assertThat(context.getBean(ChatModel.class)).isInstanceOf(
                            org.springframework.ai.ollama.OllamaChatModel.class);
                });
    }
}
```

注意：`DeterministicChatModel` 是 `@Component`，`OllamaChatConfiguration` 是 `@Configuration`，二者都进 `AutoConfigurations.of(...)` 才会被 `ApplicationContextRunner` 看到——Step 7 修；Step 5 先写测试文件（此时 `OllamaChatConfiguration` 尚不存在，编译失败即 RED）。

- [ ] **Step 6: 运行确认 RED**

Run: `./mvnw -f backend/pom.xml -pl . test -Dtest=ChatProviderSelectionTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL（`OllamaChatConfiguration` 不存在，编译错误）。

- [ ] **Step 7: 实现 OllamaChatConfiguration**

Create `OllamaChatConfiguration.java`：

```java
package io.veridex.generation.infrastructure;

import io.veridex.shared.infrastructure.security.OutboundAccessPolicy;
import java.net.URI;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 真实 Chat provider（设计 D4/D12）：仅 veridex.chat.provider=ollama 时装配。
 * 手工组装、不引入 starter；装配前经 OutboundAccessPolicy 校验，fail-fast。
 */
@Configuration
@ConditionalOnProperty(name = "veridex.chat.provider", havingValue = "ollama")
public class OllamaChatConfiguration {

    @Bean
    ChatModel ollamaChatModel(ChatProperties properties, OutboundAccessPolicy outboundPolicy) {
        outboundPolicy.validate(URI.create(properties.ollama().baseUrl()));
        var api = OllamaApi.builder()
                .baseUrl(properties.ollama().baseUrl())
                .build();
        var options = OllamaChatOptions.builder()
                .model(properties.ollama().model())
                .build();
        return OllamaChatModel.builder()
                .ollamaApi(api)
                .options(options)
                .build();
    }
}
```

同步把 Step 5 测试的 runner 改为：

```java
.withUserConfiguration(ChatProviderConfiguration.class, OllamaChatConfiguration.class)
.withConfiguration(AutoConfigurations.of(DeterministicChatModel.class))
```

（`withBean(ChatProperties...)` 重复声明会导致 runner 两次注册同一类型冲突；把 ChatProperties 交给 `ChatProviderConfiguration` 的 `@EnableConfigurationProperties` 绑定，runner 通过 `withPropertyValues("veridex.chat.provider=...")` 驱动。最终测试见 Step 8 的完整形态。）

- [ ] **Step 8: 稳定 provider 选择测试（GREEN）**

把 `ChatProviderSelectionTest` 收敛为用属性驱动（避免手捏 ChatProperties 与绑定冲突）：

```java
class ChatProviderSelectionTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(DeterministicChatModel.class, OllamaChatConfiguration.class,
                    ChatProviderConfiguration.class))
            .withPropertyValues(
                    "veridex.chat.timeout=60s",
                    "veridex.chat.ollama.base-url=http://localhost:11434",
                    "veridex.chat.ollama.model=qwen3:8b",
                    "veridex.security.outbound.allowed-hosts=localhost",
                    "veridex.security.outbound.allowed-ports=11434",
                    "veridex.security.outbound.allow-insecure-http=true")
            .withBean(OutboundAccessPolicy.class, () -> new OutboundAccessPolicy(Set.of("localhost"),
                    Set.of(11434), true));

    @Test
    void defaultProviderAssemblesOnlyDeterministicChatModel() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(DeterministicChatModel.class);
            assertThat(context.getBean(ChatModel.class)).isInstanceOf(DeterministicChatModel.class);
        });
    }

    @Test
    void ollamaProviderAssemblesOnlyOllamaChatModel() {
        runner.withPropertyValues("veridex.chat.provider=ollama").run(context -> {
            assertThat(context).hasSingleBean(ChatModel.class);
            assertThat(context).doesNotHaveBean(DeterministicChatModel.class);
            assertThat(context.getBean(ChatModel.class)).isInstanceOf(org.springframework.ai.ollama.OllamaChatModel.class);
        });
    }

    @Test
    void ollamaBaseUrlMustPassOutboundPolicy() {
        runner.withPropertyValues("veridex.chat.provider=ollama",
                        "veridex.chat.ollama.base-url=http://not-allowed.example:11434")
                .run(context -> assertThat(context).hasFailed());
    }
}
```

`OutboundAccessPolicy` 依赖 `veridex.security.outbound.*` 的 Set/boolean 绑定需要 `SecurityConfiguration` 或等价 source——若 runner 里 `OutboundAccessPolicy` 是手工 `withBean`，Set 类型可直接构造，不需要属性绑定；把上面前 3 条 `veridex.security.outbound.*` 属性删掉，仅保留手工 Bean。最终以实际编译/运行结果为准微调（保持 3 个断言语义不变）。

- [ ] **Step 9: 写 provider 合法值校验测试（RED→GREEN）**

Create `ChatProviderVerifierTest.java`：

```java
package io.veridex.generation;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.veridex.generation.infrastructure.ChatProperties;
import io.veridex.generation.infrastructure.ChatProviderConfiguration.ChatProviderVerifier;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class ChatProviderVerifierTest {

    private static ChatProviderVerifier verifier(String provider) {
        return new ChatProviderVerifier(new ChatProperties(provider, Duration.ofSeconds(60),
                new ChatProperties.Ollama("http://localhost:11434", "qwen3:8b")));
    }

    @Test
    void acceptsDeterministicAndOllama() {
        assertThatCode(() -> verifier("deterministic").afterPropertiesSet()).doesNotThrowAnyException();
        assertThatCode(() -> verifier("ollama").afterPropertiesSet()).doesNotThrowAnyException();
    }

    @Test
    void rejectsUnknownProvider() {
        assertThatThrownBy(() -> verifier("gpt-4").afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("gpt-4");
    }
}
```

若 `ChatProviderVerifier` 是 `ChatProviderConfiguration` 的嵌套 static 类，测试按嵌套类引用（如上）。若改为独立 top-level 类，调整 import。Run: `./mvnw -f backend/pom.xml -pl . test -Dtest='ChatProviderVerifierTest,ChatProviderSelectionTest' -Dsurefire.failIfNoSpecifiedTests=false` Expected: PASS。

- [ ] **Step 10: BoundedModelTags 收敛 provider + 模型名归一化**

Modify `BoundedModelTags.java`：构造参数语义改为 approved providers；2 参 resolve 用正则归一化模型名，1 参 resolve 不变：

```java
public final class BoundedModelTags {

    private static final String UNKNOWN = "unknown";
    // 受控/归一化模型名：字母数字开头，仅含 [A-Za-z0-9:_.-]，长度 ≤64；URL/异常文本一律 unknown
    private static final java.util.regex.Pattern MODEL_NAME = java.util.regex.Pattern.compile("^[A-Za-z0-9][A-Za-z0-9:_.-]{0,63}$");

    private final Set<String> approvedProviders;

    public BoundedModelTags(Set<String> approvedProviders) {
        this.approvedProviders = Set.copyOf(Objects.requireNonNull(approvedProviders));
    }

    public ModelTags resolve(String model) {
        String resolved = approvedProviders.contains(model) ? model : UNKNOWN;
        return new ModelTags(TelemetryTag.model(resolved));
    }

    public ModelTags resolve(String provider, String model) {
        String resolvedProvider = approvedProviders.contains(provider) ? provider : UNKNOWN;
        String normalizedModel = MODEL_NAME.matcher(model == null ? "" : model).matches() ? model : UNKNOWN;
        return new ModelTags(TelemetryTag.provider(resolvedProvider.toLowerCase(Locale.ROOT)),
                TelemetryTag.model(normalizedModel.toLowerCase(Locale.ROOT)));
    }
    // ModelTags record 不变
}
```

Modify `ObservabilityPrimitivesTest.java` 追加：

```java
@Test
void providerIsBoundedAndModelNameIsNormalized() {
    BoundedModelTags tags = new BoundedModelTags(Set.of("deterministic", "ollama"));
    assertThat(tags.resolve("ollama", "qwen3:8b").model()).isEqualTo("qwen3:8b");
    assertThat(tags.resolve("ollama", "qwen3:8b").tags()[0].value()).isEqualTo("ollama");
    assertThat(tags.resolve("ollama", "http://evil.example/x?q=1").model()).isEqualTo("unknown");
    assertThat(tags.resolve("gpt-4", "qwen3:8b").tags()[0].value()).isEqualTo("unknown");
    assertThat(tags.resolve("ollama", " qwen3:8b ").model()).isEqualTo("unknown");
}
```

先跑该新增测试确认 RED（现有断言 `unknown` 不变），再改实现到 GREEN。Run: `./mvnw -f backend/pom.xml -pl . test -Dtest='ObservabilityPrimitivesTest,BoundedModelTags' -Dsurefire.failIfNoSpecifiedTests=false` Expected: PASS。

- [ ] **Step 11: 跑 Task 1 全部相关测试并提交**

Run: `./mvnw -f backend/pom.xml -pl . test -Dtest='ChatConfigContractTest,ChatProviderSelectionTest,ChatProviderVerifierTest,ObservabilityPrimitivesTest,DeterministicChatModelTest,GenerationServiceImplTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS（GenerationServiceImplTest 此时仍依赖旧构造，不改动也应通过——若因 `ChatModel` 注入变更而失败，属 Task 2 范围，先保持 Task 1 只提交配置相关；若失败则顺延到 Task 2 一并处理，并在提交信息注明）。

```bash
git add backend/src/main/java/io/veridex/generation/infrastructure backend/src/main/resources/application.yml \
  backend/src/main/java/io/veridex/shared/observability/BoundedModelTags.java \
  backend/src/test/java/io/veridex/generation backend/src/test/java/io/veridex/shared/observability/ObservabilityPrimitivesTest.java
git commit -m "$(cat <<'EOF'
feat: conditionally assemble deterministic or ollama chat model

Co-Authored-By: CodeTui <noreply@codetui.dev>
EOF
)"
git diff --check
```

---

### Task 2: GenerationService 同步/流式统一

**Files:**
- Create: `backend/src/main/java/io/veridex/generation/api/GenerationEvent.java`
- Create: `backend/src/main/java/io/veridex/generation/application/GenerationModelException.java`
- Modify: `backend/src/main/java/io/veridex/generation/api/GenerationResult.java`
- Modify: `backend/src/main/java/io/veridex/generation/api/GenerationService.java`
- Modify: `backend/src/main/java/io/veridex/generation/application/GenerationServiceImpl.java`
- Modify: `backend/src/main/java/io/veridex/shared/observability/TelemetryErrorCode.java`
- Modify: `backend/src/main/java/io/veridex/shared/observability/MetricName.java`
- Modify: `backend/src/main/java/io/veridex/shared/observability/TelemetryOutcome.java`
- Create: `backend/src/main/resources/db/migration/V14__chat_provider_streaming.sql`
- Modify: `backend/src/main/java/io/veridex/trace/domain/GenerationRun.java`
- Modify: `backend/src/main/java/io/veridex/trace/domain/GenerationRunRepository.java`
- Modify: `backend/src/main/java/io/veridex/trace/domain/QueryRun.java`
- Modify: `backend/src/main/java/io/veridex/trace/api/QueryRunRecorder.java`
- Modify: `backend/src/main/java/io/veridex/trace/application/QueryRunRecorderImpl.java`
- Modify: `backend/src/test/java/io/veridex/generation/GenerationServiceImplTest.java`
- Create: `backend/src/test/java/io/veridex/generation/GenerationStreamingTest.java`

**Interfaces:**
- Consumes: `ChatProperties`、`ChatModel`（Task 1）、`BoundedModelTags`。
- Produces:
  - `GenerationEvent`（sealed）：`record Delta(String text)`、`record Completed(GenerationResult result)`、`record Refused(GenerationResult result)`。
  - `GenerationService.stream(String question, List<EvidencePiece> evidence, List<MessageRecord> history) : Flux<GenerationEvent>`。
  - `GenerationResult(String answer, List<CitationView> citations, RefusalReason refusalReason, String provider, String model, int inputTokens, int outputTokens, long durationMs, long firstTokenLatencyMs, boolean usageEstimated, String contextHash, List<PromptMessageView> promptMessages)` + 保留 8 参便捷构造（provider="deterministic", firstTokenLatencyMs=0, usageEstimated=true）。
  - `GenerationModelException` 家族：`ModelTimeoutException`、`ModelEmptyException`、`InvalidCitationException`（均 `extends RuntimeException`，消息不含敏感内容）。
  - `TelemetryErrorCode`：新增 `MODEL_TIMEOUT("model_timeout")`、`INVALID_CITATION("invalid_citation")`，加入 `persistedQueryRunCodes()`；`classify()` 对 `ModelTimeoutException`→MODEL_TIMEOUT、`InvalidCitationException`→INVALID_CITATION、`ModelEmptyException`→MODEL_ERROR。
  - `MetricName`：新增 `GENERATION_FIRST_TOKEN("veridex.generation.first_token")`、`GENERATION_OUTCOME("veridex.generation.outcome")`。
  - `TelemetryOutcome.Generation`：`SUCCESS, ERROR, REFUSED, CANCELLED`。
  - `QueryRunRecorder`：`void markGenerating(UUID runId)`（仅状态）；`void recordGeneration(UUID runId, GenerationRecord gen)`（插入或更新行）；`GenerationRecord(String provider, String model, int inputTokens, int outputTokens, long durationMs, long firstTokenLatencyMs, String degradation, String contextHash)`。
  - `QueryRun.cancel()`/`fail()` 对已终态（COMPLETED/REFUSED/FAILED/CANCELLED）幂等跳过。

- [ ] **Step 1: 写失败的事件/异常/指标测试基座（RED）**

Create `GenerationStreamingTest.java`（先写关键行为，实现后续补齐）：

```java
package io.veridex.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.veridex.conversation.api.MessageRecord;
import io.veridex.generation.api.GenerationEvent;
import io.veridex.generation.api.GenerationParameters;
import io.veridex.generation.api.GenerationResult;
import io.veridex.generation.application.CitationValidator;
import io.veridex.generation.application.GenerationServiceImpl;
import io.veridex.generation.application.ModelEmptyException;
import io.veridex.generation.application.ModelTimeoutException;
import io.veridex.generation.application.RefusalPolicy;
import io.veridex.generation.infrastructure.ChatProperties;
import io.veridex.knowledge.api.DocumentVersionQuery;
import io.veridex.retrieval.api.EvidencePiece;
import io.veridex.shared.RefusalReason;
import io.veridex.shared.observability.VeridexObservability;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class GenerationStreamingTest {

    @Mock ChatModel model;
    @Mock RefusalPolicy refusalPolicy;
    @Mock CitationValidator citationValidator;
    @Mock DocumentVersionQuery documentVersions;
    ChatProperties chatProperties = new ChatProperties("deterministic", Duration.ofSeconds(60),
            new ChatProperties.Ollama("http://localhost:11434", "qwen3:8b"));

    private GenerationServiceImpl service() {
        return new GenerationServiceImpl(model, refusalPolicy, citationValidator, documentVersions,
                new VeridexObservability(new SimpleMeterRegistry(), ObservationRegistry.create()), chatProperties);
    }

    private static EvidencePiece evidence() {
        return new EvidencePiece(1, UUID.randomUUID(), UUID.randomUUID(), 0, "请假制度", "1",
                "员工请假需提前两个工作日向直属主管提交书面申请，经审批后生效。");
    }

    @Test
    void streamsDeltasInOrderThenCompletes() {
        when(refusalPolicy.evaluate(any(), eq(50))).thenReturn(null);
        when(citationValidator.validate(any(), any(), any())).thenReturn(List.of());
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(
                new ChatResponse(List.of(new Generation(new AssistantMessage("根据"))),
                        metadataWithUsage(1, 1)),
                new ChatResponse(List.of(new Generation(new AssistantMessage("《请假制度》[1]"))),
                        metadataWithUsage(1, 3))));

        StepVerifier.create(service().stream("请假", List.of(evidence()), List.of()))
                .expectNextMatches(e -> e instanceof GenerationEvent.Delta d && d.text().equals("根据"))
                .expectNextMatches(e -> e instanceof GenerationEvent.Delta d && d.text().equals("《请假制度》[1]"))
                .expectNextMatches(e -> e instanceof GenerationEvent.Completed c
                        && c.result().answer().equals("根据《请假制度》[1]")
                        && c.result().firstTokenLatencyMs() >= 0
                        && !c.result().usageEstimated())
                .verifyComplete();
    }

    @Test
    void filtersBlankFragments() {
        when(refusalPolicy.evaluate(any(), eq(50))).thenReturn(null);
        when(citationValidator.validate(any(), any(), any())).thenReturn(List.of());
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(
                new ChatResponse(List.of(new Generation(new AssistantMessage("  ")))),
                new ChatResponse(List.of(new Generation(new AssistantMessage("答案"))))));

        StepVerifier.create(service().stream("q", List.of(evidence()), List.of()))
                .expectNextMatches(e -> e instanceof GenerationEvent.Delta d && d.text().equals("答案"))
                .expectNextMatches(e -> e instanceof GenerationEvent.Completed)
                .verifyComplete();
    }

    @Test
    void estimatesUsageWhenProviderOmitsMetadata() {
        when(refusalPolicy.evaluate(any(), eq(50))).thenReturn(null);
        when(citationValidator.validate(any(), any(), any())).thenReturn(List.of());
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(
                new ChatResponse(List.of(new Generation(new AssistantMessage("答案[1]"))))));

        StepVerifier.create(service().stream("q", List.of(evidence()), List.of()))
                .expectNextMatches(e -> e instanceof GenerationEvent.Completed c && c.result().usageEstimated())
                .verifyComplete();
    }

    @Test
    void invalidCitationEmitsErrorNotCompleted() {
        when(refusalPolicy.evaluate(any(), eq(50))).thenReturn(null);
        when(citationValidator.validate(any(), any(), any())).thenReturn(List.of(
                new io.veridex.generation.api.CitationView(9, null, UUID.randomUUID(), 0, "t", "[9]", "INVALID")));
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(
                new ChatResponse(List.of(new Generation(new AssistantMessage("没有引用[9]"))))));

        StepVerifier.create(service().stream("q", List.of(evidence()), List.of()))
                .expectNextMatches(e -> e instanceof GenerationEvent.Delta)
                .expectErrorMatches(t -> t instanceof io.veridex.generation.application.InvalidCitationException)
                .verify();
    }

    @Test
    void timeoutMapsToModelTimeoutError() {
        when(refusalPolicy.evaluate(any(), eq(50))).thenReturn(null);
        when(model.stream(any(Prompt.class))).thenReturn(Flux.never());
        ChatProperties shortTimeout = new ChatProperties("deterministic", Duration.ofMillis(50),
                new ChatProperties.Ollama("http://localhost:11434", "qwen3:8b"));
        GenerationServiceImpl timed = new GenerationServiceImpl(model, refusalPolicy, citationValidator,
                documentVersions, new VeridexObservability(new SimpleMeterRegistry(), ObservationRegistry.create()),
                shortTimeout);

        StepVerifier.create(timed.stream("q", List.of(evidence()), List.of()))
                .expectErrorMatches(t -> t instanceof ModelTimeoutException)
                .verify();
    }

    @Test
    void emptyResponseMapsToModelError() {
        when(refusalPolicy.evaluate(any(), eq(50))).thenReturn(null);
        when(model.stream(any(Prompt.class))).thenReturn(Flux.empty());

        StepVerifier.create(service().stream("q", List.of(evidence()), List.of()))
                .expectErrorMatches(t -> t instanceof ModelEmptyException)
                .verify();
    }

    @Test
    void refusalEmitsRefusedWithoutCallingModel() {
        when(refusalPolicy.evaluate(any(), eq(50))).thenReturn(RefusalReason.NO_RELEVANT_EVIDENCE);

        StepVerifier.create(service().stream("q", List.of(), List.of()))
                .expectNextMatches(e -> e instanceof GenerationEvent.Refused r
                        && r.result().refusalReason() == RefusalReason.NO_RELEVANT_EVIDENCE)
                .verifyComplete();
    }

    private static ChatResponse metadataWithUsage(int prompt, int completion) {
        // spring-ai 2.0.0：ChatResponseMetadata 在 chat.metadata 包；DefaultUsage 只有构造器、无 builder
        var metadata = org.springframework.ai.chat.metadata.ChatResponseMetadata.builder()
                .usage(new org.springframework.ai.chat.metadata.DefaultUsage(prompt, completion, prompt + completion))
                .build();
        return new ChatResponse(List.of(new Generation(new AssistantMessage("x"))), metadata);
    }
}
```

同时 create `GenerationModelException.java`：

```java
package io.veridex.generation.application;

/** 模型失败族（设计 §5.1/§5.2/§4.5）：无回退，错误经 Flux error channel 传播。 */
public class GenerationModelException extends RuntimeException {

    public GenerationModelException(String message) {
        super(message);
    }
}
```

（`ModelTimeoutException`、`ModelEmptyException`、`InvalidCitationException` 均 `extends GenerationModelException`，加进同一文件会编译报「多个 public 类」——因此把它们放进独立的 `GenerationModelException.java` 内部 static 类，或独立文件。**决定：独立文件**——`ModelTimeoutException.java`、`ModelEmptyException.java`、`InvalidCitationException.java`，各 3 行 extends。测试 import 对应类。）

- [ ] **Step 2: 运行确认 RED（编译失败）**

Run: `./mvnw -f backend/pom.xml -pl . test -Dtest=GenerationStreamingTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL——`GenerationServiceImpl` 尚无 stream/新构造、`ChatResponseMetadata` 用法未对齐、异常类未建。逐项补齐（Step 3–5）。

- [ ] **Step 3: 实现 GenerationEvent / GenerationResult / GenerationService**

Create `GenerationEvent.java`：

```java
package io.veridex.generation.api;

/**
 * 生成生命周期事件（设计 §4.2）：错误经 Flux error channel 传播，取消经 Reactor cancel 传播，
 * 不伪造 Completed。Completed 只在完整文本已聚合、引用已校验、元数据已收尾后发出。
 */
public sealed interface GenerationEvent {

    record Delta(String text) implements GenerationEvent {
    }

    record Completed(GenerationResult result) implements GenerationEvent {
    }

    record Refused(GenerationResult result) implements GenerationEvent {
    }
}
```

Modify `GenerationResult.java`（保留旧 8 参构造，避免评测/测试大改）：

```java
public record GenerationResult(String answer, List<CitationView> citations, RefusalReason refusalReason,
                               String provider, String model, int inputTokens, int outputTokens,
                               long durationMs, long firstTokenLatencyMs, boolean usageEstimated,
                               String contextHash, List<PromptMessageView> promptMessages) {

    public GenerationResult(String answer, List<CitationView> citations, RefusalReason refusalReason,
                            String model, int inputTokens, int outputTokens, long durationMs, String contextHash) {
        this(answer, citations, refusalReason, "deterministic", model, inputTokens, outputTokens,
                durationMs, 0, true, contextHash, List.of());
    }
}
```

Modify `GenerationService.java` 追加：

```java
    /**
     * 流式生成（设计 D6/D7）：返回生成生命周期事件，不暴露 Spring AI 类型。
     * cold、单次订阅；错误走 error channel；引用终检失败抛 InvalidCitationException。
     */
    reactor.core.publisher.Flux<GenerationEvent> stream(String question, List<EvidencePiece> evidence,
                                                         List<MessageRecord> history);
```

- [ ] **Step 4: 重写 GenerationServiceImpl（同步 + 流式共享逻辑）**

Modify `GenerationServiceImpl.java`：

```java
package io.veridex.generation.application;

import io.veridex.conversation.api.MessageRecord;
import io.veridex.generation.api.CitationView;
import io.veridex.generation.api.GenerationEvent;
import io.veridex.generation.api.GenerationParameters;
import io.veridex.generation.api.GenerationResult;
import io.veridex.generation.api.PromptMessageView;
import io.veridex.generation.api.GenerationService;
import io.veridex.generation.infrastructure.ChatProperties;
import io.veridex.knowledge.api.DocumentVersionQuery;
import io.veridex.retrieval.api.EvidencePiece;
import io.veridex.shared.RefusalReason;
import io.veridex.shared.observability.BoundedModelTags;
import io.veridex.shared.observability.MetricName;
import io.veridex.shared.observability.ObservationName;
import io.veridex.shared.observability.TelemetryErrorCode;
import io.veridex.shared.observability.TelemetryOutcome;
import io.veridex.shared.observability.TelemetryTag;
import io.veridex.shared.observability.VeridexObservability;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

@Service
public class GenerationServiceImpl implements GenerationService {

    private static final String DEFAULT_SYSTEM_TEMPLATE =
            "你是企业制度问答助手。只允许使用以下证据回答，不得使用模型通用知识补全。\n";
    private static final GenerationParameters DEFAULT_PARAMETERS =
            new GenerationParameters(50, DEFAULT_SYSTEM_TEMPLATE, "deterministic");

    private final ChatModel model;
    private final ChatProperties chatProperties;
    private final RefusalPolicy refusalPolicy;
    private final CitationValidator citationValidator;
    private final DocumentVersionQuery documentVersions;
    private final VeridexObservability observability;
    private final BoundedModelTags modelTags;

    public GenerationServiceImpl(ChatModel model, RefusalPolicy refusalPolicy,
                                 CitationValidator citationValidator, DocumentVersionQuery documentVersions,
                                 VeridexObservability observability, ChatProperties chatProperties) {
        this.model = model;
        this.refusalPolicy = refusalPolicy;
        this.citationValidator = citationValidator;
        this.documentVersions = documentVersions;
        this.observability = observability;
        this.chatProperties = chatProperties;
        this.modelTags = new BoundedModelTags(java.util.Set.of("deterministic", "ollama"));
    }

    // ---------- 同步入口（评测继续使用） ----------

    @Override
    public GenerationResult generate(String question, List<EvidencePiece> evidence, List<MessageRecord> history) {
        return generate(question, evidence, history, DEFAULT_PARAMETERS);
    }

    @Override
    public GenerationResult generate(String question, List<EvidencePiece> evidence,
                                     List<MessageRecord> history, GenerationParameters parameters) {
        RefusalReason refusal = refusalPolicy.evaluate(evidence, parameters.minEvidenceChars());
        if (refusal != null) {
            GenerationResult result = refusedResult(parameters, refusal);
            observability.increment(MetricName.GENERATION_OUTCOME,
                    TelemetryTag.generationOutcome(TelemetryOutcome.Generation.REFUSED),
                    providerTag());
            return result;
        }

        String system = buildSystemPrompt(evidence, parameters.systemTemplate());
        Prompt prompt = buildPrompt(system, history, question);
        List<PromptMessageView> promptMessages = promptViews(prompt);

        long start = System.nanoTime();
        var observation = observability.start(ObservationName.GENERATION_MODEL, modelTags.resolve(provider(), modelName()).tags());
        ChatResponse response;
        try {
            response = model.call(prompt);
            observation.success(TelemetryTag.generationOutcome(TelemetryOutcome.Generation.SUCCESS));
        } catch (RuntimeException e) {
            observation.failure(TelemetryErrorCode.classify(e));
            throw e;
        } finally {
            observation.close();
        }
        long durationMs = (System.nanoTime() - start) / 1_000_000;
        String answer = response.getResult().getOutput().getText();
        Usage usage = extractUsage(response, system.length() + question.length(), answer);
        return finalizeResult(answer, evidence, provider(), modelName(), usage, durationMs, 0, promptMessages);
    }

    // ---------- 流式入口（在线问答） ----------

    @Override
    public Flux<GenerationEvent> stream(String question, List<EvidencePiece> evidence, List<MessageRecord> history) {
        RefusalReason refusal = refusalPolicy.evaluate(evidence, DEFAULT_PARAMETERS.minEvidenceChars());
        if (refusal != null) {
            GenerationResult result = refusedResult(DEFAULT_PARAMETERS, refusal);
            observability.increment(MetricName.GENERATION_OUTCOME,
                    TelemetryTag.generationOutcome(TelemetryOutcome.Generation.REFUSED), providerTag());
            return Flux.just(new GenerationEvent.Refused(result));
        }

        String system = buildSystemPrompt(evidence, DEFAULT_PARAMETERS.systemTemplate());
        Prompt prompt = buildPrompt(system, history, question);
        List<PromptMessageView> promptMessages = promptViews(prompt);
        long start = System.nanoTime();
        AtomicLong firstTokenLatencyMs = new AtomicLong(-1);
        StringBuilder buffer = new StringBuilder();
        AtomicReference<ChatResponse> lastResponse = new AtomicReference<>();
        var observation = observability.start(ObservationName.GENERATION_MODEL,
                modelTags.resolve(provider(), modelName()).tags());

        Flux<ChatResponse> upstream = model.stream(prompt)
                .timeout(chatProperties.timeout(),
                        Flux.error(new ModelTimeoutException("model timeout")))
                .doOnNext(response -> {
                    lastResponse.set(response);
                    String text = response.getResult().getOutput().getText();
                    if (text != null && !text.isEmpty()) {
                        if (firstTokenLatencyMs.get() < 0) {
                            firstTokenLatencyMs.set((System.nanoTime() - start) / 1_000_000);
                        }
                        buffer.append(text);
                    }
                });

        return upstream
                .map(response -> response.getResult().getOutput().getText())
                .filter(text -> text != null && !text.isBlank())
                .map(text -> (GenerationEvent) new GenerationEvent.Delta(text))
                .concatWith(Flux.defer(() -> {
                    long elapsedMs = (System.nanoTime() - start) / 1_000_000;
                    long firstToken = firstTokenLatencyMs.get() < 0 ? elapsedMs : firstTokenLatencyMs.get();
                    String answer = buffer.toString();
                    if (answer.isBlank()) {
                        observation.failure(TelemetryErrorCode.MODEL_ERROR);
                        throw new ModelEmptyException("empty model response");
                    }
                    Usage usage = extractUsage(lastResponse.get(), system.length() + question.length(), answer);
                    GenerationResult result = finalizeResult(answer, evidence, provider(), modelName(),
                            usage, elapsedMs, firstToken, promptMessages);
                    observation.success(TelemetryTag.generationOutcome(TelemetryOutcome.Generation.SUCCESS));
                    observability.increment(MetricName.GENERATION_OUTCOME,
                            TelemetryTag.generationOutcome(TelemetryOutcome.Generation.SUCCESS), providerTag());
                    observability.record(MetricName.GENERATION_FIRST_TOKEN, firstToken, providerTag());
                    return Flux.just(new GenerationEvent.Completed(result));
                }))
                .doOnError(e -> observation.failure(TelemetryErrorCode.classify(e)));
    }

    // ---------- 共享私有逻辑 ----------

    private GenerationResult refusedResult(GenerationParameters parameters, RefusalReason refusal) {
        return new GenerationResult(null, List.of(), refusal, provider(), modelName(), 0, 0, 0, 0, true, null, List.of());
    }

    private GenerationResult finalizeResult(String answer, List<EvidencePiece> evidence, String provider,
                                            String model, Usage usage, long durationMs, long firstTokenLatencyMs,
                                            List<PromptMessageView> promptMessages) {
        List<CitationView> citations = citationValidator.validate(answer, evidence,
                documentIdByVersionId(evidence));
        boolean validCitations = citations.stream().allMatch(c -> "VALID".equals(c.validationStatus()));
        if (!validCitations) {
            throw new InvalidCitationException("answer failed citation final check");
        }
        String contextHash = Integer.toHexString(evidence.hashCode());
        return new GenerationResult(answer, citations, null, provider, model, usage.inputTokens(),
                usage.outputTokens(), durationMs, firstTokenLatencyMs, usage.estimated(), contextHash, promptMessages);
    }

    private String provider() {
        return chatProperties.provider();
    }

    private String modelName() {
        return "ollama".equals(chatProperties.provider())
                ? chatProperties.ollama().model() : "deterministic";
    }

    private TelemetryTag providerTag() {
        // 从受控 BoundedModelTags.resolve 推导（tags()[0] 为 provider 标签），避免绕过 provider 白名单
        return modelTags.resolve(provider(), modelName()).tags()[0];
    }

    private record Usage(int inputTokens, int outputTokens, boolean estimated) {
    }

    private Usage extractUsage(ChatResponse response, int inputChars, String answer) {
        var metadata = response != null && response.getMetadata() != null ? response.getMetadata() : null;
        var usage = metadata != null ? metadata.getUsage() : null;
        if (usage != null && usage.getPromptTokens() != null && usage.getCompletionTokens() != null
                && usage.getPromptTokens() > 0 && usage.getCompletionTokens() > 0) {
            return new Usage(usage.getPromptTokens(), usage.getCompletionTokens(), false);
        }
        return new Usage(Math.max(1, inputChars / 4), Math.max(1, answer.length() / 4), true);
    }

    private Prompt buildPrompt(String system, List<MessageRecord> history, String question) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(system));
        for (MessageRecord record : history) {
            messages.add(record.role().equals("USER")
                    ? new UserMessage(record.content())
                    : new AssistantMessage(record.content()));
        }
        messages.add(new UserMessage(question));
        return new Prompt(messages);
    }

    private List<PromptMessageView> promptViews(Prompt prompt) {
        return prompt.getInstructions().stream()
                .map(message -> new PromptMessageView(message.getMessageType().getValue(), message.getText()))
                .toList();
    }

    private Map<UUID, UUID> documentIdByVersionId(List<EvidencePiece> evidence) {
        List<UUID> versionIds = evidence.stream().map(EvidencePiece::documentVersionId).distinct().toList();
        return documentVersions.findDocumentIdByVersionIds(versionIds);
    }

    private String buildSystemPrompt(List<EvidencePiece> evidence, String systemTemplate) {
        StringBuilder sb = new StringBuilder(systemTemplate);
        for (EvidencePiece e : evidence) {
            sb.append("[EVIDENCE ").append(e.citationIndex()).append("|").append(e.title())
                    .append("|").append(e.text()).append("]\n");
        }
        return sb.toString();
    }
}
```

说明：
- `ChatResponseMetadata` 的 getter 名（`getUsage()`、`DefaultUsage.builder().promptTokens(...)`）以 spring-ai 2.0.0 实际 API 为准——Step 5 跑编译时若签名不符（如 `getPromptTokens()` 返回 `Integer` 或 Builder 方法名不同），按 jar 内 `ChatResponseMetadata/DefaultUsage` 源码微调，断言语义不变。
- 空响应/引用失败在 `concatWith` 的 defer 里抛异常 → 经 error channel 传播（`doOnError` 记录 failure 后继续传播）。
- 流式与同步的「引用终检」语义一致：全部 VALID 才 Completed，否则异常（设计 4.5/9.1）。

- [ ] **Step 5: 补齐异常类与观测枚举，跑编译+单测到 GREEN**

Create 三个异常（`generation/application/`）：

```java
// ModelTimeoutException.java
package io.veridex.generation.application;

public class ModelTimeoutException extends GenerationModelException {
    public ModelTimeoutException(String message) {
        super(message);
    }
}
```

`ModelEmptyException`、`InvalidCitationException` 同上（仅类名与 extends 不同）。

Modify `TelemetryErrorCode.java`：

```java
    MODEL_ERROR("model_error"),
    MODEL_TIMEOUT("model_timeout"),
    INVALID_CITATION("invalid_citation"),
```

`persistedQueryRunCodes()` 追加 `MODEL_TIMEOUT.name()`、`INVALID_CITATION.name()`；`classify(Exception)` 追加：

```java
        if (exception instanceof io.veridex.generation.application.ModelTimeoutException) {
            return MODEL_TIMEOUT;
        }
        if (exception instanceof io.veridex.generation.application.InvalidCitationException) {
            return INVALID_CITATION;
        }
        if (exception instanceof io.veridex.generation.application.GenerationModelException) {
            return MODEL_ERROR;
        }
```

Modify `MetricName.java` 追加：

```java
    GENERATION_FIRST_TOKEN("veridex.generation.first_token"),
    GENERATION_OUTCOME("veridex.generation.outcome"),
```

Modify `TelemetryOutcome.Generation` 为 `SUCCESS, ERROR, REFUSED, CANCELLED`。

Run: `./mvnw -f backend/pom.xml -pl . test -Dtest='GenerationStreamingTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS。API 已核实：`ChatResponse(List<Generation>, ChatResponseMetadata)` 构造存在；`ChatResponseMetadata` 位于 `org.springframework.ai.chat.metadata`；`DefaultUsage` 无 builder、用 `new DefaultUsage(prompt, completion, total)`。

- [ ] **Step 6: 更新 GenerationServiceImplTest（同步路径回归）**

Modify `GenerationServiceImplTest.java`：
- `@Mock DeterministicChatModel model;` → `@Mock ChatModel model;`
- `@InjectMocks GenerationServiceImpl service;` 保持（新增的 `ChatProperties` 由 Mockito 自动 mock——Mockito 对 record 的 mock 会返回默认值，`provider()` 返回 null 会破坏标签；改为在测试里显式 `@Mock ChatProperties chatProperties;` + `when(chatProperties.provider()).thenReturn("deterministic")`，或构造时注入。**决定**：`@Mock ChatProperties chatProperties;` + 每个用例前 `when(chatProperties.provider()).thenReturn("deterministic")`，`ollama()` 不用（同步路径 only）。）
- `recordsModelFailureWithBoundedTags` 里的手工构造改为 6 参新构造：
  `new GenerationServiceImpl(model, refusalPolicy, citationValidator, documentVersions, new VeridexObservability(meters, ObservationRegistry.create()), chatProperties)`，并把 `when(chatProperties.provider()).thenReturn("deterministic")` 放该用例。

Run: `./mvnw -f backend/pom.xml -pl . test -Dtest='GenerationServiceImplTest,GenerationStreamingTest' -Dsurefire.failIfNoSpecifiedTests=false` Expected: PASS。

- [ ] **Step 7: 写 trace 迁移与 recorder 变更（RED→GREEN）**

Create `V14__chat_provider_streaming.sql`：

```sql
ALTER TABLE generation_run ADD COLUMN provider VARCHAR(20) NOT NULL DEFAULT 'deterministic';
ALTER TABLE generation_run ADD COLUMN first_token_latency_ms BIGINT NOT NULL DEFAULT 0;
```

Modify `GenerationRun.java`：新增字段

```java
    @Column(nullable = false, length = 20)
    private String provider = "deterministic";

    @Column(name = "first_token_latency_ms", nullable = false)
    private long firstTokenLatencyMs;
```

构造器改为 `(UUID queryRunId, String provider, String model, int inputTokens, int outputTokens, long durationMs, long firstTokenLatencyMs, String degradation, String contextHash)`，并加：

```java
    public void applyMetrics(int inputTokens, int outputTokens, long durationMs,
                             long firstTokenLatencyMs, String degradation, String contextHash) {
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.durationMs = durationMs;
        this.firstTokenLatencyMs = firstTokenLatencyMs;
        this.degradation = degradation;
        this.contextHash = contextHash;
    }
```

（`provider` 提供 getter。）

Modify `GenerationRunRepository.java`：

```java
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GenerationRunRepository extends JpaRepository<GenerationRun, UUID> {
    Optional<GenerationRun> findFirstByQueryRunId(UUID queryRunId);
}
```

Modify `QueryRunRecorder.java`：

```java
    void markGenerating(UUID runId);

    void recordGeneration(UUID runId, GenerationRecord gen);
```

`GenerationRecord` 改为：

```java
    record GenerationRecord(String provider, String model, int inputTokens, int outputTokens,
                            long durationMs, long firstTokenLatencyMs, String degradation, String contextHash) {
    }
```

Modify `QueryRunRecorderImpl.java`：

```java
    @Override
    public void markGenerating(UUID runId) {
        runs.findById(runId).ifPresent(run -> run.mark(QueryRun.Status.GENERATING));
    }

    @Override
    public void recordGeneration(UUID runId, GenerationRecord g) {
        gens.findFirstByQueryRunId(runId).ifPresentOrElse(
                existing -> existing.applyMetrics(g.inputTokens(), g.outputTokens(), g.durationMs(),
                        g.firstTokenLatencyMs(), g.degradation(), g.contextHash()),
                () -> gens.save(new GenerationRun(runId, g.provider(), g.model(), g.inputTokens(),
                        g.outputTokens(), g.durationMs(), g.firstTokenLatencyMs(), g.degradation(), g.contextHash())));
    }
```

Modify `QueryRun.java`——取消/失败幂等：

```java
    public void fail(String error) {
        if (isTerminal()) {
            return;
        }
        this.status = Status.FAILED;
        this.error = error;
        this.completedAt = Instant.now();
    }

    public void cancel() {
        if (isTerminal()) {
            return;
        }
        this.status = Status.CANCELLED;
        this.completedAt = Instant.now();
    }

    private boolean isTerminal() {
        return status == Status.COMPLETED || status == Status.REFUSED
                || status == Status.FAILED || status == Status.CANCELLED;
    }
```

Modify `backend/src/test/java/io/veridex/trace/QueryRunRecorderImplTest.java`（该文件已存在）：
- `markGeneratingPersistsGenerationRun` 改为新签名：`recorder.markGenerating(RUN)`（断言状态 GENERATING、不再 save GenerationRun）；旧 `GenerationRecord("deterministic", 10, 20, 5, null, "abc")` 6 参构造删除。
- 新增 `recordGenerationCreatesThenUpdatesSingleRow`：

```java
    @Test
    void recordGenerationCreatesThenUpdatesSingleRow() {
        var run = new QueryRun(USER, null, "q", "q", List.of());
        when(runs.findById(RUN)).thenReturn(Optional.of(run));
        var first = new GenerationRecord("deterministic", "deterministic", 10, 20, 5, 3, null, "abc");
        var second = new GenerationRecord("deterministic", "deterministic", 30, 40, 9, 7, null, "abc");
        var existing = new io.veridex.trace.domain.GenerationRun(RUN, "deterministic", "deterministic", 0, 0, 0, 0, null, null);
        when(gens.findFirstByQueryRunId(RUN)).thenReturn(Optional.empty(), Optional.of(existing));
        recorder.recordGeneration(RUN, first);
        recorder.recordGeneration(RUN, second);
        verify(gens, times(1)).save(any());
        assertThat(existing.getInputTokens()).isEqualTo(30);
        assertThat(existing.getOutputTokens()).isEqualTo(40);
        assertThat(existing.getFirstTokenLatencyMs()).isEqualTo(7);
    }
```

- 新增 `cancelDoesNotOverrideCompleted`：

```java
    @Test
    void cancelDoesNotOverrideCompleted() {
        var completed = new QueryRun(USER, null, "q", "q", List.of());
        completed.complete();
        when(runs.findById(RUN)).thenReturn(Optional.of(completed));
        recorder.cancel(RUN);
        assertThat(completed.getStatus()).isEqualTo(QueryRun.Status.COMPLETED);
    }
```

`GenerationRun` 需新增 `getInputTokens/getOutputTokens/getFirstTokenLatencyMs` getter（Step 7 实体变更里一并加）。`recordGeneration` 首次调用走 `findFirstByQueryRunId→empty→save`，第二次走 `existing.applyMetrics`——两次调用不产生第二行。

Run: `./mvnw -f backend/pom.xml -pl . test -Dtest='QueryRunRecorderImplTest,DatabaseMigrationTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS（DatabaseMigrationTest 需要 compose 容器在跑——若容器未启动按 `backend/README` 或既有流程先起 `docker compose -f deploy/compose/compose.yml up -d postgres`；容器不可用则跳过该测试并在提交说明注明）。

- [ ] **Step 8: 全量编译 + 相关单测 + 提交**

Run: `./mvnw -f backend/pom.xml -pl . test -Dtest='GenerationServiceImplTest,GenerationStreamingTest,QueryRunRecorderImplTest,CitationValidatorTest,RefusalPolicyTest,ObservabilityPrimitivesTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS。再 `./mvnw -f backend/pom.xml -pl . -DskipTests compile` 确认 EvaluationRunService 等编译不受 GenerationResult 变更影响。

```bash
git add backend/src/main/java backend/src/main/resources/db/migration/V14__chat_provider_streaming.sql backend/src/test/java/io/veridex/generation backend/src/test/java/io/veridex/trace
git commit -m "$(cat <<'EOF'
feat: unify generation sync and streaming with real chat model

Co-Authored-By: CodeTui <noreply@codetui.dev>
EOF
)"
git diff --check
```

---

### Task 3: QA 编排与 Controller 改为端到端 Flux

**Files:**
- Modify: `backend/src/main/java/io/veridex/qa/application/QuestionAnsweringService.java`
- Modify: `backend/src/main/java/io/veridex/qa/application/QuestionAnsweringServiceImpl.java`
- Modify: `backend/src/main/java/io/veridex/qa/api/QaController.java`
- Modify: `backend/src/test/java/io/veridex/qa/QuestionAnsweringServiceTest.java`
- Modify: `backend/src/test/java/io/veridex/qa/QaTestFixture.java`
- Modify: `backend/src/test/java/io/veridex/qa/QaApiIntegrationTest.java`

**Interfaces:**
- Consumes: `GenerationService.stream(...)`（Task 2）、`QueryRunRecorder.markGenerating/recordGeneration`、`TraceBodyCapture`。
- Produces:
  - `QuestionAnsweringService.ask(UUID userId, AskRequest request) : Flux<QaEvent>`（cold，单订阅）。
  - `QaController.ask(...) : Flux<ServerSentEvent<QaEvent>>`，事件名映射保持 `run.started/retrieval.completed/answer.delta/citation.available/answer.completed/answer.refused/run.failed`。
  - 错误映射：`InvalidCitationException` → runId 标 FAILED、trace 码 `INVALID_CITATION`、`run.failed` 消息「回答未通过引用校验，未保存本次结果」；`GenerationModelException` → FAILED、`MODEL_ERROR`/`MODEL_TIMEOUT`、消息「模型服务暂时不可用，请稍后重试」；其余运行时异常 → FAILED、原 classify、安全消息「系统错误，请稍后重试」。

- [ ] **Step 1: 写失败的服务契约测试（RED）**

Modify `QuestionAnsweringServiceTest.java` 改为 StepVerifier 风格，新增取消/终检用例。核心形态：

```java
    @Test
    void happyPathEmitsStreamingEventsWithCitations() {
        UUID ver = UUID.randomUUID();
        var evidence = List.of(new EvidencePiece(1, KB, ver, 0, "请假制度", "1",
                "员工请假需提前两个工作日提交申请，经审批后生效。"));
        var searchResult = new HybridSearchResult(evidence, List.of(new RankedHitView(
                KB, ver, 0, "BM25", 2.0, null, 2.0, 1, true, null)), List.of());
        when(knowledgeScope.resolve(USER, List.of(KB))).thenReturn(List.of(KB));
        when(conversations.findOwned(USER, CONV)).thenReturn(Optional.of(new ConversationView(CONV, "t", java.time.Instant.now())));
        when(recorder.start(any(), eq(CONV), eq(List.of(KB)), any())).thenReturn(UUID.randomUUID());
        when(hybridSearch.search(USER, List.of(KB), List.of(KB), "请假")).thenReturn(searchResult);
        when(generation.stream(eq("请假"), eq(evidence), any())).thenReturn(Flux.just(
                new GenerationEvent.Delta("根据《请假制度》[1]"),
                new GenerationEvent.Completed(new GenerationResult("根据《请假制度》[1]",
                        List.of(new CitationView(1, UUID.randomUUID(), ver, 0, "请假制度", "[1]", "VALID")),
                        null, "deterministic", "deterministic", 10, 20, 5, 3, false, "abc", List.of()))));

        StepVerifier.create(service.ask(USER, new AskRequest("请假", List.of(KB), CONV)))
                .expectNextMatches(e -> e instanceof QaEvent.RunStarted)
                .expectNextMatches(e -> e instanceof QaEvent.RetrievalCompleted)
                .expectNextMatches(e -> e instanceof QaEvent.AnswerDelta)
                .expectNextMatches(e -> e instanceof QaEvent.CitationAvailable)
                .expectNextMatches(e -> e instanceof QaEvent.AnswerCompleted)
                .verifyComplete();
        verify(recorder).complete(any());
        verify(recorder).markRetrieving(any(), argThat(hits -> hits.size() == 1));
    }
```

新增用例（先写、跑 RED）：

```java
    @Test
    void invalidCitationFailsRunWithoutPersistingAssistant() {
        UUID ver = UUID.randomUUID();
        var evidence = List.of(new EvidencePiece(1, KB, ver, 0, "请假制度", "1",
                "员工请假需提前两个工作日提交申请。"));
        when(knowledgeScope.resolve(USER, List.of(KB))).thenReturn(List.of(KB));
        when(conversations.findOwned(USER, CONV)).thenReturn(Optional.of(new ConversationView(CONV, "t", java.time.Instant.now())));
        when(recorder.start(any(), eq(CONV), eq(List.of(KB)), any())).thenReturn(UUID.randomUUID());
        when(hybridSearch.search(USER, List.of(KB), List.of(KB), "请假"))
                .thenReturn(new HybridSearchResult(evidence, List.of(), List.of()));
        // 引用终检在 GenerationServiceImpl 内完成；编排层收到的是终检异常
        when(generation.stream(eq("请假"), eq(evidence), any())).thenReturn(Flux.error(
                new io.veridex.generation.application.InvalidCitationException("invalid")));

        StepVerifier.create(service.ask(USER, new AskRequest("请假", List.of(KB), CONV)))
                .expectNextMatches(e -> e instanceof QaEvent.RunStarted)
                .expectNextMatches(e -> e instanceof QaEvent.RetrievalCompleted)
                .expectNextMatches(e -> e instanceof QaEvent.RunFailed)
                .verifyComplete();
        verify(recorder).fail(any(), eq("INVALID_CITATION"));
        verify(conversations, never()).addMessage(eq(CONV), eq("ASSISTANT"), any(), any());
        verify(recorder, never()).complete(any());
    }

    @Test
    void modelErrorFailsRunWithoutPersistingAssistant() {
        when(knowledgeScope.resolve(USER, List.of(KB))).thenReturn(List.of(KB));
        when(conversations.findOwned(USER, CONV)).thenReturn(Optional.of(new ConversationView(CONV, "t", java.time.Instant.now())));
        when(recorder.start(any(), eq(CONV), eq(List.of(KB)), any())).thenReturn(UUID.randomUUID());
        when(hybridSearch.search(USER, List.of(KB), List.of(KB), "请假"))
                .thenReturn(new HybridSearchResult(List.of(evidence()), List.of(), List.of()));
        when(generation.stream(any(), any(), any()))
                .thenReturn(Flux.error(new io.veridex.generation.application.ModelTimeoutException("t")));

        StepVerifier.create(service.ask(USER, new AskRequest("请假", List.of(KB), CONV)))
                .expectNextMatches(e -> e instanceof QaEvent.RunStarted)
                .expectNextMatches(e -> e instanceof QaEvent.RetrievalCompleted)
                .expectNextMatches(e -> e instanceof QaEvent.RunFailed)
                .verifyComplete();
        verify(recorder).fail(any(), eq("MODEL_TIMEOUT"));
        verify(conversations, never()).addMessage(eq(CONV), eq("ASSISTANT"), any(), any());
    }

    @Test
    void cancellationCancelsRunAndPropagates() {
        // 装配同 happyPath；generation.stream 返回 Flux.never()。
        StepVerifier.create(service.ask(USER, new AskRequest("请假", List.of(KB), CONV)))
                .expectNextMatches(e -> e instanceof QaEvent.RunStarted)
                .expectNextMatches(e -> e instanceof QaEvent.RetrievalCompleted)
                .thenCancel()
                .verify();
        verify(recorder).cancel(any());
        verify(conversations, never()).addMessage(eq(CONV), eq("ASSISTANT"), any(), any());
    }
```

`QuestionAnsweringServiceTest` 其余旧用例（`emptyScopeRefusesAccessRestricted`、`retrievalFailureEmitsRunFailed`、`createsConversationWhenNoneProvided`、`rejectsConversationOwnedByAnotherUser`、`recordsCompletedQaRunWithBoundedTags`）同步改为 StepVerifier（`expectNext` 终端事件 + `verifyComplete`），语义不变。

- [ ] **Step 2: 运行确认 RED**

Run: `./mvnw -f backend/pom.xml -pl . test -Dtest=QuestionAnsweringServiceTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL——`ask` 仍返回 `List<QaEvent>`，StepVerifier 无法消费。

- [ ] **Step 3: 重写 QuestionAnsweringServiceImpl（cold Flux + 取消传播）**

Modify `QuestionAnsweringServiceImpl.java`（完整替换）：

```java
package io.veridex.qa.application;

import io.veridex.conversation.api.ConversationService;
import io.veridex.conversation.api.ConversationView;
import io.veridex.generation.api.CitationView;
import io.veridex.generation.api.GenerationEvent;
import io.veridex.generation.api.GenerationResult;
import io.veridex.generation.api.PromptMessageView;
import io.veridex.generation.api.GenerationService;
import io.veridex.generation.application.GenerationModelException;
import io.veridex.generation.application.InvalidCitationException;
import io.veridex.knowledge.api.KnowledgeScopeQuery;
import io.veridex.qa.api.AskRequest;
import io.veridex.qa.api.QaEvent;
import io.veridex.retrieval.api.HybridSearchResult;
import io.veridex.retrieval.api.HybridSearchService;
import io.veridex.shared.RefusalReason;
import io.veridex.shared.observability.ObservationName;
import io.veridex.shared.observability.TelemetryErrorCode;
import io.veridex.shared.observability.TelemetryOutcome;
import io.veridex.shared.observability.TelemetryTag;
import io.veridex.shared.observability.VeridexObservability;
import io.veridex.trace.api.QueryRunRecorder;
import io.veridex.trace.api.QueryRunRecorder.CitationRecord;
import io.veridex.trace.api.QueryRunRecorder.GenerationRecord;
import io.veridex.trace.api.QueryRunRecorder.RetrievalHitRecord;
import io.veridex.trace.api.TraceBodyCapture;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.scheduler.Schedulers;

/**
 * 问答编排（设计 §4.4/§4.5/§5）：cold Flux，订阅后才执行；模型流完成后再引用终检；
 * 客户端断开经 sink.onCancel 传播为上游取消并落 CANCELLED。
 */
@Service
public class QuestionAnsweringServiceImpl implements QuestionAnsweringService {

    private static final int HISTORY_TURNS = 6;

    private final KnowledgeScopeQuery knowledgeScope;
    private final ConversationService conversations;
    private final QueryRunRecorder recorder;
    private final HybridSearchService hybridSearch;
    private final GenerationService generation;
    private final VeridexObservability observability;
    private final TraceBodyCapture traceBodyCapture;

    public QuestionAnsweringServiceImpl(KnowledgeScopeQuery knowledgeScope, ConversationService conversations,
                                        QueryRunRecorder recorder, HybridSearchService hybridSearch,
                                        GenerationService generation, VeridexObservability observability,
                                        TraceBodyCapture traceBodyCapture) {
        this.knowledgeScope = knowledgeScope;
        this.conversations = conversations;
        this.recorder = recorder;
        this.hybridSearch = hybridSearch;
        this.generation = generation;
        this.observability = observability;
        this.traceBodyCapture = traceBodyCapture;
    }

    @Override
    public Flux<QaEvent> ask(UUID userId, AskRequest request) {
        boolean existingConversation = request.conversationId() != null;
        return Flux.create(sink -> execute(userId, request, sink,
                        observability.start(ObservationName.QA_RUN,
                                TelemetryTag.conversation(existingConversation
                                        ? TelemetryOutcome.Conversation.EXISTING
                                        : TelemetryOutcome.Conversation.NEW))),
                FluxSink.OverflowStrategy.BUFFER)
                .subscribeOn(Schedulers.boundedElastic());
    }

    private void execute(UUID userId, AskRequest request, FluxSink<QaEvent> sink,
                         VeridexObservability.ObservationScope observation) {
        UUID[] runRef = new UUID[1];
        Disposable[] modelSubscription = new Disposable[1];
        sink.onCancel(() -> cancelRun(runRef[0], modelSubscription[0], observation));

        try {
            List<UUID> scope = knowledgeScope.resolve(userId, request.knowledgeBaseIds());
            if (scope.isEmpty()) {
                sink.next(new QaEvent.AnswerRefused("ACCESS_RESTRICTED", "当前可访问知识范围内证据不足"));
                observation.success(TelemetryTag.qaOutcome(TelemetryOutcome.Qa.REFUSED));
                sink.complete();
                return;
            }

            UUID conversationId = request.conversationId();
            if (conversationId == null) {
                ConversationView created = conversations.create(userId, truncate(request.question(), 80));
                conversationId = created.id();
            } else if (conversations.findOwned(userId, conversationId).isEmpty()) {
                throw new IllegalStateException("会话不存在或无权访问");
            }

            String normalized = request.question().trim();
            UUID runId = recorder.start(userId, conversationId, scope, normalized);
            runRef[0] = runId;
            sink.next(new QaEvent.RunStarted(runId, conversationId));

            var history = conversations.recentMessages(conversationId, HISTORY_TURNS);
            conversations.addMessage(conversationId, "USER", request.question(), runId);

            HybridSearchResult searchResult = hybridSearch.search(userId, scope, request.knowledgeBaseIds(), normalized);
            sink.next(new QaEvent.RetrievalCompleted(searchResult.evidence().size()));
            recorder.markRetrieving(runId, searchResult.hits().stream()
                    .map(h -> new RetrievalHitRecord(h.knowledgeBaseId(), h.documentVersionId(), h.chunkIndex(),
                            h.channel(), h.bm25Score(), h.vectorScore(), h.fusionScore(), h.rank(),
                            h.enteredContext(), h.filterReason()))
                    .toList());

            recorder.markGenerating(runId);
            modelSubscription[0] = generation.stream(normalized, searchResult.evidence(), history)
                    .subscribe(
                            event -> handleGenerationEvent(event, sink, observation, runId, conversationId,
                                    request, searchResult),
                            error -> handleGenerationError(error, sink, observation, runId, request,
                                    searchResult),
                            () -> { /* 终端事件由 handleGenerationEvent 的 Completed/Refused 分支负责 */ });
        } catch (RuntimeException e) {
            handleError(e, sink, observation, runRef[0], request, List.of());
        }
    }

    private void handleGenerationEvent(GenerationEvent event, FluxSink<QaEvent> sink,
                                       VeridexObservability.ObservationScope observation, UUID runId,
                                       UUID conversationId, AskRequest request,
                                       HybridSearchResult searchResult) {
        if (event instanceof GenerationEvent.Delta delta) {
            sink.next(new QaEvent.AnswerDelta(delta.text()));
            return;
        }
        if (event instanceof GenerationEvent.Refused refused) {
            GenerationResult result = refused.result();
            sink.next(new QaEvent.AnswerRefused(result.refusalReason().name(),
                    refusalMessage(result.refusalReason())));
            recorder.refuse(runId, result.refusalReason());
            traceBodyCapture.capture(runId, TraceBodyCapture.TerminalOutcome.REFUSED,
                    result.refusalReason().name(), material(request.question(), result, searchResult.evidence()));
            observation.success(TelemetryTag.qaOutcome(TelemetryOutcome.Qa.REFUSED));
            sink.complete();
            return;
        }
        if (event instanceof GenerationEvent.Completed completed) {
            GenerationResult result = completed.result();
            recorder.recordGeneration(runId, new GenerationRecord(result.provider(), result.model(),
                    result.inputTokens(), result.outputTokens(), result.durationMs(),
                    result.firstTokenLatencyMs(),
                    searchResult.degradations().isEmpty() ? null : String.join("; ", searchResult.degradations()),
                    result.contextHash()));
            recorder.addCitations(runId, result.citations().stream()
                    .map(c -> new CitationRecord(c.citationIndex(), c.documentVersionId(), c.chunkIndex(),
                            c.sourceLocation(), c.citationText(), c.validationStatus()))
                    .toList());
            conversations.addMessage(conversationId, "ASSISTANT", result.answer(), runId);
            sink.next(new QaEvent.CitationAvailable(result.citations()));
            sink.next(new QaEvent.AnswerCompleted(runId));
            recorder.complete(runId);
            traceBodyCapture.capture(runId, TraceBodyCapture.TerminalOutcome.COMPLETED, null,
                    material(request.question(), result, searchResult.evidence()));
            observation.success(TelemetryTag.qaOutcome(TelemetryOutcome.Qa.COMPLETED));
            sink.complete();
        }
    }

    private void handleGenerationError(Throwable error, FluxSink<QaEvent> sink,
                                       VeridexObservability.ObservationScope observation, UUID runId,
                                       AskRequest request, HybridSearchResult searchResult) {
        TelemetryErrorCode code = TelemetryErrorCode.classify(toException(error));
        recorder.fail(runId, code.name());
        traceBodyCapture.capture(runId, TraceBodyCapture.TerminalOutcome.FAILED, code.name(),
                new TraceBodyCapture.TraceBodyMaterial(request.question(), List.of(), null,
                        searchResult.evidence().stream().map(e -> new TraceBodyCapture.EvidenceSnapshot(
                                e.citationIndex(), e.documentVersionId(), e.chunkIndex(), e.title(),
                                e.structurePath(), e.text())).toList(), List.of()));
        observation.failure(code);
        sink.next(new QaEvent.RunFailed(userSafeMessage(error)));
        sink.complete();
    }

    private void cancelRun(UUID runId, Disposable modelSubscription,
                           VeridexObservability.ObservationScope observation) {
        if (modelSubscription != null && !modelSubscription.isDisposed()) {
            modelSubscription.dispose();
        }
        if (runId != null) {
            recorder.cancel(runId);
            traceBodyCapture.capture(runId, TraceBodyCapture.TerminalOutcome.CANCELLED, null,
                    new TraceBodyCapture.TraceBodyMaterial("", List.of(), null, List.of(), List.of()));
        }
        observation.success(TelemetryTag.qaOutcome(TelemetryOutcome.Qa.CANCELLED));
    }

    private void handleError(RuntimeException e, FluxSink<QaEvent> sink,
                             VeridexObservability.ObservationScope observation, UUID runId,
                             AskRequest request, List<io.veridex.retrieval.api.EvidencePiece> evidence) {
        TelemetryErrorCode code = TelemetryErrorCode.classify(e);
        if (runId != null) {
            recorder.fail(runId, code.name());
            traceBodyCapture.capture(runId, TraceBodyCapture.TerminalOutcome.FAILED, code.name(),
                    new TraceBodyCapture.TraceBodyMaterial(request.question(), List.of(), null,
                            evidence.stream().map(ev -> new TraceBodyCapture.EvidenceSnapshot(
                                    ev.citationIndex(), ev.documentVersionId(), ev.chunkIndex(), ev.title(),
                                    ev.structurePath(), ev.text())).toList(), List.of()));
        }
        observation.failure(code);
        sink.next(new QaEvent.RunFailed(userSafeMessage(e)));
        sink.complete();
    }

    private static RuntimeException toException(Throwable error) {
        return error instanceof RuntimeException re ? re : new RuntimeException(error);
    }

    private static String userSafeMessage(Throwable error) {
        if (error instanceof InvalidCitationException) {
            return "回答未通过引用校验，未保存本次结果";
        }
        if (error instanceof GenerationModelException) {
            return "模型服务暂时不可用，请稍后重试";
        }
        return "系统错误，请稍后重试";
    }

    private static TraceBodyCapture.TraceBodyMaterial material(String question, GenerationResult result,
                                                                List<io.veridex.retrieval.api.EvidencePiece> evidence) {
        var prompts = result.promptMessages().stream()
                .map(p -> new TraceBodyCapture.PromptMessage(p.role(), p.content())).toList();
        var evidenceSnapshots = evidence.stream()
                .map(e -> new TraceBodyCapture.EvidenceSnapshot(e.citationIndex(), e.documentVersionId(),
                        e.chunkIndex(), e.title(), e.structurePath(), e.text())).toList();
        var citations = result.citations().stream()
                .map(c -> new TraceBodyCapture.CitationSnapshot(c.citationIndex(), c.documentVersionId(),
                        c.chunkIndex(), c.sourceLocation(), c.citationText(), c.validationStatus())).toList();
        return new TraceBodyCapture.TraceBodyMaterial(question, prompts, result.answer(), evidenceSnapshots, citations);
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

关键点：
- `Flux.create` 的 lambda 每次订阅执行一次（cold），阻塞 JPA 调用在 `boundedElastic` 上。
- 取消链路：sink 被取消 → `onCancel` → dispose 模型订阅 + `recorder.cancel` + trace CANCELLED + observation 记 CANCELLED。`recorder.cancel` 走 Task 2 的幂等守卫。
- 引用失败由 `GenerationServiceImpl` 抛 `InvalidCitationException` → 走 error handler → `INVALID_CITATION` + 不落 assistant。
- `run.failed` 只在两类位置发出：模型流 error 或编排层异常；不会在 Completed/Refused 后重复发送（sink 已 complete）。

- [ ] **Step 4: 重写 QaController（Flux<ServerSentEvent>）**

Modify `QaController.java`：

```java
    @PostMapping(value = "/ask", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<QaEvent>> ask(@RequestBody AskRequest request) {
        UUID userId = CurrentActor.id();
        return service.ask(userId, request)
                .map(event -> ServerSentEvent.<QaEvent>builder()
                        .event(eventName(event))
                        .data(event)
                        .build());
    }
```

删除 `SseEmitter`/`CompletableFuture`/`java.util.List`/`HttpStatus` 相关未用 import（`ResponseEntity`、`ResponseStatusException` 仍用于其余端点）。`eventName` 保持不变。

- [ ] **Step 5: 跑服务单测到 GREEN + 集成测试回归**

Run: `./mvnw -f backend/pom.xml -pl . test -Dtest='QuestionAnsweringServiceTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS。

Modify `QaTestFixture.java` 追加「增量读取」辅助（供 Step 6 用）：

```java
    /** 流式发起问答：读到指定事件为止（不等待流 EOF），返回已累积的 SSE 文本。 */
    protected String askUntilEvent(String session, String question, List<String> kbIds, String targetEvent) throws Exception {
        var body = new StringBuilder("{\"question\":\"" + question + "\",\"knowledgeBaseIds\":[");
        for (int i = 0; i < kbIds.size(); i++) {
            if (i > 0) {
                body.append(',');
            }
            body.append('"').append(kbIds.get(i)).append('"');
        }
        body.append("]}");

        HttpRequest req = HttpRequest.newBuilder(URI.create(base() + "/api/qa/ask"))
                .header("Content-Type", "application/json")
                .header("Cookie", session)
                .header("X-XSRF-TOKEN", csrfToken)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        assertThat(resp.statusCode()).isLessThan(300);

        StringBuilder accumulated = new StringBuilder();
        try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
            StringBuilder block = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                block.append(line).append('\n');
                if (line.isEmpty()) {
                    String chunk = block.toString();
                    accumulated.append(chunk);
                    if (chunk.contains("event:" + targetEvent)) {
                        break;
                    }
                    block.setLength(0);
                }
            }
        }
        return accumulated.toString();
    }
```

Modify `QaApiIntegrationTest.java` 新增：

```java
    @Test
    void firstAnswerDeltaArrivesBeforeStreamCompletes() throws Exception {
        String session = login("admin");
        String kbId = seedKnowledgeBase("制度库", "请假制度.md", LEAVE_CHUNK);
        publish(session, kbId);

        String head = askUntilEvent(session, "请假几天", List.of(kbId), "answer.delta");

        assertThat(head).contains("event: answer.delta");
        assertThat(head).doesNotContain("event: answer.completed");
    }
```

Run: `./mvnw -f backend/pom.xml -pl . test -Dtest='QaApiIntegrationTest,QaAclIntegrationTest,QaCitationPreviewIntegrationTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS（依赖 compose 容器；容器未起则先起 `docker compose -f deploy/compose/compose.yml up -d`，若环境无法起容器，在提交说明注明并在 verify.sh 门禁阶段补跑）。

- [ ] **Step 6: 回归 ACL/敏感输出/trace body 相关集成测试并提交**

Run: `./mvnw -f backend/pom.xml -pl . test -Dtest='QaApiIntegrationTest,QaAclIntegrationTest,QaCitationPreviewIntegrationTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS。追加检查 `QaAclIntegrationTest` 中的 ACL 拒绝仍为 `answer.refused` 且无 `answer.delta`（流式入口下预检逻辑未变）。

```bash
git add backend/src/main/java/io/veridex/qa backend/src/test/java/io/veridex/qa
git commit -m "$(cat <<'EOF'
feat: stream real token deltas over end-to-end flux SSE

Co-Authored-By: CodeTui <noreply@codetui.dev>
EOF
)"
git diff --check
```

---

### Task 4: 前端 provisional 消息与 AbortSignal

**Files:**
- Modify: `web/src/features/qa/qaApi.ts`
- Modify: `web/src/features/qa/QaPage.tsx`
- Modify: `web/src/features/qa/QaPage.test.tsx`

**Interfaces:**
- Consumes: 现有 `QaEvent` 类型、`parseSse`。
- Produces:
  - `qaApi.ask(question, knowledgeBaseIds, conversationId, onEvent, signal?: AbortSignal): Promise<void>`——abort 时静默返回，不触发 `run.failed`。
  - `QaPage`：每轮 ask 新建 `AbortController`；`run.failed`/`answer.refused` 丢弃 provisional；组件卸载 abort。

- [ ] **Step 1: 写失败的前端测试（RED）**

在 `QaPage.test.tsx` 追加：

```tsx
test('run.failed discards provisional text and shows error', async () => {
  mockedAsk.mockImplementation(async (_q, _kb, _c, onEvent) => {
    onEvent({ name: 'answer.delta', data: { text: '根据《请假制度》' } })
    onEvent({ name: 'run.failed', data: { message: '模型服务暂时不可用' } })
  })

  render(<QaPage />)

  await screen.findByRole('heading', { name: '向制度知识库提问' })
  fireEvent.change(screen.getByLabelText('问题'), { target: { value: '请假' } })
  fireEvent.click(screen.getByRole('button', { name: '发送' }))

  expect(await screen.findByText('模型服务暂时不可用')).toBeInTheDocument()
  // provisional 文本必须被丢弃
  expect(screen.queryByText(/根据《请假制度》/)).not.toBeInTheDocument()
})

test('ask receives an AbortSignal and abort does not show fake failure', async () => {
  let capturedSignal: AbortSignal | undefined
  mockedAsk.mockImplementation(async (_q, _kb, _c, _onEvent, signal) => {
    capturedSignal = signal
    // 模拟用户取消：等待 abort
    await new Promise<void>((resolve) => {
      signal?.addEventListener('abort', () => resolve())
    })
  })

  const { unmount } = render(<QaPage />)
  await screen.findByRole('heading', { name: '向制度知识库提问' })
  fireEvent.change(screen.getByLabelText('问题'), { target: { value: '请假' } })
  fireEvent.click(screen.getByRole('button', { name: '发送' }))

  expect(capturedSignal).toBeInstanceOf(AbortSignal)
  unmount() // 卸载触发 abort
  await new Promise((r) => setTimeout(r, 0))
  expect(screen.queryByText('请求失败')).not.toBeInTheDocument()
  expect(screen.queryByText(/根据《请假制度》/)).not.toBeInTheDocument()
})
```

Run: `npm --prefix web test -- --run`
Expected: FAIL——第一个用例 provisional 文本仍被保留（旧逻辑 finalizeStream），第二个用例 `ask` 尚无 signal 参数。

- [ ] **Step 2: qaApi.ask 支持 AbortSignal（GREEN）**

Modify `qaApi.ts`：

```ts
export const qaApi = {
  ask: async (
    question: string,
    knowledgeBaseIds: string[],
    conversationId: string | null,
    onEvent: (event: QaEvent) => void,
    signal?: AbortSignal,
  ) => {
    let response: Response
    try {
      response = await fetch('/api/qa/ask', {
        method: 'POST',
        credentials: 'include',
        headers: { 'Content-Type': 'application/json', ...csrfHeaders() },
        body: JSON.stringify({ question, knowledgeBaseIds, conversationId }),
        signal,
      })
    } catch (error) {
      // 用户主动取消：静默返回，不伪造 run.failed
      if (error instanceof DOMException && error.name === 'AbortError') return
      throw error
    }
    if (!response.ok) {
      onEvent({ name: 'run.failed', data: { message: `请求失败 (${response.status})` } })
      return
    }
    await parseSse(response, onEvent)
  },
  // ...其余不变
}
```

`parseSse` 中 `reader.read()` 抛 AbortError 时同样静默返回：

```ts
async function parseSse(response: Response, onEvent: (event: QaEvent) => void) {
  const reader = response.body?.getReader()
  if (!reader) return
  const decoder = new TextDecoder()
  let buffer = ''
  try {
    for (;;) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      const blocks = buffer.split('\n\n')
      buffer = blocks.pop() ?? ''
      for (const block of blocks) {
        const dataLine = block.split('\n').find((line) => line.startsWith('data:'))
        const nameLine = block.split('\n').find((line) => line.startsWith('event:'))
        if (!dataLine) continue
        const name = (nameLine?.slice(6).trim() ?? '') as QaEvent['name']
        onEvent({ name, data: JSON.parse(dataLine.slice(5).trim()) })
      }
    }
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') return
    throw error
  }
}
```

- [ ] **Step 3: QaPage 丢弃 provisional + 卸载取消（GREEN）**

Modify `QaPage.tsx`：
- 新增 `const abortRef = useRef<AbortController | null>(null)`。
- `ask()` 开头：`const controller = new AbortController(); abortRef.current = controller`；`qaApi.ask(..., controller.signal)`。
- `run.failed` 分支改为：`resetStream()`（丢弃 provisional）再追加 ERROR 消息。
- `answer.refused` 分支改为：`resetStream()` 再追加 SYSTEM 消息（语义不变，显式丢弃）。
- `finally` 中：`setStreaming(false); resetStream(); const latest = await qaApi.conversations(); setConversations(latest)`——把 `finalizeStream()` 从 finally 移除，只在 `answer.completed` 分支调用（固化）。
- 卸载清理：

```tsx
useEffect(() => {
  return () => abortRef.current?.abort()
}, [])
```

- [ ] **Step 4: 跑前端测试 + build 到 GREEN**

Run: `npm --prefix web test -- --run && npm --prefix web run build`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add web/src/features/qa
git commit -m "$(cat <<'EOF'
feat: treat streamed answer as provisional and support abort

Co-Authored-By: CodeTui <noreply@codetui.dev>
EOF
)"
git diff --check
```

---

### Task 5: Compose/Helm/NetworkPolicy/离线说明 + 显式验收脚本

**Files:**
- Modify: `deploy/compose/compose.yml`
- Modify: `deploy/compose/.env.example`
- Modify: `deploy/helm/veridex/values.yaml`
- Modify: `deploy/offline/veridex-offline/INSTALL.txt`
- Create: `scripts/verify-ollama-chat.sh`

**Interfaces:**
- Consumes: Task 1 的环境变量契约（`VERIDEX_CHAT_*`、`VERIDEX_OUTBOUND_*`）。
- Produces: 真实 Ollama 显式验收入口 `scripts/verify-ollama-chat.sh`（默认 skip、不进入 `verify.sh`）。

- [ ] **Step 1: compose 注释化 Ollama Chat 环境变量**

Modify `deploy/compose/compose.yml` 的 `backend.environment` 末尾追加注释块：

```yaml
      # --- Ollama Chat（可选，默认 deterministic 无需配置）---
      # 指向宿主机 Ollama 时使用 host.docker.internal，不要用 localhost（容器内）。
      # VERIDEX_CHAT_PROVIDER: ollama
      # VERIDEX_OLLAMA_BASE_URL: http://host.docker.internal:11434
      # VERIDEX_OLLAMA_CHAT_MODEL: qwen3:8b
      # VERIDEX_CHAT_TIMEOUT: 60s
      # VERIDEX_OUTBOUND_ALLOWED_HOSTS: host.docker.internal
      # VERIDEX_OUTBOUND_ALLOWED_PORTS: "11434"
      # VERIDEX_OUTBOUND_ALLOW_INSECURE_HTTP: "true"
```

Modify `deploy/compose/.env.example`（若不存在则创建，与 compose 使用的 `*.env` 变量同风格）追加：

```bash
# Chat provider（deterministic | ollama）
# VERIDEX_CHAT_PROVIDER=deterministic
# VERIDEX_CHAT_TIMEOUT=60s
# VERIDEX_OLLAMA_BASE_URL=http://host.docker.internal:11434
# VERIDEX_OLLAMA_CHAT_MODEL=qwen3:8b
# 使用真实 Ollama（HTTP）时需显式放行：
# VERIDEX_OUTBOUND_ALLOWED_HOSTS=host.docker.internal
# VERIDEX_OUTBOUND_ALLOWED_PORTS=11434
# VERIDEX_OUTBOUND_ALLOW_INSECURE_HTTP=true
```

- [ ] **Step 2: Helm values 增 chat 配置 + externalEgress 示例**

Modify `deploy/helm/veridex/values.yaml` 的 `backend.env` 追加（保持 `additionalProperties: string` 兼容，无需改 schema）：

```yaml
      VERIDEX_CHAT_PROVIDER: deterministic
      VERIDEX_CHAT_TIMEOUT: 60s
      VERIDEX_OLLAMA_BASE_URL: http://ollama.internal:11434
      VERIDEX_OLLAMA_CHAT_MODEL: qwen3:8b
```

`networkPolicy.externalEgress` 注释追加示例：

```yaml
  # Ollama 示例（VERIDEX_CHAT_PROVIDER=ollama 时）：
  # - { type: selector, namespace: ollama, podSelector: { app.kubernetes.io/name: ollama }, port: 11434 }
```

- [ ] **Step 3: 离线包说明**

Modify `deploy/offline/veridex-offline/INSTALL.txt`（不存在则先看 `deploy/offline/` 实际布局，追加章节）：

```text
## Ollama Chat（可选）

- Veridex 默认 deterministic（测试占位模型），无需任何模型服务。
- 使用真实 Chat 时，Ollama 与模型权重（qwen3:8b 或自定义）由安装者预置，不打入本交付包。
- 配置：VERIDEX_CHAT_PROVIDER=ollama、VERIDEX_OLLAMA_BASE_URL、VERIDEX_OLLAMA_CHAT_MODEL，
  并按 NetworkPolicy externalEgress 显式放行 Ollama 的 host/port。
```

- [ ] **Step 4: 写显式验收脚本（RED→GREEN 语义：脚本可执行且默认 skip）**

Create `scripts/verify-ollama-chat.sh`：

```bash
#!/usr/bin/env bash
set -euo pipefail

# 真实 Ollama Chat 显式验收（设计 §9.3）。不进默认 CI：
# 需要外部 Ollama 可达（VERIDEX_OLLAMA_BASE_URL）与已起 Compose 栈。
# 覆盖验收项 1-4；模型不可达（MODEL_ERROR）、客户端取消（CANCELLED）、
# 日志不泄露 prompt/completion 为人工验收项，见文末清单。

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BASE_URL="${VERIDEX_OLLAMA_BASE_URL:-http://localhost:11434}"
MODEL="${VERIDEX_OLLAMA_CHAT_MODEL:-qwen3:8b}"

echo "==> 检查 Ollama 可达性（$BASE_URL）"
if ! curl -fsS --max-time 5 "$BASE_URL/api/tags" >/dev/null 2>&1; then
  echo "Ollama 不可达，跳过真实模型验收（默认 deterministic 不受影响）。"
  exit 0
fi
curl -fsS --max-time 10 "$BASE_URL/api/tags" | grep -q "\"$MODEL\"" \
  || { echo "模型 $MODEL 未安装，请先 ollama pull $MODEL"; exit 1; }

echo "==> 冒烟：登录 + 建库 + 发布 + 问答（首 delta 先于流结束）"
# 复用 deploy/compose/smoke.sh 的登录方式；此处断言：
# 1) 首个 event: answer.delta 在流完成前到达（流式读取）
# 2) 最终存在 event: answer.completed 且 citation.available 含 VALID
# 3) 流式读取期间无 run.failed 先行
# 实现细节：curl -N 流式读取 SSE，awk 记录事件序列。
echo "==> 断言：首个 answer.delta 在流完成前到达且最终完成"
# 需要 compose 栈以 VERIDEX_CHAT_PROVIDER=ollama 运行；smoke.sh 已含登录/建库/发布，
# 此处用 curl -N 流式读取一次问答 SSE，按 event 行序做顺序断言。
SSE_LOG="$(mktemp)"
if ! curl -NsS --max-time 120 -H "Content-Type: application/json" \
     -H "Cookie: $SESSION_COOKIE" -H "X-XSRF-TOKEN: $CSRF_TOKEN" \
     -d '{"question":"请假几天","knowledgeBaseIds":[]}' \
     "$VERIDEX_WEB_URL/api/qa/ask" | tee "$SSE_LOG" | awk '
       /^event:/ { ev=substr($0,7); gsub(/\r/,"",ev); seq=seq " " ev }
       END { print seq > "/dev/stderr" }
     ' 2>&1; then
  echo "SSE 请求失败，见 $SSE_LOG"
  exit 1
fi
grep -q "event:answer.delta" "$SSE_LOG" || { echo "缺少 answer.delta"; exit 1; }
grep -q "event:answer.completed" "$SSE_LOG" || { echo "缺少 answer.completed"; exit 1; }
# 顺序断言：delta 出现在 completed 之前
python3 - "$SSE_LOG" <<'INNER_PYEOF'
import sys
lines = open(sys.argv[1]).read().splitlines()
events = [l[6:].strip() for l in lines if l.startswith("event:")]
assert events.index("answer.delta") < events.index("answer.completed"), events
assert "run.failed" not in events, events
print("OK: delta 先于 completed，无 run.failed，事件序列 =", events)
INNER_PYEOF
rm -f "$SSE_LOG"

echo "==> 人工验收清单（记录结果后人工确认）"
cat <<'MANUAL'
- [ ] 模型不可达时返回 run.failed（MODEL_ERROR），无 deterministic 静默回退
- [ ] 客户端中断后 QueryRun 为 CANCELLED
- [ ] 默认日志/metrics/SSE 错误消息不泄露 prompt/completion/内部 URL
MANUAL
```

说明：真实 Ollama 验收依赖宿主机环境，脚本以「可达性检查 + 门禁失败提示 + 人工清单」为骨架；具体的流式断言命令（curl -N + awk 事件序列）在实施时以 `deploy/compose/smoke.sh` 现有风格实现并本机跑通一次（有 Ollama 则断言真实；无则确认 skip 分支）。`chmod +x scripts/verify-ollama-chat.sh`。**不**加入 `verify.sh`。

- [ ] **Step 5: 跑部署契约 + lint 回归**

Run: `./mvnw -f backend/pom.xml -pl . test -Dtest='HelmChartStaticContractTest,DockerfileStaticContractTest,DeploymentExitGateTest' -Dsurefire.failIfNoSpecifiedTests=false`
以及（本机有 docker/helm 时）`./scripts/verify-deployment.sh all`（无 docker 会自动降级 host-only 静态检查）。
Expected: PASS——helm values 变更不得破坏 chart lint/schema 契约。

- [ ] **Step 6: 提交**

```bash
git add deploy scripts/verify-ollama-chat.sh
git commit -m "$(cat <<'EOF'
feat: wire ollama chat config through compose, helm and acceptance script

Co-Authored-By: CodeTui <noreply@codetui.dev>
EOF
)"
git diff --check
```

---

### Task 6: 文档与阶段门禁

**Files:**
- Modify: `README.md`
- Modify: `docs/architecture.md`
- Modify: `docs/superpowers/specs/2026-08-18-ollama-chat-provider-streaming-design.md`（仅在发现契约偏差时加「实施偏差」附注；无偏差不改）

**Interfaces:**
- Consumes: Task 1–5 全部产出。
- Produces: 满足设计 §10 验收标准 1–12 的文档与全量门禁结果。

- [ ] **Step 1: README 增 Chat provider 配置说明**

在 README 配置/部署章节补充（具体位置以 README 现有目录为准）：

```markdown
### Chat 生成模型

- 默认 `deterministic`（测试占位：从证据块模板拼接，无外部依赖）；首个真实 Chat provider 为 Ollama。
- 启用真实模型（需安装者预置 Ollama 与模型权重）：

  VERIDEX_CHAT_PROVIDER=ollama
  VERIDEX_OLLAMA_BASE_URL=http://ollama.internal:11434
  VERIDEX_OLLAMA_CHAT_MODEL=qwen3:8b
  VERIDEX_OUTBOUND_ALLOWED_HOSTS=ollama.internal
  VERIDEX_OUTBOUND_ALLOWED_PORTS=11434
  VERIDEX_OUTBOUND_ALLOW_INSECURE_HTTP=true

- Ollama 经 HTTP 时必须显式开启 insecure-http 并放行 host/port；模型故障/超时不会回退 deterministic。
- 流式回答为 provisional：引用终检通过（answer.completed）才持久化；引用失败发 run.failed 且不保存。
```

- [ ] **Step 2: architecture.md 更新**

- §4 可观测性边界：补充 `veridex.generation.first_token`、`veridex.generation.outcome`（success/refused/failed/cancelled）与 provider 标签 {deterministic, ollama}、错误码 MODEL_ERROR/MODEL_TIMEOUT/INVALID_CITATION。
- §5.5 问答：SSE 由 Controller 返回 `Flux<ServerSentEvent<QaEvent>>`；事件协议 7 种不变；`answer.delta` 为 provisional；取消传播与 CANCELLED。
- §7 部署拓扑：`veridex.chat.*` 配置入口与 NetworkPolicy externalEgress 放行 Ollama 的要求。

- [ ] **Step 3: 全量门禁**

Run: `./scripts/verify.sh`（7 步：Maven clean verify、前端测试、前端 build、observability、security、deployment、git diff --check）。
Expected: 全绿。若个别步因环境不可用（无 docker）按既有降级逻辑输出 skipped 亦视为通过。
若 Maven clean verify 暴露任何回归，修复到全绿后再提交。

- [ ] **Step 4: 提交**

```bash
git add README.md docs/architecture.md
git commit -m "$(cat <<'EOF'
docs: document ollama chat provider and streaming contract

Co-Authored-By: CodeTui <noreply@codetui.dev>
EOF
)"
git diff --check
```

- [ ] **Step 5: 阶段总结（门禁陈述）**

对照设计 §10 验收标准逐条给出结论（1–12），并更新 `docs/superpowers/plans/2026-08-19-ollama-chat-provider-streaming.md` 顶部 checkbox 为已完成；随后进入 Phase 5-e 设计（备份恢复+容量），容量报告须同时覆盖 deterministic 基线与 Ollama `qwen3:8b`。


---

## 执行记录（2026-08-19）

全部 6 个 Task 完成，提交 cf43820→040ddcf（12 个）。门禁 `verify.sh` 各步骤全绿（见 Task 6 结论）。

### 实施偏差

- **异常类迁往 `generation::api`**：`GenerationModelException` 族与 `GenerationErrorCodes` 原计划放 `generation.application`，
  但 `qa` 模块只允许依赖 `generation::api`，且 `shared.observability.TelemetryErrorCode` 引用 generation 异常会形成模块环
  （`generation→knowledge→iam→shared→generation`，ArchitectureTest 强制）。改为放入 `generation.api`（它们本就是流式契约的一部分），
  分类逻辑收敛到 `GenerationErrorCodes.classify`；`TelemetryErrorCode.classify` 恢复为通用分类。
  `generation` 模块声明增加 `shared::security`（OllamaChatConfiguration 使用 OutboundAccessPolicy）。

- **ChatProviderSelectionTest 默认 provider 显式声明**：ApplicationContextRunner 环境无 application.yml 默认值，
  verifier 要求 provider 非空，故 base runner 显式设 `veridex.chat.provider=deterministic`；matchIfMissing 默认行为由集成测试与契约测试覆盖。

- **usage 元数据 API 以 spring-ai 2.0.0 实测为准**：`ChatResponseMetadata` 位于 `org.springframework.ai.chat.metadata`（非 model 包），
  `DefaultUsage` 无 builder、用 `new DefaultUsage(prompt, completion, total)`。

- **kind 验收环境适配（DNS 污染）**：依赖镜像经宿主机 `docker pull` + `docker save | ctr import` 进节点（`kind load --all-platforms`
  在仅有单平台 blob 的宿主机上报 digest 缺失）；kind 节点 containerd 声明 daocloud mirror；
  `rabbitmq:4-alpine` 在 kind 节点以 root 入口生成 root 属主 `.erlang.cookie` 导致预启动 eacces，
  显式 `runAsUser/runAsGroup: 999` 修复（test-deps.yaml）。

- **契约测试断言按占位符文本定位**：`ChatConfigContractTest` 最初按 `provider:`/`timeout:` 键匹配会被 embedding 块误命中，
  改为按 `VERIDEX_CHAT_*` 占位符查找。

- **前端 `qaApi.ask`/`QaPage` 细节**：abort 走 `DOMException AbortError` 静默返回；`run.failed`/`answer.refused` 先 `resetStream()` 丢弃 provisional；
  `finally` 不再无条件 `finalizeStream()`（只在 `answer.completed` 固化）。

### Task 6 门禁结论（对照设计 §10 验收标准）

1. 零配置只装配 `DeterministicChatModel`，现有 CI 不变 —— `ChatProviderSelectionTest.defaultProviderAssemblesOnlyDeterministicChatModel` + 全量回归绿。
2. `VERIDEX_CHAT_PROVIDER=ollama` 只装配 `OllamaChatModel` —— 选择测试 + `OllamaChatModel` 实例断言。
3. 在线问答在模型完成前发首个真实 delta —— `QaApiIntegrationTest.firstAnswerDeltaArrivesBeforeStreamCompletes`（读到 `event:answer.delta` 时流未结束）。
4. 同步评测仍可执行且共享 prompt/拒答/引用 —— `EvaluationRunService` 走 `generate`，编译与回归绿。
5. 只有引用终检通过才保存 assistant 消息并发 `answer.completed` —— `GenerationStreamingTest` + `QuestionAnsweringServiceTest.happyPath`。
6. 引用失败发 `run.failed`（INVALID_CITATION），前端不保留 provisional —— `invalidCitationFailsRunWithoutPersistingAssistant` + `QaPage.test.tsx`。
7. 模型故障/超时不回退 deterministic，trace/metrics 记稳定码 —— `GenerationStreamingTest`（MODEL_TIMEOUT/MODEL_ERROR）+ `TelemetryErrorCode`。
8. 客户端断开取消上游模型流，QueryRun 进 CANCELLED —— `cancellationCancelsRunAndPropagates` + `QueryRun.cancel()` 幂等。
9. Ollama 受 OutboundAccessPolicy 与 Helm NetworkPolicy 双重约束 —— `ollamaBaseUrlMustPassOutboundPolicy` + externalEgress 示例/离线文档。
10. 默认日志/metrics/SSE 错误不泄露敏感内容 —— `SensitiveOutputRegressionTest`/`PromptInjectionSafetyIntegrationTest` 回归绿。
11. backend/web/部署验证/真实 Ollama 显式验收 —— `verify.sh` 各步骤全绿；`scripts/verify-ollama-chat.sh` 提供显式入口
    （本机 Ollama 可达、模型 `qwen3.5:9b-mlx` 实测流式返回；端到端真实模型验收需以 `VERIDEX_CHAT_PROVIDER=ollama` 起栈）。
12. README/architecture 明确 deterministic=测试占位、Ollama=首个真实 provider —— Task 6 文档提交。

下一阶段：Phase 5-e 备份恢复+容量设计；容量报告须同时覆盖 deterministic 基线与 Ollama `qwen3:8b`（本机可用 `qwen3.5:9b-mlx` 代替）真实模型结果。
