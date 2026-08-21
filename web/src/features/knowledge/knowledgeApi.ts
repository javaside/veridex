import { csrfHeaders } from '../../lib/csrf'

export type KnowledgeBase = { id: string; name: string; description: string | null; slug: string }
export type DocumentSummary = { id: string; filename: string; contentType: string; sizeBytes: number }
export type DocumentVersion = {
  id: string
  documentId: string
  versionNo: number
  status: string
  chunkCount: number
  errorMessage: string | null
  objectKey: string
}
export type ChunkPreview = { index: number; text: string; title: string; structurePath: string }
export type DocumentVersionLocation = { knowledgeBaseId: string; documentId: string; versionId: string }
export type Release = {
  releaseId: string
  versionNo: number
  status: string
  indexName: string
  aliasName: string
  isActive: boolean
  documentCount: number
  chunkCount: number
}
export type PublishResult = { release: Release; excludedCount: number }

const json = async <T>(response: Response): Promise<T> => {
  if (!response.ok) {
    const message = await response.text().catch(() => '')
    throw new Error(message || `请求失败 (${response.status})`)
  }
  return (response.status === 204 ? null : await response.json()) as T
}

export const knowledgeApi = {
  list: (): Promise<KnowledgeBase[]> =>
    fetch('/api/knowledge-bases', { credentials: 'include' }).then((response) => json<KnowledgeBase[]>(response)),
  create: (name: string, description?: string): Promise<KnowledgeBase> =>
    fetch('/api/knowledge-bases', {
      method: 'POST',
      credentials: 'include',
      headers: { 'Content-Type': 'application/json', ...csrfHeaders() },
      body: JSON.stringify({ name, description }),
    }).then((response) => json<KnowledgeBase>(response)),
  documents: (kbId: string): Promise<DocumentSummary[]> =>
    fetch(`/api/knowledge-bases/${kbId}/documents`, { credentials: 'include' }).then((response) => json<DocumentSummary[]>(response)),
  versions: (documentId: string): Promise<DocumentVersion[]> =>
    fetch(`/api/documents/${documentId}/versions`, { credentials: 'include' }).then((response) => json<DocumentVersion[]>(response)),
  locateVersion: (versionId: string): Promise<DocumentVersionLocation | null> =>
    fetch(`/api/documents/versions/${versionId}/location`, { credentials: 'include' }).then(async (response) => {
      if (response.status === 404) return null
      return json<DocumentVersionLocation>(response)
    }),
  upload: (kbId: string, file: File): Promise<DocumentVersion> => {
    const form = new FormData()
    form.append('file', file)
    return fetch(`/api/knowledge-bases/${kbId}/documents`, {
      method: 'POST',
      credentials: 'include',
      headers: csrfHeaders(),
      body: form,
    }).then((response) => json<DocumentVersion>(response))
  },
  parsed: (documentId: string, versionId: string): Promise<string> =>
    fetch(`/api/documents/${documentId}/versions/${versionId}/parsed`, { credentials: 'include' }).then(async (response) => {
      if (!response.ok) throw new Error((await response.text().catch(() => '')) || `预览加载失败 (${response.status})`)
      return response.text()
    }),
  chunks: (documentId: string, versionId: string): Promise<ChunkPreview[]> =>
    fetch(`/api/documents/${documentId}/versions/${versionId}/chunks`, { credentials: 'include' }).then((response) => json<ChunkPreview[]>(response)),
  releases: (kbId: string): Promise<Release[]> =>
    fetch(`/api/knowledge-bases/${kbId}/releases`, { credentials: 'include' }).then((response) => json<Release[]>(response)),
  publish: (kbId: string): Promise<PublishResult> =>
    fetch(`/api/knowledge-bases/${kbId}/releases/publish`, {
      method: 'POST',
      credentials: 'include',
      headers: csrfHeaders(),
    }).then((response) => json<PublishResult>(response)),
  releaseAction: (kbId: string, releaseId: string, action: 'make-current' | 'offline' | 'delete'): Promise<null> =>
    fetch(`/api/knowledge-bases/${kbId}/releases/${releaseId}/${action}`, {
      method: 'POST',
      credentials: 'include',
      headers: csrfHeaders(),
    }).then((response) => json<null>(response)),
}
