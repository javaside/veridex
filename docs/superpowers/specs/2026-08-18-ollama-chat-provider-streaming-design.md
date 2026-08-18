# Ollama Chat Provider 与真实流式问答 - 设计文档

> 日期：2026-08-18
> 状态：已确认
> 定位：Phase 5-e 前置阶段，补齐 Phase 3 遗留的真实 ChatModel 路由与真实 SSE

## 1. 背景

当前检索、权限、引用和 trace 链路已实现，但生成仍固定注入 `DeterministicChatModel`。该模型从证据块中挑选一段并模板拼接，不具备总结、跨证据归纳或语义生成能力。`QuestionAnsweringService` 先同步生成完整答案，再按 8 个字符构造 `answer.delta`，因此当前 SSE 是模拟流式，无法验证首 token 延迟、生成中断和客户端取消。

Phase 3 路线图要求本地/外部 ChatModel 路由、SSE 生命周期、取消和超时。Phase 5-e 将进行容量与恢复验证；在此之前必须先接入真实模型，否则在线容量结论不代表真实 RAG 负载。

## 2. 目标与非目标

### 2.1 目标

- 通过启动期配置在 `deterministic` 与 Ollama ChatModel 之间互斥装配；
- 默认保持 deterministic，显式配置后使用 Ollama `qwen3:8b`；
- 在线问答端到端返回真实 token 流，并将客户端断开传播为上游取消；
- 完整答案生成后执行引用终检，通过后才落会话并完成 QueryRun；
- 保留同步生成入口，供现有评测执行使用；
- 记录真实模型标识、token usage、总时延、首 token 时延、失败与取消结果；
- 将 Ollama 配置纳入 Compose/Helm、NetworkPolicy、离线安装说明和显式验收脚本。

### 2.2 非目标

- 不支持 OpenAI 或 OpenAI-compatible provider；
- 不支持请求级动态模型路由、多模型负载均衡或自动故障转移；
- 不将 Ollama 服务打包进默认 Compose 或 Helm chart；
- 不修改 embedding provider、rerank provider 或评测指标定义；
- 不实现 Phase 5-e 的备份恢复、Spring Session 或 1-5 million chunk 容量报告；
- 不允许 Ollama 失败时静默回退 deterministic。

## 3. 已确认决策

| 编号 | 决策 | 说明 |
|---|---|---|
| D1 | 独立前置阶段 | 先补真实生成，再规划 Phase 5-e，避免容量测试基于占位模型。 |
| D2 | `ChatModel` 单端口 | 业务只依赖 Spring AI `ChatModel`；provider 通过条件 Bean 在启动期互斥选择。 |
| D3 | deterministic 为默认 | 零配置、默认 CI 和离线开发保持可重复。 |
| D4 | 首版只支持 Ollama | 与私有部署目标一致，复用已有 `spring-ai-ollama` 依赖。 |
| D5 | Ollama 默认 `qwen3:8b` | 兼顾中文 RAG 质量与本地资源，允许环境变量覆盖。 |
| D6 | 端到端 `Flux<QaEvent>` | 在线问答使用 `ChatModel.stream()`，Controller 不再先收集完整事件列表。 |
| D7 | 同步/流式双入口 | 在线走流式；评测继续同步调用，共用 prompt、拒答、引用和元数据收尾逻辑。 |
| D8 | `answer.delta` 是 provisional | 只有 `answer.completed` 才表示文本已通过引用终检并持久化。 |
| D9 | 引用失败丢弃 provisional 文本 | 服务端发 `run.failed`，不落 assistant 消息；前端删除当前临时消息。 |
| D10 | 无自动模型回退 | 模型异常必须暴露并记录，不能用模板答案掩盖生产故障。 |
| D11 | 取消向上传播 | 客户端断开取消模型订阅，QueryRun 与 trace 进入 `CANCELLED`。 |
| D12 | 外部 Ollama 由安装者管理 | chart 只传连接配置并显式放行 egress，不创建 Ollama workload。 |

## 4. 组件设计

### 4.1 配置与 provider 装配

新增 `ChatProperties`：

```yaml
veridex:
  chat:
    provider: ${VERIDEX_CHAT_PROVIDER:deterministic}
    timeout: ${VERIDEX_CHAT_TIMEOUT:60s}
    ollama:
      base-url: ${VERIDEX_OLLAMA_BASE_URL:http://localhost:11434}
      model: ${VERIDEX_OLLAMA_CHAT_MODEL:qwen3:8b}
```

`DeterministicChatModel` 增加：

```java
@ConditionalOnProperty(
    name = "veridex.chat.provider",
    havingValue = "deterministic",
    matchIfMissing = true)
```

新增 `OllamaChatConfiguration`，仅在 `veridex.chat.provider=ollama` 时创建 `OllamaChatModel`。使用 `OllamaApi.builder().baseUrl(...)`、`OllamaChatOptions.builder().model(...)` 和 `OllamaChatModel.builder()` 手工装配，不引入 Ollama starter，避免额外自动配置与 Bean 歧义。

装配前调用 `OutboundAccessPolicy.validate(URI)`。Ollama 使用 HTTP 时必须显式开启 `VERIDEX_OUTBOUND_ALLOW_INSECURE_HTTP=true`，host 与 port 必须进入既有 allowlist。沿用安全加固阶段的 fail-fast 策略，不为 localhost 或内网地址增加隐式豁免。

`GenerationServiceImpl` 改为注入 `ChatModel` 和 `ChatProperties`，不得依赖具体 provider 类型。`BoundedModelTags` 允许值收敛为 `deterministic` 与 `ollama`，模型名称继续作为受控/归一化标签，禁止把任意 URL、prompt 或异常文本放入指标标签。

### 4.2 GenerationService 接口

保留同步接口：

```java
GenerationResult generate(...);
GenerationResult generate(..., GenerationParameters parameters);
```

新增流式接口，返回生成生命周期事件而不是直接暴露 Spring AI 类型：

```java
Flux<GenerationEvent> stream(...);
```

`GenerationEvent` 是 generation 模块拥有的 sealed interface：

- `Delta(String text)`：模型输出增量；
- `Completed(GenerationResult result)`：完整文本已聚合、引用已校验、元数据已收尾；
- `Refused(GenerationResult result)`：模型调用前的证据拒答。

错误通过 Flux error channel 传播。取消通过 Reactor cancel signal 传播，不伪造 `Completed`。

同步和流式路径必须复用以下私有逻辑：

- 拒答策略；
- system prompt、证据块和会话历史组装；
- prompt snapshot；
- context hash；
- 引用校验；
- token usage 与时延提取/降级估算；
- observability 标签与错误分类。

### 4.3 QuestionAnsweringService 与 SSE

接口改为：

```java
Flux<QaEvent> ask(UUID userId, AskRequest request);
```

返回 cold Flux：只有 Controller 订阅后才创建/校验会话、启动 QueryRun、检索并调用模型。单次订阅只执行一次问答流程；禁止对该 Flux 使用隐式重试，否则可能重复写用户消息和 QueryRun。

`QaController` 返回 `Flux<ServerSentEvent<QaEvent>>`。事件名称保持现有协议：

1. `run.started`
2. `retrieval.completed`
3. `answer.delta`，零到多次
4. `citation.available`
5. `answer.completed`、`answer.refused` 或 `run.failed`

`answer.delta` 明确定义为 provisional。`citation.available` 只在完整答案引用终检通过后发送。`answer.completed` 是唯一将文本视为持久回答的成功信号。

### 4.4 在线数据流

正常回答：

1. 解析授权知识范围并校验会话归属；
2. 创建 QueryRun，发送 `run.started`；
3. 保存用户消息，执行混合检索并记录 RetrievalHit；
4. 发送 `retrieval.completed`；
5. 拒答策略通过后，将 QueryRun 标为 `GENERATING` 并订阅 `ChatModel.stream(prompt)`；
6. 每个非空模型文本片段立即映射为 `answer.delta`，同时仅在内存中追加到本次答案缓冲；
7. 模型流完成后，基于完整文本执行引用校验；
8. 引用有效时记录 GenerationRun 与 Citation，保存 assistant 消息，捕获 trace body；
9. 依次发送 `citation.available`、`answer.completed`，QueryRun 标为 `COMPLETED`。

证据不足时不调用模型，记录 refusal 并发送 `answer.refused`。

### 4.5 引用终检

真实模型输出必须至少包含一个形如 `[n]` 的引用，且所有引用索引都能映射到本次授权证据。非拒答答案未通过引用校验时：

- 不保存 assistant 消息；
- 不记录可用 Citation；
- QueryRun 标为 `FAILED`；
- trace body 可按既有受控策略捕获模型原始输出与验证结果；
- 发送 `run.failed`，稳定错误码为 `INVALID_CITATION`，对终端用户使用不泄露内部细节的消息；
- 前端删除本次 provisional assistant 消息并显示错误消息。

不得把无效引用回答降级为 completed，也不得把 deterministic 结果拼接到已发送 token 后面。

## 5. 失败、超时与取消

### 5.1 模型失败

连接拒绝、DNS/网络错误、非成功响应、空响应、流中断和 provider 内部异常统一归类为 `MODEL_ERROR`。系统：

- 取消上游订阅；
- 不保存 assistant 消息；
- 将 QueryRun 标为 `FAILED`；
- 保存受控 trace 元数据；
- 发送单个 `run.failed` 终端事件。

不自动回退 deterministic。

### 5.2 超时

`veridex.chat.timeout` 是一次模型生成的总时限，默认 60 秒。超时通过 Reactor `timeout` 施加到模型 Flux，归类为 `MODEL_ERROR` 并在 trace degradation/error 中保留稳定子码 `MODEL_TIMEOUT`。HTTP/SSE 层的超时不得短于模型总时限加收尾余量；具体值由实施计划固定并测试。

### 5.3 客户端取消

浏览器取消 fetch、页面卸载或连接断开会取消 Controller 返回的 Flux。若 QueryRun 已创建且尚未进入终态：

- 取消 `ChatModel.stream()` 上游订阅；
- 调用 `QueryRunRecorder.cancel(runId)`；
- 捕获 `TerminalOutcome.CANCELLED` trace；
- 不保存不完整 assistant 消息；
- 不再尝试发送终端 SSE。

取消收尾必须幂等，不能覆盖已完成、已拒答或已失败状态。

## 6. Token、时延与可观测性

优先读取最终 `ChatResponse` metadata 的 usage；provider 未返回 usage 时，沿用字符数估算，但必须在内部标记为 estimated，不能伪装为 provider 精确值。

GenerationRun 继续记录 input/output token 与总时延，并新增首 token 时延字段。模型标识记录实际 provider 与 model，不再直接使用评测 Profile 中未接路由的任意字符串作为实际调用模型。

新增/调整低基数指标：

- generation model duration；
- generation first-token latency；
- generation outcome：success/refused/failed/cancelled；
- provider：deterministic/ollama；
- error code：MODEL_ERROR/MODEL_TIMEOUT/INVALID_CITATION。

默认不记录 prompt、证据或 completion body；仍由既有 trace-body 开关、加密与保留策略控制。

## 7. 前端协议

`QaPage` 将当前 assistant 流式消息视为临时状态：

- `answer.delta`：追加文本；
- `citation.available`：绑定已验证引用；
- `answer.completed`：固化消息并允许反馈；
- `answer.refused`：移除空的临时消息并显示拒答；
- `run.failed`：删除本次临时 assistant 消息，再显示错误；
- fetch 被用户主动取消：删除临时消息，不显示伪失败。

`qaApi.ask` 接受或创建 `AbortSignal`，页面卸载与后续显式停止操作可取消请求。首版不新增“停止生成”按钮；该按钮属于独立 UX 变更，不是接通取消传播的前提。

## 8. 部署与配置契约

### 8.1 本地与 Compose

默认 Compose 保持 deterministic，不新增 Ollama 容器。启用远程/宿主机 Ollama 时通过环境变量设置 provider、base URL、model 和 outbound allowlist。macOS 容器访问宿主机使用安装文档明确的 `host.docker.internal`，不能把 `localhost` 误写为宿主机。

### 8.2 Helm

backend ConfigMap/values/schema 增加非敏感 Chat 配置。Ollama 当前无 API key，因此不扩展 existing Secret 固定 key；未来 provider 需要凭据时单独设计 Secret 契约。

NetworkPolicy 继续使用 `networkPolicy.externalEgress` 显式放行 Ollama selector 或 CIDR + port。chart 默认不开放 Ollama 出站。离线包文档说明 Ollama 与模型权重由安装者预置，不将模型权重打入 Veridex 交付包。

### 8.3 配置示例

```bash
VERIDEX_CHAT_PROVIDER=ollama
VERIDEX_OLLAMA_BASE_URL=http://ollama.internal:11434
VERIDEX_OLLAMA_CHAT_MODEL=qwen3:8b
VERIDEX_CHAT_TIMEOUT=60s
VERIDEX_OUTBOUND_ALLOWED_HOSTS=ollama.internal
VERIDEX_OUTBOUND_ALLOWED_PORTS=11434
VERIDEX_OUTBOUND_ALLOW_INSECURE_HTTP=true
```

若 embedding 与 chat 同时使用 Ollama，两者复用 base URL，但模型名分别由 `VERIDEX_OLLAMA_EMBEDDING_MODEL` 与 `VERIDEX_OLLAMA_CHAT_MODEL` 控制。

## 9. 测试策略

### 9.1 单元与组件测试

- provider selection：deterministic/ollama 各自只有一个 `ChatModel` Bean，非法 provider 启动失败；
- Ollama config：base URL 经过 outbound policy 校验，默认模型和覆盖模型正确；
- generation sync：prompt、拒答、引用、usage、错误分类保持兼容；
- generation stream：多 delta 顺序、完整聚合、空片段过滤、最终 usage、首 token 时延；
- invalid citation：已发送 delta 后终检失败，不产生 Completed；
- QA orchestration：事件顺序、成功落库、失败不落 assistant、取消状态幂等；
- Controller：SSE 事件逐步到达而非方法结束后批量返回；
- 前端：run.failed 丢弃 provisional 文本，completed 固化，AbortSignal 正确传递。

### 9.2 回归测试

默认 provider 为 deterministic，现有 backend/web 测试必须保持全绿。ACL、prompt injection、敏感输出、citation preview re-authorization 与 trace body 保护测试必须覆盖新的流式入口。

### 9.3 真实 Ollama 验收

真实 Ollama 不进入默认 CI，提供显式验证入口。验收至少覆盖：

- `qwen3:8b` 可达且返回真实增量；
- 首个 `answer.delta` 在流完成前到达；
- 最终回答引用通过并可预览；
- 模型不可达时 `MODEL_ERROR`，无 deterministic 静默回退；
- 客户端中断后 QueryRun 为 `CANCELLED`；
- prompt/completion 默认不进入应用日志和指标标签。

## 10. 验收标准

1. 零配置启动只装配 `DeterministicChatModel`，现有 CI 结果不变；
2. `VERIDEX_CHAT_PROVIDER=ollama` 只装配 `OllamaChatModel`，实际调用 `qwen3:8b` 或配置模型；
3. 在线问答在模型完成前发送至少一个真实 `answer.delta`；
4. 同步评测仍可执行，且与在线路径共享 prompt、拒答和引用规则；
5. 只有引用终检通过的回答才保存 assistant 消息并发送 `answer.completed`；
6. 引用失败发送 `run.failed`，前端不保留 provisional 文本；
7. 模型故障和超时不回退 deterministic，QueryRun/trace/metrics 记录稳定错误码；
8. 客户端断开取消上游模型流，QueryRun 进入 `CANCELLED`，不保存不完整回答；
9. Ollama 目标受 OutboundAccessPolicy 与 Helm NetworkPolicy 双重约束；
10. 默认日志、metrics、SSE 错误消息不泄露 prompt、证据、completion、内部 URL 或凭据；
11. backend、web、部署验证与真实 Ollama 显式验收全部通过；
12. README/architecture/配置文档明确 deterministic 是测试占位、Ollama 是首个真实 Chat provider。

## 11. 实施顺序

1. Chat provider 配置与条件装配；
2. GenerationService 同步/流式统一；
3. QA 编排与 Controller 改为端到端 Flux；
4. 引用失败、取消、超时与 trace/metrics 收尾；
5. 前端 provisional 消息和 AbortSignal；
6. Compose/Helm/NetworkPolicy/离线说明；
7. 自动化回归与真实 Ollama 验收；
8. 文档和阶段门禁。

该阶段完成并通过门禁后，才开始 Phase 5-e 备份恢复与容量设计。Phase 5-e 的在线负载场景必须同时报告 deterministic 基线与 Ollama `qwen3:8b` 真实模型结果，二者不可混为同一容量结论。
