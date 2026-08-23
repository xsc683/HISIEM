// 接入层 API 客户端(代理:web/ vite.config.js 把 /api → 后端 8080)

const BASE = '/api'
const REQUEST_TIMEOUT_MS = 10000

// 所有来自数据或用户输入的路径段都必须编码，避免 IP、用户名和案件 ID
// 中的 `:`, `/`, `#` 等字符改变 API 路由边界。
const pathSegment = (value) => encodeURIComponent(String(value))

// 登录 token(Story 08 RBAC),本地持久化;所有请求自动携带
let authToken = localStorage.getItem('siem_token') || ''
let activeTenant = localStorage.getItem('siem_tenant') || 'default'

function authHeaders(extra) {
  const h = { ...(extra || {}) }
  if (authToken) h.Authorization = `Bearer ${authToken}`
  if (authToken) h['X-Tenant-ID'] = activeTenant
  return h
}

export function setActiveTenant(tenantId) {
  activeTenant = tenantId || 'default'
  localStorage.setItem('siem_tenant', activeTenant)
}

export function getActiveTenant() {
  return activeTenant
}

export function listMyTenants() {
  return request('/tenants/mine')
}

async function request(path, options) {
  const controller = new AbortController()
  const timeout = window.setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS)
  let r
  try {
    r = await fetch(BASE + path, {
      ...options,
      signal: options?.signal || controller.signal,
      headers: authHeaders(options?.headers),
    })
  } catch (e) {
    if (e?.name === 'AbortError') throw new Error(`请求超时（${REQUEST_TIMEOUT_MS / 1000}s）`)
    throw e
  } finally {
    window.clearTimeout(timeout)
  }
  if (r.status === 401 && path !== '/auth/login') {
    authToken = ''
    localStorage.removeItem('siem_token')
  }
  const raw = await r.text()
  let body = null
  if (raw.trim()) {
    try { body = JSON.parse(raw) } catch {
      body = { message: raw.slice(0, 200) }
    }
  }
  if (!r.ok) {
    throw new Error(body?.message || body?.error || `请求失败: ${r.status}`)
  }
  return r.status === 204 || !raw.trim() ? null : body
}

export function listTemplates() {
  return request('/parser-templates')
}

export function testParse(templateId, sample) {
  return request('/parser-templates/test', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ templateId, sample }),
  })
}

export function previewLogSource(payload) {
  return request('/log-sources/preview', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  })
}

// ---- 数据源生命周期(Story 01) ----

export function listLogSources() {
  return request('/log-sources')
}

export function getLogSource(id) {
  return request(`/log-sources/${pathSegment(id)}`)
}

export function createLogSource(payload) {
  return request('/log-sources', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  })
}

export function activateLogSource(id) {
  return request(`/log-sources/${pathSegment(id)}/activate`, { method: 'POST' })
}

export function deactivateLogSource(id) {
  return request(`/log-sources/${pathSegment(id)}/deactivate`, { method: 'POST' })
}

export function deleteLogSource(id) {
  return request(`/log-sources/${pathSegment(id)}`, { method: 'DELETE' })
}

// ---- 模板保存(Story 02) ----

export function saveTemplate(template) {
  return request('/parser-templates', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(template),
  })
}

// ---- 检测规则管理(Story 03) ----

export function listDetectionRules() {
  return request('/detection-rules')
}

export function getRuleHits(id) {
  return request(`/detection-rules/${pathSegment(id)}/hits`)
}

export function toggleRule(id) {
  return request(`/detection-rules/${pathSegment(id)}/toggle`, { method: 'POST' })
}

export function deployRules() {
  return request('/detection-rules/deploy', { method: 'POST' })
}

export function ruleMitre() {
  return request('/detection-rules/mitre')
}

// ---- 数据健康(Story 05) ----

export function dataHealthSources() {
  return request('/data-health/sources')
}

export function dataHealthTrend(id) {
  return request(`/data-health/sources/${pathSegment(id)}/trend`)
}

export function dataHealthFailures(id, size) {
  return request(`/data-health/sources/${pathSegment(id)}/failures?size=${size || 50}`)
}

// ---- 系统设置·资产关键度(Story 06) ----

export function listCriticality() {
  return request('/settings/criticality')
}

export function setCriticality(type, key, level) {
  return request(`/settings/criticality/${pathSegment(type)}/${pathSegment(key)}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ level }),
  })
}

export function deleteCriticality(type, key) {
  return request(`/settings/criticality/${pathSegment(type)}/${pathSegment(key)}`, { method: 'DELETE' })
}

export function recalcCriticality() {
  return request('/settings/criticality/recalc', { method: 'POST' })
}

export function searchCriticality(type, query) {
  const q = new URLSearchParams()
  if (type) q.set('type', type)
  if (query) q.set('query', query)
  return request('/settings/criticality/search?' + q.toString())
}

export function batchCriticality(items) {
  return request('/settings/criticality/batch', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ items }),
  })
}

// ---- 认证与权限(Story 08 RBAC) ----

export function login(username, password) {
  return request('/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password }),
  }).then((r) => {
    authToken = r.token
    localStorage.setItem('siem_token', r.token)
    return r
  })
}

export function logout() {
  return request('/auth/logout', { method: 'POST' }).catch(() => {}).finally(() => {
    authToken = ''
    localStorage.removeItem('siem_token')
  })
}

export function changePassword(currentPassword, newPassword) {
  return request('/auth/password', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ currentPassword, newPassword }),
  })
}

export function authMe() {
  return request('/auth/me')
}

export function listUsers() {
  return request('/auth/users')
}

export function createUser(payload) {
  return request('/auth/users', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  })
}

export function deleteUser(username) {
  return request(`/auth/users/${pathSegment(username)}`, { method: 'DELETE' })
}

export function updateUserRole(username, role) {
  return request(`/auth/users/${pathSegment(username)}/role`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ role }),
  })
}

export function listRoles() {
  return request('/auth/roles')
}

export function auditLogs() {
  return request('/auth/audit-logs')
}

// ---- 通知中心(Story 10) ----

export function listNotifications(unread) {
  return request(`/notifications${unread ? '?unread=true' : ''}`)
}

export function readNotification(id) {
  return request(`/notifications/${pathSegment(id)}/read`, { method: 'POST' })
}

export function readAllNotifications() {
  return request('/notifications/read-all', { method: 'POST' })
}

export function deleteNotification(id) {
  return request(`/notifications/${pathSegment(id)}`, { method: 'DELETE' })
}

// ---- 告警台(Story 04) ----

export function listAlerts(status) {
  const query = status ? `?${new URLSearchParams({ status }).toString()}` : ''
  return request(`/alerts${query}`)
}

export function getAlert(id) {
  return request(`/alerts/${pathSegment(id)}`)
}

export function updateAlertStatus(id, status) {
  return request(`/alerts/${pathSegment(id)}/status`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ status }),
  })
}

export function updateAlertVerdict(id, verdict) {
  return request(`/alerts/${pathSegment(id)}/verdict`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ verdict }),
  })
}

export function batchAlertStatus(ids, status) {
  return request('/alerts/batch-status', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ ids, status }),
  })
}

export function batchAlertVerdict(ids, verdict) {
  return request('/alerts/batch-verdict', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ ids, verdict }),
  })
}

export function fpRate() {
  return request('/alerts/fp-rate')
}

// ---- 调查台·案件聚合(Story 07) ----

export function listCases(status, entity) {
  const q = new URLSearchParams()
  if (status) q.set('status', status)
  if (entity) q.set('entity', entity)
  const s = q.toString()
  return request(`/cases${s ? `?${s}` : ''}`)
}

export function getCase(id) {
  return request(`/cases/${pathSegment(id)}`)
}

export function createCase(alertIds, title) {
  return request('/cases', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ alertIds, title }),
  })
}

export function addCaseAlerts(id, alertIds) {
  return request(`/cases/${pathSegment(id)}/alerts`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ alertIds }),
  })
}

export function removeCaseAlert(id, alertId) {
  return request(`/cases/${pathSegment(id)}/alerts/${pathSegment(alertId)}`, { method: 'DELETE' })
}

export function updateCaseStatus(id, status, verdict) {
  return request(`/cases/${pathSegment(id)}/status`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ status, verdict }),
  })
}

export function caseTimeline(id, size) {
  return request(`/cases/${pathSegment(id)}/timeline?size=${size || 50}`)
}

export function deleteCase(id) {
  return request(`/cases/${pathSegment(id)}`, { method: 'DELETE' })
}

export function aggregateCases() {
  return aggregateCasesWithOptions({})
}

export function aggregateCasesWithOptions({ windowMinutes, groupByRule, threshold, ruleId } = {}) {
  const q = new URLSearchParams()
  if (windowMinutes) q.set('windowMinutes', windowMinutes)
  if (groupByRule) q.set('groupByRule', 'true')
  if (threshold) q.set('threshold', threshold)
  if (ruleId) q.set('ruleId', ruleId)
  return request('/cases/aggregate?' + q.toString(), { method: 'POST' })
}

export function updateCaseMetadata(id, payload) {
  return request(`/cases/${pathSegment(id)}/metadata`, {
    method: 'PATCH', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  })
}

export function updateCaseCollaborators(id, usernames) {
  return request('/cases/' + pathSegment(id) + '/collaborators', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ usernames }),
  })
}

// ---- 运维扫描与后台任务 ----

export function healthScan() {
  return request('/ops/health-scan')
}

export function listTasks(size) {
  return request(`/tasks?size=${size || 50}`)
}

export function getTask(id) {
  return request(`/tasks/${pathSegment(id)}`)
}

// ---- SOAR Playbook 与执行 ----

export function listSoarPlaybooks(resourceType) {
  const query = resourceType ? `?${new URLSearchParams({ resourceType }).toString()}` : ''
  return request(`/soar/playbooks${query}`)
}

export function reloadSoarPlaybooks() {
  return request('/soar/playbooks/reload', { method: 'POST' })
}

export function listSoarExecutions(size = 50) {
  return request(`/soar/executions?${new URLSearchParams({ size }).toString()}`)
}

export function getSoarExecution(id) {
  return request(`/soar/executions/${pathSegment(id)}`)
}

export function getSoarExecutionEvents(id) {
  return request(`/soar/executions/${pathSegment(id)}/events`)
}

export function listSoarAutomationRules() {
  return request('/soar/automation-rules')
}

export function scanSoarAutomationRules() {
  return request('/soar/automation-rules/scan', { method: 'POST' })
}

export function listSoarConnectors() {
  return request('/soar/connectors')
}

export function getSoarConnectorRuntime() {
  return request('/soar/connectors/runtime')
}

export function listSoarRevisions(state) {
  const query = state ? `?${new URLSearchParams({ state }).toString()}` : ''
  return request(`/soar/designer/revisions${query}`)
}

export function createSoarDraft(definition, layout) {
  return request('/soar/designer/drafts', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ definition, layout }),
  })
}

export function updateSoarDraft(playbookId, revision, definition, layout, lockVersion) {
  return request(`/soar/designer/${pathSegment(playbookId)}/revisions/${revision}`, {
    method: 'PUT', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ definition, layout, lockVersion }),
  })
}

export function submitSoarRevision(playbookId, revision) {
  return request(`/soar/designer/${pathSegment(playbookId)}/revisions/${revision}/submit`, { method: 'POST' })
}

export function reviewSoarRevision(playbookId, revision, approved, note) {
  return request(`/soar/designer/${pathSegment(playbookId)}/revisions/${revision}/review`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ approved, note }),
  })
}

export function publishSoarRevision(playbookId, revision, rolloutPercentage) {
  return request(`/soar/designer/${pathSegment(playbookId)}/revisions/${revision}/publish`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ rolloutPercentage }),
  })
}

export function startSoarExecution(playbookId, resourceType, resourceId) {
  return request('/soar/executions', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ playbookId, resourceType, resourceId }),
  })
}

export function decideSoarApproval(id, approved) {
  return request(`/soar/executions/${pathSegment(id)}/approval`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ approved }),
  })
}

export function retrySoarExecution(id) {
  return request(`/soar/executions/${pathSegment(id)}/retry`, { method: 'POST' })
}

export function cancelSoarExecution(id) {
  return request(`/soar/executions/${pathSegment(id)}/cancel`, { method: 'POST' })
}

export function pauseSoarExecution(id) {
  return request(`/soar/executions/${pathSegment(id)}/pause`, { method: 'POST' })
}

export function resumeSoarExecution(id) {
  return request(`/soar/executions/${pathSegment(id)}/resume`, { method: 'POST' })
}
