import test from 'node:test'
import assert from 'node:assert/strict'
import {
  RESPONSE_PROPOSAL_ACTION_KEY,
  RESPONSE_REASON_MAX_LENGTH,
  TIMELINE_FILTERS,
  buildProposalRequest,
  canCreateResponseProposal,
  canDecideProposal,
  confidencePercent,
  durationText,
  executionStatusColor,
  executionStatusLabel,
  filterTimeline,
  hypothesisStatusLabel,
  investigationPhaseLabel,
  investigationStatusColor,
  investigationStatusLabel,
  isExecutionTerminal,
  isInvestigationActive,
  isInvestigationTerminal,
  needsResponsePolling,
  policyDecisionLabel,
  proposalAwaitingSubmission,
  responseActionLabel,
  proposalSubmissionFailed,
  proposalSubmissionNeedsAttention,
  proposalSubmissionRetrying,
  responseProposalStatusColor,
  responseProposalStatusLabel,
  submissionStatusColor,
  submissionStatusLabel,
  selectablePlaybooks,
  timelineEntryLabel,
  timelineKindLabel,
  timelineStatusLabel,
  verdictColor,
  verdictLabel,
} from './copilot.js'

test('活动与终态判定互斥且覆盖全部调查状态', () => {
  for (const status of ['CREATED', 'RUNNING']) {
    assert.equal(isInvestigationActive(status), true)
    assert.equal(isInvestigationTerminal(status), false)
  }
  for (const status of ['COMPLETED', 'FAILED', 'CANCELLED']) {
    assert.equal(isInvestigationActive(status), false)
    assert.equal(isInvestigationTerminal(status), true)
  }
  assert.equal(isInvestigationActive(undefined), false)
  assert.equal(isInvestigationTerminal(''), false)
})

test('响应工作流状态不属于调查生命周期', () => {
  // §5：审批/执行是调查 COMPLETED 之后独立的聚合生命周期；调查状态永远不会变成这些值。
  for (const status of ['WAITING_APPROVAL', 'EXECUTING_RESPONSE', 'APPROVED', 'SUBMITTED', 'REJECTED']) {
    assert.equal(isInvestigationActive(status), false)
    assert.equal(isInvestigationTerminal(status), false)
  }
})

test('已批准但未提交的提案不编造外部执行 ID，且仍驱动轮询', () => {
  const awaiting = { status: 'APPROVED', execution: null }
  assert.equal(proposalAwaitingSubmission(awaiting), true)
  assert.equal(proposalAwaitingSubmission({ status: 'APPROVED', execution: { external_execution_id: 'x' } }), false)
  assert.equal(proposalAwaitingSubmission({ status: 'SUBMITTED', execution: null }), false)
  assert.equal(proposalAwaitingSubmission(null), false)

  assert.equal(needsResponsePolling([awaiting]), true)
  assert.equal(needsResponsePolling([{ status: 'SUBMITTED', execution: { status: 'RUNNING' } }]), true)
  assert.equal(needsResponsePolling([{ status: 'SUBMITTED', execution: { status: 'SUCCEEDED' } }]), false)
  assert.equal(needsResponsePolling([{ status: 'REJECTED', execution: null }]), false)
  assert.equal(needsResponsePolling([]), false)
  assert.equal(needsResponsePolling(null), false)
})

test('提交被 provider 明确拒绝是终态：停止轮询，且不再是“等待提交”', () => {
  // provider 拒绝的是这次 SUBMISSION，不是某次执行 —— 没有执行，也就没有外部执行 ID。
  const failed = {
    status: 'APPROVED',
    execution: null,
    submission: { status: 'FAILED_DEFINITIVE', attempt_count: 2, last_error_code: 'HTTP_422' },
  }
  assert.equal(proposalSubmissionFailed(failed), true)
  assert.equal(proposalAwaitingSubmission(failed), false)
  assert.equal(proposalSubmissionRetrying(failed), false)
  // 终态：不再轮询，页面不会无限刷新。
  assert.equal(needsResponsePolling([failed]), false)

  // 有执行时以 provider 状态为准；提交投影不再决定轮询。
  assert.equal(
    needsResponsePolling([{ status: 'SUBMITTED', execution: { status: 'SUCCEEDED' }, submission: { status: 'FAILED_DEFINITIVE' } }]),
    false,
  )
})

test('重试预算耗尽 = 确定的本地终态：停止轮询，且不声称 provider 拒绝', () => {
  // 失败始终是瞬时/不确定的：既不能说 provider 拒绝，也不能说没有执行。
  const exhausted = {
    status: 'APPROVED',
    execution: null,
    submission: {
      status: 'ATTENTION_REQUIRED',
      attempt_count: 9,
      last_error_code: 'HTTP_503',
      safe_error_message: 'upstream unavailable',
      attention_required_at: '2026-09-11T00:30:00Z',
    },
  }
  assert.equal(proposalSubmissionNeedsAttention(exhausted), true)
  // 它不是「明确拒绝」，也不是「还在重试」，更不是「等待提交」。
  assert.equal(proposalSubmissionFailed(exhausted), false)
  assert.equal(proposalSubmissionRetrying(exhausted), false)
  assert.equal(proposalAwaitingSubmission(exhausted), false)
  // 终态：浏览器不再轮询一个永远不会再发生的重试。
  assert.equal(needsResponsePolling([exhausted]), false)
  assert.equal(submissionStatusLabel('ATTENTION_REQUIRED'), '需要人工处理')
  assert.notEqual(submissionStatusLabel('ATTENTION_REQUIRED'), submissionStatusLabel('FAILED_DEFINITIVE'))
})

test('重试预算耗尽的时间线和响应过滤器都来自持久事实', () => {
  assert.equal(timelineKindLabel('RESPONSE_SUBMISSION_ATTENTION_REQUIRED'), '提交需人工处理')
  assert.equal(
    timelineEntryLabel({
      kind: 'RESPONSE_SUBMISSION_ATTENTION_REQUIRED',
      status: 'SUBMISSION_ATTENTION_REQUIRED',
    }),
    '提交状态不确定 / 需要人工处理',
  )
  assert.equal(timelineStatusLabel('SUBMISSION_ATTENTION_REQUIRED'), '需要人工处理')

  const response = TIMELINE_FILTERS.find((item) => item.key === 'response')
  assert.ok(response.kinds.includes('RESPONSE_SUBMISSION_ATTENTION_REQUIRED'))
})

test('提交重试中仍是本地状态：继续轮询，且不显示外部执行 ID', () => {
  const retrying = {
    status: 'APPROVED',
    execution: null,
    submission: { status: 'RETRYING', attempt_count: 3, last_error_code: 'HTTP_429' },
  }
  assert.equal(proposalSubmissionRetrying(retrying), true)
  assert.equal(proposalAwaitingSubmission(retrying), true)
  assert.equal(proposalSubmissionFailed(retrying), false)
  assert.equal(needsResponsePolling([retrying]), true)

  // 提交成功但 provider 还在跑：仍然轮询到终态。
  assert.equal(
    needsResponsePolling([{
      status: 'SUBMITTED',
      submission: { status: 'SUBMITTED' },
      execution: { status: 'RUNNING', external_execution_id: 'exec-1' },
    }]),
    true,
  )
})

test('提交状态有独立的中文标签与颜色，不与执行状态混用', () => {
  assert.equal(submissionStatusLabel('PENDING'), '等待提交')
  assert.equal(submissionStatusLabel('RETRYING'), '提交重试中')
  assert.equal(submissionStatusLabel('SUBMITTED'), '已提交')
  assert.equal(submissionStatusLabel('FAILED_DEFINITIVE'), '提交失败')
  assert.equal(submissionStatusLabel(null), '—')
  assert.equal(submissionStatusColor('FAILED_DEFINITIVE'), 'red')
  assert.equal(submissionStatusColor('RETRYING'), 'orange')
  // 提交状态与执行状态是两套命名空间，绝不互相解释。
  assert.notEqual(submissionStatusLabel('FAILED_DEFINITIVE'), executionStatusLabel('FAILED'))
})

test('提交生命周期的时间线条目来自持久事实，且不冒充执行事实', () => {
  assert.equal(timelineKindLabel('RESPONSE_SUBMISSION_QUEUED'), '提交排队')
  assert.equal(timelineKindLabel('RESPONSE_SUBMISSION_RETRYING'), '提交重试')
  assert.equal(timelineKindLabel('RESPONSE_SUBMISSION_FAILED'), '提交失败')
  assert.equal(
    timelineEntryLabel({ kind: 'RESPONSE_SUBMISSION_FAILED', status: 'SUBMISSION_FAILED' }),
    '提交失败',
  )
  assert.equal(
    timelineEntryLabel({ kind: 'RESPONSE_SUBMISSION_RETRYING', status: 'SUBMISSION_RETRYING' }),
    '已批准 / 提交重试中',
  )
  assert.equal(timelineStatusLabel('SUBMISSION_RETRYING'), '提交重试中')
  assert.equal(timelineStatusLabel('SUBMISSION_FAILED'), '提交失败')

  // 响应过滤器必须包含提交事实，否则它们会从「响应」视图里消失。
  const response = TIMELINE_FILTERS.find((item) => item.key === 'response')
  for (const kind of ['RESPONSE_SUBMISSION_QUEUED', 'RESPONSE_SUBMISSION_RETRYING', 'RESPONSE_SUBMISSION_FAILED']) {
    assert.ok(response.kinds.includes(kind), kind)
  }
  const entries = [
    { kind: 'RESPONSE_SUBMISSION_FAILED' },
    { kind: 'PLAN_CREATED' },
  ]
  assert.deepEqual(filterTimeline(entries, 'response').map((e) => e.kind), ['RESPONSE_SUBMISSION_FAILED'])
})

test('已批准等待提交的时间线显示状态而不是执行身份', () => {
  assert.equal(timelineEntryLabel({ kind: 'RESPONSE_APPROVED', status: 'AWAITING_SUBMISSION' }), '已批准 / 等待提交')
  assert.equal(timelineEntryLabel({ kind: 'RESPONSE_APPROVED' }), '已批准')
  assert.equal(timelineEntryLabel({ kind: 'RESPONSE_EXECUTION_SUCCEEDED' }), '执行成功')
  assert.equal(timelineStatusLabel('AWAITING_SUBMISSION'), '等待提交')
  assert.equal(timelineStatusLabel('REQUIRE_APPROVAL'), '需要人工审批')
  assert.equal(timelineStatusLabel('DENY'), '策略拒绝')
  assert.equal(timelineStatusLabel(''), '')
})

test('剧本下拉只暴露已发布且已启用的剧本', () => {
  const playbooks = [
    { id: 'p1', name: '隔离主机', status: 'published', enabled: true },
    { id: 'p2', name: '草稿剧本', status: 'draft', enabled: false },
    { id: 'p3', name: '已停用', status: 'disabled', enabled: false },
    { id: 'p4', name: '已发布未启用', status: 'published', enabled: false },
    null,
  ]
  assert.deepEqual(selectablePlaybooks(playbooks), [{ value: 'p1', label: '隔离主机' }])
  assert.deepEqual(selectablePlaybooks(null), [])
})

test('创建表单仅对有权限、已完成、尚无提案且有证据的调查开放', () => {
  const base = { status: 'COMPLETED', proposals: [], role: 'analyst', evidenceCount: 2 }
  assert.equal(canCreateResponseProposal(base), true)
  assert.equal(canCreateResponseProposal({ ...base, role: 'admin' }), true)
  assert.equal(canCreateResponseProposal({ ...base, role: 'audit' }), false)
  assert.equal(canCreateResponseProposal({ ...base, status: 'RUNNING' }), false)
  assert.equal(canCreateResponseProposal({ ...base, proposals: [{ proposal_id: 'x' }] }), false)
  assert.equal(canCreateResponseProposal({ ...base, evidenceCount: 0 }), false)
  assert.equal(canCreateResponseProposal(), false)
})

test('提案请求体只有有界契约，绝不携带目标/租户/操作人', () => {
  const body = buildProposalRequest({
    playbookId: 'pb-1',
    evidenceIds: ['e1', 'e2'],
    reason: '  确认暴力破解  ',
  })
  assert.deepEqual(body, {
    action_key: 'START_SOAR_PLAYBOOK',
    evidence_ids: ['e1', 'e2'],
    parameters: { playbook_id: 'pb-1' },
    reason: '确认暴力破解',
  })
  assert.deepEqual(Object.keys(body).sort(), ['action_key', 'evidence_ids', 'parameters', 'reason'])
  assert.deepEqual(Object.keys(body.parameters), ['playbook_id'])
  assert.equal(RESPONSE_PROPOSAL_ACTION_KEY, body.action_key)
  for (const forbidden of ['target', 'tenant_id', 'actor', 'provider', 'resource_type', 'address_id', 'business_id']) {
    assert.equal(forbidden in body, false)
  }
})

test('提案请求体在缺项或超长时抛错', () => {
  assert.throws(() => buildProposalRequest({ playbookId: '', evidenceIds: ['e1'], reason: 'x' }), /剧本/)
  assert.throws(() => buildProposalRequest({ playbookId: 'pb', evidenceIds: [], reason: 'x' }), /证据/)
  assert.throws(() => buildProposalRequest({ playbookId: 'pb', evidenceIds: ['e1'], reason: ' ' }), /理由/)
  assert.throws(
    () => buildProposalRequest({
      playbookId: 'pb',
      evidenceIds: ['e1'],
      reason: 'x'.repeat(RESPONSE_REASON_MAX_LENGTH + 1),
    }),
    /理由不能超过/,
  )
})

test('状态、阶段、结论、假设标签与颜色映射', () => {
  assert.equal(investigationStatusLabel('RUNNING'), '调查中')
  assert.equal(investigationStatusColor('FAILED'), 'red')
  assert.equal(investigationStatusLabel('UNKNOWN'), 'UNKNOWN')
  assert.equal(investigationPhaseLabel('VERIFYING'), '验证中')
  assert.equal(investigationPhaseLabel(''), '')
  assert.equal(verdictLabel('MALICIOUS'), '恶意')
  assert.equal(verdictColor('INCONCLUSIVE'), 'orange')
  assert.equal(hypothesisStatusLabel('CONTRADICTED'), '已反驳')
})

test('时间线过滤仅按持久事实 kind 归类，不做合成', () => {
  const entries = [
    { kind: 'PLAN_CREATED' },
    { kind: 'TOOL_STARTED' },
    { kind: 'TOOL_SUCCEEDED' },
    { kind: 'EVIDENCE_RECORDED' },
    { kind: 'HYPOTHESIS_ASSESSED' },
    { kind: 'FINDING_RECORDED' },
    { kind: 'RESULT_FINALIZED' },
    { kind: 'INVESTIGATION_COMPLETED' },
  ]
  assert.equal(filterTimeline(entries, 'all').length, entries.length)
  assert.equal(filterTimeline(entries, 'plan').length, 1)
  assert.equal(filterTimeline(entries, 'tools').length, 2)
  assert.equal(filterTimeline(entries, 'evidence').length, 2)
  assert.equal(filterTimeline(entries, 'analysis').length, 1)
  assert.equal(filterTimeline(entries, 'result').length, 2)
  assert.equal(filterTimeline(entries, 'unknown').length, entries.length)
  assert.equal(filterTimeline(null, 'all').length, 0)
  assert.equal(TIMELINE_FILTERS.length, 7)
})

test('响应时间线按响应 kind 归类', () => {
  const entries = [
    { kind: 'RESULT_FINALIZED' },
    { kind: 'RESPONSE_PROPOSAL_CREATED' },
    { kind: 'RESPONSE_APPROVED' },
    { kind: 'RESPONSE_EXECUTION_SUCCEEDED' },
  ]
  assert.equal(filterTimeline(entries, 'response').length, 3)
  assert.equal(timelineKindLabel('RESPONSE_EXECUTION_SUCCEEDED'), '执行成功')
})

test('响应提案/执行/策略/动作标签与颜色映射', () => {
  assert.equal(responseProposalStatusLabel('WAITING_APPROVAL'), '待审批')
  assert.equal(responseProposalStatusColor('APPROVED'), 'green')
  assert.equal(executionStatusLabel('SUCCEEDED'), '成功')
  assert.equal(executionStatusColor('FAILED'), 'red')
  assert.equal(policyDecisionLabel('REQUIRE_APPROVAL'), '需要人工审批')
  assert.equal(responseActionLabel('START_SOAR_PLAYBOOK'), '启动 SOAR 剧本')
})

test('仅待审批提案可决策，且执行仅在终态收敛', () => {
  assert.equal(canDecideProposal('WAITING_APPROVAL'), true)
  for (const status of ['CREATED', 'DENIED', 'APPROVED', 'REJECTED', 'SUBMITTED']) {
    assert.equal(canDecideProposal(status), false)
  }
  assert.equal(isExecutionTerminal('SUCCEEDED'), true)
  assert.equal(isExecutionTerminal('FAILED'), true)
  assert.equal(isExecutionTerminal('QUEUED'), false)
  assert.equal(isExecutionTerminal(undefined), false)
})

test('耗时与置信度格式化在缺失时返回占位符', () => {
  assert.equal(durationText('2026-09-11T00:00:00Z', '2026-09-11T00:00:42Z'), '42 秒')
  assert.equal(durationText('2026-09-11T00:00:00Z', '2026-09-11T00:02:05Z'), '2 分 5 秒')
  assert.equal(durationText('2026-09-11T00:00:00Z', '2026-09-11T01:30:00Z'), '1 小时 30 分')
  assert.equal(durationText('', '2026-09-11T00:00:00Z'), '—')
  assert.equal(durationText('2026-09-11T00:00:10Z', '2026-09-11T00:00:00Z'), '—')
  assert.equal(confidencePercent(0.9), '90%')
  assert.equal(confidencePercent(0), '0%')
  assert.equal(confidencePercent(undefined), '—')
})
