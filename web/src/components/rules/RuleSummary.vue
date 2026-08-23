<template>
  <div class="summary">
    <div>{{ conditionText }}</div>
    <div v-if="rule.category === 'window'" class="window">按 <code>{{ rule.keyField }}</code> 聚合 · {{ rule.windowMinutes }} 分钟 ≥ {{ rule.threshold }} 次<span v-if="rule.slidingMinutes"> · 每 {{ rule.slidingMinutes }} 分钟滑动</span></div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
const props = defineProps({ rule: { type: Object, required: true } })

function summarize(condition) {
  if (!condition) return '未配置条件'
  if (condition.type === 'field_equals') return `${condition.field} = ${String(condition.value)}`
  if (condition.type === 'field_in') return `${condition.field} ∈ [${(condition.values || []).join(', ')}]`
  const children = (condition.conditions || []).map(summarize)
  if (condition.type === 'not') return `NOT (${children[0] || '—'})`
  return children.join(condition.type === 'any' ? ' OR ' : ' AND ')
}

const conditionText = computed(() => summarize(props.rule.condition))
</script>

<style scoped>
.summary { min-width: 260px; max-width: 520px; color: #314b5f; font-size: 12px; line-height: 1.55; }
.window { margin-top: 3px; color: #1d6fa5; }
</style>
