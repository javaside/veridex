import { fireEvent, render, screen } from '@testing-library/react'
import { useState } from 'react'
import { expect, test, vi } from 'vitest'
import { ConfirmDialog } from './ConfirmDialog'

test('renders content and confirms on button click', () => {
  const onConfirm = vi.fn()
  const onCancel = vi.fn()
  render(<ConfirmDialog open title="删除索引发布" description="此操作无法撤销" confirmLabel="删除" danger onConfirm={onConfirm} onCancel={onCancel} />)

  expect(screen.getByRole('heading', { name: '删除索引发布' })).toBeInTheDocument()
  expect(screen.getByText('此操作无法撤销')).toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: '删除' }))
  expect(onConfirm).toHaveBeenCalledTimes(1)
  expect(onCancel).not.toHaveBeenCalled()
})

test('cancels on escape and backdrop', () => {
  const onConfirm = vi.fn()
  const onCancel = vi.fn()
  render(<ConfirmDialog open title="确认" description="d" confirmLabel="确定" onConfirm={onConfirm} onCancel={onCancel} />)

  fireEvent.keyDown(document, { key: 'Escape' })
  expect(onCancel).toHaveBeenCalledTimes(1)
  fireEvent.click(screen.getByLabelText('关闭对话框'))
  expect(onCancel).toHaveBeenCalledTimes(2)
  expect(onConfirm).not.toHaveBeenCalled()
})

test('traps tab focus inside and restores focus to the trigger after close', () => {
  function Harness() {
    const [open, setOpen] = useState(false)
    return <><button type="button" onClick={() => setOpen(true)}>打开</button><ConfirmDialog open={open} title="确认" description="d" confirmLabel="确定" onConfirm={() => {}} onCancel={() => setOpen(false)} /></>
  }
  render(<Harness />)
  const trigger = screen.getByRole('button', { name: '打开' })
  trigger.focus()
  fireEvent.click(trigger)

  const confirm = screen.getByRole('button', { name: '确定' })
  const cancel = screen.getByRole('button', { name: '取消' })
  expect(confirm).toHaveFocus()

  fireEvent.keyDown(document, { key: 'Tab' })
  expect(cancel).toHaveFocus()
  fireEvent.keyDown(document, { key: 'Tab', shiftKey: true })
  expect(confirm).toHaveFocus()

  fireEvent.keyDown(document, { key: 'Escape' })
  expect(trigger).toHaveFocus()
})

test('does not close from escape, backdrop, or cancel while cancellation is disabled', () => {
  const onCancel = vi.fn()
  render(<ConfirmDialog open title="确认" description="d" confirmLabel="处理中" confirmDisabled cancelDisabled onConfirm={() => {}} onCancel={onCancel} />)

  fireEvent.keyDown(document, { key: 'Escape' })
  fireEvent.click(screen.getByLabelText('关闭对话框'))
  fireEvent.click(screen.getByRole('button', { name: '取消' }))

  expect(onCancel).not.toHaveBeenCalled()
})

test('renders nothing when closed', () => {
  render(<ConfirmDialog open={false} title="确认" description="d" confirmLabel="确定" onConfirm={() => {}} onCancel={() => {}} />)
  expect(screen.queryByRole('heading', { name: '确认' })).not.toBeInTheDocument()
})
