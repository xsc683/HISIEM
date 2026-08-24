<template><a-tag :color="color" class="status-tag"><span class="status-dot" />{{ text }}</a-tag></template>

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

<style scoped>
.status-tag { display: inline-flex; min-height: 22px; align-items: center; gap: 5px; padding-inline: 7px; font-size: 11px; font-weight: 600; line-height: 20px; }
.status-dot { width: 5px; height: 5px; flex: 0 0 5px; border-radius: 50%; background: currentColor; opacity: .8; }
</style>
