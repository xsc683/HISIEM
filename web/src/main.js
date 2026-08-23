import { createApp } from 'vue'
import 'ant-design-vue/dist/reset.css'
import '@vue-flow/core/dist/style.css'
import '@vue-flow/core/dist/theme-default.css'
import App from './App.vue'
import router from './router/index.js'
import './styles/main.css'

createApp(App).use(router).mount('#root')
