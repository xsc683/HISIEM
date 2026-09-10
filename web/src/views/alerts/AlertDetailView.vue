<template>
  <div class="page-shell">
    <PageHeader :title="alert?.['alert.rule_name'] || '告警详情'" description="面向分析师的结构化证据视图；完整 JSON 保留在次级页签。">
      <a-button @click="router.push('/alerts')">返回告警台</a-button>
      <template v-if="alert">
        <a-button v-if="activeInvestigation" type="primary" @click="openInvestigation(activeInvestigation.investigation_id)">继续 Agent 调查</a-button>
        <template v-else-if="latestInvestigation">
          <a-button type="primary" @click="openInvestigation(latestInvestigation.investigation_id)">查看 Agent 调查</a-button>
          <a-button :loading="agentLaunching" @click="launchInvestigation">重新调查</a-button>
        </template>
        <a-button v-else type="primary" :loading="agentLaunching" @click="launchInvestigation">交给 Agent 调查</a-button>
        <a-button type="primary" @click="runSoar">运行 SOAR</a-button>
        <a-button v-if="!alert['alert.case_id']" @click="router.push({ path: '/cases/new', query: { alerts: alert._id } })">创建案件</a-button>
      </template>
    </PageHeader>
    <LoadState :loading="loading" :error="error" :empty="!alert" @retry="load">
      <template v-if="alert">
        <a-card class="surface-card">
          <div class="filter-bar" style="margin-bottom: 16px">
            <span>处置状态</span><a-select :value="alert['alert.status']" style="width: 150px" :options="statusOptions" :loading="saving" @change="changeStatus" />
            <span>分析结论</span><a-select :value="alert['alert.analyst_verdict']" allow-clear placeholder="未判定" style="width: 170px" :options="verdictOptions" :loading="saving" @change="changeVerdict" />
            <router-link v-if="alert['alert.case_id']" :to="`/cases/${encodeURIComponent(alert['alert.case_id'])}`">查看关联案件 {{ alert['alert.case_id'] }}</router-link>
          </div>
          <AlertDetails :alert="alert" />
        </a-card>
      </template>
    </LoadState>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { getAlert, launchAlertInvestigation, lookupAlertInvestigation, updateAlertStatus, updateAlertVerdict } from '../../api/index.js'
import PageHeader from '../../components/common/PageHeader.vue'
import LoadState from '../../components/common/LoadState.vue'
import AlertDetails from '../../components/alerts/AlertDetails.vue'
import { displayLabel } from '../../utils/display.js'

const route = useRoute(); const router = useRouter()
const alert = ref(null); const loading = ref(false); const saving = ref(false); const agentLaunching = ref(false); const error = ref('')
// §6 服务端权威的重新进入状态：当前活动调查 / 最近一次调查（不依赖浏览器本地状态）。
const investigationLookup = ref({ active: null, latest: null })
const activeInvestigation = computed(() => investigationLookup.value.active)
const latestInvestigation = computed(() => investigationLookup.value.latest)
const statusOptions = ['open', 'acknowledged', 'investigating', 'resolved', 'closed'].map((value) => ({ value, label: displayLabel('status', value) }))
const verdictOptions = ['true_positive', 'false_positive', 'duplicate'].map((value) => ({ value, label: displayLabel('verdict', value) }))
async function load() { loading.value = true; error.value = ''; try { alert.value = await getAlert(route.params.id) } catch (cause) { error.value = cause.message } finally { loading.value = false } }
async function loadInvestigationLookup() {
  try {
    const result = await lookupAlertInvestigation(route.params.id)
    investigationLookup.value = { active: result?.active || null, latest: result?.latest || null }
  } catch {
    investigationLookup.value = { active: null, latest: null }
  }
}
async function changeStatus(status) { saving.value = true; try { await updateAlertStatus(alert.value._id, status); await load(); message.success('告警状态已更新') } catch (cause) { message.error(cause.message) } finally { saving.value = false } }
async function changeVerdict(verdict) { if (!verdict) return; saving.value = true; try { await updateAlertVerdict(alert.value._id, verdict); await load(); message.success('分析结论已更新') } catch (cause) { message.error(cause.message) } finally { saving.value = false } }
function runSoar() { router.push({ path: '/soar/executions', query: { resourceType: 'alert', resourceId: alert.value._id, manual: '1' } }) }
function openInvestigation(investigationId) { router.push(`/copilot/investigations/${encodeURIComponent(investigationId)}`) }
async function launchInvestigation() {
  if (agentLaunching.value || !alert.value) return
  agentLaunching.value = true
  try {
    const result = await launchAlertInvestigation(alert.value._id)
    if (!result?.redirectUrl) throw new Error('Agent 未返回任务跳转地址')
    if (/^https?:\/\//i.test(result.redirectUrl)) window.location.assign(result.redirectUrl)
    else router.push(result.redirectUrl)
  } catch (cause) {
    message.error(`Agent 任务创建失败：${cause.message}`)
  } finally { agentLaunching.value = false }
}
onMounted(async () => { await load(); await loadInvestigationLookup() })
</script>
