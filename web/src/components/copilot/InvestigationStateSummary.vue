<template>
  <a-card class="surface-card state-summary" size="small">
    <div class="summary-grid">
      <div class="summary-cell">
        <span class="summary-label">证据</span>
        <span class="summary-value" data-testid="summary-evidence">{{ summary.evidenceCount }}</span>
        <span class="summary-sub">
          平台事实 {{ summary.platformCount }} · 支持性上下文 {{ summary.contextCount }}
        </span>
      </div>
      <div class="summary-cell">
        <span class="summary-label">发现</span>
        <span class="summary-value" data-testid="summary-findings">{{ summary.findingCount }}</span>
        <span class="summary-sub">已纳入结论的发现见下方“发现”</span>
      </div>
      <div class="summary-cell">
        <span class="summary-label">{{ VERDICT_AUTHORITY_LABEL }}</span>
        <span class="summary-value" :class="`verdict-${(summary.verdictDisposition || 'none').toLowerCase()}`" data-testid="summary-verdict">
          {{ summary.verdictText }}
        </span>
        <span class="summary-sub">置信度 {{ summary.confidenceText }}</span>
      </div>
      <div class="summary-cell summary-response">
        <span class="summary-label">响应</span>
        <template v-if="summary.response">
          <span class="summary-value" data-testid="summary-response-stage">{{ summary.response.stage.label }}</span>
          <span class="summary-sub">
            {{ summary.response.stage.status }}<template v-if="summary.response.stage.detail"> · {{ summary.response.stage.detail }}</template>
          </span>
        </template>
        <template v-else>
          <span class="summary-value" data-testid="summary-response-stage">—</span>
          <span class="summary-sub">本次调查尚无响应提案</span>
        </template>
      </div>
    </div>

    <a-alert
      class="summary-action"
      :type="actionType"
      show-icon
      :data-action-kind="summary.action.kind"
      data-testid="summary-action">
      <template #message>
        <component :is="actionIcon" class="action-icon" />
        <span data-testid="summary-action-label">{{ summary.action.label }}</span>
      </template>
      <template #description>{{ summary.action.description }}</template>
    </a-alert>
  </a-card>
</template>

<script setup>
import { computed } from 'vue'
import {
  CheckCircleOutlined,
  ClockCircleOutlined,
  ExclamationCircleOutlined,
  PlayCircleOutlined,
} from '@ant-design/icons-vue'
import { ANALYST_ACTION, VERDICT_AUTHORITY_LABEL, workspaceStateSummary } from '../../utils/copilot.js'

// Landing 摘要只消费服务端已给出的持久状态：它汇总事实，绝不推导业务状态。
// 「需要分析师处理」由 utils/copilot.js 的 analystAction 依据持久状态判定，页面不参与。
const props = defineProps({
  status: { type: String, default: '' },
  evidence: { type: Array, default: () => [] },
  findings: { type: Array, default: () => [] },
  result: { type: Object, default: null },
  proposals: { type: Array, default: () => [] },
  canDecide: { type: Boolean, default: false },
})

const summary = computed(() => workspaceStateSummary({
  status: props.status,
  evidence: props.evidence,
  findings: props.findings,
  result: props.result,
  proposals: props.proposals,
  canDecide: props.canDecide,
}))

const ACTION_ICONS = {
  [ANALYST_ACTION.SUBMISSION_ATTENTION_REQUIRED]: ExclamationCircleOutlined,
  [ANALYST_ACTION.APPROVAL_REQUIRED]: ExclamationCircleOutlined,
  [ANALYST_ACTION.APPROVAL_PENDING_OTHERS]: ClockCircleOutlined,
  [ANALYST_ACTION.INVESTIGATION_RUNNING]: PlayCircleOutlined,
  [ANALYST_ACTION.PROPOSAL_OPTIONAL]: CheckCircleOutlined,
  [ANALYST_ACTION.NONE]: CheckCircleOutlined,
}
// 「需要我处理」用 warning 突出，「等待他人 / 进行中 / 无需操作」保持中性，不用颜色制造焦虑。
const ACTION_TYPES = {
  [ANALYST_ACTION.SUBMISSION_ATTENTION_REQUIRED]: 'warning',
  [ANALYST_ACTION.APPROVAL_REQUIRED]: 'warning',
  [ANALYST_ACTION.APPROVAL_PENDING_OTHERS]: 'info',
  [ANALYST_ACTION.INVESTIGATION_RUNNING]: 'info',
  [ANALYST_ACTION.PROPOSAL_OPTIONAL]: 'info',
  [ANALYST_ACTION.NONE]: 'info',
}

const actionIcon = computed(() => ACTION_ICONS[summary.value.action.kind] || CheckCircleOutlined)
const actionType = computed(() => ACTION_TYPES[summary.value.action.kind] || 'info')
</script>

<style scoped>
.state-summary { display: grid; gap: 12px; }
.summary-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 14px; }
.summary-cell { display: grid; gap: 2px; min-width: 0; }
.summary-label { color: #80919d; font-size: 12px; }
.summary-value { color: #1d3244; font-size: 18px; font-weight: 700; line-height: 1.3; }
.summary-value.verdict-malicious { color: #cf1322; }
.summary-value.verdict-benign { color: #389e0d; }
.summary-value.verdict-inconclusive { color: #d48806; }
.summary-sub { color: #7c8b96; font-size: 11px; line-height: 1.5; overflow-wrap: anywhere; }
.summary-action { margin: 0; }
.action-icon { margin-right: 6px; }

/* 窄屏：摘要由四列降为两列，再降为单列，避免任何一列被压到不可读。 */
@media (max-width: 992px) {
  .summary-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
@media (max-width: 576px) {
  .summary-grid { grid-template-columns: minmax(0, 1fr); }
}
</style>
