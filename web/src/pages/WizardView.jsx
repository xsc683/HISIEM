import { Button, Card, Descriptions, Input, Select, Space, Steps, Table, Tag } from 'antd'

export default function WizardView({ step, setStep, templates, selectedId, setSelectedId, selected, sample, setSample,
  testResult, handleTest, busy, name, setName, port, setPort, protocol, setProtocol, sourcePath, setSourcePath, config, handlePreview,
  srcName, setSrcName, srcPort, setSrcPort, srcProtocol, setSrcProtocol, srcPath, setSrcPath, handleCreateSource, sources, activating, handleActivate,
  handleDeactivate, handleDeleteSource }) {
  const steps = [
    { title: '选模板', description: '选择日志类型' },
    { title: '测样例', description: '验证解析' },
    { title: '配预览', description: '生成配置' },
    { title: '创建生效', description: '接入完成' },
  ]

  return (
    <div>
      <Card style={{ marginBottom: 16 }}>
        <Steps current={step} items={steps} responsive />
      </Card>

      {step === 0 && (
        <Card title="① 选择解析模板">
          <Space direction="vertical" size="middle" style={{ width: '100%' }}>
            <Select showSearch style={{ width: '100%' }} value={selectedId || undefined}
              placeholder="-- 请选择(来自模板库) --" onChange={setSelectedId} optionFilterProp="label"
              options={templates.map((t) => ({ value: t.id, label: `${t.name} (${t.id})` }))} />
            {selected && (
              <Descriptions size="small" column={2} bordered>
                <Descriptions.Item label="说明">{selected.description}</Descriptions.Item>
                <Descriptions.Item label="协议">{selected.protocol}</Descriptions.Item>
                <Descriptions.Item label="ID"><code>{selected.id}</code></Descriptions.Item>
                <Descriptions.Item label="状态"><Tag color={selected.status === 'stable' ? 'green' : 'orange'}>{selected.status}</Tag></Descriptions.Item>
              </Descriptions>
            )}
          </Space>
        </Card>
      )}

      {step === 1 && (
        <Card title="② 解析测试(粘贴样例日志)">
          <Space direction="vertical" size="middle" style={{ width: '100%' }}>
            <Input.TextArea rows={3} maxLength={8192} showCount value={sample} onChange={(e) => setSample(e.target.value)}
              placeholder="粘贴一条日志样例,如:Aug 1 10:20:00 server03 sshd[9999]: Failed password for test from 172.16.1.20" />
            <Button type="primary" loading={busy} onClick={handleTest}>测试解析</Button>
            {testResult && (
              <div>
                <Tag color={testResult.ok ? 'green' : 'red'}>{testResult.ok ? '✓ 解析成功' : '✗ 解析失败(未匹配任何 grok 模式)'}</Tag>
                {testResult.ok && (
                  <Table rowKey="k" size="small" pagination={false} style={{ marginTop: 8 }}
                    dataSource={Object.entries(testResult.fields).map(([k, v]) => ({ k, v: String(v) }))}
                    columns={[{ title: '字段', dataIndex: 'k', render: (v) => <code>{v}</code> }, { title: '值', dataIndex: 'v', render: (v) => <code>{v}</code> }]} />
                )}
              </div>
            )}
          </Space>
        </Card>
      )}

      {step === 2 && (
        <Card title="③ 数据源配置预览(声明 → 生成 Logstash 配置)">
          <Space direction="vertical" size="middle" style={{ width: '100%' }}>
            <Space wrap>
              <div><div style={{ marginBottom: 4, fontSize: 12, color: '#666' }}>数据源名称</div><Input style={{ width: 200 }} value={name} onChange={(e) => setName(e.target.value)} placeholder="如 ssh-auth-web-01" /></div>
              <div><div style={{ marginBottom: 4, fontSize: 12, color: '#666' }}>协议</div><Select style={{ width: 120 }} value={protocol} onChange={setProtocol} options={['tcp', 'syslog', 'file'].map((v) => ({ value: v, label: v }))} /></div>
              {protocol === 'file'
                ? <Input style={{ width: 300 }} value={sourcePath} onChange={(e) => setSourcePath(e.target.value)} placeholder="/var/log/auth.log" />
                : <div><div className="field-label">采集端口</div><Input style={{ width: 140 }} type="number" value={port} onChange={(e) => setPort(e.target.value)} /></div>}
            </Space>
            <Button loading={busy} onClick={handlePreview}>生成配置</Button>
            {config && <pre style={{ background: '#0f1d33', color: '#a8d4ff', padding: 14, borderRadius: 8, fontSize: 12, whiteSpace: 'pre-wrap' }}>{config}</pre>}
          </Space>
        </Card>
      )}

      {step === 3 && (
        <Card title="④ 创建数据源并生效">
          <Space direction="vertical" size="middle" style={{ width: '100%' }}>
            <Space wrap>
              <div><div style={{ marginBottom: 4, fontSize: 12, color: '#666' }}>数据源名称</div><Input style={{ width: 200 }} value={srcName} onChange={(e) => setSrcName(e.target.value)} placeholder="如 ssh-web-01" /></div>
              <div><div style={{ marginBottom: 4, fontSize: 12, color: '#666' }}>协议</div><Select style={{ width: 120 }} value={srcProtocol} onChange={setSrcProtocol} options={['tcp', 'syslog', 'file'].map((v) => ({ value: v, label: v }))} /></div>
              {srcProtocol === 'file'
                ? <Input style={{ width: 300 }} value={srcPath} onChange={(e) => setSrcPath(e.target.value)} placeholder="/var/log/auth.log" />
                : <div><div className="field-label">采集端口</div><Input style={{ width: 140 }} type="number" value={srcPort} onChange={(e) => setSrcPort(e.target.value)} /></div>}
            </Space>
            <Button type="primary" loading={busy} onClick={handleCreateSource}>创建数据源</Button>
            {sources.length > 0 && (
              <Table rowKey="id" size="small" dataSource={sources} pagination={{ pageSize: 5, showTotal: (t) => `共 ${t} 个数据源` }}
                columns={[
                  { title: 'ID', dataIndex: 'id', render: (v) => <code>{v}</code> }, { title: '名称', dataIndex: 'name' },
                  { title: '模板', dataIndex: 'templateId' }, { title: '协议', dataIndex: 'protocol' },
                  { title: '端口/路径', render: (_, s) => s.protocol === 'file' ? s.path : s.port },
                  { title: '状态', dataIndex: 'status', render: (v) => <Tag color={v === 'active' ? 'green' : v === 'failed' ? 'red' : 'orange'}>{v}</Tag> },
                  { title: '操作', render: (_, s) => activating[s.id] ? <Tag color="processing">处理中…</Tag> : (
                    <Space size="small">
                      {(s.status === 'creating' || s.status === 'failed' || s.status === 'stopped') && <Button size="small" type="primary" onClick={() => handleActivate(s.id)}>生效</Button>}
                      {s.status === 'active' && <Button size="small" onClick={() => handleDeactivate(s.id)}>停用</Button>}
                      {s.status !== 'active' && <Button size="small" danger onClick={() => handleDeleteSource(s.id)}>删除</Button>}
                    </Space>
                  ) },
                ]} />
            )}
          </Space>
        </Card>
      )}

      <Card>
        <Space style={{ width: '100%', justifyContent: step === 0 ? 'flex-end' : 'space-between' }}>
          {step > 0 && <Button onClick={() => setStep(step - 1)}>上一步</Button>}
          {step < 3 && <Button type="primary" onClick={() => setStep(step + 1)}>下一步</Button>}
          {step === 3 && <Button type="primary" onClick={() => setStep(0)}>完成</Button>}
        </Space>
      </Card>
    </div>
  )
}
