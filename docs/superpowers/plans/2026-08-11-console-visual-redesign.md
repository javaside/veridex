# Veridex Console Visual Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 Veridex 登录页、应用框架、知识管理和占位工作区重构为克制、清晰、可访问的企业知识管理控制台，同时保留现有路由、API 和业务流程。

**Architecture:** 使用现有 React 19、React Router 和原生 CSS，建立语义 CSS Token、全局 AppShell 和聚焦业务组件。远程数据区域显式维护 loading、empty、error、success 状态；知识管理由页面编排知识库列表、上传、文档版本、Release 和预览侧板。

**Tech Stack:** React 19、TypeScript 6、React Router 7、原生 CSS、`@phosphor-icons/react`、Vitest 4、Testing Library、Vite 8。

## Global Constraints

- 视觉方向为 Fluent UI 启发的克制 B2B 产品语言，但不引入 Fluent UI 组件包。
- `DESIGN_VARIANCE: 5`、`MOTION_INTENSITY: 3`、`VISUAL_DENSITY: 6`。
- 保持 `/login`、`/workbench`、`/knowledge`、`/evaluation`、`/admin` 路由不变。
- 保持现有后端 API、Session 认证、上传限制和 Release 语义不变。
- 页面锁定浅色主题，以低饱和墨绿作为唯一非语义强调色。
- 内容面板、输入、按钮、状态标签分别使用 12px、8px、8px、6px 圆角。
- 禁止 AI 紫色渐变、玻璃拟态、外发光、虚构指标、营销 Hero、装饰性状态点和手写 SVG。
- 可见文案中不得使用 em dash 或 en dash 字符。
- 所有远程区域必须具备 loading、empty、error 和 success 表现；写操作必须防重复提交。
- CSS 动效只使用 transform 和 opacity，并支持 `prefers-reduced-motion`。
- 小于 768px 时使用顶部导航、单列内容、堆叠版本行和全屏预览层。
- 本次只修改前端和前端依赖；验证范围为前端测试、Lint、构建和浏览器检查，不运行完整后端测试。

---

## 文件结构

### 新增

- `web/src/app/AppShell.tsx`：全局导航、用户信息、退出和内容容器。
- `web/src/app/PageHeader.tsx`：统一页面标题、说明、摘要和操作插槽。
- `web/src/app/ComingSoonPage.tsx`：三个未实现工作区的真实路线图占位页。
- `web/src/features/knowledge/components/KnowledgeBaseList.tsx`：知识库读取、选择、创建和区域状态。
- `web/src/features/knowledge/components/ReleaseList.tsx`：Release 显示、状态操作和错误反馈。
- `web/src/features/knowledge/components/PreviewDrawer.tsx`：解析文本侧边预览和焦点管理。
- `web/src/features/knowledge/components/StatusBadge.tsx`：文档版本与 Release 状态标签。
- `web/src/features/auth/LoginPage.test.tsx`：登录交互回归测试。
- `web/src/features/knowledge/KnowledgePage.test.tsx`：知识管理状态与主流程测试。

### 修改

- `web/package.json`、`web/package-lock.json`：加入 `@phosphor-icons/react`。
- `web/src/app/App.tsx`：认证编排和 AppShell 接入。
- `web/src/app/routes.tsx`：路由元数据和 ComingSoonPage。
- `web/src/app/App.test.tsx`：新框架、导航、退出和占位页测试。
- `web/src/features/auth/LoginPage.tsx`：分栏登录、忙碌和错误状态。
- `web/src/features/knowledge/KnowledgePage.tsx`：知识管理页面编排。
- `web/src/features/knowledge/components/UploadForm.tsx`：文件摘要、上传反馈和错误状态。
- `web/src/features/knowledge/components/VersionList.tsx`：文档、版本、错误和预览入口。
- `web/src/features/knowledge/knowledgeApi.ts`：统一响应错误、收窄 Release action 类型。
- `web/src/styles.css`：完整 Token、布局、组件、响应式和 reduced-motion 样式。

---

### Task 1: 建立应用框架和统一占位页面

**Files:**
- Create: `web/src/app/AppShell.tsx`
- Create: `web/src/app/PageHeader.tsx`
- Create: `web/src/app/ComingSoonPage.tsx`
- Modify: `web/src/app/App.tsx`
- Modify: `web/src/app/routes.tsx`
- Modify: `web/src/app/App.test.tsx`
- Modify: `web/package.json`
- Modify: `web/package-lock.json`

**Interfaces:**
- `AppShell({ user, onLogout, children })` consumes `CurrentUser` and renders the authenticated shell.
- `PageHeader({ title, description, meta?, actions? })` renders the common workspace heading.
- `ComingSoonPage({ title, description, phase, capabilities })` renders one unfinished route.
- `WorkspaceRoute` produces `path`, `label`, `englishLabel`, `icon`, and `content`.

- [ ] **Step 1: 安装统一图标库**

Run:

```bash
npm --prefix web install @phosphor-icons/react
```

Expected: `web/package.json` and lockfile contain `@phosphor-icons/react`; no other UI dependency is added.

- [ ] **Step 2: 先扩展 App 测试**

在 `App.test.tsx` 增加以下用户行为断言：

```tsx
import { fireEvent, render, screen, waitFor } from '@testing-library/react'

// 保留现有 fetch stub，并让 logout 返回 204。
test('renders authenticated shell identity and logout control', async () => {
  render(<MemoryRouter initialEntries={['/knowledge']}><App /></MemoryRouter>)

  expect(await screen.findByText('管理员')).toBeInTheDocument()
  expect(screen.getByText('PLATFORM_ADMIN')).toBeInTheDocument()
  expect(screen.getByRole('button', { name: '退出登录' })).toBeInTheDocument()
})

test('logs out and returns to login', async () => {
  render(<MemoryRouter initialEntries={['/knowledge']}><App /></MemoryRouter>)
  fireEvent.click(await screen.findByRole('button', { name: '退出登录' }))
  await waitFor(() => expect(screen.getByRole('heading', { name: '欢迎回来' })).toBeInTheDocument())
})

test.each([
  ['/workbench', 'Phase 3', '员工问答'],
  ['/evaluation', 'Phase 4', '质量评测'],
  ['/admin', 'Phase 5', '平台管理'],
])('renders planned workspace state for %s', async (path, phase, title) => {
  render(<MemoryRouter initialEntries={[path]}><App /></MemoryRouter>)
  expect(await screen.findByRole('heading', { name: title })).toBeInTheDocument()
  expect(screen.getByText(phase)).toBeInTheDocument()
  expect(screen.getByRole('link', { name: '前往知识管理' })).toHaveAttribute('href', '/knowledge')
})
```

- [ ] **Step 3: 运行测试确认失败**

Run:

```bash
npm --prefix web test -- src/app/App.test.tsx
```

Expected: FAIL because logout control, planned workspace content and new login heading do not exist.

- [ ] **Step 4: 实现路由元数据和框架组件**

`routes.tsx` 使用 Phosphor 图标，三个占位页提供真实阶段内容：

```tsx
import { ChatCircleText, Database, Gauge, ShieldCheck } from '@phosphor-icons/react'
import type { ComponentType, ReactNode } from 'react'
import { ComingSoonPage } from './ComingSoonPage'
import { KnowledgePage } from '../features/knowledge/KnowledgePage'

export type WorkspaceRoute = {
  path: string
  label: string
  englishLabel: string
  icon: ComponentType<{ size?: number; weight?: 'regular' | 'fill' }>
  content: ReactNode
}

export const workspaceRoutes: WorkspaceRoute[] = [
  {
    path: '/workbench', label: '员工问答', englishLabel: 'Workbench', icon: ChatCircleText,
    content: <ComingSoonPage title="员工问答" phase="Phase 3" description="基于授权知识范围生成可验证答案。" capabilities={['混合检索与重排', '流式回答', '引用校验']} />,
  },
  {
    path: '/knowledge', label: '知识管理', englishLabel: 'Knowledge', icon: Database,
    content: <KnowledgePage />,
  },
  {
    path: '/evaluation', label: '质量评测', englishLabel: 'Evaluation', icon: Gauge,
    content: <ComingSoonPage title="质量评测" phase="Phase 4" description="比较不可变配置并分析失败案例。" capabilities={['版本化数据集', '检索与回答指标', '回归门禁']} />,
  },
  {
    path: '/admin', label: '平台管理', englishLabel: 'Administration', icon: ShieldCheck,
    content: <ComingSoonPage title="平台管理" phase="Phase 5" description="管理安全、可观测性与企业部署。" capabilities={['访问治理', '运行监控', '备份与恢复']} />,
  },
]
```

`AppShell` 必须使用 `<aside className="app-sidebar">`、`<nav aria-label="平台工作区">`、头像缩写、角色和“退出登录”按钮；`ComingSoonPage` 使用 `PageHeader` 和 `/knowledge` 链接，不渲染虚构数据。

- [ ] **Step 5: 接入认证和退出流程**

在 `App.tsx` 中新增：

```tsx
const logout = async () => {
  await authApi.logout()
  setUser(null)
}

return (
  <AppShell user={user} onLogout={logout}>
    <Routes>{/* 保持现有路由 */}</Routes>
  </AppShell>
)
```

加载态使用带 `role="status"` 的品牌骨架，而不是纯文本。

- [ ] **Step 6: 运行框架测试**

Run:

```bash
npm --prefix web test -- src/app/App.test.tsx
```

Expected: PASS.

- [ ] **Step 7: 提交任务**

```bash
git add web/package.json web/package-lock.json web/src/app
 git commit -m "feat: redesign authenticated console shell"
```

---

### Task 2: 重构登录体验

**Files:**
- Create: `web/src/features/auth/LoginPage.test.tsx`
- Modify: `web/src/features/auth/LoginPage.tsx`

**Interfaces:**
- `LoginPage` continues calling `authApi.login(username, password)` and redirects to `/knowledge` on success.
- Visible states: idle, submitting, error.

- [ ] **Step 1: 编写登录交互测试**

```tsx
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, expect, test, vi } from 'vitest'
import { LoginPage } from './LoginPage'

beforeEach(() => vi.restoreAllMocks())

test('renders labeled fields and local account guidance', () => {
  render(<LoginPage />)
  expect(screen.getByRole('heading', { name: '欢迎回来' })).toBeInTheDocument()
  expect(screen.getByLabelText('用户名')).toHaveAttribute('autocomplete', 'username')
  expect(screen.getByLabelText('密码')).toHaveAttribute('autocomplete', 'current-password')
  expect(screen.getByText(/admin \/ veridex/)).toBeInTheDocument()
})

test('disables submission while login is pending', async () => {
  vi.stubGlobal('fetch', vi.fn(() => new Promise(() => {})))
  render(<LoginPage />)
  fireEvent.change(screen.getByLabelText('用户名'), { target: { value: 'admin' } })
  fireEvent.change(screen.getByLabelText('密码'), { target: { value: 'veridex' } })
  fireEvent.click(screen.getByRole('button', { name: '登录' }))
  expect(await screen.findByRole('button', { name: '正在登录' })).toBeDisabled()
})

test('shows an accessible inline error', async () => {
  vi.stubGlobal('fetch', vi.fn(() => Promise.resolve(new Response('', { status: 401 }))))
  render(<LoginPage />)
  fireEvent.click(screen.getByRole('button', { name: '登录' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('登录失败')
})
```

- [ ] **Step 2: 运行测试确认失败**

Run: `npm --prefix web test -- src/features/auth/LoginPage.test.tsx`

Expected: FAIL because new heading, account guidance and submitting state are missing.

- [ ] **Step 3: 实现分栏登录页**

`LoginPage.tsx` 增加 `submitting`，提交前清空错误，finally 恢复按钮。DOM 使用：

```tsx
<div className="login-page">
  <section className="login-brand" aria-label="Veridex 产品介绍">
    <div className="brand-lockup"><span className="brand-mark">V</span><span>Veridex</span></div>
    <h1>让企业知识成为可验证的答案。</h1>
    <p>统一管理文档、处理版本和检索发布，为带权限的 RAG 查询准备可靠知识底座。</p>
    <ul className="capability-list"><li>异步文档处理</li><li>不可变索引发布</li><li>权限与审计边界</li></ul>
  </section>
  <main className="login-panel">
    <form className="login-form" onSubmit={submit}>
      <header><p className="section-kicker">企业知识控制台</p><h2>欢迎回来</h2><p>登录后继续管理知识入库。</p></header>
      {/* 显式 label + input */}
      {error && <p className="form-error" role="alert">{error}</p>}
      <button disabled={submitting}>{submitting ? '正在登录' : '登录'}</button>
      <p className="local-account-note">本地开发账号：<strong>admin / veridex</strong></p>
    </form>
  </main>
</div>
```

可见文案不得出现 em dash。

- [ ] **Step 4: 验证登录测试**

Run: `npm --prefix web test -- src/features/auth/LoginPage.test.tsx src/app/App.test.tsx`

Expected: PASS.

- [ ] **Step 5: 提交任务**

```bash
git add web/src/features/auth
 git commit -m "feat: redesign console login experience"
```

---

### Task 3: 建立知识库页面状态和上传反馈

**Files:**
- Create: `web/src/features/knowledge/KnowledgePage.test.tsx`
- Create: `web/src/features/knowledge/components/KnowledgeBaseList.tsx`
- Modify: `web/src/features/knowledge/KnowledgePage.tsx`
- Modify: `web/src/features/knowledge/components/UploadForm.tsx`
- Modify: `web/src/features/knowledge/knowledgeApi.ts`

**Interfaces:**
- `KnowledgeBaseList({ bases, selectedId, loading, error, onSelect, onRetry, onCreate })` owns only create-form UI state.
- `UploadForm({ kbId, onUploaded })` exposes upload success through callback and all feedback locally.
- `KnowledgePage` owns list loading/error, selected base and refresh counter.

- [ ] **Step 1: 编写知识库加载、空和错误测试**

```tsx
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, expect, test, vi } from 'vitest'
import { KnowledgePage } from './KnowledgePage'

beforeEach(() => vi.restoreAllMocks())

test('selects the first knowledge base after loading', async () => {
  vi.stubGlobal('fetch', vi.fn((input: RequestInfo | URL) => {
    const url = String(input)
    const body = url.endsWith('/api/knowledge-bases') ? [{ id: 'kb-1', name: '产品知识', description: null, slug: 'product' }] : []
    return Promise.resolve(Response.json(body))
  }))
  render(<KnowledgePage />)
  expect(await screen.findByRole('heading', { name: '产品知识' })).toBeInTheDocument()
})

test('shows a useful empty state', async () => {
  vi.stubGlobal('fetch', vi.fn(() => Promise.resolve(Response.json([]))))
  render(<KnowledgePage />)
  expect(await screen.findByText('创建第一个知识库')).toBeInTheDocument()
})

test('shows and retries a list error', async () => {
  const fetchMock = vi.fn()
    .mockResolvedValueOnce(new Response('', { status: 500 }))
    .mockResolvedValueOnce(Response.json([]))
  vi.stubGlobal('fetch', fetchMock)
  render(<KnowledgePage />)
  fireEvent.click(await screen.findByRole('button', { name: '重试' }))
  await waitFor(() => expect(screen.getByText('创建第一个知识库')).toBeInTheDocument())
})
```

- [ ] **Step 2: 运行测试确认失败**

Run: `npm --prefix web test -- src/features/knowledge/KnowledgePage.test.tsx`

Expected: FAIL because errors are swallowed and first base is not auto-selected.

- [ ] **Step 3: 收紧 API 错误处理**

将 `knowledgeApi.ts` 的 helper 改为：

```ts
const json = async <T>(response: Response): Promise<T> => {
  if (!response.ok) {
    const message = await response.text().catch(() => '')
    throw new Error(message || `请求失败 (${response.status})`)
  }
  return (response.status === 204 ? null : await response.json()) as T
}
```

`parsed` 同样先检查 `response.ok` 再 `text()`。删除 Release action 中不存在的 `'publish'`，类型收窄为 `'rollback' | 'offline' | 'delete'`。

- [ ] **Step 4: 实现 KnowledgeBaseList 和页面编排**

`KnowledgePage` 使用显式 `loadBases`，设置 loading/error；成功后保留仍存在的选择，否则选第一项。页面结构：

```tsx
<div className="workspace-page knowledge-page">
  <PageHeader title="知识管理" description="管理知识库、文档版本和当前检索发布。" meta={`${bases.length} 个知识库`} actions={<button onClick={openCreate}>新建知识库</button>} />
  <div className="knowledge-layout">
    <KnowledgeBaseList ... />
    <section className="knowledge-workspace">...</section>
  </div>
</div>
```

创建成功后将返回 KnowledgeBase 加入列表并选中；失败保留输入并显示 `role="alert"`。

- [ ] **Step 5: 完善 UploadForm**

增加 `selectedFile`、`error`、`success`。文件选择后显示格式化文件名和大小；提交按钮必须有选中文件才启用。上传反馈使用：

```tsx
{error && <p className="inline-message error" role="alert">{error}</p>}
{success && <p className="inline-message success" role="status">文档已提交处理</p>}
```

保留原生 `<input type="file" accept=".pdf,.docx,.txt,.md">`，显示“支持 PDF、DOCX、TXT、Markdown，最大 50 MB”。

- [ ] **Step 6: 验证知识库与上传测试**

Run:

```bash
npm --prefix web test -- src/features/knowledge/KnowledgePage.test.tsx
```

Expected: PASS.

- [ ] **Step 7: 提交任务**

```bash
git add web/src/features/knowledge
 git commit -m "feat: add resilient knowledge workspace states"
```

---

### Task 4: 重构文档版本、Release 和解析预览

**Files:**
- Create: `web/src/features/knowledge/components/StatusBadge.tsx`
- Create: `web/src/features/knowledge/components/PreviewDrawer.tsx`
- Create: `web/src/features/knowledge/components/ReleaseList.tsx`
- Modify: `web/src/features/knowledge/components/VersionList.tsx`
- Modify: `web/src/features/knowledge/KnowledgePage.test.tsx`

**Interfaces:**
- `StatusBadge({ status, kind })` maps status to localized label and semantic class.
- `PreviewDrawer({ title, content, open, onClose, returnFocusRef })` closes via button and Escape.
- `ReleaseList({ kbId, releases, loading, error, onChanged, onRetry })` owns row-level pending/error state.
- `VersionList({ kbId, refreshKey, onSummaryChange? })` loads documents/releases and renders two distinct sections.

- [ ] **Step 1: 增加版本、预览和 Release 测试**

在 `KnowledgePage.test.tsx` 增加完整 fetch 路由 stub，并断言：

```tsx
test('expands versions and opens a closable preview drawer', async () => {
  render(<KnowledgePage />)
  fireEvent.click(await screen.findByRole('button', { name: /员工手册/ }))
  expect(await screen.findByText('v1')).toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: '预览 v1' }))
  expect(await screen.findByRole('dialog', { name: '员工手册 v1 解析预览' })).toBeInTheDocument()
  fireEvent.keyDown(document, { key: 'Escape' })
  await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
})

test('confirms destructive release deletion', async () => {
  const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false)
  render(<KnowledgePage />)
  fireEvent.click(await screen.findByRole('button', { name: '删除发布 v1' }))
  expect(confirm).toHaveBeenCalledWith('删除该索引发布？此操作无法撤销。')
  expect(fetch).not.toHaveBeenCalledWith(expect.stringContaining('/delete'), expect.anything())
})
```

- [ ] **Step 2: 运行测试确认失败**

Run: `npm --prefix web test -- src/features/knowledge/KnowledgePage.test.tsx`

Expected: FAIL because dialog semantics, Escape close and delete confirmation are absent.

- [ ] **Step 3: 实现 StatusBadge**

使用完整映射：

```tsx
const LABELS: Record<string, string> = {
  UPLOADED: '已上传', PROCESSING: '处理中', READY: '就绪', FAILED: '失败', OFFLINE: '离线',
  DRAFT: '草稿', PUBLISHED: '已发布', ROLLED_BACK: '已回滚',
}

export function StatusBadge({ status }: { status: string }) {
  return <span className={`status-badge status-${status.toLowerCase()}`}>{LABELS[status] ?? status}</span>
}
```

状态颜色必须保留文字标签，不能只靠颜色区分。

- [ ] **Step 4: 实现 PreviewDrawer**

组件在 `open=false` 时返回 null；`open=true` 时渲染 backdrop 和 `role="dialog" aria-modal="true"`。useEffect 监听 Escape 并在卸载时移除 listener；打开时聚焦关闭按钮，关闭时调用方将焦点返回预览按钮。正文 `<pre>` 不再绑定关闭事件。

- [ ] **Step 5: 实现 ReleaseList**

状态规则：

```text
PUBLISHED: 显示回滚、离线、删除
ROLLED_BACK: 只显示删除
OFFLINE: 只显示删除
DRAFT: 只显示删除
```

删除前调用：

```ts
if (!window.confirm('删除该索引发布？此操作无法撤销。')) return
```

每行以 `pendingReleaseId` 禁用操作。失败在该 Release 行显示 `role="alert"`。操作成功调用 `onChanged()`。

- [ ] **Step 6: 重构 VersionList**

明确拆成“文档与版本”和“索引发布”两个 section。文档加载、Release 加载和单文档版本加载分别维护错误；不使用空 catch。版本 READY 时按钮 aria-label 为 `预览 v${versionNo}`。失败版本显示 `errorMessage`。无文档和无 Release 都有独立空状态。

- [ ] **Step 7: 验证知识管理完整测试**

Run:

```bash
npm --prefix web test -- src/features/knowledge/KnowledgePage.test.tsx
```

Expected: PASS.

- [ ] **Step 8: 提交任务**

```bash
git add web/src/features/knowledge
 git commit -m "feat: redesign document and release management"
```

---

### Task 5: 实施视觉 Token、响应式和交互状态

**Files:**
- Modify: `web/src/styles.css`
- Modify: `web/src/app/App.test.tsx` only if accessible names changed during styling integration.

**Interfaces:**
- CSS class names come from Tasks 1-4.
- Semantic tokens are global CSS custom properties; components do not use inline color values.

- [ ] **Step 1: 建立全局 Token**

`styles.css` 顶部至少定义：

```css
:root {
  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", "PingFang SC", "Microsoft YaHei", sans-serif;
  color: #1d2935;
  background: #f3f5f6;
  font-synthesis: none;
  --surface-page: #f3f5f6;
  --surface-panel: #fbfcfc;
  --surface-muted: #edf1f1;
  --surface-accent: #e2efeb;
  --text-primary: #1d2935;
  --text-secondary: #5d6a75;
  --text-muted: #77838d;
  --border: #d9dfdf;
  --border-strong: #c5cecc;
  --accent: #286f62;
  --accent-hover: #205e53;
  --accent-contrast: #f7fbfa;
  --danger: #a33a35;
  --danger-surface: #f8e8e6;
  --warning: #7b5b10;
  --warning-surface: #f8efcf;
  --success: #256b45;
  --success-surface: #deeee4;
  --focus: #1f6e9b;
  --radius-panel: 12px;
  --radius-control: 8px;
  --radius-status: 6px;
}
```

禁止在后续规则中引入第二个装饰性强调色。

- [ ] **Step 2: 实现框架和登录布局**

桌面 `.app-shell` 为 240px + 1fr；`.app-sidebar` 固定高度并将 user section 推到底部。主内容 `.app-main` 内部 `.app-content` 最大宽度 1440px。登录页使用 `grid-template-columns: minmax(360px, 0.9fr) minmax(420px, 1.1fr)` 和 `min-height: 100dvh`。

所有按钮具有 hover、focus-visible、disabled、active 状态；active 使用 `transform: translateY(1px)`。

- [ ] **Step 3: 实现知识工作区布局**

`.knowledge-layout` 使用 `grid-template-columns: minmax(240px, 300px) minmax(0, 1fr)`。知识库、上传、文档、Release 是有边框的功能区，但禁止每一行都形成独立浮动卡片。文档行使用单一底部分隔线。索引名使用系统 monospace。

- [ ] **Step 4: 实现预览层和状态**

Drawer 桌面宽度 `min(620px, 92vw)`，从右侧以 transform 进入；backdrop 只动画 opacity。骨架形状匹配列表和标题。error、success、warning、offline 状态均符合 Token。

- [ ] **Step 5: 实现移动端和 reduced motion**

```css
@media (max-width: 767px) {
  .app-shell { display: block; }
  .app-sidebar { position: sticky; top: 0; width: 100%; min-height: auto; }
  .workspace-nav { display: flex; overflow-x: auto; }
  .knowledge-layout, .login-page { grid-template-columns: 1fr; }
  .login-brand { display: none; }
  .version-row, .release-row { grid-template-columns: 1fr; }
  .preview-drawer { width: 100%; }
}

@media (prefers-reduced-motion: reduce) {
  *, *::before, *::after { scroll-behavior: auto !important; animation-duration: 0.01ms !important; animation-iteration-count: 1 !important; transition-duration: 0.01ms !important; }
}
```

- [ ] **Step 6: 运行前端自动验证**

Run:

```bash
npm --prefix web test
npm --prefix web run lint
npm --prefix web run build
```

Expected: all commands exit 0. Existing Fast Refresh warning must be removed by keeping route data and component exports in appropriate files, or explicitly reported if it remains non-blocking.

- [ ] **Step 7: 扫描 taste skill 禁止项**

Run:

```bash
if grep -R -nE '—|–' web/src --include='*.tsx' --include='*.css'; then exit 1; fi
if grep -R -nE '#000000|#ffffff|linear-gradient|radial-gradient|box-shadow:.*0 0' web/src/styles.css; then exit 1; fi
git diff --check
```

Expected: no matches and no whitespace errors.

- [ ] **Step 8: 提交任务**

```bash
git add web/src/styles.css web/src/app/App.test.tsx
 git commit -m "style: apply calm enterprise console system"
```

---

### Task 6: 浏览器验收与最终前端门禁

**Files:**
- Modify only files required to fix concrete browser or accessibility defects found in this task.

**Interfaces:**
- Uses running backend at `http://localhost:8080` and Vite at `http://localhost:5173`.
- Produces verified desktop/mobile screenshots and Lighthouse results; screenshots remain temporary and are not committed.

- [ ] **Step 1: 启动前端并打开登录页**

Run:

```bash
npm --prefix web run dev
```

Use Chrome DevTools to open `http://localhost:5173/login` at 1440x900.

- [ ] **Step 2: 验收登录页**

确认：左右分栏、表单首屏完整、标签清晰、按钮不换行、错误可见、Tab 顺序合理、无水平滚动。再切换 390x844，确认品牌区隐藏且表单完整。

- [ ] **Step 3: 验收认证后应用框架**

使用 `admin / veridex` 登录。检查桌面侧栏、当前用户、退出按钮、四个导航路由；逐一打开三个 ComingSoonPage，确认阶段、文案和返回链接准确，无虚构数据。

- [ ] **Step 4: 验收知识管理主流程**

检查知识库加载、默认选择、新建表单、文件选择、文档展开、状态标签、预览 Drawer、Release 按钮和删除确认。若当前数据库有数据，不删除用户数据；只使用可逆操作或新建临时知识库。

- [ ] **Step 5: 验收移动布局和 reduced motion**

在 390x844 下检查顶部导航、单列知识库布局、堆叠行和全屏 Drawer。模拟 `prefers-reduced-motion: reduce`，确认 Drawer 和页面无明显非必要动画。

- [ ] **Step 6: 运行浏览器质量检查**

运行 Lighthouse desktop snapshot，要求：

```text
Accessibility >= 95
Best Practices >= 95
SEO >= 90
```

查看 Console，要求无 React key、状态更新、网络处理和可访问性相关错误。

- [ ] **Step 7: 修复发现的具体缺陷并复验**

每个缺陷只修改对应组件或 CSS；复现同一路径确认已修复。若修改行为，补充对应 Testing Library 测试。

- [ ] **Step 8: 最终前端门禁**

Run:

```bash
npm --prefix web test
npm --prefix web run lint
npm --prefix web run build
git diff --check
git status --short
```

Expected: tests, lint and build exit 0; only intended frontend changes remain.

- [ ] **Step 9: 提交浏览器修复**

如果有修复：

```bash
git add web
 git commit -m "fix: polish console responsive accessibility"
```

如果没有修复，不创建空提交。
