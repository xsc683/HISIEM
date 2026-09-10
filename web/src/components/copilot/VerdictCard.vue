<template>
  <a-card class="surface-card verdict-card" :class="`verdict-${disposition.toLowerCase()}`">
    <div class="verdict-head">
      <a-tag :color="verdictColor(disposition)" class="verdict-tag">{{ verdictLabel(disposition) }}</a-tag>
      <span class="verdict-confidence">置信度 {{ confidencePercent(confidence) }}</span>
      <span class="verdict-source">Agent 结论 · 不可变</span>
    </div>
    <p class="verdict-summary">{{ summary || '（未提供结论摘要）' }}</p>
    <a-alert
      v-if="disposition === 'INCONCLUSIVE'"
      type="warning" show-icon class="verdict-uncertainty"
      message="证据不足以形成确定结论"
      description="本次调查未能收集到足以支撑恶意/正常判定的证据，请结合下方“不确定性”与已收集证据人工研判。" />
  </a-card>
</template>

<script setup>
import { computed } from 'vue'
import { confidencePercent, verdictColor, verdictLabel } from '../../utils/copilot.js'

const props = defineProps({ verdict: { type: Object, required: true } })
const disposition = computed(() => props.verdict?.disposition || 'INCONCLUSIVE')
const summary = computed(() => props.verdict?.summary || '')
const confidence = computed(() => props.verdict?.confidence)
</script>

<style scoped>
.verdict-head { display: flex; align-items: center; gap: 10px; }
.verdict-tag { padding-inline: 10px; font-size: 14px; font-weight: 700; }
.verdict-confidence { color: #3f5a6c; font-size: 13px; }
.verdict-source { margin-left: auto; color: #93a3ad; font-size: 11px; }
.verdict-summary { margin: 12px 0 0; color: #1d3244; font-size: 15px; line-height: 1.6; }
.verdict-uncertainty { margin-top: 12px; }
.verdict-card.verdict-malicious { border-left: 4px solid #cf1322; }
.verdict-card.verdict-benign { border-left: 4px solid #389e0d; }
.verdict-card.verdict-inconclusive { border-left: 4px solid #d48806; }
</style>
