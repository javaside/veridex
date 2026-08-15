import { ThumbsDown, ThumbsUp } from '@phosphor-icons/react'
import type { Citation } from '../qaApi'

/** 把文本中的 [n] 引用编号渲染为可点击 chip（点击由父组件处理，n 为 0 时不可点）。 */
export function ChatMessage({ role, content, citations, onCitationClick, onFeedback }: {
  role: 'USER' | 'ASSISTANT' | 'SYSTEM' | 'ERROR'
  content: string
  citations?: Citation[]
  onCitationClick?: (citation: Citation) => void
  onFeedback?: (rating: 'UP' | 'DOWN') => void
}) {
  if (role === 'USER') {
    return <div className="chat-message chat-user"><div className="chat-bubble">{content}</div></div>
  }
  if (role === 'SYSTEM' || role === 'ERROR') {
    return <div className={`chat-message chat-system${role === 'ERROR' ? ' chat-error' : ''}`}>{content}</div>
  }

  const parts = content.split(/(\[\d+\])/g)
  return (
    <div className="chat-message chat-assistant">
      <div className="chat-bubble">
        {parts.map((part, index) => {
          const match = /\[(\d+)\]/.exec(part)
          if (!match) return <span key={index}>{part}</span>
          const citation = citations?.find((c) => c.citationIndex === Number(match[1]))
          if (!citation) return <span key={index} className="citation-marker">{part}</span>
          return (
            <button
              key={index}
              type="button"
              className="citation-marker"
              title={citation.sourceLocation ?? '来源'}
              onClick={() => onCitationClick?.(citation)}
            >
              {part}
            </button>
          )
        })}
      </div>
      {citations && citations.length > 0 && (
        <div className="chat-citations" aria-label="引用来源">
          {citations.map((citation) => (
            <button
              key={citation.citationIndex}
              type="button"
              className="citation-chip"
              title="查看原文"
              onClick={() => onCitationClick?.(citation)}
            >
              [{citation.citationIndex}] {citation.sourceLocation ?? '来源'}
            </button>
          ))}
        </div>
      )}
      {onFeedback && (
        <div className="chat-feedback">
          <button type="button" aria-label="有帮助" onClick={() => onFeedback('UP')}><ThumbsUp size={15} aria-hidden="true" /></button>
          <button type="button" aria-label="有问题" onClick={() => onFeedback('DOWN')}><ThumbsDown size={15} aria-hidden="true" /></button>
        </div>
      )}
    </div>
  )
}
