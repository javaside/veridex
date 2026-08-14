import { useState } from 'react'
import type { DatasetView } from '../evaluationApi'

export function DatasetList({ datasets, selectedId, loading, error, creating, onSelect, onRetry, onCreate }: { datasets: DatasetView[]; selectedId: string | null; loading: boolean; error: string | null; creating: boolean; onSelect: (d: DatasetView) => void; onRetry: () => void; onCreate: (name: string) => Promise<void> }) {
  const [name, setName] = useState('')

  const submit = async () => {
    if (!name.trim()) return
    await onCreate(name.trim())
    setName('')
  }

  return (
    <div className="panel knowledge-base-panel">
      <div className="panel-header"><h2>数据集</h2></div>
      <form className="inline-create-form" onSubmit={(e) => { e.preventDefault(); void submit() }}>
        <input value={name} onChange={(e) => setName(e.target.value)} placeholder="数据集名称" aria-label="数据集名称" />
        <button className="primary-button" type="submit" disabled={creating || !name.trim()}>{creating ? '创建中' : '新建数据集'}</button>
      </form>
      {loading && <div role="status" className="loading-copy" style={{ padding: '14px' }}>正在加载数据集</div>}
      {error && <div className="state-block compact"><p role="alert">{error}</p><button className="secondary-button" type="button" onClick={onRetry}>重试</button></div>}
      <ul className="knowledge-base-list">
        {datasets.map((d) => (
          <li key={d.id}><button className={d.id === selectedId ? 'active' : ''} type="button" onClick={() => onSelect(d)}><span className="kb-icon">E</span><span><strong>{d.name}</strong><small>{d.caseCount} 用例 · v{d.latestVersionNo ?? 0}</small></span></button></li>
        ))}
        {!loading && !error && datasets.length === 0 && <li className="state-block compact"><h3>创建第一个数据集</h3><p>评测集用于管理 ground-truth 用例并发布不可变版本。</p></li>}
      </ul>
    </div>
  )
}
