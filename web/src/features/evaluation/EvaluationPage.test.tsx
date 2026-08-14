import { fireEvent, render, screen } from '@testing-library/react'
import { beforeEach, expect, test, vi } from 'vitest'
import { EvaluationPage } from './EvaluationPage'

beforeEach(() => vi.restoreAllMocks())

test('shows the empty evaluation workspace after loading', async () => {
  vi.stubGlobal('fetch', vi.fn(() => Promise.resolve(new Response('[]', { status: 200, headers: { 'Content-Type': 'application/json' } }))))

  render(<EvaluationPage />)

  expect(await screen.findByRole('heading', { name: '创建第一个数据集' })).toBeInTheDocument()
  expect(screen.getByRole('heading', { name: '选择一个数据集' })).toBeInTheDocument()
})

test('creates a dataset from the inline form', async () => {
  const fetchMock = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input)
    const json = (body: unknown) => Promise.resolve(new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } }))
    if (url === '/api/evaluation/datasets' && init?.method === 'POST') {
      return json({ id: 'ds-1', name: '回归集', description: null, caseCount: 0, latestVersionNo: null })
    }
    if (url === '/api/evaluation/datasets') return json([])
    if (url.endsWith('/versions')) return json([])
    return json([])
  })
  vi.stubGlobal('fetch', fetchMock)

  render(<EvaluationPage />)

  fireEvent.change(await screen.findByRole('textbox', { name: '数据集名称' }), { target: { value: '回归集' } })
  fireEvent.click(screen.getByRole('button', { name: '新建数据集' }))

  expect(await screen.findByRole('heading', { name: '回归集' })).toBeInTheDocument()
})

test('publishes the selected dataset and refreshes versions', async () => {
  let published = false
  const fetchMock = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input)
    const json = (body: unknown) => Promise.resolve(new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } }))
    if (url === '/api/evaluation/datasets') return json([{ id: 'ds-1', name: '回归集', description: null, caseCount: 1, latestVersionNo: null }])
    if (url.endsWith('/cases')) return json([{ id: 'c-1', question: '问题一', expectedBehavior: 'ANSWER', expectedAnswer: '答案一', evidence: [] }])
    if (url.endsWith('/publish') && init?.method === 'POST') {
      published = true
      return json({ versionId: 'v-1', versionNo: 1, caseCount: 1 })
    }
    if (url.endsWith('/versions')) return json(published ? [{ id: 'v-1', versionNo: 1, caseCount: 1, createdAt: '2026-08-15T00:00:00Z' }] : [])
    return json([])
  })
  vi.stubGlobal('fetch', fetchMock)

  render(<EvaluationPage />)

  fireEvent.click(await screen.findByRole('button', { name: '发布版本' }))

  expect(await screen.findByRole('status')).toHaveTextContent('已发布版本 v1')
  expect(await screen.findByText('v1')).toBeInTheDocument()
})
