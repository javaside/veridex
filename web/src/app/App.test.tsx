import { render, screen } from '@testing-library/react'
import { MemoryRouter, useLocation } from 'react-router-dom'
import { beforeEach, describe, expect, test, vi } from 'vitest'
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

const meJson = JSON.stringify({ id: '1', username: 'admin', displayName: '管理员', role: 'PLATFORM_ADMIN' })

beforeEach(() => {
  vi.stubGlobal('fetch', vi.fn((input: RequestInfo | URL) => {
    const url = String(input)
    if (url.includes('/api/auth/me')) {
      return Promise.resolve(new Response(meJson, { status: 200, headers: { 'Content-Type': 'application/json' } }))
    }
    return Promise.resolve(new Response('[]', { status: 200, headers: { 'Content-Type': 'application/json' } }))
  }))
})

describe('App routes', () => {
  test.each(workspaces.filter(([p]) => p !== '/knowledge'))(
    'renders the exact heading for %s',
    async (path, title) => {
      render(
        <MemoryRouter initialEntries={[path]}>
          <App />
        </MemoryRouter>,
      )

      expect(await screen.findByRole('heading', { name: title, level: 1 })).toBeInTheDocument()
    },
  )

  test('renders the knowledge workspace page for /knowledge', async () => {
    render(
      <MemoryRouter initialEntries={['/knowledge']}>
        <App />
      </MemoryRouter>,
    )

    expect(await screen.findByRole('heading', { name: '知识库', level: 2 })).toBeInTheDocument()
  })

  test('renders navigation links with their workspace hrefs', async () => {
    render(
      <MemoryRouter initialEntries={['/workbench']}>
        <App />
      </MemoryRouter>,
    )

    for (const [path, title] of workspaces) {
      expect(await screen.findByRole('link', { name: title })).toHaveAttribute('href', path)
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
