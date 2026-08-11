import { SignOut } from '@phosphor-icons/react'
import type { ReactNode } from 'react'
import { NavLink } from 'react-router-dom'
import type { CurrentUser } from '../features/auth/authApi'
import { workspaceRoutes } from './routes'

export function AppShell({ user, onLogout, children }: { user: CurrentUser; onLogout: () => void; children: ReactNode }) {
  const initials = user.displayName.slice(0, 2).toUpperCase()

  return (
    <div className="app-shell">
      <aside className="app-sidebar">
        <div className="sidebar-brand">
          <span className="brand-mark" aria-hidden="true">V</span>
          <div><strong>Veridex</strong><small>本地开发环境</small></div>
        </div>
        <nav className="workspace-nav" aria-label="平台工作区">
          {workspaceRoutes.map((route) => {
            const Icon = route.icon
            return (
              <NavLink key={route.path} to={route.path}>
                <Icon size={20} weight="regular" aria-hidden="true" />
                <span><strong>{route.label}</strong><small>{route.englishLabel}</small></span>
              </NavLink>
            )
          })}
        </nav>
        <div className="sidebar-user">
          <span className="user-avatar" aria-hidden="true">{initials}</span>
          <div className="user-identity"><strong>{user.displayName}</strong><small>{user.role}</small></div>
          <button className="icon-button" type="button" onClick={onLogout} aria-label="退出登录">
            <SignOut size={19} aria-hidden="true" />
          </button>
        </div>
      </aside>
      <main className="app-main"><div className="app-content">{children}</div></main>
    </div>
  )
}
