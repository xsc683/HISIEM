<template>
  <div class="condition-card">
    <template v-if="isLeaf">
      <a-space wrap>
        <a-tag color="blue">{{ operatorLabel }}</a-tag>
        <code>{{ condition.field }}</code>
        <strong>{{ condition.type === 'field_in' ? '属于' : '等于' }}</strong>
        <a-tag v-for="value in values" :key="String(value)">{{ value }}</a-tag>
      </a-space>
    </template>
    <template v-else>
      <a-tag :color="condition.type === 'all' ? 'cyan' : condition.type === 'any' ? 'purple' : 'orange'">{{ operatorLabel }}</a-tag>
      <div class="children">
        <RuleConditionTree v-for="(child, index) in children" :key="index" :condition="child" />
      </div>
    </template>
  </div>
</template>

<script setup>
import { computed } from 'vue'
defineOptions({ name: 'RuleConditionTree' })
const props = defineProps({ condition: { type: Object, required: true } })
const isLeaf = computed(() => ['field_equals', 'field_in'].includes(props.condition?.type))
const children = computed(() => props.condition?.conditions || [])
const values = computed(() => props.condition?.type === 'field_in' ? props.condition.values || [] : [props.condition?.value])
const operatorLabel = computed(() => ({ field_equals: '字段条件', field_in: '集合条件', all: '全部满足（AND）', any: '任一满足（OR）', not: '条件取反（NOT）' }[props.condition?.type] || props.condition?.type || '未知条件'))
</script>

<style scoped>
.children { display: grid; gap: 8px; margin: 9px 0 0 16px; }
.condition-card .condition-card { background: white; }
</style>
