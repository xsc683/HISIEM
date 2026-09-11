import test from 'node:test'
import assert from 'node:assert/strict'
import {
  TIMELINE_FILTERS,
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
  policyDecisionLabel,
  responseActionLabel,
  responseProposalStatusColor,
  responseProposalStatusLabel,
  timelineKindLabel,
  verdictColor,
  verdictLabel,
} from './copilot.js'

test('活动与终态判定互斥且覆盖全部调查状态', () => {
  for (const status of ['CREATED', 'RUNNING', 'WAITING_APPROVAL', 'EXECUTING_RESPONSE']) {
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
