import { CaretDown, CaretRight, FileText, MagnifyingGlass } from '@phosphor-icons/react'
import { useCallback, useEffect, useRef, useState } from 'react'
import { knowledgeApi, type DocumentSummary, type DocumentVersion, type Release } from '../knowledgeApi'
import { PreviewDrawer } from './PreviewDrawer'
import { ReleaseList } from './ReleaseList'
import { StatusBadge } from './StatusBadge'

const formatBytes = (bytes: number) => bytes < 1024 * 1024 ? `${Math.max(1, Math.round(bytes / 1024))} KB` : `${(bytes / 1024 / 1024).toFixed(1)} MB`

export function VersionList({ kbId, refreshKey }: { kbId: string; refreshKey: number }) {
  const [documents, setDocuments] = useState<DocumentSummary[]>([])
  const [versions, setVersions] = useState<Record<string, DocumentVersion[]>>({})
  const [releases, setReleases] = useState<Release[]>([])
  const [loading, setLoading] = useState(true)
  const [releaseLoading, setReleaseLoading] = useState(true)
  const [documentError, setDocumentError] = useState<string | null>(null)
  const [releaseError, setReleaseError] = useState<string | null>(null)
  const [versionErrors, setVersionErrors] = useState<Record<string, string>>({})
  const [expandedDoc, setExpandedDoc] = useState<string | null>(null)
  const [preview, setPreview] = useState<{ title: string; content: string } | null>(null)
  const previewButtonRef = useRef<HTMLButtonElement>(null)

  const loadDocuments = useCallback(async () => { setLoading(true); setDocumentError(null); try { setDocuments(await knowledgeApi.documents(kbId)) } catch (caught) { setDocumentError(caught instanceof Error ? caught.message : '文档加载失败') } finally { setLoading(false) } }, [kbId])
  const loadReleases = useCallback(async () => { setReleaseLoading(true); setReleaseError(null); try { setReleases(await knowledgeApi.releases(kbId)) } catch (caught) { setReleaseError(caught instanceof Error ? caught.message : '索引发布加载失败') } finally { setReleaseLoading(false) } }, [kbId])

  useEffect(() => { void loadDocuments(); void loadReleases() }, [loadDocuments, loadReleases, refreshKey])

  const toggleDoc = async (doc: DocumentSummary) => {
    if (expandedDoc === doc.id) { setExpandedDoc(null); return }
    setExpandedDoc(doc.id)
    if (versions[doc.id]) return
    setVersionErrors((current) => ({ ...current, [doc.id]: '' }))
    try { setVersions((current) => ({ ...current, [doc.id]: await knowledgeApi.versions(doc.id) })) } catch (caught) { setVersionErrors((current) => ({ ...current, [doc.id]: caught instanceof Error ? caught.message : '版本加载失败' })) }
  }

  const showPreview = async (doc: DocumentSummary, version: DocumentVersion, button: HTMLButtonElement) => {
    previewButtonRef.current = button
    try { setPreview({ title: `${doc.filename} v${version.versionNo} 解析预览`, content: await knowledgeApi.parsed(doc.id, version.id) }) } catch (caught) { setVersionErrors((current) => ({ ...current, [doc.id]: caught instanceof Error ? caught.message : '预览加载失败' })) }
  }

  return (
    <div className="knowledge-data-grid">
      <section className="panel data-panel documents-panel">
        <header className="panel-header"><div><h3>文档与版本</h3><p>查看处理状态、Chunk 数量和解析结果。</p></div><span className="count-label">{documents.length}</span></header>
        {loading && <div className="table-skeleton" role="status" aria-label="正在加载文档"><i /><i /><i /></div>}
        {!loading && documentError && <div className="state-block compact"><p role="alert">{documentError}</p><button className="secondary-button" onClick={() => void loadDocuments()}>重试</button></div>}
        {!loading && !documentError && documents.length === 0 && <div className="state-block compact"><FileText size={28} /><h4>尚无文档</h4><p>从上方选择文件，提交后可在这里跟踪处理进度。</p></div>}
        {!loading && !documentError && documents.length > 0 && <div className="document-list">{documents.map((doc) => <article className="document-item" key={doc.id}><button className="document-toggle" onClick={() => void toggleDoc(doc)} aria-expanded={expandedDoc === doc.id}>{expandedDoc === doc.id ? <CaretDown size={18} /> : <CaretRight size={18} />}<span className="document-icon"><FileText size={19} /></span><span className="document-name"><strong>{doc.filename}</strong><small>{doc.contentType} / {formatBytes(doc.sizeBytes)}</small></span></button>{expandedDoc === doc.id && <div className="document-versions">{versionErrors[doc.id] && <p className="row-error" role="alert">{versionErrors[doc.id]}</p>}{!versions[doc.id] && !versionErrors[doc.id] && <p className="loading-copy" role="status">正在加载版本</p>}{(versions[doc.id] ?? []).map((version) => <div className="version-row" key={version.id}><strong>v{version.versionNo}</strong><StatusBadge status={version.status} /><span>{version.chunkCount} chunks</span>{version.status === 'READY' && <button className="text-button" aria-label={`预览 v${version.versionNo}`} onClick={(event) => void showPreview(doc, version, event.currentTarget)}><MagnifyingGlass size={16} />预览</button>}{version.errorMessage && <p className="row-error">{version.errorMessage}</p>}</div>)}</div>}</article>)}</div>}
      </section>
      <ReleaseList kbId={kbId} releases={releases} loading={releaseLoading} error={releaseError} onChanged={() => void loadReleases()} onRetry={() => void loadReleases()} />
      <PreviewDrawer title={preview?.title ?? ''} content={preview?.content ?? ''} open={Boolean(preview)} onClose={() => setPreview(null)} returnFocusRef={previewButtonRef} />
    </div>
  )
}
