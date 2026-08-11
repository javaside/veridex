import { FolderOpen } from '@phosphor-icons/react'
import { useCallback, useEffect, useState } from 'react'
import { PageHeader } from '../../app/PageHeader'
import { knowledgeApi, type KnowledgeBase } from './knowledgeApi'
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

  return (
    <section className="workspace-page knowledge-page">
      <PageHeader title="知识管理" description="管理知识库、文档版本和当前检索发布。" meta={`${bases.length} 个知识库`} />
      <div className="knowledge-layout">
        <KnowledgeBaseList bases={bases} selectedId={selected?.id ?? null} loading={loading} error={error} creating={creating} onSelect={setSelected} onRetry={() => void loadBases()} onCreate={createBase} />
        <div className="knowledge-workspace">
          {selected ? (
            <>
              <header className="knowledge-workspace-header"><div><p className="section-kicker">当前知识库</p><h2>{selected.name}</h2><p>{selected.description || '管理该知识库中的文档、版本和索引发布。'}</p></div><span className="availability-label">可用</span></header>
              <UploadForm kbId={selected.id} onUploaded={() => setRefreshKey((key) => key + 1)} />
              <VersionList kbId={selected.id} refreshKey={refreshKey} />
            </>
          ) : (
            <div className="panel state-block workspace-empty"><FolderOpen size={34} aria-hidden="true" /><h2>选择一个知识库</h2><p>从左侧选择知识库，或创建第一个知识库开始上传文档。</p></div>
          )}
        </div>
      </div>
    </section>
  )
}
