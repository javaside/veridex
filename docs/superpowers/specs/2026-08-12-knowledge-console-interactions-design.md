# 知识管理页交互打磨 — 设计文档

> 日期：2026-08-12
> 状态：已与产品负责人确认

## 1. 背景与问题

知识管理页的三个交互缺陷：

1. **当前检索不突出**：active 行与历史行视觉无差别，只能靠行内小字「当前检索」辨认，用户无法一眼看清线上版本。
2. **删除确认是浏览器原生弹窗**：`window.confirm` 的系统默认框与页面风格完全脱节，观感差。
3. **上传/发布反馈太弱**：上传成功只有一行小字，发布后列表刷新但新版本/状态变化不显眼，用户感知不到"页面动了"。

## 2. 已确认的设计决策

| # | 决策 | 说明 |
|---|---|---|
| D1 | **当前检索整行高亮 + 徽标** | active 行背景色 + 左侧强调边框 + 「当前检索」彩色徽标；历史/下架行保持浅色 |
| D2 | **自定义确认弹窗** | 用与页面风格一致的 `ConfirmDialog` 替换 `window.confirm`，复用预览抽屉的焦点管理模式；删除用 danger 样式 |
| D3 | **toast + 列表动画** | 上传/发布成功或失败右上角 toast 通知（自动消失）；新发布版本行带渐隐高亮动画 |

## 3. 组件结构

### 3.1 `ConfirmDialog`（新建 `web/src/components/ConfirmDialog.tsx`）

- 全屏遮罩 + 居中面板 + 焦点管理（打开聚焦确认按钮，关闭后焦点还给触发按钮）+ Escape/遮罩关闭；
- 接口：
  ```ts
  { open: boolean; title: string; description: string; confirmLabel: string; danger?: boolean; onConfirm: () => void; onCancel: () => void }
  ```
- 危险操作（删除）确认按钮用 `danger` 样式。

### 3.2 `Toast`（新建 `web/src/components/Toast.tsx`）

- 固定右上角，success / error 两种样式；
- 3 秒自动消失 + 手动关闭按钮；
- 接口：`{ type: 'success' | 'error'; message: string; onDismiss: () => void }`。

### 3.3 数据流

- `KnowledgePage` 统一管理 `toast`（`{ type, message } | null`）与 `highlightReleaseId`（发布成功后要动画的新版本 id）：
  - 发布成功 → toast「已发布当前知识库」+ 设置 `highlightReleaseId`；
  - 发布失败 → toast 错误；
- `UploadForm` 增加 `onNotify(type, message)` 回调上抛 → 上传成功 toast「文档已提交处理」/失败 toast，与发布共用同一个 toast 容器；
- `VersionList` → `ReleaseList` 透传 `highlightReleaseId`。

## 4. 当前检索高亮（D1）

- active 行加 `release-row.active`：
  - 背景色 `--surface-accent`；
  - 左侧 3px 强调边框（`--accent`）；
  - 「当前检索」改为醒目徽标（彩色圆角标，复用 `status-badge` 语义）；
- 历史/下架行保持现有浅色，视觉层级清晰。

## 5. 删除确认（D2）

- `ReleaseList` 点删除 → 打开 `ConfirmDialog`：
  - 标题「删除索引发布」；
  - 描述「删除后该版本索引将永久移除，此操作无法撤销。」；
  - 按钮「取消 / 删除」，删除按钮 danger 样式；
- 确认才发请求；取消 / Escape / 遮罩关闭均不发请求；
- 组件通用化，未来其它危险操作复用。

## 6. 列表动画（D3）

- 新发布的版本行（`releaseId === highlightReleaseId`）渲染时带 `flash` 动画：accent 背景 1.2s 渐隐回正常，让"新版本上线"一眼可见；
- 遵循 `prefers-reduced-motion` 约束（已有全局 reduce 样式）。

## 7. 测试影响

- `ReleaseList`：
  - 删除确认弹窗交互（确认发请求 / 取消不发）；
  - active 行高亮 class（`release-row.active`）；
  - `highlightReleaseId` 行带 flash class；
- `KnowledgePage`：
  - 发布成功 toast 出现；
  - UploadForm 上传成功 toast 上抛；
- 现有测试回归（发布 / 空态 / 设为当前 / 删除取消）。

## 8. 非目标 / 后续

- 不改后端与 API（纯前端交互层）；
- 不改文档列表 / 知识库列表的其它交互；
- Toast 为单例（一次只显示一条），不引入 toast 队列。
