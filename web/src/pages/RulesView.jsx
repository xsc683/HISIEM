import { Badge, Button, Card, Space, Table, Tag, Typography } from 'antd'
import { ThunderboltOutlined } from '@ant-design/icons'
import { RISK_COLOR } from '../components/common.jsx'

export default function RulesView({ detRules, ruleHits, deploying, deployMsg, mitre, handleDeployRules, handleToggleRule }) {
  return (
    <Card>
      <Space direction="vertical" size="middle" style={{ width: '100%' }}>
        <Space>
          <Button type="primary" icon={<ThunderboltOutlined />} loading={deploying} onClick={handleDeployRules}>
            {deploying ? '部署生效中(约 15-35s)…' : '部署生效(同步规则 + 重启检测 job)'}
          </Button>
          {deployMsg && <Tag color={deployMsg.startsWith('部署失败') ? 'red' : 'green'}>{deployMsg}</Tag>}
        </Space>
        <Table rowKey="id" dataSource={detRules} size="small" pagination={{ pageSize: 10, showTotal: (t) => `共 ${t} 条规则` }}
          columns={[
            { title: '规则 ID', dataIndex: 'id', render: (v) => <code>{v}</code> }, { title: '名称', dataIndex: 'name' },
            { title: '近 7d 命中', render: (_, r) => ruleHits[r.id] == null ? <Typography.Text type="secondary">—</Typography.Text> : <Badge count={ruleHits[r.id]} showZero color={ruleHits[r.id] > 0 ? '#1677ff' : '#bfbfbf'} /> },
            { title: '类别', dataIndex: 'category', render: (v) => <Tag>{v}</Tag> }, { title: 'type', dataIndex: 'type', render: (v) => <code>{v}</code> },
            { title: '风险分', dataIndex: 'riskScore', sorter: (a, b) => a.riskScore - b.riskScore, render: (v) => <Tag color={RISK_COLOR(v)}>{v}</Tag> },
            { title: 'MITRE', dataIndex: 'tags', render: (tags) => (tags || []).map((t, i) => <Tag key={i} color="blue">{t}</Tag>) },
            { title: '状态', dataIndex: 'enabled', render: (en) => en ? <Tag color="green">启用</Tag> : <Tag>停用</Tag> },
            { title: '启停', render: (_, r) => <Button size="small" danger={r.enabled} onClick={() => handleToggleRule(r.id)}>{r.enabled ? '停用' : '启用'}</Button> },
          ]} />
        {mitre.coverage && mitre.coverage.length > 0 && (
          <details>
            <summary>MITRE ATT&CK 覆盖({mitre.coverage.length} 条)</summary>
            <Table rowKey={(_, i) => i} size="small" pagination={false} dataSource={mitre.coverage}
              columns={[{ title: '技术', dataIndex: 'technique', render: (v) => <code>{v}</code> }, { title: '规则', dataIndex: 'ruleId' }, { title: '覆盖', dataIndex: 'coverage' }]} />
          </details>
        )}
      </Space>
    </Card>
  )
}
