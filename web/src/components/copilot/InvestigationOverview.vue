<template>
  <div class="overview">
    <VerdictCard v-if="result?.verdict" :verdict="result.verdict" />
    <a-card v-else class="surface-card" size="small">
      <a-alert
        :type="running ? 'info' : 'warning'" show-icon
        :message="running ? '调查进行中，结论尚未生成' : '本次调查未产出结论'"
        :description="running
          ? 'Agent 正在收集证据并形成结论，页面会在调查进行中自动刷新。'
          : '调查已结束但未生成最终结论（例如失败或取消）。下方展示目前已收集的事实。'" />
    </a-card>

    <FindingList :findings="findings" :evidence-by-id="evidenceById" @select-evidence="(id) => emit('select-evidence', id)" />

    <template v-if="result">
      <UncertaintyList :items="result.uncertainties || []" />
      <AttackMappingList :items="result.attack_mappings || []" />
      <ResponseRecommendationList :items="result.response_recommendations || []" />
    </template>
  </div>
</template>

<script setup>
import VerdictCard from './VerdictCard.vue'
import FindingList from './FindingList.vue'
import UncertaintyList from './UncertaintyList.vue'
import AttackMappingList from './AttackMappingList.vue'
import ResponseRecommendationList from './ResponseRecommendationList.vue'

defineProps({
  result: { type: Object, default: null },
  findings: { type: Array, default: () => [] },
  evidenceById: { type: Object, default: () => ({}) },
  running: { type: Boolean, default: false },
})
const emit = defineEmits(['select-evidence'])
</script>

<style scoped>
.overview { display: grid; gap: 16px; }
</style>
