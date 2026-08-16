import { useEffect, useState } from 'react'
import { listTemplates, testParse, previewLogSource } from './api.js'

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

  useEffect(() => {
    listTemplates().then(setTemplates).catch((e) => alert(e.message))
  }, [])

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
    </div>
  )
}
