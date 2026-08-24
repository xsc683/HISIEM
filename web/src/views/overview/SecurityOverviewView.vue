<template>
  <div ref="screen" class="security-overview">
    <header class="overview-header">
      <div>
        <span class="eyebrow">HISIEM · LIVE SECURITY OPERATIONS</span>
        <h1>安全运营态势大屏</h1>
        <p>事件接入、告警分诊、案件调查和闭环结果的同屏观察</p>
      </div>
      <div class="overview-actions">
        <div class="freshness"><i :class="{ error: Boolean(error) }" />{{ error || `最近更新 ${updatedText}` }}</div>
        <a :href="kibanaUrl" target="_blank" rel="noopener noreferrer">进入 Kibana</a>
        <button type="button" @click="toggleFullscreen">{{ fullscreen ? '退出全屏' : '进入全屏' }}</button>
        <button type="button" :disabled="loading" @click="load">{{ loading ? '刷新中…' : '立即刷新' }}</button>
      </div>
    </header>

    <section class="metric-grid">
      <article class="metric event-metric">
        <span>24 小时事件</span><strong>{{ formatNumber(eventTotal) }}</strong>
        <small>当前结果展示最近 {{ events.length }} 条</small>
      </article>
      <article class="metric alert-metric">
        <span>待处置告警</span><strong>{{ alertBacklog }}</strong>
        <small>open / acknowledged / investigating</small>
      </article>
      <article class="metric case-metric">
        <span>调查中案件</span><strong>{{ activeCases }}</strong>
        <small>open + investigating</small>
      </article>
      <article class="metric closure-metric">
        <span>告警闭环率</span><strong>{{ alertClosureRate }}%</strong>
        <small>resolved + closed / 全量告警</small>
      </article>
      <article class="metric closure-metric">
        <span>案件闭环率</span><strong>{{ caseClosureRate }}%</strong>
        <small>resolved / 全量案件</small>
      </article>
      <article class="metric link-metric">
        <span>告警归案率</span><strong>{{ caseLinkRate }}%</strong>
        <small>已关联 case.id 的告警</small>
      </article>
    </section>

    <section class="overview-grid">
      <article class="panel event-panel">
        <div class="panel-title"><div><span>LIVE EVENTS</span><h2>实时安全事件</h2></div><router-link to="/logs">检索全部日志 →</router-link></div>
        <div class="event-stream">
          <router-link v-for="event in events" :key="event._id" to="/logs" class="event-row">
            <time>{{ timeOf(event['@timestamp']) }}</time>
            <div><strong>{{ event['event.action'] || event['event.category'] || '安全事件' }}</strong><span>{{ eventSummary(event) }}</span></div>
            <code>{{ event['source.ip'] || event['host.name'] || event['user.name'] || '—' }}</code>
          </router-link>
          <div v-if="!events.length && !loading" class="empty-state">最近 24 小时没有可展示事件</div>
        </div>
      </article>

      <article class="panel closure-panel">
        <div class="panel-title"><div><span>CASE CLOSURE</span><h2>处置与闭环</h2></div><small>全量状态聚合</small></div>
        <div class="funnel-section">
          <h3>告警处置状态</h3>
          <div v-for="item in alertStatusBars" :key="item.key" class="status-line">
            <label><span>{{ item.label }}</span><strong>{{ item.count }}</strong></label>
            <div><i :style="{ width: `${item.percent}%`, background: item.color }" /></div>
          </div>
        </div>
        <div class="funnel-section">
          <h3>案件调查状态</h3>
          <div v-for="item in caseStatusBars" :key="item.key" class="status-line">
            <label><span>{{ item.label }}</span><strong>{{ item.count }}</strong></label>
            <div><i :style="{ width: `${item.percent}%`, background: item.color }" /></div>
          </div>
        </div>
      </article>

      <article class="panel recent-panel">
        <div class="panel-title"><div><span>ALERT QUEUE</span><h2>最新告警</h2></div><router-link to="/alerts">进入告警台 →</router-link></div>
        <router-link v-for="alert in recentAlerts" :key="alert._id" :to="`/alerts/${encodeURIComponent(alert._id)}`" class="work-row">
          <i :class="`severity-${alert['alert.severity'] || 'unknown'}`" aria-hidden="true" />
          <div><strong>{{ alert['alert.rule_name'] || '未命名告警' }}</strong><span>{{ severityLabel(alert['alert.severity']) }} · {{ alert['alert.entity'] || alert['source.ip'] || '未知实体' }}</span></div>
          <div class="work-state"><b>{{ alert['alert.risk_score'] ?? '—' }}</b><time>{{ timeOf(alert['alert.created_at'] || alert['@timestamp']) }}</time></div>
        </router-link>
        <div v-if="!recentAlerts.length && !loading" class="empty-state">暂无告警</div>
      </article>

      <article class="panel recent-panel">
        <div class="panel-title"><div><span>INVESTIGATION</span><h2>最新案件</h2></div><router-link to="/cases">进入调查台 →</router-link></div>
        <router-link v-for="item in recentCases" :key="item['case.id']" :to="`/cases/${encodeURIComponent(item['case.id'])}`" class="work-row case-row">
          <i :class="`case-${item['case.status'] || 'open'}`" />
          <div><strong>{{ item['case.title'] || item['case.id'] }}</strong><span>{{ (item.entities || []).map((entity) => entity.value).join(' · ') || '未提取实体' }}</span></div>
          <div class="work-state"><b>{{ item.alert_ids?.length || 0 }} 告警</b><time>{{ timeOf(item['case.updated_at']) }}</time></div>
        </router-link>
        <div v-if="!recentCases.length && !loading" class="empty-state">暂无案件</div>
      </article>
    </section>
    <footer>每 10 秒自动刷新 · 页面不可见时暂停请求 · 数据时间统一按浏览器时区展示</footer>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { getAlertSummary, getCaseSummary } from '../../api/index.js'
import { searchLogs } from '../logs/logSearchApi.js'
import { kibanaUrl as resolveKibanaUrl } from '../../utils/runtimeUrls.js'

const screen = ref(null)
const loading = ref(false)
const error = ref('')
const updatedAt = ref(null)
const events = ref([])
const eventTotal = ref(0)
const alertSummary = ref({ total: 0, linked: 0, statuses: {}, recent: [] })
const caseSummary = ref({ total: 0, statuses: {}, recent: [] })
const fullscreen = ref(false)
const kibanaUrl = resolveKibanaUrl()
let refreshTimer

const percent = (value, total) => total ? Math.round((value / total) * 100) : 0
const statusCount = (summary, statuses) => statuses.reduce((total, status) => total + Number(summary.statuses?.[status] || 0), 0)
const alertBacklog = computed(() => statusCount(alertSummary.value, ['open', 'acknowledged', 'investigating']))
const activeCases = computed(() => statusCount(caseSummary.value, ['open', 'investigating']))
const alertClosureRate = computed(() => percent(statusCount(alertSummary.value, ['resolved', 'closed']), alertSummary.value.total))
const caseClosureRate = computed(() => percent(statusCount(caseSummary.value, ['resolved']), caseSummary.value.total))
const caseLinkRate = computed(() => percent(alertSummary.value.linked, alertSummary.value.total))
const recentAlerts = computed(() => alertSummary.value.recent || [])
const recentCases = computed(() => caseSummary.value.recent || [])
const updatedText = computed(() => updatedAt.value ? new Intl.DateTimeFormat('zh-CN', { hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false }).format(updatedAt.value) : '等待首次刷新')

const alertStatusBars = computed(() => bars(alertSummary.value, [
  ['open', '待确认', '#ff6b75'], ['acknowledged', '已确认', '#f6a84b'], ['investigating', '调查中', '#47a9ff'],
  ['resolved', '已解决', '#45d1b2'], ['closed', '已关闭', '#768da0'],
]))
const caseStatusBars = computed(() => bars(caseSummary.value, [
  ['open', '待调查', '#ff8b61'], ['investigating', '调查中', '#47a9ff'], ['resolved', '已闭环', '#45d1b2'],
]))

function bars(summary, definitions) {
  return definitions.map(([key, label, color]) => {
    const value = Number(summary.statuses?.[key] || 0)
    return { key, label, color, count: value, percent: percent(value, summary.total) }
  })
}

function eventEpoch(value) { const epoch = Date.parse(value || ''); return Number.isFinite(epoch) ? epoch : 0 }
function formatNumber(value) { return new Intl.NumberFormat('zh-CN').format(Number(value) || 0) }
function timeOf(value) {
  if (!value || !eventEpoch(value)) return '—'
  return new Intl.DateTimeFormat('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false }).format(new Date(value))
}
function eventSummary(event) {
  const value = event.message || event['event.original'] || [event['user.name'], event['host.name']].filter(Boolean).join(' · ')
  return String(value || '无事件摘要').slice(0, 120)
}
function severityLabel(value) {
  return ({ critical: '严重', high: '高危', medium: '中危', low: '低危' })[value] || '未知级别'
}

async function load() {
  if (loading.value || document.hidden) return
  loading.value = true
  error.value = ''
  try {
    const from = new Date(Date.now() - 24 * 60 * 60 * 1000).toISOString()
    const [eventResult, alertResult, caseResult] = await Promise.allSettled([
      searchLogs({ from, to: new Date().toISOString(), page: 0, size: 20, sort: 'desc', logic: 'AND', filters: [] }),
      getAlertSummary(),
      getCaseSummary(),
    ])
    const failures = []
    if (eventResult.status === 'fulfilled') {
      events.value = eventResult.value.items || []
      eventTotal.value = Number(eventResult.value.total) || 0
    } else failures.push(`事件：${eventResult.reason?.message || '请求失败'}`)
    if (alertResult.status === 'fulfilled') alertSummary.value = alertResult.value || alertSummary.value
    else failures.push(`告警：${alertResult.reason?.message || '请求失败'}`)
    if (caseResult.status === 'fulfilled') caseSummary.value = caseResult.value || caseSummary.value
    else failures.push(`案件：${caseResult.reason?.message || '请求失败'}`)
    if (failures.length < 3) updatedAt.value = new Date()
    error.value = failures.length ? `部分数据刷新失败 · ${failures.join('；')}` : ''
  } finally {
    loading.value = false
  }
}

async function toggleFullscreen() {
  if (!document.fullscreenElement) await screen.value?.requestFullscreen?.()
  else await document.exitFullscreen?.()
}
function syncFullscreen() { fullscreen.value = Boolean(document.fullscreenElement) }
function refreshWhenVisible() { if (!document.hidden) load() }

onMounted(() => {
  load()
  refreshTimer = window.setInterval(load, 10_000)
  document.addEventListener('visibilitychange', refreshWhenVisible)
  document.addEventListener('fullscreenchange', syncFullscreen)
})
onBeforeUnmount(() => {
  window.clearInterval(refreshTimer)
  document.removeEventListener('visibilitychange', refreshWhenVisible)
  document.removeEventListener('fullscreenchange', syncFullscreen)
})
</script>

<style scoped>
.security-overview { min-height: calc(100vh - 112px); padding: 22px; overflow: hidden; color: #d9ecf7; background: radial-gradient(circle at 12% 8%, rgb(25 95 125 / 35%), transparent 32%), radial-gradient(circle at 90% 0, rgb(36 89 144 / 28%), transparent 30%), #071923; border: 1px solid #183746; border-radius: 14px; box-shadow: inset 0 0 70px rgb(22 111 144 / 8%); }
.security-overview:fullscreen { min-height: 100vh; padding: 28px; overflow: auto; border: 0; border-radius: 0; }
.overview-header, .overview-actions, .panel-title, .status-line label, .work-row, footer { display: flex; align-items: center; }
.overview-header { justify-content: space-between; gap: 24px; margin-bottom: 18px; }
.eyebrow, .panel-title span { color: #55c9ef; font-size: 10px; font-weight: 800; letter-spacing: .16em; }
h1 { margin: 3px 0 2px; color: #f4fbff; font-size: 26px; letter-spacing: .03em; } .overview-header p { margin: 0; color: #7695a6; }
.overview-actions { justify-content: flex-end; gap: 9px; flex-wrap: wrap; }.overview-actions a, .overview-actions button { height: 32px; padding: 0 12px; color: #c9e6f4; border: 1px solid #28546a; border-radius: 6px; background: #0d2a39; cursor: pointer; }.overview-actions button:disabled { opacity: .55; cursor: wait; }
.freshness { color: #8faebb; font-size: 12px; }.freshness i { width: 7px; height: 7px; margin-right: 7px; display: inline-block; border-radius: 50%; background: #41d0a8; box-shadow: 0 0 9px #41d0a8; }.freshness i.error { background: #ff6975; box-shadow: 0 0 9px #ff6975; }
.metric-grid { display: grid; grid-template-columns: repeat(6, minmax(145px, 1fr)); gap: 10px; margin-bottom: 10px; }.metric { min-width: 0; padding: 13px 15px; border: 1px solid #1b4152; border-top: 2px solid var(--accent, #44c8ed); background: linear-gradient(145deg, rgb(17 48 63 / 96%), rgb(9 31 42 / 96%)); }.metric span, .metric small { display: block; color: #789aaa; }.metric span { font-size: 12px; }.metric strong { display: block; margin: 4px 0 1px; color: #f5fbff; font-size: 28px; line-height: 1.05; }.metric small { overflow: hidden; font-size: 10px; text-overflow: ellipsis; white-space: nowrap; }.alert-metric { --accent:#ff6b75; }.case-metric { --accent:#f5a44a; }.closure-metric { --accent:#44d0ad; }.link-metric { --accent:#9e8cff; }
.overview-grid { display: grid; grid-template-columns: minmax(0, 1.5fr) minmax(300px, .75fr); gap: 10px; }.panel { min-width: 0; padding: 15px; border: 1px solid #1b4152; background: rgb(8 29 40 / 92%); }.panel-title { justify-content: space-between; min-height: 38px; margin-bottom: 9px; }.panel-title h2 { margin: 1px 0 0; color: #eaf7fc; font-size: 16px; }.panel-title a { color: #55c9ef; font-size: 12px; }.panel-title small { color: #658596; }
.event-stream { min-height: 282px; }.event-row { display: grid; grid-template-columns: 116px minmax(0, 1fr) 135px; gap: 12px; align-items: center; min-height: 44px; padding: 5px 7px; color: inherit; border-top: 1px solid #143545; }.event-row:hover, .work-row:hover { background: #0f3343; }.event-row time, .event-row code, .work-state time { color: #7394a4; font-size: 11px; }.event-row div { min-width: 0; }.event-row strong, .event-row span { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }.event-row strong { color: #d7edf7; font-size: 12px; }.event-row span { margin-top: 2px; color: #7593a2; font-size: 11px; }.event-row code { overflow: hidden; text-align: right; text-overflow: ellipsis; }
.funnel-section + .funnel-section { margin-top: 20px; }.funnel-section h3 { margin: 0 0 7px; color: #8cabb9; font-size: 12px; }.status-line { display: grid; grid-template-columns: 90px 1fr; gap: 9px; margin: 8px 0; }.status-line label { justify-content: space-between; color: #93b0bd; font-size: 11px; }.status-line label strong { color: #d9ecf7; }.status-line > div { height: 7px; align-self: center; overflow: hidden; background: #102f3e; border-radius: 5px; }.status-line i { display: block; min-width: 2px; height: 100%; border-radius: inherit; transition: width .3s; }
.work-row { min-height: 46px; gap: 10px; padding: 5px 6px; color: inherit; border-top: 1px solid #143545; }.work-row > i { width: 8px; height: 28px; flex: 0 0 8px; border-radius: 2px; background: #607e8d; }.work-row > div:nth-child(2) { min-width: 0; flex: 1; }.work-row strong, .work-row span, .work-state b, .work-state time { display: block; }.work-row strong, .work-row span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }.work-row strong { color: #d9ecf7; font-size: 12px; }.work-row span { color: #718f9e; font-size: 11px; }.work-state { flex: 0 0 92px; text-align: right; }.work-state b { color: #d8edf6; font-size: 11px; }.severity-critical { background:#ff5261 !important; }.severity-high { background:#ff8a55 !important; }.severity-medium { background:#f4c44f !important; }.severity-low { background:#4fb7e6 !important; }.case-open { background:#ff8a55 !important; }.case-investigating { background:#4fa8ef !important; }.case-resolved { background:#45d1b2 !important; }
.empty-state { display: grid; min-height: 90px; place-items: center; color: #587889; font-size: 12px; } footer { justify-content: center; padding-top: 11px; color: #527383; font-size: 10px; letter-spacing: .04em; }
@media (max-width: 1280px) { .metric-grid { grid-template-columns: repeat(3, 1fr); }.overview-grid { grid-template-columns: 1fr; } }
@media (max-width: 760px) { .security-overview { padding: 14px; }.overview-header { align-items: flex-start; flex-direction: column; }.metric-grid { grid-template-columns: repeat(2, 1fr); }.event-row { grid-template-columns: 92px minmax(0, 1fr); }.event-row code { display: none; } }
</style>
