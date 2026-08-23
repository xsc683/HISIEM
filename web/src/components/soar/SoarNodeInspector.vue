<template>
  <div v-if="node" class="node-inspector">
    <div class="inspector-heading"><div><span>{{ typeLabel[node.type] }}</span><strong>{{ node.name }}</strong></div><a-button v-if="!locked" danger type="text" @click="$emit('delete')">删除节点</a-button></div>
    <a-form layout="vertical">
      <a-form-item label="节点名称"><a-input :value="node.name" :disabled="locked" @change="patchName($event.target.value)" /></a-form-item>
      <a-form-item label="节点 ID"><a-input :value="node.id" disabled /></a-form-item>
    </a-form>
    <ConditionNodeForm v-if="node.type === 'condition'" :model-value="node.config" :fields="fields" @update:model-value="patchConfig" />
    <BusinessNodeForm v-else-if="node.type === 'business'" :model-value="node.config" :actions="actions" @update:model-value="patchConfig" />
    <HumanNodeForm v-else-if="node.type === 'human'" :model-value="node.config" @update:model-value="patchConfig" />
    <WaitNodeForm v-else-if="node.type === 'wait'" :model-value="node.config" @update:model-value="patchConfig" />
    <a-alert v-else type="info" show-icon :message="locked ? '开始/结束节点由系统维护，不能删除或配置。' : '选择节点配置。'" />
    <NodeRuntimePolicyForm v-if="!locked" :model-value="node.policy" :node-type="node.type" @update:model-value="patchPolicy" />
  </div>
  <a-empty v-else description="选择一个节点查看配置" />
</template>

<script setup>
import { computed } from 'vue'
import BusinessNodeForm from './BusinessNodeForm.vue'
import ConditionNodeForm from './ConditionNodeForm.vue'
import HumanNodeForm from './HumanNodeForm.vue'
import NodeRuntimePolicyForm from './NodeRuntimePolicyForm.vue'
import WaitNodeForm from './WaitNodeForm.vue'

const props = defineProps({ node: { type: Object, default: null }, fields: { type: Array, default: () => [] }, actions: { type: Array, default: () => [] } })
const emit = defineEmits(['update', 'delete'])
const locked = computed(() => ['start', 'end'].includes(props.node?.type))
const typeLabel = { start: '开始', end: '结束', condition: '条件判断', business: '业务动作', human: '人工审批', wait: '等待' }
function patchName(name) { emit('update', { ...props.node, name }) }
function patchConfig(config) { emit('update', { ...props.node, config }) }
function patchPolicy(policy) { emit('update', { ...props.node, policy }) }
</script>

<style scoped>
.inspector-heading { display:flex; justify-content:space-between; align-items:start; margin-bottom:14px; }
.inspector-heading span, .inspector-heading strong { display:block; }
.inspector-heading span { color:#758795; font-size:11px; text-transform:uppercase; }
.inspector-heading strong { margin-top:3px; color:#18354a; font-size:16px; }
</style>
