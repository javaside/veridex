import { ChatCircleDots, ChatCircleText, Database, Gauge, ShieldCheck, SlidersHorizontal } from '@phosphor-icons/react'
import type { ComponentType, ReactNode } from 'react'
import { KnowledgePage } from '../features/knowledge/KnowledgePage'
import { QaPage } from '../features/qa/QaPage'
import { EvaluationPage } from '../features/evaluation/EvaluationPage'
import { ConfigurationPage } from '../features/configuration/ConfigurationPage'
import { FeedbackPage } from '../features/feedback/FeedbackPage'
import { ComingSoonPage } from './ComingSoonPage'

export type WorkspaceRoute = {
  path: string
  label: string
  englishLabel: string
  icon: ComponentType<{ size?: number; weight?: 'regular' | 'fill' }>
  content: ReactNode
}

export const workspaceRoutes: WorkspaceRoute[] = [
  {
    path: '/workbench', label: '员工问答', englishLabel: 'Workbench', icon: ChatCircleText,
    content: <QaPage />,
  },
  {
    path: '/knowledge', label: '知识管理', englishLabel: 'Knowledge', icon: Database,
    content: <KnowledgePage />,
  },
  {
    path: '/evaluation', label: '质量评测', englishLabel: 'Evaluation', icon: Gauge,
    content: <EvaluationPage />,
  },
  {
    path: '/configuration', label: '配置版本', englishLabel: 'Configuration', icon: SlidersHorizontal,
    content: <ConfigurationPage />,
  },
  {
    path: '/feedback', label: '反馈管理', englishLabel: 'Feedback', icon: ChatCircleDots,
    content: <FeedbackPage />,
  },
  {
    path: '/admin', label: '平台管理', englishLabel: 'Administration', icon: ShieldCheck,
    content: <ComingSoonPage title="平台管理" phase="Phase 5" description="管理安全、可观测性与企业部署。" capabilities={['访问治理', '运行监控', '备份与恢复']} />,
  },
]
