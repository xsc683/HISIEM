import { lazy, Suspense, useEffect, useState } from 'react'
import { ConfigProvider, Layout, Menu, Button, Input, Card, Space, Badge, Alert, Typography, Modal, message, theme } from 'antd'
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
  login, logout, authMe, changePassword, listUsers, createUser, deleteUser, updateUserRole, listRoles, auditLogs,
  listNotifications, readNotification, readAllNotifications, deleteNotification,
  listAlerts, getAlert, updateAlertStatus, updateAlertVerdict,
  batchAlertStatus, batchAlertVerdict, fpRate,
  listCases, getCase, createCase, addCaseAlerts, removeCaseAlert,
  updateCaseStatus, caseTimeline, deleteCase, aggregateCasesWithOptions,
  updateCaseMetadata, updateCaseCollaborators, healthScan, listTasks,
} from './api.js'
import { pathFromRouteKey, routeKeyFromPath } from './routes.js'
import { AvatarUser, formatPlatformTime, LOCAL_TIME_LABEL } from './components/common.jsx'

const { Header, Sider, Content } = Layout

const WizardView = lazy(() => import('./pages/WizardView.jsx'))
const RulesView = lazy(() => import('./pages/RulesView.jsx'))
const AlertsView = lazy(() => import('./pages/AlertsView.jsx'))
const CasesView = lazy(() => import('./pages/CasesView.jsx'))
const HealthView = lazy(() => import('./pages/HealthView.jsx'))
const OpsHealthView = lazy(() => import('./pages/OpsHealthView.jsx'))
const CriticalityView = lazy(() => import('./pages/CriticalityView.jsx'))
const NotificationsView = lazy(() => import('./pages/NotificationsView.jsx'))
const RbacView = lazy(() => import('./pages/RbacView.jsx'))

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
  const [protocol, setProtocol] = useState('tcp')
  const [sourcePath, setSourcePath] = useState('')
  const [config, setConfig] = useState('')
  const [busy, setBusy] = useState(false)

  const [sources, setSources] = useState([])
  const [srcName, setSrcName] = useState('')
  const [srcPort, setSrcPort] = useState(5001)
  const [srcProtocol, setSrcProtocol] = useState('tcp')
  const [srcPath, setSrcPath] = useState('')
  const [activating, setActivating] = useState({})

  const [detRules, setDetRules] = useState([])
  const [ruleHits, setRuleHits] = useState({})
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
  const [passwordModalOpen, setPasswordModalOpen] = useState(false)
  const [currentPassword, setCurrentPassword] = useState('')
  const [newPassword, setNewPassword] = useState('')
  const [confirmPassword, setConfirmPassword] = useState('')
  const [passwordSaving, setPasswordSaving] = useState(false)
  const [loadErrors, setLoadErrors] = useState([])
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
  const [caseAlertDetails, setCaseAlertDetails] = useState({})
  const [caseTitle, setCaseTitle] = useState('')
  const [caseWindow, setCaseWindow] = useState(30)
  const [caseThreshold, setCaseThreshold] = useState(2)
  const [caseGroupByRule, setCaseGroupByRule] = useState(false)
  const [caseTimeline_, setCaseTimeline_] = useState([])
  const [opsHealth, setOpsHealth] = useState(null)
  const [tasks, setTasks] = useState([])
  const [caseOwner, setCaseOwner] = useState('')
  const [evidenceTitle, setEvidenceTitle] = useState('')
  const [evidenceUri, setEvidenceUri] = useState('')
  const [caseCollaborators, setCaseCollaborators] = useState('')

  const [activeKey, setActiveKey] = useState(() => routeKeyFromPath())
  const [collapsed, setCollapsed] = useState(false)
  const [wizardStep, setWizardStep] = useState(0)
  const [now, setNow] = useState(() => new Date())

  useEffect(() => {
    const timer = window.setInterval(() => setNow(new Date()), 1000)
    return () => window.clearInterval(timer)
  }, [])

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
    authMe().then(setUser).catch(() => setUser(null))
  }, [])

  useEffect(() => {
    if (!user || user.passwordChangeRequired) return
    setLoadErrors([])
    const failed = []
    const report = (name) => (error) => {
      failed.push(`${name}: ${error.message}`)
      setLoadErrors([...failed])
    }
    listAlerts(alertFilter).then(setAlerts).catch(report('告警'))
    fpRate().then(setFpRates).catch(report('FP 率'))
    listCases(caseFilter).then(setCases).catch(report('案件'))
    listTemplates().then(setTemplates).catch(report('解析模板'))
    listLogSources().then(setSources).catch(report('数据源'))
    listDetectionRules().then(async (rows) => {
      setDetRules(rows)
      const counts = await Promise.all(rows.map((rule) =>
        getRuleHits(rule.id).then((result) => [rule.id, result.count]).catch(() => [rule.id, null])))
      setRuleHits(Object.fromEntries(counts))
    }).catch(report('检测规则'))
    ruleMitre().then(setMitre).catch(report('MITRE'))
    dataHealthSources().then(setHealth).catch(report('数据健康'))
    listCriticality().then(setCrit).catch(report('资产关键度'))
  }, [user])

  useEffect(() => {
    if (user?.passwordChangeRequired) setPasswordModalOpen(true)
  }, [user?.passwordChangeRequired])

  useEffect(() => {
    if (!user || user.passwordChangeRequired || activeKey !== 'ops-health') return undefined
    let disposed = false
    let inFlight = false
    const load = async () => {
      if (disposed || inFlight) return
      inFlight = true
      try {
        const [healthResult, tasksResult] = await Promise.allSettled([
          healthScan(), listTasks(50),
        ])
        if (disposed) return
        if (healthResult.status === 'fulfilled') setOpsHealth(healthResult.value)
        else message.error(`健康扫描失败: ${healthResult.reason?.message || '未知错误'}`)
        if (tasksResult.status === 'fulfilled') setTasks(tasksResult.value)
        else message.error(`任务列表加载失败: ${tasksResult.reason?.message || '未知错误'}`)
      } finally {
        inFlight = false
      }
    }
    load()
    const timer = window.setInterval(load, 10000)
    return () => { disposed = true; window.clearInterval(timer) }
  }, [user, activeKey])

  useEffect(() => {
    if (user && !user.passwordChangeRequired && user.role === 'admin') {
      listUsers().then(setUsers).catch((e) => message.error(`用户列表加载失败: ${e.message}`))
      listRoles().then(setRoles).catch((e) => message.error(`角色列表加载失败: ${e.message}`))
      auditLogs().then(setAudit).catch((e) => message.error(`审计日志加载失败: ${e.message}`))
    } else {
      setUsers([]); setRoles([]); setAudit([])
    }
  }, [user])

  useEffect(() => {
    if (!user || user.passwordChangeRequired) {
      setNotifs([])
      return undefined
    }
    let disposed = false
    let inFlight = false
    const loadNotifs = async () => {
      if (disposed || inFlight) return
      inFlight = true
      try {
        setNotifs(await listNotifications())
      } catch (e) {
        if (!disposed) message.error(`通知加载失败: ${e.message}`)
      } finally {
        inFlight = false
      }
    }
    loadNotifs()
    const timer = setInterval(loadNotifs, 20000)
    return () => { disposed = true; clearInterval(timer) }
  }, [user])

  async function handleLogin() {
    try {
      const r = await login(loginUser.trim(), loginPass)
      setUser({ username: r.username, role: r.role, passwordChangeRequired: r.passwordChangeRequired })
      setLoginPass('')
      message.success(r.passwordChangeRequired ? '登录成功，请先修改初始密码' : '登录成功')
    } catch (e) { message.error(e.message) }
  }

  async function handleChangePassword() {
    if (newPassword.length < 12) { message.warning('新密码至少 12 位'); return }
    if (newPassword !== confirmPassword) { message.warning('两次新密码不一致'); return }
    setPasswordSaving(true)
    try {
      await changePassword(currentPassword, newPassword)
      setUser((prev) => ({ ...prev, passwordChangeRequired: false }))
      setPasswordModalOpen(false)
      setCurrentPassword(''); setNewPassword(''); setConfirmPassword('')
      message.success('密码已更新')
    } catch (e) { message.error(e.message) } finally { setPasswordSaving(false) }
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
    if (!window.confirm(`确定删除用户 ${username}？`)) return
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
    if (!window.confirm(`确定删除 ${type}/${key} 的关键度配置？`)) return
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
    setRecalcMsg('重算任务已排队…')
    try {
      const r = await recalcCriticality()
      setRecalcMsg('任务 ' + r.taskId + ' 已排队，可在运行态扫描查看状态')
      message.success('实体风险重算已排队')
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
    if (new TextEncoder().encode(sample).length > 8 * 1024) {
      message.warning('样例日志不能超过 8 KiB')
      return
    }
    setBusy(true)
    try {
      setTestResult(await testParse(selectedId, sample.trim()))
    } catch (e) { message.error(e.message) } finally { setBusy(false) }
  }

  async function handlePreview() {
    if (!selectedId) { message.warning('先选模板'); return }
    setBusy(true)
    try {
      const r = await previewLogSource({ name, protocol, templateId: selectedId,
        port: protocol === 'file' ? 0 : Number(port), path: protocol === 'file' ? sourcePath : null })
      setConfig(`# 数据源:${name || '未命名'} (${r.template})\ninput {\n  ${r.input}\n}\n\nfilter {\n${r.config}}`)
    } catch (e) { message.error(e.message) } finally { setBusy(false) }
  }

  async function handleCreateSource() {
    if (!selectedId) { message.warning('先选模板'); return }
    if (!srcName.trim()) { message.warning('填数据源名称'); return }
    setBusy(true)
    try {
      const s = await createLogSource({ name: srcName.trim(), protocol: srcProtocol, templateId: selectedId,
        port: srcProtocol === 'file' ? 0 : Number(srcPort), path: srcProtocol === 'file' ? srcPath : null })
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
    if (!window.confirm(`确定删除数据源 ${id}？仅停用或失败的数据源可以删除。`)) return
    try {
      await deleteLogSource(id)
      setSources((prev) => prev.filter((s) => s.id !== id))
      message.success(`数据源 ${id} 已删除`)
    } catch (e) { message.error(e.message) }
  }

  function pollSource(id, terminalStatuses = ['active', 'failed']) {
    let attempts = 0
    const poll = async () => {
      attempts += 1
      try {
        const s = await getLogSource(id)
        setSources((prev) => prev.map((x) => (x.id === id ? s : x)))
        if (terminalStatuses.includes(s.status)) {
          setActivating((prev) => ({ ...prev, [id]: false }))
          message.success(s.status === 'active' ? `数据源 ${id} 已生效` : s.status === 'stopped' ? `数据源 ${id} 已停用` : `数据源 ${id} 生效失败`)
          return
        }
        if (attempts >= 60) {
          setActivating((prev) => ({ ...prev, [id]: false }))
          message.warning(`数据源 ${id} 状态轮询超时，请到运行态任务查看结果`)
          return
        }
        const delay = Math.min(1000 * (2 ** Math.min(attempts - 1, 4)), 10000)
        window.setTimeout(poll, delay)
      } catch (e) {
        setActivating((prev) => ({ ...prev, [id]: false }))
        message.error(e.message)
      }
    }
    poll()
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
      if (detailAlert && detailAlert._id === id) {
        setDetailAlert(await getAlert(id).catch((e) => { message.error(`告警详情刷新失败: ${e.message}`); return null }))
      }
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

  async function reloadAlerts(status = alertFilter) {
    try { setAlerts(await listAlerts(status)) }
    catch (e) { message.error(`告警刷新失败: ${e.message}`) }
  }

  async function reloadCases() {
    try { setCases(await listCases(caseFilter)) }
    catch (e) { message.error(`案件刷新失败: ${e.message}`) }
  }

  async function handleCreateCase() {
    const ids = [...selAlerts]
    if (ids.length < 2) { message.warning('至少勾选 2 条 open 告警'); return }
    try {
      const c = await createCase(ids, caseTitle || `案件 ${new Date().toISOString().slice(0, 10)}`)
      setSelAlerts(new Set())
      await reloadAlerts()
      await reloadCases()
      openCaseDetail(c['case.id'])
      message.success('案件已创建')
    } catch (e) { message.error(e.message) }
  }

  async function openCaseDetail(id) {
      const c = await getCase(id).catch((e) => { message.error(`案件详情加载失败: ${e.message}`); return null })
    setDetailCase(c)
    if (c) {
      setCaseOwner(c['case.owner'] || '')
      setCaseCollaborators((c['case.collaborators'] || []).join(', '))
      const alertIds = c['alert_ids'] || []
      const [timeline, linked] = await Promise.all([
        caseTimeline(id, 30).catch((e) => { message.error(`时间线加载失败: ${e.message}`); return [] }),
        Promise.all(alertIds.slice(0, 50).map((alertId) => getAlert(alertId).then((alert) => [alertId, alert]).catch(() => [alertId, null]))),
      ])
      setCaseTimeline_(timeline)
      setCaseAlertDetails((prev) => ({ ...prev, ...Object.fromEntries(linked.filter(([, alert]) => alert)) }))
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
      await reloadAlerts()
      await reloadCases()
      openCaseDetail(id)
      message.success('案件已结案,内部告警批量 closed')
    } catch (e) { message.error(e.message) }
  }

  async function handleUpdateCollaborators(id) {
    try {
      const updated = await updateCaseCollaborators(id, caseCollaborators.split(',').map((v) => v.trim()).filter(Boolean))
      setDetailCase(updated)
      message.success('协作负责人已保存')
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
      await reloadAlerts()
      openCaseDetail(id)
    } catch (e) { message.error(e.message) }
  }

  async function handleRemoveFromCase(id, alertId) {
    try {
      await removeCaseAlert(id, alertId)
      await reloadAlerts()
      openCaseDetail(id)
    } catch (e) { message.error(e.message) }
  }

  async function handleDeleteCase(id) {
    const current = cases.find((c) => c['case.id'] === id)
    if (current?.alert_ids?.length) {
      message.warning('案件仍包含告警，请先在详情中移出全部告警')
      return
    }
    if (!window.confirm(`确定删除案件 ${id}？此操作不可撤销。`)) return
    try {
      await deleteCase(id)
      setDetailCase(null)
      await reloadCases()
      message.success('案件已删除')
    } catch (e) { message.error(e.message) }
  }

  async function handleAggregate() {
    try {
      const r = await aggregateCasesWithOptions({
        windowMinutes: Number(caseWindow), threshold: Number(caseThreshold), groupByRule: caseGroupByRule,
      })
      await reloadCases()
      message.success(`自动聚合完成,新建 ${r.created} 个案件`)
    } catch (e) { message.error(e.message) }
  }

  async function handleReadNotif(id) {
    try {
      await readNotification(id)
      setNotifs(await listNotifications())
    } catch (e) { message.error(`通知更新失败: ${e.message}`) }
  }

  async function handleReadAllNotifs() {
    try {
      await readAllNotifications()
      setNotifs(await listNotifications())
    } catch (e) { message.error(`通知更新失败: ${e.message}`) }
  }

  async function handleDelNotif(id) {
    if (!window.confirm('确定删除这条通知？')) return
    try {
      await deleteNotification(id)
      setNotifs(await listNotifications())
    } catch (e) { message.error(e.message) }
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
              onChange={(e) => setLoginUser(e.target.value)} placeholder="用户名" />
            <Input.Password size="large" value={loginPass}
              onChange={(e) => setLoginPass(e.target.value)}
              onPressEnter={handleLogin}
              placeholder="密码" />
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              首次登录或管理员重置后，需要设置至少 12 位新密码。
            </Typography.Text>
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
              <Typography.Text type="secondary" style={{ fontSize: 12 }} title="Elasticsearch 中的事件时间和告警生成时间以 UTC ISO-8601 保存，页面按浏览器本地时区显示">
                时间：{LOCAL_TIME_LABEL} · {formatPlatformTime(now)}
              </Typography.Text>
              <Badge count={openAlertCount} size="small"><Button icon={<AlertOutlined />} onClick={() => navigate('alerts')}>告警</Button></Badge>
              <Badge count={unreadCount} size="small"><Button icon={<BellOutlined />} onClick={() => navigate('notify')}>通知</Button></Badge>
              <Space>
                <AvatarUser username={user.username} role={user.role} />
                <Button icon={<SafetyCertificateOutlined />} onClick={() => setPasswordModalOpen(true)}>修改密码</Button>
                <Button icon={<LogoutOutlined />} onClick={handleLogout}>退出</Button>
              </Space>
            </Space>
          </Header>

          <Content style={{ padding: 20, background: '#f5f7fa' }}>
            {loadErrors.length > 0 && (
              <Alert
                type="warning"
                showIcon
                closable
                onClose={() => setLoadErrors([])}
                message="部分数据加载失败"
                description={loadErrors.join('；')}
                style={{ marginBottom: 16 }}
              />
            )}
            <Suspense fallback={<Card loading style={{ minHeight: 240 }} />}>
              {activeKey === 'wizard' && (
                <WizardView
                  step={wizardStep} setStep={setWizardStep}
                  templates={templates} selectedId={selectedId} setSelectedId={setSelectedId}
                  selected={selected} sample={sample} setSample={setSample}
                  testResult={testResult} handleTest={handleTest} busy={busy}
                  name={name} setName={setName} port={port} setPort={setPort}
                  protocol={protocol} setProtocol={setProtocol} sourcePath={sourcePath} setSourcePath={setSourcePath}
                  config={config} handlePreview={handlePreview}
                  srcName={srcName} setSrcName={setSrcName} srcPort={srcPort} setSrcPort={setSrcPort}
                  srcProtocol={srcProtocol} setSrcProtocol={setSrcProtocol} srcPath={srcPath} setSrcPath={setSrcPath}
                  handleCreateSource={handleCreateSource}
                  sources={sources} activating={activating} handleActivate={handleActivate}
                  handleDeactivate={handleDeactivate} handleDeleteSource={handleDeleteSource}
                />
              )}
              {activeKey === 'rules' && <RulesView detRules={detRules} ruleHits={ruleHits} deploying={deploying} deployMsg={deployMsg} mitre={mitre} handleDeployRules={handleDeployRules} handleToggleRule={handleToggleRule} />}
              {activeKey === 'alerts' && <AlertsView alerts={alerts} alertFilter={alertFilter} setAlertFilter={setAlertFilter} selAlerts={selAlerts} setSelAlerts={setSelAlerts} fpRates={fpRates} handleCreateCase={handleCreateCase} handleBatchStatus={handleBatchStatus} handleBatchVerdict={handleBatchVerdict} handleAlertStatus={handleAlertStatus} handleAlertVerdict={handleAlertVerdict} reloadAlerts={reloadAlerts} />}
              {activeKey === 'cases' && (
                <CasesView
                  cases={cases} setCases={setCases} alerts={alerts} caseAlertDetails={caseAlertDetails} caseFilter={caseFilter} setCaseFilter={setCaseFilter}
                  selAlerts={selAlerts} setSelAlerts={setSelAlerts} caseTitle={caseTitle} setCaseTitle={setCaseTitle}
                  caseWindow={caseWindow} setCaseWindow={setCaseWindow} caseThreshold={caseThreshold} setCaseThreshold={setCaseThreshold}
                  caseGroupByRule={caseGroupByRule} setCaseGroupByRule={setCaseGroupByRule}
                  detailCase={detailCase} setDetailCase={setDetailCase} caseTimeline_={caseTimeline_} openCaseDetail={openCaseDetail}
                  handleCreateCase={handleCreateCase} handleAggregate={handleAggregate}
                  handleInvestigateCase={handleInvestigateCase} handleResolveCase={handleResolveCase}
                  handleAddToCase={handleAddToCase} handleRemoveFromCase={handleRemoveFromCase}
                  handleDeleteCase={handleDeleteCase} caseOwner={caseOwner} setCaseOwner={setCaseOwner}
                  evidenceTitle={evidenceTitle} setEvidenceTitle={setEvidenceTitle}
                  evidenceUri={evidenceUri} setEvidenceUri={setEvidenceUri}
                  handleUpdateCaseMetadata={handleUpdateCaseMetadata} caseCollaborators={caseCollaborators}
                  setCaseCollaborators={setCaseCollaborators} handleUpdateCollaborators={handleUpdateCollaborators}
                />
              )}
              {activeKey === 'health' && <HealthView health={health} sources={sources} healthDetail={healthDetail} healthLoading={healthLoading} handleHealthDetail={handleHealthDetail} />}
              {activeKey === 'ops-health' && <OpsHealthView opsHealth={opsHealth} tasks={tasks} healthScan={healthScan} listTasks={listTasks} setOpsHealth={setOpsHealth} setTasks={setTasks} />}
              {activeKey === 'criticality' && <CriticalityView crit={crit} critType={critType} setCritType={setCritType} critKey={critKey} setCritKey={setCritKey} critLevel={critLevel} setCritLevel={setCritLevel} recalcMsg={recalcMsg} handleCritAdd={handleCritAdd} handleRecalc={handleRecalc} handleCritSet={handleCritSet} handleCritDelete={handleCritDelete} />}
              {activeKey === 'notify' && <NotificationsView notifs={notifs} unreadCount={unreadCount} handleReadAllNotifs={handleReadAllNotifs} handleReadNotif={handleReadNotif} handleDelNotif={handleDelNotif} />}
              {activeKey === 'rbac' && <RbacView user={user} users={users} roles={roles} audit={audit} newUname={newUname} setNewUname={setNewUname} newPass={newPass} setNewPass={setNewPass} newRole={newRole} setNewRole={setNewRole} handleCreateUser={handleCreateUser} handleRoleChange={handleRoleChange} handleDelUser={handleDelUser} />}
            </Suspense>
          </Content>
          <Modal
            title="修改密码"
            open={passwordModalOpen}
            onOk={handleChangePassword}
            confirmLoading={passwordSaving}
            onCancel={() => !user.passwordChangeRequired && setPasswordModalOpen(false)}
            closable={!user.passwordChangeRequired}
            maskClosable={!user.passwordChangeRequired}
            cancelButtonProps={{ disabled: user.passwordChangeRequired }}
            okText="保存新密码"
            cancelText="稍后"
          >
            {user.passwordChangeRequired && (
              <Alert type="warning" showIcon message="请先完成初始密码轮换" style={{ marginBottom: 12 }} />
            )}
            <Space direction="vertical" style={{ width: '100%' }}>
              <Input.Password value={currentPassword} onChange={(e) => setCurrentPassword(e.target.value)} placeholder="当前密码" />
              <Input.Password value={newPassword} onChange={(e) => setNewPassword(e.target.value)} placeholder="新密码（至少 12 位）" />
              <Input.Password value={confirmPassword} onChange={(e) => setConfirmPassword(e.target.value)} placeholder="再次输入新密码" />
            </Space>
          </Modal>
        </Layout>
      </Layout>
    </ConfigProvider>
  )
}
