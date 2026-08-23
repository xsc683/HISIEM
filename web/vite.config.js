import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import Components from 'unplugin-vue-components/vite'
import { AntDesignVueResolver } from 'unplugin-vue-components/resolvers'

// 前端开发服务器; /api 代理到 Spring Boot 后端(8080)
export default defineConfig({
  plugins: [
    vue(),
    Components({
      dts: false,
      resolvers: [AntDesignVueResolver({ importStyle: false })],
    }),
  ],
  build: {
    // 将稳定的第三方依赖与业务代码分离，页面迭代时浏览器可以复用 vendor 缓存。
    rollupOptions: {
      output: {
        manualChunks(id) {
          if (!id.includes('node_modules')) return undefined
          const moduleId = id.replaceAll('\\', '/')
          if (moduleId.includes('/vue/') || moduleId.includes('/vue-router/')) return 'vue'
          if (moduleId.includes('/@vue-flow/')) return 'vue-flow'
          // Ant Design Vue 按页面引用自动拆分，避免首屏加载全部组件。
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
