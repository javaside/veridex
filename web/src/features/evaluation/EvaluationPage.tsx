import { FolderOpen, RocketLaunch } from '@phosphor-icons/react'
import { useCallback, useEffect, useState } from 'react'
import { PageHeader } from '../../app/PageHeader'
import { Toast } from '../../components/Toast'
import { evaluationApi, type DatasetView, type VersionDetail, type VersionView } from './evaluationApi'
import { CaseWorkspace } from './components/CaseWorkspace'
import { DatasetList } from './components/DatasetList'
import { RunPanel } from './components/RunPanel'

export function EvaluationPage() {
  const [datasets, setDatasets] = useState<DatasetView[]>([])
  const [selected, setSelected] = useState<DatasetView | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [creating, setCreating] = useState(false)
  const [publishing, setPublishing] = useState(false)
  const [refreshKey, setRefreshKey] = useState(0)
  const [versions, setVersions] = useState<VersionView[]>([])
  const [versionDetail, setVersionDetail] = useState<VersionDetail | null>(null)
  const [toast, setToast] = useState<{ type: 'success' | 'error'; message: string } | null>(null)

  const loadDatasets = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const next = await evaluationApi.list()
      setDatasets(next)
      setSelected((current) => next.find((d) => d.id === current?.id) ?? next[0] ?? null)
    } catch (e) {
      setError(e instanceof Error ? e.message : '数据集加载失败')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    let active = true
    evaluationApi.list()
      .then((next) => {
        if (!active) return
        setDatasets(next)
        setSelected((current) => next.find((d) => d.id === current?.id) ?? next[0] ?? null)
      })
      .catch((caught) => {
        if (active) setError(caught instanceof Error ? caught.message : '数据集加载失败')
      })
      .finally(() => {
        if (active) setLoading(false)
      })
    return () => { active = false }
  }, [])

  useEffect(() => {
    if (!selected) return
    evaluationApi.versions(selected.id).then(setVersions).catch(() => setVersions([]))
  }, [selected, refreshKey])

  const createDataset = async (name: string) => {
    setCreating(true)
    try {
      const created = await evaluationApi.create(name)
      setDatasets((current) => [created, ...current])
      setSelected(created)
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
      const result = await evaluationApi.publish(selected.id)
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
      setVersionDetail(await evaluationApi.version(selected.id, versionNo))
    } catch (e) {
      setToast({ type: 'error', message: e instanceof Error ? e.message : '版本加载失败' })
    }
  }

  return (
    <section className="workspace-page evaluation-page">
      <PageHeader title="质量评测" description="管理版本化评测集与 ground-truth 用例。" meta={`${datasets.length} 个数据集`} />
      <div className="knowledge-layout">
        <DatasetList datasets={datasets} selectedId={selected?.id ?? null} loading={loading} error={error} creating={creating} onSelect={setSelected} onRetry={() => void loadDatasets()} onCreate={createDataset} />
        <div className="knowledge-workspace">
          {selected ? (
            <>
              <header className="knowledge-workspace-header"><div><p className="section-kicker">当前数据集</p><h2>{selected.name}</h2><p>{selected.description || '管理用例并发布不可变版本。'}</p></div><div className="workspace-header-actions"><button className="primary-button" type="button" onClick={() => void publish()} disabled={publishing}><RocketLaunch size={18} aria-hidden="true" />{publishing ? '正在发布' : '发布版本'}</button></div></header>
              <CaseWorkspace dataset={selected} refreshKey={refreshKey} onChanged={() => setRefreshKey((k) => k + 1)} onNotify={(type, message) => setToast({ type, message })} />
              <div className="panel version-panel">
                <div className="panel-header"><h2>版本历史</h2></div>
                <ul className="version-list">
                  {versions.map((v) => <li key={v.id}><button type="button" onClick={() => void openVersion(v.versionNo)}><strong>v{v.versionNo}</strong><small>{v.caseCount} 用例 · {v.createdAt}</small></button></li>)}
                  {versions.length === 0 && <li className="case-list-empty">尚未发布版本</li>}
                </ul>
              </div>
              {versionDetail && (
                <div className="panel version-detail">
                  <div className="panel-header"><h2>版本 v{versionDetail.versionNo}（已冻结）</h2><button className="text-button" type="button" onClick={() => setVersionDetail(null)}>关闭</button></div>
                  <ol>{versionDetail.cases.map((c) => <li key={c.position}><strong>{c.question}</strong><small>{c.expectedBehavior}</small></li>)}</ol>
                </div>
              )}
              <RunPanel datasetId={selected.id} versions={versions} onNotify={(type, message) => setToast({ type, message })} />
            </>
          ) : (
            <div className="panel state-block workspace-empty"><FolderOpen size={34} aria-hidden="true" /><h2>选择一个数据集</h2><p>从左侧选择数据集，或创建第一个数据集开始管理用例。</p></div>
          )}
        </div>
      </div>
      {toast && <Toast type={toast.type} message={toast.message} onDismiss={() => setToast(null)} />}
    </section>
  )
}
