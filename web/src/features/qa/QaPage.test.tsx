import { fireEvent, render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, test, vi } from 'vitest'
import { knowledgeApi } from '../knowledge/knowledgeApi'
import { QaPage } from './QaPage'
import { qaApi } from './qaApi'

vi.mock('./qaApi', () => ({
  qaApi: {
    ask: vi.fn(),
    conversations: vi.fn(),
    messages: vi.fn(),
  },
}))
vi.mock('../knowledge/knowledgeApi', () => ({
  knowledgeApi: {
    list: vi.fn().mockResolvedValue([{ id: 'kb-1', name: '制度库', description: null, slug: 'zd' }]),
    chunks: vi.fn(),
  },
}))

const mockedAsk = vi.mocked(qaApi.ask)
const mockedConversations = vi.mocked(qaApi.conversations)

describe('QaPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockedConversations.mockResolvedValue([])
    // jsdom 未实现 scrollTo
    Element.prototype.scrollTo = () => {}
  })

  test('streams answer deltas into the message list with citations', async () => {
    mockedAsk.mockImplementation(async (_question, _kbIds, _conversationId, onEvent) => {
      onEvent({ name: 'run.started', data: { runId: 'r1', conversationId: 'c1' } })
      onEvent({ name: 'retrieval.completed', data: { hitCount: 1 } })
      onEvent({ name: 'answer.delta', data: { text: '根据《请假制度》[1]，员工请假需提前申请' } })
      onEvent({ name: 'citation.available', data: { citations: [{ citationIndex: 1, documentId: 'doc-1', documentVersionId: 'v1', chunkIndex: 0, sourceLocation: '请假制度', citationText: '[1]', validationStatus: 'VALID' }] } })
      onEvent({ name: 'answer.completed', data: {} })
    })

    render(<QaPage />)

    await screen.findByRole('heading', { name: '向制度知识库提问' })
    fireEvent.change(screen.getByLabelText('问题'), { target: { value: '请假几天' } })
    fireEvent.click(screen.getByRole('button', { name: '发送' }))

    expect(await screen.findByText(/根据《请假制度》/)).toBeInTheDocument()
    expect(screen.getByText('[1]')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /\[1\] 请假制度/ })).toBeInTheDocument()
  })

  test('clicking a citation opens the source preview', async () => {
    vi.mocked(knowledgeApi.chunks).mockResolvedValue([{ index: 0, text: '员工请假需提前两个工作日申请', title: '请假制度', structurePath: '1' }])
    mockedAsk.mockImplementation(async (_question, _kbIds, _conversationId, onEvent) => {
      onEvent({ name: 'answer.delta', data: { text: '根据《请假制度》[1]回答' } })
      onEvent({ name: 'citation.available', data: { citations: [{ citationIndex: 1, documentId: 'doc-1', documentVersionId: 'v1', chunkIndex: 0, sourceLocation: '请假制度', citationText: '[1]', validationStatus: 'VALID' }] } })
      onEvent({ name: 'answer.completed', data: {} })
    })

    render(<QaPage />)

    await screen.findByRole('heading', { name: '向制度知识库提问' })
    fireEvent.change(screen.getByLabelText('问题'), { target: { value: '请假' } })
    fireEvent.click(screen.getByRole('button', { name: '发送' }))

    const chip = await screen.findByRole('button', { name: /\[1\] 请假制度/ })
    fireEvent.click(chip)

    expect(await screen.findByRole('dialog', { name: '请假制度' })).toBeInTheDocument()
    expect(screen.getByText('员工请假需提前两个工作日申请')).toBeInTheDocument()
  })

  test('shows refusal message with generic access wording', async () => {
    mockedAsk.mockImplementation(async (_question, _kbIds, _conversationId, onEvent) => {
      onEvent({ name: 'answer.refused', data: { reason: 'ACCESS_RESTRICTED', message: '当前可访问知识范围内证据不足' } })
    })

    render(<QaPage />)

    await screen.findByRole('heading', { name: '向制度知识库提问' })
    fireEvent.change(screen.getByLabelText('问题'), { target: { value: '请假' } })
    fireEvent.click(screen.getByRole('button', { name: '发送' }))

    expect(await screen.findByText('当前可访问知识范围内证据不足')).toBeInTheDocument()
  })

  test('shows run failure as an error message', async () => {
    mockedAsk.mockImplementation(async (_question, _kbIds, _conversationId, onEvent) => {
      onEvent({ name: 'run.failed', data: { message: 'opensearch down' } })
    })

    render(<QaPage />)

    await screen.findByRole('heading', { name: '向制度知识库提问' })
    fireEvent.change(screen.getByLabelText('问题'), { target: { value: '请假' } })
    fireEvent.click(screen.getByRole('button', { name: '发送' }))

    expect(await screen.findByText('opensearch down')).toBeInTheDocument()
  })

  test('loading a conversation populates message history', async () => {
    mockedConversations.mockResolvedValue([{ id: 'c1', title: '请假', createdAt: '2026-08-12T00:00:00Z' }])
    vi.mocked(qaApi.messages).mockResolvedValue([
      { id: 'm1', role: 'USER', content: '请假几天', queryRunId: null },
      { id: 'm2', role: 'ASSISTANT', content: '根据《请假制度》回答', queryRunId: 'r1' },
    ])

    render(<QaPage />)

    const conversationButton = await screen.findByRole('button', { name: /请假/ })
    fireEvent.click(conversationButton)

    expect(await screen.findByText('根据《请假制度》回答')).toBeInTheDocument()
    expect(screen.getByText('请假几天')).toBeInTheDocument()
  })
})
