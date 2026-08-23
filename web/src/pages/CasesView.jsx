import { Alert, Button, Card, Descriptions, Divider, Empty, Input, InputNumber, Modal, Select, Space, Table, Tag, Typography, message } from 'antd'
import { listCases } from '../api.js'
import { LOCAL_TIME_LABEL, TimeText } from '../components/common.jsx'

export default function CasesView({ cases, setCases, alerts, caseAlertDetails, caseFilter, setCaseFilter, selAlerts, setSelAlerts, caseTitle, setCaseTitle,
  caseWindow, setCaseWindow, caseThreshold, setCaseThreshold, caseGroupByRule, setCaseGroupByRule,
  detailCase, setDetailCase, caseTimeline_, openCaseDetail, handleCreateCase, handleAggregate,
  handleInvestigateCase, handleResolveCase, handleAddToCase, handleRemoveFromCase, handleDeleteCase,
  caseOwner, setCaseOwner, evidenceTitle, setEvidenceTitle, evidenceUri, setEvidenceUri,
  handleUpdateCaseMetadata, caseCollaborators, setCaseCollaborators, handleUpdateCollaborators, onRunSoar }) {
  return (
    <div>
      <Card style={{ marginBottom: 16 }}>
        <Space wrap>
          <Select style={{ width: 160 }} value={caseFilter || undefined} placeholder="全部状态" allowClear
            onChange={(v) => { setCaseFilter(v || ''); listCases(v || '').then(setCases).catch((e) => message.error(`案件筛选失败: ${e.message}`)) }}
            options={['open', 'investigating', 'resolved'].map((s) => ({ value: s, label: s }))} />
          <Button type="primary" onClick={handleAggregate}>触发一轮自动聚合</Button>
          <Space direction="vertical" size={2}>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>聚合窗口（分钟）</Typography.Text>
            <InputNumber min={1} max={1440} precision={0} style={{ width: 130 }} value={Number(caseWindow)} onChange={(value) => setCaseWindow(value ?? 30)} />
          </Space>
          <Space direction="vertical" size={2}>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>最少告警数</Typography.Text>
            <InputNumber min={2} max={1000} precision={0} style={{ width: 130 }} value={Number(caseThreshold)} onChange={(value) => setCaseThreshold(value ?? 2)} />
          </Space>
          <Select value={caseGroupByRule} onChange={setCaseGroupByRule} style={{ width: 140 }} options={[{ value: false, label: '按实体分组' }, { value: true, label: '按规则+实体' }]} />
          <Button onClick={() => setSelAlerts(new Set())}>清空告警勾选</Button>
          <Input style={{ width: 220 }} value={caseTitle} onChange={(e) => setCaseTitle(e.target.value)} placeholder="案件标题(可选)" />
          <Button type="primary" onClick={handleCreateCase}>手动聚合勾选告警为案件</Button>
          <Typography.Text type="secondary">已勾选 {selAlerts.size} 条 open 告警</Typography.Text>
        </Space>
        <Alert type="info" showIcon style={{ marginTop: 12 }}
          message={`当前自动聚合条件：${Number(caseWindow)} 分钟内，同一实体至少 ${Number(caseThreshold)} 条 open 告警${caseGroupByRule ? '，且按同一规则分组' : ''}`}
          description={`聚合依据是事件时间（页面按${LOCAL_TIME_LABEL}显示）；生成案件后，可在案件详情查看告警、实体和近 24 小时事件时间线。`} />
      </Card>

      <Card>
        {cases.length === 0 && <Empty description="暂无案件(同实体 ≥2 条 open 告警会自动聚合;或从告警台勾选手动建案)" />}
        <Space direction="vertical" size="middle" style={{ width: '100%' }}>
          {cases.map((c) => (
            <div key={c['case.id']} style={{ border: '1px solid #f0f0f0', borderRadius: 8, padding: '12px 16px', display: 'flex', justifyContent: 'space-between', alignItems: 'center', background: c['case.status'] === 'resolved' ? '#fafafa' : '#fff' }}>
              <div>
                <Space><strong>{c['case.title']}</strong><code style={{ fontSize: 11, color: '#999' }}>{c['case.id']}</code><Tag color={c['case.status'] === 'resolved' ? 'blue' : c['case.status'] === 'investigating' ? 'gold' : 'red'}>{c['case.status']}</Tag><Tag>{c['case.aggregation'] === 'auto' ? '自动聚合' : '手动聚合'}</Tag></Space>
                <div style={{ marginTop: 4, fontSize: 12, color: '#888' }}>{c['alert_ids']?.length} 告警 · 负责人 {c['case.owner'] || '-'} · 更新 <TimeText value={c['case.updated_at']} /></div>
                <Space size={4} wrap style={{ marginTop: 6 }}>{(c.entities || []).map((entity) => <Tag key={`${entity.type}:${entity.value}`} color="blue">{entity.type}:{entity.value}</Tag>)}</Space>
              </div>
              <Space>
                <Button size="small" onClick={() => openCaseDetail(c['case.id'])}>详情</Button>
                {c['case.status'] === 'open' && <Button size="small" onClick={() => handleInvestigateCase(c['case.id'])}>接手</Button>}
                <Button size="small" danger disabled={(c['alert_ids'] || []).length > 0} title={(c['alert_ids'] || []).length > 0 ? '请先移出全部告警' : '删除案件'} onClick={() => handleDeleteCase(c['case.id'])}>删</Button>
              </Space>
            </div>
          ))}
        </Space>
      </Card>

      <Modal open={!!detailCase}
        title={<Space><strong>{detailCase?.['case.title']}</strong><Tag color={detailCase?.['case.status'] === 'resolved' ? 'blue' : detailCase?.['case.status'] === 'investigating' ? 'gold' : 'red'}>{detailCase?.['case.status']}</Tag>{detailCase?.['case.verdict'] && <Tag color="green">{detailCase['case.verdict']}</Tag>}</Space>}
        onCancel={() => setDetailCase(null)} footer={null} width={860}>
        {detailCase && (
          <>
            <Descriptions size="small" column={3} bordered style={{ marginBottom: 12 }}>
              <Descriptions.Item label="案件 ID"><code>{detailCase['case.id']}</code></Descriptions.Item>
              <Descriptions.Item label="聚合来源">{detailCase['case.aggregation']}</Descriptions.Item>
              <Descriptions.Item label="操作者">{detailCase['case.operator']}</Descriptions.Item>
              <Descriptions.Item label="负责人">{detailCase['case.owner'] || '—'}</Descriptions.Item>
              <Descriptions.Item label="实体">{(detailCase.entities || []).map((e) => <Tag key={e.type + e.value}>{e.type}:{e.value}</Tag>)}</Descriptions.Item>
              <Descriptions.Item label="创建时间"><TimeText value={detailCase['case.created_at']} /></Descriptions.Item>
              <Descriptions.Item label="结案时间"><TimeText value={detailCase['case.closed_at']} /></Descriptions.Item>
            </Descriptions>
            <Space wrap style={{ marginBottom: 12 }}>
              {detailCase['case.status'] === 'open' && <Button type="primary" onClick={() => handleInvestigateCase(detailCase['case.id'])}>接手调查</Button>}
              {detailCase['case.status'] === 'investigating' && <Select placeholder="结案选 verdict…" style={{ width: 200 }} onChange={(v) => handleResolveCase(detailCase['case.id'], v)} options={['true_positive', 'false_positive', 'duplicate'].map((v) => ({ value: v, label: v }))} />}
              <Button onClick={() => handleAddToCase(detailCase['case.id'])}>追加勾选告警</Button>
              <Button type="primary" onClick={() => onRunSoar('case', detailCase['case.id'])}>运行 SOAR</Button>
            </Space>
            <Divider style={{ margin: '12px 0' }}>处置负责人和证据</Divider>
            <Space wrap>
              <Input style={{ width: 180 }} value={caseOwner} onChange={(e) => setCaseOwner(e.target.value)} placeholder="负责人用户名" />
              <Input style={{ width: 260 }} value={caseCollaborators} onChange={(e) => setCaseCollaborators(e.target.value)} placeholder="协作人(逗号分隔)" />
              <Input style={{ width: 180 }} value={evidenceTitle} onChange={(e) => setEvidenceTitle(e.target.value)} placeholder="证据标题" />
              <Input style={{ width: 260 }} value={evidenceUri} onChange={(e) => setEvidenceUri(e.target.value)} placeholder="证据 URI/链接" />
              <Button type="primary" onClick={() => handleUpdateCaseMetadata(detailCase['case.id'])}>保存</Button>
              <Button onClick={() => handleUpdateCollaborators(detailCase['case.id'])}>保存协作人</Button>
            </Space>
            {(detailCase.evidence || []).length > 0 && <div style={{ marginTop: 8, fontSize: 12 }}>{(detailCase.evidence || []).map((e, i) => <div key={i}><Tag color="blue">{e.type || 'evidence'}</Tag>{e.title || '未命名'} {e.uri && <code>{e.uri}</code>}</div>)}</div>}
            <Divider style={{ margin: '12px 0' }}>案内告警({(detailCase['alert_ids'] || []).length})</Divider>
            <Space wrap>{(detailCase['alert_ids'] || []).map((id) => {
              const linkedAlert = caseAlertDetails[id] || alerts.find((item) => item._id === id)
              return <Tag key={id} closable onClose={() => handleRemoveFromCase(detailCase['case.id'], id)} color="blue">{linkedAlert?.['alert.rule_name'] || '告警'} · {linkedAlert?.['alert.severity'] || '未知级别'} · <code>{id.slice(0, 12)}</code></Tag>
            })}</Space>
            <Divider style={{ margin: '16px 0' }}>关联事件时间线(实时查 siem-events,近 24h)</Divider>
            {caseTimeline_.length === 0 ? <Empty description="近 24h 无关联事件(历史案件的事件可能已过期)" image={Empty.PRESENTED_IMAGE_SIMPLE} /> : <Table rowKey={(_, i) => i} size="small" pagination={{ pageSize: 10 }} dataSource={caseTimeline_} columns={[{ title: '事件时间', dataIndex: '@timestamp', width: 200, render: (v) => <TimeText value={v} /> }, { title: 'action', dataIndex: 'event.action', render: (v) => <Tag color="blue">{v}</Tag> }, { title: 'message', dataIndex: 'message', ellipsis: true }]} />}
          </>
        )}
      </Modal>
    </div>
  )
}
