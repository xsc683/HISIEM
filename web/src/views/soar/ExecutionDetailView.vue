<template>
  <div class="page-shell">
    <PageHeader :title="execution?.playbookName || 'SOAR 执行详情'" description="每个节点保存解析后的输入、业务输出和错误；执行使用创建时冻结的图快照。">
      <a-button @click="router.push('/soar/executions')">返回执行列表</a-button><a-button v-if="cancellable" danger @click="cancel">取消执行</a-button>
    </PageHeader>
    <LoadState :loading="loading" :error="error" :empty="!execution" @retry="load">
      <template v-if="execution">
        <a-alert v-if="execution.error" type="error" show-icon message="执行失败" :description="execution.error" />
        <a-card class="surface-card">
          <a-descriptions bordered :column="3">
            <a-descriptions-item label="执行 ID"><code>{{ execution.id }}</code></a-descriptions-item><a-descriptions-item label="状态"><StatusTag :value="execution.status" /></a-descriptions-item><a-descriptions-item label="当前节点"><code>{{ execution.currentNodeId || '—' }}</code></a-descriptions-item>
            <a-descriptions-item label="Playbook"><code>{{ execution.playbookId }} @ {{ execution.playbookRevision }}</code></a-descriptions-item><a-descriptions-item label="入口事件">{{ execution.eventType }}</a-descriptions-item><a-descriptions-item label="消息 ID"><code>{{ execution.triggerMessageId }}</code></a-descriptions-item>
            <a-descriptions-item label="对象">{{ execution.objectType }}: <code>{{ execution.objectId }}</code></a-descriptions-item><a-descriptions-item label="Kafka 位置"><code>{{ triggerPosition }}</code></a-descriptions-item><a-descriptions-item label="创建时间"><TimeText :value="execution.createdAt" /></a-descriptions-item>
            <a-descriptions-item label="完成时间"><TimeText :value="execution.finishedAt" /></a-descriptions-item>
          </a-descriptions>
        </a-card>
        <a-card class="surface-card" title="节点运行记录">
          <a-table row-key="id" size="small" :data-source="execution.nodeRuns" :columns="columns" :pagination="false">
            <template #bodyCell="{ column, record }">
              <template v-if="column.key === 'node'"><strong>{{ record.nodeName }}</strong><div><code>{{ record.nodeId }}</code></div></template>
              <template v-else-if="column.key === 'type'"><a-tag>{{ record.nodeType }}</a-tag></template>
              <template v-else-if="column.key === 'status'"><StatusTag :value="record.status" /></template>
              <template v-else-if="column.key === 'attempt'"><code>#{{ record.visitNo }}.{{ record.attempt }}</code></template>
              <template v-else-if="column.key === 'time'"><TimeText :value="record.finishedAt || record.startedAt" /></template>
              <template v-else-if="column.key === 'io'"><a-popover trigger="click" placement="leftTop"><template #title>节点输入 / 输出 / 错误</template><template #content><pre class="code-panel io-json">{{ JSON.stringify({ input: record.input, output: record.output, error: record.error }, null, 2) }}</pre></template><a-button size="small">查看完整 I/O</a-button></a-popover></template>
            </template>
          </a-table>
        </a-card>
        <a-row :gutter="16"><a-col :span="12"><a-card class="surface-card" title="触发信封与业务快照"><pre class="code-panel">{{ JSON.stringify({ trigger: execution.triggerEnvelope, payload: execution.payloadSnapshot }, null, 2) }}</pre></a-card></a-col><a-col :span="12"><a-card class="surface-card" title="Playbook 图快照"><pre class="code-panel">{{ JSON.stringify(execution.graphSnapshot, null, 2) }}</pre></a-card></a-col></a-row>
      </template>
    </LoadState>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { message } from 'ant-design-vue'
import { useRoute, useRouter } from 'vue-router'
import { cancelSoarExecution, getSoarExecution } from '../../api/index.js'
import LoadState from '../../components/common/LoadState.vue'
import PageHeader from '../../components/common/PageHeader.vue'
import StatusTag from '../../components/common/StatusTag.vue'
import TimeText from '../../components/common/TimeText.vue'
const route = useRoute(); const router = useRouter(); const execution = ref(); const loading = ref(false); const error = ref(''); let timer
const cancellable = computed(() => execution.value && !['success', 'failed', 'cancelled'].includes(execution.value.status))
const triggerPosition = computed(() => { const kafka = execution.value?.triggerEnvelope?.kafka; return kafka ? `${kafka.topic}[${kafka.partition}]@${kafka.offset}` : '非 Kafka / 历史执行' })
const columns = [{ key: 'node', title: '节点' }, { key: 'type', title: '类型', width: 110 }, { key: 'attempt', title: '访问 / 尝试', width: 100 }, { key: 'status', title: '状态', width: 120 }, { key: 'time', title: '时间', width: 180 }, { dataIndex: 'error', title: '错误' }, { key: 'io', title: '输入 / 输出', width: 130 }]
async function load() { loading.value = !execution.value; error.value = ''; try { execution.value = await getSoarExecution(route.params.id); if (cancellable.value) { window.clearTimeout(timer); timer = window.setTimeout(load, 2500) } } catch (cause) { error.value = cause.message } finally { loading.value = false } }
async function cancel() { try { await cancelSoarExecution(execution.value.id); message.success('执行已取消'); await load() } catch (cause) { message.error(cause.message) } }
onMounted(load); onBeforeUnmount(() => window.clearTimeout(timer))
</script>
<style scoped>.io-json { width:680px; max-height:500px; }</style>
