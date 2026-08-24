import { request } from '../../api/index.js'
import { buildLogSearchRequest } from './logSearchQuery.js'

export const fetchLogFields = () => request('/log-search/fields')

export async function searchLogs(criteria = {}) {
  const body = await request('/log-search', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(buildLogSearchRequest(criteria)),
  })
  return normalizeSearchResponse(body)
}

export function normalizeSearchResponse(body) {
  return {
    items: Array.isArray(body?.items) ? body.items : [],
    page: Math.max(0, Number(body?.page) || 0),
    size: Math.max(1, Number(body?.size) || 25),
    total: Math.max(0, Number(body?.total) || 0),
    tookMs: Number.isFinite(Number(body?.tookMs)) ? Number(body.tookMs) : null,
  }
}

