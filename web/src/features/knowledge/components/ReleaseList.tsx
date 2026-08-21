import { ArrowsClockwise, CloudSlash, Trash } from '@phosphor-icons/react'
import { useState } from 'react'
import { ConfirmDialog } from '../../../components/ConfirmDialog'
import { knowledgeApi, type Release } from '../knowledgeApi'
import { StatusBadge } from './StatusBadge'

export function ReleaseList({ kbId, releases, loading, error, onChanged, onRetry, highlightReleaseId }: { kbId: string; releases: Release[]; loading: boolean; error: string | null; onChanged: () => void; onRetry: () => void; highlightReleaseId?: string | null }) {
  const [pendingId, setPendingId] = useState<string | null>(null)
  const [rowError, setRowError] = useState<Record<string, string>>({})
  const [confirmingDelete, setConfirmingDelete] = useState<Release | null>(null)

  const act = async (release: Release, action: 'make-current' | 'offline' | 'delete') => {
    setPendingId(release.releaseId)
    setRowError((current) => ({ ...current, [release.releaseId]: '' }))
    try {
      await knowledgeApi.releaseAction(kbId, release.releaseId, action)
      onChanged()
    } catch (caught) {
      setRowError((current) => ({ ...current, [release.releaseId]: caught instanceof Error ? caught.message : '操作失败' }))
    } finally {
      setPendingId(null)
    }
  }

  const confirmDelete = (release: Release) => {
    setConfirmingDelete(null)
    void act(release, 'delete')
  }

  const statusLabel = (release: Release) => {
    if (release.status === 'PUBLISHING') return '发布中'
    if (release.isActive) return '当前检索'
    if (release.status === 'PUBLISHED') return '历史版本'
    return '已下架'
  }

  const hasActive = releases.some((release) => release.isActive)

  return (
    <section className="panel data-panel">
      <header className="panel-header"><div><h3>索引发布</h3><p>控制当前可用于检索的不可变索引版本。</p></div><span className="count-label">{releases.length}</span></header>
      {loading && <div className="table-skeleton" role="status" aria-label="正在加载索引发布"><i /><i /></div>}
      {!loading && error && <div className="state-block compact"><p role="alert">{error}</p><button className="secondary-button" onClick={onRetry}><ArrowsClockwise size={17} />重试</button></div>}
      {!loading && !error && releases.length === 0 && <div className="state-block compact"><h4>尚无索引发布</h4><p>文档处理完成后，点击「发布」将当前知识库固化为检索快照。</p></div>}
      {!loading && !error && releases.length > 0 && !hasActive && <div className="state-block compact"><h4>当前无检索版本</h4><p>点击「发布」将当前知识库重新上线为检索快照。</p></div>}
      {!loading && !error && releases.length > 0 && <div className="release-list">{releases.map((release) => {
        const pending = pendingId === release.releaseId
        const isPublishing = release.status === 'PUBLISHING'
        const rowClass = ['release-row', release.isActive ? 'active' : '', release.releaseId === highlightReleaseId ? 'flash' : ''].filter(Boolean).join(' ')
        return <article className={rowClass} key={release.releaseId}><div className="release-version"><strong>v{release.versionNo}</strong><StatusBadge status={release.status} /></div><code>{release.indexName}</code><div className="release-summary"><small>{statusLabel(release)}</small><small>{release.documentCount} 份文档 / {release.chunkCount} chunks</small></div><div className="row-actions">{release.isActive ? <button className="text-button" disabled={pending} onClick={() => void act(release, 'offline')}><CloudSlash size={16} />下架</button> : <button className="text-button" disabled={pending || isPublishing} onClick={() => void act(release, 'make-current')}><ArrowsClockwise size={16} />设为当前</button>}<button className="text-button danger" aria-label={`删除发布 v${release.versionNo}`} disabled={pending || release.isActive || isPublishing} title={release.isActive ? '当前检索版本，需先下架' : isPublishing ? '发布中，不可操作' : undefined} onClick={() => setConfirmingDelete(release)}><Trash size={16} />删除</button></div>{rowError[release.releaseId] && <p className="row-error" role="alert">{rowError[release.releaseId]}</p>}</article>
      })}</div>}
      <ConfirmDialog
        open={confirmingDelete !== null}
        title="删除索引发布"
        description="删除后该版本索引将永久移除，此操作无法撤销。"
        confirmLabel="删除"
        danger
        onConfirm={() => confirmingDelete && confirmDelete(confirmingDelete)}
        onCancel={() => setConfirmingDelete(null)}
      />
    </section>
  )
}
