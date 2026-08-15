import { useEffect, useRef, type RefObject } from 'react'

const FOCUSABLE = 'button:not(:disabled), input:not(:disabled), select:not(:disabled), textarea:not(:disabled), [href], [tabindex]:not([tabindex="-1"])'

export function useModalFocus({
  active,
  containerRef,
  initialFocusRef,
  onEscape,
  escapeDisabled = false,
}: {
  active: boolean
  containerRef: RefObject<HTMLElement | null>
  initialFocusRef: RefObject<HTMLElement | null>
  onEscape?: () => void
  escapeDisabled?: boolean
}) {
  const previousFocusRef = useRef<HTMLElement | null>(null)
  const onEscapeRef = useRef(onEscape)
  const escapeDisabledRef = useRef(escapeDisabled)

  useEffect(() => {
    onEscapeRef.current = onEscape
    escapeDisabledRef.current = escapeDisabled
  }, [escapeDisabled, onEscape])

  useEffect(() => {
    if (!active) return
    previousFocusRef.current = document.activeElement instanceof HTMLElement ? document.activeElement : null

    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        if (!escapeDisabledRef.current && onEscapeRef.current) {
          event.preventDefault()
          onEscapeRef.current()
        }
        return
      }
      if (event.key !== 'Tab') return

      const focusable = Array.from(containerRef.current?.querySelectorAll<HTMLElement>(FOCUSABLE) ?? [])
      if (focusable.length === 0) {
        event.preventDefault()
        containerRef.current?.focus()
        return
      }

      event.preventDefault()
      const currentIndex = focusable.indexOf(document.activeElement as HTMLElement)
      const nextIndex = event.shiftKey
        ? (currentIndex <= 0 ? focusable.length - 1 : currentIndex - 1)
        : (currentIndex < 0 || currentIndex === focusable.length - 1 ? 0 : currentIndex + 1)
      focusable[nextIndex].focus()
    }

    document.addEventListener('keydown', handleKeyDown)
    initialFocusRef.current?.focus()
    return () => {
      document.removeEventListener('keydown', handleKeyDown)
      previousFocusRef.current?.focus()
      previousFocusRef.current = null
    }
  }, [active, containerRef, initialFocusRef])
}
