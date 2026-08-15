# Phase 4-e 版本对比与回归门禁 — 设计文档

> 日期：2026-08-15
> 状态：已与用户确认（两个 run id / 硬编码默认阈值 / 纯计算）

## 1. 背景与目标

### 1.1 现状

- P4-d 已完成评测运行：`EvaluationRun`（status/指标 JSONB/profile 引用）+ `RunMetrics`（聚合指标）+ 同步执行器 + REST API + 前端 RunPanel。
- `RunMetrics` 字段：`avgRecallAt1/3/5`、`avgMrr`、`avgNdcgAt10`、`citationHitRate`、`refusalMatchRate`、`avgLatencyMs`、`caseCount`、`completedCount`。
- 尚缺「同一数据集对比两个不可变配置」与「回归门禁」的收尾能力。

### 1.2 本轮目标（Phase 4 子项目 e）

管理员选择两个已完成的评测运行（baseline / candidate），获得逐指标对比报告与回归门禁判定。纯计算、不落库。

### 1.3 范围

- 对比两个 run 的聚合指标（delta）。
- 回归门禁判定（硬编码默认阈值 + 完整性）。
- **不涉及**：门禁规则配置化（后续子项目）、对比/门禁结果持久化。

## 2. 已确认的设计决策

| # | 决策 | 说明 |
|---|---|---|
| G1 | **对比输入 = 两个 run id** | 管理员显式选 baseline / candidate run。 |
| G2 | **硬编码默认阈值** | 相对退化阈值 10%；四类关键指标参与判定。 |
| G3 | **纯计算** | 不落库，实时计算返回；历史可随时重算。 |
| G4 | **完整性优先** | 任一 run 非 COMPLETED 或 `completedCount != caseCount` → `INCOMPLETE`（不能通过门禁）。 |
| G5 | **latency 只报告不判定** | 对比表中展示，但不参与门禁（低延迟与高质量常冲突，暂不纳入）。 |

## 3. 门禁规则（硬编码）

**完整性**：baseline 与 candidate 均须 `status == COMPLETED` 且 `metrics.completedCount == metrics.caseCount`；否则 verdict = `INCOMPLETE`。

**相对退化**（越高越好指标，相对 baseline 下降超过阈值即 fail）：

| 指标 | 阈值 |
|---|---|
| `avgMrr` | 下降 > 10% |
| `avgNdcgAt10` | 下降 > 10% |
| `citationHitRate` | 下降 > 10% |
| `refusalMatchRate` | 下降 > 10% |

**verdict**：无失败项 → `PASS`；有失败项 → `FAIL`；不完整 → `INCOMPLETE`。

## 4. 领域/值对象（evaluation.api）

```java
enum GateVerdict { PASS, FAIL, INCOMPLETE }

record MetricComparison(String name, double baseline, double candidate, double delta) {}

record GateCheck(String metric, double baseline, double candidate, double maxAllowed) {}

record ComparisonResult(UUID baselineRunId, UUID candidateRunId,
                        RunMetrics baseline, RunMetrics candidate,
                        List<MetricComparison> metrics, GateVerdict verdict,
                        List<GateCheck> failedChecks) {}
```

- `delta = candidate - baseline`（越高越好指标，正 = 改善）。
- `maxAllowed` = `baseline * (1 - 0.10)`（允许的最低值）。
- `failedChecks` 仅含失败项。

## 5. 服务（evaluation.application）

`EvaluationComparisonService`：

```java
ComparisonResult compare(UUID baselineRunId, UUID candidateRunId);
```

- 加载两个 run → 反序列化 `RunMetrics`。
- 完整性检查 → `INCOMPLETE`。
- 计算 8 项指标对比（含 latency）。
- 对 4 项关键指标做相对退化检查。
- 返回 `ComparisonResult`。

## 6. API（evaluation/api）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/evaluation/comparisons` | `{baselineRunId, candidateRunId}` → `ComparisonResult` |

权限：`PLATFORM_ADMIN`/`KNOWLEDGE_ADMIN`。

## 7. 前端（/evaluation）

- RunPanel 增加「对比」区：两个 run 选择器（基线/候选）+ 对比按钮。
- 展示逐指标对比表 + 门禁 verdict（PASS/FAIL/INCOMPLETE）+ 失败项明细。

## 8. 测试

- `EvaluationComparisonServiceTest`：PASS / FAIL / INCOMPLETE 三类判定 + delta 计算 + 阈值边界。
- `ArchitectureTest`（边界不变）。
- 前端 `RunPanel.test.tsx` 补对比交互。

## 9. 验收标准

1. 选两个 run 能生成逐指标对比报告。
2. 任一指标相对退化 > 10% → `FAIL` 并列出失败项。
3. 任一 run 不完整 → `INCOMPLETE`。
4. `./scripts/verify.sh` 全绿。
