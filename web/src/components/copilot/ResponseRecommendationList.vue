<template>
  <a-card class="surface-card" size="small" :title="`响应建议 (${items.length})`">
    <template #extra><a-tag color="default">只读 · 本阶段不执行</a-tag></template>
    <a-alert
      type="info" show-icon class="readonly-note"
      message="以下为 Agent 给出的处置建议，仅作研判参考。"
      description="当前版本不提供执行 / 阻断 / 隔离 / 审批入口；如需响应处置请在 SOAR 中另行发起。" />
    <a-empty v-if="!items.length" :image="Empty.PRESENTED_IMAGE_SIMPLE" description="未给出响应建议" />
    <ul v-else class="recommendation-list">
      <li v-for="(item, index) in items" :key="index">
        <div class="recommendation-desc">{{ item.description || '—' }}</div>
        <div v-if="item.reason" class="recommendation-reason"><strong>依据：</strong>{{ item.reason }}</div>
      </li>
    </ul>
  </a-card>
</template>

<script setup>
import { Empty } from 'ant-design-vue'

defineProps({ items: { type: Array, default: () => [] } })
</script>

<style scoped>
.readonly-note { margin-bottom: 12px; }
.recommendation-list { margin: 0; padding-left: 18px; }
.recommendation-list li { margin-bottom: 10px; }
.recommendation-list li:last-child { margin-bottom: 0; }
.recommendation-desc { color: #1d3244; line-height: 1.6; }
.recommendation-reason { margin-top: 4px; color: #5b7080; font-size: 12px; }
</style>
