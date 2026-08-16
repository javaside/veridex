import { useRef } from 'react'
import { useModalFocus } from './useModalFocus'

export function ConfirmDialog({ open, title, description, confirmLabel, danger, confirmDisabled, cancelDisabled, onConfirm, onCancel }: {
  open: boolean
  title: string
  description: string
  confirmLabel: string
  danger?: boolean
  confirmDisabled?: boolean
  cancelDisabled?: boolean
  onConfirm: () => void
  onCancel: () => void
}) {
  const dialogRef = useRef<HTMLDivElement>(null)
  const confirmRef = useRef<HTMLButtonElement>(null)

  useModalFocus({
    active: open,
    containerRef: dialogRef,
    initialFocusRef: confirmRef,
    onEscape: onCancel,
    escapeDisabled: cancelDisabled,
  })

  if (!open) return null
  return (
    <div className="dialog-layer">
      <button className="dialog-backdrop" type="button" aria-label="关闭对话框" onClick={onCancel} disabled={cancelDisabled} />
      <div ref={dialogRef} className="confirm-dialog" role="dialog" aria-modal="true" aria-label={title} tabIndex={-1}>
        <h3>{title}</h3>
        <p>{description}</p>
        <div className="dialog-actions">
          <button className="secondary-button" type="button" onClick={onCancel} disabled={cancelDisabled}>取消</button>
          <button ref={confirmRef} className={danger ? 'danger-button' : 'primary-button'} type="button" onClick={onConfirm} disabled={confirmDisabled}>{confirmLabel}</button>
        </div>
      </div>
    </div>
  )
}
