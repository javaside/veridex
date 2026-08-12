import { X } from '@phosphor-icons/react'
import { useEffect, useRef } from 'react'

export function PreviewDrawer({ title, content, loading = false, open, onClose, returnFocusRef }: {
  title: string
  content: string
  loading?: boolean
  open: boolean
  onClose: () => void
  returnFocusRef?: React.RefObject<HTMLButtonElement | null>
}) {
  const closeRef = useRef<HTMLButtonElement>(null)
  const contentRef = useRef<HTMLPreElement>(null)

  useEffect(() => {
    if (!open) return
    const returnFocusNode = returnFocusRef?.current
    const handleKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose()
      if (event.key !== 'Tab') return
      event.preventDefault()
      if (document.activeElement === closeRef.current) contentRef.current?.focus()
      else closeRef.current?.focus()
    }
    document.addEventListener('keydown', handleKey)
    closeRef.current?.focus()
    return () => { document.removeEventListener('keydown', handleKey); returnFocusNode?.focus() }
  }, [open, onClose, returnFocusRef])

  if (!open) return null
  return (
    <div className="drawer-layer">
      <button className="drawer-backdrop" type="button" tabIndex={-1} onClick={onClose} aria-label="关闭预览" />
      <aside className="preview-drawer" role="dialog" aria-modal="true" aria-label={title}>
        <header><div><p className="section-kicker">解析结果</p><h2>{title}</h2></div><button ref={closeRef} className="icon-button" type="button" onClick={onClose} aria-label="关闭预览"><X size={20} /></button></header>
        {loading ? (
          <div className="preview-loading" role="status" aria-label="正在加载解析内容">
            <span className="preview-spinner" aria-hidden="true" />
            <p>正在加载解析内容…</p>
          </div>
        ) : (
          <pre ref={contentRef} tabIndex={0}>{content}</pre>
        )}
      </aside>
    </div>
  )
}
