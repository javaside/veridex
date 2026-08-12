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

  const dialog = screen.getByRole('dialog', { name: '文档预览' })
  const closeButton = dialog.querySelector<HTMLButtonElement>('button')!
  const previewContent = screen.getByText('解析内容')
  const backdrop = screen.getAllByRole('button', { name: '关闭预览' })[0]
  expect(backdrop).toHaveAttribute('tabindex', '-1')
  expect(closeButton).toHaveFocus()
  expect(previewContent).toHaveAttribute('tabindex', '0')

  fireEvent.keyDown(document, { key: 'Tab' })
  expect(previewContent).toHaveFocus()
  fireEvent.keyDown(document, { key: 'Tab' })
  expect(closeButton).toHaveFocus()
  fireEvent.keyDown(document, { key: 'Tab', shiftKey: true })
  expect(previewContent).toHaveFocus()
  fireEvent.keyDown(document, { key: 'Tab', shiftKey: true })
  expect(closeButton).toHaveFocus()

  rerender(
    <>
      <button ref={returnFocusRef}>打开预览</button>
      <PreviewDrawer title="文档预览" content="解析内容" open={false} onClose={onClose} returnFocusRef={returnFocusRef} />
    </>,
  )
  expect(screen.getByRole('button', { name: '打开预览' })).toHaveFocus()
})

test('shows a loading state instead of content while loading is pending', () => {
  const onClose = vi.fn()
  render(<PreviewDrawer title="文档预览" content="" loading open onClose={onClose} />)

  expect(screen.getByRole('dialog', { name: '文档预览' })).toBeInTheDocument()
  expect(screen.getByRole('status', { name: /正在加载解析内容/ })).toBeInTheDocument()
  expect(screen.queryByText('解析内容')).not.toBeInTheDocument()
})

test('renders content once loading completes', () => {
  const onClose = vi.fn()
  const { rerender } = render(<PreviewDrawer title="文档预览" content="" loading open onClose={onClose} />)

  rerender(<PreviewDrawer title="文档预览" content="解析内容" loading={false} open onClose={onClose} />)

  expect(screen.getByText('解析内容')).toBeInTheDocument()
  expect(screen.queryByRole('status', { name: /正在加载解析内容/ })).not.toBeInTheDocument()
})
