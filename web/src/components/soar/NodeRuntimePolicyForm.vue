<template>
  <a-divider orientation="left">运行策略</a-divider>
  <a-form layout="vertical">
    <a-form-item label="最大执行次数" extra="包含首次执行；自动值由节点 Handler 决定。">
      <a-select :value="policy.maxAttempts" :options="attemptOptions" @change="patch('maxAttempts', $event)" />
    </a-form-item>
    <a-row :gutter="10">
      <a-col :span="12"><a-form-item label="初始退避（秒）"><a-input-number :value="policy.initialDelaySeconds" :min="1" :max="3600" style="width:100%" @change="patch('initialDelaySeconds', $event)" /></a-form-item></a-col>
      <a-col :span="12"><a-form-item label="最大退避（秒）"><a-input-number :value="policy.maxDelaySeconds" :min="policy.initialDelaySeconds" :max="3600" style="width:100%" @change="patch('maxDelaySeconds', $event)" /></a-form-item></a-col>
    </a-row>
    <a-form-item label="指数退避倍率">
      <a-input-number :value="policy.backoffMultiplier" :min="1" :max="10" :step="0.5" style="width:100%" @change="patch('backoffMultiplier', $event)" />
    </a-form-item>
  </a-form>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({ modelValue: { type: Object, default: null }, nodeType: { type: String, required: true } })
const emit = defineEmits(['update:modelValue'])
const fallback = { maxAttempts: 0, initialDelaySeconds: 2, backoffMultiplier: 2, maxDelaySeconds: 60 }
const policy = computed(() => ({ ...fallback, ...(props.modelValue || {}) }))
const automaticAttempts = computed(() => props.nodeType === 'business' ? 3 : 1)
const attemptOptions = computed(() => [
  { value: 0, label: `自动（当前 ${automaticAttempts.value} 次）` },
  ...[1, 2, 3, 5, 10].map((value) => ({ value, label: `${value} 次` })),
])

function patch(key, value) {
  const next = { ...policy.value, [key]: value }
  if (key === 'initialDelaySeconds' && next.maxDelaySeconds < value) next.maxDelaySeconds = value
  emit('update:modelValue', next)
}
</script>
