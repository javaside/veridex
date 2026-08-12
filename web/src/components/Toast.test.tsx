import { act, fireEvent, render, screen } from '@testing-library/react'
import { expect, test, vi } from 'vitest'
import { Toast } from './Toast'

test('renders message and dismisses on close button', () => {
  const onDismiss = vi.fn()
  render(<Toast type="success" message="已发布当前知识库" onDismiss={onDismiss} />)
  expect(screen.getByText('已发布当前知识库')).toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: '关闭通知' }))
  expect(onDismiss).toHaveBeenCalledTimes(1)
})

test('auto-dismisses after 3 seconds', () => {
  vi.useFakeTimers()
  const onDismiss = vi.fn()
  render(<Toast type="error" message="发布失败" onDismiss={onDismiss} />)
  act(() => { vi.advanceTimersByTime(3000) })
  expect(onDismiss).toHaveBeenCalledTimes(1)
  vi.useRealTimers()
})
