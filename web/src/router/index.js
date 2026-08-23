import { createRouter, createWebHistory } from 'vue-router'
import { hasSession } from '../api/index.js'
import { useAuth } from '../composables/useAuth.js'

const MainLayout = () => import('../layouts/MainLayout.vue')

const routes = [
  { path: '/login', name: 'login', component: () => import('../views/login/LoginView.vue'), meta: { public: true, title: '登录' } },
  {
    path: '/', component: MainLayout, redirect: '/alerts', children: [
      { path: 'alerts', component: () => import('../views/alerts/AlertListView.vue'), meta: { title: '告警台', menu: '/alerts' } },
      { path: 'alerts/:id', component: () => import('../views/alerts/AlertDetailView.vue'), meta: { title: '告警详情', menu: '/alerts' } },
      { path: 'cases', component: () => import('../views/cases/CaseListView.vue'), meta: { title: '调查案件', menu: '/cases' } },
      { path: 'cases/new', component: () => import('../views/cases/CaseCreateView.vue'), meta: { title: '手动建案', menu: '/cases' } },
      { path: 'cases/:id', component: () => import('../views/cases/CaseDetailView.vue'), meta: { title: '案件详情', menu: '/cases' } },
      { path: 'rules', component: () => import('../views/rules/RuleListView.vue'), meta: { title: '检测规则', menu: '/rules' } },
      { path: 'rules/new', component: () => import('../views/rules/RuleFormView.vue'), meta: { title: '新建规则', menu: '/rules', roles: ['admin'] } },
      { path: 'rules/:id/edit', component: () => import('../views/rules/RuleFormView.vue'), meta: { title: '编辑规则', menu: '/rules', roles: ['admin'] } },
      { path: 'rules/:id', component: () => import('../views/rules/RuleDetailView.vue'), meta: { title: '规则详情', menu: '/rules' } },
      { path: 'sources', component: () => import('../views/sources/SourceListView.vue'), meta: { title: '数据源', menu: '/sources' } },
      { path: 'sources/new', component: () => import('../views/sources/SourceFormView.vue'), meta: { title: '新建数据源', menu: '/sources', roles: ['admin', 'ops'] } },
      { path: 'sources/:id', component: () => import('../views/sources/SourceDetailView.vue'), meta: { title: '数据源详情', menu: '/sources' } },
      { path: 'parser-templates', component: () => import('../views/sources/ParserTemplateListView.vue'), meta: { title: '解析规则库', menu: '/parser-templates' } },
      { path: 'soar', component: () => import('../views/soar/SoarOverviewView.vue'), meta: { title: 'SOAR 自动化', menu: '/soar' } },
      { path: 'soar/designer', component: () => import('../views/soar/SoarDesignerView.vue'), meta: { title: 'Playbook 设计器', menu: '/soar' } },
      { path: 'soar/executions/:id', component: () => import('../views/soar/SoarExecutionDetailView.vue'), meta: { title: 'SOAR 执行详情', menu: '/soar' } },
      { path: 'health', component: () => import('../views/health/DataHealthListView.vue'), meta: { title: '数据健康', menu: '/health' } },
      { path: 'health/:sourceId', component: () => import('../views/health/DataHealthDetailView.vue'), meta: { title: '数据健康详情', menu: '/health' } },
      { path: 'ops/health', component: () => import('../views/ops/OpsHealthView.vue'), meta: { title: '运行态扫描', menu: '/ops/health' } },
      { path: 'notifications', component: () => import('../views/notifications/NotificationListView.vue'), meta: { title: '通知中心', menu: '/notifications' } },
      { path: 'criticality', component: () => import('../views/criticality/CriticalityListView.vue'), meta: { title: '资产关键度', menu: '/criticality' } },
      { path: 'criticality/new', component: () => import('../views/criticality/CriticalityFormView.vue'), meta: { title: '新增资产关键度', menu: '/criticality', roles: ['admin'] } },
      { path: 'criticality/:type/:key/edit', component: () => import('../views/criticality/CriticalityFormView.vue'), meta: { title: '编辑资产关键度', menu: '/criticality', roles: ['admin'] } },
      { path: 'rbac', redirect: '/rbac/users' },
      { path: 'rbac/users', component: () => import('../views/rbac/UserListView.vue'), meta: { title: '用户与权限', menu: '/rbac/users', roles: ['admin'] } },
      { path: 'rbac/users/new', component: () => import('../views/rbac/UserCreateView.vue'), meta: { title: '新建用户', menu: '/rbac/users', roles: ['admin'] } },
      { path: 'rbac/users/:username', component: () => import('../views/rbac/UserDetailView.vue'), meta: { title: '用户详情', menu: '/rbac/users', roles: ['admin'] } },
      { path: 'rbac/roles', component: () => import('../views/rbac/RoleMatrixView.vue'), meta: { title: '角色权限矩阵', menu: '/rbac/users', roles: ['admin', 'audit'] } },
      { path: 'rbac/audit', component: () => import('../views/rbac/AuditLogView.vue'), meta: { title: '审计日志', menu: '/rbac/users', roles: ['admin'] } },
      { path: 'wizard', redirect: '/sources/new' },
    ],
  },
  { path: '/:pathMatch(.*)*', redirect: '/alerts' },
]

const router = createRouter({ history: createWebHistory(), routes, scrollBehavior: () => ({ top: 0 }) })
const auth = useAuth()

router.beforeEach(async (to) => {
  if (to.meta.public) {
    if (to.path === '/login' && hasSession()) {
      try { await auth.ensure(); return '/alerts' } catch { return true }
    }
    return true
  }
  if (!hasSession()) return { path: '/login', query: { redirect: to.fullPath } }
  try {
    const user = await auth.ensure()
    if (to.meta.roles && !to.meta.roles.includes(user.role)) return '/alerts'
    return true
  } catch {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
})

window.addEventListener('siem:unauthorized', () => {
  auth.reset()
  if (router.currentRoute.value.path !== '/login') router.replace({ path: '/login', query: { redirect: router.currentRoute.value.fullPath } })
})

export default router
