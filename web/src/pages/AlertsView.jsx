import { Button, Card, Select, Space, Table, Tag, Typography } from 'antd'
import { LOCAL_TIME_LABEL, RISK_COLOR, STATUS_TAG, TimeText, VERDICT_TAG, alertSource } from '../components/common.jsx'

export default function AlertsView({ alerts, alertFilter, setAlertFilter, selAlerts, setSelAlerts, fpRates,
  handleCreateCase, handleBatchStatus, handleBatchVerdict, handleAlertStatus, handleAlertVerdict, reloadAlerts, onRunSoar }) {
  return (
    <Card>
      <Space direction="vertical" size="middle" style={{ width: '100%' }}>
        <Space wrap>
          <Select value={alertFilter} style={{ width: 160 }} onChange={(v) => { setAlertFilter(v); reloadAlerts(v) }} options={['open', 'acknowledged', 'investigating', 'resolved', 'closed'].map((s) => ({ value: s, label: s }))} />
          <Button type="primary" onClick={handleCreateCase}>选中直接建案</Button>
          <Button onClick={() => handleBatchStatus('acknowledged')}>批量 ack</Button>
          <Button danger onClick={() => handleBatchStatus('closed')}>批量 close</Button>
          <Select placeholder="批量 verdict…" style={{ width: 180 }} onChange={(v) => v && handleBatchVerdict(v)} options={['true_positive', 'false_positive', 'duplicate'].map((v) => ({ value: v, label: v }))} />
          <Typography.Text type="secondary">已勾选 {selAlerts.size} 条</Typography.Text>
        </Space>
        <Table rowKey="_id" dataSource={alerts} size="small" scroll={{ x: 1280 }} pagination={{ pageSize: 15, showTotal: (t) => `共 ${t} 条告警` }}
          rowSelection={{ selectedRowKeys: [...selAlerts], onChange: (keys) => setSelAlerts(new Set(keys)) }}
          expandable={{ expandedRowRender: (r) => (
            <Space direction="vertical" size="small" style={{ width: '100%' }}>
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>时间说明：事件时间用于检测窗口；告警生成时间表示系统发现并写入告警的时间。原始 JSON 按 UTC 保存，页面字段按 {LOCAL_TIME_LABEL} 显示。</Typography.Text>
              <Space wrap>
                <Button size="small" onClick={() => handleAlertStatus(r._id, 'acknowledged')}>ack</Button>
                <Button size="small" onClick={() => handleAlertStatus(r._id, 'investigating')}>investigating</Button>
                <Button size="small" danger onClick={() => handleAlertStatus(r._id, 'closed')}>close</Button>
                <Button size="small" type="primary" onClick={() => onRunSoar('alert', r._id)}>运行 SOAR</Button>
                <Select size="small" placeholder="verdict…" style={{ width: 160 }} onChange={(v) => handleAlertVerdict(r._id, v)} options={['true_positive', 'false_positive', 'duplicate'].map((v) => ({ value: v, label: v }))} />
              </Space>
              <pre style={{ background: '#0f1d33', color: '#a8d4ff', padding: 10, borderRadius: 6, fontSize: 12, maxHeight: 420, overflow: 'auto', whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>{JSON.stringify(r, null, 2)}</pre>
            </Space>
          ) }}
          columns={[
            { title: '风险', dataIndex: 'alert.risk_score', width: 70, sorter: (a, b) => a['alert.risk_score'] - b['alert.risk_score'], render: (v) => <Tag color={RISK_COLOR(v)}>{v}</Tag> },
            { title: '规则', dataIndex: 'alert.rule_name', render: (v, r) => <>{v} <Typography.Text type="secondary" style={{ fontSize: 11 }}>{r['alert.rule_id']}</Typography.Text></> },
            { title: 'severity', dataIndex: 'alert.severity', render: (v) => <Tag color={v === 'critical' ? 'red' : v === 'high' ? 'orange' : v === 'medium' ? 'gold' : 'blue'}>{v}</Tag> },
            { title: '状态', dataIndex: 'alert.status', render: (v) => <Tag color={STATUS_TAG[v]}>{v}</Tag> },
            { title: 'verdict', dataIndex: 'alert.analyst_verdict', render: (v) => v ? <Tag color={VERDICT_TAG[v]}>{v}</Tag> : <Typography.Text type="secondary">—</Typography.Text> },
            { title: '来源/实体', dataIndex: 'source.ip', render: (v, r) => <Space direction="vertical" size={0}><Typography.Text>{alertSource(r)}</Typography.Text><Typography.Text type="secondary" style={{ fontSize: 11 }}>{v || r['user.name'] || r['alert.entity'] || '—'}</Typography.Text></Space> },
            { title: '案件', dataIndex: 'alert.case_id', render: (v) => v ? <Tag color="blue">{v}</Tag> : <Typography.Text type="secondary">未归案</Typography.Text> },
            { title: '事件时间/窗口结束', dataIndex: '@timestamp', width: 180, render: (v) => <TimeText value={v} /> },
            { title: '告警生成', dataIndex: 'alert.created_at', width: 180, render: (v) => <TimeText value={v} /> },
          ]} />
        <details>
          <summary>按规则 FP 率({fpRates.filter((r) => r.high).length} 条 &gt;50% 需 review)</summary>
          <Table rowKey="ruleId" size="small" pagination={false} dataSource={fpRates}
            columns={[{ title: '规则', dataIndex: 'ruleId', render: (v) => <code>{v}</code> }, { title: '总数', dataIndex: 'total' }, { title: 'FP', dataIndex: 'fp' }, { title: 'TP', dataIndex: 'tp' }, { title: 'FP 率', dataIndex: 'fpRate', render: (v, r) => <Tag color={r.high ? 'red' : 'green'}>{v}%</Tag> }, { title: '标记', render: (_, r) => r.high ? <Tag color="red">需 review</Tag> : '—' }]} />
        </details>
      </Space>
    </Card>
  )
}
