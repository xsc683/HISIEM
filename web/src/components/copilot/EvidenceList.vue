<template>
  <a-card class="surface-card" size="small" :title="`证据 (${evidence.length})`">
    <a-empty v-if="!evidence.length" :image="Empty.PRESENTED_IMAGE_SIMPLE" description="尚未收集到证据" />
    <div v-else class="evidence-list">
      <button
        v-for="item in evidence" :key="item.evidence_id"
        type="button" class="evidence-card" :class="{ selected: item.evidence_id === selectedId }"
        @click="emit('select', item.evidence_id)">
        <div class="evidence-head">
          <span class="evidence-summary">{{ item.summary || item.evidence_id }}</span>
          <a-tag v-if="citationCount(item.evidence_id)" color="blue" class="evidence-usage">
            被 {{ citationCount(item.evidence_id) }} 个发现引用
          </a-tag>
        </div>
        <div class="evidence-meta">
          <span>{{ sourceLabel(item) }}</span>
          <span class="dot">·</span>
          <span>观测时间 <TimeText :value="item.observed_at" /></span>
          <span class="dot">·</span>
          <span>采集时间 <TimeText :value="item.collected_at" /></span>
        </div>
        <div v-if="item.entity_refs?.length" class="evidence-entities">
          <a-tag v-for="(ref, index) in item.entity_refs" :key="index" color="default" class="entity-tag">
            {{ ref.kind }}: {{ ref.value }}
          </a-tag>
        </div>
      </button>
    </div>
  </a-card>
</template>

<script setup>
import { Empty } from 'ant-design-vue'
import TimeText from '../common/TimeText.vue'

const props = defineProps({
  evidence: { type: Array, default: () => [] },
  findings: { type: Array, default: () => [] },
  selectedId: { type: String, default: '' },
})
const emit = defineEmits(['select'])

const usage = (() => {
  const map = {}
  for (const finding of props.findings || []) {
    for (const id of finding.evidence_citations || []) map[id] = (map[id] || 0) + 1
  }
  return map
})()

function citationCount(id) { return usage[id] || 0 }

function sourceLabel(item) {
  const source = item.source || {}
  const parts = [source.type, source.provider, source.operation].filter(Boolean)
  return parts.length ? parts.join(' / ') : '来源未知'
}
</script>

<style scoped>
.evidence-list { display: grid; gap: 10px; }
.evidence-card { display: block; width: 100%; padding: 12px 14px; border: 1px solid #e4ecf1; border-radius: 8px; background: #fff; text-align: left; cursor: pointer; transition: border-color .15s, box-shadow .15s; }
.evidence-card:hover { border-color: #91caff; box-shadow: 0 2px 8px rgba(22, 119, 255, .12); }
.evidence-card.selected { border-color: #1677ff; box-shadow: 0 0 0 2px rgba(22, 119, 255, .16); }
.evidence-head { display: flex; align-items: baseline; gap: 8px; }
.evidence-summary { flex: 1; color: #1d3244; font-size: 14px; font-weight: 600; line-height: 1.6; }
.evidence-usage { flex: 0 0 auto; margin: 0; }
.evidence-meta { margin-top: 5px; color: #7c8b96; font-size: 11px; }
.evidence-meta .dot { margin: 0 6px; }
.evidence-entities { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 8px; }
.entity-tag { margin: 0; font-size: 11px; }
</style>
