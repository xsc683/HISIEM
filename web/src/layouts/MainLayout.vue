<template>
  <a-layout class="app-layout">
    <div v-if="isMobile && mobileNavOpen" class="mobile-nav-mask" aria-hidden="true" @click="closeMobileNav" />
    <a-layout-sider :collapsed="siderCollapsed" :width="244" :collapsed-width="72" :trigger="null" class="app-sider"
      :class="{ 'mobile-open': mobileNavOpen }">
      <div class="sider-shell">
        <div class="brand" :class="{ compact: siderCollapsed }">
          <div class="brand-mark">H</div>
          <div v-if="!siderCollapsed" class="brand-copy"><strong>HISIEM</strong><span>SECURITY OPERATIONS</span></div>
        </div>
        <a-tooltip v-if="!isMobile" :title="siderCollapsed ? '展开导航' : '收起导航'" placement="right">
          <button type="button" class="sider-toggle" :aria-label="siderCollapsed ? '展开导航' : '收起导航'" @click="toggleSider">
            <MenuUnfoldOutlined v-if="siderCollapsed" /><MenuFoldOutlined v-else />
          </button>
        </a-tooltip>
        <div class="nav-scroll">
          <a-menu mode="inline" theme="dark" :items="menuItems" :selected-keys="selectedKeys" @click="navigate" />
        </div>
        <div class="sider-footer">
          <a-dropdown v-model:open="accountMenuOpen" placement="topLeft" :trigger="['click']" overlay-class-name="account-dropdown-overlay">
            <button type="button" class="account-trigger" :class="{ compact: siderCollapsed }" aria-label="打开账户菜单">
              <span class="account-avatar">{{ userInitial }}</span>
              <span v-if="!siderCollapsed" class="account-copy">
                <span class="account-name">{{ auth.state.user?.username || '当前用户' }}<em>{{ roleLabel }}</em></span>
                <span class="account-context">{{ tenantName }} · {{ nowText }}</span>
              </span>
              <DownOutlined v-if="!siderCollapsed" class="account-chevron" />
            </button>
            <template #overlay>
              <div class="account-popover" @click.stop>
                <div class="account-popover-head">
                  <span class="account-avatar large">{{ userInitial }}</span>
                  <div><strong>{{ auth.state.user?.username || '当前用户' }}</strong><span>{{ roleLabel }}</span></div>
                </div>
                <div class="account-popover-meta">
                  <span>当前租户</span>
                  <a-select v-if="tenants.length > 1" v-model:value="tenantId" size="small" class="tenant-select" @change="changeTenant"
                    :options="tenants.map((tenant) => ({ value: tenant.id, label: tenant.name }))" />
                  <strong v-else>{{ tenantName }}</strong>
                </div>
                <div class="account-popover-time"><ClockCircleOutlined /> 平台本地时间 {{ nowText }}</div>
                <div class="account-popover-actions">
                  <button type="button" @click="openPassword"><KeyOutlined /><span>修改密码</span></button>
                  <button type="button" class="logout-action" @click="handleLogout"><LogoutOutlined /><span>退出登录</span></button>
                </div>
              </div>
            </template>
          </a-dropdown>
        </div>
      </div>
    </a-layout-sider>
    <a-layout :style="{ marginLeft: isMobile ? '0' : (siderCollapsed ? '72px' : '244px') }" class="main-column">
      <header v-if="isMobile" class="mobile-header">
        <button type="button" class="mobile-menu-button" aria-label="打开导航" @click="mobileNavOpen = true"><MenuUnfoldOutlined /></button>
        <div class="mobile-brand"><span class="brand-mark small">H</span><strong>HISIEM</strong></div>
        <span class="mobile-page-title">{{ route.meta.title || '安全运营平台' }}</span>
      </header>
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
import { computed, h, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import {
  AlertOutlined, ApiOutlined, ApartmentOutlined, BarChartOutlined, BellOutlined, DownOutlined,
  ClockCircleOutlined, DashboardOutlined, KeyOutlined, LogoutOutlined, MenuFoldOutlined, MenuUnfoldOutlined, RobotOutlined,
  SafetyCertificateOutlined, SearchOutlined, SettingOutlined, TagsOutlined, TeamOutlined, ThunderboltOutlined,
} from '@ant-design/icons-vue'
import { changePassword, getActiveTenant, listMyTenants, setActiveTenant } from '../api/index.js'
import { useAuth } from '../composables/useAuth.js'
import { displayLabel } from '../utils/display.js'
import { kibanaUrl as resolveKibanaUrl } from '../utils/runtimeUrls.js'
import { canAccessRoles } from '../utils/navigation.js'

const route = useRoute()
const router = useRouter()
const auth = useAuth()
const collapsed = ref(false)
const isMobile = ref(false)
const mobileNavOpen = ref(false)
const tenants = ref([])
const tenantId = ref(getActiveTenant())
const nowText = ref('')
const passwordOpen = ref(false)
const accountMenuOpen = ref(false)
const passwordSaving = ref(false)
const passwordForm = reactive({ current: '', next: '', confirm: '' })
let clockTimer
let mobileMedia
const kibanaUrl = resolveKibanaUrl()

const icon = (component) => () => h(component)
const menuDefinitions = [
  { key: '/overview', icon: icon(DashboardOutlined), label: '安全运营大屏', roles: ['admin', 'analyst', 'audit'] },
  { key: '/logs', icon: icon(SearchOutlined), label: '日志检索', roles: ['admin', 'analyst', 'audit'] },
  { key: '/alerts', icon: icon(AlertOutlined), label: '告警台', roles: ['admin', 'analyst', 'audit'] },
  { key: '/cases', icon: icon(SafetyCertificateOutlined), label: '调查案件', roles: ['admin', 'analyst', 'audit'] },
  { key: 'detection', icon: icon(ApartmentOutlined), label: '检测与接入', children: [
    { key: '/rules', label: '检测规则' },
    { key: '/sources', label: '数据源' },
    { key: '/parser-templates', label: '解析规则库' },
  ] },
  { key: 'soar', icon: icon(RobotOutlined), label: 'SOAR 自动化', children: [
    { key: '/soar/playbooks', label: 'Playbook' },
    { key: '/soar/executions', label: '执行实例' },
    { key: '/soar/approvals', label: '人工审批' },
  ] },
  { key: 'operations', icon: icon(ThunderboltOutlined), label: '运行与治理', children: [
    { key: '/health', icon: icon(BarChartOutlined), label: '数据健康' },
    { key: '/ops/health', label: '运行态扫描' },
    { key: 'external:kibana', icon: icon(ApiOutlined), label: 'Kibana 分析 ↗' },
    { key: '/notifications', icon: icon(BellOutlined), label: '通知中心' },
  ] },
  { key: 'settings', icon: icon(SettingOutlined), label: '平台设置', children: [
    { key: '/criticality', icon: icon(TagsOutlined), label: '资产关键度' },
    { key: '/rbac/users', icon: icon(TeamOutlined), label: '用户与权限' },
  ] },
]

const menuItems = computed(() => filterMenu(menuDefinitions, auth.state.user?.role))

function filterMenu(items, role) {
  return items.flatMap((item) => {
    if (!canAccessRoles(role, item.roles)) return []
    const children = item.children ? filterMenu(item.children, role) : undefined
    if (item.children && !children.length) return []
    return [{ ...item, ...(children ? { children } : {}) }]
  })
}

const selectedKeys = computed(() => [route.meta.menu || route.path])
const siderCollapsed = computed(() => !isMobile.value && collapsed.value)
const roleLabel = computed(() => displayLabel('role', auth.state.user?.role))
const mustChangePassword = computed(() => Boolean(auth.state.user?.passwordChangeRequired))
const tenantName = computed(() => tenants.value.find((tenant) => tenant.id === tenantId.value)?.name || tenantId.value || '默认租户')
const userInitial = computed(() => String(auth.state.user?.username || 'U').slice(0, 1).toUpperCase())

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
  accountMenuOpen.value = false
  mobileNavOpen.value = false
  if (key === 'external:kibana') {
    window.open(kibanaUrl, '_blank', 'noopener,noreferrer')
    return
  }
  if (String(key).startsWith('/')) router.push(key)
}

function toggleSider() {
  accountMenuOpen.value = false
  collapsed.value = !collapsed.value
}

function closeMobileNav() {
  mobileNavOpen.value = false
  accountMenuOpen.value = false
}

function openPassword() {
  accountMenuOpen.value = false
  passwordOpen.value = true
}

function closeAccountOnEscape(event) {
  if (event.key === 'Escape') {
    accountMenuOpen.value = false
    mobileNavOpen.value = false
  }
}

function syncViewport(event) {
  isMobile.value = event.matches
  if (!event.matches) mobileNavOpen.value = false
}

function changeTenant(value) {
  setActiveTenant(value)
  window.location.reload()
}

async function handleLogout() {
  accountMenuOpen.value = false
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
  mobileMedia = window.matchMedia('(max-width: 900px)')
  syncViewport(mobileMedia)
  mobileMedia.addEventListener('change', syncViewport)
  window.addEventListener('keydown', closeAccountOnEscape)
})
onBeforeUnmount(() => {
  window.clearInterval(clockTimer)
  mobileMedia?.removeEventListener('change', syncViewport)
  window.removeEventListener('keydown', closeAccountOnEscape)
})
watch(() => route.fullPath, () => closeMobileNav())
</script>

<style scoped>
.app-layout { min-height: 100vh; background: transparent; }
.app-sider { position: fixed; inset: 0 auto 0 0; z-index: 20; overflow: visible; background: #0d2637; box-shadow: 8px 0 28px rgb(7 27 42 / 15%); }
.sider-shell { position: relative; display: flex; height: 100vh; flex-direction: column; overflow: visible; }
.brand { height: 76px; display: flex; align-items: center; gap: 11px; flex: 0 0 76px; padding: 0 18px; color: white; border-bottom: 1px solid rgb(255 255 255 / 6%); }
.brand.compact { justify-content: center; padding: 0; }
.brand-mark { display: grid; place-items: center; width: 38px; height: 38px; flex: 0 0 38px; border: 1px solid rgb(145 222 248 / 22%); border-radius: 11px; background: linear-gradient(145deg, #3ca8cf, #1a679a); font-size: 20px; font-weight: 800; box-shadow: 0 9px 25px rgb(6 82 122 / 32%); }
.brand-copy strong { display: block; font-size: 17px; letter-spacing: .09em; }
.brand-copy span { display: block; margin-top: 2px; color: #7ea4b8; font-size: 9px; letter-spacing: .13em; }
.sider-toggle { position: absolute; z-index: 3; top: 25px; right: -13px; display: grid; width: 27px; height: 27px; place-items: center; padding: 0; color: #58778a; border: 1px solid #d9e3e9; border-radius: 50%; background: #fff; box-shadow: 0 4px 13px rgb(18 46 64 / 18%); cursor: pointer; transition: color .18s, border-color .18s, transform .18s; }
.sider-toggle:hover { color: #176e9f; border-color: #7db5d2; transform: scale(1.06); }
.nav-scroll { min-height: 0; flex: 1; overflow-x: hidden; overflow-y: auto; padding: 10px 7px; scrollbar-width: thin; scrollbar-color: #294a5d transparent; }
.nav-scroll :deep(.ant-menu) { border-inline-end: 0 !important; background: transparent; }
.nav-scroll :deep(.ant-menu-item), .nav-scroll :deep(.ant-menu-submenu-title) { margin-block: 2px; border-radius: 8px; }
.nav-scroll :deep(.ant-menu-item-selected) { box-shadow: inset 3px 0 #62c6e9; }
.sider-footer { flex: 0 0 auto; padding: 10px; border-top: 1px solid rgb(255 255 255 / 7%); background: rgb(6 24 35 / 28%); }
.account-trigger { display: flex; width: 100%; min-height: 58px; align-items: center; gap: 10px; padding: 8px 9px; color: #dcecf3; text-align: left; border: 1px solid rgb(130 185 207 / 13%); border-radius: 10px; background: rgb(255 255 255 / 4%); cursor: pointer; transition: background .18s, border-color .18s; }
.account-trigger:hover { border-color: rgb(106 196 230 / 32%); background: rgb(255 255 255 / 7%); }
.account-trigger.compact { justify-content: center; min-height: 48px; padding: 5px; }
.account-avatar { display: inline-grid; width: 34px; height: 34px; flex: 0 0 34px; place-items: center; color: #eafbff; border: 1px solid rgb(128 217 246 / 28%); border-radius: 10px; background: linear-gradient(145deg, #246f98, #174c6c); font-size: 14px; font-style: normal; font-weight: 750; }
.account-avatar.large { width: 40px; height: 40px; flex-basis: 40px; border-radius: 11px; }
.account-copy { min-width: 0; flex: 1; }
.account-name, .account-context { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.account-name { color: #e8f4f8; font-size: 13px; font-weight: 650; }
.account-name em { margin-left: 6px; color: #82aabd; font-size: 10px; font-style: normal; font-weight: 500; }
.account-context { margin-top: 3px; color: #789bad; font-size: 10px; font-variant-numeric: tabular-nums; }
.account-chevron { color: #7193a5; font-size: 9px; }
.main-column { min-height: 100vh; background: transparent; transition: margin-left .2s; }
.content { min-height: 100vh; padding: 22px 28px 38px; }
.mobile-header, .mobile-nav-mask { display: none; }
:global(.account-dropdown-overlay) { padding-bottom: 5px; }
:global(.account-popover) { width: 286px; overflow: hidden; border: 1px solid #dbe4ea; border-radius: 12px; background: #fff; box-shadow: 0 18px 48px rgb(16 44 63 / 20%); }
:global(.account-popover-head) { display: flex; align-items: center; gap: 11px; padding: 14px 15px; background: linear-gradient(135deg, #f3f8fb, #fff); border-bottom: 1px solid #e7edf1; }
:global(.account-popover-head strong), :global(.account-popover-head span) { display: block; }
:global(.account-popover-head strong) { color: #173247; font-size: 14px; }
:global(.account-popover-head div > span) { margin-top: 2px; color: #718695; font-size: 11px; }
:global(.account-popover-meta) { display: flex; min-height: 43px; align-items: center; justify-content: space-between; gap: 12px; padding: 8px 15px; color: #6d8190; font-size: 12px; }
:global(.account-popover-meta strong) { color: #29485d; font-size: 12px; }
:global(.tenant-select) { width: 150px; }
:global(.account-popover-time) { padding: 0 15px 10px; color: #8a9aa6; font-size: 11px; font-variant-numeric: tabular-nums; }
:global(.account-popover-time svg) { margin-right: 5px; }
:global(.account-popover-actions) { display: grid; grid-template-columns: 1fr 1fr; gap: 7px; padding: 10px; border-top: 1px solid #e8edf1; background: #fafcfd; }
:global(.account-popover-actions button) { display: flex; min-height: 34px; align-items: center; justify-content: center; gap: 6px; color: #35566c; border: 1px solid #dce5ea; border-radius: 7px; background: #fff; cursor: pointer; }
:global(.account-popover-actions button:hover) { color: #176e9f; border-color: #8fbdd4; background: #f5fbfe; }
:global(.account-popover-actions .logout-action:hover) { color: #b83f49; border-color: #e0aeb3; background: #fff7f7; }
@media (max-width: 1300px) { .content { padding-inline: 22px; } }
@media (max-width: 900px) {
  .app-sider { z-index: 51; width: 244px !important; min-width: 244px !important; max-width: 244px !important; transform: translateX(-105%); box-shadow: 14px 0 38px rgb(3 20 31 / 28%); transition: transform .2s ease; }
  .app-sider.mobile-open { transform: translateX(0); }
  .mobile-nav-mask { position: fixed; z-index: 50; inset: 0; display: block; background: rgb(5 20 30 / 46%); backdrop-filter: blur(2px); }
  .mobile-header { position: sticky; z-index: 30; top: 0; display: flex; height: 54px; align-items: center; gap: 11px; padding: 0 14px; border-bottom: 1px solid #dce5ea; background: rgb(255 255 255 / 94%); box-shadow: 0 3px 13px rgb(20 50 68 / 7%); backdrop-filter: blur(12px); }
  .mobile-menu-button { display: grid; width: 36px; height: 36px; place-items: center; padding: 0; color: #27536d; border: 1px solid #d7e2e8; border-radius: 8px; background: #f8fafb; cursor: pointer; }
  .mobile-menu-button:active { background: #eaf2f6; transform: translateY(1px); }
  .mobile-brand { display: flex; align-items: center; gap: 7px; color: #173247; letter-spacing: .04em; }
  .brand-mark.small { width: 27px; height: 27px; flex-basis: 27px; border-radius: 8px; font-size: 14px; }
  .mobile-page-title { min-width: 0; margin-left: auto; overflow: hidden; color: #718492; font-size: 11px; text-overflow: ellipsis; white-space: nowrap; }
  .main-column { margin-left: 0 !important; }
  .content { padding: 14px 14px 28px; }
}
@media (max-width: 520px) {
  .mobile-page-title { display: none; }
  .content { padding-inline: 10px; }
}
</style>
