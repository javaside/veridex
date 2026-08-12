import { ArrowsClockwise, CloudSlash, Trash } from '@phosphor-icons/react'
import { useState } from 'react'
import { knowledgeApi, type Release } from '../knowledgeApi'
import { StatusBadge } from './StatusBadge'

export function ReleaseList({ kbId, releases, loading, error, onChanged, onRetry }: { kbId: string; releases: Release[]; loading: boolean; error: string | null; onChanged: () => void; onRetry: () => void }) {
  const [pendingId, setPendingId] = useState<string | null>(null)
  const [rowError, setRowError] = useState<Record<string, string>>({})

  const act = async (release: Release, action: 'rollback' | 'offline' | 'delete') => {
    if (action === 'delete' && !window.confirm('删除该索引发布？此操作无法撤销。')) return
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

  const statusLabel = (release: Release) => {
    if (release.isActive) return '当前检索'
    if (release.status === 'PUBLISHED') return '历史版本'
    return release.status
  }

  return (
    <section className="panel data-panel">
      <header className="panel-header"><div><h3>索引发布</h3><p>控制当前可用于检索的不可变索引版本。</p></div><span className="count-label">{releases.length}</span></header>
      {loading && <div className="table-skeleton" role="status" aria-label="正在加载索引发布"><i /><i /></div>}
      {!loading && error && <div className="state-block compact"><p role="alert">{error}</p><button className="secondary-button" onClick={onRetry}><ArrowsClockwise size={17} />重试</button></div>}
      {!loading && !error && releases.length === 0 && <div className="state-block compact"><h4>尚无索引发布</h4><p>文档处理完成后，点击「发布」将当前知识库固化为检索快照。</p></div>}
      {!loading && !error && releases.length > 0 && <div className="release-list">{releases.map((release) => {
        const pending = pendingId === release.releaseId
        return <article className="release-row" key={release.releaseId}><div className="release-version"><strong>v{release.versionNo}</strong><StatusBadge status={release.status} /></div><code>{release.indexName}</code><div className="release-summary"><small>{statusLabel(release)}</small><small>{release.documentCount} 份文档 / {release.chunkCount} chunks</small></div><div className="row-actions">{release.status === 'PUBLISHED' && release.isActive && <><button className="text-button" disabled={pending} onClick={() => void act(release, 'rollback')}><ArrowsClockwise size={16} />回滚</button><button className="text-button" disabled={pending} onClick={() => void act(release, 'offline')}><CloudSlash size={16} />离线</button></>}<button className="text-button danger" aria-label={`删除发布 v${release.versionNo}`} disabled={pending || release.isActive} title={release.isActive ? '当前检索版本，需先回滚或离线' : undefined} onClick={() => void act(release, 'delete')}><Trash size={16} />删除</button></div>{rowError[release.releaseId] && <p className="row-error" role="alert">{rowError[release.releaseId]}</p>}</article>
      })}</div>}
    </section>
  )
}
