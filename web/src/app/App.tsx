import { useEffect, useState } from 'react'
import { Navigate, NavLink, Route, Routes } from 'react-router-dom'
import { authApi, type CurrentUser } from '../features/auth/authApi'
import { LoginPage } from '../features/auth/LoginPage'
import { workspaceRoutes } from './routes'

export function App() {
  const [user, setUser] = useState<CurrentUser | null>(null)
  const [checking, setChecking] = useState(true)

  useEffect(() => {
    authApi
      .me()
      .then(setUser)
      .catch(() => setUser(null))
      .finally(() => setChecking(false))
  }, [])

  if (checking) {
    return <div className="app-shell loading">加载中…</div>
  }

  if (!user) {
    return (
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route path="*" element={<Navigate to="/login" replace />} />
      </Routes>
    )
  }

  return (
    <div className="app-shell">
      <aside>
        <strong>Veridex</strong>
        <nav aria-label="平台工作区">
          {workspaceRoutes.map((route) => (
            <NavLink key={route.path} to={route.path}>
              <span>{route.label}</span>
              <small aria-hidden="true">{route.englishLabel}</small>
            </NavLink>
          ))}
        </nav>
        <div className="user-info">
          <span>{user.displayName}</span>
          <small>{user.role}</small>
        </div>
      </aside>
      <main>
        <Routes>
          {workspaceRoutes.map((route) => (
            <Route key={route.path} path={route.path} element={route.content} />
          ))}
          <Route path="/login" element={<Navigate to="/workbench" replace />} />
          <Route path="*" element={<Navigate to="/workbench" replace />} />
        </Routes>
      </main>
    </div>
  )
}
