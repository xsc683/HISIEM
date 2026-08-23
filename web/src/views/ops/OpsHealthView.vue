<template>
  <div class="page-shell">
    <PageHeader title="运行态健康扫描" description="同时检查网络探针与 Kafka/Flink/Logstash 业务状态，降级 TCP 只能说明端口监听。"><a-button type="primary" :loading="loading" @click="load"><ThunderboltOutlined /> 执行扫描</a-button></PageHeader>
    <a-alert v-if="scan" :type="scan.status === 'UP' ? 'success' : 'error'" show-icon :message="`总体状态：${scan.status}`" :description="`扫描时间：${scan.scannedAt}`" />
    <a-card class="surface-card" title="组件状态">
      <LoadState :loading="loading" :error="error" :empty="!scan" empty-text="点击“执行扫描”检查六个运行组件" @retry="load">
        <a-table v-if="scan" row-key="name" :data-source="Object.values(scan.components || {})" :columns="columns" :pagination="false">
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'status'"><StatusTag :value="record.status" /></template>
            <template v-else-if="column.key === 'probe'"><a-tag :color="record.degraded ? 'orange' : 'blue'">{{ record.degraded ? '降级 TCP' : record.probe || 'HTTP' }}</a-tag></template>
            <template v-else-if="column.key === 'message'"><span v-if="record.error" class="danger-text">{{ record.error }}</span><span v-else-if="record.warning" style="color:#ad6800">{{ record.warning }}</span><span v-else class="success-text">探针正常</span></template>
          </template>
        </a-table>
      </LoadState>
    </a-card>
    <a-card class="surface-card" title="后台任务"><a-table row-key="id" size="small" :data-source="tasks" :columns="taskColumns" :pagination="{ pageSize: 10 }"><template #bodyCell="{ column, record }"><template v-if="column.key === 'status'"><StatusTag :value="record.status" /></template><template v-else-if="column.key === 'time'"><TimeText :value="record.updatedAt" /></template></template></a-table></a-card>
  </div>
</template>
<script setup>
import { onMounted, ref } from 'vue'; import { ThunderboltOutlined } from '@ant-design/icons-vue'; import { healthScan, listTasks } from '../../api/index.js'; import PageHeader from '../../components/common/PageHeader.vue'; import LoadState from '../../components/common/LoadState.vue'; import StatusTag from '../../components/common/StatusTag.vue'; import TimeText from '../../components/common/TimeText.vue'
const scan = ref(null); const tasks = ref([]); const loading = ref(false); const error = ref(''); const columns = [{ dataIndex: 'name', title: '组件', width: 130 }, { key: 'status', title: '状态', width: 100 }, { dataIndex: 'latencyMs', title: '延迟 ms', width: 100 }, { key: 'probe', title: '探针', width: 120 }, { key: 'message', title: '诊断信息' }]; const taskColumns = [{ dataIndex: 'type', title: '任务' }, { dataIndex: 'resourceId', title: '资源' }, { key: 'status', title: '状态', width: 110 }, { dataIndex: 'progress', title: '进度', width: 90, customRender: ({ text }) => `${text}%` }, { dataIndex: 'message', title: '消息' }, { key: 'time', title: '更新时间', width: 190 }]
async function load() { loading.value = true; error.value = ''; try { [scan.value, tasks.value] = await Promise.all([healthScan(), listTasks()]) } catch (cause) { error.value = cause.message } finally { loading.value = false } } onMounted(load)
</script>
