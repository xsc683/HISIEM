import { useEffect, useMemo, useState } from 'react'
import { Alert, Button, Card, Col, Descriptions, Empty, Input, Row, Select, Space, Statistic, Table, Tag, Typography, message } from 'antd'
import {
  cancelSoarExecution, decideSoarApproval, getSoarExecution, getSoarExecutionEvents,
  listSoarAutomationRules, listSoarConnectors, listSoarExecutions, listSoarPlaybooks,
  pauseSoarExecution, reloadSoarPlaybooks, resumeSoarExecution, retrySoarExecution,
  scanSoarAutomationRules, startSoarExecution,
} from '../api.js'
import { TimeText } from '../components/common.jsx'
import ExecutionTimeline from '../components/soar/ExecutionTimeline.jsx'
import PlaybookGraph from '../components/soar/PlaybookGraph.jsx'

const STATUS_COLORS = {
  queued: 'default', running: 'processing', waiting_approval: 'gold', paused: 'cyan',
  succeeded: 'green', failed: 'red', rejected: 'orange', cancelled: 'default',
  retrying: 'orange', skipped: 'default',
}

export default function SoarView({ user }) {
  const query = useMemo(() => new URLSearchParams(window.location.search), [])
  const [playbooks, setPlaybooks] = useState([])
  const [executions, setExecutions] = useState([])
  const [automationRules, setAutomationRules] = useState([])
  const [connectors, setConnectors] = useState([])
  const [resourceType, setResourceType] = useState(query.get('resourceType') || 'alert')
  const [resourceId, setResourceId] = useState(query.get('resourceId') || '')
  const [playbookId, setPlaybookId] = useState('')
  const [detail, setDetail] = useState(null)
  const [events, setEvents] = useState([])
  const [busy, setBusy] = useState(false)
  const canExecute = user?.role === 'admin' || user?.role === 'analyst'
  const compatiblePlaybooks = playbooks.filter((item) => (item.resourceTypes || []).includes(resourceType))

  function canApprove(execution) {
    const snapshot = execution.playbookSnapshot || {}
    const node = (snapshot.nodes || []).find((item) => item.id === execution.approvalStepId)
      || snapshot.steps?.[execution.currentStep]
    const requiredRole = node?.with?.requiredRole || 'analyst'
    return user?.role === 'admin' || user?.role === requiredRole
  }

  async function refresh() {
    try {
      const [nextPlaybooks, nextExecutions, nextRules, nextConnectors] = await Promise.all([
        listSoarPlaybooks(), listSoarExecutions(50), listSoarAutomationRules(), listSoarConnectors(),
      ])
      setPlaybooks(nextPlaybooks)
      setExecutions(nextExecutions)
      setAutomationRules(nextRules)
      setConnectors(nextConnectors)
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
        // 轮询失败时保留最近一次可用结果，显式刷新仍会展示错误。
      } finally { inFlight = false }
    }
    refresh()
    const timer = window.setInterval(load, 3000)
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
      await openDetail(result.id)
      message.success('执行已持久化入队，由 SOAR Worker 异步推进')
      await refresh()
    } catch (e) { message.error(e.message) } finally { setBusy(false) }
  }

  async function decide(id, approved) {
    if (!approved && !window.confirm('确定拒绝该审批？Playbook 将按 rejected 边继续。')) return
    try {
      await decideSoarApproval(id, approved)
      await Promise.all([openDetail(id), refresh()])
      message.success(approved ? '审批通过，执行已重新入队' : '审批拒绝，执行进入拒绝分支')
    } catch (e) { message.error(e.message) }
  }

  async function controlExecution(id, action, label) {
    try {
      await action(id)
      await Promise.all([openDetail(id), refresh()])
      message.success(label)
    } catch (e) { message.error(e.message) }
  }

  async function openDetail(id) {
    try {
      const [execution, timeline] = await Promise.all([getSoarExecution(id), getSoarExecutionEvents(id)])
      setDetail(execution)
      setEvents(timeline)
    } catch (e) { message.error(e.message) }
  }

  async function reloadDefinitions() {
    try {
      setPlaybooks(await reloadSoarPlaybooks())
      message.success('Playbook 已完成校验并从 YAML 重新加载')
    } catch (e) { message.error(e.message) }
  }

  async function scanRules() {
    try {
      const result = await scanSoarAutomationRules()
      message.success(`扫描 ${result.checked} 个资源，匹配 ${result.matched}，提交/去重 ${result.submittedOrDeduplicated}`)
      await refresh()
    } catch (e) { message.error(e.message) }
  }

  return (
    <Space direction="vertical" size="middle" style={{ width: '100%' }}>
      <Alert showIcon type="info" message="SOAR V2 编排与安全边界"
        description="条件图由持久化 Worker 推进，支持并行分支、汇聚、审批、延迟、超时重试、失败路由、暂停/恢复和执行时间线。外部调用只能使用管理员登记的固定端点连接器，凭据来自环境变量；不允许任意 Shell、动态 URL 或任意 Header。" />

      <Row gutter={[12, 12]}>
        <Col xs={12} lg={6}><Card><Statistic title="图式 Playbook" value={playbooks.filter((item) => item.formatVersion === '2').length} suffix={`/ ${playbooks.length}`} /></Card></Col>
        <Col xs={12} lg={6}><Card><Statistic title="自动化规则" value={automationRules.filter((item) => item.active).length} suffix={`/ ${automationRules.length}`} /></Card></Col>
        <Col xs={12} lg={6}><Card><Statistic title="连接器可用" value={connectors.filter((item) => item.enabled && item.configured).length} suffix={`/ ${connectors.length}`} /></Card></Col>
        <Col xs={12} lg={6}><Card><Statistic title="待审批 / 运行中" value={executions.filter((item) => ['queued', 'running', 'waiting_approval'].includes(item.status)).length} /></Card></Col>
      </Row>

      <Card title="启动自动化处置" extra={<Space><Button onClick={refresh}>刷新</Button>{user?.role === 'admin' && <><Button onClick={scanRules}>扫描自动化规则</Button><Button onClick={reloadDefinitions}>重新加载 YAML</Button></>}</Space>}>
        <Space wrap>
          <Select value={resourceType} style={{ width: 120 }} onChange={(value) => { setResourceType(value); setPlaybookId(playbooks.find((item) => item.resourceTypes.includes(value))?.id || '') }} options={[{ value: 'alert', label: '告警' }, { value: 'case', label: '案件' }]} />
          <Input value={resourceId} onChange={(event) => setResourceId(event.target.value)} style={{ width: 330 }} placeholder={resourceType === 'alert' ? '告警 _id' : '案件 ID'} />
          <Select value={playbookId || undefined} onChange={setPlaybookId} style={{ width: 330 }} placeholder="选择兼容的 Playbook"
            options={compatiblePlaybooks.map((item) => ({ value: item.id, label: `${item.name} · v${item.version}` }))} />
          <Button type="primary" disabled={!canExecute} loading={busy} onClick={start}>运行 Playbook</Button>
        </Space>
      </Card>

      <Card title={`Playbook 定义（${playbooks.length}）`}>
        <Table rowKey="id" size="small" pagination={false} dataSource={playbooks}
          expandable={{ expandedRowRender: (row) => <PlaybookGraph playbook={row} /> }}
          columns={[
            { title: '名称', dataIndex: 'name' },
            { title: '格式', render: (_, row) => <Tag color={row.formatVersion === '2' ? 'green' : 'default'}>{row.formatVersion === '2' ? 'V2 条件图' : 'V1 兼容'}</Tag> },
            { title: 'ID / 版本', render: (_, row) => <><code>{row.id}</code> <Tag>v{row.version}</Tag></> },
            { title: '资源', dataIndex: 'resourceTypes', render: (types) => types.map((type) => <Tag key={type} color="blue">{type}</Tag>) },
            { title: '节点', render: (_, row) => (row.nodes || row.steps || []).length },
            { title: '说明', dataIndex: 'description' },
          ]} />
      </Card>

      <Card title={`执行记录（${executions.length}）`}>
        {executions.length === 0 && <Empty description="尚无 SOAR 执行" />}
        <Table rowKey="id" size="small" dataSource={executions} pagination={{ pageSize: 10 }}
          expandable={{ expandedRowRender: (row) => <StepTable steps={row.steps || []} /> }}
          columns={[
            { title: '状态', dataIndex: 'status', render: (value) => <Tag color={STATUS_COLORS[value]}>{value}</Tag> },
            { title: 'Playbook', render: (_, row) => <Space direction="vertical" size={0}><Typography.Text>{row.playbookSnapshot?.name || row.playbookId}</Typography.Text><code>{row.playbookId} · v{row.playbookVersion}</code></Space> },
            { title: '目标', render: (_, row) => <><Tag>{row.resourceType}</Tag><code>{row.resourceId}</code></> },
            { title: '触发', dataIndex: 'triggerType', render: (value) => <Tag>{value || 'manual'}</Tag> },
            { title: '当前节点', dataIndex: 'currentNode', render: (value) => value ? <code>{value}</code> : '—' },
            { title: '更新时间', dataIndex: 'updatedAt', render: (value) => <TimeText value={value} /> },
            { title: '操作', render: (_, row) => <ExecutionActions execution={row} canExecute={canExecute} canApprove={canApprove(row)} onDetail={openDetail} onDecision={decide} onControl={controlExecution} /> },
          ]} />
      </Card>

      {detail && <Card title="执行详情与事件时间线" extra={<Button size="small" onClick={() => { setDetail(null); setEvents([]) }}>关闭</Button>}>
        <Descriptions bordered size="small" column={3}>
          <Descriptions.Item label="执行 ID"><code>{detail.id}</code></Descriptions.Item>
          <Descriptions.Item label="状态"><Tag color={STATUS_COLORS[detail.status]}>{detail.status}</Tag></Descriptions.Item>
          <Descriptions.Item label="触发方式">{detail.triggerType || 'manual'}</Descriptions.Item>
          <Descriptions.Item label="当前节点"><code>{detail.currentNode || '—'}</code></Descriptions.Item>
          <Descriptions.Item label="已执行节点">{detail.nodesExecuted}</Descriptions.Item>
          <Descriptions.Item label="待运行 frontier">{detail.frontier?.length ? detail.frontier.map((id) => <Tag key={id}>{id}</Tag>) : '—'}</Descriptions.Item>
          <Descriptions.Item label="资源">{detail.resourceType}: <code>{detail.resourceId}</code></Descriptions.Item>
          <Descriptions.Item label="发起人">{detail.actor}</Descriptions.Item>
          <Descriptions.Item label="审批人">{detail.approvedBy || '—'}</Descriptions.Item>
          {detail.nextRunAt && <Descriptions.Item label="下次运行"><TimeText value={detail.nextRunAt} /></Descriptions.Item>}
          {detail.approvalMessage && <Descriptions.Item label="待审批" span={3}>{detail.approvalMessage}</Descriptions.Item>}
          {detail.error && <Descriptions.Item label="错误/恢复信息" span={3}><Typography.Text type="danger">{detail.error}</Typography.Text></Descriptions.Item>}
        </Descriptions>
        <StepTable steps={detail.steps || []} />
        <Typography.Title level={5} style={{ marginTop: 20 }}>不可变执行事件</Typography.Title>
        <ExecutionTimeline events={events} />
      </Card>}
    </Space>
  )
}

function ExecutionActions({ execution, canExecute, canApprove, onDetail, onDecision, onControl }) {
  return <Space wrap>
    <Button size="small" onClick={() => onDetail(execution.id)}>详情</Button>
    {canExecute && execution.status === 'waiting_approval' && canApprove && <><Button size="small" type="primary" onClick={() => onDecision(execution.id, true)}>批准</Button><Button size="small" danger onClick={() => onDecision(execution.id, false)}>拒绝</Button></>}
    {canExecute && execution.status === 'waiting_approval' && !canApprove && <Tag color="gold">角色不满足</Tag>}
    {canExecute && ['queued', 'running'].includes(execution.status) && <Button size="small" onClick={() => onControl(execution.id, pauseSoarExecution, '暂停请求已提交')}>暂停</Button>}
    {canExecute && execution.status === 'paused' && <Button size="small" onClick={() => onControl(execution.id, resumeSoarExecution, '执行已恢复入队')}>恢复</Button>}
    {canExecute && ['queued', 'running', 'waiting_approval', 'paused'].includes(execution.status) && <Button size="small" danger onClick={() => onControl(execution.id, cancelSoarExecution, '执行已取消')}>取消</Button>}
    {canExecute && execution.status === 'failed' && <Button size="small" onClick={() => onControl(execution.id, retrySoarExecution, '失败执行已重新入队')}>重试</Button>}
  </Space>
}

function StepTable({ steps }) {
  return <Table rowKey="stepId" size="small" pagination={false} style={{ marginTop: 12 }} dataSource={steps}
    expandable={{ expandedRowRender: (row) => <pre style={{ maxHeight: 300, overflow: 'auto', whiteSpace: 'pre-wrap' }}>{JSON.stringify({ input: row.input, output: row.output, error: row.error }, null, 2)}</pre> }}
    columns={[
      { title: '#', dataIndex: 'stepIndex', width: 50 },
      { title: '节点', render: (_, row) => <><Typography.Text>{row.stepName}</Typography.Text><br /><code>{row.stepId}</code></> },
      { title: '类型 / 动作', render: (_, row) => <><Tag>{row.nodeType}</Tag><code>{row.action}</code></> },
      { title: '尝试', render: (_, row) => `${row.attempt}/${row.maxAttempts}` },
      { title: '状态', dataIndex: 'status', render: (value) => <Tag color={STATUS_COLORS[value]}>{value}</Tag> },
      { title: '耗时', dataIndex: 'durationMs', render: (value) => value == null ? '—' : `${value} ms` },
      { title: '完成时间', dataIndex: 'finishedAt', render: (value) => <TimeText value={value} /> },
    ]} />
}
