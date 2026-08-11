import { CheckCircle, LockKey, Stack } from '@phosphor-icons/react'
import { useState } from 'react'
import { authApi } from './authApi'

export function LoginPage() {
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)

  const submit = async (event: React.FormEvent) => {
    event.preventDefault()
    setError(null)
    setSubmitting(true)
    try {
      await authApi.login(username, password)
      window.location.href = '/knowledge'
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : '登录失败')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div className="login-page">
      <section className="login-brand" aria-label="Veridex 产品介绍">
        <div className="brand-lockup"><span className="brand-mark" aria-hidden="true">V</span><span>Veridex</span></div>
        <div className="login-brand-copy">
          <p className="section-kicker">企业知识基础设施</p>
          <h1>让企业知识成为可验证的答案。</h1>
          <p>统一管理文档、处理版本和检索发布，为带权限的 RAG 查询准备可靠知识底座。</p>
        </div>
        <ul className="capability-list">
          <li><Stack size={19} aria-hidden="true" /><span><strong>异步文档处理</strong><small>解析、分块和索引状态全程可追踪</small></span></li>
          <li><CheckCircle size={19} aria-hidden="true" /><span><strong>不可变索引发布</strong><small>失败版本不会影响当前可用知识</small></span></li>
          <li><LockKey size={19} aria-hidden="true" /><span><strong>权限与审计边界</strong><small>知识范围由平台统一计算和记录</small></span></li>
        </ul>
      </section>
      <main className="login-panel">
        <form className="login-form" onSubmit={submit}>
          <header>
            <p className="section-kicker">企业知识控制台</p>
            <h2>欢迎回来</h2>
            <p>登录后继续管理知识入库。</p>
          </header>
          <label>用户名<input name="username" value={username} onChange={(event) => setUsername(event.target.value)} autoComplete="username" /></label>
          <label>密码<input name="password" type="password" value={password} onChange={(event) => setPassword(event.target.value)} autoComplete="current-password" /></label>
          {error && <p className="form-error" role="alert">{error}</p>}
          <button className="primary-button login-button" type="submit" disabled={submitting}>{submitting ? '正在登录' : '登录'}</button>
          <p className="local-account-note">本地开发账号：<strong>admin / veridex</strong></p>
        </form>
      </main>
    </div>
  )
}
