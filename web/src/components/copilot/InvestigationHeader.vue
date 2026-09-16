<template>
  <div class="investigation-header">
    <PageHeader :title="title" :description="`调查 ID：${header?.investigation_id || '—'}`">
      <a-button @click="emit('back')">返回告警</a-button>
      <a-button :loading="refreshing" @click="emit('refresh')">
        刷新<template v-if="lastRefreshed"><span class="refresh-hint">（{{ lastRefreshed }}）</span></template>
      </a-button>
      <a-popconfirm
        v-if="active" title="确认取消本次调查？" ok-text="取消调查" cancel-text="返回"
        :disabled="cancelling" @confirm="emit('cancel')">
        <a-button danger :loading="cancelling">取消调查</a-button>
      </a-popconfirm>
    </PageHeader>

    <a-alert
      v-if="stale" type="warning" show-icon class="stale-banner"
      message="数据可能已过期"
      :description="`最近一次刷新失败，当前展示的是最后一次成功获取的调查快照。${lastRefreshed ? `（快照时间 ${lastRefreshed}）` : ''}`" />

    <a-card class="surface-card" size="small">
      <div class="header-strip">
        <span class="strip-item"><span class="strip-label">状态</span><a-tag :color="investigationStatusColor(header?.status)">{{ investigationStatusLabel(header?.status) }}</a-tag></span>
        <span v-if="active && header?.phase" class="strip-item"><span class="strip-label">阶段</span><a-tag color="blue">{{ investigationPhaseLabel(header.phase) }}</a-tag></span>
        <span class="strip-item"><span class="strip-label">发起人</span><strong>{{ header?.initiated_by || '—' }}</strong></span>
        <span class="strip-item"><span class="strip-label">耗时</span><strong>{{ durationText(header?.started_at, header?.finished_at) }}</strong></span>
        <span v-if="running" class="strip-item running-hint"><a-spin size="small" /> 调查进行中</span>
      </div>
      <a-descriptions bordered size="small" :column="isNarrow ? 1 : 2" class="header-descriptions">
        <a-descriptions-item label="创建时间"><TimeText :value="header?.created_at" /></a-descriptions-item>
        <a-descriptions-item label="开始时间"><TimeText :value="header?.started_at" /></a-descriptions-item>
        <a-descriptions-item label="结束时间"><TimeText :value="header?.finished_at" /></a-descriptions-item>
        <a-descriptions-item label="取消时间"><TimeText :value="header?.cancelled_at" /></a-descriptions-item>
        <a-descriptions-item label="终点原因" :span="2">{{ header?.termination_reason || '—' }}</a-descriptions-item>
        <a-descriptions-item label="源告警" :span="2">
          <template v-if="sourceAlertRef">
            <a-tag color="default">{{ sourceAlertRef.provider }} / {{ sourceAlertRef.resource_type }}</a-tag>
            <router-link :to="`/alerts/${encodeURIComponent(sourceAlertRef.address_id)}`" class="source-link">
              {{ sourceAlertRef.business_id || sourceAlertRef.address_id }}
            </router-link>
          </template>
          <span v-else>—</span>
        </a-descriptions-item>
      </a-descriptions>
    </a-card>
  </div>
</template>

<script setup>
import PageHeader from '../common/PageHeader.vue'
import TimeText from '../common/TimeText.vue'
import { durationText, investigationPhaseLabel, investigationStatusColor, investigationStatusLabel } from '../../utils/copilot.js'
import { useNarrowViewport } from '../../composables/useViewport.js'

defineProps({
  header: { type: Object, default: null },
  sourceAlertRef: { type: Object, default: null },
  active: { type: Boolean, default: false },
  running: { type: Boolean, default: false },
  stale: { type: Boolean, default: false },
  lastRefreshed: { type: String, default: '' },
  refreshing: { type: Boolean, default: false },
  cancelling: { type: Boolean, default: false },
  title: { type: String, default: 'AI 调查工作台' },
})
const emit = defineEmits(['back', 'refresh', 'cancel'])
const { isNarrow } = useNarrowViewport()
</script>

<style scoped>
.investigation-header { display: grid; gap: 16px; }
.refresh-hint { color: #93a3ad; font-size: 11px; }
.stale-banner { margin: 0; }
.header-strip { display: flex; flex-wrap: wrap; align-items: center; gap: 18px; margin-bottom: 12px; }
.strip-item { display: inline-flex; align-items: center; gap: 6px; color: #2f5268; font-size: 13px; }
.strip-label { color: #80919d; }
.running-hint { color: #1677ff; }
.header-descriptions { margin-top: 4px; }
.source-link { margin-left: 8px; }
</style>
