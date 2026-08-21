import react from '@vitejs/plugin-react'
import { defineConfig } from 'vitest/config'

export default defineConfig({
  plugins: [react()],
  test: {
    environment: 'jsdom',
    setupFiles: './src/test/setup.ts',
  },
  server: {
    // 同时监听 IPv4 与 IPv6：本机 localhost 解析在 ::1 与 127.0.0.1 间切换，
    // 只绑单一地址族会导致一部分请求连不上（此前 sub2api 占用 IPv4:8080 后
    // 后端只能走 IPv6，前端代理与浏览器直连随之出现间歇性失败）。
    host: '::',
    proxy: {
      '/api': 'http://localhost:8088',
      '/actuator': 'http://localhost:8088',
    },
  },
})
