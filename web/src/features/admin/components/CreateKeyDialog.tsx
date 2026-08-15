import { Check, Copy, Key } from '@phosphor-icons/react'
import { useRef, useState } from 'react'
import type { CurrentUser } from '../../auth/authApi'
import { useModalFocus } from '../../../components/useModalFocus'
import { adminApi, API_KEY_SCOPES, type ApiKeyScope } from '../adminApi'

async function copyText(value: string) {
  if (navigator.clipboard?.writeText) {
    await navigator.clipboard.writeText(value)
    return
  }
  const textarea = document.createElement('textarea')
  textarea.value = value
  textarea.setAttribute('readonly', '')
  textarea.style.position = 'fixed'
  textarea.style.opacity = '0'
  document.body.appendChild(textarea)
  textarea.select()
  const copied = document.execCommand('copy')
  textarea.remove()
  if (!copied) throw new Error('copy unavailable')
}

export function CreateKeyDialog({ user, onDone, onCancel, onNotify }: {
  user: CurrentUser
  onDone: () => void
  onCancel: () => void
  onNotify: (type: 'success' | 'error', message: string) => void
}) {
  const [name, setName] = useState('')
  const [targetUserId, setTargetUserId] = useState('')
  const [scopes, setScopes] = useState<ApiKeyScope[]>([])
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [createdToken, setCreatedToken] = useState<string | null>(null)
  const dialogRef = useRef<HTMLElement>(null)
  const nameRef = useRef<HTMLInputElement>(null)
  const closeRef = useRef<HTMLButtonElement>(null)

  useModalFocus({
    active: true,
    containerRef: dialogRef,
    initialFocusRef: createdToken ? closeRef : nameRef,
    onEscape: onCancel,
    escapeDisabled: submitting || createdToken !== null,
  })

  const normalizedTarget = targetUserId.trim()
  const ownerDescription = normalizedTarget
    ? `为目标用户 ${normalizedTarget} 签发凭据。`
    : '为当前管理员签发凭据。'

  const toggleScope = (scope: ApiKeyScope) => {
    setScopes((current) => current.includes(scope) ? current.filter((value) => value !== scope) : [...current, scope])
  }

  const submit = async (event: React.FormEvent) => {
    event.preventDefault()
    if (!name.trim() || scopes.length === 0 || submitting) return
    if (normalizedTarget && !/^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(normalizedTarget)) {
      setError('目标用户 ID 必须是有效的 UUID')
      return
    }
    setSubmitting(true)
    setError(null)
    try {
      const created = await adminApi.createKey(name.trim(), scopes, normalizedTarget || undefined)
      setCreatedToken(created.token)
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : '创建失败')
    } finally {
      setSubmitting(false)
    }
  }

  const copy = async () => {
    if (!createdToken) return
    try {
      await copyText(createdToken)
      onNotify('success', 'token 已复制')
    } catch {
      onNotify('error', '复制失败，请手动选择 token')
    }
  }

  if (createdToken) {
    return (
      <div className="dialog-layer">
        <div ref={dialogRef as React.RefObject<HTMLDivElement>} className="confirm-dialog create-key-dialog" role="dialog" aria-modal="true" aria-label="API key 已创建" tabIndex={-1}>
          <span className="dialog-key-icon success"><Check size={21} aria-hidden="true" /></span>
          <h3>API key 已创建</h3>
          <p className="dialog-description">完整 token 只显示这一次。关闭后无法再次查看，请立即复制并安全保存。</p>
          <div className="key-token-display"><code>{createdToken}</code></div>
          <div className="dialog-actions">
            <button className="secondary-button" type="button" onClick={() => void copy()}><Copy size={17} aria-hidden="true" />复制 token</button>
            <button ref={closeRef} className="primary-button" type="button" onClick={onDone}>我已保存，关闭</button>
          </div>
        </div>
      </div>
    )
  }

  return (
    <div className="dialog-layer">
      <button className="dialog-backdrop" type="button" aria-label="关闭对话框" onClick={onCancel} disabled={submitting} />
      <form ref={dialogRef as React.RefObject<HTMLFormElement>} className="confirm-dialog create-key-dialog" role="dialog" aria-modal="true" aria-label="创建 API key" tabIndex={-1} onSubmit={(event) => void submit(event)}>
        <span className="dialog-key-icon"><Key size={21} aria-hidden="true" /></span>
        <h3>创建 API key</h3>
        <p className="dialog-description">{ownerDescription}按最小权限原则选择所需作用域。</p>
        {error && <p className="row-error" role="alert">{error}</p>}
        <label className="dialog-field" htmlFor="api-key-name">名称</label>
        <input ref={nameRef} id="api-key-name" className="dialog-input" value={name} maxLength={200} disabled={submitting} onChange={(event) => setName(event.target.value)} placeholder="如：数据同步脚本" />
        {user.role === 'PLATFORM_ADMIN' && (
          <>
            <label className="dialog-field" htmlFor="api-key-user-id">目标用户 ID（可选）</label>
            <input id="api-key-user-id" className="dialog-input" value={targetUserId} disabled={submitting} onChange={(event) => setTargetUserId(event.target.value)} placeholder={`留空则为当前用户 ${user.id}`} />
          </>
        )}
        <fieldset className="key-scope-fieldset" disabled={submitting}>
          <legend>作用域（至少选一个）</legend>
          <div className="key-scope-list">
            {API_KEY_SCOPES.map(({ value, label }) => (
              <label key={value}>
                <input type="checkbox" checked={scopes.includes(value)} onChange={() => toggleScope(value)} />
                <span><strong>{value}</strong><small>{label}</small></span>
              </label>
            ))}
          </div>
        </fieldset>
        <div className="dialog-actions">
          <button className="secondary-button" type="button" onClick={onCancel} disabled={submitting}>取消</button>
          <button className="primary-button" type="submit" disabled={submitting || !name.trim() || scopes.length === 0}>{submitting ? '创建中' : '创建'}</button>
        </div>
      </form>
    </div>
  )
}
