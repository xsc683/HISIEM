<template>
  <a-form layout="vertical">
    <a-form-item label="循环体起点"><a-select :value="modelValue.bodyStart" :options="startOptions" placeholder="选择循环体第一个节点" @change="patch('bodyStart', $event)" /></a-form-item>
    <a-form-item label="循环体终点" extra="只显示 LOOP END 节点。"><a-select :value="modelValue.bodyEnd" :options="endOptions" placeholder="选择循环体结束节点" @change="patch('bodyEnd', $event)" /></a-form-item>
    <a-form-item label="迭代项（每行一项）"><a-textarea :value="items" :rows="5" @change="patchItems($event.target.value)" /></a-form-item>
    <a-form-item label="最大迭代次数" extra="1–1000，且不能小于迭代项数量。"><a-input-number :value="modelValue.maxIterations" :min="1" :max="1000" @change="patch('maxIterations', $event)" /></a-form-item>
  </a-form>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({ modelValue: { type: Object, required: true }, nodes: { type: Array, default: () => [] } })
const emit = defineEmits(['update:modelValue'])
const items = computed(() => (props.modelValue.items || []).join('\n'))
const startOptions = computed(() => props.nodes.filter((node) => !['start', 'end', 'loop', 'loop_end'].includes(node.type))
  .map((node) => ({ value: node.id, label: `${node.name} · ${node.id}` })))
const endOptions = computed(() => props.nodes.filter((node) => node.type === 'loop_end')
  .map((node) => ({ value: node.id, label: `${node.name} · ${node.id}` })))
function patch(key, value) { emit('update:modelValue', { ...props.modelValue, [key]: value }) }
function patchItems(value) { patch('items', value.split(/\r?\n/).map((item) => item.trim()).filter(Boolean)) }
</script>
