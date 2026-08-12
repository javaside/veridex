# 知识管理页交互打磨 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让知识管理页的当前检索版本一眼可辨（整行高亮 + 徽标）、删除确认换成风格一致的自定义弹窗、上传/发布状态变更通过 toast + 列表动画清晰反馈。

**Architecture:** 纯前端交互层改动。新建 `ConfirmDialog`（复用预览抽屉的遮罩/焦点管理模式）与 `Toast` 两个通用组件；`KnowledgePage` 统一管理 toast 与高亮目标 release id；`UploadForm` 通过 `onNotify` 回调上抛上传结果；`ReleaseList` 增加 active 行高亮、删除确认弹窗、flash 动画行。

**Tech Stack:** React 19 + TypeScript、`@phosphor-icons/react`、Vitest + Testing Library、原生 CSS。

## Global Constraints

- 纯前端改动，不改后端与 API。
- Toast 为单例（一次只显示一条），不引入 toast 队列。
- flash 动画遵循 `prefers-reduced-motion`（已有全局 reduce 样式覆盖）。
- 前端文案禁止 em dash / en dash 字符。
- 可见文案均为中文（沿用现有 UI 语言）。
- 删除确认取消 / Escape / 遮罩关闭均不发请求。

---

### Task 1: ConfirmDialog 组件

**Files:**
- Create: `web/src/components/ConfirmDialog.tsx`
- Create: `web/src/components/ConfirmDialog.test.tsx`
- Modify: `web/src/styles.css`

**Interfaces:**
- Produces: `ConfirmDialog({ open, title, description, confirmLabel, danger?, onConfirm, onCancel })`
  - `open: boolean`，false 时不渲染
  - `danger?: boolean`，true 时确认按钮用 danger 样式
  - 打开时聚焦确认按钮，Escape / 遮罩点击 / 取消按钮调 `onCancel`，确认按钮调 `onConfirm`
  - 无 `returnFocusRef` 需求（由调用方在 onCancel/onConfirm 里处理聚焦，简化）

- [ ] **Step 1: 写失败测试**

```tsx
import { fireEvent, render, screen } from '@testing-library/react'
import { expect, test, vi } from 'vitest'
import { ConfirmDialog } from './ConfirmDialog'

test('renders content and confirms on button click', () => {
  const onConfirm = vi.fn()
  const onCancel = vi.fn()
  render(<ConfirmDialog open title="删除索引发布" description="此操作无法撤销" confirmLabel="删除" danger onConfirm={onConfirm} onCancel={onCancel} />)

  expect(screen.getByRole('heading', { name: '删除索引发布' })).toBeInTheDocument()
  expect(screen.getByText('此操作无法撤销')).toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: '删除' }))
  expect(onConfirm).toHaveBeenCalledTimes(1)
  expect(onCancel).not.toHaveBeenCalled()
})

test('cancels on escape and backdrop', () => {
  const onConfirm = vi.fn()
  const onCancel = vi.fn()
  render(<ConfirmDialog open title="确认" description="d" confirmLabel="确定" onConfirm={onConfirm} onCancel={onCancel} />)

  fireEvent.keyDown(document, { key: 'Escape' })
  expect(onCancel).toHaveBeenCalledTimes(1)
  fireEvent.click(screen.getByLabelText('关闭对话框'))
  expect(onCancel).toHaveBeenCalledTimes(2)
  expect(onConfirm).not.toHaveBeenCalled()
})

test('renders nothing when closed', () => {
  render(<ConfirmDialog open={false} title="确认" description="d" confirmLabel="确定" onConfirm={() => {}} onCancel={() => {}} />)
  expect(screen.queryByRole('heading', { name: '确认' })).not.toBeInTheDocument()
})
```

- [ ] **Step 2: 运行测试确认失败**

```bash
cd web && npx vitest run src/components/ConfirmDialog.test.tsx
```
Expected: FAIL（组件不存在）。

- [ ] **Step 3: 实现组件**

```tsx
import { useEffect, useRef } from 'react'

export function ConfirmDialog({ open, title, description, confirmLabel, danger, onConfirm, onCancel }: {
  open: boolean
  title: string
  description: string
  confirmLabel: string
  danger?: boolean
  onConfirm: () => void
  onCancel: () => void
}) {
  const confirmRef = useRef<HTMLButtonElement>(null)

  useEffect(() => {
    if (!open) return
    const handleKey = (event: KeyboardEvent) => { if (event.key === 'Escape') onCancel() }
    document.addEventListener('keydown', handleKey)
    confirmRef.current?.focus()
    return () => document.removeEventListener('keydown', handleKey)
  }, [open, onCancel])

  if (!open) return null
  return (
    <div className="dialog-layer">
      <button className="dialog-backdrop" type="button" aria-label="关闭对话框" onClick={onCancel} />
      <div className="confirm-dialog" role="dialog" aria-modal="true" aria-label={title}>
        <h3>{title}</h3>
        <p>{description}</p>
        <div className="dialog-actions">
          <button className="secondary-button" type="button" onClick={onCancel}>取消</button>
          <button ref={confirmRef} className={danger ? 'danger-button' : 'primary-button'} type="button" onClick={onConfirm}>{confirmLabel}</button>
        </div>
      </div>
    </div>
  )
}
```

- [ ] **Step 4: 加样式**

`styles.css` 追加（沿用现有 token）：

```css
.dialog-layer { position: fixed; inset: 0; z-index: 30; display: grid; place-items: center; }
.dialog-backdrop { position: absolute; inset: 0; width: 100%; height: 100%; border: 0; border-radius: 0; background: rgba(20, 32, 38, .34); cursor: default; animation: fade-in .18s ease-out; }
.confirm-dialog { position: relative; width: min(420px, 92vw); padding: 22px; display: grid; gap: 10px; background: var(--surface-panel); border: 1px solid var(--border); border-radius: var(--radius-panel); box-shadow: 0 20px 60px rgba(25, 43, 39, .18); animation: dialog-in .18s ease-out; }
.confirm-dialog h3 { margin: 0; font-size: 16px; }
.confirm-dialog p { margin: 0; color: var(--text-secondary); font-size: 13px; line-height: 1.55; }
.dialog-actions { margin-top: 8px; display: flex; justify-content: flex-end; gap: 8px; }
.danger-button { padding: 9px 14px; border: 0; border-radius: var(--radius-control); background: var(--danger); color: #fff; font-weight: 650; cursor: pointer; }
.danger-button:hover { filter: brightness(.94); }
@keyframes dialog-in { from { opacity: .6; transform: scale(.97); } to { opacity: 1; transform: scale(1); } }
```

- [ ] **Step 5: 运行测试确认通过**

```bash
cd web && npx vitest run src/components/ConfirmDialog.test.tsx
```
Expected: PASS。

- [ ] **Step 6: 提交**

```bash
git add web/src/components web/src/styles.css
git commit -m "feat: add confirm dialog component"
```

---

### Task 2: Toast 组件

**Files:**
- Create: `web/src/components/Toast.tsx`
- Create: `web/src/components/Toast.test.tsx`
- Modify: `web/src/styles.css`

**Interfaces:**
- Produces: `Toast({ type, message, onDismiss })`
  - `type: 'success' | 'error'`
  - 右上角固定显示，3 秒后自动调 `onDismiss`，关闭按钮也可手动关闭

- [ ] **Step 1: 写失败测试**

```tsx
import { act, fireEvent, render, screen } from '@testing-library/react'
import { expect, test, vi } from 'vitest'
import { Toast } from './Toast'

test('renders message and dismisses on close button', () => {
  const onDismiss = vi.fn()
  render(<Toast type="success" message="已发布当前知识库" onDismiss={onDismiss} />)
  expect(screen.getByText('已发布当前知识库')).toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: '关闭通知' }))
  expect(onDismiss).toHaveBeenCalledTimes(1)
})

test('auto-dismisses after 3 seconds', async () => {
  vi.useFakeTimers()
  const onDismiss = vi.fn()
  render(<Toast type="error" message="发布失败" onDismiss={onDismiss} />)
  act(() => { vi.advanceTimersByTime(3000) })
  expect(onDismiss).toHaveBeenCalledTimes(1)
  vi.useRealTimers()
})
```

- [ ] **Step 2: 运行测试确认失败**

```bash
cd web && npx vitest run src/components/Toast.test.tsx
```
Expected: FAIL（组件不存在）。

- [ ] **Step 3: 实现组件**

```tsx
import { CheckCircle, X, XCircle } from '@phosphor-icons/react'
import { useEffect } from 'react'

export function Toast({ type, message, onDismiss }: { type: 'success' | 'error'; message: string; onDismiss: () => void }) {
  useEffect(() => {
    const timer = setTimeout(onDismiss, 3000)
    return () => clearTimeout(timer)
  }, [onDismiss])

  return (
    <div className={`toast toast-${type}`} role="status">
      {type === 'success' ? <CheckCircle size={18} aria-hidden="true" /> : <XCircle size={18} aria-hidden="true" />}
      <span>{message}</span>
      <button type="button" aria-label="关闭通知" onClick={onDismiss}><X size={16} aria-hidden="true" /></button>
    </div>
  )
}
```

- [ ] **Step 4: 加样式**

```css
.toast { position: fixed; top: 20px; right: 20px; z-index: 40; max-width: 360px; padding: 12px 14px; display: flex; align-items: center; gap: 9px; border-radius: var(--radius-control); box-shadow: 0 10px 30px rgba(25, 43, 39, .16); font-size: 13px; animation: toast-in .2s ease-out; }
.toast-success { background: var(--success-surface); color: var(--success); border: 1px solid color-mix(in srgb, var(--success) 28%, transparent); }
.toast-error { background: var(--danger-surface); color: var(--danger); border: 1px solid color-mix(in srgb, var(--danger) 28%, transparent); }
.toast button { width: 24px; height: 24px; padding: 0; display: grid; place-items: center; border: 0; border-radius: 6px; background: transparent; color: inherit; cursor: pointer; }
.toast button:hover { background: rgba(0, 0, 0, .06); }
@keyframes toast-in { from { opacity: 0; transform: translateY(-8px); } to { opacity: 1; transform: translateY(0); } }
```

- [ ] **Step 5: 运行测试确认通过**

```bash
cd web && npx vitest run src/components/Toast.test.tsx
```
Expected: PASS。

- [ ] **Step 6: 提交**

```bash
git add web/src/components web/src/styles.css
git commit -m "feat: add toast component"
```

---

### Task 3: 当前检索高亮 + 删除确认 + flash 动画（ReleaseList）

**Files:**
- Modify: `web/src/features/knowledge/components/ReleaseList.tsx`
- Modify: `web/src/styles.css`
- Test: `web/src/features/knowledge/KnowledgePage.test.tsx`

**Interfaces:**
- Consumes: `ConfirmDialog`（Task 1）、`Release`（含 `isActive`）、`highlightReleaseId?: string`
- Produces: `ReleaseList` 新增 prop `highlightReleaseId?: string`（新发布版本 id，用于 flash 动画）

- [ ] **Step 1: 写失败测试**

在 `KnowledgePage.test.tsx` 追加：

```tsx
test('shows confirm dialog before deleting a release', async () => {
  const fetchMock = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input)
    const json = (body: unknown) => Promise.resolve(new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } }))
    if (url === '/api/knowledge-bases') return json([{ id: 'kb-1', name: '员工知识', description: null, slug: 'employee' }])
    if (url.endsWith('/documents')) return json([])
    if (url.endsWith('/releases')) return json([{ releaseId: 'release-1', versionNo: 1, status: 'PUBLISHED', indexName: 'i1', aliasName: 'a', isActive: false, documentCount: 1, chunkCount: 3 }])
    return json([])
  })
  vi.stubGlobal('fetch', fetchMock)

  render(<KnowledgePage />)

  fireEvent.click(await screen.findByRole('button', { name: '删除发布 v1' }))
  expect(screen.getByRole('dialog', { name: '删除索引发布' })).toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: '取消' }))
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  expect(fetchMock.mock.calls.some(([input]) => String(input).includes('/delete'))).toBe(false)
})

test('marks the active release row with a highlight class', async () => {
  vi.stubGlobal('fetch', vi.fn((input: RequestInfo | URL) => {
    const url = String(input)
    const json = (body: unknown) => Promise.resolve(new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } }))
    if (url === '/api/knowledge-bases') return json([{ id: 'kb-1', name: '员工知识', description: null, slug: 'employee' }])
    if (url.endsWith('/documents')) return json([])
    if (url.endsWith('/releases')) return json([{ releaseId: 'r2', versionNo: 2, status: 'PUBLISHED', indexName: 'i2', aliasName: 'a', isActive: true, documentCount: 2, chunkCount: 10 }, { releaseId: 'r1', versionNo: 1, status: 'PUBLISHED', indexName: 'i1', aliasName: 'a', isActive: false, documentCount: 1, chunkCount: 3 }])
    return json([])
  }))

  render(<KnowledgePage />)

  const rows = await screen.findAllByRole('article')
  const activeRow = rows.find((row) => row.textContent?.includes('v2'))
  expect(activeRow).toHaveClass('active')
})
```

- [ ] **Step 2: 运行测试确认失败**

```bash
cd web && npx vitest run src/features/knowledge/KnowledgePage.test.tsx
```
Expected: FAIL（无确认弹窗、无 active class）。

- [ ] **Step 3: 实现**

`ReleaseList.tsx`：
- 加 props：`highlightReleaseId?: string`；
- 加 state：`confirmingDelete: Release | null`；
- 删除按钮 onClick 改为 `setConfirmingDelete(release)`（不再 `window.confirm`）；
- 渲染 `ConfirmDialog`（`open={confirmingDelete !== null}`，`onConfirm` 里执行 `act(release, 'delete')` 并 `setConfirmingDelete(null)`，`onCancel` 关闭）；
- active 行加 `release-row active` class；
- `highlightReleaseId === release.releaseId` 的行加 `flash` class。

- [ ] **Step 4: 加样式**

```css
.release-row.active { background: var(--surface-accent); border-left: 3px solid var(--accent); margin-left: -1px; padding-left: 11px; border-radius: var(--radius-control); }
.release-row.flash { animation: flash-highlight 1.2s ease-out; }
@keyframes flash-highlight { 0% { background: var(--surface-accent); } 100% { background: transparent; } }
```

- [ ] **Step 5: 运行测试确认通过**

```bash
cd web && npm test
```
Expected: PASS。

- [ ] **Step 6: 提交**

```bash
git add web/src/features/knowledge web/src/styles.css
git commit -m "feat: highlight active release and confirm deletion"
```

---

### Task 4: toast + 高亮联动（KnowledgePage + UploadForm + VersionList）

**Files:**
- Modify: `web/src/features/knowledge/KnowledgePage.tsx`
- Modify: `web/src/features/knowledge/components/UploadForm.tsx`
- Modify: `web/src/features/knowledge/components/VersionList.tsx`
- Test: `web/src/features/knowledge/KnowledgePage.test.tsx`

**Interfaces:**
- Consumes: `Toast`（Task 2）、`ConfirmDialog` 已有
- Produces:
  - `UploadForm` 新增 prop `onNotify?: (type: 'success' | 'error', message: string) => void`
  - `VersionList` 新增 prop `highlightReleaseId?: string`，透传给 `ReleaseList`
  - `KnowledgePage`：`toast` state、`highlightReleaseId` state，发布/上传后设置

- [ ] **Step 1: 写失败测试**

在 `KnowledgePage.test.tsx` 追加：

```tsx
test('shows a toast after publishing', async () => {
  const fetchMock = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input)
    const json = (body: unknown) => Promise.resolve(new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } }))
    if (url === '/api/knowledge-bases') return json([{ id: 'kb-1', name: '员工知识', description: null, slug: 'employee' }])
    if (url.endsWith('/documents')) return json([])
    if (url.endsWith('/releases/publish') && init?.method === 'POST') return json({ release: { releaseId: 'r1', versionNo: 1, status: 'PUBLISHED', indexName: 'i1', aliasName: 'a', isActive: true, documentCount: 1, chunkCount: 5 }, excludedCount: 0 })
    if (url.endsWith('/releases')) return json([{ releaseId: 'r1', versionNo: 1, status: 'PUBLISHED', indexName: 'i1', aliasName: 'a', isActive: true, documentCount: 1, chunkCount: 5 }])
    return json([])
  })
  vi.stubGlobal('fetch', fetchMock)

  render(<KnowledgePage />)

  fireEvent.click(await screen.findByRole('button', { name: '发布' }))
  expect(await screen.findByRole('status')).toHaveTextContent('已发布当前知识库')
})
```

（发布后 toast 的 message 与现有 `publishNotice` 不同：toast 用「已发布当前知识库」且自动消失，`publishNotice` 的 inline 提示可移除——见 Step 3。）

- [ ] **Step 2: 运行测试确认失败**

```bash
cd web && npx vitest run src/features/knowledge/KnowledgePage.test.tsx
```
Expected: 视实现而定，新增断言若 toast 未实现则失败。

- [ ] **Step 3: 实现**

`KnowledgePage.tsx`：
- 移除 `publishNotice`/`publishError` inline 提示（改由 toast 承担）；
- 加 `const [toast, setToast] = useState<{ type: 'success' | 'error'; message: string } | null>(null)`；
- 加 `const [highlightReleaseId, setHighlightReleaseId] = useState<string | null>(null)`；
- `publishNow`：成功后 `setToast({ type: 'success', message: result.excludedCount > 0 ? `本次发布未包含 ${result.excludedCount} 个文档` : '已发布当前知识库' })`、`setHighlightReleaseId(result.release.releaseId)`；失败 `setToast({ type: 'error', message: ... })`；
- 渲染 `{toast && <Toast type={toast.type} message={toast.message} onDismiss={() => setToast(null)} />}`；
- `VersionList` 传 `highlightReleaseId`。

`UploadForm.tsx`：
- 加 prop `onNotify?: (type: 'success' | 'error', message: string) => void`；
- 上传成功：`onNotify?.('success', '文档已提交处理')`；失败：`onNotify?.('error', ...)`；移除 `success` state 与 inline 提示。

`VersionList.tsx`：
- 加 prop `highlightReleaseId?: string`，透传给 `ReleaseList`。

- [ ] **Step 4: 运行测试确认通过**

```bash
cd web && npm test
```
Expected: PASS（`publishNotice` 相关旧断言需同步更新为 toast 断言）。

- [ ] **Step 5: 提交**

```bash
git add web/src/features/knowledge
git commit -m "feat: toast and highlight feedback for publish and upload"
```

---

### Task 5: 全量前端验证与收尾

**Files:**
- 无（仅验证）

- [ ] **Step 1: 全量前端验证**

```bash
cd web && npm test && npm run lint && npm run build && git diff --check
```
Expected: 全部通过。

- [ ] **Step 2: 浏览器验收（可选，人工）**

启动 dev server，验证：发布后 toast + 新版本高亮、当前检索行高亮、删除弹窗交互、上传成功 toast、Escape/遮罩关闭弹窗。
