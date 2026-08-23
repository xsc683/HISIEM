<template><a-tag :color="color">{{ text }}</a-tag></template>

<script setup>
import { computed } from 'vue'
import { displayLabel, statusColor } from '../../utils/display.js'

const props = defineProps({ value: { type: [String, Number], default: '' }, group: { type: String, default: 'status' } })
const text = computed(() => displayLabel(props.group, props.value))
const groupColors = {
  severity: { low: 'blue', medium: 'gold', high: 'orange', critical: 'red' },
  criticality: { low: 'green', medium: 'gold', high: 'orange', extreme: 'red' },
  role: { admin: 'red', analyst: 'blue', ops: 'cyan', audit: 'purple' },
  verdict: { true_positive: 'red', false_positive: 'green', duplicate: 'orange' },
  category: { single_event: 'blue', window: 'purple', cep: 'magenta', baseline: 'cyan' },
}
const color = computed(() => groupColors[props.group]?.[props.value] || statusColor(props.value))
</script>
