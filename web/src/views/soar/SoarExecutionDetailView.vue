<template>
  <div class="page-shell">
    <PageHeader :title="execution?.playbookSnapshot?.name || 'SOAR 执行详情'" description="执行使用启动时冻结的 Playbook 快照；节点尝试和不可变事件用于恢复与审计。">
      <a-button @click="router.push('/soar')">返回运行台</a-button>
      <template v-if="execution">
        <a-button v-if="execution.status === 'waiting_approval'" type="primary" @click="decide(true)">批准</a-button><a-button v-if="execution.status === 'waiting_approval'" danger @click="decide(false)">拒绝</a-button>
        <a-button v-if="['queued', 'running'].includes(execution.status)" @click="control(pauseSoarExecution, '执行已暂停')">暂停</a-button>
        <a-button v-if="execution.status === 'paused'" @click="control(resumeSoarExecution, '执行已恢复')">恢复</a-button>
        <a-button v-if="execution.status === 'failed'" @click="control(retrySoarExecution, '执行已重新入队')">重试</a-button>
        <a-button v-if="['queued', 'running', 'waiting_approval', 'waiting_child', 'paused'].includes(execution.status)" danger @click="control(cancelSoarExecution, '执行已取消')">取消</a-button>
      </template>
    </PageHeader>
    <LoadState :loading="loading" :error="error" :empty="!execution" @retry="load">
      <template v-if="execution">
        <a-alert v-if="execution.error" type="error" show-icon message="执行错误 / 恢复信息" :description="execution.error" />
        <a-card class="surface-card">
          <a-descriptions bordered :column="3">
            <a-descriptions-item label="执行 ID"><code>{{ execution.id }}</code></a-descriptions-item><a-descriptions-item label="状态"><StatusTag :value="execution.status" /></a-descriptions-item><a-descriptions-item label="触发方式">{{ execution.triggerType || 'manual' }}</a-descriptions-item>
            <a-descriptions-item label="Playbook"><code>{{ execution.playbookId }} · {{ execution.playbookVersion }}</code></a-descriptions-item><a-descriptions-item label="当前节点"><code>{{ execution.currentNode || '—' }}</code></a-descriptions-item><a-descriptions-item label="已执行节点">{{ execution.nodesExecuted }}</a-descriptions-item>
            <a-descriptions-item label="目标资源">{{ execution.resourceType }}: <code>{{ execution.resourceId }}</code></a-descriptions-item><a-descriptions-item label="租户"><code>{{ execution.tenantId }}</code></a-descriptions-item><a-descriptions-item label="父执行"><router-link v-if="execution.parentExecutionId" :to="`/soar/executions/${encodeURIComponent(execution.parentExecutionId)}`">{{ execution.parentExecutionId }}</router-link><span v-else>—</span></a-descriptions-item>
            <a-descriptions-item label="发起人">{{ execution.actor }}</a-descriptions-item><a-descriptions-item label="审批人">{{ execution.approvedBy || '—' }}</a-descriptions-item><a-descriptions-item label="下次运行"><TimeText :value="execution.nextRunAt" /></a-descriptions-item>
            <a-descriptions-item v-if="execution.approvalMessage" label="待审批说明" :span="3">{{ execution.approvalMessage }}</a-descriptions-item>
            <a-descriptions-item label="Frontier" :span="3"><a-tag v-for="node in execution.frontier || []" :key="node">{{ node }}</a-tag><span v-if="!execution.frontier?.length">—</span></a-descriptions-item>
          </a-descriptions>
        </a-card>
        <a-card class="surface-card" title="节点执行尝试">
          <a-table row-key="stepId" size="small" :data-source="execution.steps || []" :columns="stepColumns" :pagination="false">
            <template #bodyCell="{ column, record }"><template v-if="column.key === 'node'"><strong>{{ record.stepName }}</strong><div><code class="mono-id">{{ record.stepId }}</code></div></template><template v-else-if="column.key === 'action'"><a-tag>{{ record.nodeType }}</a-tag><code>{{ record.action }}</code></template><template v-else-if="column.key === 'attempt'">{{ record.attempt }}/{{ record.maxAttempts }}</template><template v-else-if="column.key === 'status'"><StatusTag :value="record.status" /></template><template v-else-if="column.key === 'duration'">{{ record.durationMs == null ? '—' : `${record.durationMs} ms` }}</template><template v-else-if="column.key === 'time'"><TimeText :value="record.finishedAt" /></template><template v-else-if="column.key === 'details'"><a-popover title="节点输入 / 输出 / 错误" trigger="click"><template #content><pre class="code-panel step-json">{{ JSON.stringify({ input: record.input, output: record.output, error: record.error }, null, 2) }}</pre></template><a-button size="small">查看</a-button></a-popover></template></template>
          </a-table>
        </a-card>
        <a-card class="surface-card" title="不可变执行事件时间线">
          <a-timeline><a-timeline-item v-for="(event, index) in events" :key="event.id || index" :color="event.type?.includes('failed') ? 'red' : 'blue'"><strong>{{ event.type || event.eventType }}</strong> · <TimeText :value="event.createdAt || event.timestamp" /><div class="muted">{{ event.message || event.nodeId || '' }}</div></a-timeline-item></a-timeline>
        </a-card>
      </template>
    </LoadState>
  </div>
</template>

<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'; import { useRoute, useRouter } from 'vue-router'; import { message } from 'ant-design-vue'; import { cancelSoarExecution, decideSoarApproval, getSoarExecution, getSoarExecutionEvents, pauseSoarExecution, resumeSoarExecution, retrySoarExecution } from '../../api/index.js'; import PageHeader from '../../components/common/PageHeader.vue'; import LoadState from '../../components/common/LoadState.vue'; import StatusTag from '../../components/common/StatusTag.vue'; import TimeText from '../../components/common/TimeText.vue'
const route = useRoute(); const router = useRouter(); const execution = ref(null); const events = ref([]); const loading = ref(false); const error = ref(''); let timer
const stepColumns = [{ dataIndex: 'stepIndex', title: '#', width: 50 }, { key: 'node', title: '节点' }, { key: 'action', title: '类型 / 动作' }, { key: 'attempt', title: '尝试', width: 75 }, { key: 'status', title: '状态', width: 110 }, { key: 'duration', title: '耗时', width: 90 }, { key: 'time', title: '完成时间', width: 180 }, { key: 'details', title: '详情', width: 70 }]
async function load() { loading.value = !execution.value; error.value = ''; try { [execution.value, events.value] = await Promise.all([getSoarExecution(route.params.id), getSoarExecutionEvents(route.params.id)]); if (['queued', 'running', 'waiting_child'].includes(execution.value.status)) schedule() } catch (cause) { error.value = cause.message } finally { loading.value = false } }
function schedule() { window.clearTimeout(timer); timer = window.setTimeout(load, 2500) }
async function decide(approved) { try { await decideSoarApproval(execution.value.id, approved); message.success(approved ? '审批已通过' : '审批已拒绝'); await load() } catch (cause) { message.error(cause.message) } }
async function control(action, success) { try { await action(execution.value.id); message.success(success); await load() } catch (cause) { message.error(cause.message) } }
onMounted(load); onBeforeUnmount(() => window.clearTimeout(timer))
</script>
<style scoped>.step-json { width: 640px; max-height: 420px; }</style>
