import { fireEvent, render, screen, waitFor } from '@testing-library/react'
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
      expect(screen.getAllByText(path === '/workbench' ? 'Phase 3' : path === '/evaluation' ? 'Phase 4' : 'Phase 5')).not.toHaveLength(0)
      expect(screen.getByRole('link', { name: '前往知识管理' })).toHaveAttribute('href', '/knowledge')
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

  test('renders authenticated identity and logs out to the login page', async () => {
    const fetchMock = vi.mocked(fetch)
    fetchMock.mockImplementation((input: RequestInfo | URL) => {
      const url = String(input)
      if (url.includes('/api/auth/me')) {
        return Promise.resolve(new Response(meJson, { status: 200, headers: { 'Content-Type': 'application/json' } }))
      }
      if (url.includes('/api/auth/logout')) return Promise.resolve(new Response(null, { status: 204 }))
      return Promise.resolve(new Response('[]', { status: 200, headers: { 'Content-Type': 'application/json' } }))
    })

    render(<MemoryRouter initialEntries={['/workbench']}><App /></MemoryRouter>)

    expect(await screen.findByText('管理员')).toBeInTheDocument()
    expect(screen.getByText('PLATFORM_ADMIN')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '退出登录' }))
    expect(await screen.findByRole('heading', { name: '欢迎回来' })).toBeInTheDocument()
  })

  test('disables logout while pending and reports failures without ending the session', async () => {
    let resolveLogout: ((response: Response) => void) | undefined
    const fetchMock = vi.mocked(fetch)
    fetchMock.mockImplementation((input: RequestInfo | URL) => {
      const url = String(input)
      if (url.includes('/api/auth/me')) {
        return Promise.resolve(new Response(meJson, { status: 200, headers: { 'Content-Type': 'application/json' } }))
      }
      if (url.includes('/api/auth/logout')) {
        return new Promise((resolve) => { resolveLogout = resolve })
      }
      return Promise.resolve(new Response('[]', { status: 200, headers: { 'Content-Type': 'application/json' } }))
    })

    render(<MemoryRouter initialEntries={['/workbench']}><App /></MemoryRouter>)

    fireEvent.click(await screen.findByRole('button', { name: '退出登录' }))
    expect(screen.getByRole('button', { name: '正在退出' })).toBeDisabled()

    resolveLogout?.(new Response('', { status: 500 }))
    expect(await screen.findByRole('alert')).toHaveTextContent('退出失败')
    await waitFor(() => expect(screen.getByRole('button', { name: '退出登录' })).toBeEnabled())
    expect(screen.getByRole('heading', { name: '员工问答', level: 1 })).toBeInTheDocument()
  })
})
