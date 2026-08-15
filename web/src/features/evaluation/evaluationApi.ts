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

export type RunMetrics = {
  avgRecallAt1: number
  avgRecallAt3: number
  avgRecallAt5: number
  avgMrr: number
  avgNdcgAt10: number
  citationHitRate: number
  refusalMatchRate: number
  avgLatencyMs: number
  caseCount: number
  completedCount: number
}

export type CaseMetrics = {
  recallAt1: number
  recallAt3: number
  recallAt5: number
  mrr: number
  ndcgAt10: number
  citationHit: number
  refusalMatch: boolean
  latencyMs: number
}

export type RunCitationRecord = { citationIndex: number; documentVersionId: string; chunkIndex: number; validationStatus: string }
export type RetrievedChunkRecord = { documentVersionId: string; chunkIndex: number; rank: number; fusionScore: number | null }

export type RunCaseView = {
  position: number
  question: string
  expectedBehavior: string
  actualBehavior: string
  answer: string | null
  groundTruthEvidence: EvidenceRef[]
  citations: RunCitationRecord[]
  retrievedChunks: RetrievedChunkRecord[]
  metrics: CaseMetrics
}

export type RunView = {
  id: string
  datasetId: string
  datasetVersionId: string
  profileId: string
  profileVersionNo: number
  status: string
  metrics: RunMetrics | null
  createdAt: string
  completedAt: string | null
}

export type RunDetail = RunView & { error: string | null; cases: RunCaseView[] }

export type GateVerdict = 'PASS' | 'FAIL' | 'INCOMPLETE'
export type MetricComparison = { name: string; baseline: number; candidate: number; delta: number }
export type GateCheck = { metric: string; baseline: number; candidate: number; maxAllowed: number }
export type ComparisonResult = {
  baselineRunId: string
  candidateRunId: string
  baseline: RunMetrics | null
  candidate: RunMetrics | null
  metrics: MetricComparison[]
  verdict: GateVerdict
  failedChecks: GateCheck[]
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
  startRun: (payload: {
    datasetId: string
    datasetVersionNo: number
    profileId: string
    profileVersionNo: number
    knowledgeBaseIds: string[]
  }): Promise<RunView> =>
    fetch('/api/evaluation/runs', post(payload)).then((r) => json<RunView>(r)),
  runs: (datasetId: string): Promise<RunView[]> =>
    fetch(`/api/evaluation/runs?datasetId=${datasetId}`, { credentials: 'include' }).then((r) => json<RunView[]>(r)),
  run: (id: string): Promise<RunDetail> =>
    fetch(`/api/evaluation/runs/${id}`, { credentials: 'include' }).then((r) => json<RunDetail>(r)),
  compareRuns: (baselineRunId: string, candidateRunId: string): Promise<ComparisonResult> =>
    fetch('/api/evaluation/comparisons', post({ baselineRunId, candidateRunId })).then((r) => json<ComparisonResult>(r)),
}
