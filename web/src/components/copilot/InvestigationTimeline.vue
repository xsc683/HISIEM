<template>
  <a-card class="surface-card" size="small" :title="`活动时间线 (${filtered.length}/${timeline.length})`">
    <template #extra>
      <a-radio-group v-model:value="filterKey" size="small" button-style="solid">
        <a-radio-button v-for="filter in TIMELINE_FILTERS" :key="filter.key" :value="filter.key">
          {{ filter.label }}
        </a-radio-button>
      </a-radio-group>
    </template>
    <a-empty v-if="!filtered.length" :image="Empty.PRESENTED_IMAGE_SIMPLE" description="当前筛选下暂无活动记录" />
    <a-timeline v-else class="timeline">
      <a-timeline-item v-for="(entry, index) in filtered" :key="`${entry.kind}-${entry.ref_id}-${index}`" :color="kindColor(entry.kind)">
        <div class="timeline-head">
          <a-tag color="default" class="timeline-kind">{{ timelineEntryLabel(entry) }}</a-tag>
          <span class="timeline-title">{{ entry.title || '—' }}</span>
          <span class="timeline-time"><TimeText :value="entry.occurred_at" /></span>
        </div>
        <div class="timeline-foot">
          <a-tag v-if="entry.status" :color="toolStatusColor(entry.status)">{{ timelineStatusLabel(entry.status) }}</a-tag>
          <a-tag
            v-if="entry.ref_type === 'evidence' && entry.ref_id" color="blue" class="ref-chip"
            @click="emit('select-evidence', entry.ref_id)">
            查看证据 {{ String(entry.ref_id).slice(0, 8) }}
          </a-tag>
          <span v-if="metadataText(entry)" class="timeline-meta">{{ metadataText(entry) }}</span>
        </div>
      </a-timeline-item>
    </a-timeline>
  </a-card>
</template>

<script setup>
import { computed, ref } from 'vue'
import { Empty } from 'ant-design-vue'
import TimeText from '../common/TimeText.vue'
import {
  TIMELINE_FILTERS,
  filterTimeline,
  timelineEntryLabel,
  timelineStatusLabel,
  toolStatusColor,
} from '../../utils/copilot.js'

const props = defineProps({ timeline: { type: Array, default: () => [] } })
const emit = defineEmits(['select-evidence'])

const filterKey = ref('all')
const filtered = computed(() => filterTimeline(props.timeline, filterKey.value))

function kindColor(kind) {
  if (kind.includes('FAILED')) return 'red'
  if (kind.includes('SUCCEEDED') || kind.includes('RECORDED') || kind.includes('FINALIZED') || kind.includes('COMPLETED')) return 'green'
  if (kind === 'INVESTIGATION_CANCELLED') return 'gray'
  return 'blue'
}

function metadataText(entry) {
  const meta = entry.safe_metadata
  if (!meta || typeof meta !== 'object') return ''
  return Object.entries(meta).map(([key, value]) => `${key}: ${value}`).join(' · ')
}
</script>

<style scoped>
.timeline { margin-top: 4px; }
.timeline-head { display: flex; align-items: baseline; gap: 8px; }
.timeline-kind { margin: 0; }
.timeline-title { flex: 1; color: #1d3244; font-size: 13px; font-weight: 600; }
.timeline-time { color: #93a3ad; font-size: 11px; }
.timeline-foot { display: flex; flex-wrap: wrap; align-items: center; gap: 6px; margin-top: 4px; }
.timeline-meta { color: #7c8b96; font-size: 11px; }
.ref-chip { margin: 0; cursor: pointer; }
</style>
