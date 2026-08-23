import { useEffect, useMemo, useState } from 'react'
import { Button, Card, Col, Divider, Empty, Input, InputNumber, Modal, Row, Select, Space, Table, Tag, Typography, message } from 'antd'
import {
  createSoarDraft, listSoarRevisions, publishSoarRevision, reviewSoarRevision,
  submitSoarRevision, updateSoarDraft,
} from '../../api.js'

const NODE_TYPES = ['action', 'decision', 'approval', 'delay', 'subplaybook', 'loop', 'map', 'end']
const ACTIONS = ['context.set', 'notification.create', 'alert.set_status', 'alert.set_verdict', 'case.set_status', 'case.add_alert', 'case.add_evidence', 'connector.call']
const COLORS = { action: '#1677ff', decision: '#722ed1', approval: '#d48806', delay: '#08979c', subplaybook: '#531dab', loop: '#c41d7f', map: '#389e0d', end: '#595959' }

const clone = (value) => JSON.parse(JSON.stringify(value))

export default function PlaybookDesigner({ enabled, onPublished }) {
  const [revisions, setRevisions] = useState([])
  const [current, setCurrent] = useState(null)
  const [definition, setDefinition] = useState(null)
  const [layout, setLayout] = useState({})
  const [selectedNodeId, setSelectedNodeId] = useState('')
  const [edge, setEdge] = useState({ source: '', target: '', event: 'success', when: '' })
  const [rollout, setRollout] = useState(10)
  const [newDialog, setNewDialog] = useState(false)
  const [metadata, setMetadata] = useState({ id: '', name: '', version: '1.0.0' })

  async function refresh(selectKey) {
    try {
      const rows = await listSoarRevisions()
      setRevisions(rows)
      if (selectKey) {
        const found = rows.find((item) => keyOf(item) === selectKey)
        if (found) load(found)
      }
    } catch (e) { message.error(`版本目录加载失败: ${e.message}`) }
  }

  useEffect(() => { refresh() }, [])

  const nodes = definition?.nodes || []
  const selectedNode = nodes.find((node) => node.id === selectedNodeId)
  const edges = useMemo(() => nodes.flatMap((node) => (node.transitions || []).map((item, index) => ({ ...item, source: node.id, index }))), [nodes])

  function load(revision) {
    setCurrent(revision)
    setDefinition(clone(revision.definition))
    setLayout(clone(revision.layout || {}))
    setSelectedNodeId('')
  }

  function createEmpty() {
    const id = metadata.id.trim()
    if (!id || !metadata.name.trim()) return message.warning('请填写 ID 和名称')
    setCurrent(null)
    setDefinition({
      formatVersion: '2', id, name: metadata.name.trim(), description: '', version: metadata.version || '1.0.0',
      enabled: true, resourceTypes: ['alert', 'case'], entrypoint: '', defaults: { timeoutSeconds: 30, retry: { maxAttempts: 2, delaySeconds: 2, backoffMultiplier: 2 } },
      triggers: [], nodes: [], steps: null,
    })
    setLayout({})
    setNewDialog(false)
  }

  function copyAsDraft() {
    if (!definition) return
    setCurrent(null)
    setDefinition({ ...clone(definition), version: bumpVersion(definition.version) })
    message.info('已复制为新草稿；保存后生成新的 revision')
  }

  function addNode(type) {
    if (!definition) return message.warning('请先新建或选择一个版本')
    let suffix = 1
    while (nodes.some((node) => node.id === `${type}-${suffix}`)) suffix++
    const id = `${type}-${suffix}`
    const next = defaultNode(type, id)
    updateDefinition({ ...definition, entrypoint: definition.entrypoint || id, nodes: [...nodes, next] })
    setLayout({ ...layout, [id]: { x: 50 + nodes.length * 25, y: 50 + nodes.length * 18 } })
    setSelectedNodeId(id)
  }

  function patchNode(patch) {
    updateDefinition({ ...definition, nodes: nodes.map((node) => node.id === selectedNodeId ? { ...node, ...patch } : node) })
  }

  function removeNode() {
    if (!selectedNode) return
    const nextNodes = nodes.filter((node) => node.id !== selectedNode.id).map((node) => ({
      ...node, transitions: (node.transitions || []).filter((item) => item.target !== selectedNode.id),
    }))
    const nextLayout = { ...layout }
    delete nextLayout[selectedNode.id]
    updateDefinition({ ...definition, entrypoint: definition.entrypoint === selectedNode.id ? nextNodes[0]?.id || '' : definition.entrypoint, nodes: nextNodes })
    setLayout(nextLayout)
    setSelectedNodeId('')
  }

  function addEdge() {
    if (!edge.source || !edge.target || edge.source === edge.target) return message.warning('请选择不同的起点和终点')
    let condition = null
    if (edge.when.trim()) {
      try { condition = JSON.parse(edge.when) } catch { return message.error('边条件不是合法 JSON') }
    }
    updateDefinition({ ...definition, nodes: nodes.map((node) => node.id === edge.source ? {
      ...node, transitions: [...(node.transitions || []), { target: edge.target, on: edge.event, ...(condition ? { when: condition } : {}) }],
    } : node) })
  }

  function removeEdge(item) {
    updateDefinition({ ...definition, nodes: nodes.map((node) => node.id === item.source ? {
      ...node, transitions: (node.transitions || []).filter((_, index) => index !== item.index),
    } : node) })
  }

  function dropNode(event) {
    event.preventDefault()
    const id = event.dataTransfer.getData('soar-node')
    if (!id) return
    const bounds = event.currentTarget.getBoundingClientRect()
    setLayout({ ...layout, [id]: { x: Math.max(0, event.clientX - bounds.left - 80), y: Math.max(0, event.clientY - bounds.top - 30) } })
  }

  async function save() {
    if (!definition) return
    try {
      const saved = current
        ? await updateSoarDraft(current.playbookId, current.revision, definition, layout, current.lockVersion)
        : await createSoarDraft(definition, layout)
      message.success(`草稿已保存：revision ${saved.revision}`)
      await refresh(keyOf(saved))
    } catch (e) { message.error(`保存失败: ${e.message}`) }
  }

  async function transition(action, successText) {
    if (!current) return
    try {
      const next = await action(current.playbookId, current.revision)
      message.success(successText)
      await refresh(keyOf(next))
      if (next.state === 'published') onPublished?.()
    } catch (e) { message.error(e.message) }
  }

  return <Card title="Playbook 可视化设计器" extra={<Space>
    <Button onClick={() => setNewDialog(true)} disabled={!enabled}>新建</Button>
    <Button onClick={copyAsDraft} disabled={!enabled || !definition}>复制为草稿</Button>
    <Button type="primary" onClick={save} disabled={!enabled || !definition || current && !['draft', 'rejected'].includes(current.state)}>保存草稿</Button>
  </Space>}>
    <Space wrap style={{ marginBottom: 12 }}>
      <Select style={{ width: 460 }} placeholder="选择版本" value={current ? keyOf(current) : undefined}
        onChange={(value) => load(revisions.find((item) => keyOf(item) === value))}
        options={revisions.map((item) => ({ value: keyOf(item), label: `${item.playbookId} · r${item.revision} · ${item.state} · ${item.rolloutPercentage}%` }))} />
      {current && <><Tag color={stateColor(current.state)}>{current.state}</Tag><Tag>lock {current.lockVersion}</Tag><Typography.Text type="secondary">创建 {current.createdBy} · 审批 {current.reviewedBy || '—'}</Typography.Text></>}
    </Space>

    {!definition ? <Empty description="选择已有版本或新建 Playbook" /> : <>
      <Row gutter={12}>
        <Col span={4}>
          <Typography.Text strong>节点工具箱</Typography.Text>
          <div style={{ display: 'grid', gap: 6, marginTop: 8 }}>
            {NODE_TYPES.map((type) => <Button key={type} style={{ borderColor: COLORS[type], color: COLORS[type] }} onClick={() => addNode(type)}>{type}</Button>)}
          </div>
          <Divider />
          <Typography.Text strong>基本属性</Typography.Text>
          <Input value={definition.name} onChange={(e) => updateDefinition({ ...definition, name: e.target.value })} addonBefore="名称" style={{ marginTop: 8 }} />
          <Input value={definition.version} onChange={(e) => updateDefinition({ ...definition, version: e.target.value })} addonBefore="版本" style={{ marginTop: 8 }} />
          <Select mode="multiple" value={definition.resourceTypes} onChange={(value) => updateDefinition({ ...definition, resourceTypes: value })}
            style={{ width: '100%', marginTop: 8 }} options={[{ value: 'alert' }, { value: 'case' }]} />
          <Select value={definition.entrypoint || undefined} onChange={(value) => updateDefinition({ ...definition, entrypoint: value })}
            placeholder="入口节点" style={{ width: '100%', marginTop: 8 }} options={nodes.map((node) => ({ value: node.id }))} />
        </Col>
        <Col span={14}>
          <div onDragOver={(e) => e.preventDefault()} onDrop={dropNode}
            style={{ height: 520, position: 'relative', overflow: 'auto', border: '1px dashed #bfbfbf', borderRadius: 8, background: '#fafafa' }}>
            <svg width="100%" height="100%" style={{ position: 'absolute', inset: 0, pointerEvents: 'none' }}>
              {edges.map((item) => {
                const from = layout[item.source] || { x: 20, y: 20 }
                const to = layout[item.target] || { x: 300, y: 100 }
                return <g key={`${item.source}-${item.index}`}><line x1={from.x + 150} y1={from.y + 30} x2={to.x} y2={to.y + 30} stroke={item.on === 'failure' ? '#cf1322' : '#8c8c8c'} strokeWidth="2" /><text x={(from.x + to.x + 150) / 2} y={(from.y + to.y + 50) / 2} fill="#595959" fontSize="11">{item.on || 'success'}</text></g>
              })}
            </svg>
            {nodes.map((node) => {
              const position = layout[node.id] || { x: 20, y: 20 }
              return <div key={node.id} draggable onDragStart={(e) => e.dataTransfer.setData('soar-node', node.id)} onClick={() => setSelectedNodeId(node.id)}
                style={{ position: 'absolute', left: position.x, top: position.y, width: 150, minHeight: 60, padding: 8, borderRadius: 8, cursor: 'move', background: '#fff', border: `2px solid ${selectedNodeId === node.id ? COLORS[node.type] : '#d9d9d9'}`, boxShadow: '0 2px 7px #00000018' }}>
                <Tag color={COLORS[node.type]}>{node.type}</Tag><br />
                <Typography.Text strong ellipsis>{node.name}</Typography.Text><br /><code>{node.id}</code>
              </div>
            })}
          </div>
          <Space wrap style={{ marginTop: 8 }}>
            <Select placeholder="起点" value={edge.source || undefined} onChange={(value) => setEdge({ ...edge, source: value })} options={nodes.map((node) => ({ value: node.id }))} style={{ width: 150 }} />
            <Select placeholder="事件" value={edge.event} onChange={(value) => setEdge({ ...edge, event: value })} options={['success', 'failure', 'approved', 'rejected', 'complete', 'always'].map((value) => ({ value }))} style={{ width: 130 }} />
            <Select placeholder="终点" value={edge.target || undefined} onChange={(value) => setEdge({ ...edge, target: value })} options={nodes.map((node) => ({ value: node.id }))} style={{ width: 150 }} />
            <Input value={edge.when} onChange={(e) => setEdge({ ...edge, when: e.target.value })} placeholder='可选条件 JSON：{"field":"resource.alert.severity","operator":"eq","value":"critical"}' style={{ width: 420 }} />
            <Button onClick={addEdge}>连接</Button>
          </Space>
          <div style={{ marginTop: 8 }}>{edges.map((item) => <Tag closable key={`${item.source}-${item.index}`} onClose={() => removeEdge(item)}>{item.source} · {item.on} → {item.target}{item.when ? ' [条件]' : ''}</Tag>)}</div>
        </Col>
        <Col span={6}>
          <Typography.Text strong>节点检查器</Typography.Text>
          {!selectedNode ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="选择节点" /> : <Space direction="vertical" style={{ width: '100%', marginTop: 8 }}>
            <Input value={selectedNode.id} disabled addonBefore="ID" />
            <Input value={selectedNode.name} onChange={(e) => patchNode({ name: e.target.value })} addonBefore="名称" />
            <Select value={selectedNode.join || 'any'} onChange={(value) => patchNode({ join: value })} options={[{ value: 'any', label: '任一上游可运行' }, { value: 'all', label: '等待全部上游' }]} style={{ width: '100%' }} />
            <Select value={Boolean(selectedNode.exclusive)} onChange={(value) => patchNode({ exclusive: value })} options={[{ value: false, label: '匹配所有边（可并行）' }, { value: true, label: '只走首条匹配边' }]} style={{ width: '100%' }} />
            {selectedNode.type === 'action' && <Select value={selectedNode.action} onChange={(value) => patchNode({ action: value })} options={ACTIONS.map((value) => ({ value }))} style={{ width: '100%' }} />}
            {selectedNode.type === 'delay' && <InputNumber min={1} max={86400} value={selectedNode.delaySeconds} onChange={(value) => patchNode({ delaySeconds: value })} addonBefore="秒" style={{ width: '100%' }} />}
            {selectedNode.type === 'end' && <Select value={selectedNode.result} onChange={(value) => patchNode({ result: value })} options={['succeeded', 'failed', 'rejected'].map((value) => ({ value }))} style={{ width: '100%' }} />}
            {['action', 'approval', 'subplaybook', 'loop', 'map'].includes(selectedNode.type) && <><Typography.Text type="secondary">with 参数</Typography.Text><JsonEditor value={selectedNode.with || {}} onChange={(value) => patchNode({ with: value })} /></>}
            <Typography.Text type="secondary">节点执行条件（空对象表示无条件）</Typography.Text>
            <JsonEditor value={selectedNode.when || {}} onChange={(value) => patchNode({ when: Object.keys(value).length ? value : null })} />
            <Button danger onClick={removeNode}>删除节点</Button>
          </Space>}
        </Col>
      </Row>
      <Divider />
      <Space wrap>
        {current?.state === 'draft' && <Button type="primary" onClick={() => transition(submitSoarRevision, '已提交审批')}>提交审批</Button>}
        {current?.state === 'pending_approval' && <><Button type="primary" onClick={() => transition((id, revision) => reviewSoarRevision(id, revision, true, '可视化检查通过'), '审批通过')}>审批通过</Button><Button danger onClick={() => transition((id, revision) => reviewSoarRevision(id, revision, false, '需要修改'), '已驳回')}>驳回</Button></>}
        {current?.state === 'approved' && <><InputNumber min={1} max={100} value={rollout} onChange={setRollout} addonAfter="%" /><Button type="primary" onClick={() => transition((id, revision) => publishSoarRevision(id, revision, rollout), `已按 ${rollout}% 灰度发布`)}>发布</Button></>}
      </Space>
      <Table style={{ marginTop: 12 }} size="small" pagination={false} rowKey={(row) => keyOf(row)} dataSource={revisions.filter((item) => item.playbookId === definition.id)} columns={[
        { title: 'Revision', dataIndex: 'revision' }, { title: '版本', dataIndex: 'semanticVersion' },
        { title: '状态', dataIndex: 'state', render: (value) => <Tag color={stateColor(value)}>{value}</Tag> },
        { title: '流量', dataIndex: 'rolloutPercentage', render: (value) => `${value}%` },
        { title: '创建/审批/发布', render: (_, row) => `${row.createdBy} / ${row.reviewedBy || '—'} / ${row.publishedBy || '—'}` },
      ]} />
    </>}

    <Modal title="新建图式 Playbook" open={newDialog} onOk={createEmpty} onCancel={() => setNewDialog(false)}>
      <Space direction="vertical" style={{ width: '100%' }}>
        <Input value={metadata.id} onChange={(e) => setMetadata({ ...metadata, id: e.target.value })} addonBefore="ID" placeholder="incident-response-v1" />
        <Input value={metadata.name} onChange={(e) => setMetadata({ ...metadata, name: e.target.value })} addonBefore="名称" />
        <Input value={metadata.version} onChange={(e) => setMetadata({ ...metadata, version: e.target.value })} addonBefore="版本" />
      </Space>
    </Modal>
  </Card>

  function updateDefinition(next) {
    setDefinition(next)
  }
}

function JsonEditor({ value, onChange }) {
  const [text, setText] = useState(JSON.stringify(value, null, 2))
  useEffect(() => setText(JSON.stringify(value, null, 2)), [value])
  function commit() {
    try { onChange(JSON.parse(text)) } catch { message.error('with 参数不是合法 JSON') }
  }
  return <Input.TextArea rows={10} value={text} onChange={(e) => setText(e.target.value)} onBlur={commit} />
}

function defaultNode(type, id) {
  const base = { id, name: `${type} 节点`, type, exclusive: false, join: 'any', transitions: [] }
  if (type === 'action') return { ...base, action: 'context.set', with: { values: { note: 'value' } } }
  if (type === 'approval') return { ...base, with: { requiredRole: 'analyst', message: '请复核处置动作' } }
  if (type === 'delay') return { ...base, delaySeconds: 5 }
  if (type === 'subplaybook') return { ...base, with: { playbookId: 'replace-with-playbook-id', input: {} } }
  if (type === 'loop') return { ...base, with: { maxIterations: 10, iterationVariable: 'iteration' } }
  if (type === 'map') return { ...base, with: { items: [], action: 'notification.create', arguments: { message: '${item}' }, concurrency: 4, maxItems: 100, continueOnError: false } }
  if (type === 'end') return { ...base, result: 'succeeded' }
  return base
}

function keyOf(item) { return `${item.playbookId}:${item.revision}` }

function stateColor(value) {
  return { draft: 'blue', pending_approval: 'gold', approved: 'cyan', published: 'green', rejected: 'red', retired: 'default' }[value] || 'default'
}

function bumpVersion(version) {
  const parts = String(version || '1.0.0').split('.')
  const patch = Number(parts[2] || 0) + 1
  return `${parts[0] || 1}.${parts[1] || 0}.${patch}`
}
