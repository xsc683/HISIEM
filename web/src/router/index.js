import { createRouter, createWebHistory } from 'vue-router'
import { hasSession } from '../api/index.js'
import { useAuth } from '../composables/useAuth.js'
import { canAccessRoles, landingRoute } from '../utils/navigation.js'

const MainLayout = () => import('../layouts/MainLayout.vue')

const routes = [
  { path: '/login', name: 'login', component: () => import('../views/login/LoginView.vue'), meta: { public: true, title: '登录' } },
  {
    path: '/', component: MainLayout, redirect: '/overview', children: [
      { path: 'overview', component: () => import('../views/overview/SecurityOverviewView.vue'), meta: { title: '安全运营大屏', menu: '/overview', roles: ['admin', 'analyst', 'audit'] } },
      { path: 'logs', component: () => import('../views/logs/LogSearchView.vue'), meta: { title: '日志检索', menu: '/logs', roles: ['admin', 'analyst', 'audit'] } },
      { path: 'alerts', component: () => import('../views/alerts/AlertListView.vue'), meta: { title: '告警台', menu: '/alerts', roles: ['admin', 'analyst', 'audit'] } },
      { path: 'alerts/:id', component: () => import('../views/alerts/AlertDetailView.vue'), meta: { title: '告警详情', menu: '/alerts', roles: ['admin', 'analyst', 'audit'] } },
      { path: 'cases', component: () => import('../views/cases/CaseListView.vue'), meta: { title: '调查案件', menu: '/cases', roles: ['admin', 'analyst', 'audit'] } },
      { path: 'cases/new', component: () => import('../views/cases/CaseCreateView.vue'), meta: { title: '手动建案', menu: '/cases', roles: ['admin', 'analyst'] } },
      { path: 'cases/:id', component: () => import('../views/cases/CaseDetailView.vue'), meta: { title: '案件详情', menu: '/cases', roles: ['admin', 'analyst', 'audit'] } },
      { path: 'rules', component: () => import('../views/rules/RuleListView.vue'), meta: { title: '检测规则', menu: '/rules' } },
      { path: 'rules/new', component: () => import('../views/rules/RuleFormView.vue'), meta: { title: '新建规则', menu: '/rules', roles: ['admin'] } },
      { path: 'rules/:id/edit', component: () => import('../views/rules/RuleFormView.vue'), meta: { title: '编辑规则', menu: '/rules', roles: ['admin'] } },
      { path: 'rules/:id', component: () => import('../views/rules/RuleDetailView.vue'), meta: { title: '规则详情', menu: '/rules' } },
      { path: 'sources', component: () => import('../views/sources/SourceListView.vue'), meta: { title: '数据源', menu: '/sources' } },
      { path: 'sources/new', component: () => import('../views/sources/SourceFormView.vue'), meta: { title: '新建数据源', menu: '/sources', roles: ['admin', 'ops'] } },
      { path: 'sources/:id', component: () => import('../views/sources/SourceDetailView.vue'), meta: { title: '数据源详情', menu: '/sources' } },
      { path: 'parser-templates', component: () => import('../views/sources/ParserTemplateListView.vue'), meta: { title: '解析规则库', menu: '/parser-templates' } },
      { path: 'soar', redirect: '/soar/playbooks' },
      { path: 'soar/playbooks', component: () => import('../views/soar/PlaybookListView.vue'), meta: { title: 'SOAR Playbook', menu: '/soar/playbooks' } },
      { path: 'soar/playbooks/new', component: () => import('../views/soar/PlaybookEditorView.vue'), meta: { title: '新建 Playbook', menu: '/soar/playbooks', roles: ['admin'] } },
      { path: 'soar/playbooks/:id/edit', component: () => import('../views/soar/PlaybookEditorView.vue'), meta: { title: '编辑 Playbook', menu: '/soar/playbooks', roles: ['admin'] } },
      { path: 'soar/executions', component: () => import('../views/soar/ExecutionListView.vue'), meta: { title: 'SOAR 执行实例', menu: '/soar/executions' } },
      { path: 'soar/executions/:id', component: () => import('../views/soar/ExecutionDetailView.vue'), meta: { title: 'SOAR 执行详情', menu: '/soar/executions' } },
      { path: 'soar/approvals', component: () => import('../views/soar/ApprovalListView.vue'), meta: { title: 'SOAR 人工审批', menu: '/soar/approvals' } },
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
  { path: '/:pathMatch(.*)*', redirect: '/overview' },
]

const router = createRouter({ history: createWebHistory(), routes, scrollBehavior: () => ({ top: 0 }) })
const auth = useAuth()

router.beforeEach(async (to) => {
  if (to.meta.public) {
    if (to.path === '/login' && hasSession()) {
      try { const user = await auth.ensure(); return landingRoute(user?.role) } catch { return true }
    }
    return true
  }
  if (!hasSession()) return { path: '/login', query: { redirect: to.fullPath } }
  try {
    const user = await auth.ensure()
    if (!canAccessRoles(user.role, to.meta.roles)) return landingRoute(user.role)
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
