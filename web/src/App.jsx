import { useEffect, useState } from 'react'
import {
  listTemplates, testParse, previewLogSource,
  listLogSources, createLogSource, activateLogSource, getLogSource,
  listDetectionRules, toggleRule, deployRules, ruleMitre,
  dataHealthSources, dataHealthTrend, dataHealthFailures,
  listCriticality, setCriticality, deleteCriticality, recalcCriticality,
  login, logout, authMe, listUsers, createUser, deleteUser, updateUserRole, listRoles, auditLogs,
  listNotifications, readNotification, readAllNotifications, deleteNotification,
  listAlerts, getAlert, updateAlertStatus, updateAlertVerdict,
  batchAlertStatus, batchAlertVerdict, fpRate,
  listCases, getCase, createCase, addCaseAlerts, removeCaseAlert,
  updateCaseStatus, caseTimeline, deleteCase, aggregateCases,
} from './api.js'

const styles = {
  root: { maxWidth: 920, margin: '0 auto', padding: 24, fontFamily: 'system-ui, sans-serif' },
  section: { marginBottom: 28, padding: 16, border: '1px solid #ddd', borderRadius: 8 },
  h2: { marginTop: 0, fontSize: 18 },
  label: { display: 'block', marginBottom: 6, color: '#555' },
  input: { padding: 6, marginRight: 8, border: '1px solid #ccc', borderRadius: 4 },
  textarea: { width: '100%', padding: 8, marginBottom: 8, border: '1px solid #ccc', borderRadius: 4, fontFamily: 'monospace' },
  button: { padding: '6px 14px', cursor: 'pointer', borderRadius: 4, border: '1px solid #888', background: '#f0f0f0' },
  ok: { color: 'green' }, bad: { color: 'red' },
  table: { borderCollapse: 'collapse', width: '100%', fontSize: 13 },
  pre: { background: '#f5f5f5', padding: 12, borderRadius: 4, whiteSpace: 'pre-wrap', fontFamily: 'monospace', fontSize: 13 },
}

export default function App() {
  const [templates, setTemplates] = useState([])
  const [selectedId, setSelectedId] = useState('')
  const [sample, setSample] = useState('')
  const [testResult, setTestResult] = useState(null)
  const [name, setName] = useState('')
  const [port, setPort] = useState(5001)
  const [config, setConfig] = useState('')
  const [busy, setBusy] = useState(false)

  // 数据源生命周期(Story 01)
  const [sources, setSources] = useState([])
  const [srcName, setSrcName] = useState('')
  const [srcPort, setSrcPort] = useState(5001)
  const [activating, setActivating] = useState({})

  // 检测规则管理(Story 03)
  const [detRules, setDetRules] = useState([])
  const [deploying, setDeploying] = useState(false)
  const [deployMsg, setDeployMsg] = useState('')
  const [mitre, setMitre] = useState({})

  // 数据健康(Story 05)
  const [health, setHealth] = useState([])
  const [healthDetail, setHealthDetail] = useState(null)
  const [healthLoading, setHealthLoading] = useState(false)

  // 设置·资产关键度(Story 06)
  const [crit, setCrit] = useState({})
  const [critType, setCritType] = useState('ip')
  const [critKey, setCritKey] = useState('')
  const [critLevel, setCritLevel] = useState('high')
  const [recalcMsg, setRecalcMsg] = useState('')

  // 认证与权限(Story 08)
  const [user, setUser] = useState(null)
  const [loginUser, setLoginUser] = useState('')
  const [loginPass, setLoginPass] = useState('')
  const [users, setUsers] = useState([])
  const [roles, setRoles] = useState([])
  const [audit, setAudit] = useState([])
  const [newUname, setNewUname] = useState('')
  const [newPass, setNewPass] = useState('')
  const [newRole, setNewRole] = useState('analyst')

  // 通知中心(Story 10)
  const [notifs, setNotifs] = useState([])

  // 告警台(Story 04)
  const [alerts, setAlerts] = useState([])
  const [alertFilter, setAlertFilter] = useState('open')
  const [selAlerts, setSelAlerts] = useState(new Set())
  const [detailAlert, setDetailAlert] = useState(null)
  const [fpRates, setFpRates] = useState([])

  // 调查台·案件聚合(Story 07)
  const [cases, setCases] = useState([])
  const [caseFilter, setCaseFilter] = useState('')
  const [selCaseAlerts, setSelCaseAlerts] = useState(new Set())
  const [detailCase, setDetailCase] = useState(null)
  const [caseTitle, setCaseTitle] = useState('')
  const [caseTimeline_, setCaseTimeline_] = useState([])

  useEffect(() => {
    listAlerts(alertFilter).then(setAlerts).catch(() => {})
    fpRate().then(setFpRates).catch(() => {})
    listCases(caseFilter).then(setCases).catch(() => {})
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  async function reloadAlerts() {
    setAlerts(await listAlerts(alertFilter).catch(() => []))
  }

  async function reloadCases() {
    setCases(await listCases(caseFilter).catch(() => []))
  }

  async function handleCreateCase() {
    const ids = [...selAlerts]
    if (ids.length < 2) { alert('至少勾选 2 条 open 告警'); return }
    try {
      const c = await createCase(ids, caseTitle || `案件 ${new Date().toISOString().slice(0, 10)}`)
      setSelAlerts(new Set())
      await reloadCases()
      openCaseDetail(c['case.id'])
    } catch (e) { alert(e.message) }
  }

  async function openCaseDetail(id) {
    const c = await getCase(id).catch(() => null)
    setDetailCase(c)
    if (c) setCaseTimeline_(await caseTimeline(id, 30).catch(() => []))
  }

  async function handleResolveCase(id, verdict) {
    if (!verdict) { alert('结案必选 verdict'); return }
    try {
      await updateCaseStatus(id, 'resolved', verdict)
      await reloadCases()
      openCaseDetail(id)
    } catch (e) { alert(e.message) }
  }

  async function handleInvestigateCase(id) {
    try {
      await updateCaseStatus(id, 'investigating', null)
      await reloadCases()
      openCaseDetail(id)
    } catch (e) { alert(e.message) }
  }

  async function handleAddToCase(id) {
    const ids = [...selAlerts]
    if (!ids.length) { alert('先勾选 open 告警'); return }
    try {
      await addCaseAlerts(id, ids)
      setSelAlerts(new Set())
      openCaseDetail(id)
    } catch (e) { alert(e.message) }
  }

  async function handleRemoveFromCase(id, alertId) {
    try {
      await removeCaseAlert(id, alertId)
      openCaseDetail(id)
    } catch (e) { alert(e.message) }
  }

  async function handleDeleteCase(id) {
    if (!confirm('删除案件?')) return
    try {
      await deleteCase(id)
      setDetailCase(null)
      await reloadCases()
    } catch (e) { alert(e.message) }
  }

  async function handleAggregate() {
    try {
      const r = await aggregateCases()
      await reloadCases()
      alert(`自动聚合完成,新建 ${r.created} 个案件`)
    } catch (e) { alert(e.message) }
  }

  function toggleSel(id) {
    setSelAlerts((prev) => {
      const next = new Set(prev)
      if (next.has(id)) next.delete(id); else next.add(id)
      return next
    })
  }

  async function handleAlertDetail(id) {
    setDetailAlert(await getAlert(id).catch((e) => { alert(e.message); return null }))
  }

  async function handleAlertStatus(id, status) {
    await updateAlertStatus(id, status).catch((e) => alert(e.message))
    setDetailAlert(null)
    reloadAlerts()
  }

  async function handleAlertVerdict(id, verdict) {
    await updateAlertVerdict(id, verdict).catch((e) => alert(e.message))
    if (detailAlert && detailAlert._id === id) setDetailAlert(await getAlert(id).catch(() => null))
    reloadAlerts()
  }

  async function handleBatchStatus(status) {
    if (selAlerts.size === 0) return alert('先勾选告警')
    if (status === 'closed' && !window.confirm('批量结案将要求已打 verdict,确认?')) return
    try {
      const r = await batchAlertStatus([...selAlerts], status)
      alert(`批量 ${status}:成功 ${r.succeeded}/${r.total}${r.failed.length ? `,失败 ${r.failed.join(',')}` : ''}`)
      setSelAlerts(new Set())
      reloadAlerts()
    } catch (e) { alert(e.message) }
  }

  async function handleBatchVerdict(verdict) {
    if (selAlerts.size === 0) return alert('先勾选告警')
    try {
      const r = await batchAlertVerdict([...selAlerts], verdict)
      alert(`批量 verdict ${verdict}:成功 ${r.succeeded}/${r.total}`)
      setSelAlerts(new Set())
      reloadAlerts()
    } catch (e) { alert(e.message) }
  }

  useEffect(() => {
    const loadNotifs = () => listNotifications().then(setNotifs).catch(() => {})
    loadNotifs()
    const timer = setInterval(loadNotifs, 20000)   // 20s 轮询(健康异常/部署通知)
    return () => clearInterval(timer)
  }, [])

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

  useEffect(() => {
    listTemplates().then(setTemplates).catch((e) => alert(e.message))
    listLogSources().then(setSources).catch(() => {})
    listDetectionRules().then(setDetRules).catch(() => {})
    ruleMitre().then(setMitre).catch(() => {})
    dataHealthSources().then(setHealth).catch(() => {})
    listCriticality().then(setCrit).catch(() => {})
    authMe().then(setUser).catch(() => setUser(null))
  }, [])

  useEffect(() => {
    if (user && user.role === 'admin') {
      listUsers().then(setUsers).catch(() => {})
      listRoles().then(setRoles).catch(() => {})
      auditLogs().then(setAudit).catch(() => {})
    } else {
      setUsers([]); setRoles([]); setAudit([])
    }
  }, [user])

  async function handleLogin() {
    try {
      const r = await login(loginUser.trim(), loginPass)
      setUser({ username: r.username, role: r.role })
      setLoginPass('')
    } catch (e) { alert(e.message) }
  }

  async function handleLogout() {
    await logout()
    setUser(null)
  }

  async function handleCreateUser() {
    if (!newUname.trim()) return alert('填用户名')
    try {
      await createUser({ username: newUname.trim(), password: newPass, role: newRole })
      setNewUname(''); setNewPass('')
      setUsers(await listUsers())
      setAudit(await auditLogs())
    } catch (e) { alert(e.message) }
  }

  async function handleDelUser(username) {
    try {
      await deleteUser(username)
      setUsers(await listUsers())
      setAudit(await auditLogs())
    } catch (e) { alert(e.message) }
  }

  async function handleRoleChange(username, role) {
    try {
      await updateUserRole(username, role)
      setUsers(await listUsers())
      setAudit(await auditLogs())
    } catch (e) { alert(e.message) }
  }

  async function handleCritSet(type, key, level) {
    try {
      await setCriticality(type, key, level)
      setCrit(await listCriticality())
    } catch (e) { alert(e.message) }
  }

  async function handleCritDelete(type, key) {
    try {
      await deleteCriticality(type, key)
      setCrit(await listCriticality())
    } catch (e) { alert(e.message) }
  }

  async function handleCritAdd() {
    if (!critKey.trim()) return alert('填资产键(IP/用户名/主机名)')
    try {
      await setCriticality(critType, critKey.trim(), critLevel)
      setCritKey('')
      setCrit(await listCriticality())
    } catch (e) { alert(e.message) }
  }

  async function handleRecalc() {
    setRecalcMsg('重算中(约数秒)…')
    try {
      const r = await recalcCriticality()
      setRecalcMsg(`实体风险已重算:${r.output.split('\n').filter((l) => l.trim()).slice(-3).join(' / ')}`)
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
      alert(`加载健康详情失败: ${e.message}`)
    } finally { setHealthLoading(false) }
  }

  async function handleToggleRule(id) {
    try {
      const updated = await toggleRule(id)
      setDetRules((prev) => prev.map((r) => (r.id === id ? { ...r, enabled: updated.enabled } : r)))
      alert(`规则 ${id} → ${updated.enabled ? '已启用' : '已停用'}(写回 infra/rules,点「部署生效」后重启检测 job 才生效)`)
    } catch (e) { alert(e.message) }
  }

  async function handleDeployRules() {
    setDeploying(true)
    setDeployMsg('')
    try {
      const r = await deployRules()
      setDeployMsg(`部署完成:jobId=${r.jobId},enabled 变更已生效`)
    } catch (e) {
      setDeployMsg(`部署失败: ${e.message}`)
    } finally { setDeploying(false) }
  }

  const selected = templates.find((t) => t.id === selectedId)

  async function handleTest() {
    if (!selectedId || !sample.trim()) return alert('先选模板并粘贴样例日志')
    setBusy(true)
    try {
      setTestResult(await testParse(selectedId, sample.trim()))
    } catch (e) { alert(e.message) } finally { setBusy(false) }
  }

  async function handlePreview() {
    if (!selectedId) return alert('先选模板')
    setBusy(true)
    try {
      const r = await previewLogSource({ name, protocol: 'tcp', templateId: selectedId, port: Number(port) })
      setConfig(`# 数据源:${name || '未命名'} (${r.template})\ninput {\n  ${r.input}\n}\n\nfilter {\n${r.config}}`)
    } catch (e) { alert(e.message) } finally { setBusy(false) }
  }

  async function handleCreateSource() {
    if (!selectedId) return alert('先选模板')
    if (!srcName.trim()) return alert('填数据源名称')
    setBusy(true)
    try {
      const s = await createLogSource({ name: srcName.trim(), protocol: 'tcp', templateId: selectedId, port: Number(srcPort) })
      setSources([...sources, s])
      setSrcName('')
      alert(`数据源 ${s.id} 已创建(状态 ${s.status}),点「生效」接入`)
    } catch (e) { alert(e.message) } finally { setBusy(false) }
  }

  function handleActivate(id) {
    setActivating((prev) => ({ ...prev, [id]: true }))
    activateLogSource(id)
      .then(() => pollSource(id))
      .catch((e) => { setActivating((prev) => ({ ...prev, [id]: false })); alert(e.message) })
  }

  function pollSource(id) {
    const timer = setInterval(async () => {
      try {
        const s = await getLogSource(id)
        setSources((prev) => prev.map((x) => (x.id === id ? s : x)))
        if (s.status === 'active' || s.status === 'failed') {
          clearInterval(timer)
          setActivating((prev) => ({ ...prev, [id]: false }))
        }
      } catch {
        clearInterval(timer)
        setActivating((prev) => ({ ...prev, [id]: false }))
      }
    }, 2000)
  }

  return (
    <div style={styles.root}>
      <h1>HISIEM · 日志接入</h1>

      <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end', marginBottom: 12, fontSize: 13 }}>
        {user ? (
          <>
            <span>👤 {user.username}({user.role})</span>
            <button style={styles.button} onClick={handleLogout}>退出</button>
          </>
        ) : (
          <>
            <input style={styles.input} value={loginUser} onChange={(e) => setLoginUser(e.target.value)} placeholder="用户名(默认 admin)" />
            <input style={styles.input} type="password" value={loginPass} onChange={(e) => setLoginPass(e.target.value)} placeholder="密码(admin123)" />
            <button style={styles.button} onClick={handleLogin}>登录</button>
          </>
        )}
      </div>

      <section style={styles.section}>
        <h2 style={styles.h2}>① 选择解析模板</h2>
        <select value={selectedId} onChange={(e) => setSelectedId(e.target.value)}
          style={{ ...styles.input, padding: '6px 10px' }}>
          <option value="">-- 请选择(来自模板库) --</option>
          {templates.map((t) => (
            <option key={t.id} value={t.id}>{t.name} ({t.id})</option>
          ))}
        </select>
        {selected && <p style={{ color: '#555' }}>{selected.description} · 协议 {selected.protocol}</p>}
      </section>

      <section style={styles.section}>
        <h2 style={styles.h2}>② 解析测试(粘贴样例日志)</h2>
        <textarea style={styles.textarea} rows={3} value={sample} onChange={(e) => setSample(e.target.value)}
          placeholder="粘贴一条日志样例，如：Aug 1 10:20:00 server03 sshd[9999]: Failed password for test from 172.16.1.20" />
        <button style={styles.button} onClick={handleTest} disabled={busy}>测试解析</button>
        {testResult && (
          <div style={{ marginTop: 10 }}>
            <p style={testResult.ok ? styles.ok : styles.bad}>
              {testResult.ok ? '✓ 解析成功' : '✗ 解析失败(未匹配任何 grok 模式)'}
            </p>
            {testResult.ok && (
              <table style={styles.table} border={1} cellPadding={6}>
                <tbody>
                  {Object.entries(testResult.fields).map(([k, v]) => (
                    <tr key={k}><td style={{ width: 220 }}><code>{k}</code></td><td><code>{String(v)}</code></td></tr>
                  ))}
                </tbody>
              </table>
            )}
          </div>
        )}
      </section>

      <section style={styles.section}>
        <h2 style={styles.h2}>③ 数据源配置预览(声明 → 生成 Logstash 配置)</h2>
        <label style={styles.label}>数据源名称</label>
        <input style={styles.input} value={name} onChange={(e) => setName(e.target.value)} placeholder="如 ssh-auth-web-01" />
        <label style={styles.label}>采集端口(tcp)</label>
        <input style={styles.input} type="number" value={port} onChange={(e) => setPort(e.target.value)} />
        <button style={styles.button} onClick={handlePreview} disabled={busy}>生成配置</button>
        {config && <pre style={{ ...styles.pre, marginTop: 10 }}>{config}</pre>}
      </section>

      <section style={styles.section}>
        <h2 style={styles.h2}>④ 数据源(创建 → 生效 → 状态)</h2>
        <div>
          <label style={styles.label}>数据源名称</label>
          <input style={styles.input} value={srcName} onChange={(e) => setSrcName(e.target.value)} placeholder="如 ssh-web-01" />
          <label style={styles.label}>端口(tcp)</label>
          <input style={styles.input} type="number" value={srcPort} onChange={(e) => setSrcPort(e.target.value)} />
          <button style={styles.button} onClick={handleCreateSource} disabled={busy}>创建数据源</button>
        </div>
        <table style={{ ...styles.table, marginTop: 12 }} border={1} cellPadding={6}>
          <thead>
            <tr><th>id</th><th>名称</th><th>模板</th><th>端口</th><th>状态</th><th>操作</th></tr>
          </thead>
          <tbody>
            {sources.map((s) => (
              <tr key={s.id}>
                <td><code>{s.id}</code></td>
                <td>{s.name}</td>
                <td>{s.templateId}</td>
                <td>{s.port}</td>
                <td>{s.status}</td>
                <td>
                  {activating[s.id] ? (
                    <span>生效中…(部署约 10-20s)</span>
                  ) : (
                    (s.status === 'creating' || s.status === 'failed') && (
                      <button style={styles.button} onClick={() => handleActivate(s.id)}>生效</button>
                    )
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </section>

      <section style={styles.section}>
        <h2 style={styles.h2}>⑤ 检测规则(infra/rules/*.yaml,只读 + 启停)</h2>
        <button style={styles.button} onClick={handleDeployRules} disabled={deploying}>
          {deploying ? '部署生效中(约 15-35s)…' : '部署生效(同步规则 + 重启检测 job)'}
        </button>
        {deployMsg && <p style={{ marginTop: 8, color: deployMsg.startsWith('部署失败') ? styles.bad.color : 'green' }}>{deployMsg}</p>}
        <table style={{ ...styles.table, marginTop: 12 }} border={1} cellPadding={6}>
          <thead>
            <tr><th>规则 id</th><th>名称</th><th>类别</th><th>type</th><th>风险分</th><th>MITRE</th><th>状态</th><th>启停</th></tr>
          </thead>
          <tbody>
            {detRules.map((r) => (
              <tr key={r.id}>
                <td><code>{r.id}</code></td>
                <td>{r.name}</td>
                <td>{r.category}</td>
                <td><code>{r.type}</code></td>
                <td>{r.riskScore}</td>
                <td>{Array.isArray(r.tags) ? r.tags.join(', ') : ''}</td>
                <td style={{ color: r.enabled ? styles.ok.color : styles.bad.color }}>{r.enabled ? '✅ 启用' : '⏸ 停用'}</td>
                <td>
                  <button style={styles.button} onClick={() => handleToggleRule(r.id)}>
                    {r.enabled ? '停用' : '启用'}
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        {mitre.coverage && (
          <details style={{ marginTop: 12 }}>
            <summary>MITRE ATT&CK 覆盖(由规则 tags 动态聚合,{mitre.coverage.length} 条)</summary>
            <table style={{ ...styles.table, marginTop: 8 }} border={1} cellPadding={4}>
              <thead><tr><th>技术</th><th>规则</th><th>覆盖</th></tr></thead>
              <tbody>
                {mitre.coverage.map((row, i) => (
                  <tr key={i}>
                    <td><code>{row.technique}</code></td>
                    <td>{row.ruleId}</td>
                    <td>{row.coverage}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </details>
        )}
      </section>

      <section style={styles.section}>
        <h2 style={styles.h2}>⑥ 数据健康(每源事件量 / 失败率 / 最后收到)</h2>
        {health.length === 0 && <p style={{ color: '#888' }}>暂无数据源事件(接入数据源并生效后,事件带 log.source_id 可聚合)</p>}
        {health.map((s) => (
          <div key={s.sourceId} style={{
            border: `1px solid ${s.anomalous ? '#e7664c' : '#ddd'}`,
            background: s.anomalous ? '#fdf0ee' : '#fff',
            borderRadius: 8, padding: 12, marginBottom: 10,
          }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
              <div>
                <strong>{s.sourceName || '(未命名)'}</strong> <code style={{ color: '#888', fontSize: 12 }}>{s.sourceId}</code>
                {s.anomalous && <span style={{ ...styles.bad, marginLeft: 8 }}>⚠ 解析异常({s.reason})</span>}
              </div>
              <button style={styles.button} onClick={() => handleHealthDetail(s.sourceId)} disabled={healthLoading}>详情(趋势/失败日志)</button>
            </div>
            <div style={{ display: 'flex', gap: 24, marginTop: 8, fontSize: 13 }}>
              <span>近 1h <b>{s.events1h}</b> 条</span>
              <span>近 24h <b>{s.events24h}</b> 条</span>
              <span>失败率 <b style={{ color: s.failRate > 5 ? styles.bad.color : styles.ok.color }}>{s.failRate}%</b> ({s.failures1h} 条)</span>
              <span>最后收到 <b>{s.lastSeen ? new Date(s.lastSeen).toLocaleString() : '—'}</b></span>
            </div>
            {healthDetail && healthDetail.sourceId === s.sourceId && (
              <div style={{ marginTop: 10 }}>
                {healthDetail.trend.length > 0 && (
                  <div>
                    <div style={{ fontSize: 12, color: '#666' }}>近 24h 事件/失败趋势(逐小时,事件条;红=失败):</div>
                    <div style={{ display: 'flex', alignItems: 'flex-end', gap: 1, height: 60, marginTop: 4 }}>
                      {healthDetail.trend.map((t, i) => (
                        <div key={i} title={`${t.bucket}: 事件 ${t.events} / 失败 ${t.failures}`}
                          style={{ width: 8, background: t.failures > 0 ? '#e7664c' : '#4e9a51', height: Math.max(2, Math.min(60, (t.events || 0) * 3)) }} />
                      ))}
                    </div>
                  </div>
                )}
                {healthDetail.failures.length > 0 && (
                  <details style={{ marginTop: 8 }}>
                    <summary style={{ fontSize: 13 }}>最近解析失败日志({healthDetail.failures.length} 条)</summary>
                    <ul style={{ fontSize: 12, marginTop: 6, maxHeight: 200, overflow: 'auto' }}>
                      {healthDetail.failures.map((f, i) => (
                        <li key={i}><code>{f['@timestamp']}</code> {f.message}</li>
                      ))}
                    </ul>
                  </details>
                )}
              </div>
            )}
          </div>
        ))}
      </section>

      <section style={styles.section}>
        <h2 style={styles.h2}>⑦ 设置·资产关键度(infra/elasticsearch/asset-criticality.json)</h2>
        <div style={{ display: 'flex', gap: 8, marginBottom: 10 }}>
          <select style={styles.input} value={critType} onChange={(e) => setCritType(e.target.value)}>
            <option value="ip">IP</option><option value="user">用户</option><option value="host">主机</option>
          </select>
          <input style={styles.input} value={critKey} onChange={(e) => setCritKey(e.target.value)} placeholder="IP/用户名/主机名" />
          <select style={styles.input} value={critLevel} onChange={(e) => setCritLevel(e.target.value)}>
            <option value="low">Low ×0.5</option><option value="medium">Medium ×1</option>
            <option value="high">High ×1.5</option><option value="extreme">Extreme ×2</option>
          </select>
          <button style={styles.button} onClick={handleCritAdd}>新增/更新</button>
          <button style={styles.button} onClick={handleRecalc}>触发实体风险重算(entity-risk.py)</button>
        </div>
        {recalcMsg && <p style={{ marginTop: 4, fontSize: 12, color: '#555' }}>{recalcMsg}</p>}
        {['ip', 'user', 'host'].map((type) => (
          <div key={type} style={{ marginBottom: 12 }}>
            <div style={{ fontSize: 13, fontWeight: 600, marginBottom: 4 }}>{type === 'ip' ? 'IP' : type === 'user' ? '用户' : '主机'}</div>
            {Object.entries(crit[type] || {}).length === 0 && <span style={{ fontSize: 12, color: '#888' }}>(空)</span>}
            {Object.entries(crit[type] || {}).map(([key, item]) => (
              <div key={key} style={{ display: 'flex', gap: 8, alignItems: 'center', marginBottom: 4, fontSize: 13 }}>
                <code style={{ width: 160 }}>{key}</code>
                <select value={item.level} onChange={(e) => handleCritSet(type, key, e.target.value)} style={styles.input}>
                  <option value="low">Low ×0.5</option><option value="medium">Medium ×1</option>
                  <option value="high">High ×1.5</option><option value="extreme">Extreme ×2</option>
                </select>
                <span style={{ color: '#888' }}>weight {item.weight}</span>
                <button style={{ ...styles.button, color: '#c00' }} onClick={() => handleCritDelete(type, key)}>删</button>
              </div>
            ))}
          </div>
        ))}
      </section>

      <section style={styles.section}>
        <h2 style={styles.h2}>⑧ 用户与权限(RBAC,admin 可见)</h2>
        {!user || user.role !== 'admin' ? (
          <p style={{ color: '#888' }}>需以 admin 登录后查看/管理用户、角色与审计日志。</p>
        ) : (
          <>
            <div style={{ display: 'flex', gap: 8, marginBottom: 10 }}>
              <input style={styles.input} value={newUname} onChange={(e) => setNewUname(e.target.value)} placeholder="新用户名" />
              <input style={styles.input} value={newPass} onChange={(e) => setNewPass(e.target.value)} placeholder="密码(≥6位)" />
              <select style={styles.input} value={newRole} onChange={(e) => setNewRole(e.target.value)}>
                <option value="analyst">analyst</option><option value="ops">ops</option>
                <option value="audit">audit</option><option value="admin">admin</option>
              </select>
              <button style={styles.button} onClick={handleCreateUser}>新增用户</button>
            </div>
            <table style={styles.table} border={1} cellPadding={6}>
              <thead><tr><th>用户名</th><th>角色</th><th>状态</th><th>操作</th></tr></thead>
              <tbody>
                {users.map((u) => (
                  <tr key={u.username}>
                    <td>{u.username}</td>
                    <td>
                      <select value={u.role} onChange={(e) => handleRoleChange(u.username, e.target.value)}>
                        <option value="analyst">analyst</option><option value="ops">ops</option>
                        <option value="audit">audit</option><option value="admin">admin</option>
                      </select>
                    </td>
                    <td>{u.status}</td>
                    <td><button style={{ ...styles.button, color: '#c00' }} onClick={() => handleDelUser(u.username)}>删</button></td>
                  </tr>
                ))}
              </tbody>
            </table>
            <details style={{ marginTop: 10 }}>
              <summary style={{ fontSize: 13 }}>角色权限矩阵({roles.length})</summary>
              <table style={{ ...styles.table, marginTop: 6 }} border={1} cellPadding={4}>
                <tbody>
                  {roles.map((r) => (
                    <tr key={r.name}><td style={{ width: 100 }}><code>{r.name}</code></td><td>{r.permissions.join(', ')}</td></tr>
                  ))}
                </tbody>
              </table>
            </details>
            <details style={{ marginTop: 10 }}>
              <summary style={{ fontSize: 13 }}>审计日志({audit.length})</summary>
              <div style={{ maxHeight: 160, overflow: 'auto', marginTop: 6, fontSize: 12 }}>
                {audit.slice().reverse().map((a, i) => (
                  <div key={i}><code>{a.timestamp}</code> {a.action} → {a.target}</div>
                ))}
              </div>
            </details>
          </>
        )}
      </section>

      <section style={styles.section}>
        <h2 style={styles.h2}>
          ⑨ 通知中心
          {notifs.filter((n) => !n.read).length > 0 && (
            <span style={{ ...styles.bad, marginLeft: 8 }}>🔔 {notifs.filter((n) => !n.read).length} 条未读</span>
          )}
        </h2>
        <button style={styles.button} onClick={handleReadAllNotifs}>全部已读</button>
        {notifs.length === 0 && <p style={{ color: '#888' }}>暂无通知(规则部署 / 实体风险重算 / 数据源健康异常会在此提示)</p>}
        <div style={{ marginTop: 8 }}>
          {notifs.slice().reverse().map((n) => (
            <div key={n.id} style={{
              display: 'flex', justifyContent: 'space-between', alignItems: 'center',
              padding: '6px 10px', marginBottom: 4, borderRadius: 6,
              background: n.read ? '#f5f5f5' : '#fffbe6', fontSize: 13,
            }}>
              <div>
                <code style={{ color: '#888', fontSize: 11 }}>[{n.type}]</code> {n.message}
                <div style={{ fontSize: 11, color: '#999' }}>{n.timestamp}</div>
              </div>
              <div style={{ display: 'flex', gap: 6 }}>
                {!n.read && <button style={styles.button} onClick={() => handleReadNotif(n.id)}>已读</button>}
                <button style={{ ...styles.button, color: '#c00' }} onClick={() => handleDelNotif(n.id)}>删</button>
              </div>
            </div>
          ))}
        </div>
      </section>

      <section style={styles.section}>
        <h2 style={styles.h2}>
          ⑩ 调查台·案件聚合
          <span style={{ fontSize: 12, color: '#888', marginLeft: 8 }}>
            (同实体 30min ≥2 条 open 告警自动成案;告警台勾选可手动聚合)
          </span>
        </h2>
        <div style={{ display: 'flex', gap: 8, marginBottom: 10, alignItems: 'center' }}>
          <select style={styles.input} value={caseFilter} onChange={(e) => { setCaseFilter(e.target.value); listCases(e.target.value).then(setCases).catch(() => {}) }}>
            <option value="">全部状态</option><option value="open">open</option>
            <option value="investigating">investigating</option><option value="resolved">resolved</option>
          </select>
          <button style={styles.button} onClick={handleAggregate}>触发一轮自动聚合</button>
          <button style={styles.button} onClick={() => setSelAlerts(new Set())}>清空告警勾选</button>
          <span style={{ fontSize: 12, color: '#888' }}>已勾选 open 告警 {selAlerts.size} 条,可「手动聚合」或「追加到案件」</span>
        </div>
        <button style={styles.button} onClick={handleCreateCase}>手动聚合勾选告警为案件</button>
        <div style={{ marginTop: 8 }}>
          {cases.length === 0 && <p style={{ color: '#888' }}>暂无案件(同实体 ≥2 条 open 告警会自动聚合;或从告警台勾选手动建案)</p>}
          {cases.map((c) => (
            <div key={c['case.id']} style={{ padding: '8px 10px', marginBottom: 6, border: '1px solid #eee', borderRadius: 6, fontSize: 13 }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                <div>
                  <b>{c['case.title']}</b>
                  <code style={{ color: '#888', marginLeft: 8, fontSize: 11 }}>{c['case.id']}</code>
                  <span style={{ marginLeft: 8, color: c['case.status'] === 'resolved' ? 'green' : c['case.status'] === 'investigating' ? '#b8860b' : '#c00' }}>
                    [{c['case.status']}]
                  </span>
                  <span style={{ marginLeft: 8, fontSize: 11, color: '#999' }}>
                    {c['case.aggregation'] === 'auto' ? '自动' : '手动'} · {c['alert_ids']?.length} 告警 · 操作者 {c['case.operator'] || '-'}
                  </span>
                </div>
                <div style={{ display: 'flex', gap: 6 }}>
                  <button style={styles.button} onClick={() => openCaseDetail(c['case.id'])}>详情</button>
                  {c['case.status'] === 'open' && (
                    <button style={styles.button} onClick={() => handleInvestigateCase(c['case.id'])}>接手</button>
                  )}
                  <button style={{ ...styles.button, color: '#c00' }} onClick={() => handleDeleteCase(c['case.id'])}>删</button>
                </div>
              </div>
            </div>
          ))}
        </div>

        {detailCase && (
          <div style={{ marginTop: 12, border: '1px solid #ccc', borderRadius: 8, padding: 12 }}>
            <div style={{ display: 'flex', gap: 8, alignItems: 'center', marginBottom: 8 }}>
              <b>{detailCase['case.title']}</b>
              <code style={{ fontSize: 11, color: '#888' }}>{detailCase['case.id']}</code>
              <span>[{detailCase['case.status']}]</span>
              {detailCase['case.status'] === 'investigating' && (
                <>
                  <select style={styles.input} onChange={(e) => e.target.value && handleResolveCase(detailCase['case.id'], e.target.value)} defaultValue="">
                    <option value="">结案选 verdict…</option>
                    <option value="true_positive">true_positive</option><option value="false_positive">false_positive</option>
                    <option value="duplicate">duplicate</option>
                  </select>
                </>
              )}
              <button style={styles.button} onClick={() => handleAddToCase(detailCase['case.id'])}>追加勾选告警</button>
              <button style={styles.button} onClick={() => setDetailCase(null)}>关</button>
            </div>
            <div style={{ fontSize: 12, marginBottom: 6, color: '#555' }}>
              实体:{detailCase['entities']?.map((e) => `${e.type}:${e.value}`).join(', ') || '-'}
              {detailCase['case.verdict'] && <span> · verdict: {detailCase['case.verdict']}</span>}
              {detailCase['case.closed_at'] && <span> · 结案: {detailCase['case.closed_at']}</span>}
            </div>
            <div style={{ fontSize: 12, marginBottom: 6 }}>
              <b>案内告警:</b>{' '}
              {detailCase['alert_ids']?.map((id) => (
                <span key={id} style={{ marginRight: 6 }}>
                  <code style={{ fontSize: 10 }}>{id.slice(0, 10)}</code>
                  <button style={{ ...styles.button, fontSize: 10, padding: '1px 6px', marginLeft: 3, color: '#c00' }}
                    onClick={() => handleRemoveFromCase(detailCase['case.id'], id)}>移出</button>
                </span>
              ))}
            </div>
            <details>
              <summary style={{ fontSize: 12 }}>关联事件时间线(实时查 siem-events,近 24h)</summary>
              <table style={{ ...styles.table, marginTop: 6 }} border={1} cellPadding={4}>
                <thead><tr><th>时间</th><th>action</th><th>message</th></tr></thead>
                <tbody>
                  {caseTimeline_.map((t, i) => (
                    <tr key={i}>
                      <td style={{ fontSize: 11 }}>{t['@timestamp']}</td>
                      <td><code>{t['event.action']}</code></td>
                      <td style={{ fontSize: 11 }}>{(t.message || '').slice(0, 60)}</td>
                    </tr>
                  ))}
                  {caseTimeline_.length === 0 && <tr><td colSpan={3} style={{ color: '#888' }}>近 24h 无关联事件(历史案件的事件可能已过期)</td></tr>}
                </tbody>
              </table>
            </details>
          </div>
        )}
      </section>

      <section style={styles.section}>
        <h2 style={styles.h2}>⑪ 告警台(三线 + verdict + 批量,替代 triage-alert.py)</h2>
        <div style={{ display: 'flex', gap: 8, marginBottom: 10 }}>
          <select style={styles.input} value={alertFilter} onChange={(e) => { setAlertFilter(e.target.value); listAlerts(e.target.value).then(setAlerts) }}>
            <option value="open">open</option><option value="acknowledged">acknowledged</option>
            <option value="investigating">investigating</option><option value="resolved">resolved</option>
            <option value="closed">closed</option>
          </select>
          <button style={styles.button} onClick={() => handleBatchStatus('acknowledged')}>批量 ack</button>
          <button style={styles.button} onClick={() => handleBatchStatus('closed')}>批量 close</button>
          <select style={styles.input} onChange={(e) => e.target.value && handleBatchVerdict(e.target.value)} defaultValue="">
            <option value="">批量 verdict…</option>
            <option value="true_positive">true_positive</option><option value="false_positive">false_positive</option>
            <option value="duplicate">duplicate</option>
          </select>
        </div>
        <table style={styles.table} border={1} cellPadding={6}>
          <thead><tr><th>☑</th><th>风险</th><th>规则</th><th>severity</th><th>状态</th><th>verdict</th><th>来源</th><th>@timestamp</th></tr></thead>
          <tbody>
            {alerts.map((a) => (
              <tr key={a['alert.id'] || a._id} onClick={() => handleAlertDetail(a._id || a['alert.id'])}
                style={{ cursor: 'pointer', background: selAlerts.has(a._id) ? '#eef6ff' : '' }}>
                <td onClick={(e) => e.stopPropagation()}><input type="checkbox" checked={selAlerts.has(a._id)}
                  onChange={() => toggleSel(a._id)} /></td>
                <td><b>{a['alert.risk_score']}</b></td>
                <td><code>{a['alert.rule_id']}</code></td>
                <td>{a['alert.severity']}</td>
                <td>{a['alert.status']}</td>
                <td style={{ color: a['alert.analyst_verdict'] === 'false_positive' ? '#c00' : '#555' }}>{a['alert.analyst_verdict'] || '—'}</td>
                <td>{a['source.ip'] || a['user.name'] || a['host.name'] || ''}</td>
                <td style={{ fontSize: 12 }}>{a['@timestamp']}</td>
              </tr>
            ))}
          </tbody>
        </table>

        {detailAlert && (
          <div style={{ marginTop: 12, border: '1px solid #ccc', borderRadius: 8, padding: 12 }}>
            <div style={{ display: 'flex', gap: 8, alignItems: 'center', marginBottom: 8 }}>
              <b>详情 {detailAlert._id}</b>
              <button style={styles.button} onClick={() => handleAlertStatus(detailAlert._id, 'acknowledged')}>ack</button>
              <button style={styles.button} onClick={() => handleAlertStatus(detailAlert._id, 'investigating')}>investigating</button>
              <button style={styles.button} onClick={() => handleAlertStatus(detailAlert._id, 'closed')}>close</button>
              <select style={styles.input} onChange={(e) => e.target.value && handleAlertVerdict(detailAlert._id, e.target.value)} defaultValue="">
                <option value="">打 verdict…</option>
                <option value="true_positive">true_positive</option><option value="false_positive">false_positive</option>
                <option value="duplicate">duplicate</option>
              </select>
              <button style={styles.button} onClick={() => setDetailAlert(null)}>关</button>
            </div>
            <pre style={{ ...styles.pre, fontSize: 12 }}>{JSON.stringify(detailAlert, null, 2).slice(0, 2500)}</pre>
          </div>
        )}

        <details style={{ marginTop: 12 }}>
          <summary style={{ fontSize: 13 }}>按规则 FP 率(FP/(TP+FP),{fpRates.filter((r) => r.high).length} 条 &gt;50% 需 review)</summary>
          <table style={{ ...styles.table, marginTop: 6 }} border={1} cellPadding={4}>
            <thead><tr><th>规则</th><th>总数</th><th>FP</th><th>TP</th><th>FP 率</th><th>标记</th></tr></thead>
            <tbody>
              {fpRates.map((r, i) => (
                <tr key={i} style={{ background: r.high ? '#fdf0ee' : '' }}>
                  <td><code>{r.ruleId}</code></td><td>{r.total}</td><td>{r.fp}</td><td>{r.tp}</td>
                  <td><b>{r.fpRate}%</b></td><td>{r.high ? '⚠ 需 review' : '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </details>
      </section>
    </div>
  )
}
