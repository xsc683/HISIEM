import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// 前端开发服务器; /api 代理到 Spring Boot 后端(8080)
export default defineConfig({
  plugins: [react()],
  build: {
    // 将稳定的第三方依赖与业务代码分离，页面迭代时浏览器可以复用 vendor 缓存。
    rollupOptions: {
      output: {
        manualChunks(id) {
          if (!id.includes('node_modules')) return undefined
          const moduleId = id.replaceAll('\\', '/')
          if (moduleId.includes('/react/') || moduleId.includes('/react-dom/')) return 'react'
          // Ant Design 按实际页面引用由 Rollup 自动拆分，避免首屏加载全部组件。
          // 其余依赖也交给 Rollup 自动归并，避免通用 vendor 与 react 之间形成循环 chunk。
          return undefined
        },
      },
    },
  },
  server: {
    port: 5173,
    proxy: {
      '/api': 'http://localhost:8080',
    },
  },
})
