import { expect, test } from '@playwright/test'

const WORKSPACE = {
  investigation: {
    investigation_id: 'inv-1', tenant_id: 'default', status: 'COMPLETED', phase: null,
    initiated_by: 'analyst', created_at: '2026-09-11T00:00:00Z', started_at: '2026-09-11T00:00:02Z',
    finished_at: '2026-09-11T00:03:12Z', cancelled_at: null, termination_reason: null, current_plan_revision: 1,
  },
  source_alert_ref: { provider: 'hisiem', resource_type: 'alert', address_id: 'a-1', business_id: 'SSH-BF-1' },
  plan_revisions: [{
    id: 'plan-1', revision: 1, generated_by: 'agent', created_at: '2026-09-11T00:00:10Z',
    steps: [
      { step_id: 's1', ordinal: 1, objective: '检索 SSH 失败登录', status: 'PENDING' },
      { step_id: 's2', ordinal: 2, objective: '关联主机与用户', status: 'PENDING' },
    ],
  }],
  evidence: [{
    evidence_id: 'ev-1', collected_at: '2026-09-11T00:01:00Z', observed_at: '2026-09-11T00:00:30Z',
    summary: '5 分钟内 6 次失败登录', source: { type: 'search', provider: 'hisiem', operation: 'search_events' },
    source_tool_invocation_id: 'ti-1', observation: { count: 6, window: '5m' },
    entity_refs: [{ kind: 'ip', value: '203.0.113.9' }], raw_reference: { index: 'siem-events-*' },
    content_hash: 'sha256:abc123', dedup_key: 'dk-1',
  }],
  hypotheses: [{
    hypothesis_id: 'h-1', statement: '存在外部暴力破解', status: 'SUPPORTED', assessment_revision: 1,
    latest_assessment: {
      revision: 1, status: 'SUPPORTED', reason_summary: '失败次数超过阈值', created_at: '2026-09-11T00:01:30Z',
      evidence_relations: [{ evidence_id: 'ev-1', relation: 'SUPPORTS' }],
    },
  }],
  findings: [{
    finding_id: 'f-1', statement: '检测到外部 SSH 暴力破解', created_at: '2026-09-11T00:02:00Z',
    evidence_citations: ['ev-1'], in_result: true,
  }],
  result: {
    result_id: 'r-1', verdict: { disposition: 'MALICIOUS', summary: '确认外部暴力破解攻击', confidence: 0.85 },
    created_at: '2026-09-11T00:03:10Z', finding_ids: ['f-1'],
    uncertainties: [{ description: '无法确认是否成功登录', missing_information: 'auth.success 日志' }],
    attack_mappings: [{ framework: 'MITRE ATT&CK', technique_id: 'T1110', name: 'Brute Force', version: 'v14', source: 'agent' }],
    response_recommendations: [{ description: '封禁源 IP', reason: '持续失败登录' }],
  },
  tool_activity: [{
    invocation_id: 'ti-1', tool_name: 'hisiem.search_events', status: 'SUCCEEDED',
    started_at: '2026-09-11T00:00:40Z', finished_at: '2026-09-11T00:00:41Z',
    error_code: null, safe_error_message: null, duration_ms: 1000,
  }],
  timeline: [
    { kind: 'INVESTIGATION_CREATED', occurred_at: '2026-09-11T00:00:00Z', title: '调查已创建', status: null, ref_type: 'investigation', ref_id: 'inv-1', safe_metadata: {} },
    { kind: 'TOOL_SUCCEEDED', occurred_at: '2026-09-11T00:00:41Z', title: '工具调用完成', status: 'SUCCEEDED', ref_type: 'tool', ref_id: 'ti-1', safe_metadata: {} },
    { kind: 'EVIDENCE_RECORDED', occurred_at: '2026-09-11T00:01:00Z', title: '记录证据', status: null, ref_type: 'evidence', ref_id: 'ev-1', safe_metadata: {} },
    { kind: 'RESULT_FINALIZED', occurred_at: '2026-09-11T00:03:10Z', title: '生成结论', status: null, ref_type: 'result', ref_id: 'r-1', safe_metadata: {} },
  ],
}

async function mockApi(page, extra) {
  await page.addInitScript(() => {
    localStorage.setItem('siem_token', 'e2e-token')
    localStorage.setItem('siem_tenant', 'default')
  })
  await page.route('**/api/**', async (route) => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    if (!path.startsWith('/api/')) return route.continue()
    const json = (body, status = 200) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) })
    if (path === '/api/auth/me') return json({ username: 'e2e-admin', role: 'admin', passwordChangeRequired: false })
    if (path === '/api/tenants/mine') return json([{ id: 'default', name: '默认租户' }])
    if (extra && await extra({ path, request, json })) return
    return json([])
  })
}

test('AI 调查工作台按服务端读模型渲染结论、证据与时间线', async ({ page }) => {
  await mockApi(page, async ({ path, request, json }) => {
    if (path === '/api/agent-investigations/inv-1/workspace' && request.method() === 'GET') {
      await json(WORKSPACE)
      return true
    }
    return false
  })

  await page.goto('/copilot/investigations/inv-1')
  await expect(page.getByRole('heading', { name: 'AI 调查工作台' })).toBeVisible()
  await expect(page.locator('.header-strip')).toContainText('已完成')
  await expect(page.locator('.header-strip')).toContainText('analyst')
  await expect(page.locator('.verdict-card')).toContainText('恶意')
  await expect(page.locator('.verdict-card')).toContainText('85%')
  await expect(page.locator('.verdict-card')).toContainText('确认外部暴力破解攻击')
  await expect(page.locator('.finding-card')).toContainText('检测到外部 SSH 暴力破解')
  await expect(page.locator('.finding-card')).toContainText('已纳入结论')
  await expect(page.getByText('MITRE ATT&CK')).toBeVisible()
  // 响应建议是「建议文本」，并指向正式的响应流程（提案 → 策略 → 人工审批），可执行动作不在概览里。
  await expect(page.getByText('建议文本 · 不可执行')).toBeVisible()
  await expect(page.locator('.readonly-note')).toContainText('不是执行命令')
  await expect(page.locator('.readonly-note')).toContainText('经策略判定与人工审批')

  // 发现 → 证据：引用芯片按持久 ID 解析并打开证据抽屉
  await page.locator('.finding-card .citation-chip').first().click()
  const drawer = page.locator('.ant-drawer-content')
  await expect(drawer).toContainText('完整性')
  await expect(drawer).toContainText('sha256:abc123')
  await expect(drawer).toContainText('5 分钟内 6 次失败登录')
  await page.locator('.ant-drawer-close').click()
  await expect(page.locator('.ant-drawer-content')).not.toBeVisible()

  // 证据页签
  await expect(page.locator('.evidence-card')).toContainText('5 分钟内 6 次失败登录')
  await expect(page.locator('.evidence-card')).toContainText('被 1 个发现引用')

  // 调查过程页签：计划 / 假设 / 工具活动
  await page.getByRole('tab', { name: '调查过程' }).click()
  await expect(page.getByText('检索 SSH 失败登录')).toBeVisible()
  await expect(page.getByText('存在外部暴力破解')).toBeVisible()
  await expect(page.getByText('hisiem.search_events')).toBeVisible()

  // 时间线页签与过滤
  await page.getByRole('tab', { name: '时间线' }).click()
  await expect(page.locator('.ant-timeline-item')).toHaveCount(4)
  await page.locator('.ant-radio-button-wrapper', { hasText: '工具' }).click()
  await expect(page.locator('.ant-timeline-item')).toHaveCount(1)
  await expect(page.locator('.ant-timeline-item')).toContainText('工具调用完成')
})

test('进行中的调查渲染部分状态且结论区不伪造结果', async ({ page }) => {
  const running = {
    ...WORKSPACE,
    investigation: { ...WORKSPACE.investigation, investigation_id: 'inv-2', status: 'RUNNING', phase: 'INVESTIGATING', finished_at: null },
    result: null,
    findings: [],
  }
  await mockApi(page, async ({ path, request, json }) => {
    if (path === '/api/agent-investigations/inv-2/workspace' && request.method() === 'GET') {
      await json(running)
      return true
    }
    return false
  })

  await page.goto('/copilot/investigations/inv-2')
  await expect(page.locator('.header-strip')).toContainText('调查中')
  await expect(page.getByText('调查进行中，结论尚未生成')).toBeVisible()
  await expect(page.locator('.verdict-card')).toHaveCount(0)
})

test('告警详情按服务端权威状态提供继续入口', async ({ page }) => {
  const active = { ...WORKSPACE.investigation, investigation_id: 'inv-9', status: 'RUNNING' }
  await mockApi(page, async ({ path, request, json }) => {
    if (path === '/api/alerts/a-1' && request.method() === 'GET') {
      await json({ _id: 'a-1', 'alert.rule_name': 'SSH 暴力破解', 'alert.status': 'open', 'alert.entity': '203.0.113.9' })
      return true
    }
    if (path === '/api/alerts/a-1/agent-investigation' && request.method() === 'GET') {
      await json({ active, latest: active })
      return true
    }
    if (path === '/api/agent-investigations/inv-9/workspace' && request.method() === 'GET') {
      await json({ ...WORKSPACE, investigation: active, result: null, findings: [] })
      return true
    }
    return false
  })

  await page.goto('/alerts/a-1')
  const continueButton = page.getByRole('button', { name: '继续 Agent 调查' })
  await expect(continueButton).toBeVisible()
  await continueButton.click()
  await expect(page).toHaveURL(/\/copilot\/investigations\/inv-9$/)
})
