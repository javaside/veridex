export type FeedbackRating = 'UP' | 'DOWN'
export type FeedbackReasonCode =
  | 'WRONG_ANSWER' | 'HALLUCINATION' | 'MISSING_EVIDENCE'
  | 'OUTDATED' | 'WRONG_REFUSAL' | 'OTHER'
export type FeedbackEvidence = { documentVersionId: string; chunkIndexes: number[] }

export type FeedbackView = {
  id: string
  queryRunId: string | null
  rating: FeedbackRating
  reasonCode: FeedbackReasonCode | null
  question: string
  answer: string
  evidence: FeedbackEvidence[]
  convertedCaseId: string | null
  createdAt: string
}

const json = async <T>(response: Response): Promise<T> => {
  if (!response.ok) {
    throw new Error((await response.text().catch(() => '')) || `请求失败 (${response.status})`)
  }
  return response.json() as T
}

export const feedbackApi = {
  listDown: (): Promise<FeedbackView[]> =>
    fetch('/api/feedback?rating=DOWN', { credentials: 'include' }).then((r) => json<FeedbackView[]>(r)),
  markConverted: (id: string, caseId: string): Promise<FeedbackView> =>
    fetch(`/api/feedback/${id}/converted`, {
      method: 'POST',
      credentials: 'include',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ caseId }),
    }).then((r) => json<FeedbackView>(r)),
}
