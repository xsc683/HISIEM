<template>
  <a-card class="surface-card" size="small" title="创建响应提案">
    <a-alert
      type="warning" show-icon class="bounded-banner"
      message="有界提案：目标与动作都不可编辑"
      description="动作固定为 START_SOAR_PLAYBOOK；执行目标由服务端从本调查的来源告警派生，浏览器不提交目标、租户或操作人。提案本身不会执行任何动作，必须再经人工审批。" />

    <a-form layout="vertical" class="proposal-form">
      <a-form-item label="响应动作">
        <a-input :value="RESPONSE_PROPOSAL_ACTION_KEY" data-testid="proposal-action" readonly disabled />
        <p class="field-hint">{{ responseActionLabel(RESPONSE_PROPOSAL_ACTION_KEY) }}（唯一可选的执行动作）</p>
      </a-form-item>

      <a-form-item label="执行目标（来自本调查的来源告警，只读）">
        <a-input :value="targetText" data-testid="proposal-target" readonly disabled />
        <p class="field-hint">目标不接受手工输入；服务端会用告警提供方与资源标识复核后才落库。</p>
      </a-form-item>

      <a-form-item label="SOAR 剧本">
        <a-select
          v-model:value="playbookId"
          data-testid="proposal-playbook"
          popup-class-name="proposal-playbook-dropdown"
          placeholder="选择一个已发布且启用的剧本"
          :options="playbookOptions"
          :loading="playbooksLoading"
          :disabled="submitting"
          show-search
          option-filter-prop="label"
          not-found-content="没有已发布且启用的剧本可供选择" />
        <p v-if="playbooksError" class="field-error">剧本列表加载失败：{{ playbooksError }}</p>
        <p v-else class="field-hint">仅列出已发布且已启用的剧本，没有自由文本输入。</p>
      </a-form-item>

      <a-form-item label="引用证据（至少一条，只能来自本次调查已收集的证据）">
        <a-select
          v-model:value="evidenceIds"
          data-testid="proposal-evidence"
          popup-class-name="proposal-evidence-dropdown"
          mode="multiple"
          placeholder="选择至少一条证据"
          :options="evidenceOptions"
          :disabled="submitting"
          option-filter-prop="label" />
      </a-form-item>

      <a-form-item :label="`响应理由（不超过 ${RESPONSE_REASON_MAX_LENGTH} 字）`">
        <a-textarea
          v-model:value="reason"
          data-testid="proposal-reason"
          :rows="3"
          :maxlength="RESPONSE_REASON_MAX_LENGTH"
          show-count
          :disabled="submitting"
          placeholder="说明为什么需要执行该剧本，供审批人核对。" />
      </a-form-item>

      <div class="proposal-actions">
        <a-button type="primary" :loading="submitting" @click="submit">提交提案</a-button>
        <span class="field-hint">提交后进入策略判定 / 人工审批，不会立即执行。</span>
      </div>
    </a-form>
  </a-card>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { message } from 'ant-design-vue'
import { listSoarPlaybooks } from '../../api/index.js'
import {
  RESPONSE_PROPOSAL_ACTION_KEY,
  RESPONSE_REASON_MAX_LENGTH,
  buildProposalRequest,
  responseActionLabel,
  selectablePlaybooks,
} from '../../utils/copilot.js'

const props = defineProps({
  sourceAlertRef: { type: Object, default: null },
  evidence: { type: Array, default: () => [] },
  submitting: { type: Boolean, default: false },
})
const emit = defineEmits(['create'])

const playbooks = ref([])
const playbooksLoading = ref(false)
const playbooksError = ref('')
const playbookId = ref(undefined)
const evidenceIds = ref([])
const reason = ref('')

const playbookOptions = computed(() => selectablePlaybooks(playbooks.value))
const evidenceOptions = computed(() => (props.evidence || []).map((item) => ({
  value: item.evidence_id,
  label: item.summary || item.evidence_id,
})))

// 目标只读展示：与后端派生结果同源，仅用于让审批人看见将要作用的对象。
const targetText = computed(() => {
  const ref = props.sourceAlertRef || {}
  const parts = [ref.provider, ref.resource_type, ref.business_id || ref.address_id].filter(Boolean)
  return parts.length ? parts.join(' / ') : '—'
})

onMounted(async () => {
  playbooksLoading.value = true
  try {
    playbooks.value = await listSoarPlaybooks()
  } catch (cause) {
    playbooksError.value = cause?.message || '未知错误'
  } finally {
    playbooksLoading.value = false
  }
})

function submit() {
  let body
  try {
    body = buildProposalRequest({
      playbookId: playbookId.value,
      evidenceIds: evidenceIds.value,
      reason: reason.value,
    })
  } catch (cause) {
    message.error(cause?.message || '提案内容不完整')
    return
  }
  emit('create', body)
}
</script>

<style scoped>
.bounded-banner { margin-bottom: 16px; }
.proposal-form { margin-top: 4px; }
.field-hint { color: #80919d; font-size: 12px; margin: 4px 0 0; }
.field-error { color: #cf1322; font-size: 12px; margin: 4px 0 0; }
.proposal-actions { display: flex; align-items: center; gap: 12px; }
</style>
