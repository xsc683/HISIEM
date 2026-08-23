import { Alert, Button, Card, Empty, Input, Select, Space, Table, Tabs, Tag } from 'antd'

export default function CriticalityView({ crit, critType, setCritType, critKey, setCritKey, critLevel, setCritLevel, recalcMsg, handleCritAdd, handleRecalc, handleCritSet, handleCritDelete }) {
  return (
    <Card title="资产关键度(infra/elasticsearch/asset-criticality.json)">
      <Space wrap style={{ marginBottom: 12 }}>
        <Select value={critType} style={{ width: 100 }} onChange={setCritType} options={[{ value: 'ip', label: 'IP' }, { value: 'user', label: '用户' }, { value: 'host', label: '主机' }]} />
        <Input style={{ width: 180 }} value={critKey} onChange={(e) => setCritKey(e.target.value)} placeholder="IP/用户名/主机名" />
        <Select value={critLevel} style={{ width: 140 }} onChange={setCritLevel} options={['low', 'medium', 'high', 'extreme'].map((l) => ({ value: l, label: l }))} />
        <Button type="primary" onClick={handleCritAdd}>新增/更新</Button>
        <Button onClick={handleRecalc} loading={recalcMsg === '重算中(约数秒)…'}>触发实体风险重算</Button>
      </Space>
      {recalcMsg && <Alert type="info" message={recalcMsg} showIcon style={{ marginBottom: 12 }} />}
      <Tabs items={['ip', 'user', 'host'].map((type) => ({
        key: type, label: type === 'ip' ? 'IP' : type === 'user' ? '用户' : '主机',
        children: Object.entries(crit[type] || {}).length === 0 ? <Empty description="空" /> : <Table rowKey="key" size="small" pagination={false} dataSource={Object.entries(crit[type] || {}).map(([k, v]) => ({ key: k, ...v }))}
          columns={[{ title: '资产', dataIndex: 'key', render: (v) => <code>{v}</code> }, { title: '级别', dataIndex: 'level', render: (v) => <Tag color={v === 'extreme' ? 'red' : v === 'high' ? 'orange' : v === 'medium' ? 'gold' : 'green'}>{v}</Tag> }, { title: '权重', dataIndex: 'weight' }, { title: '操作', render: (_, r) => <Space><Select size="small" value={r.level} style={{ width: 110 }} onChange={(v) => handleCritSet(type, r.key, v)} options={['low', 'medium', 'high', 'extreme'].map((l) => ({ value: l, label: l }))} /><Button size="small" danger onClick={() => handleCritDelete(type, r.key)}>删</Button></Space> }]} />,
      }))} />
    </Card>
  )
}
