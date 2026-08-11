import { useEffect, useState } from 'react'
import { Navigate, Route, Routes } from 'react-router-dom'
import { authApi, type CurrentUser } from '../features/auth/authApi'
import { LoginPage } from '../features/auth/LoginPage'
import { AppShell } from './AppShell'
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
    return <div className="app-loading" role="status"><span className="brand-mark" aria-hidden="true">V</span><span>正在加载工作区</span></div>
  }

  if (!user) {
    return (
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route path="*" element={<Navigate to="/login" replace />} />
      </Routes>
    )
  }

  const logout = async () => {
    await authApi.logout()
    setUser(null)
  }

  return (
    <AppShell user={user} onLogout={logout}>
      <Routes>
        {workspaceRoutes.map((route) => (
          <Route key={route.path} path={route.path} element={route.content} />
        ))}
        <Route path="/login" element={<Navigate to="/workbench" replace />} />
        <Route path="*" element={<Navigate to="/workbench" replace />} />
      </Routes>
    </AppShell>
  )
}
