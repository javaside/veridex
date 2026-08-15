import { useEffect, useState } from 'react'
import { configurationApi, type ProfileView, type VersionView as ProfileVersionView } from '../../configuration/configurationApi'
import { knowledgeApi, type KnowledgeBase } from '../../knowledge/knowledgeApi'
import { evaluationApi, type ComparisonResult, type RunDetail, type RunView, type VersionView } from '../evaluationApi'

const fmtPct = (n: number) => `${(n * 100).toFixed(1)}%`
const fmtDelta = (n: number) => `${n >= 0 ? '+' : ''}${(n * 100).toFixed(1)}pp`

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
  const [expandedId, setExpandedId] = useState<string | null>(null)
  const [detail, setDetail] = useState<RunDetail | null>(null)
  const [loadingDetail, setLoadingDetail] = useState(false)
  const [starting, setStarting] = useState(false)
  const [baselineId, setBaselineId] = useState<string>('')
  const [candidateId, setCandidateId] = useState<string>('')
  const [comparison, setComparison] = useState<ComparisonResult | null>(null)
  const [comparing, setComparing] = useState(false)

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

  const toggleDetail = async (id: string) => {
    if (expandedId === id) {
      setExpandedId(null)
      return
    }
    setExpandedId(id)
    setDetail(null)
    setLoadingDetail(true)
    try {
      setDetail(await evaluationApi.run(id))
    } catch (e) {
      setExpandedId(null)
      onNotify('error', e instanceof Error ? e.message : '运行详情加载失败')
    } finally {
      setLoadingDetail(false)
    }
  }

  const completedRuns = runs.filter((r) => r.status === 'COMPLETED')

  const compare = async () => {
    if (!baselineId || !candidateId || baselineId === candidateId) return
    setComparing(true)
    try {
      setComparison(await evaluationApi.compareRuns(baselineId, candidateId))
    } catch (e) {
      onNotify('error', e instanceof Error ? e.message : '对比失败')
    } finally {
      setComparing(false)
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
          <div key={run.id} className="run-entry">
            <button type="button" className="run-item" aria-expanded={expandedId === run.id} onClick={() => void toggleDetail(run.id)}>
              <span><strong>{run.status}</strong></span>
              <span className="run-metrics">
                {run.metrics ? `R@1 ${fmtPct(run.metrics.avgRecallAt1)} · MRR ${run.metrics.avgMrr.toFixed(3)} · Ref ${fmtPct(run.metrics.refusalMatchRate)}` : '—'}
              </span>
            </button>
            {expandedId === run.id && (
              <div className="run-detail">
                <div className="run-detail-header"><h3>运行详情（{detail?.status ?? run.status}）</h3><button className="text-button" type="button" onClick={() => setExpandedId(null)}>关闭</button></div>
                {loadingDetail ? (
                  <div className="case-list-empty">加载中…</div>
                ) : detail ? (
                  <>
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
                  </>
                ) : null}
              </div>
            )}
          </div>
        ))}
      </div>

      <div className="comparison-section">
        <div className="panel-header"><h3>版本对比与回归门禁</h3></div>
        <div className="comparison-form">
          <label>基线运行
            <select value={baselineId} onChange={(e) => setBaselineId(e.target.value)}>
              <option value="">选择基线运行</option>
              {completedRuns.map((r) => <option key={r.id} value={r.id}>{r.status} · {r.createdAt}</option>)}
            </select>
          </label>
          <label>候选运行
            <select value={candidateId} onChange={(e) => setCandidateId(e.target.value)}>
              <option value="">选择候选运行</option>
              {completedRuns.map((r) => <option key={r.id} value={r.id}>{r.status} · {r.createdAt}</option>)}
            </select>
          </label>
          <button className="primary-button" type="button" onClick={() => void compare()} disabled={comparing || !baselineId || !candidateId || baselineId === candidateId}>
            {comparing ? '对比中' : '对比'}
          </button>
        </div>

        {comparison && (
          <div className="comparison-result">
            <div className={`gate-verdict gate-${comparison.verdict.toLowerCase()}`}>
              门禁判定：{comparison.verdict}
            </div>
            {comparison.metrics.length > 0 && (
              <table className="comparison-table">
                <thead><tr><th>指标</th><th>基线</th><th>候选</th><th>变化</th></tr></thead>
                <tbody>
                  {comparison.metrics.map((m) => (
                    <tr key={m.name}>
                      <td>{m.name}</td>
                      <td>{m.name === 'Avg latency (ms)' ? `${m.baseline.toFixed(0)}ms` : fmtPct(m.baseline)}</td>
                      <td>{m.name === 'Avg latency (ms)' ? `${m.candidate.toFixed(0)}ms` : fmtPct(m.candidate)}</td>
                      <td className={m.delta < 0 ? 'delta-negative' : 'delta-positive'}>
                        {m.name === 'Avg latency (ms)'
                          ? `${m.delta >= 0 ? '+' : ''}${m.delta.toFixed(0)}ms`
                          : fmtDelta(m.delta)}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
            {comparison.failedChecks.length > 0 && (
              <ul className="failed-checks">
                {comparison.failedChecks.map((c) => (
                  <li key={c.metric}>{c.metric}：{fmtPct(c.candidate)} 低于允许下限 {fmtPct(c.maxAllowed)}</li>
                ))}
              </ul>
            )}
          </div>
        )}
      </div>
    </div>
  )
}
