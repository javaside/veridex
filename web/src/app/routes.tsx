import { ChatCircleText, Database, Gauge, ShieldCheck } from '@phosphor-icons/react'
import type { ComponentType, ReactNode } from 'react'
import { KnowledgePage } from '../features/knowledge/KnowledgePage'
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
    content: <ComingSoonPage title="员工问答" phase="Phase 3" description="基于授权知识范围生成可验证答案。" capabilities={['混合检索与重排', '流式回答', '引用校验']} />,
  },
  {
    path: '/knowledge', label: '知识管理', englishLabel: 'Knowledge', icon: Database,
    content: <KnowledgePage />,
  },
  {
    path: '/evaluation', label: '质量评测', englishLabel: 'Evaluation', icon: Gauge,
    content: <ComingSoonPage title="质量评测" phase="Phase 4" description="比较不可变配置并分析失败案例。" capabilities={['版本化数据集', '检索与回答指标', '回归门禁']} />,
  },
  {
    path: '/admin', label: '平台管理', englishLabel: 'Administration', icon: ShieldCheck,
    content: <ComingSoonPage title="平台管理" phase="Phase 5" description="管理安全、可观测性与企业部署。" capabilities={['访问治理', '运行监控', '备份与恢复']} />,
  },
]
