export type EvidenceRef = { documentVersionId: string; chunkIndexes: number[] }

export type DatasetView = {
  id: string
  name: string
  description: string | null
  caseCount: number
  latestVersionNo: number | null
}

export type ExpectedBehavior = 'ANSWER' | 'REFUSE'

export type CaseView = {
  id: string
  question: string
  expectedBehavior: ExpectedBehavior
  expectedAnswer: string | null
  evidence: EvidenceRef[]
}

export type VersionView = { id: string; versionNo: number; caseCount: number; createdAt: string }

export type VersionCaseView = {
  position: number
  question: string
  expectedBehavior: ExpectedBehavior
  expectedAnswer: string | null
  evidence: EvidenceRef[]
}

export type VersionDetail = {
  id: string
  versionNo: number
  caseCount: number
  createdAt: string
  cases: VersionCaseView[]
}

export type PublishResult = { versionId: string; versionNo: number; caseCount: number }

export type CaseInput = {
  question: string
  expectedBehavior: ExpectedBehavior
  expectedAnswer: string | null
  evidence: EvidenceRef[]
}

const json = async <T>(response: Response): Promise<T> => {
  if (!response.ok) {
    const message = await response.text().catch(() => '')
    throw new Error(message || `请求失败 (${response.status})`)
  }
  return (response.status === 204 ? null : await response.json()) as T
}

const post = (payload: unknown): RequestInit => ({
  method: 'POST',
  credentials: 'include',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify(payload),
})

const put = (payload: unknown): RequestInit => ({
  method: 'PUT',
  credentials: 'include',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify(payload),
})

export const evaluationApi = {
  list: (): Promise<DatasetView[]> =>
    fetch('/api/evaluation/datasets', { credentials: 'include' }).then((r) => json<DatasetView[]>(r)),
  get: (id: string): Promise<DatasetView> =>
    fetch(`/api/evaluation/datasets/${id}`, { credentials: 'include' }).then((r) => json<DatasetView>(r)),
  create: (name: string, description?: string): Promise<DatasetView> =>
    fetch('/api/evaluation/datasets', post({ name, description })).then((r) => json<DatasetView>(r)),
  cases: (id: string): Promise<CaseView[]> =>
    fetch(`/api/evaluation/datasets/${id}/cases`, { credentials: 'include' }).then((r) => json<CaseView[]>(r)),
  addCase: (id: string, payload: CaseInput): Promise<CaseView> =>
    fetch(`/api/evaluation/datasets/${id}/cases`, post(payload)).then((r) => json<CaseView>(r)),
  updateCase: (id: string, caseId: string, payload: CaseInput): Promise<CaseView> =>
    fetch(`/api/evaluation/datasets/${id}/cases/${caseId}`, put(payload)).then((r) => json<CaseView>(r)),
  deleteCase: (id: string, caseId: string): Promise<null> =>
    fetch(`/api/evaluation/datasets/${id}/cases/${caseId}`, { method: 'DELETE', credentials: 'include' }).then((r) => json<null>(r)),
  publish: (id: string): Promise<PublishResult> =>
    fetch(`/api/evaluation/datasets/${id}/publish`, { method: 'POST', credentials: 'include' }).then((r) => json<PublishResult>(r)),
  versions: (id: string): Promise<VersionView[]> =>
    fetch(`/api/evaluation/datasets/${id}/versions`, { credentials: 'include' }).then((r) => json<VersionView[]>(r)),
  version: (id: string, versionNo: number): Promise<VersionDetail> =>
    fetch(`/api/evaluation/datasets/${id}/versions/${versionNo}`, { credentials: 'include' }).then((r) => json<VersionDetail>(r)),
}
