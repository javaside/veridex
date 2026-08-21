# RAG 配置参数语义

> 本文档解释「配置版本」（`/configuration`）模块里五维 RAG 配置参数的业务含义、默认值与代码位置。字段定义见 `backend/src/main/java/io/veridex/configuration/api/*Config.java`，内置默认值见 `ProfileDefaults`。

一套 RAG 配置（`ProfileConfig`）覆盖从「文档入库 → 检索 → 生成回答」的五个维度：`chunking` / `retrieval` / `generation` / `prompt` / `model`。每维都是固定结构（JSONB），草稿与发布快照共用同一结构。

## 1. Chunking（分块）

文档入库时被切成若干 chunk，这是检索的最小单位。

| 参数 | 默认值 | 含义 |
|---|---|---|
| `maxChars` | 2000 | 单个 chunk 的最大字符数。某章节/段落超过 2000 字符就硬切成多块 |
| `overlap` | 80 | 硬切时相邻两块重叠的字符数，避免一句话被切断、语义丢失 |

对应 `StructureFirstChunker`：先按 Markdown 1–3 级标题切章节，超长块再按 `maxChars` 硬切并保留 `overlap` 重叠。

> 注意：`chunking.maxChars` / `chunking.overlap` 已在入库 worker（`DocumentIngestionWorker`）读取「当前生效 Profile」驱动 `StructureFirstChunker`（未设置生效版本时回退 `ProfileDefaults` 的 2000/80）。分块发生在文档上传入库那一刻，属于快照参数：改配置后只对之后新入库的文档生效，已入库的存量文档需重新上传/重处理才会按新参数重切。

## 2. Retrieval（检索）

决定「怎么从知识库里找证据、拼多少进上下文」。

| 参数 | 默认值 | 含义 |
|---|---|---|
| `topKPerChannel` | 30 | 每一路检索（BM25 关键词 / 向量）各自召回多少条候选 |
| `rrfK` | 60 | RRF（倒数排名融合）算法里的常数 k，用于把 BM25 与向量两路排序合并成综合排名。k 越大，排名靠前的优势越平缓 |
| `contextTopK` | 6 | 融合重排后，最终塞进上下文给大模型看的证据条数上限 |
| `perDocumentMax` | 3 | 单个文档最多贡献几条证据，防止某文档霸屏、保证来源多样性 |
| `contextMaxChars` | 4000 | 上下文总字符预算（近似 token 预算），防止拼接的上下文过长 |

对应 `HybridSearchServiceImpl`（双路召回 + RRF 融合 + Rerank）与 `ContextAssemblyService`（按 topK / perDocumentMax / maxChars 组装证据）。

## 3. Generation（生成）

| 参数 | 默认值 | 含义 |
|---|---|---|
| `maxHistoryTurns` | 6 | 多轮对话时带多少轮历史消息给模型（在线 QA 编排层使用；评测每个 case 无历史，仅记录到快照） |
| `minEvidenceChars` | 50 | 拒答阈值：检索到的证据总字符数少于 50 就判定「证据不足」拒绝回答 |

对应 `RefusalPolicy`（`minEvidenceChars`）与 QA 编排层（`QuestionAnsweringServiceImpl`）读取 Profile 的 `maxHistoryTurns` 决定会话历史轮数。

## 4. Prompt（提示词）

| 参数 | 默认值 | 含义 |
|---|---|---|
| `systemTemplate` | `你是企业制度问答助手。只允许使用以下证据回答，不得使用模型通用知识补全。\n` | 给大模型的系统提示词模板，设定角色与「只用证据、不补全」的约束 |

实际生成时会在模板后**强制追加**引用格式指令（要求模型用 `[n]` 引用证据），再拼接 `[EVIDENCE 编号|标题|内容]` 的证据（见 `GenerationServiceImpl.buildSystemPrompt`）。引用指令与可配置模板解耦，保证真实模型（deepseek/ollama）也稳定输出 `[n]` 标记。

## 5. Model（模型）

| 参数 | 默认值 | 含义 |
|---|---|---|
| `chatModel` | `deterministic` | 生成回答用的对话模型标识（仅记录） |
| `embeddingModel` | `deterministic` | 向量化用的嵌入模型标识（仅记录） |

`chatModel` / `embeddingModel` 是配置 Profile 里的**标识记录字段**（默认值 `deterministic`），用于评测快照与结果可复现，**不参与运行时模型路由**。实际装配哪个模型由环境变量决定，与这两个字段解耦：

- 对话模型：`veridex.chat.provider`（默认 `deepseek`，模型 `deepseek-v4-flash`；也可 `ollama`；`deterministic` 为测试占位实现）
- 嵌入模型：`veridex.embedding.provider`（默认 `deterministic` 128 维确定性哈希；`ollama` 为真实语义 `qwen3-embedding` 1024 维）

> 注意：当前只有单一模型装配，`chatModel`/`embeddingModel` 字段值不会被用来在运行时切换 provider。要支持「按 Profile 切换模型」需先做多模型装配 + 路由，属未实现能力。

## 6. 五维 JSON 结构（草稿与快照共用）

```json
{
  "chunking": { "maxChars": 2000, "overlap": 80 },
  "retrieval": {
    "topKPerChannel": 30, "rrfK": 60, "contextTopK": 6,
    "perDocumentMax": 3, "contextMaxChars": 4000
  },
  "generation": { "maxHistoryTurns": 6, "minEvidenceChars": 50 },
  "prompt": { "systemTemplate": "你是企业制度问答助手。只允许使用以下证据回答……" },
  "model": { "chatModel": "deterministic", "embeddingModel": "deterministic" }
}
```

## 7. 版本化语义

- `ConfigurationProfile` 持有可变 `draft`（五维 JSONB）；`publish` 冻结为 `ConfigurationProfileVersion`（`versionNo` 递增），已发布版本不可改。
- 修改 `draft` 只作用于草稿，不影响任何已发布版本。
- 五维中任一为 null / 空白（prompt 模板、模型标识为空）则 `publish` 返回 400 拒绝。
- 「最新版本」即最高 `versionNo`；「当前生效版本」是独立概念，由知识管理员显式 `activateVersion` 设置 `active_version_no`（全局唯一，跨 Profile）。在线问答读「当前生效」版本，未设置时回退 `ProfileDefaults`；评测读「显式指定」版本，不受生效标记影响。

## 8. 哪些参数真正在生效

| 维度 | 在线问答（`/api/qa/ask`） | 评测运行（`/api/evaluation/runs`） |
|---|---|---|
| chunking（maxChars / overlap） | 读取「当前生效 Profile」（入库时，未设置生效版本回退默认值） | 记录到快照，不驱动评测（评测不重新分块） |
| retrieval（topK / rrfK / context 预算） | 读取「当前生效 Profile」 | 读取「指定版本 Profile」 |
| generation.minEvidenceChars | 读取「当前生效 Profile」 | 读取「指定版本 Profile」 |
| generation.maxHistoryTurns | 读取「当前生效 Profile」（会话历史轮数） | 记录到快照（评测单轮无历史） |
| prompt.systemTemplate | 读取「当前生效 Profile」 | 读取「指定版本 Profile」 |
| model.chatModel / embeddingModel | 仅记录（不路由） | 仅记录（不路由） |

即：**评测链路与在线问答都已参数化驱动 retrieval / generation / prompt（在线问答额外驱动 chunking 与 maxHistoryTurns）；model 维度始终仅记录、不做运行时路由**。两者区别在于读取哪个版本：在线问答读「当前生效」版本，评测读「显式指定」版本，因此可以用同一数据集对比两套配置。
