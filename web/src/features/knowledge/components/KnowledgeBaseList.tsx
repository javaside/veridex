import { ArrowsClockwise, Database, Plus, X } from '@phosphor-icons/react'
import { useState } from 'react'
import type { KnowledgeBase } from '../knowledgeApi'

export function KnowledgeBaseList({ bases, selectedId, loading, error, creating, onSelect, onRetry, onCreate }: {
  bases: KnowledgeBase[]
  selectedId: string | null
  loading: boolean
  error: string | null
  creating: boolean
  onSelect: (base: KnowledgeBase) => void
  onRetry: () => void
  onCreate: (name: string) => Promise<void>
}) {
  const [showCreate, setShowCreate] = useState(false)
  const [name, setName] = useState('')
  const [createError, setCreateError] = useState<string | null>(null)

  const submit = async (event: React.FormEvent) => {
    event.preventDefault()
    if (!name.trim()) return
    setCreateError(null)
    try {
      await onCreate(name.trim())
      setName('')
      setShowCreate(false)
    } catch (caught) {
      setCreateError(caught instanceof Error ? caught.message : '知识库创建失败')
    }
  }

  return (
    <aside className="panel knowledge-base-panel" aria-label="知识库列表">
      <header className="panel-header">
        <div><h2>知识库</h2><p>{bases.length} 个可用空间</p></div>
        <button className="icon-button" type="button" aria-label={showCreate ? '取消新建知识库' : '新建知识库'} onClick={() => setShowCreate((value) => !value)}>{showCreate ? <X size={18} /> : <Plus size={18} />}</button>
      </header>
      {showCreate && (
        <form className="inline-create-form" onSubmit={submit}>
          <label>知识库名称<input autoFocus value={name} onChange={(event) => setName(event.target.value)} placeholder="例如：产品知识中心" /></label>
          {createError && <p className="inline-message error" role="alert">{createError}</p>}
          <button className="primary-button" type="submit" disabled={creating || !name.trim()}>{creating ? '正在创建' : '创建知识库'}</button>
        </form>
      )}
      {loading && <div className="list-skeleton" role="status" aria-label="正在加载知识库"><i /><i /><i /></div>}
      {!loading && error && <div className="state-block compact"><p role="alert">{error}</p><button className="secondary-button" onClick={onRetry}><ArrowsClockwise size={17} />重试</button></div>}
      {!loading && !error && bases.length === 0 && <div className="state-block compact"><Database size={28} aria-hidden="true" /><h3>创建第一个知识库</h3><p>集中管理文档、版本和检索发布。</p><button className="secondary-button" onClick={() => setShowCreate(true)}>新建知识库</button></div>}
      {!loading && !error && bases.length > 0 && (
        <ul className="knowledge-base-list">
          {bases.map((base) => <li key={base.id}><button className={selectedId === base.id ? 'active' : ''} onClick={() => onSelect(base)}><span className="kb-icon"><Database size={18} /></span><span><strong>{base.name}</strong><small>{base.description || '可用'}</small></span></button></li>)}
        </ul>
      )}
    </aside>
  )
}
