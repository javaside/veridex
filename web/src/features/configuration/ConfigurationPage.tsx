import { FolderOpen, RocketLaunch } from '@phosphor-icons/react'
import { useCallback, useEffect, useState } from 'react'
import { PageHeader } from '../../app/PageHeader'
import { Toast } from '../../components/Toast'
import { configurationApi, defaultConfig, type ProfileConfig, type ProfileView, type VersionDetail, type VersionView } from './configurationApi'
import { ProfileEditor } from './components/ProfileEditor'
import { ProfileList } from './components/ProfileList'

export function ConfigurationPage() {
  const [profiles, setProfiles] = useState<ProfileView[]>([])
  const [selected, setSelected] = useState<ProfileView | null>(null)
  const [draft, setDraft] = useState<ProfileConfig | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [creating, setCreating] = useState(false)
  const [publishing, setPublishing] = useState(false)
  const [refreshKey, setRefreshKey] = useState(0)
  const [versions, setVersions] = useState<VersionView[]>([])
  const [versionDetail, setVersionDetail] = useState<VersionDetail | null>(null)
  const [toast, setToast] = useState<{ type: 'success' | 'error'; message: string } | null>(null)

  const loadProfiles = useCallback(() => {
    return configurationApi.list().then((next) => {
      setProfiles(next)
      setSelected((current) => next.find((p) => p.id === current?.id) ?? next[0] ?? null)
    })
  }, [])

  useEffect(() => {
    let active = true
    configurationApi.list()
      .then((next) => { if (active) { setProfiles(next); setSelected(next[0] ?? null) } })
      .catch((e) => { if (active) setError(e instanceof Error ? e.message : '配置加载失败') })
      .finally(() => { if (active) setLoading(false) })
    return () => { active = false }
  }, [])

  useEffect(() => {
    if (!selected) return
    let active = true
    configurationApi.get(selected.id)
      .then((d) => { if (active) setDraft(d.draft) })
      .catch(() => { if (active) setDraft(null) })
    return () => { active = false }
  }, [selected, refreshKey])

  useEffect(() => {
    if (!selected) return
    configurationApi.versions(selected.id).then(setVersions).catch(() => setVersions([]))
  }, [selected, refreshKey])

  const createProfile = async (name: string) => {
    setCreating(true)
    try {
      await configurationApi.create(name, null, defaultConfig())
      await loadProfiles()
      setRefreshKey((k) => k + 1)
    } catch (e) {
      setToast({ type: 'error', message: e instanceof Error ? e.message : '创建失败' })
    } finally {
      setCreating(false)
    }
  }

  const publish = async () => {
    if (!selected) return
    setPublishing(true)
    setToast(null)
    try {
      const result = await configurationApi.publish(selected.id)
      setToast({ type: 'success', message: `已发布版本 v${result.versionNo}` })
      setRefreshKey((k) => k + 1)
    } catch (e) {
      setToast({ type: 'error', message: e instanceof Error ? e.message : '发布失败' })
    } finally {
      setPublishing(false)
    }
  }

  const openVersion = async (versionNo: number) => {
    if (!selected) return
    try {
      setVersionDetail(await configurationApi.version(selected.id, versionNo))
    } catch (e) {
      setToast({ type: 'error', message: e instanceof Error ? e.message : '版本加载失败' })
    }
  }

  return (
    <section className="workspace-page configuration-page">
      <PageHeader title="配置版本" description="版本化 RAG 配置，供评测对比引用。" meta={`${profiles.length} 个配置`} />
      <div className="knowledge-layout">
        <ProfileList profiles={profiles} selectedId={selected?.id ?? null} loading={loading} error={error} creating={creating} onSelect={setSelected} onRetry={() => void loadProfiles()} onCreate={createProfile} />
        <div className="knowledge-workspace">
          {selected && draft ? (
            <>
              <header className="knowledge-workspace-header"><div><p className="section-kicker">当前配置</p><h2>{selected.name}</h2><p>{selected.description || '管理 RAG 五维配置并发布不可变版本。'}</p></div><div className="workspace-header-actions"><button className="primary-button" type="button" onClick={() => void publish()} disabled={publishing}><RocketLaunch size={18} aria-hidden="true" />{publishing ? '正在发布' : '发布版本'}</button></div></header>
              <ProfileEditor key={selected.id + ':' + refreshKey} profileId={selected.id} name={selected.name} description={selected.description} initial={draft} onSaved={() => setRefreshKey((k) => k + 1)} onNotify={(type, message) => setToast({ type, message })} />
              <div className="panel version-panel">
                <div className="panel-header"><h2>版本历史</h2></div>
                <ul className="version-list">
                  {versions.map((v) => <li key={v.id}><button type="button" onClick={() => void openVersion(v.versionNo)}><strong>v{v.versionNo}</strong><small>{v.createdAt}</small></button></li>)}
                  {versions.length === 0 && <li className="case-list-empty">尚未发布版本</li>}
                </ul>
              </div>
              {versionDetail && (
                <div className="panel version-detail">
                  <div className="panel-header"><h2>版本 v{versionDetail.versionNo}（已冻结）</h2><button className="text-button" type="button" onClick={() => setVersionDetail(null)}>关闭</button></div>
                  <pre className="config-preview">{JSON.stringify(versionDetail.config, null, 2)}</pre>
                </div>
              )}
            </>
          ) : (
            <div className="panel state-block workspace-empty"><FolderOpen size={34} aria-hidden="true" /><h2>选择一个配置</h2><p>从左侧选择配置，或创建第一个配置开始管理。</p></div>
          )}
        </div>
      </div>
      {toast && <Toast type={toast.type} message={toast.message} onDismiss={() => setToast(null)} />}
    </section>
  )
}
