import type { ReactNode } from 'react'

export function PageHeader({ title, description, meta, actions }: { title: string; description: string; meta?: string; actions?: ReactNode }) {
  return (
    <header className="page-header">
      <div>
        <div className="page-title-row"><h1>{title}</h1>{meta && <span className="page-meta">{meta}</span>}</div>
        <p>{description}</p>
      </div>
      {actions && <div className="page-actions">{actions}</div>}
    </header>
  )
}
