<template>
  <a-layout class="app-layout">
    <a-layout-sider v-model:collapsed="collapsed" :width="236" :collapsed-width="72" class="app-sider">
      <div class="brand" :class="{ compact: collapsed }">
        <div class="brand-mark">H</div>
        <div v-if="!collapsed"><strong>HISIEM</strong><span>SECURITY OPERATIONS</span></div>
      </div>
      <a-menu mode="inline" theme="dark" :items="menuItems" :selected-keys="selectedKeys" @click="navigate" />
    </a-layout-sider>
    <a-layout :style="{ marginLeft: collapsed ? '72px' : '236px' }" class="main-column">
      <a-layout-header class="topbar">
        <div class="topbar-left">
          <a-button type="text" class="collapse-button" @click="collapsed = !collapsed">
            <MenuUnfoldOutlined v-if="collapsed" /><MenuFoldOutlined v-else />
          </a-button>
          <div class="route-context"><span>安全运营平台</span><strong>{{ route.meta.title || '控制台' }}</strong></div>
        </div>
        <div class="topbar-right">
          <a-select v-if="tenants.length > 1" v-model:value="tenantId" style="width: 180px" @change="changeTenant"
            :options="tenants.map((tenant) => ({ value: tenant.id, label: tenant.name }))" />
          <a-tag v-else color="blue">租户：{{ tenants[0]?.name || tenantId }}</a-tag>
          <span class="clock">{{ nowText }}</span>
          <a-dropdown>
            <a-button class="user-button"><UserOutlined /> {{ auth.state.user?.username }} · {{ roleLabel }} <DownOutlined /></a-button>
            <template #overlay>
              <a-menu>
                <a-menu-item key="password" @click="passwordOpen = true"><KeyOutlined /> 修改密码</a-menu-item>
                <a-menu-divider />
                <a-menu-item key="logout" @click="handleLogout"><LogoutOutlined /> 退出登录</a-menu-item>
              </a-menu>
            </template>
          </a-dropdown>
        </div>
      </a-layout-header>
      <a-layout-content class="content"><router-view :key="route.fullPath" /></a-layout-content>
    </a-layout>
  </a-layout>

  <a-modal v-model:open="passwordOpen" title="修改登录密码" :closable="!mustChangePassword" :mask-closable="!mustChangePassword"
    :keyboard="!mustChangePassword" :cancel-button-props="{ disabled: mustChangePassword }" ok-text="保存新密码"
    :confirm-loading="passwordSaving" @ok="savePassword">
    <a-alert v-if="mustChangePassword" type="warning" show-icon message="首次登录必须先修改临时密码" style="margin-bottom: 16px" />
    <a-form layout="vertical">
      <a-form-item label="当前密码" required><a-input-password v-model:value="passwordForm.current" autocomplete="current-password" /></a-form-item>
      <a-form-item label="新密码" required extra="至少 12 位，并包含大小写字母、数字和特殊字符">
        <a-input-password v-model:value="passwordForm.next" autocomplete="new-password" />
      </a-form-item>
      <a-form-item label="确认新密码" required><a-input-password v-model:value="passwordForm.confirm" autocomplete="new-password" /></a-form-item>
    </a-form>
  </a-modal>
</template>

<script setup>
import { computed, h, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import {
  AlertOutlined, ApiOutlined, ApartmentOutlined, BarChartOutlined, BellOutlined, DownOutlined,
  KeyOutlined, LogoutOutlined, MenuFoldOutlined, MenuUnfoldOutlined, RobotOutlined,
  SafetyCertificateOutlined, SettingOutlined, TagsOutlined, TeamOutlined, ThunderboltOutlined, UserOutlined,
} from '@ant-design/icons-vue'
import { changePassword, getActiveTenant, listMyTenants, setActiveTenant } from '../api/index.js'
import { useAuth } from '../composables/useAuth.js'
import { displayLabel } from '../utils/display.js'

const route = useRoute()
const router = useRouter()
const auth = useAuth()
const collapsed = ref(false)
const tenants = ref([])
const tenantId = ref(getActiveTenant())
const nowText = ref('')
const passwordOpen = ref(false)
const passwordSaving = ref(false)
const passwordForm = reactive({ current: '', next: '', confirm: '' })
let clockTimer

const icon = (component) => () => h(component)
const menuItems = [
  { key: '/alerts', icon: icon(AlertOutlined), label: '告警台' },
  { key: '/cases', icon: icon(SafetyCertificateOutlined), label: '调查案件' },
  { key: 'detection', icon: icon(ApartmentOutlined), label: '检测与接入', children: [
    { key: '/rules', label: '检测规则' },
    { key: '/sources', label: '数据源' },
    { key: '/parser-templates', label: '解析规则库' },
  ] },
  { key: '/soar', icon: icon(RobotOutlined), label: 'SOAR 自动化' },
  { key: 'operations', icon: icon(ThunderboltOutlined), label: '运行与治理', children: [
    { key: '/health', icon: icon(BarChartOutlined), label: '数据健康' },
    { key: '/ops/health', label: '运行态扫描' },
    { key: '/notifications', icon: icon(BellOutlined), label: '通知中心' },
  ] },
  { key: 'settings', icon: icon(SettingOutlined), label: '平台设置', children: [
    { key: '/criticality', icon: icon(TagsOutlined), label: '资产关键度' },
    { key: '/rbac/users', icon: icon(TeamOutlined), label: '用户与权限' },
  ] },
]

const selectedKeys = computed(() => [route.meta.menu || route.path])
const roleLabel = computed(() => displayLabel('role', auth.state.user?.role))
const mustChangePassword = computed(() => Boolean(auth.state.user?.passwordChangeRequired))

function updateClock() {
  nowText.value = new Intl.DateTimeFormat('zh-CN', { hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false }).format(new Date())
}

async function loadTenants() {
  try {
    tenants.value = await listMyTenants()
    if (!tenants.value.some((tenant) => tenant.id === tenantId.value) && tenants.value.length) {
      tenantId.value = tenants.value[0].id
      setActiveTenant(tenantId.value)
    }
  } catch (error) {
    message.error(`租户目录加载失败：${error.message}`)
  }
}

function navigate({ key }) {
  if (String(key).startsWith('/')) router.push(key)
}

function changeTenant(value) {
  setActiveTenant(value)
  window.location.reload()
}

async function handleLogout() {
  try {
    await auth.signOut()
  } catch (error) {
    message.warning(`服务端注销失败，本地会话已清除：${error.message}`)
  }
  await router.replace('/login')
}

async function savePassword() {
  if (!passwordForm.current || passwordForm.next.length < 12 || passwordForm.next !== passwordForm.confirm) {
    message.error('请检查当前密码、新密码长度和两次输入是否一致')
    return
  }
  passwordSaving.value = true
  try {
    await changePassword(passwordForm.current, passwordForm.next)
    auth.state.user.passwordChangeRequired = false
    Object.assign(passwordForm, { current: '', next: '', confirm: '' })
    passwordOpen.value = false
    message.success('密码已修改')
  } catch (error) {
    message.error(error.message)
  } finally {
    passwordSaving.value = false
  }
}

onMounted(() => {
  updateClock()
  clockTimer = window.setInterval(updateClock, 1000)
  passwordOpen.value = mustChangePassword.value
  loadTenants()
})
onBeforeUnmount(() => window.clearInterval(clockTimer))
</script>

<style scoped>
.app-layout { min-height: 100vh; }
.app-sider { position: fixed; inset: 0 auto 0 0; z-index: 20; overflow: auto; background: #102b3d; box-shadow: 4px 0 18px rgb(7 27 42 / 14%); }
.brand { height: 76px; display: flex; align-items: center; gap: 11px; padding: 0 18px; color: white; }
.brand.compact { padding-left: 16px; }
.brand-mark { display: grid; place-items: center; width: 40px; height: 40px; flex: 0 0 40px; border-radius: 10px; background: linear-gradient(140deg, #38a6cc, #1d6fa5); font-size: 21px; font-weight: 800; }
.brand strong { display: block; font-size: 18px; letter-spacing: .08em; }
.brand span { display: block; margin-top: 2px; color: #83a8bc; font-size: 9px; letter-spacing: .12em; }
.main-column { min-height: 100vh; transition: margin-left .2s; }
.topbar { position: sticky; top: 0; z-index: 15; height: 64px; padding: 0 24px; display: flex; align-items: center; justify-content: space-between; background: rgb(255 255 255 / 94%); border-bottom: 1px solid #dce5eb; backdrop-filter: blur(10px); }
.topbar-left, .topbar-right { display: flex; align-items: center; gap: 12px; }
.collapse-button { font-size: 18px; }
.route-context span { display: block; color: #7a8a99; font-size: 11px; }
.route-context strong { display: block; color: #163047; font-size: 15px; }
.clock { color: #687b8c; font-variant-numeric: tabular-nums; }
.user-button { color: #29485e; }
.content { padding: 24px 28px 40px; }
</style>
