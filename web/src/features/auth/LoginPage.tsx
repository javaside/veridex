import { useState } from 'react'
import { authApi } from './authApi'

export function LoginPage() {
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)

  const submit = async (e: React.FormEvent) => {
    e.preventDefault()
    try {
      await authApi.login(username, password)
      window.location.href = '/knowledge'
    } catch (err) {
      setError(err instanceof Error ? err.message : '登录失败')
    }
  }

  return (
    <div className="login-page">
      <form className="login-form" onSubmit={submit}>
        <h1>Veridex</h1>
        <p className="login-hint">企业知识库平台</p>
        <label>
          用户名
          <input value={username} onChange={(e) => setUsername(e.target.value)} autoComplete="username" />
        </label>
        <label>
          密码
          <input
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            autoComplete="current-password"
          />
        </label>
        {error && <p className="login-error">{error}</p>}
        <button type="submit">登录</button>
      </form>
    </div>
  )
}
