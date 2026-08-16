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
