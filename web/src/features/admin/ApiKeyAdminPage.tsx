import { Key, Plus } from '@phosphor-icons/react'
import { useCallback, useEffect, useState } from 'react'
import { PageHeader } from '../../app/PageHeader'
import type { CurrentUser } from '../auth/authApi'
import { ConfirmDialog } from '../../components/ConfirmDialog'
import { Toast } from '../../components/Toast'
import { adminApi, type ApiKeyView } from './adminApi'
import { CreateKeyDialog } from './components/CreateKeyDialog'
import { KeyList } from './components/KeyList'

export function ApiKeyAdminPage({ user }: { user: CurrentUser }) {
  const [keys, setKeys] = useState<ApiKeyView[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [creating, setCreating] = useState(false)
  const [revokeTarget, setRevokeTarget] = useState<ApiKeyView | null>(null)
  const [revokeLoading, setRevokeLoading] = useState(false)
  const [toast, setToast] = useState<{ type: 'success' | 'error'; message: string } | null>(null)

  const load = useCallback(async () => {
    const next = await adminApi.listKeys()
    setKeys(next)
  }, [])

  const refresh = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      await load()
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : 'API key 加载失败')
    } finally {
      setLoading(false)
    }
  }, [load])

  useEffect(() => {
    let active = true
    adminApi.listKeys()
      .then((next) => { if (active) setKeys(next) })
      .catch((caught) => { if (active) setError(caught instanceof Error ? caught.message : 'API key 加载失败') })
      .finally(() => { if (active) setLoading(false) })
    return () => { active = false }
  }, [])

  const revoke = async () => {
    if (!revokeTarget || revokeLoading) return
    const target = revokeTarget
    setRevokeLoading(true)
    try {
      await adminApi.revokeKey(target.id)
    } catch (caught) {
      setRevokeTarget(null)
      setToast({ type: 'error', message: caught instanceof Error ? caught.message : '吊销失败' })
      setRevokeLoading(false)
      return
    }

    setKeys((current) => current.map((key) => key.id === target.id ? { ...key, revokedAt: new Date().toISOString() } : key))
    setRevokeTarget(null)
    try {
      await load()
      setToast({ type: 'success', message: 'API key 已吊销' })
    } catch (caught) {
      const message = caught instanceof Error ? caught.message : '列表刷新失败'
      setToast({ type: 'error', message: `API key 已吊销，但刷新列表失败：${message}` })
    } finally {
      setRevokeLoading(false)
    }
  }

  return (
    <section className="workspace-page api-key-admin-page">
      <PageHeader
        title="API key 管理"
        description="为脚本与外部系统签发可吊销的 scoped 访问凭据。"
        meta={`${keys.filter((key) => !key.revokedAt).length} 个生效中`}
        actions={<button className="primary-button" type="button" onClick={() => setCreating(true)}><Plus size={18} aria-hidden="true" />创建 API key</button>}
      />
      <div className="panel key-management-panel">
        <div className="panel-header">
          <div><h2>访问凭据</h2><p>列表不会返回完整 token；已吊销凭据保留用于审计。</p></div>
          <Key size={20} aria-hidden="true" />
        </div>
        <KeyList keys={keys} loading={loading} error={error} onRetry={() => void refresh()} onRevoke={setRevokeTarget} />
      </div>
      {creating && (
        <CreateKeyDialog
          user={user}
          onCancel={() => setCreating(false)}
          onDone={() => { setCreating(false); void refresh(); setToast({ type: 'success', message: 'API key 已创建' }) }}
          onNotify={(type, message) => setToast({ type, message })}
        />
      )}
      <ConfirmDialog
        open={revokeTarget !== null}
        title="吊销 API key"
        description={`确定吊销「${revokeTarget?.name ?? ''}」（${revokeTarget?.tokenPrefix ?? ''}…）？吊销后立即失效，无法恢复。`}
        confirmLabel={revokeLoading ? '正在吊销' : '确认吊销'}
        danger
        confirmDisabled={revokeLoading}
        cancelDisabled={revokeLoading}
        onConfirm={() => void revoke()}
        onCancel={() => { if (!revokeLoading) setRevokeTarget(null) }}
      />
      {toast && <Toast type={toast.type} message={toast.message} onDismiss={() => setToast(null)} />}
    </section>
  )
}
