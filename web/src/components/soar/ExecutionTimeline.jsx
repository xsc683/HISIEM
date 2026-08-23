import { Empty, Timeline, Typography } from 'antd'
import { TimeText } from '../common.jsx'

const EVENT_COLORS = {
  'execution.failed': 'red', 'execution.cancelled': 'gray', 'execution.rejected': 'orange',
  'execution.succeeded': 'green', 'node.retry_scheduled': 'orange',
  'node.failure_routed': 'red', 'approval.requested': 'gold',
}

export default function ExecutionTimeline({ events }) {
  if (!events?.length) return <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无执行事件" />
  return <Timeline style={{ marginTop: 18 }} items={events.map((event) => ({
    color: EVENT_COLORS[event.eventType] || 'blue',
    children: <div>
      <Typography.Text strong>{event.eventType}</Typography.Text>
      {event.nodeId && <> · <code>{event.nodeId}</code></>}
      <Typography.Text type="secondary"> · {event.actor} · <TimeText value={event.createdAt} /></Typography.Text>
      {event.details && Object.keys(event.details).length > 0 && (
        <pre style={{ margin: '5px 0 0', maxHeight: 160, overflow: 'auto', whiteSpace: 'pre-wrap' }}>{JSON.stringify(event.details, null, 2)}</pre>
      )}
    </div>,
  }))} />
}
