import type { ReactNode } from 'react'
import { KnowledgePage } from '../features/knowledge/KnowledgePage'
import { LoginPage } from '../features/auth/LoginPage'

export type WorkspaceRoute = {
  path: string
  label: string
  englishLabel: string
  content: ReactNode
}

export const workspaceRoutes: WorkspaceRoute[] = [
  {
    path: '/workbench',
    label: '员工问答',
    englishLabel: 'Workbench',
    content: <h1>员工问答</h1>,
  },
  {
    path: '/knowledge',
    label: '知识管理',
    englishLabel: 'Knowledge',
    content: <KnowledgePage />,
  },
  {
    path: '/evaluation',
    label: '质量评测',
    englishLabel: 'Evaluation',
    content: <h1>质量评测</h1>,
  },
  {
    path: '/admin',
    label: '平台管理',
    englishLabel: 'Administration',
    content: <h1>平台管理</h1>,
  },
]

export function LoginRoute() {
  return <LoginPage />
}
