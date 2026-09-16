// Stage D — 分析师体验的展示契约测试（权威语义 / 知识来源 / 响应生命周期 / Landing 摘要）。
//
// 这些用例锁定的是「UI 如何表达服务端权威」这件事，不是业务判定本身：前端只做展示映射，
// 任何权威类别都必须来自服务端已持久化的字段。

import test from 'node:test'
import assert from 'node:assert/strict'
import {
  ANALYST_ACTION,
  ANALYST_DISPOSITION_LABEL,
  AUTHORITY_LABELS,
  PLATFORM_EVIDENCE_SOURCE_TYPES,
  VERDICT_AUTHORITY_LABEL,
  analystAction,
  authorityDescription,
  authorityLabel,
  citationText,
  evidenceAuthority,
  furthestProposal,
  isPlatformEvidence,
  isSupportingContext,
  knowledgeFacts,
  responseStage,
  supportingFindings,
  workspaceStateSummary,
} from './copilot.js'

const EVIDENCE = (id, type, extra = {}) => ({
  evidence_id: id,
  summary: `证据 ${id}`,
  source: { type, provider: 'hisiem', operation: 'op' },
  observed_at: '2026-09-11T00:02:00Z',
  collected_at: '2026-09-11T00:02:01Z',
  ...extra,
})

const PROPOSAL = (over = {}) => ({
  proposal_id: 'prop-1',
  status: 'WAITING_APPROVAL',
  action_key: 'START_SOAR_PLAYBOOK',
  parameters: {},
  reason: 'r',
  created_at: '2026-09-11T00:04:00Z',
  approval: { request_id: 'req-1', status: 'WAITING_APPROVAL', decision: null },
  submission: null,
  execution: null,
  ...over,
})

test('证据权威类别只映射服务端已持久化的 source.type', () => {
  for (const type of PLATFORM_EVIDENCE_SOURCE_TYPES) {
    assert.equal(evidenceAuthority(type), 'PLATFORM_FACT')
    assert.equal(isPlatformEvidence(type), true)
  }
  assert.equal(evidenceAuthority('KNOWLEDGE'), 'KNOWLEDGE_CONTEXT')
  assert.equal(evidenceAuthority('SYSTEM'), 'SYSTEM_CONTEXT')
  assert.equal(evidenceAuthority('THREAT_INTEL'), 'THREAT_INTEL')
  assert.equal(evidenceAuthority('SOMETHING_NEW'), 'UNKNOWN')
  assert.equal(evidenceAuthority(undefined), 'UNKNOWN')
  // 只有 HISIEM_* 是观测事实：知识与系统元数据永远不是。
  assert.equal(isPlatformEvidence('KNOWLEDGE'), false)
  assert.equal(isPlatformEvidence('SYSTEM'), false)
})

test('权威类别同时有文字标签与说明，不依赖颜色', () => {
  for (const authority of Object.keys(AUTHORITY_LABELS)) {
    assert.equal(typeof AUTHORITY_LABELS[authority], 'string')
    assert.ok(AUTHORITY_LABELS[authority].length > 0)
    assert.equal(authorityLabel(authority), AUTHORITY_LABELS[authority])
    assert.ok(authorityDescription(authority).length > 0)
  }
  assert.equal(authorityLabel('PLATFORM_FACT'), '平台事实')
  assert.equal(authorityLabel('KNOWLEDGE_CONTEXT'), '支持性上下文')
  assert.equal(isSupportingContext('PLATFORM_FACT'), false)
  assert.equal(isSupportingContext('KNOWLEDGE_CONTEXT'), true)
})

test('知识证据只暴露来源身份与检索时间，绝不暴露检索打分', () => {
  const facts = knowledgeFacts({
    ...EVIDENCE('ev-1', 'KNOWLEDGE'),
    observation: {
      title: 'SSH 暴力破解处置指引', excerpt: '建议封禁源 IP',
      source_kind: 'runbook', source_version: '2026.09',
      technique_id: 'T1110', framework: 'MITRE ATT&CK',
      authoritative_release: 'v14.1',
    },
    raw_reference: {
      citation_identity: {
        citation_id: 'cit-1', document_id: 'doc-1',
        document_version_id: 'ver-1', chunk_id: 'chk-1', content_hash: 'a'.repeat(64),
      },
      retrieval_provenance: { mode: 'HYBRID', profile_id: 'p-1', retrieved_at: '2026-09-11T00:01:00Z' },
    },
  })

  assert.equal(facts.title, 'SSH 暴力破解处置指引')
  assert.equal(facts.citationId, 'cit-1')
  assert.equal(facts.sourceKind, 'runbook')
  assert.equal(facts.sourceVersion, '2026.09')
  assert.equal(facts.retrievalMode, 'HYBRID')
  assert.equal(facts.retrievedAt, '2026-09-11T00:01:00Z')
  assert.equal(facts.techniqueId, 'T1110')
  assert.equal(facts.attackRelease, 'v14.1')
  assert.equal(citationText(facts), 'cit-1')

  // 检索执行元数据（rank / score / rrf）不得出现在展示模型里，更不得当作置信度。
  for (const key of Object.keys(facts)) {
    assert.ok(!/rank|score|rrf|similarity|cosine/i.test(key), `unexpected ranking field: ${key}`)
  }
  assert.equal(knowledgeFacts(null), null)
})

test('结论的支撑发现只按持久 finding_id 解析，不做文本匹配', () => {
  const findings = [
    { finding_id: 'f-1', statement: 'A', evidence_citations: ['ev-1'], in_result: true },
    { finding_id: 'f-2', statement: 'B', evidence_citations: [], in_result: false },
    { finding_id: 'f-3', statement: 'C', evidence_citations: ['ev-2'], in_result: true },
  ]
  const result = { result_id: 'r-1', finding_ids: ['f-1', 'f-3'], verdict: { disposition: 'MALICIOUS' } }

  assert.deepEqual(supportingFindings(result, findings).map((f) => f.finding_id), ['f-1', 'f-3'])
  // 名字相同但 ID 不同不得被选中。
  assert.deepEqual(supportingFindings(result, [{ finding_id: 'zz', statement: 'f-1' }]), [])
  assert.deepEqual(supportingFindings(null, findings), [])
  assert.deepEqual(supportingFindings({ result_id: 'r', finding_ids: [] }, findings), [])
})

test('AI 调查结论与分析师处置在标签上明确分开', () => {
  assert.equal(VERDICT_AUTHORITY_LABEL, 'AI 调查结论')
  assert.equal(ANALYST_DISPOSITION_LABEL, '分析师处置')
  assert.notEqual(VERDICT_AUTHORITY_LABEL, ANALYST_DISPOSITION_LABEL)
})

test('响应生命周期不合并提案 / 审批 / 提交 / 执行四种状态', () => {
  const waiting = responseStage(PROPOSAL())
  assert.equal(waiting.stage, 'HUMAN')
  assert.equal(waiting.status, '待审批')

  const approved = responseStage(PROPOSAL({ status: 'APPROVED', approval: { request_id: 'req-1', decision: { decision: 'APPROVE' } } }))
  assert.equal(approved.stage, 'SUBMISSION')
  assert.equal(approved.status, '等待提交')
  // 已批准但尚无 provider 执行引用：绝不显示外部执行编号。
  assert.ok(!approved.detail.includes('外部执行编号'))

  const rejected = responseStage(PROPOSAL({ status: 'REJECTED', approval: { request_id: 'req-1', decision: { decision: 'REJECT' } } }))
  assert.equal(rejected.stage, 'HUMAN')
  assert.equal(rejected.status, '已驳回')

  const denied = responseStage(PROPOSAL({ status: 'DENIED', approval: null, policy_reason: '策略拒绝' }))
  assert.equal(denied.stage, 'POLICY')
  assert.equal(denied.detail, '策略拒绝')

  // 提交被 provider 明确拒绝 ≠ 已提交，也 ≠ 有执行。
  const submitFailed = responseStage(PROPOSAL({ status: 'APPROVED', submission: { status: 'FAILED_DEFINITIVE' }, approval: { decision: { decision: 'APPROVE' } } }))
  assert.equal(submitFailed.stage, 'SUBMISSION')
  assert.equal(submitFailed.status, '提交失败')
  assert.ok(!submitFailed.detail.includes('外部执行编号'))

  const attention = responseStage(PROPOSAL({ status: 'APPROVED', submission: { status: 'ATTENTION_REQUIRED' } }))
  assert.equal(attention.stage, 'SUBMISSION')
  assert.equal(attention.status, '需要人工处理')

  const executing = responseStage(PROPOSAL({
    status: 'SUBMITTED',
    execution: { provider: 'hisiem', status: 'RUNNING', external_execution_id: 'soar-9' },
  }))
  assert.equal(executing.stage, 'EXECUTION')
  assert.equal(executing.status, '执行中')
  assert.equal(executing.detail, 'HISIEM 正在执行，页面会自动刷新')
  // 摘要是「状态」而不是技术身份：外部执行编号只出现在响应页签的执行详情里。
  assert.ok(!executing.detail.includes('soar-9'))

  const executed = responseStage(PROPOSAL({
    status: 'SUBMITTED',
    execution: { provider: 'hisiem', status: 'SUCCEEDED', external_execution_id: 'soar-9' },
  }))
  assert.equal(executed.status, '成功')
  assert.equal(executed.detail, 'HISIEM 已返回最终执行状态')
  assert.ok(!executed.detail.includes('soar-9'))

  assert.equal(responseStage(null).stage, null)
})

test('摘要取生命周期推进得最远的提案', () => {
  const waiting = PROPOSAL({ proposal_id: 'p-wait' })
  const exec = PROPOSAL({
    proposal_id: 'p-exec', status: 'SUBMITTED',
    execution: { provider: 'hisiem', status: 'SUCCEEDED', external_execution_id: 'soar-1' },
  })
  assert.equal(furthestProposal([waiting, exec]).proposal_id, 'p-exec')
  assert.equal(furthestProposal([exec, waiting]).proposal_id, 'p-exec')
  assert.equal(furthestProposal([]), null)
  assert.equal(furthestProposal(null), null)
})

test('是否需要分析师操作只由持久状态推导，且顺序即优先级', () => {
  // 提交状态不确定优先级最高：本地重试已耗尽，必须人工核对。
  const attention = analystAction({
    status: 'COMPLETED', canDecide: true,
    proposals: [PROPOSAL(), PROPOSAL({ proposal_id: 'p-2', submission: { status: 'ATTENTION_REQUIRED' } })],
  })
  assert.equal(attention.kind, ANALYST_ACTION.SUBMISSION_ATTENTION_REQUIRED)
  assert.equal(attention.required, true)

  // 待审批：有审批权限的人需要动作，其他人只是等待。
  const mine = analystAction({ status: 'COMPLETED', canDecide: true, proposals: [PROPOSAL()] })
  assert.equal(mine.kind, ANALYST_ACTION.APPROVAL_REQUIRED)
  assert.equal(mine.required, true)
  const others = analystAction({ status: 'COMPLETED', canDecide: false, proposals: [PROPOSAL()] })
  assert.equal(others.kind, ANALYST_ACTION.APPROVAL_PENDING_OTHERS)
  assert.equal(others.required, false)

  assert.equal(analystAction({ status: 'RUNNING', proposals: [] }).kind, ANALYST_ACTION.INVESTIGATION_RUNNING)
  assert.equal(analystAction({ status: 'COMPLETED', proposals: [] }).kind, ANALYST_ACTION.PROPOSAL_OPTIONAL)
  assert.equal(analystAction({ status: 'CANCELLED', proposals: [] }).kind, ANALYST_ACTION.NONE)
  assert.equal(analystAction({}).kind, ANALYST_ACTION.NONE)
  // 「需要操作」永远带文字说明，不靠颜色表达。
  for (const kind of Object.values(ANALYST_ACTION)) {
    const action = Object.values(ANALYST_ACTION).includes(kind)
    assert.ok(action)
  }
  assert.ok(attention.label.length > 0 && attention.description.length > 0)
})

test('待办判定与生命周期阶段一致，不产生自相矛盾的摘要', () => {
  // 已经进入执行阶段的提案不再是「等待审批」，即使它历史上曾处于 WAITING_APPROVAL。
  const executing = PROPOSAL({
    status: 'WAITING_APPROVAL',
    execution: { provider: 'hisiem', status: 'RUNNING', external_execution_id: 'soar-1' },
  })
  const action = analystAction({ status: 'COMPLETED', canDecide: true, proposals: [executing] })
  assert.notEqual(action.kind, ANALYST_ACTION.APPROVAL_REQUIRED)
  assert.notEqual(action.kind, ANALYST_ACTION.APPROVAL_PENDING_OTHERS)
  assert.equal(action.required, false)
})

test('响应建议的文案不得声称平台没有审批入口', async () => {
  const { readFile } = await import('node:fs/promises')
  const source = await readFile(new URL('../components/copilot/ResponseRecommendationList.vue', import.meta.url), 'utf8')
  // 平台确实提供有界提案 → 策略 → 人工审批路径，因此这条 P1 时期的措辞已经不成立。
  assert.ok(!source.includes('不提供执行'))
  assert.ok(!source.includes('审批入口'))
  assert.ok(source.includes('不是执行命令'))
  assert.ok(source.includes('经策略判定与人工审批'))
})

test('Landing 摘要统计平台事实与支持性上下文，不伪造结论', () => {
  const summary = workspaceStateSummary({
    status: 'COMPLETED',
    evidence: [
      EVIDENCE('ev-1', 'HISIEM_LOG_SEARCH'),
      EVIDENCE('ev-2', 'HISIEM_EVENT'),
      EVIDENCE('ev-3', 'KNOWLEDGE'),
    ],
    findings: [{ finding_id: 'f-1', statement: 'A', evidence_citations: ['ev-1'] }],
    result: { result_id: 'r-1', finding_ids: ['f-1'], verdict: { disposition: 'INCONCLUSIVE', summary: 's', confidence: 0.2 } },
    proposals: [],
    canDecide: true,
  })
  assert.equal(summary.evidenceCount, 3)
  assert.equal(summary.platformCount, 2)
  assert.equal(summary.contextCount, 1)
  assert.equal(summary.findingCount, 1)
  assert.equal(summary.verdictDisposition, 'INCONCLUSIVE')
  assert.equal(summary.verdictText, '证据不足')
  assert.equal(summary.confidenceText, '20%')
  assert.equal(summary.response, null)

  // 结论尚未生成时不得编造。
  const running = workspaceStateSummary({ status: 'RUNNING', evidence: [], findings: [], result: null, proposals: [] })
  assert.equal(running.verdictText, '尚未生成结论')
  assert.equal(running.confidenceText, '—')
  assert.equal(running.action.kind, ANALYST_ACTION.INVESTIGATION_RUNNING)
})
