<template>
  <a-form layout="vertical">
    <a-form-item label="分支标签（逗号分隔）" extra="连接分支时会按此顺序使用尚未连接的标签，发布至少需要两个分支。">
      <a-input :value="branches" placeholder="left, right" @change="patchBranches($event.target.value)" />
    </a-form-item>
    <a-form-item label="汇合节点" extra="只显示 JOIN 节点；所有分支路径都必须到达它。">
      <a-select :value="modelValue.joinNode" :options="joinOptions" placeholder="选择并行汇合节点" @change="patch('joinNode', $event)" />
    </a-form-item>
  </a-form>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({ modelValue: { type: Object, required: true }, nodes: { type: Array, default: () => [] } })
const emit = defineEmits(['update:modelValue'])
const branches = computed(() => (props.modelValue.branches || []).join(', '))
const joinOptions = computed(() => props.nodes.filter((node) => node.type === 'join')
  .map((node) => ({ value: node.id, label: `${node.name} · ${node.id}` })))
function patch(key, value) { emit('update:modelValue', { ...props.modelValue, [key]: value }) }
function patchBranches(value) { patch('branches', value.split(',').map((item) => item.trim().toLowerCase()).filter(Boolean)) }
</script>
