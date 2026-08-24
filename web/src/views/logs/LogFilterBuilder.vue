<template>
  <div class="filter-builder">
    <div class="filter-toolbar">
      <span class="filter-label">条件关系</span>
      <a-radio-group :value="logic" button-style="solid" size="small" @update:value="$emit('update:logic', $event)">
        <a-radio-button v-for="option in LOGIC_OPTIONS" :key="option.value" :value="option.value">{{ option.value }}</a-radio-button>
      </a-radio-group>
      <span class="logic-help">{{ logicDescription }}</span>
      <a-button size="small" @click="addFilter"><PlusOutlined /> 添加条件</a-button>
    </div>

    <a-alert v-if="fieldsError" type="warning" show-icon :message="fieldsError" class="field-alert">
      <template #action><a-button size="small" @click="$emit('retry-fields')">重试</a-button></template>
    </a-alert>

    <div v-if="filters.length" class="filter-list">
      <div v-for="(filter, index) in filters" :key="filter.id" class="filter-row">
        <span class="condition-index">{{ index + 1 }}</span>
        <a-select
          :value="filter.field"
          show-search
          :loading="fieldsLoading"
          :disabled="fieldsLoading || !fieldOptions.length"
          :options="fieldOptions"
          :filter-option="filterField"
          placeholder="选择归一化字段"
          class="field-select"
          @update:value="changeField(index, $event)"
        >
          <template #option="option">
            <span>{{ option.label }}</span><span v-if="option.type" class="field-type">{{ option.type }}</span>
          </template>
        </a-select>
        <a-select
          :value="filter.operator"
          :options="operatorOptions(filter)"
          class="operator-select"
          @update:value="changeOperator(index, $event)"
        />
        <span v-if="!operatorMeta(filter.operator).needsValue" class="no-value-hint">无需填写值</span>
        <a-select
          v-else-if="operatorMeta(filter.operator).multiple"
          mode="tags"
          :value="Array.isArray(filter.value) ? filter.value : []"
          :token-separators="[',']"
          placeholder="输入值并回车，可填写多个"
          class="value-input"
          @update:value="patchFilter(index, 'value', $event)"
        />
        <a-input
          v-else
          :value="filter.value"
          allow-clear
          placeholder="输入匹配值"
          class="value-input"
          @update:value="patchFilter(index, 'value', $event)"
          @press-enter="$emit('search')"
        />
        <a-button type="text" danger aria-label="删除筛选条件" @click="removeFilter(index)"><DeleteOutlined /></a-button>
      </div>
    </div>
    <a-empty v-else :image="simpleImage" description="未添加筛选条件，将检索时间范围内的全部日志" class="filter-empty" />
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { Empty } from 'ant-design-vue'
import { DeleteOutlined, PlusOutlined } from '@ant-design/icons-vue'
import { createEmptyFilter, LOGIC_OPTIONS, OPERATOR_OPTIONS, operatorMeta } from './logSearchQuery.js'

const props = defineProps({
  filters: { type: Array, required: true },
  logic: { type: String, required: true },
  fieldOptions: { type: Array, default: () => [] },
  fieldsLoading: Boolean,
  fieldsError: { type: String, default: '' },
})
const emit = defineEmits(['update:filters', 'update:logic', 'retry-fields', 'search'])
const simpleImage = Empty.PRESENTED_IMAGE_SIMPLE
const logicDescription = computed(() => props.logic === 'OR' ? '任意一条条件成立即可' : '每一条条件都必须成立')

function update(filters) {
  emit('update:filters', filters)
}
function addFilter() {
  update([...props.filters, createEmptyFilter()])
}
function removeFilter(index) {
  update(props.filters.filter((_, itemIndex) => itemIndex !== index))
}
function patchFilter(index, key, value) {
  update(props.filters.map((filter, itemIndex) => itemIndex === index ? { ...filter, [key]: value } : filter))
}
function operatorOptions(filter) {
  const field = props.fieldOptions.find((option) => option.value === filter.field)
  if (!field?.operators?.length) return OPERATOR_OPTIONS
  return OPERATOR_OPTIONS.filter((option) => field.operators.includes(option.value))
}
function changeField(index, fieldName) {
  const current = props.filters[index]
  const field = props.fieldOptions.find((option) => option.value === fieldName)
  const available = field?.operators?.length
    ? OPERATOR_OPTIONS.filter((option) => field.operators.includes(option.value))
    : OPERATOR_OPTIONS
  const operator = available.some((option) => option.value === current.operator)
    ? current.operator : available[0]?.value || 'is'
  const value = operator === current.operator ? current.value : operatorMeta(operator).multiple ? [] : ''
  update(props.filters.map((filter, itemIndex) => itemIndex === index
    ? { ...filter, field: fieldName, operator, value }
    : filter))
}
function changeOperator(index, operator) {
  const value = operatorMeta(operator).multiple ? [] : ''
  update(props.filters.map((filter, itemIndex) => itemIndex === index ? { ...filter, operator, value } : filter))
}
function filterField(input, option) {
  return `${option.label} ${option.value} ${option.type || ''}`.toLowerCase().includes(input.toLowerCase())
}
</script>

<style scoped>
.filter-builder { display: flex; flex-direction: column; gap: 12px; }
.filter-toolbar { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.filter-label { color: #526879; font-weight: 600; }
.logic-help { margin-right: auto; color: #718294; font-size: 12px; }
.field-alert { margin-top: 2px; }
.filter-list { display: flex; flex-direction: column; gap: 8px; }
.filter-row { display: grid; grid-template-columns: 24px minmax(220px, 1fr) 205px minmax(220px, 1.4fr) 32px; align-items: center; gap: 8px; }
.condition-index { display: inline-flex; align-items: center; justify-content: center; width: 22px; height: 22px; border-radius: 50%; background: #e9f2f8; color: #3275a5; font-size: 12px; font-weight: 700; }
.field-select, .operator-select, .value-input { width: 100%; }
.field-type { float: right; margin-left: 12px; color: #8a99a6; font-size: 11px; }
.no-value-hint { display: flex; align-items: center; min-height: 32px; padding: 0 11px; border: 1px dashed #cbd7df; border-radius: 6px; color: #718294; background: #f8fafb; }
.filter-empty { margin-block: 4px; }
@media (max-width: 1300px) {
  .filter-row { grid-template-columns: 24px minmax(190px, 1fr) 180px minmax(190px, 1fr) 32px; }
}
@media (max-width: 900px) {
  .filter-list { gap: 10px; }
  .filter-row { grid-template-columns: 24px minmax(0, 1fr) 32px; align-items: start; padding: 10px; border: 1px solid #e2e9ed; border-radius: 8px; background: #fbfcfd; }
  .condition-index { grid-column: 1; grid-row: 1; margin-top: 5px; }
  .field-select { grid-column: 2; grid-row: 1; }
  .operator-select { grid-column: 2; grid-row: 2; }
  .value-input, .no-value-hint { grid-column: 2; grid-row: 3; }
  .filter-row > .ant-btn { grid-column: 3; grid-row: 1; }
}
@media (max-width: 560px) {
  .filter-toolbar { align-items: center; }
  .logic-help { width: 100%; margin: -2px 0 0; padding-left: 1px; order: 4; }
  .filter-toolbar > .ant-btn { margin-left: auto; }
}
</style>
