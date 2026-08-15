import { useState } from 'react'
import type { ProfileView } from '../configurationApi'

export function ProfileList({ profiles, selectedId, loading, error, creating, onSelect, onRetry, onCreate }: {
  profiles: ProfileView[]
  selectedId: string | null
  loading: boolean
  error: string | null
  creating: boolean
  onSelect: (p: ProfileView) => void
  onRetry: () => void
  onCreate: (name: string) => Promise<void>
}) {
  const [name, setName] = useState('')

  const submit = async () => {
    if (!name.trim()) return
    await onCreate(name.trim())
    setName('')
  }

  return (
    <div className="panel knowledge-base-panel">
      <div className="panel-header"><h2>配置</h2></div>
      <form className="inline-create-form" onSubmit={(e) => { e.preventDefault(); void submit() }}>
        <input value={name} onChange={(e) => setName(e.target.value)} placeholder="配置名称" aria-label="配置名称" />
        <button className="primary-button" type="submit" disabled={creating || !name.trim()}>{creating ? '创建中' : '新建配置'}</button>
      </form>
      {loading && <div role="status" className="loading-copy" style={{ padding: '14px' }}>正在加载配置</div>}
      {error && <div className="state-block compact"><p role="alert">{error}</p><button className="secondary-button" type="button" onClick={onRetry}>重试</button></div>}
      <ul className="knowledge-base-list">
        {profiles.map((p) => (
          <li key={p.id}><button className={p.id === selectedId ? 'active' : ''} type="button" onClick={() => onSelect(p)}><span className="kb-icon">C</span><span><strong>{p.name}</strong><small>v{p.latestVersionNo ?? 0} · {p.versionCount} 版本</small></span></button></li>
        ))}
        {!loading && !error && profiles.length === 0 && <li className="state-block compact"><h3>创建第一个配置</h3><p>配置用于版本化 RAG 参数，供评测对比引用。</p></li>}
      </ul>
    </div>
  )
}
