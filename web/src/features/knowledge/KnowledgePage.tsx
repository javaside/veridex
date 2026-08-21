import { FolderOpen, RocketLaunch } from '@phosphor-icons/react'
import { useCallback, useEffect, useState } from 'react'
import { Toast } from '../../components/Toast'
import { PageHeader } from '../../app/PageHeader'
import { knowledgeApi, type DocumentVersion, type KnowledgeBase } from './knowledgeApi'
import { KnowledgeBaseList } from './components/KnowledgeBaseList'
import { UploadForm } from './components/UploadForm'
import { VersionList } from './components/VersionList'

export function KnowledgePage() {
  const [bases, setBases] = useState<KnowledgeBase[]>([])
  const [selected, setSelected] = useState<KnowledgeBase | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [creating, setCreating] = useState(false)
  const [refreshKey, setRefreshKey] = useState(0)
  const [publishing, setPublishing] = useState(false)
  const [toast, setToast] = useState<{ type: 'success' | 'error'; message: string } | null>(null)
  const [highlightReleaseId, setHighlightReleaseId] = useState<string | null>(null)
  const [lastUploaded, setLastUploaded] = useState<{ documentId: string; versionId: string } | null>(null)

  const loadBases = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const next = await knowledgeApi.list()
      setBases(next)
      setSelected((current) => next.find((base) => base.id === current?.id) ?? next[0] ?? null)
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : '知识库加载失败')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    let active = true
    knowledgeApi.list()
      .then((next) => {
        if (!active) return
        setBases(next)
        setSelected(next[0] ?? null)
      })
      .catch((caught) => {
        if (active) setError(caught instanceof Error ? caught.message : '知识库加载失败')
      })
      .finally(() => {
        if (active) setLoading(false)
      })
    return () => { active = false }
  }, [])

  const createBase = async (name: string) => {
    setCreating(true)
    try {
      const created = await knowledgeApi.create(name)
      setBases((current) => [created, ...current])
      setSelected(created)
    } finally {
      setCreating(false)
    }
  }

  const publishNow = async () => {
    if (!selected) return
    setPublishing(true)
    setToast(null)
    try {
      const result = await knowledgeApi.publish(selected.id)
      setToast({ type: 'success', message: result.excludedCount > 0 ? `本次发布未包含 ${result.excludedCount} 个文档` : '已发布当前知识库' })
      setHighlightReleaseId(result.release.releaseId)
      setRefreshKey((key) => key + 1)
    } catch (caught) {
      setToast({ type: 'error', message: caught instanceof Error ? caught.message : '发布失败' })
    } finally {
      setPublishing(false)
    }
  }

  return (
    <section className="workspace-page knowledge-page">
      <PageHeader title="知识管理" description="管理知识库、文档版本和当前检索发布。" meta={`${bases.length} 个知识库`} />
      <div className="knowledge-layout">
        <KnowledgeBaseList bases={bases} selectedId={selected?.id ?? null} loading={loading} error={error} creating={creating} onSelect={setSelected} onRetry={() => void loadBases()} onCreate={createBase} />
        <div className="knowledge-workspace">
          {selected ? (
            <>
              <header className="knowledge-workspace-header"><div><p className="section-kicker">当前知识库</p><h2>{selected.name}</h2><p>{selected.description || '管理该知识库中的文档、版本和索引发布。'}</p></div><div className="workspace-header-actions"><button className="primary-button" type="button" onClick={() => void publishNow()} disabled={publishing}><RocketLaunch size={18} aria-hidden="true" />{publishing ? '正在发布' : '发布'}</button></div></header>
              <UploadForm kbId={selected.id} onUploaded={(version: DocumentVersion) => { setLastUploaded({ documentId: version.documentId, versionId: version.id }); setRefreshKey((key) => key + 1) }} onNotify={(type, message) => setToast({ type, message })} />
              <VersionList kbId={selected.id} refreshKey={refreshKey} highlightReleaseId={highlightReleaseId} lastUploaded={lastUploaded} />
            </>
          ) : (
            <div className="panel state-block workspace-empty"><FolderOpen size={34} aria-hidden="true" /><h2>选择一个知识库</h2><p>从左侧选择知识库，或创建第一个知识库开始上传文档。</p></div>
          )}
        </div>
      </div>
      {toast && <Toast type={toast.type} message={toast.message} onDismiss={() => setToast(null)} />}
    </section>
  )
}
