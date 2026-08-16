// 接入层 API 客户端(代理:web/ vite.config.js 把 /api → 后端 8080)

const BASE = '/api'

// 登录 token(Story 08 RBAC),本地持久化;所有请求自动携带
let authToken = localStorage.getItem('siem_token') || ''

function authHeaders(extra) {
  const h = { ...(extra || {}) }
  if (authToken) h.Authorization = `Bearer ${authToken}`
  return h
}

async function request(path, options) {
  const r = await fetch(BASE + path, { ...options, headers: authHeaders(options?.headers) })
  if (r.status === 401 && path !== '/auth/login') {
    authToken = ''
    localStorage.removeItem('siem_token')
  }
  if (!r.ok) {
    const body = await r.json().catch(() => ({}))
    throw new Error(body.error || `请求失败: ${r.status}`)
  }
  return r.json()
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
  return request(`/log-sources/${id}`)
}

export function createLogSource(payload) {
  return request('/log-sources', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  })
}

export function activateLogSource(id) {
  return request(`/log-sources/${id}/activate`, { method: 'POST' })
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
  return request(`/detection-rules/${id}/hits`)
}

export function toggleRule(id) {
  return request(`/detection-rules/${id}/toggle`, { method: 'POST' })
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
  return request(`/data-health/sources/${id}/trend`)
}

export function dataHealthFailures(id, size) {
  return request(`/data-health/sources/${id}/failures?size=${size || 50}`)
}

// ---- 系统设置·资产关键度(Story 06) ----

export function listCriticality() {
  return request('/settings/criticality')
}

export function setCriticality(type, key, level) {
  return request(`/settings/criticality/${type}/${key}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ level }),
  })
}

export function deleteCriticality(type, key) {
  return request(`/settings/criticality/${type}/${key}`, { method: 'DELETE' })
}

export function recalcCriticality() {
  return request('/settings/criticality/recalc', { method: 'POST' })
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
  authToken = ''
  localStorage.removeItem('siem_token')
  return request('/auth/logout', { method: 'POST' }).catch(() => {})
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
  return request(`/auth/users/${username}`, { method: 'DELETE' })
}

export function updateUserRole(username, role) {
  return request(`/auth/users/${username}/role`, {
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
  return request(`/notifications/${id}/read`, { method: 'POST' })
}

export function readAllNotifications() {
  return request('/notifications/read-all', { method: 'POST' })
}

export function deleteNotification(id) {
  return request(`/notifications/${id}`, { method: 'DELETE' })
}

// ---- 告警台(Story 04) ----

export function listAlerts(status) {
  return request(`/alerts${status ? `?status=${status}` : ''}`)
}

export function getAlert(id) {
  return request(`/alerts/${id}`)
}

export function updateAlertStatus(id, status) {
  return request(`/alerts/${id}/status`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ status }),
  })
}

export function updateAlertVerdict(id, verdict) {
  return request(`/alerts/${id}/verdict`, {
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
  return request(`/cases/${id}`)
}

export function createCase(alertIds, title) {
  return request('/cases', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ alertIds, title }),
  })
}

export function addCaseAlerts(id, alertIds) {
  return request(`/cases/${id}/alerts`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ alertIds }),
  })
}

export function removeCaseAlert(id, alertId) {
  return request(`/cases/${id}/alerts/${alertId}`, { method: 'DELETE' })
}

export function updateCaseStatus(id, status, verdict) {
  return request(`/cases/${id}/status`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ status, verdict }),
  })
}

export function caseTimeline(id, size) {
  return request(`/cases/${id}/timeline?size=${size || 50}`)
}

export function deleteCase(id) {
  return request(`/cases/${id}`, { method: 'DELETE' })
}

export function aggregateCases() {
  return request('/cases/aggregate', { method: 'POST' })
}
