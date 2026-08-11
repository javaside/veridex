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

test('does not send a release deletion request when confirmation is cancelled', async () => {
  const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false)
  const fetchMock = vi.fn((input: RequestInfo | URL) => {
    const url = String(input)
    const json = (body: unknown) => Promise.resolve(new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } }))
    if (url === '/api/knowledge-bases') return json([{ id: 'kb-1', name: '员工知识', description: null, slug: 'employee' }])
    if (url.endsWith('/documents')) return json([])
    if (url.endsWith('/releases')) return json([{ releaseId: 'release-1', versionNo: 1, status: 'PUBLISHED', indexName: 'veridex-kb-1-1', aliasName: 'veridex-kb-1' }])
    return json([])
  })
  vi.stubGlobal('fetch', fetchMock)

  render(<KnowledgePage />)

  fireEvent.click(await screen.findByRole('button', { name: '删除发布 v1' }))
  expect(confirm).toHaveBeenCalledWith('删除该索引发布？此操作无法撤销。')
  expect(fetchMock.mock.calls.some(([input]) => String(input).includes('/delete'))).toBe(false)
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
