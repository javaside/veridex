/**
 * 读取 Spring Security csrf.spa() 通过 XSRF-TOKEN cookie 下发的 CSRF token，
 * 供浏览器 Session 的写请求（POST/PUT/DELETE）通过 X-XSRF-TOKEN header 回传。
 */
export function csrfToken(): string | null {
  if (typeof document === 'undefined') return null
  const match = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/)
  return match ? decodeURIComponent(match[1]) : null
}

export function csrfHeaders(): Record<string, string> {
  const token = csrfToken()
  return token ? { 'X-XSRF-TOKEN': token } : {}
}
