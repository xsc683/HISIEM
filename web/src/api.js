// 接入层 API 客户端(代理:web/ vite.config.js 把 /api → 后端 8080)

const BASE = '/api'

async function request(path, options) {
  const r = await fetch(BASE + path, options)
  if (!r.ok) {
    throw new Error(`请求失败: ${r.status}`)
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
