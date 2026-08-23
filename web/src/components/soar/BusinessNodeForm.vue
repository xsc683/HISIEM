<template>
  <a-form layout="vertical">
    <a-form-item label="平台业务动作" required>
      <a-select :value="modelValue.action" show-search placeholder="选择动作" :options="actionOptions" @change="selectAction" />
    </a-form-item>
    <template v-if="selected">
      <a-form-item v-for="parameter in selected.parameters" :key="parameter.id" :label="parameter.label" :required="parameter.required">
        <a-select v-if="parameter.type === 'select'" :value="parameters[parameter.id]" :options="parameter.options.map((value) => ({ value, label: value }))" @change="setParameter(parameter.id, $event)" />
        <a-input v-else :value="parameters[parameter.id]" placeholder="固定值或 ${alert.id} / ${case.id}" @change="setParameter(parameter.id, $event.target.value)" />
      </a-form-item>
    </template>
  </a-form>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({ modelValue: { type: Object, required: true }, actions: { type: Array, default: () => [] } })
const emit = defineEmits(['update:modelValue'])
const selected = computed(() => props.actions.find((action) => action.id === props.modelValue.action))
const parameters = computed(() => props.modelValue.parameters || {})
const actionOptions = computed(() => props.actions.map((action) => ({ value: action.id, label: `${action.label} · ${action.id}` })))

function selectAction(action) {
  const definition = props.actions.find((item) => item.id === action)
  const defaults = {}
  ;(definition?.parameters || []).forEach((parameter) => { defaults[parameter.id] = parameter.options?.[0] || '' })
  emit('update:modelValue', { action, parameters: defaults })
}
function setParameter(key, value) { emit('update:modelValue', { ...props.modelValue, parameters: { ...parameters.value, [key]: value } }) }
</script>
