import test from 'node:test'
import assert from 'node:assert/strict'
import { canAccessRoles, landingRoute } from './navigation.js'

test('运维角色落到有权限的数据健康页，其余安全角色进入运营大屏', () => {
  assert.equal(landingRoute('ops'), '/health')
  assert.equal(landingRoute('admin'), '/overview')
  assert.equal(landingRoute('analyst'), '/overview')
  assert.equal(landingRoute('audit'), '/overview')
})

test('带角色范围的导航仅对允许角色可见', () => {
  assert.equal(canAccessRoles('ops', ['admin', 'analyst', 'audit']), false)
  assert.equal(canAccessRoles('audit', ['admin', 'analyst', 'audit']), true)
  assert.equal(canAccessRoles('ops'), true)
})
