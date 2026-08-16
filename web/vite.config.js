import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// 前端开发服务器; /api 代理到 Spring Boot 后端(8080)
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': 'http://localhost:8080',
    },
  },
})
