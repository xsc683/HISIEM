#!/usr/bin/env node

// 只使用 Node 20+ 标准库。运行前设置 SIEM_TOKEN，并提供一个真实告警/案件 ID。
const baseUrl = process.env.SIEM_BASE_URL || 'http://localhost:8080'
const token = process.env.SIEM_TOKEN
const tenant = process.env.SIEM_TENANT || 'default'
const playbookId = process.env.SIEM_SOAR_PLAYBOOK || 'alert-high-risk-triage'
const resourceType = process.env.SIEM_RESOURCE_TYPE || 'alert'
const resourceId = process.env.SIEM_RESOURCE_ID
const total = Number(process.env.SIEM_LOAD_TOTAL || 1000)
const concurrency = Number(process.env.SIEM_LOAD_CONCURRENCY || 50)

if (!token || !resourceId) {
  throw new Error('必须设置 SIEM_TOKEN 和 SIEM_RESOURCE_ID；脚本不会使用默认凭据或虚构资源')
}
if (!Number.isInteger(total) || total < 1 || total > 100000
  || !Number.isInteger(concurrency) || concurrency < 1 || concurrency > 1000) {
  throw new Error('SIEM_LOAD_TOTAL/CONCURRENCY 超出安全范围')
}

const latencies = []
const errors = []
let cursor = 0
const started = performance.now()

async function worker() {
  while (true) {
    const index = cursor++
    if (index >= total) return
    const begin = performance.now()
    try {
      const response = await fetch(`${baseUrl}/api/soar/executions`, {
        method: 'POST',
        headers: {
          Authorization: `Bearer ${token}`,
          'X-Tenant-ID': tenant,
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({ playbookId, resourceType, resourceId }),
      })
      if (response.status !== 202) throw new Error(`HTTP ${response.status}: ${(await response.text()).slice(0, 200)}`)
      latencies.push(performance.now() - begin)
    } catch (error) {
      errors.push({ index, message: error.message })
    }
  }
}

await Promise.all(Array.from({ length: concurrency }, worker))
latencies.sort((a, b) => a - b)
const elapsed = performance.now() - started
const percentile = (p) => latencies[Math.min(latencies.length - 1, Math.floor(latencies.length * p))] || 0
const result = {
  total,
  accepted: latencies.length,
  failed: errors.length,
  concurrency,
  elapsedMs: Math.round(elapsed),
  throughputPerSecond: Number((latencies.length / (elapsed / 1000)).toFixed(2)),
  latencyMs: { p50: Math.round(percentile(0.5)), p95: Math.round(percentile(0.95)), p99: Math.round(percentile(0.99)) },
  sampleErrors: errors.slice(0, 10),
}

console.log(JSON.stringify(result, null, 2))
if (errors.length) process.exitCode = 1
