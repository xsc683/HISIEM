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

const EVIDENCE = [
  {
    evidence_id: 'ev-1', summary: 'sshd 暴力破解失败登录',
    source: { type: 'log', provider: 'hisiem', operation: 'search_events' },
    observed_at: '2026-09-11T00:02:00Z', collected_at: '2026-09-11T00:02:01Z', entity_refs: [],
  },
  {
    evidence_id: 'ev-2', summary: '源 IP 资产关键度',
    source: { type: 'asset', provider: 'hisiem', operation: 'asset_lookup' },
    observed_at: '2026-09-11T00:02:10Z', collected_at: '2026-09-11T00:02:11Z', entity_refs: [],
  },
]

// 只有「已发布 + 已启用」的剧本可以被选；草稿与停用剧本必须不出现。
const PLAYBOOKS = [
  { id: 'pb-contain', name: '隔离主机', status: 'published', enabled: true },
  { id: 'pb-draft', name: '草稿剧本', status: 'draft', enabled: false },
  { id: 'pb-off', name: '已停用剧本', status: 'disabled', enabled: false },
]

function workspace(response, overrides = {}) {
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
    ...overrides,
  }
}

async function mockApi(page, { role = 'admin', response, onWrite, playbooks = PLAYBOOKS } = {}) {
  await page.addInitScript(() => {
    localStorage.setItem('siem_token', 'e2e-token')
    localStorage.setItem('siem_tenant', 'default')
  })
  let current = response
  await page.route('**/api/**', async (route) => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    // The glob also matches Vite module URLs like /src/api/index.js — never
    // intercept those (or the app cannot load).
    if (!path.startsWith('/api/')) return route.continue()
    const json = (body, status = 200) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) })
    if (path === '/api/auth/me') return json({ username: `e2e-${role}`, role, passwordChangeRequired: false })
    if (path === '/api/tenants/mine') return json([{ id: 'default', name: '默认租户' }])
    if (path === '/api/soar/playbooks' && request.method() === 'GET') return json(playbooks)
    if (path === '/api/agent-investigations/inv-1/workspace' && request.method() === 'GET') {
      return json(typeof current === 'function' ? current() : current)
    }
    if (path === '/api/agent-investigations/inv-1/response-proposals' && request.method() === 'POST') {
      const body = JSON.parse(request.postData() || '{}')
      if (onWrite) onWrite(body)
      return json({ proposal: { proposal_id: 'prop-new', status: 'WAITING_APPROVAL' } }, 201)
    }
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

// 下拉面板会留在 DOM 里，且可能同时存在多个；按 popup-class-name 精确定位到某一个下拉，
// 并排除已隐藏的那个。
const optionsOf = (page, name) =>
  page.locator(`.proposal-${name}-dropdown:not(.ant-select-dropdown-hidden) .ant-select-item-option`)

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

test('已批准但尚未提交：显示等待提交，不编造外部执行 ID', async ({ page }) => {
  // §6：APPROVED 且没有 provider 执行引用时，本地只有一份持久化提交意图，
  // 页面必须显示「已批准 / 等待提交」，且不得出现任何外部执行编号。
  const awaiting = {
    ...PROPOSAL_WAITING,
    status: 'APPROVED',
    approval: {
      ...PROPOSAL_WAITING.approval,
      decision: { decision: 'APPROVE', actor_subject_id: 'operator', actor_display_name: 'operator', reason: null, decided_at: '2026-09-11T00:05:00Z' },
    },
    execution: null,
  }
  await mockApi(page, { role: 'analyst', response: workspace({ recommendations: [], proposals: [awaiting] }) })

  await page.goto('/copilot/investigations/inv-1')
  await page.locator('.ant-tabs-tab', { hasText: '响应' }).click()

  await expect(page.locator('.awaiting-submission')).toContainText('已批准 / 等待提交')
  await expect(page.locator('.awaiting-submission')).toContainText('不会显示任何外部执行编号')
  // 没有执行快照，整个执行快照区块就不得渲染：这里断言只有该区块才有的字段标签，
  // 否则「没有外部执行 ID」的断言在区块被 v-if 关掉时会空洞地通过。
  await expect(page.getByText('执行方')).toHaveCount(0)
  await expect(page.getByText('最近观测')).toHaveCount(0)
  await expect(page.getByText('外部执行 ID')).toHaveCount(0)
  await expect(page.getByText('prop-1')).toHaveCount(0)
})

test('有界提案流程：从空状态到提交，浏览器只提交有界契约', async ({ page }) => {
  const writes = []
  // 提交成功后服务端返回的读模型里出现新提案；页面刷新后必须看到它。
  const created = {
    ...PROPOSAL_WAITING,
    proposal_id: 'prop-new',
    parameters: { playbook_id: 'pb-contain' },
    reason: '确认暴力破解，需要隔离主机',
  }
  let submitted = false
  await mockApi(page, {
    role: 'analyst',
    onWrite: (body) => { writes.push(body); submitted = true },
    response: () => workspace(
      { recommendations: [], proposals: submitted ? [created] : [] },
      { evidence: EVIDENCE },
    ),
  })

  // 1) 打开一个已完成、尚无提案、有证据的调查
  await page.goto('/copilot/investigations/inv-1')
  // 2) 进入响应用签
  await page.locator('.ant-tabs-tab', { hasText: '响应' }).click()
  // 3) 有界表单出现
  const form = page.locator('.proposal-form')
  await expect(form).toBeVisible()
  await expect(page.locator('.bounded-banner')).toContainText('目标与动作都不可编辑')

  // 4) 动作固定只读
  await expect(page.getByTestId('proposal-action')).toHaveValue('START_SOAR_PLAYBOOK')
  await expect(page.getByTestId('proposal-action')).toBeDisabled()
  // 5) 目标来自调查的来源告警，只读
  await expect(page.getByTestId('proposal-target')).toHaveValue('hisiem / alert / SSH-BF-1')
  await expect(page.getByTestId('proposal-target')).toBeDisabled()

  // 6) 剧本下拉只有「已发布 + 已启用」的选项，且没有自由文本输入
  await page.getByTestId('proposal-playbook').click()
  await expect(optionsOf(page, 'playbook')).toHaveCount(1)
  await expect(optionsOf(page, 'playbook').first()).toContainText('隔离主机')
  await expect(optionsOf(page, 'playbook')).not.toContainText('草稿剧本')
  await expect(optionsOf(page, 'playbook')).not.toContainText('已停用剧本')
  await page.keyboard.type('任意自由文本剧本')
  await expect(optionsOf(page, 'playbook')).toHaveCount(0)
  await expect(page.getByText('没有已发布且启用的剧本可供选择')).toBeVisible()
  await page.getByTestId('proposal-playbook').locator('input').fill('')
  await page.keyboard.press('Escape')

  // 7) 选择剧本
  await page.getByTestId('proposal-playbook').click()
  await optionsOf(page, 'playbook').first().click()
  await expect(page.getByTestId('proposal-playbook')).toContainText('隔离主机')

  // 8) 证据只能从本次调查的证据里选
  await page.getByTestId('proposal-evidence').click()
  await expect(optionsOf(page, 'evidence')).toHaveCount(2)
  await optionsOf(page, 'evidence').first().click()
  await page.keyboard.press('Escape')
  await expect(page.getByTestId('proposal-evidence')).toContainText('sshd 暴力破解失败登录')

  // 9) 有界理由
  await page.getByTestId('proposal-reason').fill('确认暴力破解，需要隔离主机')

  // 10) 提交
  await page.getByRole('button', { name: '提交提案' }).click()
  await expect.poll(() => writes.length).toBe(1)

  // 11) 请求体只有有界契约：没有目标、没有租户、没有操作人
  expect(writes[0]).toEqual({
    action_key: 'START_SOAR_PLAYBOOK',
    evidence_ids: ['ev-1'],
    parameters: { playbook_id: 'pb-contain' },
    reason: '确认暴力破解，需要隔离主机',
  })
  for (const forbidden of ['target', 'tenant_id', 'actor', 'provider', 'resource_type', 'address_id', 'business_id']) {
    expect(forbidden in writes[0]).toBe(false)
  }

  // 12) 提交后回到等待审批状态
  await expect(page.locator('.proposal-title')).toContainText('待审批')
  // 13) 本次调查已有提案，创建入口随之消失（不会重复提交）
  await expect(form).toHaveCount(0)
})

test('有界提案流程的否定用例：缺项不提交，越权/越界状态不出现表单', async ({ page }) => {
  const writes = []
  await mockApi(page, {
    role: 'admin',
    onWrite: (body) => writes.push(body),
    response: workspace({ recommendations: [], proposals: [] }, { evidence: EVIDENCE }),
  })

  await page.goto('/copilot/investigations/inv-1')
  await page.locator('.ant-tabs-tab', { hasText: '响应' }).click()

  // 缺剧本 → 拒绝提交（本地校验，不发请求）
  await expect(page.locator('.proposal-form')).toBeVisible()
  await page.getByRole('button', { name: '提交提案' }).click()
  await expect(page.getByText('请选择一个已发布且启用的 SOAR 剧本')).toBeVisible()
  expect(writes).toHaveLength(0)

  // 选了剧本但没选证据 → 仍然拒绝
  await page.getByTestId('proposal-playbook').click()
  await optionsOf(page, 'playbook').first().click()
  await page.getByTestId('proposal-reason').fill('理由')
  await page.getByRole('button', { name: '提交提案' }).click()
  await expect(page.getByText('至少需要选择一条本次调查的证据')).toBeVisible()
  expect(writes).toHaveLength(0)
})

test('越权角色与不满足前提的调查没有创建入口', async ({ page }) => {
  const base = { recommendations: [], proposals: [] }
  const cases = [
    { role: 'audit', response: workspace(base, { evidence: EVIDENCE }), why: '审计角色只读' },
    { role: 'analyst', response: workspace(base, { evidence: [] }), why: '没有可引用的证据' },
    {
      role: 'analyst',
      response: workspace({ recommendations: [], proposals: [PROPOSAL_WAITING] }, { evidence: EVIDENCE }),
      why: '本次调查已有提案',
    },
    {
      role: 'analyst',
      response: workspace(base, {
        evidence: EVIDENCE,
        investigation: { ...workspace(base).investigation, status: 'RUNNING', finished_at: null },
      }),
      why: '调查尚未完成',
    },
  ]
  for (const item of cases) {
    await page.unrouteAll({ behavior: 'wait' })
    await mockApi(page, { role: item.role, response: item.response })
    await page.goto('/copilot/investigations/inv-1')
    await page.locator('.ant-tabs-tab', { hasText: '响应' }).click()
    await expect(page.locator('.proposal-form'), item.why).toHaveCount(0)
  }
})
