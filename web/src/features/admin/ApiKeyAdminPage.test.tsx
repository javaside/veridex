import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, expect, test, vi } from 'vitest'
import { ApiKeyAdminPage } from './ApiKeyAdminPage'

const platformUser = {
  id: '00000000-0000-0000-0000-000000000001',
  username: 'admin',
  displayName: '管理员',
  role: 'PLATFORM_ADMIN',
}

const knowledgeAdmin = {
  id: '00000000-0000-0000-0000-000000000002',
  username: 'knowledge-admin',
  displayName: '知识管理员',
  role: 'KNOWLEDGE_ADMIN',
}

const renderAdmin = (user = platformUser) => render(<ApiKeyAdminPage user={user} />)

const activeKey = {
  id: 'k1',
  name: '同步脚本',
  tokenPrefix: 'vd_abcd1',
  userId: 'u1',
  scopes: ['qa', 'feedback'],
  createdAt: '2026-08-16T10:00:00Z',
  revokedAt: null,
  lastUsedAt: null,
}

const jsonResponse = (body: unknown, status = 200) =>
  Promise.resolve(new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } }))

beforeEach(() => {
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

test('lists keys with prefix, owner, scopes, and status', async () => {
  vi.stubGlobal('fetch', vi.fn(() => jsonResponse([activeKey])))

  renderAdmin()

  expect(screen.getByRole('status')).toHaveTextContent('正在加载 API key')
  expect(await screen.findByText('同步脚本')).toBeInTheDocument()
  expect(screen.getByText('vd_abcd1…')).toBeInTheDocument()
  expect(screen.getByText('用户 u1')).toBeInTheDocument()
  expect(screen.getByText('生效中')).toBeInTheDocument()
  expect(screen.getByText('qa')).toBeInTheDocument()
  expect(screen.getByText('feedback')).toBeInTheDocument()
})

test('shows ProblemDetail detail without exposing raw JSON', async () => {
  vi.stubGlobal('fetch', vi.fn(() => Promise.resolve(new Response(JSON.stringify({
    type: 'about:blank', title: '服务不可用', status: 503, detail: '请稍后重试',
  }), { status: 503, headers: { 'Content-Type': 'application/problem+json' } }))))

  renderAdmin()

  expect(await screen.findByRole('alert')).toHaveTextContent('请稍后重试')
  expect(screen.getByRole('alert')).not.toHaveTextContent('"detail"')
})

test('uses ProblemDetail title when detail is absent', async () => {
  vi.stubGlobal('fetch', vi.fn(() => Promise.resolve(new Response(JSON.stringify({ title: '访问被拒绝', status: 403 }), {
    status: 403,
    headers: { 'Content-Type': 'application/problem+json' },
  }))))

  renderAdmin()

  expect(await screen.findByRole('alert')).toHaveTextContent('访问被拒绝')
  expect(screen.getByRole('alert')).not.toHaveTextContent('{')
})

test('retries after the key list fails to load', async () => {
  const fetchMock = vi.fn()
    .mockImplementationOnce(() => Promise.resolve(new Response('服务暂不可用', { status: 503 })))
    .mockImplementationOnce(() => jsonResponse([]))
  vi.stubGlobal('fetch', fetchMock)

  renderAdmin()

  expect(await screen.findByRole('alert')).toHaveTextContent('服务暂不可用')
  fireEvent.click(screen.getByRole('button', { name: '重试' }))

  expect(await screen.findByRole('heading', { name: '暂无 API key' })).toBeInTheDocument()
  expect(fetchMock).toHaveBeenCalledTimes(2)
})

test('platform admin can optionally create a key for a valid target user UUID with all wire scopes', async () => {
  const clipboardWrite = vi.fn(() => Promise.resolve())
  Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText: clipboardWrite } })
  const fetchMock = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
    if (String(input) === '/api/iam/keys' && init?.method === 'POST') {
      return jsonResponse({
        id: 'k2', userId: 'u1', name: '新 key', tokenPrefix: 'vd_NewTo', token: 'vd_NewTokenOnlyOnce',
        scopes: ['qa', 'knowledge:read'], createdAt: '2026-08-16T10:00:00Z',
      }, 201)
    }
    return jsonResponse([])
  })
  vi.stubGlobal('fetch', fetchMock)

  renderAdmin()
  fireEvent.click(await screen.findByRole('button', { name: '创建 API key' }))
  expect(screen.getByText('为当前管理员签发凭据。按最小权限原则选择所需作用域。')).toBeInTheDocument()
  fireEvent.change(screen.getByLabelText('名称'), { target: { value: ' 新 key ' } })
  const targetUserId = '00000000-0000-0000-0000-000000000099'
  fireEvent.change(screen.getByLabelText('目标用户 ID（可选）'), { target: { value: targetUserId } })
  expect(screen.getByText(`为目标用户 ${targetUserId} 签发凭据。按最小权限原则选择所需作用域。`)).toBeInTheDocument()
  expect(screen.queryByText('为当前管理员签发凭据。按最小权限原则选择所需作用域。')).not.toBeInTheDocument()
  for (const scope of ['qa', 'knowledge:read', 'knowledge:write', 'configuration', 'evaluation', 'feedback']) {
    fireEvent.click(screen.getByLabelText(new RegExp(scope.replace(':', '\\:'))))
  }
  fireEvent.click(screen.getByRole('button', { name: /^创建$/ }))

  expect(await screen.findByText('vd_NewTokenOnlyOnce')).toBeInTheDocument()
  const postCall = fetchMock.mock.calls.find(([, init]) => init?.method === 'POST')
  expect(JSON.parse(String(postCall?.[1]?.body))).toEqual({
    name: '新 key',
    scopes: ['qa', 'knowledge:read', 'knowledge:write', 'configuration', 'evaluation', 'feedback'],
    userId: targetUserId,
  })

  fireEvent.click(screen.getByRole('button', { name: '复制 token' }))
  await waitFor(() => expect(clipboardWrite).toHaveBeenCalledWith('vd_NewTokenOnlyOnce'))
  expect(await screen.findByRole('status')).toHaveTextContent('token 已复制')

  fireEvent.click(screen.getByRole('button', { name: '我已保存，关闭' }))
  await waitFor(() => expect(screen.queryByText('vd_NewTokenOnlyOnce')).not.toBeInTheDocument())
  expect(screen.queryByRole('dialog', { name: 'API key 已创建' })).not.toBeInTheDocument()
})

test('create and success dialogs trap focus and restore it to the create trigger', async () => {
  const fetchMock = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
    if (String(input) === '/api/iam/keys' && init?.method === 'POST') {
      return jsonResponse({
        id: 'k2', userId: platformUser.id, name: 'focus key', tokenPrefix: 'vd_focus', token: 'vd_FocusToken',
        scopes: ['qa'], createdAt: '2026-08-16T10:00:00Z',
      }, 201)
    }
    return jsonResponse([])
  })
  vi.stubGlobal('fetch', fetchMock)

  renderAdmin()
  const trigger = await screen.findByRole('button', { name: '创建 API key' })
  trigger.focus()
  fireEvent.click(trigger)
  expect(screen.getByLabelText('名称')).toHaveFocus()

  fireEvent.keyDown(document, { key: 'Tab', shiftKey: true })
  expect(screen.getByRole('button', { name: '取消' })).toHaveFocus()
  fireEvent.change(screen.getByLabelText('名称'), { target: { value: 'focus key' } })
  fireEvent.click(screen.getByLabelText(/qa/))
  fireEvent.click(screen.getByRole('button', { name: /^创建$/ }))

  const close = await screen.findByRole('button', { name: '我已保存，关闭' })
  expect(close).toHaveFocus()
  fireEvent.keyDown(document, { key: 'Tab' })
  expect(screen.getByRole('button', { name: '复制 token' })).toHaveFocus()
  fireEvent.keyDown(document, { key: 'Escape' })
  expect(screen.getByRole('dialog', { name: 'API key 已创建' })).toBeInTheDocument()

  fireEvent.click(close)
  expect(trigger).toHaveFocus()
})

test('knowledge admin creates only for self and cannot enter a target user', async () => {
  const fetchMock = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
    if (String(input) === '/api/iam/keys' && init?.method === 'POST') {
      return jsonResponse({
        id: 'k2', userId: knowledgeAdmin.id, name: 'self key', tokenPrefix: 'vd_self1', token: 'vd_SelfToken',
        scopes: ['qa'], createdAt: '2026-08-16T10:00:00Z',
      }, 201)
    }
    return jsonResponse([])
  })
  vi.stubGlobal('fetch', fetchMock)

  renderAdmin(knowledgeAdmin)
  fireEvent.click(await screen.findByRole('button', { name: '创建 API key' }))

  expect(screen.queryByLabelText('目标用户 ID（可选）')).not.toBeInTheDocument()
  fireEvent.change(screen.getByLabelText('名称'), { target: { value: 'self key' } })
  fireEvent.click(screen.getByLabelText(/qa/))
  fireEvent.click(screen.getByRole('button', { name: /^创建$/ }))

  await screen.findByText('vd_SelfToken')
  const postCall = fetchMock.mock.calls.find(([, init]) => init?.method === 'POST')
  expect(JSON.parse(String(postCall?.[1]?.body))).toEqual({ name: 'self key', scopes: ['qa'] })
})

test('platform admin rejects an invalid target user UUID before submission', async () => {
  const fetchMock = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
    void input
    void init
    return jsonResponse([])
  })
  vi.stubGlobal('fetch', fetchMock)

  renderAdmin()
  fireEvent.click(await screen.findByRole('button', { name: '创建 API key' }))
  fireEvent.change(screen.getByLabelText('名称'), { target: { value: '代理 key' } })
  fireEvent.change(screen.getByLabelText('目标用户 ID（可选）'), { target: { value: 'not-a-uuid' } })
  fireEvent.click(screen.getByLabelText(/qa/))
  fireEvent.click(screen.getByRole('button', { name: /^创建$/ }))

  expect(await screen.findByRole('alert')).toHaveTextContent('目标用户 ID 必须是有效的 UUID')
  expect(fetchMock.mock.calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(0)
})

test('keeps the create dialog open and reports a create error', async () => {
  const fetchMock = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
    if (String(input) === '/api/iam/keys' && init?.method === 'POST') {
      return Promise.resolve(new Response('名称已存在', { status: 409 }))
    }
    return jsonResponse([])
  })
  vi.stubGlobal('fetch', fetchMock)

  renderAdmin()
  fireEvent.click(await screen.findByRole('button', { name: '创建 API key' }))
  fireEvent.change(screen.getByLabelText('名称'), { target: { value: '重复 key' } })
  fireEvent.click(screen.getByLabelText(/问答/))
  fireEvent.click(screen.getByRole('button', { name: /^创建$/ }))

  expect(await screen.findByRole('alert')).toHaveTextContent('名称已存在')
  expect(screen.getByRole('dialog', { name: '创建 API key' })).toBeInTheDocument()
  expect(screen.getByRole('button', { name: /^创建$/ })).toBeEnabled()
})

test('confirms revocation, disables actions while pending, and shows revoked state after refresh', async () => {
  let revoked = false
  let resolveDelete: ((response: Response) => void) | undefined
  const fetchMock = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
    if (String(input) === '/api/iam/keys/k1' && init?.method === 'DELETE') {
      return new Promise<Response>((resolve) => { resolveDelete = resolve })
    }
    return jsonResponse([{ ...activeKey, revokedAt: revoked ? '2026-08-16T11:00:00Z' : null }])
  })
  vi.stubGlobal('fetch', fetchMock)

  renderAdmin()
  fireEvent.click(await screen.findByRole('button', { name: '吊销 同步脚本' }))
  fireEvent.click(screen.getByRole('button', { name: '确认吊销' }))

  expect(screen.getByRole('button', { name: '正在吊销' })).toBeDisabled()
  revoked = true
  resolveDelete?.(new Response(null, { status: 204 }))

  expect(await screen.findByText('已吊销')).toBeInTheDocument()
  expect(screen.queryByRole('button', { name: '吊销 同步脚本' })).not.toBeInTheDocument()
  expect(screen.getByRole('status')).toHaveTextContent('API key 已吊销')
})

test('keeps the key locally revoked when refresh fails after successful deletion', async () => {
  const fetchMock = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
    if (String(input) === '/api/iam/keys/k1' && init?.method === 'DELETE') {
      return Promise.resolve(new Response(null, { status: 204 }))
    }
    if (fetchMock.mock.calls.length > 1) {
      return Promise.resolve(new Response(JSON.stringify({ title: '刷新失败', detail: '列表服务暂不可用' }), {
        status: 503,
        headers: { 'Content-Type': 'application/problem+json' },
      }))
    }
    return jsonResponse([activeKey])
  })
  vi.stubGlobal('fetch', fetchMock)

  renderAdmin()
  fireEvent.click(await screen.findByRole('button', { name: '吊销 同步脚本' }))
  fireEvent.click(screen.getByRole('button', { name: '确认吊销' }))

  expect(await screen.findByText('已吊销')).toBeInTheDocument()
  expect(screen.queryByRole('button', { name: '吊销 同步脚本' })).not.toBeInTheDocument()
  expect(screen.getByRole('status')).toHaveTextContent('API key 已吊销，但刷新列表失败：列表服务暂不可用')
  expect(screen.getByRole('status')).not.toHaveTextContent(/^吊销失败/)
})

test('reports a revoke error and leaves the key actionable', async () => {
  const fetchMock = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
    if (String(input) === '/api/iam/keys/k1' && init?.method === 'DELETE') {
      return Promise.resolve(new Response('吊销服务不可用', { status: 503 }))
    }
    return jsonResponse([activeKey])
  })
  vi.stubGlobal('fetch', fetchMock)

  renderAdmin()
  fireEvent.click(await screen.findByRole('button', { name: '吊销 同步脚本' }))
  fireEvent.click(screen.getByRole('button', { name: '确认吊销' }))

  expect(await screen.findByRole('status')).toHaveTextContent('吊销服务不可用')
  expect(screen.getByRole('button', { name: '吊销 同步脚本' })).toBeEnabled()
})
