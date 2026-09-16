<template>
  <a-card class="surface-card verdict-card" :class="`verdict-${disposition.toLowerCase()}`">
    <div class="verdict-head">
      <a-tag color="geekblue" class="verdict-authority" data-testid="verdict-authority">
        <RobotOutlined class="verdict-authority-icon" />
        {{ VERDICT_AUTHORITY_LABEL }}
      </a-tag>
      <a-tag :color="verdictColor(disposition)" class="verdict-tag" data-testid="verdict-disposition">
        {{ verdictLabel(disposition) }}
      </a-tag>
      <span class="verdict-confidence">置信度 {{ confidencePercent(confidence) }}</span>
      <span class="verdict-source">不可变 · 来自 InvestigationResult</span>
    </div>

    <p class="verdict-summary">{{ summary || '（未提供结论摘要）' }}</p>

    <!-- 权威不变量：这是 Agent 的结论，永远不等于「分析师处置」。 -->
    <p class="verdict-distinction" data-testid="verdict-distinction">
      {{ VERDICT_AUTHORITY_NOTE }}（{{ ANALYST_DISPOSITION_LABEL }}由人工在平台处置流程中另行记录，二者不同。）
    </p>

    <a-alert
      v-if="inconclusive"
      type="warning" show-icon class="verdict-uncertainty"
      message="证据不足以形成确定结论"
      description="本次调查未能收集到足以支撑恶意/正常判定的证据，请结合下方“不确定性”与已收集证据人工研判。" />

    <div class="verdict-findings">
      <div class="findings-head">
        <span class="findings-label">支撑本结论的发现</span>
        <a-tag color="default" data-testid="verdict-finding-count">{{ supporting.length }}</a-tag>
      </div>
      <a-empty
        v-if="!supporting.length" :image="Empty.PRESENTED_IMAGE_SIMPLE"
        description="结论未引用任何发现" />
      <ul v-else class="findings-list">
        <li v-for="finding in supporting" :key="finding.finding_id" class="findings-item">
          <span class="findings-statement">{{ finding.statement }}</span>
          <span class="findings-citations">
            <a-tag
              v-for="id in citationsOf(finding)" :key="id" color="blue" class="citation-chip"
              @click="emit('select-evidence', id)">
              {{ chipLabel(id) }}
            </a-tag>
            <a-tag v-if="!citationsOf(finding).length" color="warning" class="citation-chip">未引用证据</a-tag>
          </span>
        </li>
      </ul>
    </div>

    <div v-if="limitations.length" class="verdict-limitations">
      <span class="limitations-label">局限 / 不确定性</span>
      <ul>
        <li v-for="(item, index) in limitations" :key="index">
          {{ item.description || '—' }}
          <span v-if="item.missing_information" class="limitations-missing">（缺失信息：{{ item.missing_information }}）</span>
        </li>
      </ul>
    </div>
  </a-card>
</template>

<script setup>
import { computed } from 'vue'
import { Empty } from 'ant-design-vue'
import { RobotOutlined } from '@ant-design/icons-vue'
import {
  ANALYST_DISPOSITION_LABEL,
  VERDICT_AUTHORITY_LABEL,
  VERDICT_AUTHORITY_NOTE,
  confidencePercent,
  supportingFindings,
  verdictColor,
  verdictIsInconclusive,
  verdictLabel,
} from '../../utils/copilot.js'

const props = defineProps({
  verdict: { type: Object, required: true },
  findings: { type: Array, default: () => [] },
  result: { type: Object, default: null },
  evidenceById: { type: Object, default: () => ({}) },
})
const emit = defineEmits(['select-evidence'])

const disposition = computed(() => props.verdict?.disposition || 'INCONCLUSIVE')
const summary = computed(() => props.verdict?.summary || '')
const confidence = computed(() => props.verdict?.confidence)
const inconclusive = computed(() => verdictIsInconclusive(disposition.value))
const limitations = computed(() => props.result?.uncertainties || [])
// 只按持久 finding_id 解析结论引用的发现，绝不做文本匹配。
const supporting = computed(() => supportingFindings(props.result, props.findings))

function citationsOf(finding) {
  return Array.isArray(finding?.evidence_citations) ? finding.evidence_citations : []
}

function chipLabel(id) {
  const evidence = props.evidenceById[id]
  const text = evidence?.summary ? String(evidence.summary) : ''
  if (!text) return `证据 ${String(id).slice(0, 8)}`
  return text.length > 34 ? `${text.slice(0, 34)}…` : text
}
</script>

<style scoped>
.verdict-head { display: flex; align-items: center; flex-wrap: wrap; gap: 10px; }
.verdict-authority { display: inline-flex; align-items: center; gap: 5px; font-weight: 700; }
.verdict-authority-icon { font-size: 12px; }
.verdict-tag { padding-inline: 10px; font-size: 14px; font-weight: 700; }
.verdict-confidence { color: #3f5a6c; font-size: 13px; }
.verdict-source { margin-left: auto; color: #93a3ad; font-size: 11px; }
.verdict-summary { margin: 12px 0 0; color: #1d3244; font-size: 15px; line-height: 1.6; }
.verdict-distinction { margin: 6px 0 0; color: #80919d; font-size: 12px; line-height: 1.6; }
.verdict-uncertainty { margin-top: 12px; }
.verdict-findings { margin-top: 14px; }
.findings-head { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }
.findings-label { color: #2f5268; font-size: 13px; font-weight: 600; }
.findings-list { margin: 0; padding-left: 18px; display: grid; gap: 8px; }
.findings-statement { color: #1d3244; font-size: 13px; font-weight: 600; line-height: 1.6; }
.findings-citations { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 4px; }
.citation-chip { margin: 0; cursor: pointer; }
.verdict-limitations { margin-top: 14px; }
.limitations-label { color: #2f5268; font-size: 13px; font-weight: 600; }
.verdict-limitations ul { margin: 6px 0 0; padding-left: 18px; }
.verdict-limitations li { color: #5b7080; font-size: 13px; line-height: 1.6; }
.limitations-missing { color: #a8710f; }
.verdict-card.verdict-malicious { border-left: 4px solid #cf1322; }
.verdict-card.verdict-benign { border-left: 4px solid #389e0d; }
.verdict-card.verdict-inconclusive { border-left: 4px solid #d48806; }
</style>
