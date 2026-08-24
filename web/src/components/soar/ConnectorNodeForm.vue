<template>
  <a-form layout="vertical">
    <a-form-item label="连接器"><a-select :value="modelValue.runtimeKey" :options="[{ value: 'http', label: '通用 HTTP' }]" @change="patch('runtimeKey', $event)" /></a-form-item>
    <a-form-item label="动作"><a-select :value="modelValue.action" :options="methods" @change="patch('action', $event)" /></a-form-item>
    <a-form-item label="URL"><a-input :value="parameters.url" placeholder="https://api.example.com/action" @change="patchParameter('url', $event.target.value)" /></a-form-item>
    <a-form-item label="请求体" extra="字符串会原样发送；敏感字段不会写入节点审计输入。"><a-textarea :value="parameters.body" :rows="4" @change="patchParameter('body', $event.target.value)" /></a-form-item>
    <a-form-item label="超时（毫秒）"><a-input-number :value="modelValue.timeoutMs" :min="1" :max="120000" @change="patch('timeoutMs', $event)" /></a-form-item>
  </a-form>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({ modelValue: { type: Object, required: true } })
const emit = defineEmits(['update:modelValue'])
const methods = ['GET', 'POST', 'PUT', 'PATCH', 'DELETE'].map((value) => ({ value, label: value }))
const parameters = computed(() => props.modelValue.parameters || {})
function patch(key, value) { emit('update:modelValue', { ...props.modelValue, [key]: value }) }
function patchParameter(key, value) { patch('parameters', { ...parameters.value, [key]: value }) }
</script>
