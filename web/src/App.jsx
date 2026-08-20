import { useEffect, useState } from 'react'
import { ConfigProvider, Layout, Menu, Button, Input, Select, Table, Card, Tag, Steps, Space, Descriptions, Badge, Tabs, Alert, Typography, Divider, Empty, Modal, message, theme } from 'antd'
import {
  LoginOutlined, LogoutOutlined, BellOutlined, SafetyCertificateOutlined,
  AlertOutlined, DeploymentUnitOutlined, BarChartOutlined, TagOutlined, TeamOutlined,
  MenuFoldOutlined, MenuUnfoldOutlined, ThunderboltOutlined, ApiOutlined,
} from '@ant-design/icons'
import {
  listTemplates, testParse, previewLogSource,
  listLogSources, createLogSource, activateLogSource, getLogSource,
  deactivateLogSource, deleteLogSource,
  listDetectionRules, toggleRule, deployRules, ruleMitre,
  dataHealthSources, dataHealthTrend, dataHealthFailures,
  listCriticality, setCriticality, deleteCriticality, recalcCriticality,
  login, logout, authMe, listUsers, createUser, deleteUser, updateUserRole, listRoles, auditLogs,
  listNotifications, readNotification, readAllNotifications, deleteNotification,
  listAlerts, getAlert, updateAlertStatus, updateAlertVerdict,
  batchAlertStatus, batchAlertVerdict, fpRate,
  listCases, getCase, createCase, addCaseAlerts, removeCaseAlert,
  updateCaseStatus, caseTimeline, deleteCase, aggregateCases,
  updateCaseMetadata, healthScan, listTasks,
} from './api.js'
import { pathFromRouteKey, routeKeyFromPath } from './routes.js'

const { Header, Sider, Content } = Layout

// 状态 → Tag 颜色
const STATUS_TAG = {
  open: 'red', acknowledged: 'orange', investigating: 'gold', resolved: 'blue', closed: 'default',
}
const VERDICT_TAG = {
  true_positive: 'red', false_positive: 'green', duplicate: 'gray',
}
const RISK_COLOR = (score) => {
  if (score >= 80) return 'red'
  if (score >= 60) return 'orange'
  if (score >= 40) return 'gold'
  return 'green'
}

const MENU_ITEMS = [
  { key: 'wizard', icon: <ApiOutlined />, label: '接入向导' },
  { key: 'rules', icon: <DeploymentUnitOutlined />, label: '检测规则' },
  { key: 'alerts', icon: <AlertOutlined />, label: '告警台' },
  { key: 'cases', icon: <SafetyCertificateOutlined />, label: '调查台' },
  { key: 'health', icon: <BarChartOutlined />, label: '数据健康' },
  { key: 'ops-health', icon: <ThunderboltOutlined />, label: '运行态扫描' },
  { key: 'criticality', icon: <TagOutlined />, label: '资产关键度' },
  { key: 'notify', icon: <BellOutlined />, label: '通知中心' },
  { key: 'rbac', icon: <TeamOutlined />, label: '用户与权限' },
]

export default function App() {
  const [templates, setTemplates] = useState([])
  const [selectedId, setSelectedId] = useState('')
  const [sample, setSample] = useState('')
  const [testResult, setTestResult] = useState(null)
  const [name, setName] = useState('')
  const [port, setPort] = useState(5001)
  const [config, setConfig] = useState('')
  const [busy, setBusy] = useState(false)

  const [sources, setSources] = useState([])
  const [srcName, setSrcName] = useState('')
  const [srcPort, setSrcPort] = useState(5001)
  const [activating, setActivating] = useState({})

  const [detRules, setDetRules] = useState([])
  const [deploying, setDeploying] = useState(false)
  const [deployMsg, setDeployMsg] = useState('')
  const [mitre, setMitre] = useState({})

  const [health, setHealth] = useState([])
  const [healthDetail, setHealthDetail] = useState(null)
  const [healthLoading, setHealthLoading] = useState(false)

  const [crit, setCrit] = useState({})
  const [critType, setCritType] = useState('ip')
  const [critKey, setCritKey] = useState('')
  const [critLevel, setCritLevel] = useState('high')
  const [recalcMsg, setRecalcMsg] = useState('')

  const [user, setUser] = useState(null)
  const [loginUser, setLoginUser] = useState('')
  const [loginPass, setLoginPass] = useState('')
  const [users, setUsers] = useState([])
  const [roles, setRoles] = useState([])
  const [audit, setAudit] = useState([])
  const [newUname, setNewUname] = useState('')
  const [newPass, setNewPass] = useState('')
  const [newRole, setNewRole] = useState('analyst')

  const [notifs, setNotifs] = useState([])

  const [alerts, setAlerts] = useState([])
  const [alertFilter, setAlertFilter] = useState('open')
  const [selAlerts, setSelAlerts] = useState(new Set())
  const [detailAlert, setDetailAlert] = useState(null)
  const [fpRates, setFpRates] = useState([])

  const [cases, setCases] = useState([])
  const [caseFilter, setCaseFilter] = useState('')
  const [detailCase, setDetailCase] = useState(null)
  const [caseTitle, setCaseTitle] = useState('')
  const [caseTimeline_, setCaseTimeline_] = useState([])
  const [opsHealth, setOpsHealth] = useState(null)
  const [tasks, setTasks] = useState([])
  const [caseOwner, setCaseOwner] = useState('')
  const [evidenceTitle, setEvidenceTitle] = useState('')
  const [evidenceUri, setEvidenceUri] = useState('')

  const [activeKey, setActiveKey] = useState(() => routeKeyFromPath())
  const [collapsed, setCollapsed] = useState(false)
  const [wizardStep, setWizardStep] = useState(0)

  function navigate(key) {
    setActiveKey(key)
    const path = pathFromRouteKey(key)
    if (window.location.pathname !== path) window.history.pushState({}, '', path)
  }

  useEffect(() => {
    const onPopState = () => setActiveKey(routeKeyFromPath())
    window.addEventListener('popstate', onPopState)
    return () => window.removeEventListener('popstate', onPopState)
  }, [])

  useEffect(() => {
    listAlerts(alertFilter).then(setAlerts).catch(() => {})
    fpRate().then(setFpRates).catch(() => {})
    listCases(caseFilter).then(setCases).catch(() => {})
    listTemplates().then(setTemplates).catch(() => {})
    listLogSources().then(setSources).catch(() => {})
    listDetectionRules().then(setDetRules).catch(() => {})
    ruleMitre().then(setMitre).catch(() => {})
    dataHealthSources().then(setHealth).catch(() => {})
    listCriticality().then(setCrit).catch(() => {})
    authMe().then(setUser).catch(() => setUser(null))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  useEffect(() => {
    if (user && activeKey === 'ops-health') {
      healthScan().then(setOpsHealth).catch((e) => message.error(e.message))
      listTasks(50).then(setTasks).catch(() => {})
    }
  }, [user, activeKey])

  useEffect(() => {
    if (user && user.role === 'admin') {
      listUsers().then(setUsers).catch(() => {})
      listRoles().then(setRoles).catch(() => {})
      auditLogs().then(setAudit).catch(() => {})
    } else {
      setUsers([]); setRoles([]); setAudit([])
    }
  }, [user])

  useEffect(() => {
    const loadNotifs = () => listNotifications().then(setNotifs).catch(() => {})
    loadNotifs()
    const timer = setInterval(loadNotifs, 20000)
    return () => clearInterval(timer)
  }, [])

  async function handleLogin() {
    try {
      const r = await login(loginUser.trim(), loginPass)
      setUser({ username: r.username, role: r.role })
      setLoginPass('')
      message.success('登录成功')
    } catch (e) { message.error(e.message) }
  }

  async function handleLogout() {
    await logout()
    setUser(null)
  }

  async function handleCreateUser() {
    if (!newUname.trim()) { message.warning('填用户名'); return }
    try {
      await createUser({ username: newUname.trim(), password: newPass, role: newRole })
      setNewUname(''); setNewPass('')
      setUsers(await listUsers())
      setAudit(await auditLogs())
      message.success('用户已创建')
    } catch (e) { message.error(e.message) }
  }

  async function handleDelUser(username) {
    try {
      await deleteUser(username)
      setUsers(await listUsers())
      setAudit(await auditLogs())
      message.success('用户已删除')
    } catch (e) { message.error(e.message) }
  }

  async function handleRoleChange(username, role) {
    try {
      await updateUserRole(username, role)
      setUsers(await listUsers())
      setAudit(await auditLogs())
      message.success(`角色已更新为 ${role}`)
    } catch (e) { message.error(e.message) }
  }

  async function handleCritSet(type, key, level) {
    try {
      await setCriticality(type, key, level)
      setCrit(await listCriticality())
    } catch (e) { message.error(e.message) }
  }

  async function handleCritDelete(type, key) {
    try {
      await deleteCriticality(type, key)
      setCrit(await listCriticality())
    } catch (e) { message.error(e.message) }
  }

  async function handleCritAdd() {
    if (!critKey.trim()) { message.warning('填资产键'); return }
    try {
      await setCriticality(critType, critKey.trim(), critLevel)
      setCritKey('')
      message.success('关键度已设置')
    } catch (e) { message.error(e.message) }
  }

  async function handleRecalc() {
    setRecalcMsg('重算中(约数秒)…')
    try {
      const r = await recalcCriticality()
      setRecalcMsg(r.output.split('\n').filter((l) => l.trim()).slice(-3).join(' / '))
      message.success('实体风险已重算')
    } catch (e) { setRecalcMsg(`重算失败: ${e.message}`) }
  }

  async function handleHealthDetail(sourceId) {
    setHealthLoading(true)
    try {
      const [trend, failures] = await Promise.all([
        dataHealthTrend(sourceId), dataHealthFailures(sourceId, 20),
      ])
      setHealthDetail({ sourceId, trend, failures })
    } catch (e) {
      setHealthDetail(null)
      message.error(`加载健康详情失败: ${e.message}`)
    } finally { setHealthLoading(false) }
  }

  async function handleToggleRule(id) {
    try {
      const updated = await toggleRule(id)
      setDetRules((prev) => prev.map((r) => (r.id === id ? { ...r, enabled: updated.enabled } : r)))
      message.success(`规则 ${id} → ${updated.enabled ? '已启用' : '已停用'}(点部署生效后重启检测 job)`)
    } catch (e) { message.error(e.message) }
  }

  async function handleDeployRules() {
    setDeploying(true)
    setDeployMsg('')
    try {
      const r = await deployRules()
      setDeployMsg(`部署完成:jobId=${r.jobId}`)
      message.success('规则已部署生效')
    } catch (e) {
      setDeployMsg(`部署失败: ${e.message}`)
      message.error(`部署失败: ${e.message}`)
    } finally { setDeploying(false) }
  }

  const selected = templates.find((t) => t.id === selectedId)

  async function handleTest() {
    if (!selectedId || !sample.trim()) { message.warning('先选模板并粘贴样例日志'); return }
    setBusy(true)
    try {
      setTestResult(await testParse(selectedId, sample.trim()))
    } catch (e) { message.error(e.message) } finally { setBusy(false) }
  }

  async function handlePreview() {
    if (!selectedId) { message.warning('先选模板'); return }
    setBusy(true)
    try {
      const r = await previewLogSource({ name, protocol: 'tcp', templateId: selectedId, port: Number(port) })
      setConfig(`# 数据源:${name || '未命名'} (${r.template})\ninput {\n  ${r.input}\n}\n\nfilter {\n${r.config}}`)
    } catch (e) { message.error(e.message) } finally { setBusy(false) }
  }

  async function handleCreateSource() {
    if (!selectedId) { message.warning('先选模板'); return }
    if (!srcName.trim()) { message.warning('填数据源名称'); return }
    setBusy(true)
    try {
      const s = await createLogSource({ name: srcName.trim(), protocol: 'tcp', templateId: selectedId, port: Number(srcPort) })
      setSources([...sources, s])
      setSrcName('')
      message.success(`数据源 ${s.id} 已创建,点「生效」接入`)
    } catch (e) { message.error(e.message) } finally { setBusy(false) }
  }

  function handleActivate(id) {
    setActivating((prev) => ({ ...prev, [id]: true }))
    activateLogSource(id)
      .then(() => pollSource(id))
      .catch((e) => { setActivating((prev) => ({ ...prev, [id]: false })); message.error(e.message) })
  }

  function handleDeactivate(id) {
    setActivating((prev) => ({ ...prev, [id]: true }))
    deactivateLogSource(id)
      .then(() => pollSource(id, ['stopped']))
      .catch((e) => { setActivating((prev) => ({ ...prev, [id]: false })); message.error(e.message) })
  }

  async function handleDeleteSource(id) {
    try {
      await deleteLogSource(id)
      setSources((prev) => prev.filter((s) => s.id !== id))
      message.success(`数据源 ${id} 已删除`)
    } catch (e) { message.error(e.message) }
  }

  function pollSource(id, terminalStatuses = ['active', 'failed']) {
    const timer = setInterval(async () => {
      try {
        const s = await getLogSource(id)
        setSources((prev) => prev.map((x) => (x.id === id ? s : x)))
        if (terminalStatuses.includes(s.status)) {
          clearInterval(timer)
          setActivating((prev) => ({ ...prev, [id]: false }))
          message.success(s.status === 'active' ? `数据源 ${id} 已生效` : s.status === 'stopped' ? `数据源 ${id} 已停用` : `数据源 ${id} 生效失败`)
        }
      } catch {
        clearInterval(timer)
        setActivating((prev) => ({ ...prev, [id]: false }))
      }
    }, 2000)
  }

  function toggleSel(id) {
    setSelAlerts((prev) => {
      const next = new Set(prev)
      if (next.has(id)) next.delete(id); else next.add(id)
      return next
    })
  }

  async function handleAlertDetail(id) {
    setDetailAlert(await getAlert(id).catch((e) => { message.error(e.message); return null }))
  }

  async function handleAlertStatus(id, status) {
    await updateAlertStatus(id, status).catch((e) => message.error(e.message))
    setDetailAlert(null)
    reloadAlerts()
  }

  async function handleAlertVerdict(id, verdict) {
    await updateAlertVerdict(id, verdict).catch((e) => message.error(e.message))
    if (detailAlert && detailAlert._id === id) setDetailAlert(await getAlert(id).catch(() => null))
    reloadAlerts()
  }

  async function handleBatchStatus(status) {
    if (selAlerts.size === 0) { message.warning('先勾选告警'); return }
    if (status === 'closed' && !window.confirm('批量结案将要求已打 verdict,确认?')) return
    try {
      const r = await batchAlertStatus([...selAlerts], status)
      message.success(`批量 ${status}:成功 ${r.succeeded}/${r.total}`)
      setSelAlerts(new Set())
      reloadAlerts()
    } catch (e) { message.error(e.message) }
  }

  async function handleBatchVerdict(verdict) {
    if (selAlerts.size === 0) { message.warning('先勾选告警'); return }
    try {
      const r = await batchAlertVerdict([...selAlerts], verdict)
      message.success(`批量 verdict ${verdict}:成功 ${r.succeeded}/${r.total}`)
      setSelAlerts(new Set())
      reloadAlerts()
    } catch (e) { message.error(e.message) }
  }

  async function reloadAlerts() {
    setAlerts(await listAlerts(alertFilter).catch(() => []))
  }

  async function reloadCases() {
    setCases(await listCases(caseFilter).catch(() => []))
  }

  async function handleCreateCase() {
    const ids = [...selAlerts]
    if (ids.length < 2) { message.warning('至少勾选 2 条 open 告警'); return }
    try {
      const c = await createCase(ids, caseTitle || `案件 ${new Date().toISOString().slice(0, 10)}`)
      setSelAlerts(new Set())
      await reloadCases()
      openCaseDetail(c['case.id'])
      message.success('案件已创建')
    } catch (e) { message.error(e.message) }
  }

  async function openCaseDetail(id) {
    const c = await getCase(id).catch(() => null)
    setDetailCase(c)
    if (c) {
      setCaseOwner(c['case.owner'] || '')
      setCaseTimeline_(await caseTimeline(id, 30).catch(() => []))
    }
  }

  async function handleUpdateCaseMetadata(id) {
    try {
      const evidence = evidenceTitle.trim() || evidenceUri.trim()
        ? [{ type: 'reference', title: evidenceTitle.trim(), uri: evidenceUri.trim() }]
        : (detailCase?.evidence || [])
      const updated = await updateCaseMetadata(id, { owner: caseOwner.trim() || null, evidence })
      setDetailCase(updated)
      setEvidenceTitle(''); setEvidenceUri('')
      await reloadCases()
      message.success('负责人/证据已保存')
    } catch (e) { message.error(e.message) }
  }

  async function handleResolveCase(id, verdict) {
    if (!verdict) { message.warning('结案必选 verdict'); return }
    try {
      await updateCaseStatus(id, 'resolved', verdict)
      await reloadCases()
      openCaseDetail(id)
      message.success('案件已结案,内部告警批量 closed')
    } catch (e) { message.error(e.message) }
  }

  async function handleInvestigateCase(id) {
    try {
      await updateCaseStatus(id, 'investigating', null)
      await reloadCases()
      openCaseDetail(id)
    } catch (e) { message.error(e.message) }
  }

  async function handleAddToCase(id) {
    const ids = [...selAlerts]
    if (!ids.length) { message.warning('先勾选 open 告警'); return }
    try {
      await addCaseAlerts(id, ids)
      setSelAlerts(new Set())
      openCaseDetail(id)
    } catch (e) { message.error(e.message) }
  }

  async function handleRemoveFromCase(id, alertId) {
    try {
      await removeCaseAlert(id, alertId)
      openCaseDetail(id)
    } catch (e) { message.error(e.message) }
  }

  async function handleDeleteCase(id) {
    try {
      await deleteCase(id)
      setDetailCase(null)
      await reloadCases()
      message.success('案件已删除')
    } catch (e) { message.error(e.message) }
  }

  async function handleAggregate() {
    try {
      const r = await aggregateCases()
      await reloadCases()
      message.success(`自动聚合完成,新建 ${r.created} 个案件`)
    } catch (e) { message.error(e.message) }
  }

  async function handleReadNotif(id) {
    await readNotification(id).catch(() => {})
    setNotifs(await listNotifications().catch(() => []))
  }

  async function handleReadAllNotifs() {
    await readAllNotifications().catch(() => {})
    setNotifs(await listNotifications().catch(() => []))
  }

  async function handleDelNotif(id) {
    await deleteNotification(id).catch(() => {})
    setNotifs(await listNotifications().catch(() => []))
  }

  // ================= 登录页 =================
  if (!user) {
    return (
      <div style={{
        minHeight: '100vh', display: 'flex', alignItems: 'center', justifyContent: 'center',
        background: 'linear-gradient(135deg, #0f1d33 0%, #1e3354 60%, #2a4a78 100%)',
      }}>
        <Card style={{ width: 380, borderRadius: 12, boxShadow: '0 20px 60px rgba(0,0,0,0.4)' }}>
          <div style={{ textAlign: 'center', marginBottom: 24 }}>
            <div style={{
              width: 56, height: 56, margin: '0 auto 12px', borderRadius: 14,
              background: 'linear-gradient(135deg, #1677ff, #722ed1)', color: '#fff',
              display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: 28, fontWeight: 700,
            }}>H</div>
            <Typography.Title level={3} style={{ marginBottom: 0 }}>HISIEM 安全运营中心</Typography.Title>
            <Typography.Text type="secondary">轻量 SIEM · Elastic Stack + Flink</Typography.Text>
          </div>
          <Space direction="vertical" size="middle" style={{ width: '100%' }}>
            <Input size="large" prefix={<LoginOutlined />} value={loginUser}
              onChange={(e) => setLoginUser(e.target.value)} placeholder="用户名(默认 admin)" />
            <Input.Password size="large" value={loginPass}
              onChange={(e) => setLoginPass(e.target.value)}
              onPressEnter={handleLogin}
              placeholder="密码(admin123)" />
            <Button type="primary" size="large" block onClick={handleLogin}>登 录</Button>
          </Space>
        </Card>
      </div>
    )
  }

  // ================= 主布局 =================
  const unreadCount = notifs.filter((n) => !n.read).length
  const openAlertCount = alerts.filter((a) => a['alert.status'] === 'open').length

  const menuItems = MENU_ITEMS.map((it) => ({
    ...it,
    label: (
      <span>
        {it.label}
        {it.key === 'alerts' && openAlertCount > 0 && <Badge count={openAlertCount} style={{ marginLeft: 8, backgroundColor: '#f5222d' }} />}
        {it.key === 'notify' && unreadCount > 0 && <Badge count={unreadCount} style={{ marginLeft: 8, backgroundColor: '#faad14' }} />}
      </span>
    ),
  }))

  return (
    <ConfigProvider theme={{ algorithm: theme.defaultAlgorithm, token: { colorPrimary: '#1677ff', borderRadius: 8 } }}>
      <Layout style={{ minHeight: '100vh' }}>
        <Sider collapsible collapsed={collapsed} onCollapse={setCollapsed} theme="dark" width={200}>
          <div style={{
            height: 56, margin: 8, borderRadius: 8, background: 'linear-gradient(135deg, #1677ff, #722ed1)',
            display: 'flex', alignItems: 'center', justifyContent: 'center', color: '#fff',
            fontWeight: 700, fontSize: collapsed ? 14 : 16, gap: 8,
          }}>
            <SafetyCertificateOutlined /> {!collapsed && 'HISIEM'}
          </div>
          <Menu theme="dark" mode="inline" selectedKeys={[activeKey]} items={menuItems}
            onClick={({ key }) => navigate(key)} />
        </Sider>

        <Layout>
          <Header style={{
            background: '#fff', padding: '0 20px', display: 'flex', alignItems: 'center',
            justifyContent: 'space-between', boxShadow: '0 1px 4px rgba(0,21,41,0.08)', zIndex: 1,
          }}>
            <Space>
              <Button type="text" icon={collapsed ? <MenuUnfoldOutlined /> : <MenuFoldOutlined />}
                onClick={() => setCollapsed(!collapsed)} />
              <Typography.Text strong style={{ fontSize: 16 }}>{MENU_ITEMS.find((m) => m.key === activeKey)?.label}</Typography.Text>
            </Space>
            <Space size="middle">
              <Badge count={openAlertCount} size="small"><Button icon={<AlertOutlined />} onClick={() => navigate('alerts')}>告警</Button></Badge>
              <Badge count={unreadCount} size="small"><Button icon={<BellOutlined />} onClick={() => navigate('notify')}>通知</Button></Badge>
              <Space>
                <AvatarUser username={user.username} role={user.role} />
                <Button icon={<LogoutOutlined />} onClick={handleLogout}>退出</Button>
              </Space>
            </Space>
          </Header>

          <Content style={{ padding: 20, background: '#f5f7fa' }}>
            {/* ===== 接入向导(四步合一) ===== */}
            {activeKey === 'wizard' && (
              <WizardView
                step={wizardStep} setStep={setWizardStep}
                templates={templates} selectedId={selectedId} setSelectedId={setSelectedId}
                selected={selected} sample={sample} setSample={setSample}
                testResult={testResult} handleTest={handleTest} busy={busy}
                name={name} setName={setName} port={port} setPort={setPort}
                config={config} handlePreview={handlePreview}
                srcName={srcName} setSrcName={setSrcName} srcPort={srcPort} setSrcPort={setSrcPort}
                handleCreateSource={handleCreateSource}
                sources={sources} activating={activating} handleActivate={handleActivate}
                handleDeactivate={handleDeactivate} handleDeleteSource={handleDeleteSource}
              />
            )}

            {/* ===== 检测规则 ===== */}
            {activeKey === 'rules' && (
              <Card>
                <Space direction="vertical" size="middle" style={{ width: '100%' }}>
                  <Space>
                    <Button type="primary" icon={<ThunderboltOutlined />} loading={deploying} onClick={handleDeployRules}>
                      {deploying ? '部署生效中(约 15-35s)…' : '部署生效(同步规则 + 重启检测 job)'}
                    </Button>
                    {deployMsg && <Tag color={deployMsg.startsWith('部署失败') ? 'red' : 'green'}>{deployMsg}</Tag>}
                  </Space>
                  <Table
                    rowKey="id"
                    dataSource={detRules}
                    size="small"
                    pagination={{ pageSize: 10, showTotal: (t) => `共 ${t} 条规则` }}
                    columns={[
                      { title: '规则 ID', dataIndex: 'id', render: (v) => <code>{v}</code> },
                      { title: '名称', dataIndex: 'name' },
                      { title: '类别', dataIndex: 'category', render: (v) => <Tag>{v}</Tag> },
                      { title: 'type', dataIndex: 'type', render: (v) => <code>{v}</code> },
                      { title: '风险分', dataIndex: 'riskScore', sorter: (a, b) => a.riskScore - b.riskScore, render: (v) => <Tag color={RISK_COLOR(v)}>{v}</Tag> },
                      { title: 'MITRE', dataIndex: 'tags', render: (tags) => (tags || []).map((t, i) => <Tag key={i} color="blue">{t}</Tag>) },
                      { title: '状态', dataIndex: 'enabled', render: (en) => en ? <Tag color="green">启用</Tag> : <Tag>停用</Tag> },
                      { title: '启停', render: (_, r) => <Button size="small" danger={r.enabled} onClick={() => handleToggleRule(r.id)}>{r.enabled ? '停用' : '启用'}</Button> },
                    ]}
                  />
                  {mitre.coverage && mitre.coverage.length > 0 && (
                    <details>
                      <summary>MITRE ATT&CK 覆盖({mitre.coverage.length} 条)</summary>
                      <Table rowKey={(_, i) => i} size="small" pagination={false}
                        dataSource={mitre.coverage}
                        columns={[
                          { title: '技术', dataIndex: 'technique', render: (v) => <code>{v}</code> },
                          { title: '规则', dataIndex: 'ruleId' },
                          { title: '覆盖', dataIndex: 'coverage' },
                        ]} />
                    </details>
                  )}
                </Space>
              </Card>
            )}

            {/* ===== 告警台 ===== */}
            {activeKey === 'alerts' && (
              <Card>
                <Space direction="vertical" size="middle" style={{ width: '100%' }}>
                  <Space wrap>
                    <Select value={alertFilter} style={{ width: 160 }} onChange={(v) => { setAlertFilter(v); listAlerts(v).then(setAlerts) }}
                      options={['open', 'acknowledged', 'investigating', 'resolved', 'closed'].map((s) => ({ value: s, label: s }))} />
                    <Button onClick={() => handleBatchStatus('acknowledged')}>批量 ack</Button>
                    <Button danger onClick={() => handleBatchStatus('closed')}>批量 close</Button>
                    <Select placeholder="批量 verdict…" style={{ width: 180 }} onChange={(v) => v && handleBatchVerdict(v)}
                      options={['true_positive', 'false_positive', 'duplicate'].map((v) => ({ value: v, label: v }))} />
                    <Typography.Text type="secondary">已勾选 {selAlerts.size} 条</Typography.Text>
                  </Space>
                  <Table
                    rowKey="_id"
                    dataSource={alerts}
                    size="small"
                    pagination={{ pageSize: 15, showTotal: (t) => `共 ${t} 条告警` }}
                    rowSelection={{
                      selectedRowKeys: [...selAlerts],
                      onChange: (keys) => setSelAlerts(new Set(keys)),
                    }}
                    expandable={{
                      expandedRowRender: (r) => (
                        <Space direction="vertical" size="small" style={{ width: '100%' }}>
                          <Space wrap>
                            <Button size="small" onClick={() => handleAlertStatus(r._id, 'acknowledged')}>ack</Button>
                            <Button size="small" onClick={() => handleAlertStatus(r._id, 'investigating')}>investigating</Button>
                            <Button size="small" danger onClick={() => handleAlertStatus(r._id, 'closed')}>close</Button>
                            <Select size="small" placeholder="verdict…" style={{ width: 160 }}
                              onChange={(v) => handleAlertVerdict(r._id, v)}
                              options={['true_positive', 'false_positive', 'duplicate'].map((v) => ({ value: v, label: v }))} />
                          </Space>
                          <pre style={{ background: '#0f1d33', color: '#a8d4ff', padding: 10, borderRadius: 6, fontSize: 12, maxHeight: 220, overflow: 'auto', whiteSpace: 'pre-wrap' }}>
                            {JSON.stringify(r, null, 2).slice(0, 2500)}
                          </pre>
                        </Space>
                      ),
                    }}
                    columns={[
                      { title: '风险', dataIndex: 'alert.risk_score', width: 70, sorter: (a, b) => a['alert.risk_score'] - b['alert.risk_score'], render: (v) => <Tag color={RISK_COLOR(v)}>{v}</Tag> },
                      { title: '规则', dataIndex: 'alert.rule_name', render: (v, r) => <>{v} <Typography.Text type="secondary" style={{ fontSize: 11 }}>{r['alert.rule_id']}</Typography.Text></> },
                      { title: 'severity', dataIndex: 'alert.severity', render: (v) => <Tag color={v === 'critical' ? 'red' : v === 'high' ? 'orange' : v === 'medium' ? 'gold' : 'blue'}>{v}</Tag> },
                      { title: '状态', dataIndex: 'alert.status', render: (v) => <Tag color={STATUS_TAG[v]}>{v}</Tag> },
                      { title: 'verdict', dataIndex: 'alert.analyst_verdict', render: (v) => v ? <Tag color={VERDICT_TAG[v]}>{v}</Tag> : <Typography.Text type="secondary">—</Typography.Text> },
                      { title: '来源', dataIndex: 'source.ip', render: (v, r) => v || r['user.name'] || '—' },
                      { title: '时间', dataIndex: '@timestamp', width: 180, render: (v) => <Typography.Text type="secondary" style={{ fontSize: 11 }}>{v}</Typography.Text> },
                    ]}
                  />
                  <details>
                    <summary>按规则 FP 率({fpRates.filter((r) => r.high).length} 条 &gt;50% 需 review)</summary>
                    <Table rowKey="ruleId" size="small" pagination={false} dataSource={fpRates}
                      columns={[
                        { title: '规则', dataIndex: 'ruleId', render: (v) => <code>{v}</code> },
                        { title: '总数', dataIndex: 'total' },
                        { title: 'FP', dataIndex: 'fp' },
                        { title: 'TP', dataIndex: 'tp' },
                        { title: 'FP 率', dataIndex: 'fpRate', render: (v, r) => <Tag color={r.high ? 'red' : 'green'}>{v}%</Tag> },
                        { title: '标记', render: (_, r) => r.high ? <Tag color="red">需 review</Tag> : '—' },
                      ]} />
                  </details>
                </Space>
              </Card>
            )}

            {/* ===== 调查台 ===== */}
            {activeKey === 'cases' && (
              <CasesView
                cases={cases} setCases={setCases} caseFilter={caseFilter} setCaseFilter={setCaseFilter}
                selAlerts={selAlerts} setSelAlerts={setSelAlerts} caseTitle={caseTitle} setCaseTitle={setCaseTitle}
                detailCase={detailCase} setDetailCase={setDetailCase} caseTimeline_={caseTimeline_} openCaseDetail={openCaseDetail}
                handleCreateCase={handleCreateCase} handleAggregate={handleAggregate}
                handleInvestigateCase={handleInvestigateCase} handleResolveCase={handleResolveCase}
                handleAddToCase={handleAddToCase} handleRemoveFromCase={handleRemoveFromCase}
                handleDeleteCase={handleDeleteCase} caseOwner={caseOwner} setCaseOwner={setCaseOwner}
                evidenceTitle={evidenceTitle} setEvidenceTitle={setEvidenceTitle}
                evidenceUri={evidenceUri} setEvidenceUri={setEvidenceUri}
                handleUpdateCaseMetadata={handleUpdateCaseMetadata}
              />
            )}

            {/* ===== 数据健康 ===== */}
            {activeKey === 'health' && (
              <Card>
                {health.length === 0 && <Empty description="暂无数据源事件(接入数据源并生效后,事件带 log.source_id 可聚合)" />}
                <Space direction="vertical" size="middle" style={{ width: '100%' }}>
                  {health.map((s) => (
                    <Card key={s.sourceId} size="small"
                      style={s.anomalous ? { borderColor: '#f5222d' } : {}}
                      title={
                        <Space>
                          <strong>{s.sourceName || '(未命名)'}</strong>
                          <code style={{ fontSize: 12, color: '#999' }}>{s.sourceId}</code>
                          {s.anomalous && <Tag color="red">⚠ 解析异常({s.reason})</Tag>}
                        </Space>
                      }
                      extra={<Button size="small" loading={healthLoading} onClick={() => handleHealthDetail(s.sourceId)}>详情(趋势/失败日志)</Button>}
                    >
                      <Space size="large">
                        <span>近 1h 成功 <b>{s.events1h}</b> 条</span>
                        <span>总尝试 <b>{s.totalEvents1h ?? s.events1h}</b> 条</span>
                        <span>近 24h <b>{s.events24h}</b> 条</span>
                        <span>失败率 <b style={{ color: s.failRate > 5 ? '#f5222d' : '#52c41a' }}>{s.failRate}%</b> ({s.failures1h} 条)</span>
                        <span>最后收到 <b>{s.lastSeen ? new Date(s.lastSeen).toLocaleString() : '—'}</b></span>
                      </Space>
                      {healthDetail && healthDetail.sourceId === s.sourceId && (
                        <div style={{ marginTop: 12 }}>
                          {healthDetail.trend.length > 0 && (
                            <div>
                              <Typography.Text type="secondary" style={{ fontSize: 12 }}>近 24h 事件/失败趋势(红=失败):</Typography.Text>
                              <div style={{ display: 'flex', alignItems: 'flex-end', gap: 2, height: 60, marginTop: 6 }}>
                                  {healthDetail.trend.map((t, i) => (
                                    <div key={i} title={`${t.bucket}: 事件 ${t.events} / 失败 ${t.failures}`}
                                     style={{ width: 9, background: t.failures > 0 ? '#f5222d' : '#52c41a', height: Math.max(2, Math.min(60, (t.totalEvents || t.events || 0) * 3)), borderRadius: '2px 2px 0 0' }} />
                                ))}
                              </div>
                            </div>
                          )}
                          {healthDetail.failures.length > 0 && (
                            <details style={{ marginTop: 8 }}>
                              <summary style={{ fontSize: 13 }}>最近解析失败日志({healthDetail.failures.length} 条)</summary>
                              <div style={{ maxHeight: 200, overflow: 'auto', marginTop: 6 }}>
                                {healthDetail.failures.map((f, i) => (
                                  <div key={i} style={{ fontSize: 12, marginBottom: 4 }}>
                                    <code>{f['@timestamp']}</code> {f.message}
                                  </div>
                                ))}
                              </div>
                            </details>
                          )}
                        </div>
                      )}
                    </Card>
                  ))}
                </Space>
              </Card>
            )}

            {activeKey === 'ops-health' && (
              <Card title="运行态健康扫描" extra={<Button onClick={() => {
                healthScan().then(setOpsHealth).catch((e) => message.error(e.message))
                listTasks(50).then(setTasks).catch(() => {})
              }}>重新扫描</Button>}>
                {!opsHealth && <Empty description="点击重新扫描检查 PostgreSQL、ES、Kafka、Logstash、Flink 和 Kibana" />}
                {opsHealth && <>
                  <Alert type={opsHealth.status === 'UP' ? 'success' : 'error'} showIcon
                    message={`${opsHealth.status} · 扫描时间 ${opsHealth.scannedAt}`} style={{ marginBottom: 12 }} />
                  <Table rowKey="name" size="small" pagination={false}
                    dataSource={Object.values(opsHealth.components || {})}
                    columns={[{ title: '组件', dataIndex: 'name' },
                      { title: '状态', dataIndex: 'status', render: (v) => <Tag color={v === 'UP' ? 'green' : 'red'}>{v}</Tag> },
                      { title: '延迟', dataIndex: 'latencyMs', render: (v) => `${v} ms` },
                      { title: '错误', dataIndex: 'error' }]} />
                  <Divider>后台任务进度</Divider>
                  <Table rowKey="id" size="small" pagination={{ pageSize: 8 }} dataSource={tasks}
                    columns={[{ title: '任务', dataIndex: 'type' }, { title: '资源', dataIndex: 'resourceId' },
                      { title: '状态', dataIndex: 'status' }, { title: '进度', dataIndex: 'progress', render: (v) => `${v}%` },
                      { title: '消息', dataIndex: 'message' }, { title: '更新时间', dataIndex: 'updatedAt' }]} />
                </>}
              </Card>
            )}

            {/* ===== 资产关键度 ===== */}
            {activeKey === 'criticality' && (
              <Card title="资产关键度(infra/elasticsearch/asset-criticality.json)">
                <Space wrap style={{ marginBottom: 12 }}>
                  <Select value={critType} style={{ width: 100 }} onChange={setCritType}
                    options={[{ value: 'ip', label: 'IP' }, { value: 'user', label: '用户' }, { value: 'host', label: '主机' }]} />
                  <Input style={{ width: 180 }} value={critKey} onChange={(e) => setCritKey(e.target.value)} placeholder="IP/用户名/主机名" />
                  <Select value={critLevel} style={{ width: 140 }} onChange={setCritLevel}
                    options={['low', 'medium', 'high', 'extreme'].map((l) => ({ value: l, label: l }))} />
                  <Button type="primary" onClick={handleCritAdd}>新增/更新</Button>
                  <Button onClick={handleRecalc} loading={recalcMsg === '重算中(约数秒)…'}>触发实体风险重算</Button>
                </Space>
                {recalcMsg && <Alert type="info" message={recalcMsg} showIcon style={{ marginBottom: 12 }} />}
                <Tabs items={['ip', 'user', 'host'].map((type) => ({
                  key: type, label: type === 'ip' ? 'IP' : type === 'user' ? '用户' : '主机',
                  children: Object.entries(crit[type] || {}).length === 0
                    ? <Empty description="空" />
                    : <Table rowKey="key" size="small" pagination={false} dataSource={Object.entries(crit[type] || {}).map(([k, v]) => ({ key: k, ...v }))}
                        columns={[
                          { title: '资产', dataIndex: 'key', render: (v) => <code>{v}</code> },
                          { title: '级别', dataIndex: 'level', render: (v) => <Tag color={v === 'extreme' ? 'red' : v === 'high' ? 'orange' : v === 'medium' ? 'gold' : 'green'}>{v}</Tag> },
                          { title: '权重', dataIndex: 'weight' },
                          { title: '操作', render: (_, r) => (
                            <Space>
                              <Select size="small" value={r.level} style={{ width: 110 }}
                                onChange={(v) => handleCritSet(type, r.key, v)}
                                options={['low', 'medium', 'high', 'extreme'].map((l) => ({ value: l, label: l }))} />
                              <Button size="small" danger onClick={() => handleCritDelete(type, r.key)}>删</Button>
                            </Space>
                          )},
                        ]} />,
                }))} />
              </Card>
            )}

            {/* ===== 通知中心 ===== */}
            {activeKey === 'notify' && (
              <Card title={<>通知中心 {unreadCount > 0 && <Tag color="gold">🔔 {unreadCount} 条未读</Tag>}</>}
                extra={<Button size="small" onClick={handleReadAllNotifs}>全部已读</Button>}>
                {notifs.length === 0 && <Empty description="暂无通知(规则部署 / 实体风险重算 / 数据源健康异常会在此提示)" />}
                <Space direction="vertical" style={{ width: '100%' }}>
                  {notifs.slice().reverse().map((n) => (
                    <div key={n.id} style={{
                      display: 'flex', justifyContent: 'space-between', alignItems: 'center',
                      padding: '10px 14px', borderRadius: 8, border: '1px solid #f0f0f0',
                      background: n.read ? '#fafafa' : '#fffbe6',
                    }}>
                      <div>
                        <Tag color="blue">{n.type}</Tag> {n.message}
                        <div style={{ fontSize: 11, color: '#999' }}>{n.timestamp}</div>
                      </div>
                      <Space>
                        {!n.read && <Button size="small" onClick={() => handleReadNotif(n.id)}>已读</Button>}
                        <Button size="small" danger onClick={() => handleDelNotif(n.id)}>删</Button>
                      </Space>
                    </div>
                  ))}
                </Space>
              </Card>
            )}

            {/* ===== 用户与权限 ===== */}
            {activeKey === 'rbac' && (
              <Card title="用户与权限(RBAC,admin 可见)">
                {!user || user.role !== 'admin' ? (
                  <Alert type="info" message="需以 admin 登录后查看/管理用户、角色与审计日志。" />
                ) : (
                  <Tabs items={[
                    {
                      key: 'users', label: '用户管理',
                      children: (
                        <Space direction="vertical" size="middle" style={{ width: '100%' }}>
                          <Space wrap>
                            <Input style={{ width: 140 }} value={newUname} onChange={(e) => setNewUname(e.target.value)} placeholder="新用户名" />
                            <Input.Password style={{ width: 140 }} value={newPass} onChange={(e) => setNewPass(e.target.value)} placeholder="密码(≥6位)" />
                            <Select value={newRole} style={{ width: 110 }} onChange={setNewRole}
                              options={['analyst', 'ops', 'audit', 'admin'].map((r) => ({ value: r, label: r }))} />
                            <Button type="primary" onClick={handleCreateUser}>新增用户</Button>
                          </Space>
                          <Table rowKey="username" size="small" dataSource={users}
                            columns={[
                              { title: '用户名', dataIndex: 'username' },
                              { title: '角色', dataIndex: 'role', render: (v) => <Tag color={v === 'admin' ? 'red' : 'blue'}>{v}</Tag> },
                              { title: '状态', dataIndex: 'status', render: (v) => <Tag color={v === 'active' ? 'green' : 'default'}>{v}</Tag> },
                              { title: '操作', render: (_, u) => (
                                <Space>
                                  <Select size="small" value={u.role} style={{ width: 110 }}
                                    onChange={(v) => handleRoleChange(u.username, v)}
                                    options={['analyst', 'ops', 'audit', 'admin'].map((r) => ({ value: r, label: r }))} />
                                  <Button size="small" danger onClick={() => handleDelUser(u.username)}>删</Button>
                                </Space>
                              )},
                            ]} />
                        </Space>
                      ),
                    },
                    {
                      key: 'roles', label: `角色矩阵(${roles.length})`,
                      children: <Table rowKey="name" size="small" pagination={false} dataSource={roles}
                        columns={[
                          { title: '角色', dataIndex: 'name', render: (v) => <Tag color="blue">{v}</Tag> },
                          { title: '权限', dataIndex: 'permissions', render: (p) => p.join(', ') },
                        ]} />,
                    },
                    {
                      key: 'audit', label: `审计日志(${audit.length})`,
                      children: (
                        <div style={{ maxHeight: 300, overflow: 'auto' }}>
                          {audit.slice().reverse().map((a, i) => (
                            <div key={i} style={{ fontSize: 12, padding: '3px 0' }}>
                              <code>{a.timestamp}</code> {a.action} → {a.target}
                            </div>
                          ))}
                        </div>
                      ),
                    },
                  ]} />
                )}
              </Card>
            )}
          </Content>
        </Layout>
      </Layout>
    </ConfigProvider>
  )
}

// ================= 子组件:接入向导 =================
function WizardView({ step, setStep, templates, selectedId, setSelectedId, selected, sample, setSample,
  testResult, handleTest, busy, name, setName, port, setPort, config, handlePreview,
  srcName, setSrcName, srcPort, setSrcPort, handleCreateSource, sources, activating, handleActivate,
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
              placeholder="-- 请选择(来自模板库) --"
              onChange={setSelectedId}
              optionFilterProp="label"
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
            <Input.TextArea rows={3} value={sample} onChange={(e) => setSample(e.target.value)}
              placeholder="粘贴一条日志样例,如:Aug 1 10:20:00 server03 sshd[9999]: Failed password for test from 172.16.1.20" />
            <Button type="primary" loading={busy} onClick={handleTest}>测试解析</Button>
            {testResult && (
              <div>
                <Tag color={testResult.ok ? 'green' : 'red'}>
                  {testResult.ok ? '✓ 解析成功' : '✗ 解析失败(未匹配任何 grok 模式)'}
                </Tag>
                {testResult.ok && (
                  <Table rowKey="k" size="small" pagination={false} style={{ marginTop: 8 }}
                    dataSource={Object.entries(testResult.fields).map(([k, v]) => ({ k, v: String(v) }))}
                    columns={[
                      { title: '字段', dataIndex: 'k', render: (v) => <code>{v}</code> },
                      { title: '值', dataIndex: 'v', render: (v) => <code>{v}</code> },
                    ]} />
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
              <div>
                <div style={{ marginBottom: 4, fontSize: 12, color: '#666' }}>数据源名称</div>
                <Input style={{ width: 200 }} value={name} onChange={(e) => setName(e.target.value)} placeholder="如 ssh-auth-web-01" />
              </div>
              <div>
                <div style={{ marginBottom: 4, fontSize: 12, color: '#666' }}>采集端口(tcp)</div>
                <Input style={{ width: 140 }} type="number" value={port} onChange={(e) => setPort(e.target.value)} />
              </div>
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
              <div>
                <div style={{ marginBottom: 4, fontSize: 12, color: '#666' }}>数据源名称</div>
                <Input style={{ width: 200 }} value={srcName} onChange={(e) => setSrcName(e.target.value)} placeholder="如 ssh-web-01" />
              </div>
              <div>
                <div style={{ marginBottom: 4, fontSize: 12, color: '#666' }}>端口(tcp)</div>
                <Input style={{ width: 140 }} type="number" value={srcPort} onChange={(e) => setSrcPort(e.target.value)} />
              </div>
            </Space>
            <Button type="primary" loading={busy} onClick={handleCreateSource}>创建数据源</Button>
            {sources.length > 0 && (
              <Table rowKey="id" size="small" dataSource={sources}
                pagination={{ pageSize: 5, showTotal: (t) => `共 ${t} 个数据源` }}
                columns={[
                  { title: 'ID', dataIndex: 'id', render: (v) => <code>{v}</code> },
                  { title: '名称', dataIndex: 'name' },
                  { title: '模板', dataIndex: 'templateId' },
                  { title: '端口', dataIndex: 'port' },
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

// ================= 子组件:调查台 =================
function CasesView({ cases, setCases, caseFilter, setCaseFilter, selAlerts, setSelAlerts, caseTitle, setCaseTitle,
  detailCase, setDetailCase, caseTimeline_, openCaseDetail, handleCreateCase, handleAggregate,
  handleInvestigateCase, handleResolveCase, handleAddToCase, handleRemoveFromCase, handleDeleteCase,
  caseOwner, setCaseOwner, evidenceTitle, setEvidenceTitle, evidenceUri, setEvidenceUri,
  handleUpdateCaseMetadata }) {

  return (
    <div>
      <Card style={{ marginBottom: 16 }}>
        <Space wrap>
          <Select style={{ width: 160 }} value={caseFilter || undefined} placeholder="全部状态" allowClear
            onChange={(v) => { setCaseFilter(v || ''); listCases(v || '').then(setCases).catch(() => {}) }}
            options={['open', 'investigating', 'resolved'].map((s) => ({ value: s, label: s }))} />
          <Button type="primary" onClick={handleAggregate}>触发一轮自动聚合</Button>
          <Button onClick={() => setSelAlerts(new Set())}>清空告警勾选</Button>
          <Input style={{ width: 220 }} value={caseTitle} onChange={(e) => setCaseTitle(e.target.value)} placeholder="案件标题(可选)" />
          <Button type="primary" onClick={handleCreateCase}>手动聚合勾选告警为案件</Button>
          <Typography.Text type="secondary">已勾选 {selAlerts.size} 条 open 告警</Typography.Text>
        </Space>
      </Card>

      <Card>
        {cases.length === 0 && <Empty description="暂无案件(同实体 ≥2 条 open 告警会自动聚合;或从告警台勾选手动建案)" />}
        <Space direction="vertical" size="middle" style={{ width: '100%' }}>
          {cases.map((c) => (
            <div key={c['case.id']} style={{
              border: '1px solid #f0f0f0', borderRadius: 8, padding: '12px 16px',
              display: 'flex', justifyContent: 'space-between', alignItems: 'center',
              background: c['case.status'] === 'resolved' ? '#fafafa' : '#fff',
            }}>
              <div>
                <Space>
                  <strong>{c['case.title']}</strong>
                  <code style={{ fontSize: 11, color: '#999' }}>{c['case.id']}</code>
                  <Tag color={c['case.status'] === 'resolved' ? 'blue' : c['case.status'] === 'investigating' ? 'gold' : 'red'}>{c['case.status']}</Tag>
                  <Tag>{c['case.aggregation'] === 'auto' ? '自动聚合' : '手动聚合'}</Tag>
                </Space>
                <div style={{ marginTop: 4, fontSize: 12, color: '#888' }}>
                  {c['alert_ids']?.length} 告警 · 负责人 {c['case.owner'] || '-'} · 更新 {c['case.updated_at']}
                </div>
              </div>
              <Space>
                <Button size="small" onClick={() => openCaseDetail(c['case.id'])}>详情</Button>
                {c['case.status'] === 'open' && <Button size="small" onClick={() => handleInvestigateCase(c['case.id'])}>接手</Button>}
                <Button size="small" danger onClick={() => handleDeleteCase(c['case.id'])}>删</Button>
              </Space>
            </div>
          ))}
        </Space>
      </Card>

      {/* 案件详情:Modal 弹窗(点击「详情」即弹出,无需滚动) */}
      <Modal
        open={!!detailCase}
        title={
          <Space>
            <strong>{detailCase?.['case.title']}</strong>
            <Tag color={detailCase?.['case.status'] === 'resolved' ? 'blue' : detailCase?.['case.status'] === 'investigating' ? 'gold' : 'red'}>{detailCase?.['case.status']}</Tag>
            {detailCase?.['case.verdict'] && <Tag color="green">{detailCase['case.verdict']}</Tag>}
          </Space>
        }
        onCancel={() => setDetailCase(null)}
        footer={null}
        width={860}
      >
        {detailCase && (
          <>
            <Descriptions size="small" column={3} bordered style={{ marginBottom: 12 }}>
              <Descriptions.Item label="案件 ID"><code>{detailCase['case.id']}</code></Descriptions.Item>
              <Descriptions.Item label="聚合来源">{detailCase['case.aggregation']}</Descriptions.Item>
              <Descriptions.Item label="操作者">{detailCase['case.operator']}</Descriptions.Item>
              <Descriptions.Item label="负责人">{detailCase['case.owner'] || '—'}</Descriptions.Item>
              <Descriptions.Item label="实体">{(detailCase['entities'] || []).map((e) => <Tag key={e.type + e.value}>{e.type}:{e.value}</Tag>)}</Descriptions.Item>
              <Descriptions.Item label="创建时间">{detailCase['case.created_at']}</Descriptions.Item>
              <Descriptions.Item label="结案时间">{detailCase['case.closed_at'] || '—'}</Descriptions.Item>
            </Descriptions>

            <Space wrap style={{ marginBottom: 12 }}>
              {detailCase['case.status'] === 'open' && <Button type="primary" onClick={() => handleInvestigateCase(detailCase['case.id'])}>接手调查</Button>}
              {detailCase['case.status'] === 'investigating' && (
                <Select placeholder="结案选 verdict…" style={{ width: 200 }} onChange={(v) => handleResolveCase(detailCase['case.id'], v)}
                  options={['true_positive', 'false_positive', 'duplicate'].map((v) => ({ value: v, label: v }))} />
              )}
              <Button onClick={() => handleAddToCase(detailCase['case.id'])}>追加勾选告警</Button>
            </Space>

            <Divider style={{ margin: '12px 0' }}>处置负责人和证据</Divider>
            <Space wrap>
              <Input style={{ width: 180 }} value={caseOwner} onChange={(e) => setCaseOwner(e.target.value)} placeholder="负责人用户名" />
              <Input style={{ width: 180 }} value={evidenceTitle} onChange={(e) => setEvidenceTitle(e.target.value)} placeholder="证据标题" />
              <Input style={{ width: 260 }} value={evidenceUri} onChange={(e) => setEvidenceUri(e.target.value)} placeholder="证据 URI/链接" />
              <Button type="primary" onClick={() => handleUpdateCaseMetadata(detailCase['case.id'])}>保存</Button>
            </Space>
            {(detailCase.evidence || []).length > 0 && (
              <div style={{ marginTop: 8, fontSize: 12 }}>
                {(detailCase.evidence || []).map((e, i) => <div key={i}><Tag color="blue">{e.type || 'evidence'}</Tag>{e.title || '未命名'} {e.uri && <code>{e.uri}</code>}</div>)}
              </div>
            )}

            <Divider style={{ margin: '12px 0' }}>案内告警({(detailCase['alert_ids'] || []).length})</Divider>
            <Space wrap>
              {(detailCase['alert_ids'] || []).map((id) => (
                <Tag key={id} closable onClose={() => handleRemoveFromCase(detailCase['case.id'], id)} color="blue">
                  <code>{id.slice(0, 12)}</code>
                </Tag>
              ))}
            </Space>

            <Divider style={{ margin: '16px 0' }}>关联事件时间线(实时查 siem-events,近 24h)</Divider>
            {caseTimeline_.length === 0
              ? <Empty description="近 24h 无关联事件(历史案件的事件可能已过期)" image={Empty.PRESENTED_IMAGE_SIMPLE} />
              : <Table rowKey={(_, i) => i} size="small" pagination={{ pageSize: 10 }} dataSource={caseTimeline_}
                  columns={[
                    { title: '时间', dataIndex: '@timestamp', width: 200 },
                    { title: 'action', dataIndex: 'event.action', render: (v) => <Tag color="blue">{v}</Tag> },
                    { title: 'message', dataIndex: 'message', ellipsis: true },
                  ]} />}
          </>
        )}
      </Modal>
    </div>
  )
}

// ================= 用户头像 =================
function AvatarUser({ username, role }) {
  const colors = { admin: '#f5222d', analyst: '#1677ff', ops: '#52c41a', audit: '#722ed1' }
  return (
    <Space size="small">
      <div style={{
        width: 28, height: 28, borderRadius: '50%', background: colors[role] || '#1677ff',
        color: '#fff', display: 'flex', alignItems: 'center', justifyContent: 'center',
        fontSize: 13, fontWeight: 600,
      }}>
        {username[0]?.toUpperCase()}
      </div>
      <span>{username} · {role}</span>
    </Space>
  )
}
