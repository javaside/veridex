import { useCallback, useEffect, useState } from 'react'
import { knowledgeApi, type ChunkPreview, type DocumentSummary, type DocumentVersion } from '../../knowledge/knowledgeApi'
import type { EvidenceRef } from '../evaluationApi'

type KnowledgeBase = { id: string; name: string }

/**
 * 反向定位：给定一个 documentVersionId，找出它所属的「知识库 → 文档 → 版本」。
 * 证据只存了 documentVersionId，没有冗余 KB/文档信息，因此需要遍历定位。
 */
async function resolveVersion(kbList: KnowledgeBase[], versionId: string) {
  for (const kb of kbList) {
    const docs = await knowledgeApi.documents(kb.id)
    for (const doc of docs) {
      const vers = await knowledgeApi.versions(doc.id)
      const found = vers.find((v) => v.id === versionId)
      if (found) {
        return { baseId: kb.id, documentId: doc.id, versionId: found.id }
      }
    }
  }
  return null
}

export function EvidencePicker({ initial, onSelect }: { initial?: EvidenceRef[]; onSelect: (evidence: EvidenceRef[]) => void }) {
  const [bases, setBases] = useState<KnowledgeBase[]>([])
  const [baseId, setBaseId] = useState<string>('')
  const [documents, setDocuments] = useState<DocumentSummary[]>([])
  const [documentId, setDocumentId] = useState<string>('')
  const [versions, setVersions] = useState<DocumentVersion[]>([])
  const [versionId, setVersionId] = useState<string>('')
  const [chunks, setChunks] = useState<ChunkPreview[]>([])
  const [checked, setChecked] = useState<number[]>([])
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let active = true
    const load = async () => {
      try {
        const kbList = await knowledgeApi.list()
        if (!active) return
        setBases(kbList)

        // 回显已保存的证据：定位到对应知识库/文档/版本并勾选分块
        const ref = (initial ?? []).find((r) => r.chunkIndexes.length > 0)
        if (ref) {
          const resolved = await resolveVersion(kbList, ref.documentVersionId)
          if (!active) return
          if (resolved) {
            const [docs, vers, chunkList] = await Promise.all([
              knowledgeApi.documents(resolved.baseId),
              knowledgeApi.versions(resolved.documentId),
              knowledgeApi.chunks(resolved.documentId, resolved.versionId),
            ])
            if (!active) return
            setBaseId(resolved.baseId)
            setDocuments(docs)
            setDocumentId(resolved.documentId)
            setVersions(vers)
            setVersionId(resolved.versionId)
            setChunks(chunkList)
            setChecked(ref.chunkIndexes.filter((i) => chunkList.some((c) => c.index === i)))
          }
        }
      } catch (e) {
        if (active) setError(e instanceof Error ? e.message : '加载知识库失败')
      }
    }
    void load()
    return () => { active = false }
  }, [initial])

  const loadDocuments = useCallback((id: string) => {
    setBaseId(id)
    setDocumentId('')
    setVersionId('')
    setChunks([])
    setChecked([])
    knowledgeApi.documents(id).then(setDocuments).catch((e) => setError(e instanceof Error ? e.message : '加载文档失败'))
  }, [])

  const loadVersions = useCallback((id: string) => {
    setDocumentId(id)
    setVersionId('')
    setChunks([])
    setChecked([])
    knowledgeApi.versions(id).then(setVersions).catch((e) => setError(e instanceof Error ? e.message : '加载版本失败'))
  }, [])

  const loadChunks = useCallback((id: string) => {
    setVersionId(id)
    setChecked([])
    knowledgeApi.chunks(documentId, id).then(setChunks).catch((e) => setError(e instanceof Error ? e.message : '加载分块失败'))
  }, [documentId])

  const toggle = (index: number) => {
    setChecked((current) => (current.includes(index) ? current.filter((i) => i !== index) : [...current, index].sort((a, b) => a - b)))
  }

  const apply = () => {
    if (versionId && checked.length > 0) {
      onSelect([{ documentVersionId: versionId, chunkIndexes: checked }])
    }
  }

  return (
    <div className="evidence-picker">
      {error && <p className="row-error" role="alert">{error}</p>}
      <label>知识库<select value={baseId} onChange={(e) => loadDocuments(e.target.value)}><option value="">选择知识库</option>{bases.map((b) => <option key={b.id} value={b.id}>{b.name}</option>)}</select></label>
      <label>文档<select value={documentId} onChange={(e) => loadVersions(e.target.value)} disabled={!baseId}><option value="">选择文档</option>{documents.map((d) => <option key={d.id} value={d.id}>{d.filename}</option>)}</select></label>
      <label>文档版本<select value={versionId} onChange={(e) => loadChunks(e.target.value)} disabled={!documentId}><option value="">选择版本</option>{versions.filter((v) => v.status === 'READY').map((v) => <option key={v.id} value={v.id}>v{v.versionNo}</option>)}</select></label>
      {chunks.length > 0 && (
        <div className="evidence-chunks">
          {chunks.map((chunk) => (
            <label key={chunk.index} className="evidence-chunk"><input type="checkbox" checked={checked.includes(chunk.index)} onChange={() => toggle(chunk.index)} /><span><strong>#{chunk.index}</strong> {chunk.text.slice(0, 80)}{chunk.text.length > 80 ? '…' : ''}</span></label>
          ))}
        </div>
      )}
      <button className="secondary-button" type="button" onClick={apply} disabled={!versionId || checked.length === 0}>确认证据</button>
    </div>
  )
}
