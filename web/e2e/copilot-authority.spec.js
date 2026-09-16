// Stage D — 分析师工作台的权威语义 / 知识来源 / 状态表达 浏览器验收。
//
// 这些用例只断言 UI 如何表达服务端权威，以及 loading/error/empty/stale 与窄屏行为；
// 响应生命周期（审批/提交/执行/ATTENTION_REQUIRED）已由 response-workflow.spec.js 覆盖。

import { expect, test } from '@playwright/test'

const PLATFORM_EVIDENCE = {
  evidence_id: 'ev-1',
  collected_at: '2026-09-11T00:01:00Z',
  observed_at: '2026-09-11T00:00:30Z',
  summary: '5 分钟内 6 次失败登录',
  source: { type: 'HISIEM_LOG_SEARCH', provider: 'hisiem', operation: 'search_events' },
  source_tool_invocation_id: 'ti-1',
  observation: { count: 6, window: '5m' },
  entity_refs: [{ kind: 'ip', value: '203.0.113.9' }],
  raw_reference: { index: 'siem-events-*' },
  content_hash: 'sha256:abc123',
  dedup_key: 'dk-1',
}

const KNOWLEDGE_EVIDENCE = {
  evidence_id: 'ev-2',
  collected_at: '2026-09-11T00:01:30Z',
  observed_at: null,
  summary: 'SSH 暴力破解处置指引（片段）',
  source: { type: 'KNOWLEDGE', provider: 'knowledge', operation: 'retrieve_security_guidance' },
  source_tool_invocation_id: 'ti-2',
  observation: {
    title: 'SSH 暴力破解处置指引',
    excerpt: '建议在确认失败登录来源后封禁源 IP 并强制重置受影响账户。',
    source_kind: 'runbook',
    source_version: '2026.09',
  },
  entity_refs: [],
  raw_reference: {
    citation_identity: {
      citation_id: 'cit-7f3a', document_id: 'doc-1',
      document_version_id: 'ver-1', chunk_id: 'chk-1', content_hash: 'b'.repeat(64),
    },
    // 检索执行元数据：UI 可以把它当作技术溯源，但绝不允许当作权威/置信度展示。
    retrieval_provenance: {
      mode: 'HYBRID', profile_id: 'prof-1', retrieved_at: '2026-09-11T00:01:20Z',
      rank: 1, rrf_score: 0.9137,
    },
  },
  content_hash: 'sha256:def456',
  dedup_key: 'dk-2',
}

const ATTACK_EVIDENCE = {
  evidence_id: 'ev-3',
  collected_at: '2026-09-11T00:01:45Z',
  observed_at: null,
  summary: 'T1110 Brute Force',
  source: { type: 'KNOWLEDGE', provider: 'knowledge', operation: 'resolve_attack_technique' },
  source_tool_invocation_id: 'ti-3',
  observation: {
    technique_id: 'T1110', framework: 'MITRE ATT&CK', name: 'Brute Force',
    authoritative_release: 'v14.1',
  },
  entity_refs: [],
  raw_reference: { technique_id: 'T1110', framework: 'MITRE ATT&CK', authoritative_release: 'v14.1' },
  content_hash: 'sha256:ghi789',
  dedup_key: 'dk-3',
}

function workspace(overrides = {}) {
  return {
    investigation: {
      investigation_id: 'inv-1', tenant_id: 'default', status: 'COMPLETED', phase: null,
      initiated_by: 'analyst', created_at: '2026-09-11T00:00:00Z', started_at: '2026-09-11T00:00:02Z',
      finished_at: '2026-09-11T00:03:12Z', cancelled_at: null, termination_reason: null,
      current_plan_revision: 1,
    },
    source_alert_ref: { provider: 'hisiem', resource_type: 'alert', address_id: 'a-1', business_id: 'SSH-BF-1' },
    plan_revisions: [],
    evidence: [PLATFORM_EVIDENCE, KNOWLEDGE_EVIDENCE, ATTACK_EVIDENCE],
    hypotheses: [],
    findings: [
      { finding_id: 'f-1', statement: '检测到外部 SSH 暴力破解', created_at: '2026-09-11T00:02:00Z', evidence_citations: ['ev-1'], in_result: true },
      { finding_id: 'f-2', statement: '处置建议与本次事件相关', created_at: '2026-09-11T00:02:10Z', evidence_citations: [], in_result: false },
    ],
    result: {
      result_id: 'r-1',
      verdict: { disposition: 'MALICIOUS', summary: '确认外部暴力破解攻击', confidence: 0.85 },
      created_at: '2026-09-11T00:03:10Z',
      finding_ids: ['f-1'],
      uncertainties: [{ description: '无法确认是否成功登录', missing_information: 'auth.success 日志' }],
      attack_mappings: [{ framework: 'MITRE ATT&CK', technique_id: 'T1110', name: 'Brute Force', version: 'v14.1', source: 'knowledge' }],
      response_recommendations: [],
    },
    response: { recommendations: [], proposals: [] },
    tool_activity: [],
    timeline: [
      { kind: 'INVESTIGATION_CREATED', occurred_at: '2026-09-11T00:00:00Z', title: '调查已创建', status: null, ref_type: 'investigation', ref_id: 'inv-1', safe_metadata: {} },
      { kind: 'EVIDENCE_RECORDED', occurred_at: '2026-09-11T00:01:00Z', title: '记录证据', status: null, ref_type: 'evidence', ref_id: 'ev-1', safe_metadata: {} },
    ],
    ...overrides,
  }
}

async function mockApi(page, { role = 'admin', handler } = {}) {
  await page.addInitScript(() => {
    localStorage.setItem('siem_token', 'e2e-token')
    localStorage.setItem('siem_tenant', 'default')
  })
  await page.route('**/api/**', async (route) => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    // 该 glob 也会匹配 Vite 的模块 URL（如 /src/api/index.js），绝不可拦截。
    if (!path.startsWith('/api/')) return route.continue()
    const json = (body, status = 200) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) })
    if (path === '/api/auth/me') return json({ username: `e2e-${role}`, role, passwordChangeRequired: false })
    if (path === '/api/tenants/mine') return json([{ id: 'default', name: '默认租户' }])
    if (handler) {
      const handled = await handler({ path, request, json })
      if (handled) return
    }
    return json([])
  })
}

test('Landing 摘要一次回答状态、结论、证据与是否需要我操作', async ({ page }) => {
  await mockApi(page, {
    handler: async ({ path, request, json }) => {
      if (path === '/api/agent-investigations/inv-1/workspace' && request.method() === 'GET') {
        await json(workspace())
        return true
      }
      return false
    },
  })

  await page.goto('/copilot/investigations/inv-1')

  const summary = page.locator('.state-summary')
  await expect(summary.getByTestId('summary-evidence')).toHaveText('3')
  await expect(summary.getByTestId('summary-findings')).toHaveText('2')
  await expect(summary.getByTestId('summary-verdict')).toHaveText('恶意')
  await expect(summary).toContainText('平台事实 1 · 支持性上下文 2')
  // 结论尚未生成前不显示任何结论；此处已生成，因此显示置信度。
  await expect(summary).toContainText('置信度 85%')
  // 尚无响应提案：如实说明，而不是编造状态。
  await expect(summary.getByTestId('summary-response-stage')).toHaveText('—')
  await expect(summary).toContainText('本次调查尚无响应提案')
  // 已完成且无提案 → 没有待办（可选提案不算必须动作）。
  await expect(summary.getByTestId('summary-action-label')).toHaveText('暂无待办')
})

test('待审批时 Landing 明确提示分析师需要操作', async ({ page }) => {
  const proposal = {
    proposal_id: 'prop-1', revision: 1, content_hash: 'a'.repeat(64), status: 'WAITING_APPROVAL',
    action_key: 'START_SOAR_PLAYBOOK', parameters: {}, reason: 'contain', target_refs: [],
    evidence_ids: ['ev-1'], policy_decision: 'REQUIRE_APPROVAL', policy_reason: null,
    created_at: '2026-09-11T00:04:00Z',
    approval: { request_id: 'req-1', status: 'WAITING_APPROVAL', requested_at: '2026-09-11T00:04:00Z', requested_reason: 'r', expected_revision: 1, expected_content_hash: 'a'.repeat(64), decision: null },
    submission: null, execution: null,
  }
  await mockApi(page, {
    handler: async ({ path, request, json }) => {
      if (path === '/api/agent-investigations/inv-1/workspace' && request.method() === 'GET') {
        await json(workspace({ response: { recommendations: [], proposals: [proposal] } }))
        return true
      }
      return false
    },
  })

  await page.goto('/copilot/investigations/inv-1')
  const summary = page.locator('.state-summary')
  await expect(summary.getByTestId('summary-action')).toHaveAttribute('data-action-kind', 'APPROVAL_REQUIRED')
  await expect(summary.getByTestId('summary-action-label')).toHaveText('等待您审批响应提案')
  await expect(summary.getByTestId('summary-response-stage')).toHaveText('人工决策')
  await expect(summary).toContainText('待审批')
})

test('审计角色看到的是「等待他人审批」而不是自己的待办', async ({ page }) => {
  const proposal = {
    proposal_id: 'prop-1', revision: 1, content_hash: 'a'.repeat(64), status: 'WAITING_APPROVAL',
    action_key: 'START_SOAR_PLAYBOOK', parameters: {}, reason: 'contain', target_refs: [],
    evidence_ids: [], policy_decision: 'REQUIRE_APPROVAL', policy_reason: null,
    created_at: '2026-09-11T00:04:00Z',
    approval: { request_id: 'req-1', status: 'WAITING_APPROVAL', requested_at: '2026-09-11T00:04:00Z', requested_reason: 'r', expected_revision: 1, expected_content_hash: 'a'.repeat(64), decision: null },
    submission: null, execution: null,
  }
  await mockApi(page, {
    role: 'audit',
    handler: async ({ path, request, json }) => {
      if (path === '/api/agent-investigations/inv-1/workspace' && request.method() === 'GET') {
        await json(workspace({ response: { recommendations: [], proposals: [proposal] } }))
        return true
      }
      return false
    },
  })

  await page.goto('/copilot/investigations/inv-1')
  const summary = page.locator('.state-summary')
  await expect(summary.getByTestId('summary-action')).toHaveAttribute('data-action-kind', 'APPROVAL_PENDING_OTHERS')
  await expect(summary.getByTestId('summary-action-label')).toHaveText('等待他人审批')
})

test('证据的权威类别区分平台事实与支持性上下文，并显式说明后者不是观测事实', async ({ page }) => {
  await mockApi(page, {
    handler: async ({ path, request, json }) => {
      if (path === '/api/agent-investigations/inv-1/workspace' && request.method() === 'GET') {
        await json(workspace())
        return true
      }
      return false
    },
  })

  await page.goto('/copilot/investigations/inv-1')
  await page.locator('.ant-tabs-tab', { hasText: '证据' }).click()

  const cards = page.locator('.evidence-card')
  await expect(cards).toHaveCount(3)
  await expect(page.locator('.evidence-card .authority-tag')).toHaveCount(3)
  await expect(page.locator('.authority-tag[data-authority="PLATFORM_FACT"]')).toHaveCount(1)
  await expect(page.locator('.authority-tag[data-authority="KNOWLEDGE_CONTEXT"]')).toHaveCount(2)
  // 支持性上下文必须写明「不是观测事实」，不能只靠颜色。
  await expect(cards.nth(1)).toContainText('支持性上下文：检索到的知识，不是本次调查观测到的事实。')
  // 平台事实不提这句。
  await expect(cards.nth(0)).not.toContainText('不是本次调查观测到的事实')
  // 主视图展示来源类别与观测/采集时间。
  await expect(cards.nth(0)).toContainText('HISIEM_LOG_SEARCH / hisiem / search_events')
  await expect(cards.nth(0)).toContainText('观测时间')
  await expect(cards.nth(0)).toContainText('203.0.113.9')
})

test('知识证据展示 citation / 来源版本 / ATT&CK release，且不把检索打分当权威', async ({ page }) => {
  await mockApi(page, {
    handler: async ({ path, request, json }) => {
      if (path === '/api/agent-investigations/inv-1/workspace' && request.method() === 'GET') {
        await json(workspace())
        return true
      }
      return false
    },
  })

  await page.goto('/copilot/investigations/inv-1')
  await page.locator('.ant-tabs-tab', { hasText: '证据' }).click()
  await page.locator('.evidence-card').nth(1).click()

  const drawer = page.locator('.ant-drawer-content')
  await expect(drawer.getByTestId('knowledge-block')).toBeVisible()
  await expect(drawer.getByTestId('knowledge-citation')).toHaveText('cit-7f3a')
  await expect(drawer.getByTestId('knowledge-block')).toContainText('SSH 暴力破解处置指引')
  await expect(drawer.getByTestId('knowledge-block')).toContainText('runbook')
  await expect(drawer.getByTestId('knowledge-block')).toContainText('2026.09')
  await expect(drawer.getByTestId('knowledge-block')).toContainText('HYBRID')
  await expect(drawer.getByTestId('knowledge-block')).toContainText('检索打分 / 排序位置属于检索执行元数据')
  // 检索打分只能出现在折叠的技术溯源里，绝不能被当作权威或置信度。
  await expect(drawer.locator('.ant-alert')).toContainText('支持性上下文')

  await page.locator('.ant-drawer-close').click()
  // ATT&CK 精确解析证据展示 technique identity 与 release。
  await page.locator('.evidence-card').nth(2).click()
  const attackDrawer = page.locator('.ant-drawer-content')
  await expect(attackDrawer.getByTestId('knowledge-block')).toContainText('T1110')
  await expect(attackDrawer.getByTestId('knowledge-block')).toContainText('MITRE ATT&CK')
  await expect(attackDrawer.getByTestId('knowledge-block')).toContainText('v14.1')
})

test('AI 调查结论与分析师处置明确区分，并列出支撑发现与局限性', async ({ page }) => {
  await mockApi(page, {
    handler: async ({ path, request, json }) => {
      if (path === '/api/agent-investigations/inv-1/workspace' && request.method() === 'GET') {
        await json(workspace())
        return true
      }
      return false
    },
  })

  await page.goto('/copilot/investigations/inv-1')
  const verdict = page.locator('.verdict-card')
  await expect(verdict.getByTestId('verdict-authority')).toHaveText(/AI 调查结论/)
  await expect(verdict.getByTestId('verdict-disposition')).toHaveText('恶意')
  await expect(verdict.getByTestId('verdict-distinction')).toContainText('不等于分析师处置结论')
  await expect(verdict.getByTestId('verdict-distinction')).toContainText('分析师处置')
  // 支撑发现只按持久 finding_id 解析：f-1 在结论里，f-2 不在。
  await expect(verdict.getByTestId('verdict-finding-count')).toHaveText('1')
  await expect(verdict).toContainText('检测到外部 SSH 暴力破解')
  await expect(verdict).not.toContainText('处置建议与本次事件相关')
  await expect(verdict).toContainText('无法确认是否成功登录')
  // 结论不可能是「分析师处置」的另一种写法。
  await expect(page.locator('.verdict-card')).not.toContainText('分析师处置结论：')

  // 从结论的支撑发现点开引用证据。
  await verdict.locator('.citation-chip').first().click()
  await expect(page.locator('.ant-drawer-content')).toContainText('5 分钟内 6 次失败登录')
})

test('发现标注为模型推导，未引用证据的发现显式声明不构成权威结论', async ({ page }) => {
  await mockApi(page, {
    handler: async ({ path, request, json }) => {
      if (path === '/api/agent-investigations/inv-1/workspace' && request.method() === 'GET') {
        await json(workspace())
        return true
      }
      return false
    },
  })

  await page.goto('/copilot/investigations/inv-1')
  await expect(page.locator('.finding-authority').first()).toHaveText(/模型推导发现/)
  const noCitation = page.locator('.finding-card').nth(1)
  await expect(noCitation.getByTestId('finding-no-citation')).toContainText('不构成权威结论')
  await expect(noCitation).toContainText('未引用证据')
})

test('未知字段（CoT / 原始 prompt / checkpoint）永远不会进入渲染', async ({ page }) => {
  const leaked = workspace()
  leaked.chain_of_thought = 'COT-LEAK-MARKER'
  leaked.raw_prompt = 'PROMPT-LEAK-MARKER'
  leaked.raw_model_response = 'RESPONSE-LEAK-MARKER'
  leaked.langgraph_checkpoint = 'CHECKPOINT-LEAK-MARKER'
  leaked.investigation.internal_graph_state = 'GRAPHSTATE-LEAK-MARKER'
  leaked.result.raw_completion = 'COMPLETION-LEAK-MARKER'
  leaked.evidence[0].model_request = 'MODELREQ-LEAK-MARKER'
  leaked.timeline[0].checkpoint_id = 'TIMELINE-CHECKPOINT-LEAK-MARKER'

  await mockApi(page, {
    handler: async ({ path, request, json }) => {
      if (path === '/api/agent-investigations/inv-1/workspace' && request.method() === 'GET') {
        await json(leaked)
        return true
      }
      return false
    },
  })

  await page.goto('/copilot/investigations/inv-1')
  await page.locator('.ant-tabs-tab', { hasText: '时间线' }).click()
  await expect(page.locator('.ant-timeline-item')).toHaveCount(2)

  const body = await page.locator('body').innerText()
  for (const marker of [
    'COT-LEAK-MARKER', 'PROMPT-LEAK-MARKER', 'RESPONSE-LEAK-MARKER', 'CHECKPOINT-LEAK-MARKER',
    'GRAPHSTATE-LEAK-MARKER', 'COMPLETION-LEAK-MARKER', 'MODELREQ-LEAK-MARKER',
    'TIMELINE-CHECKPOINT-LEAK-MARKER',
  ]) {
    expect(body, `leaked: ${marker}`).not.toContain(marker)
  }
})

test('响应建议明确是建议而非执行命令，并指向正式响应流程', async ({ page }) => {
  await mockApi(page, {
    handler: async ({ path, request, json }) => {
      if (path === '/api/agent-investigations/inv-1/workspace' && request.method() === 'GET') {
        await json(workspace({
          result: {
            ...workspace().result,
            response_recommendations: [{ description: '封禁源 IP', reason: '持续失败登录' }],
          },
        }))
        return true
      }
      return false
    },
  })

  await page.goto('/copilot/investigations/inv-1')
  const note = page.locator('.readonly-note')
  await expect(note).toContainText('不是执行命令')
  await expect(note).toContainText('经策略判定与人工审批')
  // 平台确实提供审批入口（响应页签），因此不得再声称“不提供审批入口”。
  await expect(note).not.toContainText('不提供执行')
  await expect(note).not.toContainText('不提供')
  await expect(note).not.toContainText('审批入口')
  await expect(page.locator('.surface-card', { hasText: '响应建议' })).toContainText('建议文本 · 不可执行')
})

test('ATT&CK 映射展示完整 release 身份（框架与版本之间有分隔）', async ({ page }) => {
  await mockApi(page, {
    handler: async ({ path, request, json }) => {
      if (path === '/api/agent-investigations/inv-1/workspace' && request.method() === 'GET') {
        await json(workspace())
        return true
      }
      return false
    },
  })

  await page.goto('/copilot/investigations/inv-1')
  const tag = page.locator('.attack-tag')
  await expect(tag).toContainText('T1110')
  await expect(tag).toContainText('MITRE ATT&CK v14.1')
})

test('执行中的响应：摘要显示观测执行阶段，且待办判定不与之矛盾', async ({ page }) => {
  const proposal = {
    proposal_id: 'prop-1', revision: 1, content_hash: 'a'.repeat(64), status: 'SUBMITTED',
    action_key: 'START_SOAR_PLAYBOOK', parameters: {}, reason: 'contain', target_refs: [],
    evidence_ids: [], policy_decision: 'REQUIRE_APPROVAL', policy_reason: null,
    created_at: '2026-09-11T00:04:00Z',
    approval: {
      request_id: 'req-1', status: 'APPROVED', requested_at: '2026-09-11T00:04:00Z', requested_reason: 'r',
      expected_revision: 1, expected_content_hash: 'a'.repeat(64),
      decision: { decision: 'APPROVE', actor_subject_id: 'analyst', actor_display_name: '分析师甲', decided_at: '2026-09-11T00:05:00Z' },
    },
    submission: { status: 'SUBMITTED', attempt_count: 1 },
    execution: { provider: 'hisiem', status: 'RUNNING', external_execution_id: 'soar-2026-0001', submitted_at: '2026-09-11T00:05:00Z', last_observed_at: '2026-09-11T00:06:00Z' },
  }
  await mockApi(page, {
    handler: async ({ path, request, json }) => {
      if (path === '/api/agent-investigations/inv-1/workspace' && request.method() === 'GET') {
        await json(workspace({ response: { recommendations: [], proposals: [proposal] } }))
        return true
      }
      return false
    },
  })

  await page.goto('/copilot/investigations/inv-1')
  const summary = page.locator('.state-summary')
  await expect(summary.getByTestId('summary-response-stage')).toHaveText('HISIEM 观测执行')
  await expect(summary).toContainText('执行中')
  await expect(summary).toContainText('HISIEM 正在执行')
  // 摘要是状态而不是技术身份：外部执行编号只在响应页签的执行详情里。
  await expect(summary).not.toContainText('soar-2026-0001')
  // 已进入执行阶段就绝不能再提示“等待审批”。
  await expect(summary.getByTestId('summary-action-label')).not.toContainText('审批')
  await expect(summary).not.toContainText('等待您审批')
})

test('加载 / 错误 / 空 三种状态都是一等状态', async ({ page }) => {
  await mockApi(page, {
    handler: async ({ path, request, json }) => {
      if (path === '/api/agent-investigations/inv-1/workspace' && request.method() === 'GET') {
        await new Promise((resolve) => setTimeout(resolve, 1200))
        await json(workspace())
        return true
      }
      return false
    },
  })
  await page.goto('/copilot/investigations/inv-1')
  await expect(page.locator('.loading-state')).toBeVisible()
  await expect(page.locator('.loading-state')).toContainText('正在加载')
  await expect(page.locator('.state-summary')).toBeVisible()
})

test('工作台不存在时给出错误状态与重试入口', async ({ page }) => {
  await mockApi(page, {
    handler: async ({ path, request, json }) => {
      if (path === '/api/agent-investigations/inv-1/workspace' && request.method() === 'GET') {
        await json({ message: 'not found' }, 404)
        return true
      }
      return false
    },
  })
  await page.goto('/copilot/investigations/inv-1')
  await expect(page.locator('.error-state')).toBeVisible()
  await expect(page.locator('.error-state')).toContainText('数据加载失败')
  await expect(page.getByRole('button', { name: '重新加载' })).toBeVisible()
  // 错误页不显示任何调查内容。
  await expect(page.locator('.state-summary')).toHaveCount(0)
})

test('刷新失败保留上次快照并显式标注过期，不空白页面', async ({ page }) => {
  let reads = 0
  await mockApi(page, {
    handler: async ({ path, request, json }) => {
      if (path === '/api/agent-investigations/inv-1/workspace' && request.method() === 'GET') {
        reads += 1
        if (reads === 1) { await json(workspace()); return true }
        await json({ message: 'upstream down' }, 503)
        return true
      }
      return false
    },
  })

  await page.goto('/copilot/investigations/inv-1')
  await expect(page.locator('.state-summary')).toBeVisible()
  await expect(page.locator('.stale-banner')).toHaveCount(0)

  await page.locator('.investigation-header').getByRole('button', { name: /刷新/ }).click()

  await expect(page.locator('.stale-banner')).toBeVisible()
  await expect(page.locator('.stale-banner')).toContainText('数据可能已过期')
  await expect(page.locator('.stale-banner')).toContainText('最后一次成功获取的调查快照')
  // 上次快照仍在：不空白页面。
  await expect(page.locator('.state-summary')).toBeVisible()
  await expect(page.locator('.verdict-card')).toContainText('确认外部暴力破解攻击')
})

test('刷新从持久状态重建：服务端改状态后页面同步', async ({ page }) => {
  let stage = 0
  await mockApi(page, {
    handler: async ({ path, request, json }) => {
      if (path === '/api/agent-investigations/inv-1/workspace' && request.method() === 'GET') {
        stage += 1
        if (stage === 1) { await json(workspace({ result: null, findings: [] })); return true }
        await json(workspace())
        return true
      }
      return false
    },
  })

  await page.goto('/copilot/investigations/inv-1')
  await expect(page.locator('.verdict-card')).toHaveCount(0)
  await expect(page.locator('.state-summary')).toContainText('尚未生成结论')

  await page.locator('.investigation-header').getByRole('button', { name: /刷新/ }).click()
  await expect(page.locator('.verdict-card')).toBeVisible()
  await expect(page.locator('.state-summary')).toContainText('恶意')
})

test('窄屏下工作台仍然可用：单列描述、页签可达、详情走抽屉', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await mockApi(page, {
    handler: async ({ path, request, json }) => {
      if (path === '/api/agent-investigations/inv-1/workspace' && request.method() === 'GET') {
        await json(workspace())
        return true
      }
      return false
    },
  })

  await page.goto('/copilot/investigations/inv-1')

  // 关键信息在窄屏仍然首屏可见。
  await expect(page.getByRole('heading', { name: 'AI 调查工作台' })).toBeVisible()
  await expect(page.locator('.state-summary').getByTestId('summary-verdict')).toHaveText('恶意')
  await expect(page.locator('.verdict-card').getByTestId('verdict-authority')).toBeVisible()

  // 页签可达。
  await page.locator('.ant-tabs-tab', { hasText: '证据' }).click()
  await expect(page.locator('.evidence-card')).toHaveCount(3)

  // 详情仍是抽屉，且抽屉不宽于视口。
  await page.locator('.evidence-card').first().click()
  const drawer = page.locator('.ant-drawer-content')
  await expect(drawer).toBeVisible()
  const box = await drawer.boundingBox()
  expect(box.width).toBeLessThanOrEqual(390)
  await expect(drawer).toContainText('5 分钟内 6 次失败登录')

  // 页面没有横向溢出。
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)
  expect(overflow).toBeLessThanOrEqual(1)
})
