import { useRef, useState } from 'react'
import { knowledgeApi } from '../knowledgeApi'

export function UploadForm({ kbId, onUploaded }: { kbId: string; onUploaded: () => void }) {
  const inputRef = useRef<HTMLInputElement>(null)
  const [busy, setBusy] = useState(false)

  const upload = async (e: React.FormEvent) => {
    e.preventDefault()
    const file = inputRef.current?.files?.[0]
    if (!file) return
    setBusy(true)
    try {
      await knowledgeApi.upload(kbId, file)
      onUploaded()
    } finally {
      setBusy(false)
      if (inputRef.current) inputRef.current.value = ''
    }
  }

  return (
    <form onSubmit={upload} className="upload-form">
      <input ref={inputRef} type="file" accept=".pdf,.docx,.txt,.md" />
      <button type="submit" disabled={busy}>
        {busy ? '上传中…' : '上传文档'}
      </button>
    </form>
  )
}
