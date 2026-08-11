import { fireEvent, render, screen } from '@testing-library/react'
import { createRef } from 'react'
import { expect, test, vi } from 'vitest'
import { PreviewDrawer } from './PreviewDrawer'

test('keeps keyboard focus inside the preview and returns it after closing', () => {
  const onClose = vi.fn()
  const returnFocusRef = createRef<HTMLButtonElement>()
  const { rerender } = render(
    <>
      <button ref={returnFocusRef}>打开预览</button>
      <PreviewDrawer title="文档预览" content="解析内容" open onClose={onClose} returnFocusRef={returnFocusRef} />
    </>,
  )

  const closeButton = screen.getByRole('dialog', { name: '文档预览' }).querySelector<HTMLButtonElement>('button')!
  expect(closeButton).toHaveFocus()

  const previewContent = screen.getByText('解析内容')
  expect(previewContent).toHaveAttribute('tabindex', '0')
  previewContent.focus()
  fireEvent.keyDown(document, { key: 'Tab' })
  expect(closeButton).toHaveFocus()
  fireEvent.keyDown(document, { key: 'Tab', shiftKey: true })
  expect(previewContent).toHaveFocus()

  rerender(
    <>
      <button ref={returnFocusRef}>打开预览</button>
      <PreviewDrawer title="文档预览" content="解析内容" open={false} onClose={onClose} returnFocusRef={returnFocusRef} />
    </>,
  )
  expect(screen.getByRole('button', { name: '打开预览' })).toHaveFocus()
})
