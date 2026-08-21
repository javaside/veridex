import { ChatCircleDots, PaperPlaneRight, Plus } from '@phosphor-icons/react'
import { useCallback, useEffect, useRef, useState } from 'react'
import { PageHeader } from '../../app/PageHeader'
import { knowledgeApi, type KnowledgeBase } from '../knowledge/knowledgeApi'
import { PreviewDrawer } from '../knowledge/components/PreviewDrawer'
import { ChatMessage } from './components/ChatMessage'
import { KnowledgeBasePicker } from './components/KnowledgeBasePicker'
import { qaApi, type Citation, type ConversationView, type FeedbackEvidence, type FeedbackReasonCode, type MessageRecord } from './qaApi'

type LocalMessage = {
  id: string
  role: 'USER' | 'ASSISTANT' | 'SYSTEM' | 'ERROR'
  content: string
  citations?: Citation[]
  runId?: string
  question?: string
}

const CONVERSATION_PAGE_SIZE = 20

const REASON_CODES: FeedbackReasonCode[] = ['WRONG_ANSWER', 'HALLUCINATION', 'MISSING_EVIDENCE', 'OUTDATED', 'WRONG_REFUSAL', 'OTHER']
const REASON_LABELS: Record<FeedbackReasonCode, string> = {
  WRONG_ANSWER: '回答错误',
  HALLUCINATION: '无依据 / 幻觉',
  MISSING_EVIDENCE: '证据不足',
  OUTDATED: '内容过时',
  WRONG_REFUSAL: '错误拒答',
  OTHER: '其他',
}

function citationsToEvidence(citations: Citation[]): FeedbackEvidence[] {
  const byDoc = new Map<string, number[]>()
  for (const c of citations) {
    if (c.validationStatus !== 'VALID') continue
    const arr = byDoc.get(c.documentVersionId) ?? []
    arr.push(c.chunkIndex)
    byDoc.set(c.documentVersionId, arr)
  }
  return [...byDoc.entries()].map(([documentVersionId, chunkIndexes]) => ({ documentVersionId, chunkIndexes }))
}

export function QaPage() {
  const [bases, setBases] = useState<KnowledgeBase[]>([])
  const [selectedKbIds, setSelectedKbIds] = useState<string[]>([])
  const [conversations, setConversations] = useState<ConversationView[]>([])
  const [visibleCount, setVisibleCount] = useState(CONVERSATION_PAGE_SIZE)
  const [activeConversationId, setActiveConversationId] = useState<string | null>(null)
  const [messages, setMessages] = useState<LocalMessage[]>([])
  const [question, setQuestion] = useState('')
  const [streaming, setStreaming] = useState(false)
  const [qaStage, setQaStage] = useState('')
  const [loading, setLoading] = useState(true)
  const [preview, setPreview] = useState<{ title: string; content: string } | null>(null)
  const scrollRef = useRef<HTMLDivElement>(null)
  const abortRef = useRef<AbortController | null>(null)
  // 流式回答缓冲：ref 供事件回调累积（不触发渲染、无并发问题），state 镜像用于渲染。
  // 不能用「updater 内写 ref 定位消息」：React 19 StrictMode 双调用 updater 会读到
  // 旧 base 上不存在的索引 → undefined.content 白屏。
  const streamTextRef = useRef('')
  const streamCitationsRef = useRef<Citation[]>([])
  const lastQuestionRef = useRef('')
  const lastRunIdRef = useRef<string | null>(null)
  const [streamText, setStreamText] = useState('')
  const [streamCitations, setStreamCitations] = useState<Citation[]>([])
  const [feedback, setFeedback] = useState<{ message: LocalMessage; rating: 'UP' | 'DOWN' } | null>(null)

  const resetStream = useCallback(() => {
    streamTextRef.current = ''
    streamCitationsRef.current = []
    setStreamText('')
    setStreamCitations([])
  }, [])

  const appendStreamText = useCallback((text: string) => {
    streamTextRef.current += text
    setStreamText(streamTextRef.current)
  }, [])

  const setStreamCitationList = useCallback((citations: Citation[]) => {
    streamCitationsRef.current = citations
    setStreamCitations(citations)
  }, [])

  /** 把当前流式缓冲固化为一条 assistant 消息（回答结束 / 拒答 / 失败时调用）。 */
  const finalizeStream = useCallback(() => {
    const text = streamTextRef.current
    const citations = streamCitationsRef.current
    if (text || citations.length > 0) {
      setMessages((current) => [
        ...current,
        { id: `assistant-${Date.now()}`, role: 'ASSISTANT', content: text, citations, runId: lastRunIdRef.current ?? undefined, question: lastQuestionRef.current },
      ])
    }
    resetStream()
  }, [resetStream])

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

  // 页面卸载/组件销毁：取消进行中的问答流（设计 §7）
  useEffect(() => {
    return () => abortRef.current?.abort()
  }, [])

  const loadConversation = useCallback(async (conversationId: string) => {
    setActiveConversationId(conversationId)
    resetStream()
    try {
      const records: MessageRecord[] = await qaApi.messages(conversationId)
      setMessages(records.map((record) => ({ id: record.id, role: record.role as LocalMessage['role'], content: record.content })))
    } catch {
      setMessages([{ id: 'load-error', role: 'ERROR', content: '会话消息加载失败' }])
    }
  }, [])

  /** 新建会话：清空当前对话与选中会话，回到空白提问状态（后端在首问时自动建会话）。 */
  const newConversation = useCallback(() => {
    setActiveConversationId(null)
    setMessages([])
    setQuestion('')
    resetStream()
  }, [resetStream])

  const ask = async () => {
    const trimmed = question.trim()
    if (!trimmed || streaming || selectedKbIds.length === 0) return
    setQuestion('')
    setStreaming(true)
    setQaStage('正在检索知识库…')
    resetStream()
    lastQuestionRef.current = trimmed
    lastRunIdRef.current = null
    const controller = new AbortController()
    abortRef.current = controller
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
          case 'retrieval.completed':
            setQaStage(`已检索到 ${event.data.hitCount} 条证据，正在生成回答…`)
            break
          case 'answer.delta':
            setQaStage('')
            appendStreamText(event.data.text)
            break
          case 'citation.available':
            setStreamCitationList(event.data.citations)
            break
          case 'answer.refused':
            // 拒答发生在 delta 之前：丢弃空的 provisional 并显示拒答（设计 §7）
            setQaStage('')
            resetStream()
            setMessages((current) => [...current, { id: `system-${Date.now()}`, role: 'SYSTEM', content: event.data.message }])
            break
          case 'run.failed':
            // 引用终检失败/模型故障：丢弃 provisional 文本，显示错误（设计 D9）
            setQaStage('')
            resetStream()
            setMessages((current) => [...current, { id: `error-${Date.now()}`, role: 'ERROR', content: event.data.message }])
            break
          case 'answer.completed':
            lastRunIdRef.current = event.data.runId
            finalizeStream()
            break
          default:
            break
        }
      }, controller.signal)
    } catch (error) {
      // 用户主动取消：丢弃 provisional，不显示伪失败
      if (error instanceof DOMException && error.name === 'AbortError') {
        resetStream()
      } else {
        throw error
      }
    } finally {
      setStreaming(false)
      setQaStage('')
      resetStream()
      const latest = await qaApi.conversations()
      setConversations(latest)
    }
  }

  const openCitationPreview = async (citation: Citation) => {
    if (!citation.documentId) return
    try {
      const chunks = await knowledgeApi.chunks(citation.documentId, citation.documentVersionId)
      const chunk = chunks.find((c) => c.index === citation.chunkIndex)
      setPreview({ title: citation.sourceLocation ?? '引用原文', content: chunk?.text ?? '未找到该引用的原文' })
    } catch {
      setPreview({ title: citation.sourceLocation ?? '引用原文', content: '引用原文加载失败' })
    }
  }

  const submitFeedback = async (message: LocalMessage, rating: 'UP' | 'DOWN', reasonCode: FeedbackReasonCode | null) => {
    try {
      await qaApi.feedback({
        queryRunId: message.runId ?? null,
        rating,
        reasonCode: rating === 'DOWN' ? reasonCode ?? 'OTHER' : null,
        question: message.question ?? '',
        answer: message.content,
        evidence: rating === 'DOWN' ? citationsToEvidence(message.citations ?? []) : [],
      })
    } finally {
      setFeedback(null)
    }
  }

  const handleFeedback = (message: LocalMessage, rating: 'UP' | 'DOWN') => {
    if (rating === 'UP') {
      void submitFeedback(message, 'UP', null)
    } else {
      setFeedback({ message, rating: 'DOWN' })
    }
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
            <div className="qa-panel-heading">
              <span>最近会话</span>
              <button className="text-button" type="button" onClick={newConversation}>
                <Plus size={13} weight="bold" aria-hidden="true" /> 新建
              </button>
            </div>
            {conversations.length === 0 ? (
              <p className="qa-empty-hint">暂无历史会话。</p>
            ) : (
              <>
                <ul>
                  {conversations.slice(0, visibleCount).map((conversation) => (
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
                {conversations.length > visibleCount && (
                  <button
                    type="button"
                    className="secondary-button qa-load-more"
                    onClick={() => setVisibleCount((n) => n + CONVERSATION_PAGE_SIZE)}
                  >
                    加载更多（还有 {conversations.length - visibleCount} 个会话）
                  </button>
                )}
              </>
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
                onFeedback={message.role === 'ASSISTANT' && message.runId ? (rating) => handleFeedback(message, rating) : undefined}
              />
            ))}
            {streaming && streamText === '' && qaStage !== '' && (
              <div className="qa-generating" role="status" aria-live="polite">
                <span className="qa-generating-dots"><i /><i /><i /></span>
                <span>{qaStage}</span>
              </div>
            )}
            {streaming && streamText !== '' && (
              <ChatMessage
                role="ASSISTANT"
                content={streamText}
                citations={streamCitations}
                onCitationClick={(citation) => void openCitationPreview(citation)}
              />
            )}
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
      {preview && <PreviewDrawer title={preview.title} content={preview.content} open onClose={() => setPreview(null)} />}
      {feedback?.rating === 'DOWN' && (
        <div className="dialog-layer">
          <button className="dialog-backdrop" type="button" aria-label="关闭对话框" onClick={() => setFeedback(null)} />
          <div className="confirm-dialog" role="dialog" aria-modal="true" aria-label="选择反馈原因">
            <h3>这条回答有什么问题？</h3>
            <div className="feedback-reason-list">
              {REASON_CODES.map((code) => (
                <button key={code} type="button" onClick={() => void submitFeedback(feedback.message, 'DOWN', code)}>{REASON_LABELS[code]}</button>
              ))}
            </div>
          </div>
        </div>
      )}
    </section>
  )
}