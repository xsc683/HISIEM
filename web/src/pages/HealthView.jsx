import { Button, Card, Empty, Space, Tag, Typography } from 'antd'
import { TimeText } from '../components/common.jsx'

export default function HealthView({ health, sources, healthDetail, healthLoading, handleHealthDetail }) {
  return (
    <Card>
      {health.length === 0 && <Empty description="暂无数据源事件(接入数据源并生效后,事件带 log.source_id 可聚合)" />}
      <Space direction="vertical" size="middle" style={{ width: '100%' }}>
        {health.map((s) => (
          <Card key={s.sourceId} size="small" style={s.anomalous ? { borderColor: '#f5222d' } : {}}
            title={<Space><strong>{s.sourceName || '(未命名)'}</strong><code style={{ fontSize: 12, color: '#999' }}>{s.sourceId}</code>{(() => { const source = sources.find((item) => item.id === s.sourceId); if (!source) return null; const endpoint = source.protocol === 'file' ? source.path : `${source.protocol}:${source.port}`; return <Tag color="blue">接入 {endpoint}</Tag> })()}{s.status && <Tag color={s.status === 'active' ? (s.anomalous ? 'orange' : 'green') : s.status === 'failed' ? 'red' : 'default'}>{s.status}</Tag>}{s.anomalous && <Tag color="red">⚠ 解析异常({s.reason})</Tag>}</Space>}
            extra={<Button size="small" loading={healthLoading} onClick={() => handleHealthDetail(s.sourceId)}>详情(趋势/失败日志)</Button>}>
            <Space size="large"><span>近 1h 成功 <b>{s.events1h}</b> 条</span><span>总尝试 <b>{s.totalEvents1h ?? s.events1h}</b> 条</span><span>近 24h <b>{s.events24h}</b> 条</span><span>失败率 <b style={{ color: s.failRate > 5 ? '#f5222d' : '#52c41a' }}>{s.failRate}%</b> ({s.failures1h} 条)</span><span>最后收到 <b><TimeText value={s.lastSeen} /></b></span></Space>
            {healthDetail && healthDetail.sourceId === s.sourceId && <div style={{ marginTop: 12 }}>
              {healthDetail.trend.length > 0 && <div><Typography.Text type="secondary" style={{ fontSize: 12 }}>近 24h 事件/失败趋势(红=失败):</Typography.Text><div style={{ display: 'flex', alignItems: 'flex-end', gap: 2, height: 60, marginTop: 6 }}>{healthDetail.trend.map((t, i) => <div key={i} title={`${t.bucket}: 事件 ${t.events} / 失败 ${t.failures}`} style={{ width: 9, background: t.failures > 0 ? '#f5222d' : '#52c41a', height: Math.max(2, Math.min(60, (t.totalEvents || t.events || 0) * 3)), borderRadius: '2px 2px 0 0' }} />)}</div></div>}
              {healthDetail.failures.length > 0 && <details style={{ marginTop: 8 }}><summary style={{ fontSize: 13 }}>最近解析失败日志({healthDetail.failures.length} 条)</summary><div style={{ maxHeight: 200, overflow: 'auto', marginTop: 6 }}>{healthDetail.failures.map((f, i) => <div key={i} style={{ fontSize: 12, marginBottom: 4 }}><TimeText value={f['@timestamp']} /> {f.message}</div>)}</div></details>}
            </div>}
          </Card>
        ))}
      </Space>
    </Card>
  )
}
