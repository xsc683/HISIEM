<template>
  <div class="investigation-response">
    <a-alert
      type="info" show-icon class="invariant-banner"
      message="Agent 只能建议，不能授权"
      description="响应动作必须由具备审批权限的人在核对精确契约后批准，才会进入持久化执行队列；系统不会自动执行任何写操作。" />

    <a-card class="surface-card" size="small" title="响应建议">
      <a-empty v-if="!recommendations.length" description="本次调查没有给出响应建议" :image="false" />
      <ul v-else class="recommendation-list">
        <li v-for="(item, index) in recommendations" :key="index">
          <strong>{{ item.description }}</strong>
          <p class="recommendation-reason">{{ item.reason }}</p>
        </li>
      </ul>
      <p class="hint">这些是文本建议，仅供参考，不能直接执行。</p>
    </a-card>

    <a-card
      v-for="proposal in proposals" :key="proposal.proposal_id"
      class="surface-card" size="small">
      <template #title>
        <span class="proposal-title">
          {{ responseActionLabel(proposal.action_key) }}
          <a-tag :color="responseProposalStatusColor(proposal.status)">
            {{ responseProposalStatusLabel(proposal.status) }}
          </a-tag>
          <a-tag v-if="proposal.policy_decision" :color="policyDecisionColor(proposal.policy_decision)">
            {{ policyDecisionLabel(proposal.policy_decision) }}
          </a-tag>
        </span>
      </template>

      <a-descriptions bordered size="small" :column="2" class="proposal-descriptions">
        <a-descriptions-item label="目标" :span="2">
          <a-tag v-for="(target, index) in proposal.target_refs" :key="index" color="default">
            {{ target.provider }} / {{ target.resource_type }} / {{ target.business_id || target.address_id }}
          </a-tag>
        </a-descriptions-item>
        <a-descriptions-item label="参数" :span="2">
          <span v-if="parameterEntries(proposal).length === 0">—</span>
          <a-tag v-for="[key, value] in parameterEntries(proposal)" :key="key" color="geekblue">
            {{ key }} = {{ value }}
          </a-tag>
        </a-descriptions-item>
        <a-descriptions-item label="理由" :span="2">{{ proposal.reason || '—' }}</a-descriptions-item>
        <a-descriptions-item label="策略原因" :span="2">{{ proposal.policy_reason || '—' }}</a-descriptions-item>
        <a-descriptions-item label="提出人">
          <span data-testid="proposal-proposer">
            {{ proposal.created_by_display_name || proposal.created_by_subject || '—' }}
          </span>
        </a-descriptions-item>
        <a-descriptions-item label="提出时间"><TimeText :value="proposal.created_at" /></a-descriptions-item>
        <a-descriptions-item label="内容版本">{{ proposal.revision }}</a-descriptions-item>
        <a-descriptions-item label="内容指纹"><code class="hash">{{ proposal.content_hash }}</code></a-descriptions-item>
      </a-descriptions>

      <div v-if="proposal.approval" class="approval-block">
        <a-divider orientation="left">审批</a-divider>
        <a-descriptions size="small" :column="2">
          <a-descriptions-item label="审批请求">{{ proposal.approval.request_id }}</a-descriptions-item>
          <a-descriptions-item label="请求时间"><TimeText :value="proposal.approval.requested_at" /></a-descriptions-item>
          <a-descriptions-item label="绑定版本">{{ proposal.approval.expected_revision }}</a-descriptions-item>
          <a-descriptions-item label="绑定指纹"><code class="hash">{{ proposal.approval.expected_content_hash }}</code></a-descriptions-item>
          <a-descriptions-item label="请求理由" :span="2">{{ proposal.approval.requested_reason || '—' }}</a-descriptions-item>
        </a-descriptions>

        <div v-if="proposal.approval.decision" class="decision-block">
          <a-tag :color="proposal.approval.decision.decision === 'APPROVE' ? 'green' : 'default'">
            {{ proposal.approval.decision.decision === 'APPROVE' ? '已批准' : '已驳回' }}
          </a-tag>
          <span class="decision-meta">
            {{ proposal.approval.decision.actor_display_name || proposal.approval.decision.actor_subject_id }}
            · <TimeText :value="proposal.approval.decision.decided_at" />
          </span>
          <p v-if="proposal.approval.decision.reason" class="decision-reason">{{ proposal.approval.decision.reason }}</p>
        </div>

        <div v-else-if="canDecide && canDecideProposal(proposal.status)" class="decision-actions">
          <a-popconfirm
            title="确认批准该响应动作？"
            :description="`将按版本 ${proposal.approval.expected_revision}、指纹 ${shortHash(proposal.approval.expected_content_hash)} 精确绑定批准；批准后进入持久化执行队列。`"
            ok-text="批准" cancel-text="取消" @confirm="emit('decide', { proposal, decision: 'APPROVE' })">
            <a-button type="primary">批准</a-button>
          </a-popconfirm>
          <a-popconfirm
            title="确认驳回该响应动作？"
            description="驳回不会产生任何执行命令。" ok-text="驳回" cancel-text="取消"
            @confirm="emit('decide', { proposal, decision: 'REJECT' })">
            <a-button danger>驳回</a-button>
          </a-popconfirm>
        </div>
        <p v-else-if="!proposal.approval.decision" class="hint">
          您没有审批权限，只能查看该响应提案。
        </p>
      </div>

      <div v-if="proposalSubmissionFailed(proposal)" class="submission-failed">
        <a-divider orientation="left">执行</a-divider>
        <a-alert
          type="error" show-icon
          message="提交失败"
          description="HISIEM 明确拒绝了这次提交，因此没有产生任何外部执行，也不会有外部执行编号；系统不会再自动重试这次提交。" />
        <a-descriptions size="small" :column="2">
          <a-descriptions-item label="提交状态">
            <a-tag :color="submissionStatusColor(proposal.submission.status)">
              <span data-testid="submission-status">{{ submissionStatusLabel(proposal.submission.status) }}</span>
            </a-tag>
          </a-descriptions-item>
          <a-descriptions-item label="提交尝试次数">{{ proposal.submission.attempt_count }}</a-descriptions-item>
          <a-descriptions-item label="失败时间"><TimeText :value="proposal.submission.failed_at" /></a-descriptions-item>
          <a-descriptions-item label="错误码">{{ proposal.submission.last_error_code || '—' }}</a-descriptions-item>
          <a-descriptions-item label="错误信息" :span="2">{{ proposal.submission.safe_error_message || '—' }}</a-descriptions-item>
        </a-descriptions>
      </div>

      <div
        v-else-if="proposalSubmissionNeedsAttention(proposal)"
        class="submission-attention">
        <a-divider orientation="left">执行</a-divider>
        <a-alert
          type="warning" show-icon
          message="提交状态不确定 / 需要人工处理"
          description="自动提交重试预算已耗尽，但每次失败都是瞬时/不确定的（超时、限流、上游不可用），因此系统既不能断言 HISIEM 拒绝了这次提交，也不能断言没有产生执行。在此之前不会显示任何外部执行编号，系统也不会再自动重试，需要人工核对。" />
        <a-descriptions size="small" :column="2">
          <a-descriptions-item label="提交状态">
            <a-tag :color="submissionStatusColor(proposal.submission.status)">
              <span data-testid="submission-status">{{ submissionStatusLabel(proposal.submission.status) }}</span>
            </a-tag>
          </a-descriptions-item>
          <a-descriptions-item label="提交尝试次数">{{ proposal.submission.attempt_count }}</a-descriptions-item>
          <a-descriptions-item label="需要处理时间"><TimeText :value="proposal.submission.attention_required_at" /></a-descriptions-item>
          <a-descriptions-item label="最后一次错误码">{{ proposal.submission.last_error_code || '—' }}</a-descriptions-item>
          <a-descriptions-item label="最后一次错误信息" :span="2">{{ proposal.submission.safe_error_message || '—' }}</a-descriptions-item>
        </a-descriptions>
      </div>

      <div v-else-if="proposalAwaitingSubmission(proposal)" class="awaiting-submission">
        <a-divider orientation="left">执行</a-divider>
        <a-alert
          :type="proposalSubmissionRetrying(proposal) ? 'warning' : 'info'" show-icon
          :message="proposalSubmissionRetrying(proposal) ? '已批准 / 提交重试中' : '已批准 / 等待提交'"
          :description="proposalSubmissionRetrying(proposal)
            ? `提交命令仍在持久化队列中重试（已尝试 ${proposal.submission?.attempt_count ?? 0} 次）；在 HISIEM 返回真实的执行 ID 之前，这里不会显示任何外部执行编号。`
            : '人工批准已绑定上面的精确契约，提交命令已进入持久化队列；在 HISIEM 返回真实的执行 ID 之前，这里不会显示任何外部执行编号。'" />
      </div>

      <template v-if="proposal.execution">
        <a-divider orientation="left">执行</a-divider>
        <a-descriptions size="small" :column="2">
          <a-descriptions-item label="状态">
            <a-tag :color="executionStatusColor(proposal.execution.status)">
              {{ executionStatusLabel(proposal.execution.status) }}
            </a-tag>
          </a-descriptions-item>
          <a-descriptions-item label="执行方">{{ proposal.execution.provider }}</a-descriptions-item>
          <a-descriptions-item label="外部执行 ID">{{ proposal.execution.external_execution_id || '—' }}</a-descriptions-item>
          <a-descriptions-item label="开始时间"><TimeText :value="proposal.execution.started_at || proposal.execution.submitted_at" /></a-descriptions-item>
          <a-descriptions-item label="结束时间"><TimeText :value="proposal.execution.finished_at" /></a-descriptions-item>
          <a-descriptions-item label="最近观测"><TimeText :value="proposal.execution.last_observed_at" /></a-descriptions-item>
        </a-descriptions>
        <p v-if="proposal.execution.safe_error_code" class="execution-error">
          执行失败：{{ proposal.execution.safe_error_code }}
          <span v-if="proposal.execution.safe_error_message">— {{ proposal.execution.safe_error_message }}</span>
        </p>
      </template>
    </a-card>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import TimeText from '../common/TimeText.vue'
import {
  canDecideProposal,
  executionStatusColor,
  proposalAwaitingSubmission,
  proposalSubmissionFailed,
  proposalSubmissionNeedsAttention,
  proposalSubmissionRetrying,
  submissionStatusColor,
  submissionStatusLabel,
  executionStatusLabel,
  policyDecisionColor,
  policyDecisionLabel,
  responseActionLabel,
  responseProposalStatusColor,
  responseProposalStatusLabel,
} from '../../utils/copilot.js'

const props = defineProps({
  response: { type: Object, default: null },
  canDecide: { type: Boolean, default: false },
})
const emit = defineEmits(['decide'])

const recommendations = computed(() => props.response?.recommendations || [])
const proposals = computed(() => props.response?.proposals || [])

function parameterEntries(proposal) {
  return Object.entries(proposal.parameters || {})
}

function shortHash(hash) {
  if (!hash) return '—'
  return hash.length > 12 ? `${hash.slice(0, 12)}…` : hash
}
</script>

<style scoped>
.investigation-response { display: grid; gap: 16px; }
.invariant-banner { margin: 0; }
.recommendation-list { margin: 0; padding-left: 18px; display: grid; gap: 8px; }
.recommendation-reason { margin: 2px 0 0; color: #5b6b76; }
.hint { color: #80919d; font-size: 12px; margin: 8px 0 0; }
.proposal-title { display: inline-flex; align-items: center; gap: 8px; }
.proposal-descriptions { margin-bottom: 12px; }
.hash { font-family: monospace; word-break: break-all; }
.approval-block, .decision-block { display: grid; gap: 8px; }
.decision-actions { display: flex; gap: 12px; }
.decision-meta { color: #5b6b76; font-size: 12px; margin-left: 8px; }
.decision-reason, .execution-error { margin: 0; color: #5b6b76; }
.execution-error { color: #cf1322; }
.awaiting-submission, .submission-failed, .submission-attention { display: grid; gap: 8px; }
</style>
