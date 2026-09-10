<template>
  <a-drawer
    :open="open" width="560" placement="right" title="证据详情"
    :body-style="{ paddingBottom: '24px' }" @close="emit('update:open', false)">
    <a-empty v-if="!evidence" :image="Empty.PRESENTED_IMAGE_SIMPLE" description="未选择证据" />
    <template v-else>
      <h3 class="drawer-summary">{{ evidence.summary || evidence.evidence_id }}</h3>
      <a-descriptions bordered size="small" :column="1" title="来源 / 溯源">
        <a-descriptions-item label="来源类型">{{ evidence.source?.type || '—' }}</a-descriptions-item>
        <a-descriptions-item label="提供方">{{ evidence.source?.provider || '—' }}</a-descriptions-item>
        <a-descriptions-item label="操作">{{ evidence.source?.operation || '—' }}</a-descriptions-item>
        <a-descriptions-item label="取证工具调用 ID"><code>{{ evidence.source_tool_invocation_id || '—' }}</code></a-descriptions-item>
        <a-descriptions-item label="原始引用"><span class="wrap">{{ evidence.raw_reference || '—' }}</span></a-descriptions-item>
      </a-descriptions>

      <a-descriptions bordered size="small" :column="1" title="时间" class="drawer-block">
        <a-descriptions-item label="观测时间"><TimeText :value="evidence.observed_at" /></a-descriptions-item>
        <a-descriptions-item label="采集时间"><TimeText :value="evidence.collected_at" /></a-descriptions-item>
      </a-descriptions>

      <a-descriptions bordered size="small" :column="1" title="完整性" class="drawer-block">
        <a-descriptions-item label="内容哈希"><code class="wrap">{{ evidence.content_hash || '—' }}</code></a-descriptions-item>
        <a-descriptions-item label="去重键"><code class="wrap">{{ evidence.dedup_key || '—' }}</code></a-descriptions-item>
      </a-descriptions>

      <a-descriptions v-if="evidence.entity_refs?.length" bordered size="small" :column="1" title="实体" class="drawer-block">
        <a-descriptions-item v-for="(ref, index) in evidence.entity_refs" :key="index" :label="ref.kind">{{ ref.value }}</a-descriptions-item>
      </a-descriptions>

      <div class="drawer-block">
        <h4 class="drawer-subtitle">观测内容</h4>
        <pre class="code-panel">{{ observationText }}</pre>
      </div>
    </template>
  </a-drawer>
</template>

<script setup>
import { computed } from 'vue'
import { Empty } from 'ant-design-vue'
import TimeText from '../common/TimeText.vue'

const props = defineProps({
  open: { type: Boolean, default: false },
  evidence: { type: Object, default: null },
})
const emit = defineEmits(['update:open'])

const observationText = computed(() => {
  const value = props.evidence?.observation
  if (value == null || value === '') return '—'
  if (typeof value === 'string') return value
  try { return JSON.stringify(value, null, 2) } catch { return String(value) }
})
</script>

<style scoped>
.drawer-summary { margin: 0 0 16px; color: #1d3244; font-size: 16px; line-height: 1.6; }
.drawer-block { margin-top: 20px; }
.drawer-subtitle { margin: 0 0 8px; color: #2f5268; font-size: 13px; font-weight: 600; }
.wrap { overflow-wrap: anywhere; }
.code-panel { max-height: 360px; }
</style>
