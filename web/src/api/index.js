const BASE = '/api'
const DEFAULT_TIMEOUT = 12_000

let authToken = localStorage.getItem('siem_token') || ''
let activeTenant = localStorage.getItem('siem_tenant') || 'default'

export class ApiError extends Error {
  constructor(message, status = 0, code = 'NETWORK_ERROR', body = null) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
    this.body = body
  }
}

const segment = (value) => encodeURIComponent(String(value))

function clearSession() {
  authToken = ''
  localStorage.removeItem('siem_token')
}

export function hasSession() {
  return Boolean(authToken)
}

export function setActiveTenant(tenantId) {
  activeTenant = tenantId || 'default'
  localStorage.setItem('siem_tenant', activeTenant)
}

export function getActiveTenant() {
  return activeTenant
}

export async function request(path, options = {}) {
  const controller = new AbortController()
  const timeoutMs = options.timeoutMs || DEFAULT_TIMEOUT
  const timeout = window.setTimeout(() => controller.abort(), timeoutMs)
  const headers = { ...(options.headers || {}) }
  if (authToken) {
    headers.Authorization = `Bearer ${authToken}`
    headers['X-Tenant-ID'] = activeTenant
  }
  let response
  try {
    response = await fetch(BASE + path, {
      ...options,
      headers,
      signal: options.signal || controller.signal,
    })
  } catch (error) {
    if (error?.name === 'AbortError') {
      throw new ApiError(`请求超时（${Math.round(timeoutMs / 1000)} 秒）`, 0, 'TIMEOUT')
    }
    throw new ApiError(error?.message || '网络连接失败')
  } finally {
    window.clearTimeout(timeout)
  }

  const raw = await response.text()
  let body = null
  if (raw.trim()) {
    try {
      body = JSON.parse(raw)
    } catch {
      body = { message: raw.slice(0, 500) }
    }
  }
  if (response.status === 401 && path !== '/auth/login') {
    clearSession()
    window.dispatchEvent(new CustomEvent('siem:unauthorized'))
  }
  if (!response.ok) {
    throw new ApiError(
      body?.message || body?.error || `请求失败（HTTP ${response.status}）`,
      response.status,
      body?.code || `HTTP_${response.status}`,
      body,
    )
  }
  return response.status === 204 || !raw.trim() ? null : body
}

const json = (method, body, extra = {}) => ({
  method,
  headers: { 'Content-Type': 'application/json', ...(extra.headers || {}) },
  body: JSON.stringify(body),
  ...extra,
})

// 认证与租户
export async function login(username, password) {
  const result = await request('/auth/login', json('POST', { username, password }))
  authToken = result.token
  localStorage.setItem('siem_token', authToken)
  return result
}
export async function logout() {
  try {
    await request('/auth/logout', { method: 'POST' })
  } finally {
    clearSession()
  }
}
export const authMe = () => request('/auth/me')
export const changePassword = (currentPassword, newPassword) => request('/auth/password', json('POST', { currentPassword, newPassword }))
export const listUsers = () => request('/auth/users')
export const createUser = (payload) => request('/auth/users', json('POST', payload))
export const updateUserRole = (username, role) => request(`/auth/users/${segment(username)}/role`, json('PUT', { role }))
export const deleteUser = (username) => request(`/auth/users/${segment(username)}`, { method: 'DELETE' })
export const listRoles = () => request('/auth/roles')
export const auditLogs = () => request('/auth/audit-logs')
export const listMyTenants = () => request('/tenants/mine')

// 数据源与解析模板
export const listTemplates = () => request('/parser-templates')
export const testParse = (templateId, sample) => request('/parser-templates/test', json('POST', { templateId, sample }))
export const testCustomParse = (template, sample) => request('/parser-templates/test-custom', json('POST', { template, sample }))
export const saveTemplate = (template) => request('/parser-templates', json('POST', template))
export const listLogSources = () => request('/log-sources')
export const getLogSource = (id) => request(`/log-sources/${segment(id)}`)
export const previewLogSource = (payload) => request('/log-sources/preview', json('POST', payload))
export const createLogSource = (payload) => request('/log-sources', json('POST', payload))
export const activateLogSource = (id) => request(`/log-sources/${segment(id)}/activate`, { method: 'POST' })
export const deactivateLogSource = (id) => request(`/log-sources/${segment(id)}/deactivate`, { method: 'POST' })
export const deleteLogSource = (id) => request(`/log-sources/${segment(id)}`, { method: 'DELETE' })

// 检测规则
export const listDetectionRules = () => request('/detection-rules')
export const getDetectionRule = (id) => request(`/detection-rules/${segment(id)}`)
export const createDetectionRule = (payload) => request('/detection-rules', json('POST', payload))
export const updateDetectionRule = (id, payload) => request(`/detection-rules/${segment(id)}`, json('PUT', payload))
export const getRuleHits = (id, range = '7d') => request(`/detection-rules/${segment(id)}/hits?${new URLSearchParams({ range })}`)
export const toggleRule = (id) => request(`/detection-rules/${segment(id)}/toggle`, { method: 'POST' })
export const deployRules = () => request('/detection-rules/deploy', { method: 'POST', timeoutMs: 60_000 })
export const ruleMitre = () => request('/detection-rules/mitre')

// 告警
export function listAlerts(status) {
  const query = status ? `?${new URLSearchParams({ status })}` : ''
  return request(`/alerts${query}`)
}
export const getAlert = (id) => request(`/alerts/${segment(id)}`)
export const updateAlertStatus = (id, status) => request(`/alerts/${segment(id)}/status`, json('POST', { status }))
export const updateAlertVerdict = (id, verdict) => request(`/alerts/${segment(id)}/verdict`, json('POST', { verdict }))
export const batchAlertStatus = (ids, status) => request('/alerts/batch-status', json('POST', { ids, status }))
export const batchAlertVerdict = (ids, verdict) => request('/alerts/batch-verdict', json('POST', { ids, verdict }))
export const fpRate = () => request('/alerts/fp-rate')

// 案件
export function listCases(status, entity) {
  const query = new URLSearchParams()
  if (status) query.set('status', status)
  if (entity) query.set('entity', entity)
  return request(`/cases${query.size ? `?${query}` : ''}`)
}
export const getCase = (id) => request(`/cases/${segment(id)}`)
export const createCase = (alertIds, title) => request('/cases', json('POST', { alertIds, title }))
export const addCaseAlerts = (id, alertIds) => request(`/cases/${segment(id)}/alerts`, json('POST', { alertIds }))
export const removeCaseAlert = (id, alertId) => request(`/cases/${segment(id)}/alerts/${segment(alertId)}`, { method: 'DELETE' })
export const updateCaseStatus = (id, status, verdict) => request(`/cases/${segment(id)}/status`, json('POST', { status, verdict }))
export const updateCaseMetadata = (id, payload) => request(`/cases/${segment(id)}/metadata`, json('PATCH', payload))
export const updateCaseCollaborators = (id, usernames) => request(`/cases/${segment(id)}/collaborators`, json('POST', { usernames }))
export const caseTimeline = (id, size = 50) => request(`/cases/${segment(id)}/timeline?${new URLSearchParams({ size })}`)
export const deleteCase = (id) => request(`/cases/${segment(id)}`, { method: 'DELETE' })
export function aggregateCases({ windowMinutes = 30, groupByRule = false, threshold = 2, ruleId } = {}) {
  const query = new URLSearchParams({ windowMinutes, groupByRule, threshold })
  if (ruleId) query.set('ruleId', ruleId)
  return request(`/cases/aggregate?${query}`, { method: 'POST' })
}

// 数据健康、运行态与任务
export const dataHealthSources = () => request('/data-health/sources')
export const dataHealthTrend = (id) => request(`/data-health/sources/${segment(id)}/trend`)
export const dataHealthFailures = (id, size = 50) => request(`/data-health/sources/${segment(id)}/failures?${new URLSearchParams({ size })}`)
export const healthScan = () => request('/ops/health-scan', { timeoutMs: 20_000 })
export const listTasks = (size = 50) => request(`/tasks?${new URLSearchParams({ size })}`)
export const getTask = (id) => request(`/tasks/${segment(id)}`)

// 资产关键度
export const listCriticality = () => request('/settings/criticality')
export const searchCriticality = (type, query) => request(`/settings/criticality/search?${new URLSearchParams({ type: type || '', query: query || '' })}`)
export const setCriticality = (type, key, level) => request(`/settings/criticality/${segment(type)}/${segment(key)}`, json('PUT', { level }))
export const deleteCriticality = (type, key) => request(`/settings/criticality/${segment(type)}/${segment(key)}`, { method: 'DELETE' })
export const batchCriticality = (items) => request('/settings/criticality/batch', json('POST', { items }))
export const recalcCriticality = () => request('/settings/criticality/recalc', { method: 'POST' })

// 通知
export const listNotifications = (unread = false) => request(`/notifications${unread ? '?unread=true' : ''}`)
export const readNotification = (id) => request(`/notifications/${segment(id)}/read`, { method: 'POST' })
export const readAllNotifications = () => request('/notifications/read-all', { method: 'POST' })
export const deleteNotification = (id) => request(`/notifications/${segment(id)}`, { method: 'DELETE' })

// SOAR
export const listSoarPlaybooks = () => request('/soar/playbooks')
export const getSoarPlaybook = (id) => request(`/soar/playbooks/${segment(id)}`)
export const createSoarPlaybook = (payload) => request('/soar/playbooks', json('POST', payload))
export const updateSoarPlaybook = (id, payload) => request(`/soar/playbooks/${segment(id)}`, json('PUT', payload))
export const publishSoarPlaybook = (id, revision) => request(`/soar/playbooks/${segment(id)}/publish`, json('POST', { revision }))
export const setSoarPlaybookEnabled = (id, enabled) => request(`/soar/playbooks/${segment(id)}/enabled`, json('PATCH', { enabled }))
export const deleteSoarPlaybook = (id) => request(`/soar/playbooks/${segment(id)}`, { method: 'DELETE' })
export function listSoarExecutions(status, size = 100) {
  const query = new URLSearchParams({ size })
  if (status) query.set('status', status)
  return request(`/soar/executions?${query}`)
}
export const triggerSoarExecution = (payload) => request('/soar/executions', json('POST', payload))
export const getSoarExecution = (id) => request(`/soar/executions/${segment(id)}`)
export const cancelSoarExecution = (id) => request(`/soar/executions/${segment(id)}/cancel`, { method: 'POST' })
export const listSoarApprovals = (status, size = 100) => {
  const query = new URLSearchParams({ size })
  if (status) query.set('status', status)
  return request(`/soar/approvals?${query}`)
}
export const decideSoarApproval = (id, approved, note) => request(`/soar/approvals/${segment(id)}/${approved ? 'approve' : 'reject'}`, json('POST', { note }))
export const getSoarFieldDictionary = (objectType) => request(`/soar/field-dictionary?${new URLSearchParams({ objectType })}`)
export const getSoarActionDictionary = (objectType) => request(`/soar/action-dictionary?${new URLSearchParams({ objectType })}`)
