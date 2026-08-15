# Phase 4-c 反馈转坏例 — 设计文档

> 日期：2026-08-15
> 状态：已与用户确认（管理员选 ANSWER/REFUSE / 固定枚举 reason code / 完整前端闭环）

## 1. 背景与目标

### 1.1 现状

- `QaController.feedback()` 空占位：无请求体、无落库，返回 204。
- 前端 `ChatMessage` 无点赞/点踩按钮，`qaApi` 无 feedback 调用。
- 无 `feedback` 模块；`message`/`query_run` 已有 `query_run_id` 关联。
- `trace` 已有 `QueryRun`/`RetrievalHit`/`Citation` 证据链；`evaluation` 已有 `addCase`（P4-a）。

### 1.2 本轮目标（Phase 4 子项目 c）

员工能对问答回答点赞/点踩（点踩带固定 reason code）；知识管理员能查看点踩反馈，并把一条反馈转成评测集工作集中的坏例（ANSWER/REFUSE）。

### 1.3 范围

- 反馈落库 + 员工点赞/点踩 + 管理员反馈列表 + 反馈转坏例。
- **不涉及**：评测运行与指标（P4-d）、配置/Profile 运行时接入（P4-b 仅定义）、版本对比与回归门禁（P4-e）、人工评审 PASS/MINOR_ISSUE/FAIL（P4-d）。

## 2. 已确认的设计决策

| # | 决策 | 说明 |
|---|---|---|
| F1 | **新建 `feedback` 模块** | `io.veridex.feedback`，边界 `{"shared", "iam::api"}`（自包含快照，不依赖 trace/evaluation/conversation）。 |
| F2 | **反馈自包含快照** | 落库时保存 `question`/`answer`/`evidence` 快照；转坏例直接复用快照，不跨模块读 trace。 |
| F3 | **转坏例期望行为由管理员选** | ANSWER：用反馈 evidence 快照预填；REFUSE：evidence 空。转坏例由前端编排（调 evaluation addCase + feedback 标记）。 |
| F4 | **固定 reason code 枚举** | `WRONG_ANSWER` / `HALLUCINATION` / `MISSING_EVIDENCE` / `OUTDATED` / `WRONG_REFUSAL` / `OTHER`。 |
| F5 | **完整前端闭环** | 员工问答页点赞/点踩 + 管理员反馈管理页（列表 + 转坏例）。 |
| F6 | **SSE `answer.completed` 扩展** | 事件携带 `runId`（= queryRunId），供前端反馈关联；`answer.refused`/`run.failed` 无 assistant message，不支持反馈。 |
| F7 | **迁移 V10** | `V10__feedback.sql` 新建 1 张表。 |
| F8 | **权限** | 反馈记录：所有认证用户；反馈列表/转坏例标记：`PLATFORM_ADMIN`/`KNOWLEDGE_ADMIN`。 |

## 3. 领域模型

```text
Feedback （不可变快照，一次点赞/点踩一条）
```

- `Feedback`：id、userId、queryRunId(可空)、rating(`UP`/`DOWN`)、reasonCode(点踩必填)、question(快照)、answer(快照)、evidence(JSONB 快照)、convertedCaseId(可空)、createdAt。

**evidence JSONB 结构**（与 P4-a `EvidenceRef` 对齐）：

```json
[{"documentVersionId":"<uuid>","chunkIndexes":[3,4]}]
```

## 4. 数据模型（V10__feedback.sql）

```sql
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

- `query_run_id` 无外键（快照语义，反馈可独立于 query 存活）。
- `converted_case_id` 无外键（避免 feedback→evaluation 的数据库级耦合；只作标记）。

## 5. API（feedback/api）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/feedback` | 记录反馈 `{queryRunId?, rating, reasonCode?, question, answer, evidence[]}` |
| GET | `/api/feedback?rating=DOWN` | 反馈列表（管理员，可选按 rating 过滤，倒序） |
| POST | `/api/feedback/{id}/converted` | 标记已转坏例 `{caseId}` |

DTO：`RecordFeedbackRequest`、`FeedbackView`、`MarkConvertedRequest`。

校验：`rating` 必为 `UP`/`DOWN`；`DOWN` 时 `reasonCode` 必填且为枚举；`question` 非空；`UP` 时 `reasonCode`/`evidence` 归一化为空。

## 6. 反馈语义

- 同一 queryRunId 可多条反馈（不同用户、不同时间），不做去重 upsert（简单、可追溯）。
- 点踩 feedback 是坏例来源；点赞 feedback 仅记录，不进入转坏例工作流。
- 转坏例 = 前端编排：`evaluation.addCase`（用反馈快照）→ 成功后 `feedback.markConverted`。
- 已 `converted_case_id` 的反馈在列表中标为「已转坏例」，不可重复转。

## 7. 前端

### 7.1 员工问答页（/workbench）

- assistant 消息下方加「点赞 / 点踩」按钮（`ThumbsUp`/`ThumbsDown`）。
- 点踩弹出 reason code 选择（6 个枚举，单选）。
- 提交 feedback：`question` = 用户输入，`answer` = 回答文本，`evidence` = 由 VALID citation 按 `documentVersionId` 分组构造，`queryRunId` = `answer.completed` 事件返回的 runId。

### 7.2 管理员反馈页（/feedback）

- 新增路由 `/feedback`，label「反馈管理」，englishLabel「Feedback」，icon `ChatCircleDots`。
- 列表只展示 `DOWN` 反馈：question、answer（截断）、reasonCode、时间、是否已转坏例。
- 「转坏例」按钮：弹窗选目标数据集（复用 `evaluationApi.list()`）+ 期望行为 ANSWER/REFUSE → 调 `evaluationApi.addCase` + `feedbackApi.markConverted`。

## 8. 测试

- 领域/服务单测：record 校验（rating/reasonCode/question）、UP 归一化、markConverted。
- `DatabaseMigrationTest` 追加 V10 断言（feedback 表存在）。
- 集成测试（Testcontainers Postgres）：`FeedbackIntegrationTest` 覆盖「记录→列表→标记已转」。
- `ArchitectureTest` 通过（feedback 依赖 shared + iam::api）。
- 前端 Vitest：员工点赞/点踩提交、管理员反馈列表 + 转坏例交互。

## 9. 验收标准

1. 员工对回答点赞/点踩；点踩必选 reason code；反馈落库。
2. 管理员能查看点踩反馈列表，并把一条反馈转成评测集工作集中的坏例（ANSWER 带 evidence / REFUSE 空）。
3. 已转坏例的反馈被标记，不可重复转。
4. `./scripts/verify.sh` 全绿。
