import { useEffect, useState } from 'react'
import { configurationApi, type ProfileView, type VersionView as ProfileVersionView } from '../../configuration/configurationApi'
import { knowledgeApi, type KnowledgeBase } from '../../knowledge/knowledgeApi'
import { evaluationApi, type RunDetail, type RunView, type VersionView } from '../evaluationApi'

const fmtPct = (n: number) => `${(n * 100).toFixed(1)}%`

export function RunPanel({ datasetId, versions, onNotify }: {
  datasetId: string
  versions: VersionView[]
  onNotify: (type: 'success' | 'error', message: string) => void
}) {
  const [profiles, setProfiles] = useState<ProfileView[]>([])
  const [profileVersions, setProfileVersions] = useState<ProfileVersionView[]>([])
  const [bases, setBases] = useState<KnowledgeBase[]>([])
  const [datasetVersionNo, setDatasetVersionNo] = useState<number>(0)
  const [profileId, setProfileId] = useState<string>('')
  const [profileVersionNo, setProfileVersionNo] = useState<number>(0)
  const [kbIds, setKbIds] = useState<string[]>([])
  const [runs, setRuns] = useState<RunView[]>([])
  const [detail, setDetail] = useState<RunDetail | null>(null)
  const [starting, setStarting] = useState(false)

  useEffect(() => {
    let active = true
    Promise.all([configurationApi.list(), knowledgeApi.list()])
      .then(([p, b]) => {
        if (!active) return
        setProfiles(p)
        setBases(b)
        setKbIds(b.map((kb) => kb.id))
      })
      .catch(() => { if (active) onNotify('error', '配置或知识库加载失败') })
    return () => { active = false }
  }, [onNotify])

  useEffect(() => {
    let active = true
    evaluationApi.runs(datasetId)
      .then((next) => { if (active) setRuns(next) })
      .catch(() => { if (active) setRuns([]) })
    return () => { active = false }
  }, [datasetId, starting])

  const loadProfileVersions = (id: string) => {
    setProfileId(id)
    setProfileVersionNo(0)
    configurationApi.versions(id)
      .then((v) => setProfileVersions(v))
      .catch(() => setProfileVersions([]))
  }

  const start = async () => {
    if (!datasetId || !datasetVersionNo || !profileId || !profileVersionNo || kbIds.length === 0) return
    setStarting(true)
    try {
      await evaluationApi.startRun({
        datasetId,
        datasetVersionNo,
        profileId,
        profileVersionNo,
        knowledgeBaseIds: kbIds,
      })
      onNotify('success', '评测运行已提交')
      setStarting(false)
    } catch (e) {
      onNotify('error', e instanceof Error ? e.message : '评测失败')
      setStarting(false)
    }
  }

  const openDetail = async (id: string) => {
    try {
      setDetail(await evaluationApi.run(id))
    } catch (e) {
      onNotify('error', e instanceof Error ? e.message : '运行详情加载失败')
    }
  }

  return (
    <div className="panel run-panel">
      <div className="panel-header"><h2>评测运行</h2></div>
      <div className="run-form">
        <label>数据集版本
          <select value={datasetVersionNo} onChange={(e) => setDatasetVersionNo(Number(e.target.value))}>
            <option value={0}>选择版本</option>
            {versions.map((v) => <option key={v.id} value={v.versionNo}>v{v.versionNo}（{v.caseCount} 用例）</option>)}
          </select>
        </label>
        <label>配置 Profile
          <select value={profileId} onChange={(e) => loadProfileVersions(e.target.value)}>
            <option value="">选择配置</option>
            {profiles.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
          </select>
        </label>
        <label>Profile 版本
          <select value={profileVersionNo} onChange={(e) => setProfileVersionNo(Number(e.target.value))} disabled={!profileId}>
            <option value={0}>选择版本</option>
            {profileVersions.map((v) => <option key={v.id} value={v.versionNo}>v{v.versionNo}</option>)}
          </select>
        </label>
        <label>知识库范围（{kbIds.length} 个）
          <select multiple value={kbIds} onChange={(e) => setKbIds(Array.from(e.target.selectedOptions, (o) => o.value))}>
            {bases.map((b) => <option key={b.id} value={b.id}>{b.name}</option>)}
          </select>
        </label>
        <button className="primary-button" type="button" onClick={() => void start()} disabled={starting || !datasetVersionNo || !profileId || !profileVersionNo || kbIds.length === 0}>
          {starting ? '评测中' : '发起评测'}
        </button>
      </div>

      <div className="run-list">
        {runs.length === 0 && <div className="case-list-empty">尚无评测运行</div>}
        {runs.map((run) => (
          <button key={run.id} type="button" className="run-item" onClick={() => void openDetail(run.id)}>
            <span><strong>{run.status}</strong></span>
            <span className="run-metrics">
              {run.metrics ? `R@1 ${fmtPct(run.metrics.avgRecallAt1)} · MRR ${run.metrics.avgMrr.toFixed(3)} · Ref ${fmtPct(run.metrics.refusalMatchRate)}` : '—'}
            </span>
          </button>
        ))}
      </div>

      {detail && (
        <div className="run-detail">
          <div className="panel-header"><h3>运行详情（{detail.status}）</h3><button className="text-button" type="button" onClick={() => setDetail(null)}>关闭</button></div>
          {detail.metrics && (
            <div className="run-metrics-grid">
              <span>Recall@1 {fmtPct(detail.metrics.avgRecallAt1)}</span>
              <span>Recall@3 {fmtPct(detail.metrics.avgRecallAt3)}</span>
              <span>Recall@5 {fmtPct(detail.metrics.avgRecallAt5)}</span>
              <span>MRR {detail.metrics.avgMrr.toFixed(3)}</span>
              <span>NDCG@10 {detail.metrics.avgNdcgAt10.toFixed(3)}</span>
              <span>引用命中 {fmtPct(detail.metrics.citationHitRate)}</span>
              <span>拒答匹配 {fmtPct(detail.metrics.refusalMatchRate)}</span>
              <span>平均延迟 {detail.metrics.avgLatencyMs.toFixed(0)}ms</span>
            </div>
          )}
          <ul className="run-case-list">
            {detail.cases.map((c) => (
              <li key={c.position}>
                <span>#{c.position} {c.question}</span>
                <small>{c.expectedBehavior} → {c.actualBehavior} · R@1 {fmtPct(c.metrics.recallAt1)} · Ref {c.metrics.refusalMatch ? '✓' : '✗'}</small>
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  )
}
