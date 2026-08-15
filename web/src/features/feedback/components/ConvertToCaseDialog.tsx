import { useEffect, useState } from 'react'
import { evaluationApi, type DatasetView, type ExpectedBehavior } from '../../evaluation/evaluationApi'
import { feedbackApi, type FeedbackView } from '../feedbackApi'

export function ConvertToCaseDialog({ feedback, onDone, onCancel, onNotify }: {
  feedback: FeedbackView
  onDone: () => void
  onCancel: () => void
  onNotify: (type: 'success' | 'error', message: string) => void
}) {
  const [datasets, setDatasets] = useState<DatasetView[]>([])
  const [datasetId, setDatasetId] = useState<string>('')
  const [behavior, setBehavior] = useState<ExpectedBehavior>('REFUSE')
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let active = true
    evaluationApi.list()
      .then((next) => { if (active) { setDatasets(next); setDatasetId(next[0]?.id ?? '') } })
      .catch((e) => { if (active) setError(e instanceof Error ? e.message : '数据集加载失败') })
    return () => { active = false }
  }, [])

  const submit = async () => {
    if (!datasetId) return
    setSubmitting(true)
    setError(null)
    try {
      const created = await evaluationApi.addCase(datasetId, {
        question: feedback.question,
        expectedBehavior: behavior,
        expectedAnswer: null,
        evidence: behavior === 'ANSWER' ? feedback.evidence : [],
      })
      await feedbackApi.markConverted(feedback.id, created.id)
      onNotify('success', '已转成评测坏例')
      onDone()
    } catch (e) {
      setError(e instanceof Error ? e.message : '转坏例失败')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div className="dialog-layer">
      <button className="dialog-backdrop" type="button" aria-label="关闭对话框" onClick={onCancel} />
      <div className="confirm-dialog" role="dialog" aria-modal="true" aria-label="转成评测坏例">
        <h3>转成评测坏例</h3>
        <p className="dialog-description">{feedback.question}</p>
        <label className="dialog-field">
          目标数据集
          <select value={datasetId} onChange={(e) => setDatasetId(e.target.value)}>
            {datasets.map((d) => <option key={d.id} value={d.id}>{d.name}</option>)}
          </select>
        </label>
        <div className="behavior-toggle" role="radiogroup" aria-label="期望行为">
          <button type="button" className={behavior === 'ANSWER' ? 'active' : ''} onClick={() => setBehavior('ANSWER')}>ANSWER（有证据）</button>
          <button type="button" className={behavior === 'REFUSE' ? 'active' : ''} onClick={() => setBehavior('REFUSE')}>REFUSE（拒答）</button>
        </div>
        {behavior === 'ANSWER' && feedback.evidence.length === 0 && (
          <p className="dialog-warning">该反馈没有有效引用证据，ANSWER 用例证据为空将无法发布。</p>
        )}
        {error && <p className="row-error" role="alert">{error}</p>}
        <div className="dialog-actions">
          <button className="secondary-button" type="button" onClick={onCancel}>取消</button>
          <button className="primary-button" type="button" onClick={() => void submit()} disabled={submitting || !datasetId}>{submitting ? '转坏例中' : '确认转坏例'}</button>
        </div>
      </div>
    </div>
  )
}
