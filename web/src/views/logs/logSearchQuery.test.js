import test from 'node:test'
import assert from 'node:assert/strict'
import { buildLogSearchRequest, normalizeFieldOptions, validateLogFilters } from './logSearchQuery.js'

test('buildLogSearchRequest serializes valid filters and maps them to conditions', () => {
  const request = buildLogSearchRequest({
    logic: 'OR',
    page: 2,
    size: 50,
    sort: 'asc',
    from: '2026-08-24T00:00:00+08:00',
    to: '2026-08-24T23:59:59+08:00',
    filters: [
      { field: 'source.ip', operator: 'is', value: '198.51.100.1' },
      { field: 'event.category', operator: 'is_one_of', value: ['authentication', '', 'network'] },
      { field: 'user.name', operator: 'not_exist', value: 'ignored' },
      { field: '', operator: 'contain', value: 'ignored' },
    ],
  })

  assert.deepEqual(request, {
    page: 2,
    size: 50,
    sort: 'asc',
    logic: 'OR',
    from: '2026-08-24T00:00:00+08:00',
    to: '2026-08-24T23:59:59+08:00',
    conditions: [
      { field: 'source.ip', operator: 'is', value: '198.51.100.1' },
      { field: 'event.category', operator: 'is_one_of', value: ['authentication', 'network'] },
      { field: 'user.name', operator: 'not_exist' },
    ],
  })
})

test('buildLogSearchRequest removes invalid values and clamps paging', () => {
  assert.deepEqual(buildLogSearchRequest({
    page: -4,
    size: 500,
    logic: 'invalid',
    filters: [
      { field: 'message', operator: 'contain', value: '   ' },
      { field: 'tags', operator: 'not_is_one_of', value: 'alpha, beta, ' },
      { field: 'host.name', operator: 'unsupported', value: 'server' },
    ],
  }), {
    page: 0,
    size: 200,
    sort: 'desc',
    logic: 'AND',
    conditions: [{ field: 'tags', operator: 'not_is_one_of', value: ['alpha', 'beta'] }],
  })
})

test('normalizeFieldOptions accepts dictionary objects, removes duplicates and keeps types', () => {
  assert.deepEqual(normalizeFieldOptions({ fields: [
    { name: '@timestamp', type: 'date', operators: ['is', 'exist', 'unknown'] },
    { name: 'source.ip', label: '源 IP', type: 'ip', operators: ['is', 'not_is'] },
    { name: 'source.ip', type: 'keyword' },
    'message',
    null,
  ] }), [
    { value: '@timestamp', label: '@timestamp', type: 'date', operators: ['is', 'exist'] },
    { value: 'source.ip', label: '源 IP', type: 'ip', operators: ['is', 'not_is'] },
    { value: 'message', label: 'message', type: '', operators: [] },
  ])
})

test('validateLogFilters permits a blank catch-all row but rejects incomplete conditions', () => {
  assert.equal(validateLogFilters([{ field: undefined, operator: 'is', value: '' }]), '')
  assert.equal(validateLogFilters([{ field: '', operator: 'is', value: 'root' }]), '条件 1 尚未选择字段')
  assert.equal(validateLogFilters([{ field: 'user.name', operator: 'is', value: '' }]), '条件 1 尚未填写匹配值')
  assert.equal(validateLogFilters([{ field: 'user.name', operator: 'exist', value: '' }]), '')
})
