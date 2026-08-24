<template>
  <div class="page-shell">
    <PageHeader title="SOAR 执行实例" description="生命周期与人工触发进入同一套租约、fencing、attempt 和幂等执行内核。">
      <SoarSectionNav /><a-button v-if="canTrigger" type="primary" @click="openManual">手动运行</a-button><a-button @click="load"><ReloadOutlined /> 刷新</a-button>
    </PageHeader>
    <a-card class="surface-card">
      <div class="filter-bar"><span>状态</span><a-select v-model:value="status" allow-clear placeholder="全部状态" style="width:180px" :options="statuses" @change="load" /></div>
    </a-card>
    <a-card class="surface-card">
      <LoadState :loading="loading" :error="error" :empty="!executions.length" @retry="load">
        <a-table row-key="id" :data-source="executions" :columns="columns" :pagination="{ pageSize: 20 }">
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'id'"><router-link :to="`/soar/executions/${encodeURIComponent(record.id)}`"><code>{{ record.id }}</code></router-link><div class="muted"><a-tag>{{ record.triggerType }}</a-tag>{{ record.eventType }}</div></template>
            <template v-else-if="column.key === 'playbook'"><strong>{{ record.playbookName }}</strong><div class="muted">revision {{ record.playbookRevision }}</div></template>
            <template v-else-if="column.key === 'object'"><a-tag>{{ record.objectType }}</a-tag><code>{{ record.objectId }}</code></template>
            <template v-else-if="column.key === 'status'"><StatusTag :value="record.status" /><div v-if="record.currentNodeId"><code>{{ record.currentNodeId }}</code></div></template>
            <template v-else-if="column.key === 'updated'"><TimeText :value="record.updatedAt" /></template>
            <template v-else-if="column.key === 'action'"><a-button size="small" @click="router.push(`/soar/executions/${encodeURIComponent(record.id)}`)">查看节点 I/O</a-button></template>
          </template>
        </a-table>
      </LoadState>
    </a-card>
    <a-modal v-model:open="manual.open" title="手动运行 Playbook" :confirm-loading="manual.saving" @ok="submitManual">
      <a-form layout="vertical">
        <a-form-item label="Playbook" required><a-select v-model:value="manual.playbookId" :options="playbookOptions" placeholder="选择已发布且启用的 Playbook" @change="selectPlaybook" /></a-form-item>
        <a-form-item label="对象类型"><a-input :value="manual.objectType" disabled /></a-form-item>
        <a-form-item label="对象 ID" required><a-input v-model:value="manual.objectId" /></a-form-item>
        <a-form-item label="事件类型"><a-select v-model:value="manual.eventType" :options="eventOptions" /></a-form-item>
        <a-form-item label="Request ID" extra="可留空自动生成；HTTP 超时重试时复用同一 ID 可避免重复执行。"><a-input v-model:value="manual.requestId" placeholder="例如 ticket-20260824-001" /></a-form-item>
        <a-form-item label="对象字段 JSON" extra="可直接填写 severity、status 等对象字段；后端会补齐 alert/case 根对象与 ID。"><a-textarea v-model:value="manual.payloadText" :rows="6" /></a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { message } from 'ant-design-vue'
import { ReloadOutlined } from '@ant-design/icons-vue'
import { useRoute, useRouter } from 'vue-router'
import { listSoarExecutions, listSoarPlaybooks, triggerSoarExecution } from '../../api/index.js'
import { useAuth } from '../../composables/useAuth.js'
import LoadState from '../../components/common/LoadState.vue'
import PageHeader from '../../components/common/PageHeader.vue'
import StatusTag from '../../components/common/StatusTag.vue'
import TimeText from '../../components/common/TimeText.vue'
import SoarSectionNav from '../../components/soar/SoarSectionNav.vue'

const router = useRouter(); const route = useRoute(); const auth = useAuth(); const executions = ref([]); const playbooks = ref([]); const loading = ref(false); const error = ref(''); const status = ref(); let timer
const canTrigger = computed(() => ['admin', 'analyst'].includes(auth.state.user?.role))
const manual = reactive({ open: false, saving: false, playbookId: '', objectType: '', objectId: '', eventType: '', requestId: '', payloadText: '{}' })
const availablePlaybooks = computed(() => playbooks.value.filter((item) => item.status === 'published' && item.enabled && (!route.query.resourceType || item.entryType === route.query.resourceType)))
const playbookOptions = computed(() => availablePlaybooks.value.map((item) => ({ value: item.id, label: `${item.name} · ${item.entryType}` })))
const selectedPlaybook = computed(() => playbooks.value.find((item) => item.id === manual.playbookId))
const eventOptions = computed(() => (selectedPlaybook.value?.eventTypes || []).map((value) => ({ value, label: value })))
const statuses = ['pending', 'running', 'waiting', 'waiting_human', 'success', 'failed', 'cancelled'].map((value) => ({ value, label: value }))
const columns = [{ key: 'id', title: '执行 / 触发事件' }, { key: 'playbook', title: 'Playbook' }, { key: 'object', title: '处置对象' }, { key: 'status', title: '状态 / 当前节点', width: 170 }, { key: 'updated', title: '更新时间', width: 180 }, { key: 'action', title: '操作', width: 120 }]
async function load() { loading.value = !executions.value.length; error.value = ''; try { executions.value = await listSoarExecutions(status.value) } catch (cause) { error.value = cause.message } finally { loading.value = false; window.clearTimeout(timer); timer = window.setTimeout(load, 5000) } }
function selectPlaybook() { manual.objectType = selectedPlaybook.value?.entryType || ''; manual.eventType = selectedPlaybook.value?.eventTypes?.[0] || '' }
async function openManual() {
  try {
    if (!playbooks.value.length) playbooks.value = await listSoarPlaybooks()
    manual.objectId = String(route.query.resourceId || manual.objectId || '')
    manual.objectType = String(route.query.resourceType || '')
    const matched = availablePlaybooks.value[0]
    manual.playbookId = matched?.id || ''
    selectPlaybook()
    manual.open = true
  } catch (cause) { message.error(cause.message) }
}
async function submitManual() {
  if (!manual.playbookId || !manual.objectId) return message.warning('请选择 Playbook 并填写对象 ID')
  let payload
  try { payload = JSON.parse(manual.payloadText || '{}') } catch { return message.error('Payload 必须是合法 JSON') }
  manual.saving = true
  try {
    const execution = await triggerSoarExecution({ playbookId: manual.playbookId, requestId: manual.requestId || null, objectType: manual.objectType, objectId: manual.objectId, eventType: manual.eventType, payload })
    manual.open = false
    message.success('执行已创建')
    await router.push(`/soar/executions/${encodeURIComponent(execution.id)}`)
  } catch (cause) { message.error(cause.message) } finally { manual.saving = false }
}
onMounted(async () => { await load(); if (route.query.manual === '1' && canTrigger.value) await openManual() }); onBeforeUnmount(() => window.clearTimeout(timer))
</script>
