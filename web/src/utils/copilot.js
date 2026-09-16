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
  RESPONSE_SUBMISSION_QUEUED: '提交排队', RESPONSE_SUBMISSION_RETRYING: '提交重试',
  RESPONSE_SUBMISSION_FAILED: '提交失败',
  RESPONSE_SUBMISSION_ATTENTION_REQUIRED: '提交需人工处理',
}
export function timelineKindLabel(kind) { return TIMELINE_KIND_LABELS[kind] || kind || '—' }

// 时间线条目状态覆盖 kind 的默认标题：已批准但尚未提交时不存在外部执行身份，
// 只能显示“已批准 / 等待提交”，绝不编造执行 ID。
const TIMELINE_STATUS_LABELS = {
  AWAITING_SUBMISSION: '已批准 / 等待提交',
  SUBMISSION_RETRYING: '已批准 / 提交重试中',
  SUBMISSION_FAILED: '提交失败',
  SUBMISSION_ATTENTION_REQUIRED: '提交状态不确定 / 需要人工处理',
}
export function timelineEntryLabel(entry) {
  const status = entry?.status
  if (status && TIMELINE_STATUS_LABELS[status]) return TIMELINE_STATUS_LABELS[status]
  return timelineKindLabel(entry?.kind)
}

// 时间线状态标签：有专门中文名的用中文名，其余沿用原样(如策略判定/工具状态)。
const TIMELINE_STATUS_NAMES = {
  AWAITING_SUBMISSION: '等待提交',
  SUBMISSION_RETRYING: '提交重试中',
  SUBMISSION_FAILED: '提交失败',
  SUBMISSION_ATTENTION_REQUIRED: '需要人工处理',
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
      'RESPONSE_APPROVED', 'RESPONSE_REJECTED',
      'RESPONSE_SUBMISSION_QUEUED', 'RESPONSE_SUBMISSION_RETRYING', 'RESPONSE_SUBMISSION_FAILED',
      'RESPONSE_SUBMISSION_ATTENTION_REQUIRED',
      'RESPONSE_EXECUTION_QUEUED',
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

// 本地提交生命周期：与 provider 执行状态是两件事。provider 明确拒绝的是「这次提交」，
// 不是某次执行 —— 因为根本没有产生执行。
export const SUBMISSION_STATUS_LABELS = {
  PENDING: '等待提交', RETRYING: '提交重试中', SUBMITTED: '已提交', FAILED_DEFINITIVE: '提交失败',
  ATTENTION_REQUIRED: '需要人工处理',
}
const SUBMISSION_STATUS_COLORS = {
  PENDING: 'default', RETRYING: 'orange', SUBMITTED: 'blue', FAILED_DEFINITIVE: 'red',
  ATTENTION_REQUIRED: 'volcano',
}
export function submissionStatusLabel(status) { return SUBMISSION_STATUS_LABELS[status] || status || '—' }
export function submissionStatusColor(status) { return SUBMISSION_STATUS_COLORS[status] || 'default' }

// provider 明确拒绝了这次提交：没有 provider 执行，也不会有外部执行 ID，且不会被自动重试。
export function proposalSubmissionFailed(proposal) {
  return proposal?.submission?.status === 'FAILED_DEFINITIVE'
}

// 自动重试预算已耗尽，但失败始终是瞬时/不确定的：既不能声称 provider 拒绝，
// 也不能声称没有执行 —— 需要人工/运维介入。这是本地终态，不再自动重试。
export function proposalSubmissionNeedsAttention(proposal) {
  return proposal?.submission?.status === 'ATTENTION_REQUIRED'
}

// 已批准但还没有 provider 执行引用 = 本地持久提交意图已排队，等待 submit worker 真正调用
// HISIEM。此时没有外部执行 ID 可展示(§6)。提交失败不是「等待提交」，必须如实显示。
export function proposalAwaitingSubmission(proposal) {
  if (proposalSubmissionFailed(proposal)) return false
  if (proposalSubmissionNeedsAttention(proposal)) return false
  return proposal?.status === 'APPROVED' && !proposal?.execution
}

// 已批准但尚未提交，且 provider 还在重试 —— 同样是本地状态，没有外部执行 ID。
export function proposalSubmissionRetrying(proposal) {
  if (proposalSubmissionFailed(proposal)) return false
  if (proposalSubmissionNeedsAttention(proposal)) return false
  return proposal?.status === 'APPROVED'
    && !proposal?.execution
    && proposal?.submission?.status === 'RETRYING'
}

/**
 * 响应是否还在推进，从而需要继续轮询。
 *
 * - 提交被 provider 明确拒绝 = 终态，停止轮询；
 * - 自动重试预算耗尽、需要人工处理 = 终态，停止轮询；
 * - 已有 provider 执行 = 按 provider 状态判断，终态即停止；
 * - 已批准但还没有执行 = 本地提交仍在排队/重试，继续轮询。
 */
export function needsResponsePolling(proposals) {
  return (proposals || []).some((proposal) => {
    if (proposalSubmissionFailed(proposal)) return false
    if (proposalSubmissionNeedsAttention(proposal)) return false
    if (proposal?.execution) return !isExecutionTerminal(proposal.execution.status)
    return proposal?.status === 'APPROVED'
  })
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

// ---- Stage D §4/§8 证据权威类别（展示映射，不判定权威） --------------------------

// 冻结规则：HISIEM_* 是平台观测事实；KNOWLEDGE / SYSTEM 只是上下文，永远不能单独支撑
// 确定性结论；THREAT_INTEL 是外部情报。这与 Copilot 领域里的平台证据判定
// (`hisiem_soc_copilot.agent.graph.nodes`) 是同一条规则 —— 前端只把服务端已经持久化的
// source.type 映射成展示标签，不新增、不推断任何权威。
export const PLATFORM_EVIDENCE_SOURCE_TYPES = [
  'HISIEM_ALERT', 'HISIEM_EVENT', 'HISIEM_LOG_SEARCH', 'HISIEM_ENTITY',
]

const AUTHORITY_BY_SOURCE_TYPE = {
  HISIEM_ALERT: 'PLATFORM_FACT',
  HISIEM_EVENT: 'PLATFORM_FACT',
  HISIEM_LOG_SEARCH: 'PLATFORM_FACT',
  HISIEM_ENTITY: 'PLATFORM_FACT',
  THREAT_INTEL: 'THREAT_INTEL',
  KNOWLEDGE: 'KNOWLEDGE_CONTEXT',
  SYSTEM: 'SYSTEM_CONTEXT',
}

export const AUTHORITY_LABELS = {
  PLATFORM_FACT: '平台事实',
  KNOWLEDGE_CONTEXT: '支持性上下文',
  SYSTEM_CONTEXT: '系统上下文',
  THREAT_INTEL: '外部情报',
  UNKNOWN: '来源未分类',
}

const AUTHORITY_DESCRIPTIONS = {
  PLATFORM_FACT: 'HISIEM 直接观测到的平台事实（告警 / 事件 / 日志检索 / 实体）。',
  KNOWLEDGE_CONTEXT: '检索到的知识，用于理解与解释，本身不是观测事实，不能单独支撑确定性结论。',
  SYSTEM_CONTEXT: '平台产生的元数据（例如检测规则），属于上下文而非观测事实。',
  THREAT_INTEL: '来自威胁情报源的补充信息，属于上下文。',
  UNKNOWN: '来源类型未在已知分类中，请勿据此单独判断。',
}

export function evidenceAuthority(sourceType) {
  return AUTHORITY_BY_SOURCE_TYPE[sourceType] || 'UNKNOWN'
}
export function authorityLabel(authority) { return AUTHORITY_LABELS[authority] || AUTHORITY_LABELS.UNKNOWN }
export function authorityDescription(authority) {
  return AUTHORITY_DESCRIPTIONS[authority] || AUTHORITY_DESCRIPTIONS.UNKNOWN
}
export function isPlatformEvidence(sourceType) {
  return PLATFORM_EVIDENCE_SOURCE_TYPES.includes(sourceType)
}
// 支持性上下文（知识/系统/情报）在 UI 上必须显式标注，避免被误读为观测事实。
export function isSupportingContext(authority) { return authority !== 'PLATFORM_FACT' }

// ---- Stage D §10 Knowledge / Citation 展示 ---------------------------------------

const EMPTY_FIELD = '—'

/**
 * 从已持久化的证据字段提取知识来源身份（纯函数）。
 *
 * 只取来源身份与检索时间：citation id / 文档与 chunk 身份 / source kind / source
 * version / 标题 / 摘录 / 检索模式 / ATT&CK release。**绝不**返回 rank、score 或
 * RRF 位置 —— 检索打分是执行元数据，不是权威，也不得当作置信度展示。
 */
export function knowledgeFacts(evidence) {
  if (!evidence) return null
  const observation = evidence.observation && typeof evidence.observation === 'object' ? evidence.observation : {}
  const reference = evidence.raw_reference && typeof evidence.raw_reference === 'object' ? evidence.raw_reference : {}
  const identity = reference.citation_identity && typeof reference.citation_identity === 'object'
    ? reference.citation_identity : {}
  const retrieval = reference.retrieval_provenance && typeof reference.retrieval_provenance === 'object'
    ? reference.retrieval_provenance : {}
  return {
    title: observation.title || '',
    excerpt: observation.excerpt || '',
    sourceKind: observation.source_kind || '',
    sourceVersion: observation.source_version || '',
    citationId: identity.citation_id || '',
    documentId: identity.document_id || '',
    documentVersionId: identity.document_version_id || '',
    chunkId: identity.chunk_id || '',
    retrievalMode: retrieval.mode || '',
    retrievedAt: retrieval.retrieved_at || '',
    techniqueId: observation.technique_id || reference.technique_id || '',
    framework: observation.framework || reference.framework || '',
    attackRelease: observation.authoritative_release || reference.authoritative_release || '',
    tactics: Array.isArray(observation.tactics) ? observation.tactics : [],
    platforms: Array.isArray(observation.platforms) ? observation.platforms : [],
  }
}

export function citationText(facts) {
  if (!facts) return EMPTY_FIELD
  return facts.citationId || EMPTY_FIELD
}

// ---- Stage D §11 AI 调查结论 与 人工处置的区分 ------------------------------------

// 结论只能来自服务端 InvestigationResult；UI 永远不能把「人工处置」显示成同一件事。
export const VERDICT_AUTHORITY_LABEL = 'AI 调查结论'
export const VERDICT_AUTHORITY_NOTE = '由 Agent 依据本次调查的证据生成，不等于分析师处置结论。'
export const ANALYST_DISPOSITION_LABEL = '分析师处置'

// INCONCLUSIVE 是「证据不足」，不是失败；不得渲染成错误态。
export function verdictIsInconclusive(disposition) { return disposition === 'INCONCLUSIVE' }

// 结论的支撑发现：只按持久 ID 解析，绝不做文本匹配。
export function supportingFindings(result, findings) {
  if (!result) return []
  const ids = new Set((result.finding_ids || []).map((id) => String(id)))
  if (ids.size === 0) return []
  return (findings || []).filter((finding) => ids.has(String(finding.finding_id)))
}

// ---- Stage D §12 响应生命周期（提案 → 策略 → 人工 → 提交 → 执行） ------------------

export const RESPONSE_STAGE_LABELS = {
  PROPOSAL: '响应提案', POLICY: '策略判定', HUMAN: '人工决策',
  SUBMISSION: '提交', EXECUTION: 'HISIEM 观测执行',
}
const RESPONSE_STAGE_RANK = { PROPOSAL: 0, POLICY: 1, HUMAN: 2, SUBMISSION: 3, EXECUTION: 4 }

/**
 * 把一个提案映射到它当前所处的生命周期阶段（纯展示，不合并任何中间状态）。
 *
 * 每个阶段都有各自的状态与措辞：提案 ≠ 审批 ≠ 提交 ≠ 执行成功。执行阶段只有
 * 服务端返回了 provider 执行引用才成立 —— 本地提交排队/重试不算执行。
 */
export function responseStage(proposal) {
  if (!proposal) return { stage: null, rank: -1, label: EMPTY_FIELD, status: '', detail: '' }
  if (proposal.execution) {
    // 摘要只讲「处于哪个阶段、什么状态」，不复述外部执行编号等技术身份 ——
    // 完整执行身份（provider / 外部执行 ID / 时间 / 错误）在响应页签的详情里展示。
    const finished = isExecutionTerminal(proposal.execution.status)
    return {
      stage: 'EXECUTION', rank: RESPONSE_STAGE_RANK.EXECUTION,
      label: RESPONSE_STAGE_LABELS.EXECUTION,
      status: executionStatusLabel(proposal.execution.status),
      detail: finished
        ? 'HISIEM 已返回最终执行状态'
        : 'HISIEM 正在执行，页面会自动刷新',
    }
  }
  if (proposalSubmissionFailed(proposal)) {
    return {
      stage: 'SUBMISSION', rank: RESPONSE_STAGE_RANK.SUBMISSION,
      label: RESPONSE_STAGE_LABELS.SUBMISSION,
      status: submissionStatusLabel(proposal.submission?.status),
      detail: 'HISIEM 明确拒绝这次提交，未产生任何执行，也不再自动重试',
    }
  }
  if (proposalSubmissionNeedsAttention(proposal)) {
    return {
      stage: 'SUBMISSION', rank: RESPONSE_STAGE_RANK.SUBMISSION,
      label: RESPONSE_STAGE_LABELS.SUBMISSION,
      status: submissionStatusLabel(proposal.submission?.status),
      detail: '提交结果不确定，需要人工核对；系统已停止自动重试',
    }
  }
  if (proposal.status === 'APPROVED') {
    return {
      stage: 'SUBMISSION', rank: RESPONSE_STAGE_RANK.SUBMISSION,
      label: RESPONSE_STAGE_LABELS.SUBMISSION,
      status: proposalSubmissionRetrying(proposal)
        ? submissionStatusLabel('RETRYING') : submissionStatusLabel('PENDING'),
      detail: '人工批准已进入持久化提交队列；HISIEM 返回执行编号前不显示任何外部执行身份',
    }
  }
  if (proposal.approval?.decision) {
    return {
      stage: 'HUMAN', rank: RESPONSE_STAGE_RANK.HUMAN,
      label: RESPONSE_STAGE_LABELS.HUMAN,
      status: proposal.approval.decision.decision === 'APPROVE' ? '已批准' : '已驳回',
      detail: proposal.approval.decision.decision === 'APPROVE'
        ? '人工已批准，等待持久化提交' : '人工已驳回，不会产生任何执行命令',
    }
  }
  if (proposal.status === 'WAITING_APPROVAL') {
    return {
      stage: 'HUMAN', rank: RESPONSE_STAGE_RANK.HUMAN,
      label: RESPONSE_STAGE_LABELS.HUMAN,
      status: responseProposalStatusLabel('WAITING_APPROVAL'),
      detail: '已通过策略判定，等待人工决定；批准前不会执行任何动作',
    }
  }
  // CREATED / DENIED：只到策略判定阶段
  return {
    stage: 'POLICY', rank: RESPONSE_STAGE_RANK.POLICY,
    label: RESPONSE_STAGE_LABELS.POLICY,
    status: responseProposalStatusLabel(proposal.status),
    detail: proposal.policy_reason || '',
  }
}

/** 生命周期中推进得最远的一个提案（同阶段时取最新创建、再按 ID 稳定排序）。 */
export function furthestProposal(proposals) {
  const list = [...(proposals || [])]
  if (!list.length) return null
  return list.sort((a, b) => {
    const rankA = responseStage(a).rank
    const rankB = responseStage(b).rank
    if (rankA !== rankB) return rankB - rankA
    const timeA = new Date(a?.created_at || 0).getTime() || 0
    const timeB = new Date(b?.created_at || 0).getTime() || 0
    if (timeA !== timeB) return timeB - timeA
    return String(b?.proposal_id || '').localeCompare(String(a?.proposal_id || ''))
  })[0]
}

// ---- Stage D §5 Landing 状态摘要 -------------------------------------------------

export const ANALYST_ACTION = {
  SUBMISSION_ATTENTION_REQUIRED: 'SUBMISSION_ATTENTION_REQUIRED',
  APPROVAL_REQUIRED: 'APPROVAL_REQUIRED',
  APPROVAL_PENDING_OTHERS: 'APPROVAL_PENDING_OTHERS',
  INVESTIGATION_RUNNING: 'INVESTIGATION_RUNNING',
  PROPOSAL_OPTIONAL: 'PROPOSAL_OPTIONAL',
  NONE: 'NONE',
}

/**
 * 「现在是否需要分析师操作」——只由持久状态推导，不看页面会话。
 *
 * 顺序即优先级：提交结果不确定（必须人工核对）→ 等待我审批 → 等待他人审批 →
 * 调查进行中 → 可选提案 → 无需操作。任何一项都不在 UI 里写业务状态。
 */
export function analystAction({ status, proposals, canDecide } = {}) {
  const list = proposals || []
  if (list.some(proposalSubmissionNeedsAttention)) {
    return {
      kind: ANALYST_ACTION.SUBMISSION_ATTENTION_REQUIRED, required: true,
      label: '需要人工核对提交状态',
      description: '自动提交重试预算已耗尽，但失败都是瞬时/不确定的：既不能断定 HISIEM 拒绝了这次提交，也不能断定没有产生执行，需要人工核对。',
    }
  }
  // 「等待审批」只对真正停在人工决策阶段的提案成立：用生命周期阶段判定而不是裸 status，
  // 这样即使上游同时携带更靠后的阶段信息，摘要也不会自相矛盾（例如既说“等待审批”又说“执行中”）。
  const awaitingDecision = list.filter((proposal) => (
    proposal?.status === 'WAITING_APPROVAL' && responseStage(proposal).stage === 'HUMAN'
  ))
  if (awaitingDecision.length) {
    return canDecide
      ? {
        kind: ANALYST_ACTION.APPROVAL_REQUIRED, required: true,
        label: '等待您审批响应提案',
        description: '响应提案已通过策略判定，正在等待具备审批权限的人做出决定；在批准之前不会执行任何动作。',
      }
      : {
        kind: ANALYST_ACTION.APPROVAL_PENDING_OTHERS, required: false,
        label: '等待他人审批',
        description: '响应提案正在等待具备审批权限的人处理；您当前只能查看。',
      }
  }
  if (isInvestigationActive(status)) {
    return {
      kind: ANALYST_ACTION.INVESTIGATION_RUNNING, required: false,
      label: 'Agent 调查中',
      description: '调查仍在进行，页面会在进行中自动刷新，无需操作。',
    }
  }
  if (status === 'COMPLETED' && list.length === 0) {
    return {
      kind: ANALYST_ACTION.PROPOSAL_OPTIONAL, required: false,
      label: '暂无待办',
      description: '调查已结束且没有响应提案；如研判认为需要处置，可在“响应”页签发起有界提案。',
    }
  }
  return {
    kind: ANALYST_ACTION.NONE, required: false,
    label: '无需操作',
    description: '当前没有需要分析师处理的待办。',
  }
}

/** Landing 摘要：一次回答「发生了什么 / 结论是什么 / 有什么支撑 / 要我做什么 / 响应怎样了」。 */
export function workspaceStateSummary({ status, evidence, findings, result, proposals, canDecide } = {}) {
  const evidenceCount = (evidence || []).length
  const findingCount = (findings || []).length
  const platformCount = (evidence || []).filter(
    (item) => isPlatformEvidence(item?.source?.type),
  ).length
  const proposal = furthestProposal(proposals)
  return {
    evidenceCount,
    platformCount,
    contextCount: evidenceCount - platformCount,
    findingCount,
    verdictDisposition: result?.verdict?.disposition || '',
    verdictText: result?.verdict ? verdictLabel(result.verdict.disposition) : '尚未生成结论',
    confidenceText: result?.verdict ? confidencePercent(result.verdict.confidence) : EMPTY_FIELD,
    action: analystAction({ status, proposals, canDecide }),
    response: proposal
      ? { stage: responseStage(proposal), proposalStatus: responseProposalStatusLabel(proposal.status) }
      : null,
  }
}

