import { fireEvent, render, screen } from '@testing-library/react'
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
