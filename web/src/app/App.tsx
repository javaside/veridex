import { Navigate, NavLink, Route, Routes } from 'react-router-dom'
import { workspaceRoutes } from './routes'

export function App() {
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
      </aside>
      <main>
        <Routes>
          {workspaceRoutes.map((route) => (
            <Route key={route.path} path={route.path} element={route.content} />
          ))}
          <Route path="*" element={<Navigate to="/workbench" replace />} />
        </Routes>
      </main>
    </div>
  )
}
