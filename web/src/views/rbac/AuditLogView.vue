<template>
  <div class="page-shell">
    <PageHeader title="审计日志" description="记录真实操作者、动作、目标和时间，用于回溯规则、用户及处置变更。"><a-button @click="router.push('/rbac/users')">返回用户列表</a-button><a-button @click="load">刷新</a-button></PageHeader>
    <a-card class="surface-card"><LoadState :loading="loading" :error="error" :empty="!logs.length" @retry="load"><a-table row-key="id" :data-source="rows" :columns="columns" :pagination="{ pageSize: 20 }"><template #bodyCell="{ column, record }"><template v-if="column.key === 'time'"><TimeText :value="record.timestamp" /></template><template v-else-if="column.key === 'target'"><code>{{ record.target }}</code></template></template></a-table></LoadState></a-card>
  </div>
</template>
<script setup>
import { computed, onMounted, ref } from 'vue'; import { useRouter } from 'vue-router'; import { auditLogs } from '../../api/index.js'; import PageHeader from '../../components/common/PageHeader.vue'; import LoadState from '../../components/common/LoadState.vue'; import TimeText from '../../components/common/TimeText.vue'
const router = useRouter(); const logs = ref([]); const loading = ref(false); const error = ref(''); const rows = computed(() => logs.value.slice().reverse().map((item, index) => ({ id: `${item.timestamp}-${index}`, ...item }))); const columns = [{ key: 'time', title: '时间', width: 190 }, { dataIndex: 'actor', title: '操作者', width: 130 }, { dataIndex: 'action', title: '动作', width: 210 }, { key: 'target', title: '目标' }]
async function load() { loading.value = true; error.value = ''; try { logs.value = await auditLogs() } catch (cause) { error.value = cause.message } finally { loading.value = false } } onMounted(load)
</script>
