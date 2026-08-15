import { useState } from 'react'
import { configurationApi, type ProfileConfig } from '../configurationApi'

export function ProfileEditor({ profileId, name, description, initial, onSaved, onNotify }: {
  profileId: string
  name: string
  description: string | null
  initial: ProfileConfig
  onSaved: () => void
  onNotify: (type: 'success' | 'error', message: string) => void
}) {
  const [config, setConfig] = useState<ProfileConfig>(initial)
  const [saving, setSaving] = useState(false)

  const patch = (patch: Partial<ProfileConfig>) => setConfig((c) => ({ ...c, ...patch }))

  const save = async () => {
    setSaving(true)
    try {
      await configurationApi.update(profileId, name, description, config)
      onNotify('success', '草稿已保存')
      onSaved()
    } catch (e) {
      onNotify('error', e instanceof Error ? e.message : '保存失败')
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="case-editor panel">
      <div className="panel-header"><h2>五维配置草稿</h2></div>
      <div className="case-editor-body">
        <fieldset className="config-section">
          <legend>Chunking</legend>
          <label>maxChars<input type="number" value={config.chunking.maxChars} onChange={(e) => patch({ chunking: { ...config.chunking, maxChars: Number(e.target.value) } })} /></label>
          <label>overlap<input type="number" value={config.chunking.overlap} onChange={(e) => patch({ chunking: { ...config.chunking, overlap: Number(e.target.value) } })} /></label>
        </fieldset>
        <fieldset className="config-section">
          <legend>Retrieval</legend>
          <label>topKPerChannel<input type="number" value={config.retrieval.topKPerChannel} onChange={(e) => patch({ retrieval: { ...config.retrieval, topKPerChannel: Number(e.target.value) } })} /></label>
          <label>rrfK<input type="number" value={config.retrieval.rrfK} onChange={(e) => patch({ retrieval: { ...config.retrieval, rrfK: Number(e.target.value) } })} /></label>
          <label>contextTopK<input type="number" value={config.retrieval.contextTopK} onChange={(e) => patch({ retrieval: { ...config.retrieval, contextTopK: Number(e.target.value) } })} /></label>
          <label>perDocumentMax<input type="number" value={config.retrieval.perDocumentMax} onChange={(e) => patch({ retrieval: { ...config.retrieval, perDocumentMax: Number(e.target.value) } })} /></label>
          <label>contextMaxChars<input type="number" value={config.retrieval.contextMaxChars} onChange={(e) => patch({ retrieval: { ...config.retrieval, contextMaxChars: Number(e.target.value) } })} /></label>
        </fieldset>
        <fieldset className="config-section">
          <legend>Generation</legend>
          <label>maxHistoryTurns<input type="number" value={config.generation.maxHistoryTurns} onChange={(e) => patch({ generation: { ...config.generation, maxHistoryTurns: Number(e.target.value) } })} /></label>
          <label>minEvidenceChars<input type="number" value={config.generation.minEvidenceChars} onChange={(e) => patch({ generation: { ...config.generation, minEvidenceChars: Number(e.target.value) } })} /></label>
        </fieldset>
        <fieldset className="config-section">
          <legend>Prompt</legend>
          <label>systemTemplate<textarea value={config.prompt.systemTemplate} onChange={(e) => patch({ prompt: { systemTemplate: e.target.value } })} rows={4} /></label>
        </fieldset>
        <fieldset className="config-section">
          <legend>Model</legend>
          <label>chatModel<input value={config.model.chatModel} onChange={(e) => patch({ model: { ...config.model, chatModel: e.target.value } })} /></label>
          <label>embeddingModel<input value={config.model.embeddingModel} onChange={(e) => patch({ model: { ...config.model, embeddingModel: e.target.value } })} /></label>
        </fieldset>
        <div className="case-editor-actions">
          <button className="primary-button" type="button" onClick={() => void save()} disabled={saving}>{saving ? '保存中' : '保存草稿'}</button>
        </div>
      </div>
    </div>
  )
}
