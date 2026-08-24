<template>
  <div class="condition-form">
    <a-alert type="info" show-icon message="所有条件使用 AND 组合；字段只能来自生命周期字典。" />
    <div v-for="(condition, index) in conditions" :key="index" class="condition-row">
      <a-select :value="condition.field" placeholder="选择字段" :options="fieldOptions" @change="changeField(index, $event)" />
      <a-select :value="condition.operator" placeholder="操作符" :options="operatorOptions(condition.field)" @change="patch(index, { operator: $event })" />
      <a-input-number v-if="fieldType(condition.field) === 'number' && needsValue(condition.operator)" :value="condition.value" style="width:100%" @change="patch(index, { value: $event })" />
      <a-input v-else-if="needsValue(condition.operator)" :value="condition.value" placeholder="比较值，可使用 ${...}" @change="patch(index, { value: $event.target.value })" />
      <span v-else class="muted no-value">无需比较值</span>
      <a-button danger type="text" :disabled="conditions.length === 1" @click="remove(index)">删除</a-button>
    </div>
    <a-button block :disabled="conditions.length >= 10" @click="add">添加 AND 条件</a-button>
  </div>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({ modelValue: { type: Object, required: true }, fields: { type: Array, default: () => [] } })
const emit = defineEmits(['update:modelValue'])
const conditions = computed(() => props.modelValue.conditions || [])
const fieldOptions = computed(() => props.fields.map((field) => ({ value: field.path, label: `${field.label} · ${field.path}` })))
const definition = (path) => props.fields.find((field) => field.path === path)
const operatorOptions = (path) => (definition(path)?.operators || []).map((operator) => ({ value: operator.id, label: operator.label }))
const fieldType = (path) => definition(path)?.type
const needsValue = (operator) => !['is_empty', 'not_empty'].includes(operator)

function update(next) { emit('update:modelValue', { ...props.modelValue, mode: 'AND', conditions: next }) }
function patch(index, value) { update(conditions.value.map((item, position) => position === index ? { ...item, ...value } : item)) }
function changeField(index, field) {
  const operator = definition(field)?.operators?.[0]?.id || 'eq'
  patch(index, { field, operator, value: '' })
}
function add() {
  const field = props.fields[0]
  update([...conditions.value, { field: field?.path || '', operator: field?.operators?.[0]?.id || 'eq', value: '' }])
}
function remove(index) { update(conditions.value.filter((_, position) => position !== index)) }
</script>

<style scoped>
.condition-form { display: flex; flex-direction: column; gap: 10px; }
.condition-row { display: grid; grid-template-columns: minmax(0, 1fr) auto; gap: 7px; align-items: center; padding: 9px; border: 1px solid #e1e8ec; border-radius: 8px; background: #fbfcfd; }
.condition-row > .ant-select:first-child { grid-column: 1 / -1; }
.condition-row > .ant-select:nth-child(2), .condition-row > .ant-input,
.condition-row > .ant-input-number, .condition-row > .no-value { grid-column: 1; }
.condition-row > .ant-btn { grid-column: 2; grid-row: 2 / span 2; align-self: start; }
.no-value { padding-left: 8px; }
</style>
