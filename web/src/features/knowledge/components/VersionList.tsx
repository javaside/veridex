import { useEffect, useState } from 'react'
import { knowledgeApi, type DocumentSummary, type DocumentVersion, type Release } from '../knowledgeApi'

const STATUS_LABEL: Record<string, string> = {
  UPLOADED: '已上传',
  PROCESSING: '处理中',
  READY: '就绪',
  FAILED: '失败',
  OFFLINE: '离线',
}

export function VersionList({ kbId, refreshKey }: { kbId: string; refreshKey: number }) {
  const [documents, setDocuments] = useState<DocumentSummary[]>([])
  const [versions, setVersions] = useState<Record<string, DocumentVersion[]>>({})
  const [releases, setReleases] = useState<Release[]>([])
  const [preview, setPreview] = useState<string | null>(null)
  const [expandedDoc, setExpandedDoc] = useState<string | null>(null)

  useEffect(() => {
    knowledgeApi.documents(kbId).then(setDocuments).catch(() => {})
    knowledgeApi.releases(kbId).then(setReleases).catch(() => {})
  }, [kbId, refreshKey])

  const toggleDoc = async (doc: DocumentSummary) => {
    if (expandedDoc === doc.id) {
      setExpandedDoc(null)
      setPreview(null)
      return
    }
    setExpandedDoc(doc.id)
    knowledgeApi.versions(doc.id).then((vs) => setVersions((prev) => ({ ...prev, [doc.id]: vs })))
  }

  const showPreview = async (docId: string, versionId: string) => {
    setPreview(await knowledgeApi.parsed(docId, versionId))
  }

  const releaseAction = async (action: 'publish' | 'rollback' | 'offline' | 'delete', releaseId: string) => {
    await knowledgeApi.releaseAction(kbId, releaseId, action)
    knowledgeApi.releases(kbId).then(setReleases)
  }

  return (
    <div className="version-list">
      <h3>文档</h3>
      <ul>
        {documents.map((doc) => (
          <li key={doc.id}>
            <button className="doc-toggle" onClick={() => toggleDoc(doc)}>
              {doc.filename}
            </button>
            {expandedDoc === doc.id && (
              <div className="doc-versions">
                {(versions[doc.id] ?? []).map((v) => (
                  <div key={v.id} className="version-row">
                    <span>v{v.versionNo}</span>
                    <span className={`status status-${v.status.toLowerCase()}`}>{STATUS_LABEL[v.status] ?? v.status}</span>
                    <span>{v.chunkCount} chunks</span>
                    {v.status === 'READY' && (
                      <button onClick={() => showPreview(doc.id, v.id)}>预览</button>
                    )}
                  </div>
                ))}
              </div>
            )}
          </li>
        ))}
      </ul>

      <h3>索引发布</h3>
      <ul>
        {releases.map((rel) => (
          <li key={rel.releaseId} className="release-row">
            <span>v{rel.versionNo}</span>
            <span className={`status status-${rel.status.toLowerCase()}`}>{rel.status}</span>
            <span>{rel.indexName}</span>
            <button onClick={() => releaseAction('rollback', rel.releaseId)}>回滚</button>
            <button onClick={() => releaseAction('offline', rel.releaseId)}>离线</button>
            <button onClick={() => releaseAction('delete', rel.releaseId)}>删除</button>
          </li>
        ))}
      </ul>

      {preview && (
        <pre className="preview-box" onClick={() => setPreview(null)}>
          {preview}
        </pre>
      )}
    </div>
  )
}
