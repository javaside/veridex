import { useCallback, useEffect, useState } from 'react'
import { PageHeader } from '../../app/PageHeader'
import { Toast } from '../../components/Toast'
import { feedbackApi, type FeedbackView } from './feedbackApi'
import { ConvertToCaseDialog } from './components/ConvertToCaseDialog'
import { FeedbackList } from './components/FeedbackList'

export function FeedbackPage() {
  const [feedbacks, setFeedbacks] = useState<FeedbackView[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [converting, setConverting] = useState<FeedbackView | null>(null)
  const [toast, setToast] = useState<{ type: 'success' | 'error'; message: string } | null>(null)

  const load = useCallback(() => {
    return feedbackApi.listDown().then((next) => setFeedbacks(next))
  }, [])

  useEffect(() => {
    let active = true
    feedbackApi.listDown()
      .then((next) => { if (active) setFeedbacks(next) })
      .catch((e) => { if (active) setError(e instanceof Error ? e.message : '反馈加载失败') })
      .finally(() => { if (active) setLoading(false) })
    return () => { active = false }
  }, [])

  return (
    <section className="workspace-page feedback-page">
      <PageHeader title="反馈管理" description="查看员工点踩反馈，并将坏例转成评测集用例。" meta={`${feedbacks.length} 条点踩反馈`} />
      <div className="panel">
        <div className="panel-header"><h2>反馈</h2></div>
        <FeedbackList
          feedbacks={feedbacks}
          loading={loading}
          error={error}
          onRetry={() => void load()}
          onConvert={setConverting}
        />
      </div>
      {converting && (
        <ConvertToCaseDialog
          feedback={converting}
          onDone={() => { setConverting(null); void load() }}
          onCancel={() => setConverting(null)}
          onNotify={(type, message) => setToast({ type, message })}
        />
      )}
      {toast && <Toast type={toast.type} message={toast.message} onDismiss={() => setToast(null)} />}
    </section>
  )
}
