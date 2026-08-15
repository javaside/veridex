import { fireEvent, render, screen } from '@testing-library/react'
import { beforeEach, expect, test, vi } from 'vitest'
import { RunPanel } from './components/RunPanel'

beforeEach(() => vi.restoreAllMocks())

test('starts an evaluation run with selected inputs', async () => {
  let started = false
  const fetchMock = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input)
    const method = init?.method ?? 'GET'
    const json = (body: unknown) => Promise.resolve(new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } }))
    if (url === '/api/configuration/profiles') return json([{ id: 'p-1', name: '检索基线', description: null, versionCount: 1, latestVersionNo: 1 }])
    if (url === '/api/knowledge-bases') return json([{ id: 'kb-1', name: '制度库', description: null, slug: 'zd' }])
    if (url === '/api/configuration/profiles/p-1/versions') return json([{ id: 'pv-1', versionNo: 1, createdAt: '2026-08-15T00:00:00Z' }])
    if (url === '/api/evaluation/runs?datasetId=ds-1' || url === '/api/evaluation/runs?datasetId=ds-1') return json([])
    if (url === '/api/evaluation/runs' && method === 'POST') { started = true; return json({ id: 'r-1', datasetId: 'ds-1', datasetVersionId: 'dv-1', profileId: 'p-1', profileVersionNo: 1, status: 'COMPLETED', metrics: null, createdAt: '2026-08-15T00:00:00Z', completedAt: null }) }
    return json([])
  })
  vi.stubGlobal('fetch', fetchMock)

  render(<RunPanel datasetId="ds-1" versions={[{ id: 'dv-1', versionNo: 1, caseCount: 1, createdAt: '2026-08-15T00:00:00Z' }]} onNotify={() => {}} />)

  // 选择数据集版本
  fireEvent.change(await screen.findByLabelText('数据集版本'), { target: { value: '1' } })
  // 选择 profile
  fireEvent.change(await screen.findByLabelText('配置 Profile'), { target: { value: 'p-1' } })
  // 等待 profile 版本加载
  fireEvent.change(await screen.findByLabelText('Profile 版本'), { target: { value: '1' } })

  fireEvent.click(screen.getByRole('button', { name: '发起评测' }))

  await vi.waitFor(() => expect(started).toBe(true))
})

test('compares two runs and shows gate verdict', async () => {
  const run = (id: string) => ({ id, datasetId: 'ds-1', datasetVersionId: 'dv-1', profileId: 'p-1', profileVersionNo: 1, status: 'COMPLETED', metrics: { avgRecallAt1: 0.5, avgRecallAt3: 0.5, avgRecallAt5: 0.5, avgMrr: 0.5, avgNdcgAt10: 0.5, citationHitRate: 0.5, refusalMatchRate: 0.5, avgLatencyMs: 100, caseCount: 1, completedCount: 1 }, createdAt: '2026-08-15T00:00:00Z', completedAt: '2026-08-15T00:00:01Z' })
  const fetchMock = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input)
    const method = init?.method ?? 'GET'
    const json = (body: unknown) => Promise.resolve(new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } }))
    if (url === '/api/configuration/profiles') return json([])
    if (url === '/api/knowledge-bases') return json([])
    if (url === '/api/evaluation/runs?datasetId=ds-1' || url === '/api/evaluation/runs?datasetId=ds-1') return json([run('r-base'), run('r-cand')])
    if (url === '/api/evaluation/comparisons' && method === 'POST') {
      return json({ baselineRunId: 'r-base', candidateRunId: 'r-cand', baseline: run('r-base').metrics, candidate: run('r-cand').metrics, metrics: [{ name: 'MRR', baseline: 0.5, candidate: 0.5, delta: 0 }], verdict: 'PASS', failedChecks: [] })
    }
    return json([])
  })
  vi.stubGlobal('fetch', fetchMock)

  render(<RunPanel datasetId="ds-1" versions={[]} onNotify={() => {}} />)

  fireEvent.change(await screen.findByLabelText('基线运行'), { target: { value: 'r-base' } })
  fireEvent.change(await screen.findByLabelText('候选运行'), { target: { value: 'r-cand' } })
  fireEvent.click(screen.getByRole('button', { name: '对比' }))

  expect(await screen.findByText(/门禁判定：PASS/)).toBeInTheDocument()
})
