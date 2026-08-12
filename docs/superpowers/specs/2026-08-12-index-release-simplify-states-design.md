# 索引发布状态简化：移除回滚，改为「设为当前」— 设计文档

> 日期：2026-08-12
> 状态：已与产品负责人逐节确认

## 1. 背景与问题

### 1.1 现状

全量快照发布（2026-08-12 实现）后，`IndexRelease` 的状态模型为：

```
DRAFT → PUBLISHED → (ROLLED_BACK | OFFLINE)
```

加上 `isActive` 标记，「当前检索」由 `isActive` 决定，而 `status` 承担了多层含义。

### 1.2 由此产生的问题

1. **回滚语义在全量快照下无意义**：每次发布 = 知识库全部就绪文档的快照（V3 ⊇ V2 ⊇ V1 内容叠加）。回滚只是"丢掉最新发布引入的文档"，再回滚 = 更空，没有独立价值；它是单文档版本/增量索引时代的遗产概念。
2. **连锁状态混乱**：点 v3 回滚 → alias 切到 v2，但 v2 状态仍是 PUBLISHED；再点 v2 回滚 → v1 变 active。每步操作后谁 active、谁 PUBLISHED、谁 ROLLED_BACK 混在一起，`ROLLED_BACK` 既代表"被顶掉"又代表"主动回滚"。
3. **全部回滚后无任何提示**：所有快照变 ROLLED_BACK、无 active 版本、检索为空，页面只显示一排「已回滚」+ 删除按钮，用户完全看不懂怎么办。
4. **状态字段承担两种意思**：`PUBLISHED` 既表示"当前生效"又表示"曾生效"；前端只能靠 `isActive` 猜，展示混乱。

## 2. 已确认的设计决策

| # | 决策 | 说明 |
|---|---|---|
| D1 | **删除 ROLLED_BACK 状态** | `IndexReleaseStatus` 只保留 `DRAFT / PUBLISHED / OFFLINE` |
| D2 | **「当前检索」完全由 `isActive` 唯一决定** | 与 status 正交；同一知识库至多一个 `isActive=true` 快照（发布/设为当前/下架时维护此不变量） |
| D3 | **删除「回滚」操作，新增「设为当前」** | 对任意非当前快照（PUBLISHED 或 OFFLINE）点「设为当前」→ alias 原子切到它 → 原当前降级、它成为当前；已下架快照可一键恢复 |
| D4 | **空态保护** | 列表非空但无 active 快照时，顶部醒目提示「当前无检索版本，点击发布恢复」 |
| D5 | **迁移归一** | V6 把现有 `ROLLED_BACK` 记录归为 `PUBLISHED`（历史版本），不丢数据、不重建索引 |

## 3. 领域模型变化

### 3.1 `IndexReleaseStatus`

```
当前：DRAFT → PUBLISHED → (ROLLED_BACK | OFFLINE)
改为：DRAFT → PUBLISHED → OFFLINE
```

删除 `ROLLED_BACK`。

### 3.2 `IndexRelease`

- 删除 `rollback()` 方法（`status = ROLLED_BACK`）；
- 保留 `publish()` / `offline()` / `markActive()` / `markInactive()` / `setStats()`；
- 新增 `reactivate()`：把 OFFLINE 快照恢复为 PUBLISHED（`status = PUBLISHED`），供「设为当前」复用。

### 3.3 展示映射（`ReleaseView.isActive` + `status`）

| isActive | status | 页面显示 | 可用操作 |
|---|---|---|---|
| true | PUBLISHED | 当前检索 | 下架 |
| false | PUBLISHED | 历史版本 | 设为当前、删除 |
| false | OFFLINE | 已下架 | 设为当前、删除 |

## 4. 操作语义

| 操作 | 前置 | 行为 |
|---|---|---|
| **设为当前 makeCurrent** | 非当前检索快照（PUBLISHED 或 OFFLINE） | `gateway.aliasTo(alias, 该快照索引)` → 原当前快照 `markInactive()` → 该快照 `markActive()`；若原为 OFFLINE 则 `reactivate()`（status→PUBLISHED） |
| **下架 offline** | 当前检索快照 | `gateway.removeAlias(alias)` → `markInactive()` → status=OFFLINE → 该知识库暂时无当前版本 |
| **删除 delete** | 非当前检索快照 | 物理删索引 + 记录；当前检索快照不可删（须先下架），报错「当前检索版本，需先下架」 |
| **发布 publish** | 有 READY 文档 | 不变：建新快照 → 写 chunk → 切 alias → 新快照 `markActive()`，原当前降级 |

**不变量**：同一知识库至多一个 `isActive=true`。

**心智模型**：
- 发布 = 把当前文档固化为新快照并上线
- 设为当前 = 明确选择用哪个旧快照（含恢复已下架的）
- 下架 = 临时移除（检索变空）
- 删除 = 永久移除

## 5. 数据迁移（V6）

```sql
-- 现有 ROLLED_BACK 快照 → 归为 PUBLISHED（历史版本，非当前）
UPDATE index_release SET status = 'PUBLISHED' WHERE status = 'ROLLED_BACK';
```

- 迁移后：所有非当前、未下架的旧快照都是 `PUBLISHED`（历史版本），`isActive` 保持现状；
- 若迁移前已"全部回滚"导致无 active 快照 → 保持无 active（触发空态保护），不自动恢复；
- 幂等，可重复执行。

## 6. 前端呈现

- 列表行：`vN` + 状态角标（当前检索 / 历史版本 / 已下架）+ `X 份文档 / Y chunks` 统计 + 操作按钮；
- 按钮映射：
  - 历史版本 / 已下架行 → 「设为当前」+「删除」
  - 当前检索行 → 「下架」
  - 删除按钮：非当前行可用；当前行禁用并提示「需先下架」；
- **空态保护**：`releases.length > 0 && 无 active` 时，列表顶部显示醒目提示「当前无检索版本，点击发布恢复」；发布按钮常驻可用。

## 7. 测试影响

**后端**：

- `IndexReleaseServiceTest`：改「设为当前」语义（active 不变量、OFFLINE→PUBLISHED 恢复、删除拒绝 active）；
- `IngestionExitGateTest`：offline 场景保留；原 rollback 场景改为 makeCurrent；
- `DatabaseMigrationTest`：V6 迁移断言（ROLLED_BACK 归一为 PUBLISHED、版本号含 6）。

**前端**：

- `ReleaseList` / `KnowledgePage` 测试：「设为当前」按钮、空态提示、按钮映射断言。

## 8. 非目标 / 后续

- 不改检索查询（Phase 3 仍基于 alias + 快照清单）；
- 不引入"发布时自动恢复旧版本"等新行为；
- 不改动上传 / 解析 / 分块 / embedding 链路。
