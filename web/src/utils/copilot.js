// SOC Copilot 调查工作台的展示常量与纯函数（可被 node --test 直接测试）。

// 调查生命周期只描述“调查分析”本身：CREATED → RUNNING → COMPLETED | FAILED | CANCELLED。
// 响应工作流(提案 → 审批 → SOAR 执行)是调查完成之后独立的聚合生命周期，调查永远不会进入
// WAITING_APPROVAL / EXECUTING_RESPONSE —— 审批/驳回/执行都不改变调查状态。
export const INVESTIGATION_ACTIVE_STATUSES = ['CREATED', 'RUNNING']
export const INVESTIGATION_TERMINAL_STATUSES = ['COMPLETED', 'FAILED', 'CANCELLED']

export function isInvestigationActive(status) {
  return INVESTIGATION_ACTIVE_STATUSES.includes(status)
}

export function isInvestigationTerminal(status) {
  return INVESTIGATION_TERMINAL_STATUSES.includes(status)
}

const STATUS_LABELS = {
  CREATED: '已创建', RUNNING: '调查中',
  COMPLETED: '已完成', FAILED: '失败', CANCELLED: '已取消',
}
const STATUS_COLORS = {
  CREATED: 'default', RUNNING: 'blue',
  COMPLETED: 'green', FAILED: 'red', CANCELLED: 'default',
}
export function investigationStatusLabel(status) { return STATUS_LABELS[status] || status || '—' }
export function investigationStatusColor(status) { return STATUS_COLORS[status] || 'default' }

const PHASE_LABELS = { PLANNING: '规划中', INVESTIGATING: '调查取证', VERIFYING: '验证中', FINALIZING: '结案中' }
export function investigationPhaseLabel(phase) { return PHASE_LABELS[phase] || phase || '' }

const VERDICT_LABELS = { MALICIOUS: '恶意', BENIGN: '正常', INCONCLUSIVE: '证据不足' }
const VERDICT_COLORS = { MALICIOUS: 'red', BENIGN: 'green', INCONCLUSIVE: 'orange' }
export function verdictLabel(disposition) { return VERDICT_LABELS[disposition] || disposition || '—' }
export function verdictColor(disposition) { return VERDICT_COLORS[disposition] || 'default' }

const TOOL_STATUS_LABELS = { PENDING: '等待', RUNNING: '执行中', SUCCEEDED: '成功', FAILED: '失败', SKIPPED: '跳过' }
const TOOL_STATUS_COLORS = { PENDING: 'default', RUNNING: 'blue', SUCCEEDED: 'green', FAILED: 'red', SKIPPED: 'default' }
export function toolStatusLabel(status) { return TOOL_STATUS_LABELS[status] || status || '—' }
export function toolStatusColor(status) { return TOOL_STATUS_COLORS[status] || 'default' }

const HYPOTHESIS_STATUS_LABELS = { OPEN: '待评估', SUPPORTED: '已支持', CONTRADICTED: '已反驳', UNRESOLVED: '未定论' }
const HYPOTHESIS_STATUS_COLORS = { OPEN: 'default', SUPPORTED: 'red', CONTRADICTED: 'green', UNRESOLVED: 'orange' }
export function hypothesisStatusLabel(status) { return HYPOTHESIS_STATUS_LABELS[status] || status || '—' }
export function hypothesisStatusColor(status) { return HYPOTHESIS_STATUS_COLORS[status] || 'default' }

const RELATION_LABELS = { SUPPORTS: '支持', CONTRADICTS: '反驳', NEUTRAL: '中性' }
export function relationLabel(relation) { return RELATION_LABELS[relation] || relation || '—' }

const TIMELINE_KIND_LABELS = {
  INVESTIGATION_CREATED: '调查创建', INVESTIGATION_STARTED: '调查开始',
  PLAN_CREATED: '计划生成', PLAN_REVISED: '计划修订',
  TOOL_STARTED: '工具调用', TOOL_SUCCEEDED: '工具完成', TOOL_FAILED: '工具失败',
  EVIDENCE_RECORDED: '证据记录', HYPOTHESIS_ASSESSED: '假设评估', FINDING_RECORDED: '发现记录',
  RESULT_FINALIZED: '结论生成', INVESTIGATION_COMPLETED: '调查完成',
  INVESTIGATION_CANCELLED: '调查取消', INVESTIGATION_FAILED: '调查失败',
  RESPONSE_PROPOSAL_CREATED: '响应提案', RESPONSE_POLICY_EVALUATED: '策略判定',
  RESPONSE_APPROVAL_REQUESTED: '请求审批', RESPONSE_APPROVED: '已批准', RESPONSE_REJECTED: '已驳回',
  RESPONSE_EXECUTION_QUEUED: '执行提交', RESPONSE_EXECUTION_STARTED: '执行开始',
  RESPONSE_EXECUTION_SUCCEEDED: '执行成功', RESPONSE_EXECUTION_FAILED: '执行失败',
}
export function timelineKindLabel(kind) { return TIMELINE_KIND_LABELS[kind] || kind || '—' }

// 时间线条目状态覆盖 kind 的默认标题：已批准但尚未提交时不存在外部执行身份，
// 只能显示“已批准 / 等待提交”，绝不编造执行 ID。
const TIMELINE_STATUS_LABELS = {
  AWAITING_SUBMISSION: '已批准 / 等待提交',
}
export function timelineEntryLabel(entry) {
  const status = entry?.status
  if (status && TIMELINE_STATUS_LABELS[status]) return TIMELINE_STATUS_LABELS[status]
  return timelineKindLabel(entry?.kind)
}

// 时间线状态标签：有专门中文名的用中文名，其余沿用原样(如策略判定/工具状态)。
const TIMELINE_STATUS_NAMES = {
  AWAITING_SUBMISSION: '等待提交',
  REQUIRE_APPROVAL: '需要人工审批',
  DENY: '策略拒绝',
}
export function timelineStatusLabel(status) {
  if (!status) return ''
  return TIMELINE_STATUS_NAMES[status] || status
}

// 时间线过滤（docs §18）：仅按持久事实的 kind 过滤，不做任何计算/合成。
export const TIMELINE_FILTERS = [
  { key: 'all', label: '全部', kinds: null },
  { key: 'plan', label: '计划', kinds: ['PLAN_CREATED', 'PLAN_REVISED'] },
  { key: 'tools', label: '工具', kinds: ['TOOL_STARTED', 'TOOL_SUCCEEDED', 'TOOL_FAILED'] },
  { key: 'evidence', label: '证据', kinds: ['EVIDENCE_RECORDED', 'HYPOTHESIS_ASSESSED'] },
  { key: 'analysis', label: '分析', kinds: ['FINDING_RECORDED'] },
  { key: 'result', label: '结论', kinds: ['RESULT_FINALIZED', 'INVESTIGATION_COMPLETED', 'INVESTIGATION_CANCELLED', 'INVESTIGATION_FAILED'] },
  {
    key: 'response',
    label: '响应',
    kinds: [
      'RESPONSE_PROPOSAL_CREATED', 'RESPONSE_POLICY_EVALUATED', 'RESPONSE_APPROVAL_REQUESTED',
      'RESPONSE_APPROVED', 'RESPONSE_REJECTED', 'RESPONSE_EXECUTION_QUEUED',
      'RESPONSE_EXECUTION_STARTED', 'RESPONSE_EXECUTION_SUCCEEDED', 'RESPONSE_EXECUTION_FAILED',
    ],
  },
]
export function filterTimeline(entries, filterKey) {
  const filter = TIMELINE_FILTERS.find((item) => item.key === filterKey) || TIMELINE_FILTERS[0]
  if (!filter.kinds) return [...(entries || [])]
  return (entries || []).filter((entry) => filter.kinds.includes(entry.kind))
}

export function durationText(startedAt, finishedAt) {
  if (!startedAt || !finishedAt) return '—'
  const start = new Date(startedAt).getTime()
  const end = new Date(finishedAt).getTime()
  if (Number.isNaN(start) || Number.isNaN(end) || end < start) return '—'
  const totalSeconds = Math.round((end - start) / 1000)
  if (totalSeconds < 60) return `${totalSeconds} 秒`
  const minutes = Math.floor(totalSeconds / 60)
  const seconds = totalSeconds % 60
  if (minutes < 60) return `${minutes} 分 ${seconds} 秒`
  const hours = Math.floor(minutes / 60)
  return `${hours} 小时 ${minutes % 60} 分`
}

// confidence 取值在 [0,1]；展示为百分比，缺失时返回 '—'。
export function confidencePercent(confidence) {
  if (typeof confidence !== 'number' || Number.isNaN(confidence)) return '—'
  return `${Math.round(confidence * 100)}%`
}

// P2 响应工作流展示：提案/执行/策略/动作的标签与颜色。
export const RESPONSE_PROPOSAL_STATUS_LABELS = {
  CREATED: '已创建', DENIED: '策略拒绝', WAITING_APPROVAL: '待审批',
  APPROVED: '已批准', REJECTED: '已驳回', SUBMITTED: '已提交',
}
export const RESPONSE_PROPOSAL_STATUS_COLORS = {
  CREATED: 'default', DENIED: 'red', WAITING_APPROVAL: 'orange',
  APPROVED: 'green', REJECTED: 'default', SUBMITTED: 'blue',
}
export function responseProposalStatusLabel(status) { return RESPONSE_PROPOSAL_STATUS_LABELS[status] || status || '—' }
export function responseProposalStatusColor(status) { return RESPONSE_PROPOSAL_STATUS_COLORS[status] || 'default' }

const EXECUTION_STATUS_LABELS = { QUEUED: '排队中', RUNNING: '执行中', SUCCEEDED: '成功', FAILED: '失败' }
const EXECUTION_STATUS_COLORS = { QUEUED: 'default', RUNNING: 'blue', SUCCEEDED: 'green', FAILED: 'red' }
export function executionStatusLabel(status) { return EXECUTION_STATUS_LABELS[status] || status || '—' }
export function executionStatusColor(status) { return EXECUTION_STATUS_COLORS[status] || 'default' }
export function isExecutionTerminal(status) { return status === 'SUCCEEDED' || status === 'FAILED' }

const POLICY_DECISION_LABELS = { REQUIRE_APPROVAL: '需要人工审批', DENY: '策略拒绝' }
const POLICY_DECISION_COLORS = { REQUIRE_APPROVAL: 'orange', DENY: 'red' }
export function policyDecisionLabel(decision) { return POLICY_DECISION_LABELS[decision] || decision || '—' }
export function policyDecisionColor(decision) { return POLICY_DECISION_COLORS[decision] || 'default' }

const RESPONSE_ACTION_LABELS = {
  START_SOAR_PLAYBOOK: '启动 SOAR 剧本', BLOCK_SOURCE_IP: '封禁源 IP',
  DISABLE_ACCOUNT: '禁用账户', ISOLATE_HOST: '隔离主机',
}
export function responseActionLabel(actionKey) { return RESPONSE_ACTION_LABELS[actionKey] || actionKey || '—' }

// 只有已批准的人工决策可以进入执行；其余状态没有任何执行入口。
export function canDecideProposal(status) { return status === 'WAITING_APPROVAL' }

// 已批准但还没有 provider 执行引用 = 本地持久提交意图已排队，等待 submit worker 真正调用
// HISIEM。此时没有外部执行 ID 可展示(§6)。
export function proposalAwaitingSubmission(proposal) {
  return proposal?.status === 'APPROVED' && !proposal?.execution
}

// 响应仍在推进(等待提交或已有非终态执行)时才需要继续轮询。
export function needsResponsePolling(proposals) {
  return (proposals || []).some(
    (proposal) => proposalAwaitingSubmission(proposal)
      || (proposal?.execution && !isExecutionTerminal(proposal.execution.status)),
  )
}

// ---- P2 §4 有界类型化提案创建 ----------------------------------------------------

// 唯一可执行动作，只读固定；浏览器没有 action key 输入框。
export const RESPONSE_PROPOSAL_ACTION_KEY = 'START_SOAR_PLAYBOOK'
export const RESPONSE_REASON_MAX_LENGTH = 500

// 只有“已发布且启用”的剧本可以成为下拉选项，且只暴露 id/名称 —— 没有自由文本。
export function selectablePlaybooks(playbooks) {
  return (playbooks || [])
    .filter((item) => item && item.id && item.status === 'published' && item.enabled === true)
    .map((item) => ({ value: item.id, label: item.name || item.id }))
}

// 仅管理员/分析师可发起；审计角色只读。调查必须已经完成，且本次调查还没有提案。
export function canCreateResponseProposal({ status, proposals, role, evidenceCount } = {}) {
  if (!['admin', 'analyst'].includes(role)) return false
  if (status !== 'COMPLETED') return false
  if ((proposals || []).length > 0) return false
  return Number(evidenceCount || 0) > 0
}

/**
 * 构造响应提案请求体(纯函数，可测)。
 *
 * 请求体只含有界契约：action_key(固定)、evidence_ids(本次调查的证据)、parameters.playbook_id、
 * reason。绝不包含 target(目标由服务端从调查的 source_alert_ref 派生)、tenant_id 或 actor ——
 * 这三者都来自可信服务端上下文。
 */
export function buildProposalRequest({ playbookId, evidenceIds, reason } = {}) {
  const id = String(playbookId || '').trim()
  if (!id) throw new Error('请选择一个已发布且启用的 SOAR 剧本')
  const ids = (evidenceIds || []).map((value) => String(value || '').trim()).filter(Boolean)
  if (ids.length === 0) throw new Error('至少需要选择一条本次调查的证据')
  const text = String(reason || '').trim()
  if (!text) throw new Error('请填写响应理由')
  if (text.length > RESPONSE_REASON_MAX_LENGTH) {
    throw new Error(`响应理由不能超过 ${RESPONSE_REASON_MAX_LENGTH} 个字符`)
  }
  return {
    action_key: RESPONSE_PROPOSAL_ACTION_KEY,
    evidence_ids: ids,
    parameters: { playbook_id: id },
    reason: text,
  }
}
