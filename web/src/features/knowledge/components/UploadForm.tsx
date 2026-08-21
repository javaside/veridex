import { FileArrowUp } from '@phosphor-icons/react'
import { useRef, useState } from 'react'
import { knowledgeApi, type DocumentVersion } from '../knowledgeApi'

const formatBytes = (bytes: number) => bytes < 1024 * 1024 ? `${Math.max(1, Math.round(bytes / 1024))} KB` : `${(bytes / 1024 / 1024).toFixed(1)} MB`

export function UploadForm({ kbId, onUploaded, onNotify }: { kbId: string; onUploaded: (version: DocumentVersion) => void; onNotify?: (type: 'success' | 'error', message: string) => void }) {
  const inputRef = useRef<HTMLInputElement>(null)
  const [file, setFile] = useState<File | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const upload = async (event: React.FormEvent) => {
    event.preventDefault()
    if (!file) return
    setBusy(true)
    setError(null)
    try {
      const version = await knowledgeApi.upload(kbId, file)
      setFile(null)
      if (inputRef.current) inputRef.current.value = ''
      onUploaded(version)
      onNotify?.('success', '文档已提交，正在后台解析处理')
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : '文档上传失败')
      onNotify?.('error', caught instanceof Error ? caught.message : '文档上传失败')
    } finally {
      setBusy(false)
    }
  }

  return (
    <section className="panel upload-panel">
      <div className="upload-copy"><span className="feature-icon"><FileArrowUp size={22} /></span><div><h3>上传文档</h3><p>支持 PDF、DOCX、TXT、Markdown，最大 50 MB。</p></div></div>
      <form className="upload-form" onSubmit={upload}>
        <label className="file-picker"><span>{file ? '重新选择' : '选择文件'}</span><input ref={inputRef} type="file" accept=".pdf,.docx,.txt,.md" disabled={busy} onChange={(event) => { setFile(event.target.files?.[0] ?? null); setError(null) }} /></label>
        <div className="file-summary">{file ? <><strong>{file.name}</strong><small>{formatBytes(file.size)}</small></> : <span>尚未选择文件</span>}</div>
        <button className="primary-button" type="submit" disabled={busy || !file}>{busy ? '正在上传' : '提交处理'}</button>
      </form>
      {error && <p className="inline-message error" role="alert">{error}</p>}
    </section>
  )
}
