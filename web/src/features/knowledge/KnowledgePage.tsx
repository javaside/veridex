import { useEffect, useState } from 'react'
import { knowledgeApi, type KnowledgeBase } from './knowledgeApi'
import { UploadForm } from './components/UploadForm'
import { VersionList } from './components/VersionList'

export function KnowledgePage() {
  const [bases, setBases] = useState<KnowledgeBase[]>([])
  const [selected, setSelected] = useState<KnowledgeBase | null>(null)
  const [name, setName] = useState('')
  const [refreshKey, setRefreshKey] = useState(0)

  useEffect(() => {
    knowledgeApi.list().then(setBases).catch(() => {})
  }, [refreshKey])

  const createBase = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!name.trim()) return
    await knowledgeApi.create(name.trim())
    setName('')
    setRefreshKey((k) => k + 1)
  }

  return (
    <div className="knowledge-page">
      <aside className="kb-sidebar">
        <h2>知识库</h2>
        <form onSubmit={createBase} className="kb-create">
          <input value={name} onChange={(e) => setName(e.target.value)} placeholder="新建知识库名称" />
          <button type="submit">新建</button>
        </form>
        <ul>
          {bases.map((kb) => (
            <li key={kb.id}>
              <button className={selected?.id === kb.id ? 'active' : ''} onClick={() => setSelected(kb)}>
                {kb.name}
              </button>
            </li>
          ))}
        </ul>
      </aside>
      <main className="kb-content">
        {selected ? (
          <>
            <h2>{selected.name}</h2>
            <UploadForm kbId={selected.id} onUploaded={() => setRefreshKey((k) => k + 1)} />
            <VersionList kbId={selected.id} refreshKey={refreshKey} />
          </>
        ) : (
          <p className="kb-placeholder">请选择或创建一个知识库</p>
        )}
      </main>
    </div>
  )
}
