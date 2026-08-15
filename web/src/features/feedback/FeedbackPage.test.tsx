import { fireEvent, render, screen } from '@testing-library/react'
import { beforeEach, expect, test, vi } from 'vitest'
import { FeedbackPage } from './FeedbackPage'

beforeEach(() => vi.restoreAllMocks())

const feedbackItem = {
  id: 'fb-1',
  queryRunId: 'r1',
  rating: 'DOWN',
  reasonCode: 'WRONG_ANSWER',
  question: '请假需要几天？',
  answer: '错误答案',
  evidence: [],
  convertedCaseId: null,
  createdAt: '2026-08-15T00:00:00Z',
}

const dataset = { id: 'ds-1', name: '回归集', description: null, caseCount: 0, latestVersionNo: null }

test('shows empty state when there is no down feedback', async () => {
  vi.stubGlobal('fetch', vi.fn(() => Promise.resolve(new Response('[]', { status: 200, headers: { 'Content-Type': 'application/json' } }))))

  render(<FeedbackPage />)

  expect(await screen.findByRole('heading', { name: '暂无点踩反馈' })).toBeInTheDocument()
})

test('converts a down feedback into a REFUSE case', async () => {
  const fetchMock = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input)
    const method = init?.method ?? 'GET'
    const json = (body: unknown) => Promise.resolve(new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } }))
    if (url.includes('/api/feedback') && method === 'GET') return json([feedbackItem])
    if (url === '/api/evaluation/datasets' && method === 'GET') return json([dataset])
    if (url === '/api/evaluation/datasets/ds-1/cases' && method === 'POST') {
      return json({ id: 'case-1', question: '请假需要几天？', expectedBehavior: 'REFUSE', expectedAnswer: null, evidence: [] })
    }
    if (url === '/api/feedback/fb-1/converted' && method === 'POST') {
      return json({ ...feedbackItem, convertedCaseId: 'case-1' })
    }
    return json([])
  })
  vi.stubGlobal('fetch', fetchMock)

  render(<FeedbackPage />)

  fireEvent.click(await screen.findByRole('button', { name: '转坏例' }))
  expect(await screen.findByRole('dialog', { name: '转成评测坏例' })).toBeInTheDocument()

  fireEvent.click(screen.getByRole('button', { name: '确认转坏例' }))

  expect(await screen.findByRole('status')).toHaveTextContent('已转成评测坏例')
})
