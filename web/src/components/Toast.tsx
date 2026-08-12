import { CheckCircle, X, XCircle } from '@phosphor-icons/react'
import { useEffect } from 'react'

export function Toast({ type, message, onDismiss }: { type: 'success' | 'error'; message: string; onDismiss: () => void }) {
  useEffect(() => {
    const timer = setTimeout(onDismiss, 3000)
    return () => clearTimeout(timer)
  }, [onDismiss])

  return (
    <div className={`toast toast-${type}`} role="status">
      {type === 'success' ? <CheckCircle size={18} aria-hidden="true" /> : <XCircle size={18} aria-hidden="true" />}
      <span>{message}</span>
      <button type="button" aria-label="关闭通知" onClick={onDismiss}><X size={16} aria-hidden="true" /></button>
    </div>
  )
}
