import { expect, test } from '@playwright/test'

const PROPOSAL_WAITING = {
  proposal_id: 'prop-1',
  revision: 1,
  content_hash: 'a'.repeat(64),
  status: 'WAITING_APPROVAL',
  action_key: 'START_SOAR_PLAYBOOK',
  parameters: { playbook_id: 'pb-9' },
  reason: 'contain the intrusion',
  target_refs: [{ provider: 'hisiem', resource_type: 'alert', address_id: 'a-1', business_id: 'SSH-BF-1' }],
  evidence_ids: ['ev-1'],
  policy_decision: 'REQUIRE_APPROVAL',
  policy_reason: null,
  created_at: '2026-09-11T00:04:00Z',
  approval: {
    request_id: 'req-1', status: 'WAITING_APPROVAL', requested_at: '2026-09-11T00:04:00Z',
    requested_reason: 'contain the intrusion', expected_revision: 1,
    expected_content_hash: 'a'.repeat(64), decision: null,
  },
  execution: null,
}

function workspace(response) {
  return {
    investigation: {
      investigation_id: 'inv-1', tenant_id: 'default', status: 'COMPLETED', phase: null,
      initiated_by: 'analyst', created_at: '2026-09-11T00:00:00Z', started_at: '2026-09-11T00:00:02Z',
      finished_at: '2026-09-11T00:03:12Z', cancelled_at: null, termination_reason: null, current_plan_revision: 1,
    },
    source_alert_ref: { provider: 'hisiem', resource_type: 'alert', address_id: 'a-1', business_id: 'SSH-BF-1' },
    plan_revisions: [], evidence: [], hypotheses: [], findings: [], result: null,
    response,
    tool_activity: [], timeline: [],
  }
}

async function mockApi(page, { role = 'admin', response, onWrite }) {
  await page.addInitScript(() => {
    localStorage.setItem('siem_token', 'e2e-token')
    localStorage.setItem('siem_tenant', 'default')
  })
  await page.route('**/api/**', async (route) => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    // The glob also matches Vite module URLs like /src/api/index.js — never
    // intercept those (or the app cannot load).
    if (!path.startsWith('/api/')) return route.continue()
    const json = (body, status = 200) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) })
    if (path === '/api/auth/me') return json({ username: `e2e-${role}`, role, passwordChangeRequired: false })
    if (path === '/api/tenants/mine') return json([{ id: 'default', name: '默认租户' }])
    if (path === '/api/agent-investigations/inv-1/workspace' && request.method() === 'GET') return json(response)
    if (path.endsWith('/response-approvals/req-1/approve') && request.method() === 'POST') {
      if (onWrite) onWrite(JSON.parse(request.postData() || '{}'))
      return json({ proposal_id: 'prop-1', status: 'APPROVED', decision: 'APPROVE', decided_by: 'e2e-admin', decided_at: '2026-09-11T00:05:00Z', execution_queued: true })
    }
    if (path.endsWith('/response-approvals/req-1/reject') && request.method() === 'POST') {
      if (onWrite) onWrite(JSON.parse(request.postData() || '{}'))
      return json({ proposal_id: 'prop-1', status: 'REJECTED', decision: 'REJECT', decided_by: 'e2e-admin', decided_at: '2026-09-11T00:05:00Z', execution_queued: false })
    }
    return json([])
  })
}

test('响应页签展示建议与待审批提案，批准绑定精确契约', async ({ page }) => {
  const writes = []
  await mockApi(page, {
    role: 'admin',
    onWrite: (body) => writes.push(body),
    response: workspace({
      recommendations: [{ description: '封禁源 IP', reason: '持续失败登录' }],
      proposals: [PROPOSAL_WAITING],
    }),
  })

  await page.goto('/copilot/investigations/inv-1')
  await page.locator('.ant-tabs-tab', { hasText: '响应' }).click()

  // 不变量横幅 + 建议（文本，仅供参考，不可执行）
  await expect(page.locator('.invariant-banner')).toContainText('Agent 只能建议，不能授权')
  await expect(page.locator('.recommendation-list')).toContainText('封禁源 IP')
  await expect(page.locator('.hint')).toContainText('不能直接执行')

  // 类型化提案 + 精确契约
  await expect(page.locator('.proposal-title')).toContainText('启动 SOAR 剧本')
  await expect(page.locator('.proposal-title')).toContainText('待审批')
  await expect(page.locator('.proposal-descriptions')).toContainText('pb-9')
  await expect(page.locator('.hash').first()).toContainText('aaaa')

  // 批准 → 使用精确契约（版本 + 指纹）
  // 注意：ant-design-vue 会在两个汉字之间插入空格，按钮可及名为「批 准」。
  await page.locator('button', { hasText: /批\s*准/ }).first().click()
  await page.locator('.ant-popover button', { hasText: /批\s*准/ }).click()
  await expect.poll(() => writes.length).toBeGreaterThan(0)
  expect(writes[0].expected_revision).toBe(1)
  expect(writes[0].expected_content_hash).toBe('a'.repeat(64))
})

test('审计角色只读：无批准/驳回入口', async ({ page }) => {
  await mockApi(page, {
    role: 'audit',
    response: workspace({ recommendations: [], proposals: [PROPOSAL_WAITING] }),
  })

  await page.goto('/copilot/investigations/inv-1')
  await page.locator('.ant-tabs-tab', { hasText: '响应' }).click()

  await expect(page.locator('.proposal-title')).toContainText('待审批')
  await expect(page.locator('button', { hasText: /批\s*准/ })).toHaveCount(0)
  await expect(page.locator('button', { hasText: /驳\s*回/ })).toHaveCount(0)
  await expect(page.getByText('您没有审批权限')).toBeVisible()
})

test('已批准的提案展示决策与执行结果', async ({ page }) => {
  const approved = {
    ...PROPOSAL_WAITING,
    status: 'APPROVED',
    approval: {
      ...PROPOSAL_WAITING.approval,
      decision: { decision: 'APPROVE', actor_subject_id: 'operator', actor_display_name: 'operator', reason: null, decided_at: '2026-09-11T00:05:00Z' },
    },
    execution: {
      provider: 'hisiem', status: 'SUCCEEDED', submitted_at: '2026-09-11T00:05:01Z',
      last_observed_at: '2026-09-11T00:05:20Z', external_execution_id: 'exec-77',
      started_at: '2026-09-11T00:05:02Z', finished_at: '2026-09-11T00:05:20Z',
      safe_result: {}, safe_error_code: null, safe_error_message: null,
    },
  }
  await mockApi(page, { role: 'analyst', response: workspace({ recommendations: [], proposals: [approved] }) })

  await page.goto('/copilot/investigations/inv-1')
  await page.locator('.ant-tabs-tab', { hasText: '响应' }).click()

  await expect(page.locator('.decision-block')).toContainText('已批准')
  await expect(page.locator('.decision-block')).toContainText('operator')
  await expect(page.locator('.ant-tag', { hasText: '成功' })).toBeVisible()
  await expect(page.getByText('exec-77')).toBeVisible()
})
