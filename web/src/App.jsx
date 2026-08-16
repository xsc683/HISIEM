import { useEffect, useState } from 'react'
import {
  listTemplates, testParse, previewLogSource,
  listLogSources, createLogSource, activateLogSource, getLogSource,
  listDetectionRules, toggleRule, deployRules, ruleMitre,
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

  useEffect(() => {
    listTemplates().then(setTemplates).catch((e) => alert(e.message))
    listLogSources().then(setSources).catch(() => {})
    listDetectionRules().then(setDetRules).catch(() => {})
    ruleMitre().then(setMitre).catch(() => {})
  }, [])

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
    </div>
  )
}
