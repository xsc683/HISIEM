<template>
  <div class="page-shell">
    <PageHeader title="SOAR 执行实例" description="执行由生命周期消息自动创建；状态、当前节点和目标对象用于定位自动化处置进度。">
      <SoarSectionNav /><a-button @click="load"><ReloadOutlined /> 刷新</a-button>
    </PageHeader>
    <a-card class="surface-card">
      <div class="filter-bar"><span>状态</span><a-select v-model:value="status" allow-clear placeholder="全部状态" style="width:180px" :options="statuses" @change="load" /></div>
    </a-card>
    <a-card class="surface-card">
      <LoadState :loading="loading" :error="error" :empty="!executions.length" @retry="load">
        <a-table row-key="id" :data-source="executions" :columns="columns" :pagination="{ pageSize: 20 }">
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'id'"><router-link :to="`/soar/executions/${encodeURIComponent(record.id)}`"><code>{{ record.id }}</code></router-link><div class="muted">{{ record.eventType }}</div></template>
            <template v-else-if="column.key === 'playbook'"><strong>{{ record.playbookName }}</strong><div class="muted">revision {{ record.playbookRevision }}</div></template>
            <template v-else-if="column.key === 'object'"><a-tag>{{ record.objectType }}</a-tag><code>{{ record.objectId }}</code></template>
            <template v-else-if="column.key === 'status'"><StatusTag :value="record.status" /><div v-if="record.currentNodeId"><code>{{ record.currentNodeId }}</code></div></template>
            <template v-else-if="column.key === 'updated'"><TimeText :value="record.updatedAt" /></template>
            <template v-else-if="column.key === 'action'"><a-button size="small" @click="router.push(`/soar/executions/${encodeURIComponent(record.id)}`)">查看节点 I/O</a-button></template>
          </template>
        </a-table>
      </LoadState>
    </a-card>
  </div>
</template>

<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { ReloadOutlined } from '@ant-design/icons-vue'
import { useRouter } from 'vue-router'
import { listSoarExecutions } from '../../api/index.js'
import LoadState from '../../components/common/LoadState.vue'
import PageHeader from '../../components/common/PageHeader.vue'
import StatusTag from '../../components/common/StatusTag.vue'
import TimeText from '../../components/common/TimeText.vue'
import SoarSectionNav from '../../components/soar/SoarSectionNav.vue'

const router = useRouter(); const executions = ref([]); const loading = ref(false); const error = ref(''); const status = ref(); let timer
const statuses = ['pending', 'running', 'waiting', 'waiting_human', 'success', 'failed', 'cancelled'].map((value) => ({ value, label: value }))
const columns = [{ key: 'id', title: '执行 / 触发事件' }, { key: 'playbook', title: 'Playbook' }, { key: 'object', title: '处置对象' }, { key: 'status', title: '状态 / 当前节点', width: 170 }, { key: 'updated', title: '更新时间', width: 180 }, { key: 'action', title: '操作', width: 120 }]
async function load() { loading.value = !executions.value.length; error.value = ''; try { executions.value = await listSoarExecutions(status.value) } catch (cause) { error.value = cause.message } finally { loading.value = false; window.clearTimeout(timer); timer = window.setTimeout(load, 5000) } }
onMounted(load); onBeforeUnmount(() => window.clearTimeout(timer))
</script>
