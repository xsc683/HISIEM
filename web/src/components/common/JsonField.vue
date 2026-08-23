<template>
  <div>
    <a-textarea v-model:value="text" :rows="rows" class="json-field" @blur="commit" />
    <div v-if="error" class="danger-text" style="font-size: 12px; margin-top: 4px">{{ error }}</div>
  </div>
</template>
<script setup>
import { ref, watch } from 'vue'
const props = defineProps({ modelValue: { type: [Object, Array], default: () => ({}) }, rows: { type: Number, default: 7 } }); const emit = defineEmits(['update:modelValue']); const text = ref(''); const error = ref('')
watch(() => props.modelValue, (value) => { text.value = JSON.stringify(value ?? {}, null, 2) }, { immediate: true, deep: true })
function commit() { try { emit('update:modelValue', JSON.parse(text.value || '{}')); error.value = '' } catch { error.value = '请输入合法 JSON' } }
</script>
<style scoped>.json-field { font-family: 'Cascadia Code', Consolas, monospace; font-size: 12px; }</style>
