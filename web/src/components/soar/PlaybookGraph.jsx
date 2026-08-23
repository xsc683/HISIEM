import { Empty, Space, Tag, Typography } from 'antd'

const TYPE_COLORS = {
  action: 'blue', decision: 'purple', approval: 'gold', delay: 'cyan', end: 'default',
}

export default function PlaybookGraph({ playbook }) {
  const nodes = playbook?.nodes || []
  if (nodes.length === 0) {
    return <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="V1 线性 Playbook（运行时兼容编译）" />
  }
  return (
    <div style={{ display: 'grid', gap: 8, margin: '8px 0' }}>
      <Typography.Text type="secondary">
        入口 <code>{playbook.entrypoint}</code> · 图格式 v{playbook.formatVersion} · {nodes.length} 个节点
      </Typography.Text>
      {nodes.map((node) => (
        <div key={node.id} style={{ border: '1px solid #e5e7eb', borderRadius: 8, padding: '9px 12px', background: node.id === playbook.entrypoint ? '#f0f7ff' : '#fff' }}>
          <Space wrap size={6}>
            <Tag color={TYPE_COLORS[node.type]}>{node.type}</Tag>
            <Typography.Text strong>{node.name}</Typography.Text>
            <code>{node.id}</code>
            {node.action && <Tag>{node.action}</Tag>}
            {node.join === 'all' && <Tag color="geekblue">等待全部分支</Tag>}
            {node.retry?.maxAttempts && <Tag color="orange">最多 {node.retry.maxAttempts} 次</Tag>}
          </Space>
          {(node.transitions || []).length > 0 && (
            <div style={{ marginTop: 7 }}>
              <Typography.Text type="secondary">路由：</Typography.Text>{' '}
              {(node.transitions || []).map((edge, index) => (
                <Tag key={`${edge.target}-${index}`} color={edge.on === 'failure' || edge.on === 'rejected' ? 'red' : 'green'}>
                  {edge.on || 'success'} → {edge.target}{edge.when ? ' [条件]' : ''}
                </Tag>
              ))}
            </div>
          )}
        </div>
      ))}
    </div>
  )
}
