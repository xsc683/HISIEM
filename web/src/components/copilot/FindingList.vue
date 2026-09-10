<template>
  <a-card class="surface-card" size="small" :title="`发现 (${findings.length})`">
    <a-empty v-if="!findings.length" :image="Empty.PRESENTED_IMAGE_SIMPLE" description="尚未形成发现" />
    <div v-else class="finding-list">
      <FindingCard
        v-for="(finding, index) in findings" :key="finding.finding_id"
        :finding="finding" :ordinal="index + 1" :evidence-by-id="evidenceById"
        @select-evidence="(id) => emit('select-evidence', id)" />
    </div>
  </a-card>
</template>

<script setup>
import { Empty } from 'ant-design-vue'
import FindingCard from './FindingCard.vue'

defineProps({
  findings: { type: Array, default: () => [] },
  evidenceById: { type: Object, default: () => ({}) },
})
const emit = defineEmits(['select-evidence'])
</script>

<style scoped>
.finding-list { display: grid; gap: 10px; }
</style>
