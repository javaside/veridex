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

> 注意：当前在线入库链路仍使用 `StructureFirstChunker` 内硬编码的 `MAX_CHARS=2000` / `OVERLAP=80`，尚未读取 Profile 的 `chunking` 字段（chunking 维度目前主要用于版本化记录与后续接入）。

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

对应 `RefusalPolicy`（`minEvidenceChars`）与 QA 编排层的 `HISTORY_TURNS`（`maxHistoryTurns`）。

## 4. Prompt（提示词）

| 参数 | 默认值 | 含义 |
|---|---|---|
| `systemTemplate` | `你是企业制度问答助手。只允许使用以下证据回答，不得使用模型通用知识补全。\n` | 给大模型的系统提示词模板，设定角色与「只用证据、不补全」的约束 |

实际生成时会在模板后拼接 `[EVIDENCE 编号|标题|内容]` 的证据（见 `GenerationServiceImpl.buildSystemPrompt`）。

## 5. Model（模型）

| 参数 | 默认值 | 含义 |
|---|---|---|
| `chatModel` | `deterministic` | 生成回答用的对话模型标识 |
| `embeddingModel` | `deterministic` | 向量化用的嵌入模型标识 |

当前两个都默认 `deterministic`——这是确定性占位实现（`DeterministicChatModel` / `DeterministicEmbeddingModel`），用于开发与集成测试的可复现性，不产生真实语义。生产需替换为真实模型标识。

> 注意：评测运行会把 `chatModel` 记录到结果、把 `systemTemplate` 与 `minEvidenceChars` 真正传入生成链路；但 `chatModel` 目前只作为标识记录，未做模型路由（当前只有单一模型实现）。

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
- 「当前版本」即最高 `versionNo`。

## 8. 哪些参数真正在生效

| 维度 | 在线问答（`/api/qa/ask`） | 评测运行（`/api/evaluation/runs`） |
|---|---|---|
| chunking | 硬编码（`StructureFirstChunker`） | 仅记录，不驱动 |
| retrieval（topK/rrfK/context 预算） | 硬编码 `DEFAULT_PARAMETERS` | 由 Profile 驱动 |
| generation.minEvidenceChars | 硬编码 `DEFAULT_MIN_EVIDENCE_CHARS` | 由 Profile 驱动 |
| prompt.systemTemplate | 硬编码 `DEFAULT_SYSTEM_TEMPLATE` | 由 Profile 驱动 |
| model.chatModel | 硬编码 `"deterministic"` | 由 Profile 记录（不路由） |

即：**评测链路已参数化驱动 retrieval / generation / prompt；在线问答仍走硬编码默认值**。这解释了为什么「配置版本」模块当前能做「同一数据集对比两套配置」，但还不能让在线问答直接用某套配置。
