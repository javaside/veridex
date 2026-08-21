import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, expect, test, vi } from 'vitest'
import { KnowledgePage } from './KnowledgePage'

beforeEach(() => vi.restoreAllMocks())

test('shows the empty knowledge workspace after loading', async () => {
  vi.stubGlobal('fetch', vi.fn(() => Promise.resolve(new Response('[]', { status: 200, headers: { 'Content-Type': 'application/json' } }))))

  render(<KnowledgePage />)

  expect(screen.getByRole('status', { name: '正在加载知识库' })).toBeInTheDocument()
  expect(await screen.findByRole('heading', { name: '创建第一个知识库' })).toBeInTheDocument()
  expect(screen.getByRole('heading', { name: '选择一个知识库' })).toBeInTheDocument()
})

test('shows confirm dialog before deleting a release', async () => {
  const fetchMock = vi.fn((input: RequestInfo | URL) => {
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

test('expands versions and opens a closable preview drawer', async () => {
  vi.stubGlobal('fetch', vi.fn((input: RequestInfo | URL) => {
    const url = String(input)
    const json = (body: unknown) => Promise.resolve(new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } }))
    if (url === '/api/knowledge-bases') return json([{ id: 'kb-1', name: '员工知识', description: null, slug: 'employee' }])
    if (url.endsWith('/documents')) return json([{ id: 'doc-1', filename: '员工手册', contentType: 'application/pdf', sizeBytes: 1024 }])
    if (url.endsWith('/releases')) return json([])
    if (url.endsWith('/versions')) return json([{ id: 'version-1', versionNo: 1, status: 'READY', chunkCount: 3, errorMessage: null, objectKey: 'doc.pdf' }])
    if (url.endsWith('/parsed')) return Promise.resolve(new Response('解析正文', { status: 200 }))
    return json([])
  }))

  render(<KnowledgePage />)

  fireEvent.click(await screen.findByRole('button', { name: /员工手册/ }))
  expect(await screen.findByText('v1')).toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: '预览 v1' }))
  expect(await screen.findByRole('dialog', { name: '员工手册 v1 解析预览' })).toBeInTheDocument()
  fireEvent.keyDown(document, { key: 'Escape' })
  await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
})

test('sends a make-current request for a historical release', async () => {
  const fetchMock = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input)
    const json = (body: unknown) => Promise.resolve(new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } }))
    if (url === '/api/knowledge-bases') return json([{ id: 'kb-1', name: '员工知识', description: null, slug: 'employee' }])
    if (url.endsWith('/documents')) return json([])
    if (url.endsWith('/releases')) return json([{ releaseId: 'release-1', versionNo: 1, status: 'PUBLISHED', indexName: 'veridex-kb-1-1', aliasName: 'veridex-kb-1', isActive: false, documentCount: 1, chunkCount: 3 }])
    if (url.endsWith('/make-current') && init?.method === 'POST') return Promise.resolve(new Response(null, { status: 204 }))
    return json([])
  })
  vi.stubGlobal('fetch', fetchMock)

  render(<KnowledgePage />)

  fireEvent.click(await screen.findByRole('button', { name: '设为当前' }))
  await waitFor(() => expect(fetchMock.mock.calls.some(([input]) => String(input).includes('/make-current'))).toBe(true))
})

test('shows empty-state guard when no release is active', async () => {
  vi.stubGlobal('fetch', vi.fn((input: RequestInfo | URL) => {
    const url = String(input)
    const json = (body: unknown) => Promise.resolve(new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } }))
    if (url === '/api/knowledge-bases') return json([{ id: 'kb-1', name: '员工知识', description: null, slug: 'employee' }])
    if (url.endsWith('/documents')) return json([])
    if (url.endsWith('/releases')) return json([{ releaseId: 'release-1', versionNo: 1, status: 'PUBLISHED', indexName: 'veridex-kb-1-1', aliasName: 'veridex-kb-1', isActive: false, documentCount: 1, chunkCount: 3 }])
    return json([])
  }))

  render(<KnowledgePage />)

  expect(await screen.findByRole('heading', { name: '当前无检索版本' })).toBeInTheDocument()
})

test('reports a load failure and retries the knowledge base request', async () => {
  const fetchMock = vi.fn()
    .mockResolvedValueOnce(new Response('服务暂不可用', { status: 503 }))
    .mockResolvedValueOnce(new Response('[]', { status: 200, headers: { 'Content-Type': 'application/json' } }))
  vi.stubGlobal('fetch', fetchMock)

  render(<KnowledgePage />)

  expect(await screen.findByRole('alert')).toHaveTextContent('服务暂不可用')
  fireEvent.click(screen.getByRole('button', { name: '重试' }))

  expect(await screen.findByRole('heading', { name: '创建第一个知识库' })).toBeInTheDocument()
  expect(fetchMock).toHaveBeenCalledTimes(2)
})

test('publishes the knowledge base and shows the active release', async () => {
  let published = false
  const fetchMock = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input)
    const json = (body: unknown) => Promise.resolve(new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } }))
    if (url === '/api/knowledge-bases') return json([{ id: 'kb-1', name: '员工知识', description: null, slug: 'employee' }])
    if (url.endsWith('/documents')) return json([])
    if (url.endsWith('/releases/publish') && init?.method === 'POST') {
      published = true
      // 异步发布：POST 立即返回 PUBLISHING 草稿
      return json({ releaseId: 'release-1', versionNo: 1, status: 'PUBLISHING', indexName: 'veridex-kb-1-1', aliasName: 'veridex-kb-1', isActive: false, documentCount: 0, chunkCount: 0 })
    }
    if (url.endsWith('/releases')) {
      return json(published ? [{ releaseId: 'release-1', versionNo: 1, status: 'PUBLISHED', indexName: 'veridex-kb-1-1', aliasName: 'veridex-kb-1', isActive: true, documentCount: 2, chunkCount: 10 }] : [])
    }
    return json([])
  })
  vi.stubGlobal('fetch', fetchMock)

  render(<KnowledgePage />)

  const publishButton = await screen.findByRole('button', { name: '发布' })
  fireEvent.click(publishButton)

  expect(await screen.findByText('已开始发布，正在后台构建索引…')).toBeInTheDocument()
  // 轮询到 PUBLISHED 后提示完成
  expect(await screen.findByText('发布完成', {}, { timeout: 3000 })).toBeInTheDocument()
  expect(screen.getByText('当前检索')).toBeInTheDocument()
})

test('reports publish failure when the publishing draft disappears', async () => {
  const fetchMock = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input)
    const json = (body: unknown) => Promise.resolve(new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } }))
    if (url === '/api/knowledge-bases') return json([{ id: 'kb-1', name: '员工知识', description: null, slug: 'employee' }])
    if (url.endsWith('/documents')) return json([])
    if (url.endsWith('/releases/publish') && init?.method === 'POST') {
      return json({ releaseId: 'release-1', versionNo: 1, status: 'PUBLISHING', indexName: 'veridex-kb-1-1', aliasName: 'veridex-kb-1', isActive: false, documentCount: 0, chunkCount: 0 })
    }
    // 后台发布失败后草稿被丢弃 → 列表始终为空
    if (url.endsWith('/releases')) return json([])
    return json([])
  })
  vi.stubGlobal('fetch', fetchMock)

  render(<KnowledgePage />)

  fireEvent.click(await screen.findByRole('button', { name: '发布' }))

  expect(await screen.findByText('已开始发布，正在后台构建索引…')).toBeInTheDocument()
  expect(await screen.findByText('发布失败，请重试', {}, { timeout: 3000 })).toBeInTheDocument()
})
