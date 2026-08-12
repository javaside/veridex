import { Check, Square } from '@phosphor-icons/react'
import type { KnowledgeBase } from '../../knowledge/knowledgeApi'

export function KnowledgeBasePicker({ bases, selected, onToggle, onToggleAll }: {
  bases: KnowledgeBase[]
  selected: string[]
  onToggle: (id: string) => void
  onToggleAll: () => void
}) {
  const allSelected = bases.length > 0 && selected.length === bases.length
  return (
    <section className="qa-scope-panel" aria-label="知识库范围">
      <div className="qa-panel-heading">
        <span>知识库范围</span>
        <button className="text-button" type="button" onClick={onToggleAll}>
          {allSelected ? '取消全选' : '全选'}
        </button>
      </div>
      {bases.length === 0 ? (
        <p className="qa-empty-hint">暂无可用知识库，请联系管理员授权。</p>
      ) : (
        <ul className="qa-scope-list">
          {bases.map((base) => {
            const checked = selected.includes(base.id)
            return (
              <li key={base.id}>
                <button
                  type="button"
                  className="qa-scope-item"
                  aria-pressed={checked}
                  onClick={() => onToggle(base.id)}
                >
                  <span className="qa-check" aria-hidden="true">
                    {checked ? <Check size={13} weight="bold" /> : <Square size={13} />}
                  </span>
                  <span>{base.name}</span>
                </button>
              </li>
            )
          })}
        </ul>
      )}
    </section>
  )
}
