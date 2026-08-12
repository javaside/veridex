import { ChatCircleDots, PaperPlaneRight } from '@phosphor-icons/react'
import { useCallback, useEffect, useRef, useState } from 'react'
import { PageHeader } from '../../app/PageHeader'
import { knowledgeApi, type KnowledgeBase } from '../knowledge/knowledgeApi'
import { ChatMessage } from './components/ChatMessage'
import { KnowledgeBasePicker } from './components/KnowledgeBasePicker'
import { qaApi, type Citation, type ConversationView, type MessageRecord } from './qaApi'

type LocalMessage = {
  id: string
  role: 'USER' | 'ASSISTANT' | 'SYSTEM' | 'ERROR'
  content: string
  citations?: Citation[]
}

export function QaPage() {
  const [bases, setBases] = useState<KnowledgeBase[]>([])
  const [selectedKbIds, setSelectedKbIds] = useState<string[]>([])
  const [conversations, setConversations] = useState<ConversationView[]>([])
  const [activeConversationId, setActiveConversationId] = useState<string | null>(null)
  const [messages, setMessages] = useState<LocalMessage[]>([])
  const [question, setQuestion] = useState('')
  const [streaming, setStreaming] = useState(false)
  const [loading, setLoading] = useState(true)
  const scrollRef = useRef<HTMLDivElement>(null)
  const streamAssistantRef = useRef<number | null>(null)

  useEffect(() => {
    let active = true
    Promise.all([knowledgeApi.list(), qaApi.conversations()])
      .then(([kbList, convList]) => {
        if (!active) return
        setBases(kbList)
        setSelectedKbIds(kbList.map((kb) => kb.id))
        setConversations(convList)
      })
      .catch(() => {
        if (active) setMessages([{ id: 'load-error', role: 'ERROR', content: '知识库或会话加载失败' }])
      })
      .finally(() => {
        if (active) setLoading(false)
      })
    return () => { active = false }
  }, [])

  useEffect(() => {
    scrollRef.current?.scrollTo({ top: scrollRef.current.scrollHeight })
  }, [messages])

  const loadConversation = useCallback(async (conversationId: string) => {
    setActiveConversationId(conversationId)
    try {
      const records: MessageRecord[] = await qaApi.messages(conversationId)
      setMessages(records.map((record) => ({ id: record.id, role: record.role as LocalMessage['role'], content: record.content })))
    } catch {
      setMessages([{ id: 'load-error', role: 'ERROR', content: '会话消息加载失败' }])
    }
  }, [])

  const appendAssistantDelta = useCallback((text: string, citations?: Citation[]) => {
    setMessages((current) => {
      const next = [...current]
      if (streamAssistantRef.current != null) {
        const index = streamAssistantRef.current
        const existing = next[index]
        next[index] = { ...existing, content: existing.content + text, citations: citations ?? existing.citations }
      } else {
        const message: LocalMessage = { id: `assistant-${Date.now()}`, role: 'ASSISTANT', content: text, citations }
        next.push(message)
        streamAssistantRef.current = next.length - 1
      }
      return next
    })
  }, [])

  const ask = async () => {
    const trimmed = question.trim()
    if (!trimmed || streaming || selectedKbIds.length === 0) return
    setQuestion('')
    setStreaming(true)
    streamAssistantRef.current = null
    setMessages((current) => [
      ...current,
      { id: `user-${Date.now()}`, role: 'USER', content: trimmed },
    ])

    try {
      await qaApi.ask(trimmed, selectedKbIds, activeConversationId, (event) => {
        switch (event.name) {
          case 'run.started':
            setActiveConversationId(event.data.conversationId)
            break
          case 'answer.delta':
            appendAssistantDelta(event.data.text)
            break
          case 'citation.available':
            appendAssistantDelta('', event.data.citations)
            break
          case 'answer.refused':
            setMessages((current) => [...current, { id: `system-${Date.now()}`, role: 'SYSTEM', content: event.data.message }])
            break
          case 'run.failed':
            setMessages((current) => [...current, { id: `error-${Date.now()}`, role: 'ERROR', content: event.data.message }])
            break
          case 'answer.completed':
            break
          default:
            break
        }
      })
    } finally {
      setStreaming(false)
      streamAssistantRef.current = null
      const latest = await qaApi.conversations()
      setConversations(latest)
    }
  }

  const openCitationPreview = async (citation: Citation) => {
    // 引用点击预览由 Task 13 接 CitationView.documentId 后启用（chunks API 需要 documentId）
    void citation
  }

  if (loading) {
    return (
      <section className="workspace-page qa-page">
        <PageHeader title="员工问答" description="基于授权知识库提出制度问题，获得带引用的回答。" meta="加载中" />
        <div className="qa-loading" role="status">正在加载问答工作区…</div>
      </section>
    )
  }

  return (
    <section className="workspace-page qa-page">
      <PageHeader title="员工问答" description="基于授权知识库提出制度问题，获得带引用的回答。" meta={`${bases.length} 个知识库`} />
      <div className="qa-layout">
        <aside className="qa-sidebar">
          <KnowledgeBasePicker
            bases={bases}
            selected={selectedKbIds}
            onToggle={(id) => setSelectedKbIds((current) => current.includes(id) ? current.filter((x) => x !== id) : [...current, id])}
            onToggleAll={() => setSelectedKbIds((current) => current.length === bases.length ? [] : bases.map((kb) => kb.id))}
          />
          <section className="qa-conversations" aria-label="最近会话">
            <div className="qa-panel-heading"><span>最近会话</span></div>
            {conversations.length === 0 ? (
              <p className="qa-empty-hint">暂无历史会话。</p>
            ) : (
              <ul>
                {conversations.map((conversation) => (
                  <li key={conversation.id}>
                    <button
                      type="button"
                      className={conversation.id === activeConversationId ? 'qa-conv-item active' : 'qa-conv-item'}
                      onClick={() => void loadConversation(conversation.id)}
                    >
                      <ChatCircleDots size={15} aria-hidden="true" />
                      <span>{conversation.title}</span>
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </section>
        </aside>

        <div className="qa-chat">
          <div className="qa-messages" ref={scrollRef}>
            {messages.length === 0 && (
              <div className="qa-welcome">
                <h2>向制度知识库提问</h2>
                <p>选择左侧知识库范围后开始提问，回答将基于授权资料并附引用来源。</p>
              </div>
            )}
            {messages.map((message) => (
              <ChatMessage
                key={message.id}
                role={message.role}
                content={message.content}
                citations={message.citations}
                onCitationClick={(citation) => void openCitationPreview(citation)}
              />
            ))}
          </div>
          <form className="qa-composer" onSubmit={(event) => { event.preventDefault(); void ask() }}>
            <label htmlFor="qa-question" className="sr-only">问题</label>
            <input
              id="qa-question"
              value={question}
              onChange={(event) => setQuestion(event.target.value)}
              placeholder={selectedKbIds.length === 0 ? '请先选择至少一个知识库' : '输入制度问题，回车发送'}
              disabled={streaming || selectedKbIds.length === 0}
            />
            <button className="primary-button" type="submit" disabled={streaming || selectedKbIds.length === 0 || question.trim() === ''}>
              <PaperPlaneRight size={16} aria-hidden="true" />
              {streaming ? '回答中' : '发送'}
            </button>
          </form>
        </div>
      </div>
    </section>
  )
}