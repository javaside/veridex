import { useCallback, useEffect, useState } from 'react'
import { evaluationApi, type CaseInput, type CaseView, type DatasetView, type ExpectedBehavior } from '../evaluationApi'
import { EvidencePicker } from './EvidencePicker'

type EditorState = {
  question: string
  expectedBehavior: ExpectedBehavior
  expectedAnswer: string
  evidence: CaseInput['evidence']
}

const emptyEditor = (): EditorState => ({ question: '', expectedBehavior: 'ANSWER', expectedAnswer: '', evidence: [] })

const totalChunks = (evidence: CaseInput['evidence']) => evidence.reduce((n, r) => n + r.chunkIndexes.length, 0)

const toEditor = (c: CaseView): EditorState => ({
  question: c.question,
  expectedBehavior: c.expectedBehavior,
  expectedAnswer: c.expectedAnswer ?? '',
  evidence: c.evidence,
})

function CaseEditor({ initial, datasetId, onSaved, onNotify }: {
  initial: CaseView | null
  datasetId: string
  onSaved: () => void
  onNotify: (type: 'success' | 'error', message: string) => void
}) {
  const [input, setInput] = useState<EditorState>(() => (initial ? toEditor(initial) : emptyEditor()))
  const [saving, setSaving] = useState(false)

  const save = async () => {
    setSaving(true)
    try {
      const payload: CaseInput = {
        question: input.question,
        expectedBehavior: input.expectedBehavior,
        expectedAnswer: input.expectedBehavior === 'ANSWER' ? input.expectedAnswer : null,
        evidence: input.expectedBehavior === 'ANSWER' ? input.evidence : [],
      }
      if (initial) {
        await evaluationApi.updateCase(datasetId, initial.id, payload)
      } else {
        await evaluationApi.addCase(datasetId, payload)
      }
      onNotify('success', initial ? '用例已更新' : '用例已创建')
      onSaved()
    } catch (e) {
      onNotify('error', e instanceof Error ? e.message : '保存失败')
    } finally {
      setSaving(false)
    }
  }

  const remove = async () => {
    if (!initial) return
    try {
      await evaluationApi.deleteCase(datasetId, initial.id)
      onNotify('success', '用例已删除')
      onSaved()
    } catch (e) {
      onNotify('error', e instanceof Error ? e.message : '删除失败')
    }
  }

  const setBehavior = (behavior: ExpectedBehavior) => setInput((i) => ({ ...i, expectedBehavior: behavior }))

  return (
    <div className="case-editor panel">
      <div className="panel-header"><h2>{initial ? '编辑用例' : '新增用例'}</h2></div>
      <div className="case-editor-body">
        <label>问题<textarea value={input.question} onChange={(e) => setInput((i) => ({ ...i, question: e.target.value }))} rows={2} /></label>
        <div className="behavior-toggle" role="radiogroup" aria-label="期望行为">
          <button type="button" className={input.expectedBehavior === 'ANSWER' ? 'active' : ''} onClick={() => setBehavior('ANSWER')}>ANSWER（有证据）</button>
          <button type="button" className={input.expectedBehavior === 'REFUSE' ? 'active' : ''} onClick={() => setBehavior('REFUSE')}>REFUSE（拒答）</button>
        </div>
        {input.expectedBehavior === 'ANSWER' && (
          <>
            <label>期望答案<textarea value={input.expectedAnswer} onChange={(e) => setInput((i) => ({ ...i, expectedAnswer: e.target.value }))} rows={3} /></label>
            <div className="evidence-section"><strong>Ground-truth 证据</strong><EvidencePicker initial={input.evidence} onSelect={(evidence) => setInput((i) => ({ ...i, evidence }))} /><div className="evidence-summary">{totalChunks(input.evidence) > 0 ? `已选 ${totalChunks(input.evidence)} 个分块` : '尚未选择证据'}</div></div>
          </>
        )}
        <div className="case-editor-actions">
          <button className="primary-button" type="button" onClick={() => void save()} disabled={saving}>{saving ? '保存中' : '保存用例'}</button>
          {initial && <button className="text-button danger" type="button" onClick={() => void remove()}>删除用例</button>}
        </div>
      </div>
    </div>
  )
}

export function CaseWorkspace({ dataset, refreshKey, onChanged, onNotify }: { dataset: DatasetView; refreshKey: number; onChanged: () => void; onNotify: (type: 'success' | 'error', message: string) => void }) {
  const [cases, setCases] = useState<CaseView[]>([])
  const [selectedId, setSelectedId] = useState<string | null>(null)

  const load = useCallback(() => {
    return evaluationApi.cases(dataset.id).then((next) => {
      setCases(next)
      setSelectedId((current) => next.find((c) => c.id === current)?.id ?? next[0]?.id ?? null)
    })
  }, [dataset.id])

  useEffect(() => {
    let active = true
    evaluationApi.cases(dataset.id)
      .then((next) => {
        if (!active) return
        setCases(next)
        setSelectedId((current) => next.find((c) => c.id === current)?.id ?? next[0]?.id ?? null)
      })
    return () => { active = false }
  }, [dataset.id, refreshKey])

  const selected = cases.find((c) => c.id === selectedId) ?? null

  return (
    <div className="case-workspace">
      <div className="case-list-panel panel">
        <div className="panel-header"><div><h2>用例工作集</h2><p>草稿中的用例，发布时冻结为版本。</p></div><button className="text-button" type="button" onClick={() => setSelectedId(null)}>新增用例</button></div>
        <ul className="case-list">
          {cases.map((c) => (
            <li key={c.id}><button className={c.id === selectedId ? 'active' : ''} type="button" onClick={() => setSelectedId(c.id)}><strong>{c.question.slice(0, 40)}{c.question.length > 40 ? '…' : ''}</strong><small>{c.expectedBehavior}</small></button></li>
          ))}
          {cases.length === 0 && <li className="case-list-empty">还没有用例</li>}
        </ul>
      </div>
      <CaseEditor
        key={selected?.id ?? 'new'}
        initial={selected}
        datasetId={dataset.id}
        onSaved={() => { void load(); onChanged() }}
        onNotify={onNotify}
      />
    </div>
  )
}
