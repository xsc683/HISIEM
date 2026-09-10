<template>
  <a-card class="surface-card" size="small" :title="`工具活动 (${toolActivity.length})`">
    <a-empty v-if="!toolActivity.length" :image="Empty.PRESENTED_IMAGE_SIMPLE" description="尚无工具调用记录" />
    <a-table
      v-else :columns="columns" :data-source="toolActivity" :pagination="false"
      size="small" row-key="invocation_id" :scroll="{ x: 'max-content' }">
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'tool_name'"><code>{{ record.tool_name || '—' }}</code></template>
        <template v-else-if="column.key === 'status'">
          <a-tag :color="toolStatusColor(record.status)">{{ toolStatusLabel(record.status) }}</a-tag>
        </template>
        <template v-else-if="column.key === 'started_at'"><TimeText :value="record.started_at" /></template>
        <template v-else-if="column.key === 'duration'">{{ durationText(record.started_at, record.finished_at) }}</template>
        <template v-else-if="column.key === 'error'">
          <span v-if="record.error_code || record.safe_error_message" class="tool-error">
            {{ record.error_code || '' }}<template v-if="record.safe_error_message"> · {{ record.safe_error_message }}</template>
          </span>
          <span v-else>—</span>
        </template>
      </template>
    </a-table>
  </a-card>
</template>

<script setup>
import { Empty } from 'ant-design-vue'
import TimeText from '../common/TimeText.vue'
import { durationText, toolStatusColor, toolStatusLabel } from '../../utils/copilot.js'

defineProps({ toolActivity: { type: Array, default: () => [] } })

const columns = [
  { title: '工具', dataIndex: 'tool_name', key: 'tool_name' },
  { title: '状态', dataIndex: 'status', key: 'status', width: 100 },
  { title: '开始时间', dataIndex: 'started_at', key: 'started_at', width: 180 },
  { title: '耗时', key: 'duration', width: 110 },
  { title: '错误', key: 'error' },
]
</script>

<style scoped>
.tool-error { color: #cf1322; font-size: 12px; }
</style>
