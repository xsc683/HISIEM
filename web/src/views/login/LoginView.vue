<template>
  <div class="login-page">
    <div class="login-visual">
      <div class="visual-grid"></div>
      <div class="visual-content">
        <div class="shield">H</div>
        <h1>HISIEM</h1>
        <p>让事件、检测、调查与响应形成可解释的安全运营闭环。</p>
        <div class="capabilities"><span>事件检测</span><span>案件调查</span><span>自动化响应</span></div>
      </div>
    </div>
    <div class="login-panel">
      <a-form class="login-form" layout="vertical" :model="form" @finish="submit">
        <div class="eyebrow">SECURITY OPERATIONS CONSOLE</div>
        <h2>登录控制台</h2>
        <p class="muted">使用平台账户继续。登录失败和权限拒绝会记录在审计日志中。</p>
        <a-alert v-if="error" type="error" show-icon :message="error" style="margin: 20px 0" />
        <a-form-item label="用户名" name="username" :rules="[{ required: true, message: '请输入用户名' }]">
          <a-input v-model:value="form.username" size="large" autocomplete="username" placeholder="用户名" />
        </a-form-item>
        <a-form-item label="密码" name="password" :rules="[{ required: true, message: '请输入密码' }]">
          <a-input-password v-model:value="form.password" size="large" autocomplete="current-password" placeholder="密码" />
        </a-form-item>
        <a-button type="primary" html-type="submit" size="large" block :loading="auth.state.loading">登录</a-button>
      </a-form>
    </div>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuth } from '../../composables/useAuth.js'
import { landingRoute } from '../../utils/navigation.js'

const auth = useAuth()
const route = useRoute()
const router = useRouter()
const form = reactive({ username: '', password: '' })
const error = ref('')

async function submit() {
  error.value = ''
  try {
    const user = await auth.signIn(form.username, form.password)
    await router.replace(typeof route.query.redirect === 'string' ? route.query.redirect : landingRoute(user?.role))
  } catch (cause) {
    error.value = cause?.message || '登录失败'
  }
}
</script>

<style scoped>
.login-page { min-height: 100vh; display: grid; grid-template-columns: minmax(520px, 1.12fr) minmax(480px, .88fr); background: #f6f8fa; }
.login-visual { position: relative; display: grid; place-items: center; overflow: hidden; background: radial-gradient(circle at 20% 15%, #1d6fa5 0, transparent 36%), linear-gradient(135deg, #102a3c, #0b1e2d); color: white; }
.visual-grid { position: absolute; inset: 0; opacity: .12; background-image: linear-gradient(#9ed4e8 1px, transparent 1px), linear-gradient(90deg, #9ed4e8 1px, transparent 1px); background-size: 42px 42px; transform: perspective(500px) rotateX(58deg) scale(1.5); transform-origin: bottom; }
.visual-content { position: relative; width: min(520px, 75%); }
.shield { display: grid; place-items: center; width: 74px; height: 74px; border: 1px solid rgb(255 255 255 / 35%); border-radius: 20px; background: rgb(54 163 201 / 28%); font-size: 36px; font-weight: 800; box-shadow: 0 20px 60px rgb(0 0 0 / 25%); }
.visual-content h1 { margin: 24px 0 6px; font-size: 49px; letter-spacing: .12em; }
.visual-content p { max-width: 470px; color: #c1d9e6; font-size: 19px; line-height: 1.7; }
.capabilities { display: flex; gap: 9px; margin-top: 28px; }
.capabilities span { padding: 7px 12px; border: 1px solid rgb(255 255 255 / 20%); border-radius: 999px; color: #d5e8f1; font-size: 12px; }
.login-panel { display: grid; place-items: center; padding: 60px; }
.login-form { width: 390px; }
.login-form h2 { margin: 8px 0 4px; color: #122c40; font-size: 30px; }
.eyebrow { color: #1d6fa5; font-size: 11px; font-weight: 700; letter-spacing: .16em; }
@media (max-width: 900px) {
  .login-page { grid-template-columns: 1fr; grid-template-rows: minmax(230px, 32vh) 1fr; }
  .login-visual { place-items: center start; padding: 34px; }
  .visual-content { width: min(620px, 92%); }
  .shield { width: 52px; height: 52px; border-radius: 14px; font-size: 25px; }
  .visual-content h1 { margin: 13px 0 3px; font-size: 32px; }
  .visual-content p { margin: 0; font-size: 15px; }
  .capabilities { margin-top: 14px; }
  .login-panel { place-items: start center; padding: 38px 24px 52px; }
  .login-form { width: min(420px, 100%); }
}
@media (max-width: 520px) {
  .login-page { grid-template-rows: 178px 1fr; }
  .login-visual { padding: 24px 20px; }
  .shield { width: 40px; height: 40px; border-radius: 11px; font-size: 20px; }
  .visual-content h1 { margin-top: 8px; font-size: 25px; }
  .visual-content p { font-size: 12px; line-height: 1.5; }
  .capabilities { display: none; }
  .login-panel { padding: 28px 18px 40px; }
  .login-form h2 { font-size: 25px; }
}
</style>
