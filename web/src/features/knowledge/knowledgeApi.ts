export type KnowledgeBase = { id: string; name: string; description: string | null; slug: string }
export type DocumentSummary = { id: string; filename: string; contentType: string; sizeBytes: number }
export type DocumentVersion = {
  id: string
  versionNo: number
  status: string
  chunkCount: number
  errorMessage: string | null
  objectKey: string
}
export type ChunkPreview = { index: number; text: string; title: string; structurePath: string }
export type Release = { releaseId: string; versionNo: number; status: string; indexName: string; aliasName: string }

const json = (r: Response) => {
  if (!r.ok) throw new Error(`HTTP ${r.status}`)
  return r.status === 204 ? null : r.json()
}

export const knowledgeApi = {
  list: (): Promise<KnowledgeBase[]> =>
    fetch('/api/knowledge-bases', { credentials: 'include' }).then(json),
  create: (name: string, description?: string): Promise<KnowledgeBase> =>
    fetch('/api/knowledge-bases', {
      method: 'POST',
      credentials: 'include',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name, description }),
    }).then(json),
  documents: (kbId: string): Promise<DocumentSummary[]> =>
    fetch(`/api/knowledge-bases/${kbId}/documents`, { credentials: 'include' }).then(json),
  versions: (documentId: string): Promise<DocumentVersion[]> =>
    fetch(`/api/documents/${documentId}/versions`, { credentials: 'include' }).then(json),
  upload: (kbId: string, file: File): Promise<DocumentVersion> => {
    const form = new FormData()
    form.append('file', file)
    return fetch(`/api/knowledge-bases/${kbId}/documents`, {
      method: 'POST',
      credentials: 'include',
      body: form,
    }).then(json)
  },
  parsed: (documentId: string, versionId: string): Promise<string> =>
    fetch(`/api/documents/${documentId}/versions/${versionId}/parsed`, { credentials: 'include' }).then((r) => r.text()),
  chunks: (documentId: string, versionId: string): Promise<ChunkPreview[]> =>
    fetch(`/api/documents/${documentId}/versions/${versionId}/chunks`, { credentials: 'include' }).then(json),
  releases: (kbId: string): Promise<Release[]> =>
    fetch(`/api/knowledge-bases/${kbId}/releases`, { credentials: 'include' }).then(json),
  releaseAction: (kbId: string, releaseId: string, action: 'publish' | 'rollback' | 'offline' | 'delete'): Promise<null> =>
    fetch(`/api/knowledge-bases/${kbId}/releases/${releaseId}/${action}`, {
      method: 'POST',
      credentials: 'include',
    }).then(json),
}
