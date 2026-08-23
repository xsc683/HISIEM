<template>
  <div class="alert-details">
    <a-alert type="info" show-icon message="时间字段不是同一个含义" :description="`事件时间用于检测窗口；告警生成时间是系统完成检测的时间。页面按${LOCAL_TIME_LABEL}显示，原始 JSON 保留 UTC。`" />
    <div class="metric-strip">
      <div class="metric"><span class="metric-label">风险分</span><span class="metric-value">{{ alert['alert.risk_score'] ?? '—' }}</span></div>
      <div class="metric"><span class="metric-label">严重级别</span><span class="metric-value"><StatusTag group="severity" :value="alert['alert.severity']" /></span></div>
      <div class="metric"><span class="metric-label">关联事件</span><span class="metric-value">{{ alert.event_count || related.length || 1 }}</span></div>
      <div class="metric"><span class="metric-label">处置状态</span><span class="metric-value"><StatusTag :value="alert['alert.status']" /></span></div>
    </div>
    <a-descriptions bordered size="small" :column="2">
      <a-descriptions-item label="告警 ID"><code>{{ alert._id || alert['alert.id'] }}</code></a-descriptions-item>
      <a-descriptions-item label="规则"><router-link v-if="alert['alert.rule_id']" :to="`/rules/${encodeURIComponent(alert['alert.rule_id'])}`">{{ alert['alert.rule_name'] || alert['alert.rule_id'] }}</router-link></a-descriptions-item>
      <a-descriptions-item label="实体"><code>{{ entityOf(alert) }}</code></a-descriptions-item>
      <a-descriptions-item label="用户 / 主机">{{ alert['user.name'] || '—' }} / {{ alert['host.name'] || '—' }}</a-descriptions-item>
      <a-descriptions-item label="事件动作"><code>{{ alert['event.action'] || '—' }}</code></a-descriptions-item>
      <a-descriptions-item label="分析结论"><StatusTag v-if="alert['alert.analyst_verdict']" group="verdict" :value="alert['alert.analyst_verdict']" /><span v-else>未判定</span></a-descriptions-item>
      <a-descriptions-item label="事件时间"><TimeText :value="alert['@timestamp']" /></a-descriptions-item>
      <a-descriptions-item label="告警生成时间"><TimeText :value="alert['alert.created_at']" /></a-descriptions-item>
      <a-descriptions-item label="说明" :span="2">{{ alert['alert.description'] || '—' }}</a-descriptions-item>
    </a-descriptions>
    <a-tabs>
      <a-tab-pane key="evidence" :tab="`关联事件 (${related.length})`">
        <a-empty v-if="!related.length" description="该告警没有 related_events；单事件证据见原始事件字段。" />
        <a-collapse v-else>
          <a-collapse-panel v-for="(event, index) in related" :key="index">
            <template #header>
              <a-space><TimeText :value="event['@timestamp']" /><a-tag>{{ event['event.action'] || event['event.category'] || '事件' }}</a-tag><code>{{ event['source.ip'] || event['user.name'] || '—' }}</code></a-space>
            </template>
            <a-descriptions bordered size="small" :column="2">
              <a-descriptions-item v-for="field in evidenceFields" :key="field" :label="field">{{ displayValue(event[field]) }}</a-descriptions-item>
              <a-descriptions-item label="event.original" :span="2"><span class="original-event">{{ event['event.original'] || event.message || '—' }}</span></a-descriptions-item>
            </a-descriptions>
            <details class="raw-event"><summary>查看该事件完整 JSON</summary><pre class="code-panel">{{ JSON.stringify(event, null, 2) }}</pre></details>
          </a-collapse-panel>
        </a-collapse>
      </a-tab-pane>
      <a-tab-pane key="raw" tab="完整告警 JSON"><pre class="code-panel raw-alert">{{ JSON.stringify(alert, null, 2) }}</pre></a-tab-pane>
    </a-tabs>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import StatusTag from '../common/StatusTag.vue'
import TimeText from '../common/TimeText.vue'
import { entityOf, LOCAL_TIME_LABEL } from '../../utils/display.js'

const props = defineProps({ alert: { type: Object, required: true } })
const related = computed(() => Array.isArray(props.alert.related_events) ? props.alert.related_events : [])
const evidenceFields = ['event.category', 'event.action', 'event.outcome', 'source.ip', 'user.name', 'host.name']
function displayValue(value) { return Array.isArray(value) ? value.join(', ') : value == null || value === '' ? '—' : String(value) }
</script>

<style scoped>
.alert-details { display: grid; gap: 16px; }
.original-event { white-space: pre-wrap; overflow-wrap: anywhere; }
.raw-event { margin-top: 12px; }
.raw-event summary { cursor: pointer; color: #1d6fa5; margin-bottom: 8px; }
.raw-alert { max-height: 720px; }
</style>
