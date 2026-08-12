import { fireEvent, render, screen } from '@testing-library/react'
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

test('renders nothing when closed', () => {
  render(<ConfirmDialog open={false} title="确认" description="d" confirmLabel="确定" onConfirm={() => {}} onCancel={() => {}} />)
  expect(screen.queryByRole('heading', { name: '确认' })).not.toBeInTheDocument()
})
