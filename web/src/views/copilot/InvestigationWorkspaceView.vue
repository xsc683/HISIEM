<template>
  <div class="page-shell">
    <InvestigationHeader
      :header="workspace?.investigation"
      :source-alert-ref="workspace?.source_alert_ref"
      :active="active" :running="running" :stale="stale"
      :last-refreshed="lastRefreshedText" :refreshing="refreshing" :cancelling="cancelling"
      @back="goBack" @refresh="() => fetchWorkspace({ manual: true })" @cancel="cancel" />

    <LoadState
      :loading="loading && !workspace"
      :error="error && !workspace ? error : ''"
      :empty="!workspace && !loading && !error"
      empty-text="未找到该调查"
      empty-hint="该调查可能已不存在，或您没有访问权限。"
      @retry="() => fetchWorkspace({ manual: true })">
      <template v-if="workspace">
        <a-tabs v-model:activeKey="activeTab" class="workspace-tabs">
          <a-tab-pane key="overview" tab="概览">
            <InvestigationOverview
              :result="workspace.result" :findings="workspace.findings"
              :evidence-by-id="evidenceById" :running="running"
              @select-evidence="openEvidence" />
          </a-tab-pane>
          <a-tab-pane key="evidence" :tab="`证据 (${workspace.evidence.length})`">
            <EvidenceList
              :evidence="workspace.evidence" :findings="workspace.findings"
              :selected-id="selectedEvidenceId" @select="openEvidence" />
          </a-tab-pane>
          <a-tab-pane key="investigation" tab="调查过程">
            <div class="investigation-panes">
              <InvestigationPlan
                :plan-revisions="workspace.plan_revisions"
                :current-plan-revision="workspace.investigation.current_plan_revision" />
              <HypothesisList :hypotheses="workspace.hypotheses" @select-evidence="openEvidence" />
              <ToolActivityList :tool-activity="workspace.tool_activity" />
            </div>
          </a-tab-pane>
          <a-tab-pane key="timeline" tab="时间线">
            <InvestigationTimeline :timeline="workspace.timeline" @select-evidence="openEvidence" />
          </a-tab-pane>
          <a-tab-pane key="response" tab="响应">
            <div class="response-panes">
              <InvestigationResponse
                :response="workspace.response" :can-decide="canDecide" @decide="decideResponse" />
              <ResponseProposalForm
                v-if="canCreateProposal"
                :source-alert-ref="workspace.source_alert_ref"
                :evidence="workspace.evidence"
                :submitting="creatingProposal"
                @create="createProposal" />
            </div>
          </a-tab-pane>
        </a-tabs>
      </template>
    </LoadState>

    <EvidenceDetailDrawer v-model:open="drawerOpen" :evidence="selectedEvidence" />
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import LoadState from '../../components/common/LoadState.vue'
import InvestigationHeader from '../../components/copilot/InvestigationHeader.vue'
import InvestigationOverview from '../../components/copilot/InvestigationOverview.vue'
import EvidenceList from '../../components/copilot/EvidenceList.vue'
import InvestigationPlan from '../../components/copilot/InvestigationPlan.vue'
import HypothesisList from '../../components/copilot/HypothesisList.vue'
import ToolActivityList from '../../components/copilot/ToolActivityList.vue'
import InvestigationTimeline from '../../components/copilot/InvestigationTimeline.vue'
import EvidenceDetailDrawer from '../../components/copilot/EvidenceDetailDrawer.vue'
import InvestigationResponse from '../../components/copilot/InvestigationResponse.vue'
import ResponseProposalForm from '../../components/copilot/ResponseProposalForm.vue'
import {
  approveAgentResponse,
  cancelAgentInvestigation,
  createAgentResponseProposal,
  getAgentInvestigationWorkspace,
  rejectAgentResponse,
} from '../../api/index.js'
import { useAuth } from '../../composables/useAuth.js'
import { formatTime } from '../../utils/display.js'
import { canCreateResponseProposal, isInvestigationActive, needsResponsePolling } from '../../utils/copilot.js'

// §25：进行中约 2.5s 轮询；终态停止；页面隐藏暂停；失败保留上次快照并标注过期。
const POLL_INTERVAL_MS = 2500

const route = useRoute()
const router = useRouter()
const { state: authState } = useAuth()

const workspace = ref(null)
const loading = ref(true)
const refreshing = ref(false)
const error = ref('')
const stale = ref(false)
const cancelling = ref(false)
const creatingProposal = ref(false)
const activeTab = ref('overview')
const selectedEvidenceId = ref('')
const drawerOpen = ref(false)
const lastRefreshedAt = ref(null)

let timer = null
let inflight = false

const investigationId = computed(() => String(route.params.investigationId || ''))
const status = computed(() => workspace.value?.investigation?.status)
const active = computed(() => isInvestigationActive(status.value))
const running = computed(() => active.value)
const lastRefreshedText = computed(() => (lastRefreshedAt.value ? formatTime(lastRefreshedAt.value.toISOString()) : ''))
const evidenceById = computed(() => {
  const map = {}
  for (const item of workspace.value?.evidence || []) map[item.evidence_id] = item
  return map
})
const selectedEvidence = computed(
  () => (workspace.value?.evidence || []).find((item) => item.evidence_id === selectedEvidenceId.value) || null,
)

// 审批权限：仅管理员/分析师可决策，审计角色只读（与后端 @PreAuthorize 一致，前端只是不显示按钮）。
const canDecide = computed(() => ['admin', 'analyst'].includes(authState.user?.role))
const proposals = computed(() => workspace.value?.response?.proposals || [])
// 创建提案同样是写操作：只有调查已 COMPLETED、本次调查尚无提案、且至少有证据可引用时才出现表单。
const canCreateProposal = computed(() => canCreateResponseProposal({
  status: status.value,
  proposals: proposals.value,
  role: authState.user?.role,
  evidenceCount: (workspace.value?.evidence || []).length,
}))
// 响应仍在推进（已批准等待提交 / 非终态执行）时仍要轮询，即便调查本身已是终态。
const responseInFlight = computed(() => needsResponsePolling(proposals.value))

function clearTimer() {
  if (timer) { window.clearTimeout(timer); timer = null }
}

function scheduleNext() {
  clearTimer()
  if (!active.value && !responseInFlight.value) return
  if (typeof document !== 'undefined' && document.hidden) return
  timer = window.setTimeout(() => { void poll() }, POLL_INTERVAL_MS)
}

async function poll() {
  if (inflight) { scheduleNext(); return }
  await fetchWorkspace()
}

async function fetchWorkspace({ manual = false } = {}) {
  if (inflight || !investigationId.value) { scheduleNext(); return }
  inflight = true
  if (manual) refreshing.value = true
  try {
    workspace.value = await getAgentInvestigationWorkspace(investigationId.value)
    error.value = ''
    stale.value = false
    lastRefreshedAt.value = new Date()
  } catch (cause) {
    if (!workspace.value) error.value = cause?.message || '调查数据加载失败'
    else stale.value = true // 保留上次快照，绝不空白页面
  } finally {
    inflight = false
    loading.value = false
    refreshing.value = false
    scheduleNext()
  }
}

function onVisibilityChange() {
  if (document.hidden) clearTimer()
  else if (active.value || responseInFlight.value) void poll()
}

function openEvidence(evidenceId) {
  if (!evidenceId) return
  selectedEvidenceId.value = evidenceId
  drawerOpen.value = true
  activeTab.value = 'evidence'
}

function goBack() {
  const ref = workspace.value?.source_alert_ref
  if (ref?.address_id) router.push(`/alerts/${encodeURIComponent(ref.address_id)}`)
  else router.push('/alerts')
}

async function cancel() {
  if (cancelling.value) return
  cancelling.value = true
  try {
    await cancelAgentInvestigation(investigationId.value)
    message.success('已请求取消调查')
    await fetchWorkspace({ manual: true })
  } catch (cause) {
    message.error(`取消失败：${cause?.message || '未知错误'}`)
  } finally {
    cancelling.value = false
  }
}

async function createProposal(body) {
  if (creatingProposal.value) return
  creatingProposal.value = true
  try {
    await createAgentResponseProposal(investigationId.value, body)
    message.success('响应提案已创建，等待策略判定与人工审批')
    await fetchWorkspace({ manual: true })
  } catch (cause) {
    message.error(`创建提案失败：${cause?.message || '未知错误'}`)
  } finally {
    creatingProposal.value = false
  }
}

async function decideResponse({ proposal, decision }) {
  const approval = proposal?.approval
  if (!approval?.request_id) return
  // 精确契约：把用户看到的版本+指纹原样回传,由后端校验绑定。
  const body = {
    expected_revision: approval.expected_revision,
    expected_content_hash: approval.expected_content_hash,
  }
  try {
    if (decision === 'APPROVE') await approveAgentResponse(approval.request_id, body)
    else await rejectAgentResponse(approval.request_id, body)
    message.success(decision === 'APPROVE' ? '已批准，执行已进入持久化队列' : '已驳回')
    await fetchWorkspace({ manual: true })
  } catch (cause) {
    message.error(`${decision === 'APPROVE' ? '批准' : '驳回'}失败：${cause?.message || '未知错误'}`)
  }
}

onMounted(() => {
  document.addEventListener('visibilitychange', onVisibilityChange)
  void fetchWorkspace()
})

onBeforeUnmount(() => {
  document.removeEventListener('visibilitychange', onVisibilityChange)
  clearTimer()
})
</script>

<style scoped>
.workspace-tabs { margin-top: 4px; }
.investigation-panes, .response-panes { display: grid; gap: 16px; }
</style>
