export type Citation = {
  citationIndex: number
  documentVersionId: string
  chunkIndex: number
  sourceLocation: string
  citationText: string
  validationStatus: string
}

export type QaEvent =
  | { name: 'run.started'; data: { runId: string; conversationId: string } }
  | { name: 'retrieval.completed'; data: { hitCount: number } }
  | { name: 'answer.delta'; data: { text: string } }
  | { name: 'citation.available'; data: { citations: Citation[] } }
  | { name: 'answer.completed'; data: Record<string, never> }
  | { name: 'answer.refused'; data: { reason: string; message: string } }
  | { name: 'run.failed'; data: { message: string } }

export type ConversationView = { id: string; title: string; createdAt: string }
export type MessageRecord = { id: string; role: string; content: string; queryRunId: string | null }

async function parseSse(response: Response, onEvent: (event: QaEvent) => void) {
  const reader = response.body?.getReader()
  if (!reader) return
  const decoder = new TextDecoder()
  let buffer = ''
  for (;;) {
    const { done, value } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    const blocks = buffer.split('\n\n')
    buffer = blocks.pop() ?? ''
    for (const block of blocks) {
      const dataLine = block.split('\n').find((line) => line.startsWith('data:'))
      const nameLine = block.split('\n').find((line) => line.startsWith('event:'))
      if (!dataLine) continue
      const name = (nameLine?.slice(6).trim() ?? '') as QaEvent['name']
      onEvent({ name, data: JSON.parse(dataLine.slice(5).trim()) })
    }
  }
}

export const qaApi = {
  ask: async (question: string, knowledgeBaseIds: string[], conversationId: string | null, onEvent: (event: QaEvent) => void) => {
    const response = await fetch('/api/qa/ask', {
      method: 'POST',
      credentials: 'include',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ question, knowledgeBaseIds, conversationId }),
    })
    if (!response.ok) {
      onEvent({ name: 'run.failed', data: { message: `请求失败 (${response.status})` } })
      return
    }
    await parseSse(response, onEvent)
  },
  conversations: (): Promise<ConversationView[]> =>
    fetch('/api/qa/conversations', { credentials: 'include' }).then((response) => response.json()),
  messages: (conversationId: string): Promise<MessageRecord[]> =>
    fetch(`/api/qa/conversations/${conversationId}/messages`, { credentials: 'include' }).then((response) => response.json()),
}
