import { fireEvent, render, screen } from '@testing-library/react'
import { beforeEach, expect, test, vi } from 'vitest'
import { LoginPage } from './LoginPage'

beforeEach(() => vi.restoreAllMocks())

test('renders labeled fields and local account guidance', () => {
  render(<LoginPage />)

  expect(screen.getByRole('heading', { name: '欢迎回来' })).toBeInTheDocument()
  expect(screen.getByLabelText('用户名')).toHaveAttribute('autocomplete', 'username')
  expect(screen.getByLabelText('用户名')).toHaveAttribute('name', 'username')
  expect(screen.getByLabelText('密码')).toHaveAttribute('autocomplete', 'current-password')
  expect(screen.getByLabelText('密码')).toHaveAttribute('name', 'password')
  expect(screen.getByText(/admin \/ veridex/)).toBeInTheDocument()
})

test('disables submission while login is pending', async () => {
  vi.stubGlobal('fetch', vi.fn(() => new Promise(() => {})))
  render(<LoginPage />)

  fireEvent.change(screen.getByLabelText('用户名'), { target: { value: 'admin' } })
  fireEvent.change(screen.getByLabelText('密码'), { target: { value: 'veridex' } })
  fireEvent.click(screen.getByRole('button', { name: '登录' }))

  expect(await screen.findByRole('button', { name: '正在登录' })).toBeDisabled()
})

test('shows an accessible login error', async () => {
  vi.stubGlobal('fetch', vi.fn(() => Promise.resolve(new Response('', { status: 401 }))))
  render(<LoginPage />)

  fireEvent.click(screen.getByRole('button', { name: '登录' }))

  expect(await screen.findByRole('alert')).toHaveTextContent('登录失败')
})
