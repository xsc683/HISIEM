<template>
  <a-card class="surface-card" size="small" title="调查计划">
    <template v-if="planRevisions.length > 1" #extra>
      <a-select
        :value="selectedId" size="small" style="width: 150px" :options="revisionOptions"
        @change="(value) => (selectedId = value)" />
    </template>
    <a-empty v-if="!planRevisions.length" :image="Empty.PRESENTED_IMAGE_SIMPLE" description="尚未生成调查计划" />
    <template v-else-if="active">
      <div class="plan-meta">
        第 {{ active.revision }} 版 · 生成方 {{ active.generated_by || '—' }} ·
        <TimeText :value="active.created_at" />
      </div>
      <a-table
        :columns="columns" :data-source="active.steps || []" :pagination="false"
        size="small" row-key="step_id" class="plan-table">
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'status'">
            <a-tag :color="stepColor(record.status)">{{ record.status || '—' }}</a-tag>
          </template>
        </template>
      </a-table>
    </template>
  </a-card>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { Empty } from 'ant-design-vue'
import TimeText from '../common/TimeText.vue'

const props = defineProps({
  planRevisions: { type: Array, default: () => [] },
  currentPlanRevision: { type: [Number, String], default: null },
})
const selectedId = ref('')

const columns = [
  { title: '序号', dataIndex: 'ordinal', key: 'ordinal', width: 64 },
  { title: '目标', dataIndex: 'objective', key: 'objective' },
  { title: '状态', dataIndex: 'status', key: 'status', width: 120 },
]

watch(() => props.planRevisions, (list) => {
  if (!list?.length) { selectedId.value = ''; return }
  if (list.some((revision) => revision.id === selectedId.value)) return
  const current = list.find((revision) => revision.revision === props.currentPlanRevision) || list[list.length - 1]
  selectedId.value = current.id
}, { immediate: true })

const active = computed(() => {
  const list = props.planRevisions || []
  return list.find((revision) => revision.id === selectedId.value) || list[list.length - 1] || null
})
const revisionOptions = computed(() => props.planRevisions.map((revision) => ({ value: revision.id, label: `第 ${revision.revision} 版` })))

function stepColor(status) {
  if (status === 'DONE' || status === 'COMPLETED' || status === 'SUCCEEDED') return 'green'
  if (status === 'ACTIVE' || status === 'RUNNING') return 'blue'
  if (status === 'SKIPPED') return 'default'
  return 'default'
}
</script>

<style scoped>
.plan-meta { margin-bottom: 10px; color: #7c8b96; font-size: 12px; }
.plan-table { margin-top: 4px; }
</style>
