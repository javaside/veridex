import { csrfHeaders } from '../../lib/csrf'

export type ChunkingConfig = { maxChars: number; overlap: number }
export type RetrievalConfig = {
  topKPerChannel: number
  rrfK: number
  contextTopK: number
  perDocumentMax: number
  contextMaxChars: number
}
export type GenerationConfig = { maxHistoryTurns: number; minEvidenceChars: number }
export type PromptConfig = { systemTemplate: string }
export type ModelConfig = { chatModel: string; embeddingModel: string }

export type ProfileConfig = {
  chunking: ChunkingConfig
  retrieval: RetrievalConfig
  generation: GenerationConfig
  prompt: PromptConfig
  model: ModelConfig
}

export type ProfileView = {
  id: string
  name: string
  description: string | null
  versionCount: number
  latestVersionNo: number | null
}

export type ProfileDetail = {
  id: string
  name: string
  description: string | null
  draft: ProfileConfig
}

export type VersionView = { id: string; versionNo: number; createdAt: string }

export type VersionDetail = {
  id: string
  versionNo: number
  createdAt: string
  config: ProfileConfig
}

export type PublishResult = { versionId: string; versionNo: number }

export const defaultConfig = (): ProfileConfig => ({
  chunking: { maxChars: 2000, overlap: 80 },
  retrieval: { topKPerChannel: 30, rrfK: 60, contextTopK: 6, perDocumentMax: 3, contextMaxChars: 4000 },
  generation: { maxHistoryTurns: 6, minEvidenceChars: 50 },
  prompt: { systemTemplate: '你是企业制度问答助手。只允许使用以下证据回答，不得使用模型通用知识补全。\n' },
  model: { chatModel: 'deterministic', embeddingModel: 'deterministic' },
})

const json = async <T>(response: Response): Promise<T> => {
  if (!response.ok) {
    const message = await response.text().catch(() => '')
    throw new Error(message || `请求失败 (${response.status})`)
  }
  return (response.status === 204 ? null : await response.json()) as T
}

const send = (method: string, payload: unknown): RequestInit => ({
  method,
  credentials: 'include',
  headers: { 'Content-Type': 'application/json', ...csrfHeaders() },
  body: JSON.stringify(payload),
})

export const configurationApi = {
  list: (): Promise<ProfileView[]> =>
    fetch('/api/configuration/profiles', { credentials: 'include' }).then((r) => json<ProfileView[]>(r)),
  get: (id: string): Promise<ProfileDetail> =>
    fetch(`/api/configuration/profiles/${id}`, { credentials: 'include' }).then((r) => json<ProfileDetail>(r)),
  create: (name: string, description: string | null, draft: ProfileConfig): Promise<ProfileDetail> =>
    fetch('/api/configuration/profiles', send('POST', { name, description, draft })).then((r) => json<ProfileDetail>(r)),
  update: (id: string, name: string, description: string | null, draft: ProfileConfig): Promise<ProfileDetail> =>
    fetch(`/api/configuration/profiles/${id}`, send('PUT', { name, description, draft })).then((r) => json<ProfileDetail>(r)),
  publish: (id: string): Promise<PublishResult> =>
    fetch(`/api/configuration/profiles/${id}/publish`, { method: 'POST', credentials: 'include', headers: csrfHeaders() }).then((r) => json<PublishResult>(r)),
  versions: (id: string): Promise<VersionView[]> =>
    fetch(`/api/configuration/profiles/${id}/versions`, { credentials: 'include' }).then((r) => json<VersionView[]>(r)),
  version: (id: string, versionNo: number): Promise<VersionDetail> =>
    fetch(`/api/configuration/profiles/${id}/versions/${versionNo}`, { credentials: 'include' }).then((r) => json<VersionDetail>(r)),
}
