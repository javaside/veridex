import { Key, Prohibit } from '@phosphor-icons/react'
import type { ApiKeyView } from '../adminApi'

const formatDate = (iso: string | null) => iso ? iso.replace('T', ' ').slice(0, 19) : '从未'

export function KeyList({ keys, loading, error, onRetry, onRevoke }: {
  keys: ApiKeyView[]
  loading: boolean
  error: string | null
  onRetry: () => void
  onRevoke: (key: ApiKeyView) => void
}) {
  if (loading) {
    return <div role="status" className="loading-copy key-list-loading">正在加载 API key</div>
  }
  if (error) {
    return <div className="state-block compact"><p role="alert">{error}</p><button className="secondary-button" type="button" onClick={onRetry}>重试</button></div>
  }
  if (keys.length === 0) {
    return <div className="state-block compact"><Key size={30} aria-hidden="true" /><h3>暂无 API key</h3><p>创建 key 供脚本或外部系统集成访问。</p></div>
  }

  return (
    <ul className="key-list">
      {keys.map((key) => (
        <li key={key.id} className={`key-item${key.revokedAt ? ' is-revoked' : ''}`}>
          <div className="key-item-head">
            <div className="key-name"><Key size={18} aria-hidden="true" /><strong>{key.name}</strong></div>
            <span className={`key-state ${key.revokedAt ? 'revoked' : 'active'}`}>{key.revokedAt ? '已吊销' : '生效中'}</span>
          </div>
          <div className="key-identity"><code>{key.tokenPrefix}…</code><span>用户 {key.userId}</span></div>
          <dl className="key-meta">
            <div><dt>创建时间</dt><dd>{formatDate(key.createdAt)}</dd></div>
            <div><dt>最近使用</dt><dd>{formatDate(key.lastUsedAt)}</dd></div>
            {key.revokedAt && <div><dt>吊销时间</dt><dd>{formatDate(key.revokedAt)}</dd></div>}
          </dl>
          <div className="key-scopes" aria-label="作用域">{key.scopes.map((scope) => <code key={scope}>{scope}</code>)}</div>
          {!key.revokedAt && (
            <div className="key-item-foot">
              <button className="text-button danger" type="button" aria-label={`吊销 ${key.name}`} onClick={() => onRevoke(key)}><Prohibit size={16} aria-hidden="true" />吊销</button>
            </div>
          )}
        </li>
      ))}
    </ul>
  )
}
