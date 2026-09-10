<template>
  <a-card class="surface-card" size="small" :title="`假设 (${hypotheses.length})`">
    <a-empty v-if="!hypotheses.length" :image="Empty.PRESENTED_IMAGE_SIMPLE" description="尚未提出假设" />
    <div v-else class="hypothesis-list">
      <div v-for="item in hypotheses" :key="item.hypothesis_id" class="hypothesis-item">
        <div class="hypothesis-head">
          <span class="hypothesis-statement">{{ item.statement || '—' }}</span>
          <a-tag :color="hypothesisStatusColor(item.status)">{{ hypothesisStatusLabel(item.status) }}</a-tag>
        </div>
        <div v-if="item.latest_assessment" class="hypothesis-assessment">
          <span class="assessment-meta">
            评估 r{{ item.latest_assessment.revision }} · <TimeText :value="item.latest_assessment.created_at" />
          </span>
          <p v-if="item.latest_assessment.reason_summary" class="assessment-reason">{{ item.latest_assessment.reason_summary }}</p>
          <div v-if="item.latest_assessment.evidence_relations?.length" class="assessment-relations">
            <a-tag
              v-for="(relation, index) in item.latest_assessment.evidence_relations" :key="index"
              :color="relation.relation === 'CONTRADICTS' ? 'green' : relation.relation === 'SUPPORTS' ? 'red' : 'default'"
              class="relation-chip" @click="emit('select-evidence', relation.evidence_id)">
              {{ relationLabel(relation.relation) }} · {{ shortId(relation.evidence_id) }}
            </a-tag>
          </div>
        </div>
      </div>
    </div>
  </a-card>
</template>

<script setup>
import { Empty } from 'ant-design-vue'
import TimeText from '../common/TimeText.vue'
import { hypothesisStatusColor, hypothesisStatusLabel, relationLabel } from '../../utils/copilot.js'

defineProps({ hypotheses: { type: Array, default: () => [] } })
const emit = defineEmits(['select-evidence'])

function shortId(id) { return id ? String(id).slice(0, 8) : '—' }
</script>

<style scoped>
.hypothesis-list { display: grid; gap: 12px; }
.hypothesis-item { padding: 12px 14px; border: 1px solid #e4ecf1; border-radius: 8px; background: #fff; }
.hypothesis-head { display: flex; align-items: baseline; gap: 8px; }
.hypothesis-statement { flex: 1; color: #1d3244; font-size: 14px; font-weight: 600; line-height: 1.6; }
.hypothesis-assessment { margin-top: 6px; padding-left: 2px; }
.assessment-meta { color: #93a3ad; font-size: 11px; }
.assessment-reason { margin: 6px 0 0; color: #5b7080; font-size: 13px; line-height: 1.6; }
.assessment-relations { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 8px; }
.relation-chip { margin: 0; cursor: pointer; }
</style>
