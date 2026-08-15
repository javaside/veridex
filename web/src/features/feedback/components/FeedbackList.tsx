import type { FeedbackView } from '../feedbackApi'

const REASON_LABELS: Record<string, string> = {
  WRONG_ANSWER: '回答错误',
  HALLUCINATION: '无依据 / 幻觉',
  MISSING_EVIDENCE: '证据不足',
  OUTDATED: '内容过时',
  WRONG_REFUSAL: '错误拒答',
  OTHER: '其他',
}

export function FeedbackList({ feedbacks, loading, error, onRetry, onConvert }: {
  feedbacks: FeedbackView[]
  loading: boolean
  error: string | null
  onRetry: () => void
  onConvert: (feedback: FeedbackView) => void
}) {
  if (loading) {
    return <div role="status" className="loading-copy" style={{ padding: '14px' }}>正在加载反馈</div>
  }
  if (error) {
    return <div className="state-block compact"><p role="alert">{error}</p><button className="secondary-button" type="button" onClick={onRetry}>重试</button></div>
  }
  if (feedbacks.length === 0) {
    return <div className="state-block compact"><h3>暂无点踩反馈</h3><p>员工点踩回答后会出现在这里，可转成评测坏例。</p></div>
  }

  return (
    <ul className="feedback-list">
      {feedbacks.map((feedback) => (
        <li key={feedback.id} className="feedback-item">
          <div className="feedback-item-head">
            <span className="feedback-reason">{REASON_LABELS[feedback.reasonCode ?? 'OTHER']}</span>
            <span className="feedback-meta">{feedback.createdAt}</span>
          </div>
          <p className="feedback-question">{feedback.question}</p>
          <p className="feedback-answer">{feedback.answer.length > 120 ? `${feedback.answer.slice(0, 120)}…` : feedback.answer}</p>
          <div className="feedback-item-foot">
            <span>{feedback.convertedCaseId ? '已转坏例' : '未转坏例'}</span>
            {!feedback.convertedCaseId && (
              <button className="secondary-button" type="button" onClick={() => onConvert(feedback)}>转坏例</button>
            )}
          </div>
        </li>
      ))}
    </ul>
  )
}
