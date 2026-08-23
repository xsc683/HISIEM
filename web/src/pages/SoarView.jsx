import { useEffect, useMemo, useState } from 'react'
import { Alert, Button, Card, Descriptions, Empty, Input, Select, Space, Table, Tag, Typography, message } from 'antd'
import {
  decideSoarApproval, getSoarExecution, listSoarExecutions, listSoarPlaybooks,
  reloadSoarPlaybooks, retrySoarExecution, startSoarExecution,
} from '../api.js'
import { TimeText } from '../components/common.jsx'

const STATUS_COLORS = {
  queued: 'default', running: 'processing', waiting_approval: 'gold',
  succeeded: 'green', failed: 'red', rejected: 'orange', cancelled: 'default',
  skipped: 'default',
}

export default function SoarView({ user }) {
  const query = useMemo(() => new URLSearchParams(window.location.search), [])
  const [playbooks, setPlaybooks] = useState([])
  const [executions, setExecutions] = useState([])
  const [resourceType, setResourceType] = useState(query.get('resourceType') || 'alert')
  const [resourceId, setResourceId] = useState(query.get('resourceId') || '')
  const [playbookId, setPlaybookId] = useState('')
  const [detail, setDetail] = useState(null)
  const [busy, setBusy] = useState(false)
  const canExecute = user?.role === 'admin' || user?.role === 'analyst'

  function canApprove(execution) {
    const step = execution.playbookSnapshot?.steps?.[execution.currentStep]
    const requiredRole = step?.with?.requiredRole || 'analyst'
    return user?.role === 'admin' || user?.role === requiredRole
  }

  const compatiblePlaybooks = playbooks.filter((item) => (item.resourceTypes || []).includes(resourceType))

  async function refresh() {
    try {
      const [nextPlaybooks, nextExecutions] = await Promise.all([listSoarPlaybooks(), listSoarExecutions(50)])
      setPlaybooks(nextPlaybooks)
      setExecutions(nextExecutions)
      if (!playbookId || !nextPlaybooks.some((item) => item.id === playbookId && item.resourceTypes.includes(resourceType))) {
        setPlaybookId(nextPlaybooks.find((item) => item.resourceTypes.includes(resourceType))?.id || '')
      }
    } catch (e) {
      message.error(`SOAR 数据加载失败: ${e.message}`)
    }
  }

  useEffect(() => {
    let disposed = false
    let inFlight = false
    const load = async () => {
      if (disposed || inFlight) return
      inFlight = true
      try {
        const result = await listSoarExecutions(50)
        if (!disposed) setExecutions(result)
      } catch {
        // 首次加载由 refresh 展示错误；轮询失败保留最近一次可用结果。
      } finally {
        inFlight = false
      }
    }
    refresh()
    const timer = window.setInterval(load, 5000)
    return () => { disposed = true; window.clearInterval(timer) }
  }, [])

  async function start() {
    if (!playbookId || !resourceId.trim()) {
      message.warning('请选择 Playbook 并填写告警或案件 ID')
      return
    }
    setBusy(true)
    try {
      const result = await startSoarExecution(playbookId, resourceType, resourceId.trim())
      setDetail(result)
      message.success(result.status === 'waiting_approval' ? '执行已启动，正在等待审批' : `执行状态：${result.status}`)
      await refresh()
    } catch (e) {
      message.error(e.message)
    } finally {
      setBusy(false)
    }
  }

  async function decide(id, approved) {
    if (!approved && !window.confirm('确定拒绝该 SOAR 审批？执行将终止。')) return
    try {
      const result = await decideSoarApproval(id, approved)
      setDetail(result)
      await refresh()
      message.success(approved ? '审批通过，执行已继续' : '审批已拒绝')
    } catch (e) { message.error(e.message) }
  }

  async function retry(id) {
    try {
      const result = await retrySoarExecution(id)
      setDetail(result)
      await refresh()
      message.success(`重试完成：${result.status}`)
    } catch (e) { message.error(e.message) }
  }

  async function openDetail(id) {
    try { setDetail(await getSoarExecution(id)) } catch (e) { message.error(e.message) }
  }

  async function reloadDefinitions() {
    try {
      setPlaybooks(await reloadSoarPlaybooks())
      message.success('Playbook 已从 YAML 重新加载')
    } catch (e) { message.error(e.message) }
  }

  return (
    <Space direction="vertical" size="middle" style={{ width: '100%' }}>
      <Alert showIcon type="info" message="SOAR 安全边界"
        description="Playbook 来自 Git/YAML；只允许平台内部白名单动作。执行定义、每步输入输出和审批人都会持久化，当前不允许任意 Shell 或任意外部 URL。" />
      <Card title="启动自动化处置" extra={<Space><Button onClick={refresh}>刷新</Button>{user?.role === 'admin' && <Button onClick={reloadDefinitions}>重新加载 YAML</Button>}</Space>}>
        <Space wrap>
          <Select value={resourceType} style={{ width: 120 }} onChange={(value) => { setResourceType(value); setPlaybookId(playbooks.find((item) => item.resourceTypes.includes(value))?.id || '') }} options={[{ value: 'alert', label: '告警' }, { value: 'case', label: '案件' }]} />
          <Input value={resourceId} onChange={(event) => setResourceId(event.target.value)} style={{ width: 330 }} placeholder={resourceType === 'alert' ? '告警 _id' : '案件 ID'} />
          <Select value={playbookId || undefined} onChange={setPlaybookId} style={{ width: 300 }} placeholder="选择兼容的 Playbook"
            options={compatiblePlaybooks.map((item) => ({ value: item.id, label: `${item.name} · v${item.version}` }))} />
          <Button type="primary" disabled={!canExecute} loading={busy} onClick={start}>运行 Playbook</Button>
        </Space>
      </Card>

      <Card title={`Playbook（${playbooks.length}）`}>
        <Table rowKey="id" size="small" pagination={false} dataSource={playbooks}
          columns={[{ title: '名称', dataIndex: 'name' }, { title: 'ID / 版本', render: (_, row) => <><code>{row.id}</code> <Tag>v{row.version}</Tag></> }, { title: '资源', dataIndex: 'resourceTypes', render: (types) => types.map((type) => <Tag key={type} color="blue">{type}</Tag>) }, { title: '步骤', dataIndex: 'steps', render: (steps) => steps.length }, { title: '说明', dataIndex: 'description' }]} />
      </Card>

      <Card title={`执行记录（${executions.length}）`}>
        {executions.length === 0 && <Empty description="尚无 SOAR 执行" />}
        <Table rowKey="id" size="small" dataSource={executions} pagination={{ pageSize: 10 }}
          expandable={{ expandedRowRender: (row) => <StepTable steps={row.steps || []} /> }}
          columns={[
            { title: '状态', dataIndex: 'status', render: (value) => <Tag color={STATUS_COLORS[value]}>{value}</Tag> },
            { title: 'Playbook', render: (_, row) => <Space direction="vertical" size={0}><Typography.Text>{row.playbookSnapshot?.name || row.playbookId}</Typography.Text><code>{row.playbookId} · v{row.playbookVersion}</code></Space> },
            { title: '目标', render: (_, row) => <><Tag>{row.resourceType}</Tag><code>{row.resourceId}</code></> },
            { title: '发起人', dataIndex: 'actor' },
            { title: '更新时间', dataIndex: 'updatedAt', render: (value) => <TimeText value={value} /> },
            { title: '操作', render: (_, row) => <Space>
              <Button size="small" onClick={() => openDetail(row.id)}>详情</Button>
              {canExecute && row.status === 'waiting_approval' && canApprove(row) && <><Button size="small" type="primary" onClick={() => decide(row.id, true)}>批准</Button><Button size="small" danger onClick={() => decide(row.id, false)}>拒绝</Button></>}
              {canExecute && row.status === 'waiting_approval' && !canApprove(row) && <Tag color="gold">需管理员审批</Tag>}
              {canExecute && row.status === 'failed' && <Button size="small" onClick={() => retry(row.id)}>重试</Button>}
            </Space> },
          ]} />
      </Card>

      {detail && <Card title="执行详情" extra={<Button size="small" onClick={() => setDetail(null)}>关闭</Button>}>
        <Descriptions bordered size="small" column={3}>
          <Descriptions.Item label="执行 ID"><code>{detail.id}</code></Descriptions.Item>
          <Descriptions.Item label="状态"><Tag color={STATUS_COLORS[detail.status]}>{detail.status}</Tag></Descriptions.Item>
          <Descriptions.Item label="当前步骤">{detail.currentStep}/{detail.playbookSnapshot?.steps?.length || 0}</Descriptions.Item>
          <Descriptions.Item label="资源">{detail.resourceType}: <code>{detail.resourceId}</code></Descriptions.Item>
          <Descriptions.Item label="发起人">{detail.actor}</Descriptions.Item>
          <Descriptions.Item label="审批人">{detail.approvedBy || '—'}</Descriptions.Item>
          {detail.approvalMessage && <Descriptions.Item label="待审批" span={3}>{detail.approvalMessage}</Descriptions.Item>}
          {detail.error && <Descriptions.Item label="失败原因" span={3}><Typography.Text type="danger">{detail.error}</Typography.Text></Descriptions.Item>}
        </Descriptions>
        <StepTable steps={detail.steps || []} />
      </Card>}
    </Space>
  )
}

function StepTable({ steps }) {
  return <Table rowKey="stepId" size="small" pagination={false} style={{ marginTop: 12 }} dataSource={steps}
    expandable={{ expandedRowRender: (row) => <pre style={{ maxHeight: 260, overflow: 'auto', whiteSpace: 'pre-wrap' }}>{JSON.stringify({ input: row.input, output: row.output, error: row.error }, null, 2)}</pre> }}
    columns={[{ title: '#', dataIndex: 'stepIndex', width: 50 }, { title: '步骤', dataIndex: 'stepName' }, { title: '动作', dataIndex: 'action', render: (value) => <code>{value}</code> }, { title: '状态', dataIndex: 'status', render: (value) => <Tag color={STATUS_COLORS[value]}>{value}</Tag> }, { title: '完成时间', dataIndex: 'finishedAt', render: (value) => <TimeText value={value} /> }]} />
}
