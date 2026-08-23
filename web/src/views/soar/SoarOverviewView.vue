<template>
  <div class="page-shell">
    <PageHeader title="SOAR 自动化" description="以告警/案件为稳定资源启动受控编排，观察执行快照、审批节点、Connector 保护和最终处置。">
      <a-button @click="load"><ReloadOutlined /> 刷新</a-button>
      <a-button v-if="isAdmin" @click="importGit">导入 Git 为草稿</a-button>
      <a-button type="primary" @click="router.push('/soar/designer')"><ApartmentOutlined /> Playbook 设计器</a-button>
    </PageHeader>
    <a-alert type="info" show-icon message="SOAR V3 运行边界" description="动作必须属于后端白名单或登记 Connector；执行由数据库租约 Worker 异步推进，不允许任意 Shell、任意 URL 或任意 Header。" />
    <div class="metric-strip">
      <div class="metric"><span class="metric-label">已发布 Playbook</span><span class="metric-value">{{ playbooks.length }}</span></div>
      <div class="metric"><span class="metric-label">活动自动规则</span><span class="metric-value">{{ automationRules.filter((item) => item.active).length }}</span></div>
      <div class="metric"><span class="metric-label">运行 / 等待</span><span class="metric-value">{{ activeExecutions }}</span></div>
      <div class="metric"><span class="metric-label">Connector 熔断</span><span class="metric-value">{{ openCircuits }}</span></div>
    </div>

    <a-card class="surface-card" title="启动自动化处置">
      <div class="start-grid">
        <a-form-item label="资源类型" style="margin:0"><a-select v-model:value="startForm.resourceType" :options="resourceOptions" @change="chooseDefaultPlaybook" /></a-form-item>
        <a-form-item label="资源 ID" style="margin:0"><a-input v-model:value="startForm.resourceId" :placeholder="startForm.resourceType === 'alert' ? '告警 _id' : '案件 ID'" /></a-form-item>
        <a-form-item label="Playbook" style="margin:0"><a-select v-model:value="startForm.playbookId" :options="compatiblePlaybooks.map((item) => ({ value: item.id, label: `${item.name} · v${item.version}` }))" /></a-form-item>
        <a-button type="primary" :loading="starting" :disabled="!canStart" @click="start">运行 Playbook</a-button>
      </div>
    </a-card>

    <a-card class="surface-card" title="已发布 Playbook">
      <LoadState :loading="loading" :error="error" :empty="!playbooks.length" @retry="load">
        <a-table row-key="id" :data-source="playbooks" :columns="playbookColumns" :pagination="false">
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'name'"><strong>{{ record.name }}</strong><div><code class="mono-id">{{ record.id }}</code></div></template>
            <template v-else-if="column.key === 'version'"><a-tag color="green">V{{ record.formatVersion }}</a-tag> {{ record.version }}</template>
            <template v-else-if="column.key === 'resources'"><a-tag v-for="type in record.resourceTypes || []" :key="type" color="blue">{{ type === 'alert' ? '告警' : '案件' }}</a-tag></template>
            <template v-else-if="column.key === 'nodes'">{{ (record.nodes || record.steps || []).length }}</template>
          </template>
        </a-table>
      </LoadState>
    </a-card>

    <a-row :gutter="16">
      <a-col :span="15">
        <a-card class="surface-card" title="执行记录">
          <a-table row-key="id" size="small" :data-source="executions" :columns="executionColumns" :pagination="{ pageSize: 10 }">
            <template #bodyCell="{ column, record }">
              <template v-if="column.key === 'status'"><StatusTag :value="record.status" /></template>
              <template v-else-if="column.key === 'playbook'"><strong>{{ record.playbookSnapshot?.name || record.playbookId }}</strong><div><code class="mono-id">{{ record.playbookId }} · {{ record.playbookVersion }}</code></div></template>
              <template v-else-if="column.key === 'target'"><a-tag>{{ record.resourceType }}</a-tag><code>{{ record.resourceId }}</code></template>
              <template v-else-if="column.key === 'updated'"><TimeText :value="record.updatedAt" /></template>
              <template v-else-if="column.key === 'action'"><a-button size="small" @click="router.push(`/soar/executions/${encodeURIComponent(record.id)}`)">执行详情</a-button></template>
            </template>
          </a-table>
        </a-card>
      </a-col>
      <a-col :span="9">
        <a-card class="surface-card" title="Connector 保护状态">
          <a-table row-key="connectorId" size="small" :data-source="connectorRuntime" :columns="connectorColumns" :pagination="false">
            <template #bodyCell="{ column, record }"><template v-if="column.key === 'id'"><code>{{ record.connectorId }}</code></template><template v-else-if="column.key === 'circuit'"><StatusTag :value="record.circuitOpenUntil ? 'failed' : 'active'" /></template></template>
          </a-table>
        </a-card>
        <a-card class="surface-card" title="自动触发规则" style="margin-top: 16px">
          <template #extra><a-button v-if="isAdmin" size="small" @click="scanRules">手动扫描</a-button></template>
          <a-list size="small" :data-source="automationRules"><template #renderItem="{ item }"><a-list-item><a-list-item-meta :title="item.name || item.id" :description="`${item.resourceType || item.type} · ${item.playbookId}`" /><StatusTag :value="item.active ? 'active' : 'stopped'" /></a-list-item></template></a-list>
        </a-card>
      </a-col>
    </a-row>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { ApartmentOutlined, ReloadOutlined } from '@ant-design/icons-vue'
import { getSoarConnectorRuntime, importSoarGit, listSoarAutomationRules, listSoarExecutions, listSoarPlaybooks, scanSoarAutomationRules, startSoarExecution } from '../../api/index.js'
import { useAuth } from '../../composables/useAuth.js'
import PageHeader from '../../components/common/PageHeader.vue'
import LoadState from '../../components/common/LoadState.vue'
import StatusTag from '../../components/common/StatusTag.vue'
import TimeText from '../../components/common/TimeText.vue'

const route = useRoute(); const router = useRouter(); const auth = useAuth(); const playbooks = ref([]); const executions = ref([]); const automationRules = ref([]); const connectorRuntime = ref([]); const loading = ref(false); const starting = ref(false); const error = ref('')
const startForm = reactive({ resourceType: route.query.resourceType || 'alert', resourceId: route.query.resourceId || '', playbookId: '' })
const isAdmin = computed(() => auth.state.user?.role === 'admin'); const compatiblePlaybooks = computed(() => playbooks.value.filter((item) => item.resourceTypes?.includes(startForm.resourceType))); const canStart = computed(() => startForm.resourceId.trim() && startForm.playbookId); const activeExecutions = computed(() => executions.value.filter((item) => ['queued', 'running', 'waiting_approval', 'waiting_child', 'paused'].includes(item.status)).length); const openCircuits = computed(() => connectorRuntime.value.filter((item) => item.circuitOpenUntil && new Date(item.circuitOpenUntil) > new Date()).length)
const resourceOptions = [{ value: 'alert', label: '告警' }, { value: 'case', label: '案件' }]
const playbookColumns = [{ key: 'name', title: '名称' }, { key: 'version', title: '格式 / 版本', width: 150 }, { key: 'resources', title: '资源', width: 150 }, { key: 'nodes', title: '节点数', width: 90 }, { dataIndex: 'description', title: '说明' }]
const executionColumns = [{ key: 'status', title: '状态', width: 110 }, { key: 'playbook', title: 'Playbook' }, { key: 'target', title: '目标' }, { dataIndex: 'currentNode', title: '当前节点', width: 130 }, { key: 'updated', title: '更新时间', width: 180 }, { key: 'action', title: '操作', width: 90 }]
const connectorColumns = [{ key: 'id', title: 'Connector' }, { dataIndex: 'windowCalls', title: '分钟', width: 60 }, { dataIndex: 'inFlight', title: '并发', width: 60 }, { key: 'circuit', title: '熔断', width: 80 }]
function chooseDefaultPlaybook() { startForm.playbookId = compatiblePlaybooks.value[0]?.id || '' }
async function load() { loading.value = true; error.value = ''; try { [playbooks.value, executions.value, automationRules.value, connectorRuntime.value] = await Promise.all([listSoarPlaybooks(), listSoarExecutions(), listSoarAutomationRules(), getSoarConnectorRuntime()]); if (!compatiblePlaybooks.value.some((item) => item.id === startForm.playbookId)) chooseDefaultPlaybook() } catch (cause) { error.value = cause.message } finally { loading.value = false } }
async function start() { starting.value = true; try { const execution = await startSoarExecution(startForm.playbookId, startForm.resourceType, startForm.resourceId.trim()); message.success('执行已进入队列'); await router.push(`/soar/executions/${encodeURIComponent(execution.id)}`) } catch (cause) { message.error(cause.message) } finally { starting.value = false } }
async function scanRules() { try { const result = await scanSoarAutomationRules(); message.success(`扫描 ${result.checked} 个资源，匹配 ${result.matched} 个`); await load() } catch (cause) { message.error(cause.message) } }
async function importGit() { try { const result = await importSoarGit(); message.success(`已导入 ${result.length} 个草稿，仍需审批发布`) } catch (cause) { message.error(cause.message) } }
onMounted(load)
</script>
<style scoped>.start-grid { display: grid; grid-template-columns: 130px minmax(260px, 1fr) minmax(320px, 1fr) auto; align-items: end; gap: 14px; }</style>
