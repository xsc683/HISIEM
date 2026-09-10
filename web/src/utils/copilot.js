// SOC Copilot 调查工作台的展示常量与纯函数（可被 node --test 直接测试）。

export const INVESTIGATION_ACTIVE_STATUSES = ['CREATED', 'RUNNING', 'WAITING_APPROVAL', 'EXECUTING_RESPONSE']
export const INVESTIGATION_TERMINAL_STATUSES = ['COMPLETED', 'FAILED', 'CANCELLED']

export function isInvestigationActive(status) {
  return INVESTIGATION_ACTIVE_STATUSES.includes(status)
}

export function isInvestigationTerminal(status) {
  return INVESTIGATION_TERMINAL_STATUSES.includes(status)
}

const STATUS_LABELS = {
  CREATED: '已创建', RUNNING: '调查中', WAITING_APPROVAL: '等待审批', EXECUTING_RESPONSE: '执行响应',
  COMPLETED: '已完成', FAILED: '失败', CANCELLED: '已取消',
}
const STATUS_COLORS = {
  CREATED: 'default', RUNNING: 'blue', WAITING_APPROVAL: 'orange', EXECUTING_RESPONSE: 'orange',
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
}
export function timelineKindLabel(kind) { return TIMELINE_KIND_LABELS[kind] || kind || '—' }

// 时间线过滤（docs §18）：仅按持久事实的 kind 过滤，不做任何计算/合成。
export const TIMELINE_FILTERS = [
  { key: 'all', label: '全部', kinds: null },
  { key: 'plan', label: '计划', kinds: ['PLAN_CREATED', 'PLAN_REVISED'] },
  { key: 'tools', label: '工具', kinds: ['TOOL_STARTED', 'TOOL_SUCCEEDED', 'TOOL_FAILED'] },
  { key: 'evidence', label: '证据', kinds: ['EVIDENCE_RECORDED', 'HYPOTHESIS_ASSESSED'] },
  { key: 'analysis', label: '分析', kinds: ['FINDING_RECORDED'] },
  { key: 'result', label: '结论', kinds: ['RESULT_FINALIZED', 'INVESTIGATION_COMPLETED', 'INVESTIGATION_CANCELLED', 'INVESTIGATION_FAILED'] },
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
