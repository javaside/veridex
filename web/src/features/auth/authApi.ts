export type CurrentUser = { id: string; username: string; displayName: string; role: string }

export const authApi = {
  login: (username: string, password: string): Promise<CurrentUser> =>
    fetch(`/api/auth/login?username=${encodeURIComponent(username)}&password=${encodeURIComponent(password)}`, {
      method: 'POST',
      credentials: 'include',
    }).then((r) => {
      if (!r.ok) throw new Error('登录失败')
      return r.json()
    }),
  me: (): Promise<CurrentUser> =>
    fetch('/api/auth/me', { credentials: 'include' }).then((r) => {
      if (!r.ok) throw new Error('未登录')
      return r.json()
    }),
  logout: (): Promise<void> =>
    fetch('/api/auth/logout', { method: 'POST', credentials: 'include' }).then(() => undefined),
}
