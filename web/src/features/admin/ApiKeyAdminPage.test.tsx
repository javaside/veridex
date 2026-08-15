import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, expect, test, vi } from 'vitest'
import { ApiKeyAdminPage } from './ApiKeyAdminPage'

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

  render(<ApiKeyAdminPage />)

  expect(screen.getByRole('status')).toHaveTextContent('正在加载 API key')
  expect(await screen.findByText('同步脚本')).toBeInTheDocument()
  expect(screen.getByText('vd_abcd1…')).toBeInTheDocument()
  expect(screen.getByText('用户 u1')).toBeInTheDocument()
  expect(screen.getByText('生效中')).toBeInTheDocument()
  expect(screen.getByText('qa')).toBeInTheDocument()
  expect(screen.getByText('feedback')).toBeInTheDocument()
})

test('retries after the key list fails to load', async () => {
  const fetchMock = vi.fn()
    .mockImplementationOnce(() => Promise.resolve(new Response('服务暂不可用', { status: 503 })))
    .mockImplementationOnce(() => jsonResponse([]))
  vi.stubGlobal('fetch', fetchMock)

  render(<ApiKeyAdminPage />)

  expect(await screen.findByRole('alert')).toHaveTextContent('服务暂不可用')
  fireEvent.click(screen.getByRole('button', { name: '重试' }))

  expect(await screen.findByRole('heading', { name: '暂无 API key' })).toBeInTheDocument()
  expect(fetchMock).toHaveBeenCalledTimes(2)
})

test('creates a key with canonical scopes and hides its token after closing', async () => {
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

  render(<ApiKeyAdminPage />)
  fireEvent.click(await screen.findByRole('button', { name: '创建 API key' }))
  fireEvent.change(screen.getByLabelText('名称'), { target: { value: ' 新 key ' } })
  fireEvent.click(screen.getByLabelText(/问答/))
  fireEvent.click(screen.getByLabelText(/知识读取/))
  fireEvent.click(screen.getByRole('button', { name: /^创建$/ }))

  expect(await screen.findByText('vd_NewTokenOnlyOnce')).toBeInTheDocument()
  const postCall = fetchMock.mock.calls.find(([, init]) => init?.method === 'POST')
  expect(JSON.parse(String(postCall?.[1]?.body))).toEqual({ name: '新 key', scopes: ['qa', 'knowledge:read'] })

  fireEvent.click(screen.getByRole('button', { name: '复制 token' }))
  await waitFor(() => expect(clipboardWrite).toHaveBeenCalledWith('vd_NewTokenOnlyOnce'))
  expect(await screen.findByRole('status')).toHaveTextContent('token 已复制')

  fireEvent.click(screen.getByRole('button', { name: '我已保存，关闭' }))
  await waitFor(() => expect(screen.queryByText('vd_NewTokenOnlyOnce')).not.toBeInTheDocument())
  expect(screen.queryByRole('dialog', { name: 'API key 已创建' })).not.toBeInTheDocument()
})

test('keeps the create dialog open and reports a create error', async () => {
  const fetchMock = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
    if (String(input) === '/api/iam/keys' && init?.method === 'POST') {
      return Promise.resolve(new Response('名称已存在', { status: 409 }))
    }
    return jsonResponse([])
  })
  vi.stubGlobal('fetch', fetchMock)

  render(<ApiKeyAdminPage />)
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

  render(<ApiKeyAdminPage />)
  fireEvent.click(await screen.findByRole('button', { name: '吊销 同步脚本' }))
  fireEvent.click(screen.getByRole('button', { name: '确认吊销' }))

  expect(screen.getByRole('button', { name: '正在吊销' })).toBeDisabled()
  revoked = true
  resolveDelete?.(new Response(null, { status: 204 }))

  expect(await screen.findByText('已吊销')).toBeInTheDocument()
  expect(screen.queryByRole('button', { name: '吊销 同步脚本' })).not.toBeInTheDocument()
  expect(screen.getByRole('status')).toHaveTextContent('API key 已吊销')
})

test('reports a revoke error and leaves the key actionable', async () => {
  const fetchMock = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
    if (String(input) === '/api/iam/keys/k1' && init?.method === 'DELETE') {
      return Promise.resolve(new Response('吊销服务不可用', { status: 503 }))
    }
    return jsonResponse([activeKey])
  })
  vi.stubGlobal('fetch', fetchMock)

  render(<ApiKeyAdminPage />)
  fireEvent.click(await screen.findByRole('button', { name: '吊销 同步脚本' }))
  fireEvent.click(screen.getByRole('button', { name: '确认吊销' }))

  expect(await screen.findByRole('status')).toHaveTextContent('吊销服务不可用')
  expect(screen.getByRole('button', { name: '吊销 同步脚本' })).toBeEnabled()
})
