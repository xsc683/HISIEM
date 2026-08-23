import { Space, Typography } from 'antd'

// 展示层统一使用浏览器本地时区；接口和 Elasticsearch 仍保留原始 UTC ISO-8601 值。
export const LOCAL_TIME_ZONE = Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC'
export const LOCAL_TIME_LABEL = LOCAL_TIME_ZONE === 'Asia/Shanghai' ? '北京时间 (UTC+8)' : LOCAL_TIME_ZONE

export const STATUS_TAG = {
  open: 'red', acknowledged: 'orange', investigating: 'gold', resolved: 'blue', closed: 'default',
}

export const VERDICT_TAG = {
  true_positive: 'red', false_positive: 'green', duplicate: 'gray',
}

export function RISK_COLOR(score) {
  if (score >= 80) return 'red'
  if (score >= 60) return 'orange'
  if (score >= 40) return 'gold'
  return 'green'
}

export function formatPlatformTime(value) {
  if (!value) return '—'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return String(value)
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false,
  }).format(date)
}

export function timeTitle(value) {
  return value ? `原始 UTC：${value}；页面显示：${LOCAL_TIME_LABEL}` : undefined
}

export function TimeText({ value }) {
  return <Typography.Text title={timeTitle(value)}>{formatPlatformTime(value)}</Typography.Text>
}

export function alertSource(alert) {
  const direct = alert?.['log.source_name'] || alert?.['log.source_id']
  if (direct) return direct
  const related = Array.isArray(alert?.related_events)
    ? alert.related_events.find((event) => event?.['log.source_name'] || event?.['log.source_id'])
    : null
  return related?.['log.source_name'] || related?.['log.source_id'] || '未标记数据源'
}

export function AvatarUser({ username, role }) {
  const colors = { admin: '#f5222d', analyst: '#1677ff', ops: '#52c41a', audit: '#722ed1' }
  return (
    <Space size="small">
      <div style={{
        width: 28, height: 28, borderRadius: '50%', background: colors[role] || '#1677ff',
        color: '#fff', display: 'flex', alignItems: 'center', justifyContent: 'center',
        fontSize: 13, fontWeight: 600,
      }}>
        {username[0]?.toUpperCase()}
      </div>
      <span>{username} · {role}</span>
    </Space>
  )
}
