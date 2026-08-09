import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { expect, test } from 'vitest'
import { App } from './App'

test('renders the four platform workspaces', () => {
  render(
    <MemoryRouter initialEntries={['/workbench']}>
      <App />
    </MemoryRouter>,
  )

  expect(screen.getByRole('link', { name: '员工问答' })).toBeInTheDocument()
  expect(screen.getByRole('link', { name: '知识管理' })).toBeInTheDocument()
  expect(screen.getByRole('link', { name: '质量评测' })).toBeInTheDocument()
  expect(screen.getByRole('link', { name: '平台管理' })).toBeInTheDocument()
  expect(screen.getByRole('heading', { name: '员工问答' })).toBeInTheDocument()
})
