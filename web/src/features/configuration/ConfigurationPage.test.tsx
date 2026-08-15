import { fireEvent, render, screen } from '@testing-library/react'
import { beforeEach, expect, test, vi } from 'vitest'
import { ConfigurationPage } from './ConfigurationPage'

beforeEach(() => vi.restoreAllMocks())

test('shows the empty configuration workspace after loading', async () => {
  vi.stubGlobal('fetch', vi.fn(() => Promise.resolve(new Response('[]', { status: 200, headers: { 'Content-Type': 'application/json' } }))))

  render(<ConfigurationPage />)

  expect(await screen.findByRole('heading', { name: '创建第一个配置' })).toBeInTheDocument()
  expect(screen.getByRole('heading', { name: '选择一个配置' })).toBeInTheDocument()
})

test('publishes the selected profile and refreshes versions', async () => {
  let published = false
  const draft = { chunking: { maxChars: 2000, overlap: 80 }, retrieval: { topKPerChannel: 30, rrfK: 60, contextTopK: 6, perDocumentMax: 3, contextMaxChars: 4000 }, generation: { maxHistoryTurns: 6, minEvidenceChars: 50 }, prompt: { systemTemplate: '模板' }, model: { chatModel: 'deterministic', embeddingModel: 'deterministic' } }
  const fetchMock = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input)
    const json = (body: unknown) => Promise.resolve(new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } }))
    if (url === '/api/configuration/profiles') return json([{ id: 'p-1', name: '检索基线', description: null, versionCount: 0, latestVersionNo: null }])
    if (url === '/api/configuration/profiles/p-1') return json({ id: 'p-1', name: '检索基线', description: null, draft })
    if (url.endsWith('/publish') && init?.method === 'POST') { published = true; return json({ versionId: 'v-1', versionNo: 1 }) }
    if (url.endsWith('/versions')) return json(published ? [{ id: 'v-1', versionNo: 1, createdAt: '2026-08-15T00:00:00Z' }] : [])
    return json([])
  })
  vi.stubGlobal('fetch', fetchMock)

  render(<ConfigurationPage />)

  fireEvent.click(await screen.findByRole('button', { name: '发布版本' }))

  expect(await screen.findByRole('status')).toHaveTextContent('已发布版本 v1')
  expect(await screen.findByText('v1')).toBeInTheDocument()
})
