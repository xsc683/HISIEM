<template>
  <div class="finding-card" :class="{ 'in-result': finding.in_result }">
    <div class="finding-head">
      <span class="finding-index">#{{ ordinal }}</span>
      <span class="finding-statement">{{ finding.statement || '—' }}</span>
      <a-tag :color="finding.in_result ? 'green' : 'default'" class="finding-badge">
        {{ finding.in_result ? '已纳入结论' : '过程发现' }}
      </a-tag>
    </div>
    <div class="finding-meta">记录于 <TimeText :value="finding.created_at" /></div>
    <div class="finding-citations">
      <span class="citation-label">证据引用</span>
      <template v-if="citations.length">
        <a-tag
          v-for="id in citations" :key="id" color="blue" class="citation-chip"
          @click="emit('select-evidence', id)">
          {{ chipLabel(id) }}
        </a-tag>
      </template>
      <a-tag v-else color="warning" class="citation-chip">未引用证据</a-tag>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import TimeText from '../common/TimeText.vue'

const props = defineProps({
  finding: { type: Object, required: true },
  ordinal: { type: [Number, String], default: '' },
  evidenceById: { type: Object, default: () => ({}) },
})
const emit = defineEmits(['select-evidence'])

const citations = computed(() => Array.isArray(props.finding?.evidence_citations) ? props.finding.evidence_citations : [])

function chipLabel(id) {
  const evidence = props.evidenceById[id]
  const summary = evidence?.summary ? String(evidence.summary) : ''
  if (!summary) return `证据 ${String(id).slice(0, 8)}`
  return summary.length > 34 ? `${summary.slice(0, 34)}…` : summary
}
</script>

<style scoped>
.finding-card { padding: 12px 14px; border: 1px solid #e4ecf1; border-left: 3px solid #c7d5de; border-radius: 8px; background: #fff; }
.finding-card.in-result { border-left-color: #389e0d; }
.finding-head { display: flex; align-items: baseline; gap: 8px; }
.finding-index { color: #93a3ad; font-size: 12px; font-weight: 700; }
.finding-statement { flex: 1; color: #1d3244; font-size: 14px; font-weight: 600; line-height: 1.6; }
.finding-badge { flex: 0 0 auto; }
.finding-meta { margin-top: 4px; color: #93a3ad; font-size: 11px; }
.finding-citations { display: flex; flex-wrap: wrap; align-items: center; gap: 6px; margin-top: 10px; }
.citation-label { color: #5b7080; font-size: 12px; }
.citation-chip { margin: 0; cursor: pointer; }
</style>
