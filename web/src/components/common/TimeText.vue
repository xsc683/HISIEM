<template><span :title="value || ''">{{ formatted }}</span></template>

<script setup>
import { computed } from 'vue'
import { formatTime } from '../../utils/display.js'
const props = defineProps({ value: { type: String, default: '' } })
const formatted = computed(() => formatTime(props.value))
</script>
