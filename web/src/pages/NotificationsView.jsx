import { Button, Card, Empty, Space, Tag } from 'antd'
import { TimeText } from '../components/common.jsx'

export default function NotificationsView({ notifs, unreadCount, handleReadAllNotifs, handleReadNotif, handleDelNotif }) {
  return (
    <Card title={<>通知中心 {unreadCount > 0 && <Tag color="gold">🔔 {unreadCount} 条未读</Tag>}</>} extra={<Button size="small" onClick={handleReadAllNotifs}>全部已读</Button>}>
      {notifs.length === 0 && <Empty description="暂无通知(规则部署 / 实体风险重算 / 数据源健康异常会在此提示)" />}
      <Space direction="vertical" style={{ width: '100%' }}>
        {notifs.slice().reverse().map((n) => (
          <div key={n.id} style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '10px 14px', borderRadius: 8, border: '1px solid #f0f0f0', background: n.read ? '#fafafa' : '#fffbe6' }}>
            <div><Tag color="blue">{n.type}</Tag> {n.message}<div style={{ fontSize: 11, color: '#999' }}><TimeText value={n.timestamp} /></div></div>
            <Space>{!n.read && <Button size="small" onClick={() => handleReadNotif(n.id)}>已读</Button>}<Button size="small" danger onClick={() => handleDelNotif(n.id)}>删</Button></Space>
          </div>
        ))}
      </Space>
    </Card>
  )
}
