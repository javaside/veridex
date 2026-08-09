import { render, screen } from '@testing-library/react'
import { MemoryRouter, useLocation } from 'react-router-dom'
import { describe, expect, test } from 'vitest'
import { App } from './App'

const workspaces = [
  ['/workbench', '员工问答'],
  ['/knowledge', '知识管理'],
  ['/evaluation', '质量评测'],
  ['/admin', '平台管理'],
] as const

function LocationProbe() {
  return <output aria-label="当前路径">{useLocation().pathname}</output>
}

describe('App routes', () => {
  test.each(workspaces)('renders the exact heading for %s', (path, title) => {
    render(
      <MemoryRouter initialEntries={[path]}>
        <App />
      </MemoryRouter>,
    )

    expect(screen.getByRole('heading', { name: title, level: 1 })).toBeInTheDocument()
  })

  test('renders navigation links with their workspace hrefs', () => {
    render(
      <MemoryRouter initialEntries={['/workbench']}>
        <App />
      </MemoryRouter>,
    )

    for (const [path, title] of workspaces) {
      expect(screen.getByRole('link', { name: title })).toHaveAttribute('href', path)
    }
  })

  test('redirects an unknown path to /workbench', async () => {
    render(
      <MemoryRouter initialEntries={['/missing']}>
        <App />
        <LocationProbe />
      </MemoryRouter>,
    )

    expect(await screen.findByRole('heading', { name: '员工问答', level: 1 })).toBeInTheDocument()
    expect(screen.getByRole('status', { name: '当前路径' })).toHaveTextContent('/workbench')
  })
})
