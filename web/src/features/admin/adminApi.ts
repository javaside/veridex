export type ApiKeyScope = 'qa' | 'knowledge:read' | 'knowledge:write' | 'configuration' | 'evaluation' | 'feedback'

export const API_KEY_SCOPES: { value: ApiKeyScope; label: string }[] = [
  { value: 'qa', label: '问答（/api/qa）' },
  { value: 'knowledge:read', label: '知识读取（GET /api/knowledge-bases, /api/documents）' },
  { value: 'knowledge:write', label: '知识写入（上传/发布）' },
  { value: 'configuration', label: '配置版本（/api/configuration）' },
  { value: 'evaluation', label: '评测（/api/evaluation）' },
  { value: 'feedback', label: '反馈（/api/feedback）' },
]

export type ApiKeyView = {
  id: string
  name: string
  tokenPrefix: string
  userId: string
  scopes: string[]
  createdAt: string
  revokedAt: string | null
  lastUsedAt: string | null
}

export type ApiKeyCreated = {
  id: string
  userId: string
  name: string
  tokenPrefix: string
  token: string
  scopes: string[]
  createdAt: string
}

const json = async <T>(response: Response): Promise<T> => {
  if (!response.ok) {
    throw new Error((await response.text().catch(() => '')) || `请求失败 (${response.status})`)
  }
  return response.json() as Promise<T>
}

export const adminApi = {
  listKeys: (): Promise<ApiKeyView[]> =>
    fetch('/api/iam/keys', { credentials: 'include' }).then((response) => json<ApiKeyView[]>(response)),
  createKey: (name: string, scopes: ApiKeyScope[]): Promise<ApiKeyCreated> =>
    fetch('/api/iam/keys', {
      method: 'POST',
      credentials: 'include',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name, scopes }),
    }).then((response) => json<ApiKeyCreated>(response)),
  revokeKey: (id: string): Promise<void> =>
    fetch(`/api/iam/keys/${id}`, { method: 'DELETE', credentials: 'include' }).then(async (response) => {
      if (!response.ok) {
        throw new Error((await response.text().catch(() => '')) || `吊销失败 (${response.status})`)
      }
    }),
}
