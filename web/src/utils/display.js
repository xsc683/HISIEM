export const LOCAL_TIME_LABEL = '浏览器本地时间（Asia/Shanghai 环境为 UTC+8）'

export const labels = {
  status: {
    open: '待处置', acknowledged: '已确认', investigating: '调查中', resolved: '已解决', closed: '已关闭',
    active: '运行中', stopped: '已停用', creating: '配置中', failed: '失败', succeeded: '成功', queued: '排队中',
    waiting_approval: '等待审批', waiting_child: '等待子流程', paused: '已暂停', cancelled: '已取消', running: '执行中',
    draft: '草稿', pending: '待执行', success: '成功', waiting: '等待中', waiting_human: '等待人工',
    disabled: '已停用', approved: '已批准', published: '已发布', rejected: '已拒绝', retired: '已退役',
  },
  severity: { low: '低', medium: '中', high: '高', critical: '严重' },
  verdict: { true_positive: '真实攻击', false_positive: '误报', duplicate: '重复告警' },
  category: { single_event: '单事件', window: '窗口聚合', cep: '事件序列', baseline: '统计基线' },
  role: { admin: '管理员', analyst: '分析师', ops: '运维', audit: '审计员' },
  protocol: { tcp: 'TCP', syslog: 'Syslog', file: '文件' },
  criticality: { low: '低', medium: '中', high: '高', extreme: '极高' },
}

export function displayLabel(group, value) {
  return labels[group]?.[value] || value || '—'
}

export function statusColor(value) {
  if (value === 'UP') return 'green'
  if (value === 'DOWN') return 'red'
  if (['active', 'succeeded', 'success', 'approved', 'published', 'resolved'].includes(value)) return 'green'
  if (['critical', 'failed', 'rejected', 'closed', 'cancelled'].includes(value)) return 'red'
  if (['high', 'creating', 'queued', 'pending', 'waiting', 'waiting_human', 'pending_approval', 'waiting_approval', 'waiting_child', 'paused', 'disabled'].includes(value)) return 'orange'
  if (['investigating', 'acknowledged', 'running'].includes(value)) return 'blue'
  return 'default'
}

export function riskColor(score = 0) {
  if (score >= 80) return 'red'
  if (score >= 60) return 'orange'
  if (score >= 40) return 'gold'
  return 'green'
}

export function formatTime(value) {
  if (!value) return '—'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return String(value)
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false,
  }).format(date)
}

export function entityOf(alert = {}) {
  return alert['alert.entity'] || alert['source.ip'] || alert['user.name'] || alert['host.name'] || '—'
}
