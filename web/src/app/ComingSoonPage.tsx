import { ArrowRight } from '@phosphor-icons/react'
import { Link } from 'react-router-dom'
import { PageHeader } from './PageHeader'

export function ComingSoonPage({ title, description, phase, capabilities }: { title: string; description: string; phase: string; capabilities: string[] }) {
  return (
    <section className="workspace-page coming-soon-page">
      <PageHeader title={title} description={description} meta={phase} />
      <div className="coming-soon-panel">
        <div>
          <span className="phase-label">{phase}</span>
          <h2>该工作区正在建设中</h2>
          <p>当前版本优先交付可靠的知识入库底座。这里将在对应阶段接入真实业务能力。</p>
          <Link className="button-link" to="/knowledge">前往知识管理 <ArrowRight size={17} aria-hidden="true" /></Link>
        </div>
        <div className="capability-preview" aria-label="计划能力">
          <h3>计划能力</h3>
          <ul>{capabilities.map((item) => <li key={item}>{item}</li>)}</ul>
        </div>
      </div>
    </section>
  )
}
