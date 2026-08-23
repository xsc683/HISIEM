import { Alert, Button, Card, Divider, Empty, Table, Tag, Typography, message } from 'antd'
import { TimeText } from '../components/common.jsx'

export default function OpsHealthView({ opsHealth, tasks, healthScan, listTasks, setOpsHealth, setTasks }) {
  function rescan() {
    healthScan().then(setOpsHealth).catch((e) => message.error(e.message))
    listTasks(50).then(setTasks).catch((e) => message.error(`任务刷新失败: ${e.message}`))
  }

  return (
    <Card title="运行态健康扫描" extra={<Button onClick={rescan}>重新扫描</Button>}>
      {!opsHealth && <Empty description="点击重新扫描检查 PostgreSQL、ES、Kafka、Logstash、Flink 和 Kibana" />}
      {opsHealth && <>
        <Alert type={opsHealth.status === 'UP' ? 'success' : 'error'} showIcon message={`${opsHealth.status} · 扫描时间 ${opsHealth.scannedAt}`} style={{ marginBottom: 12 }} />
        <Table rowKey="name" size="small" pagination={false} dataSource={Object.values(opsHealth.components || {})}
          columns={[{ title: '组件', dataIndex: 'name' }, { title: '状态', dataIndex: 'status', render: (v) => <Tag color={v === 'UP' ? 'green' : 'red'}>{v}</Tag> }, { title: '延迟', dataIndex: 'latencyMs', render: (v) => `${v} ms` }, { title: '探针', dataIndex: 'probe', render: (v, r) => r.degraded ? <Tag color="orange">降级 TCP</Tag> : (v || 'HTTP') }, { title: '错误', dataIndex: 'error' }, { title: '提示', dataIndex: 'warning', render: (v) => v && <Typography.Text type="warning">{v}</Typography.Text> }]} />
        <Divider>后台任务进度</Divider>
        <Table rowKey="id" size="small" pagination={{ pageSize: 8 }} dataSource={tasks}
          columns={[{ title: '任务', dataIndex: 'type' }, { title: '资源', dataIndex: 'resourceId' }, { title: '状态', dataIndex: 'status' }, { title: '进度', dataIndex: 'progress', render: (v) => `${v}%` }, { title: '消息', dataIndex: 'message' }, { title: '更新时间', dataIndex: 'updatedAt', render: (v) => <TimeText value={v} /> }]} />
      </>}
    </Card>
  )
}
