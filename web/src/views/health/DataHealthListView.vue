<template>
  <div class="page-shell">
    <PageHeader title="数据健康" description="同时观察成功事件、原始失败事件和数据源声明，避免把解析失败误认为“没有数据”。"><a-button @click="load"><ReloadOutlined /> 刷新</a-button></PageHeader>
    <a-card class="surface-card">
      <LoadState :loading="loading" :error="error" :empty="!sources.length" empty-text="尚无数据源健康统计" @retry="load">
        <a-table row-key="sourceId" :data-source="sources" :columns="columns" :pagination="{ pageSize: 12 }">
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'source'"><router-link :to="`/health/${encodeURIComponent(record.sourceId)}`"><strong>{{ record.sourceName || '未命名数据源' }}</strong></router-link><div><code class="mono-id">{{ record.sourceId }}</code></div></template>
            <template v-else-if="column.key === 'status'"><StatusTag :value="record.status" /><a-tag v-if="record.anomalous" color="red">解析异常</a-tag></template>
            <template v-else-if="column.key === 'rate'"><span :class="record.failRate > 5 ? 'danger-text' : 'success-text'">{{ record.failRate ?? 0 }}%</span></template>
            <template v-else-if="column.key === 'last'"><TimeText :value="record.lastSeen" /></template>
            <template v-else-if="column.key === 'action'"><a-button size="small" @click="router.push(`/health/${encodeURIComponent(record.sourceId)}`)">趋势与失败</a-button></template>
          </template>
        </a-table>
      </LoadState>
    </a-card>
  </div>
</template>
<script setup>
import { onMounted, ref } from 'vue'; import { useRouter } from 'vue-router'; import { ReloadOutlined } from '@ant-design/icons-vue'; import { dataHealthSources } from '../../api/index.js'; import PageHeader from '../../components/common/PageHeader.vue'; import LoadState from '../../components/common/LoadState.vue'; import StatusTag from '../../components/common/StatusTag.vue'; import TimeText from '../../components/common/TimeText.vue'
const router = useRouter(); const sources = ref([]); const loading = ref(false); const error = ref(''); const columns = [{ key: 'source', title: '数据源' }, { key: 'status', title: '状态', width: 180 }, { dataIndex: 'events1h', title: '近 1h 成功', width: 110 }, { dataIndex: 'failures1h', title: '近 1h 失败', width: 110 }, { key: 'rate', title: '失败率', width: 100 }, { dataIndex: 'events24h', title: '近 24h', width: 110 }, { key: 'last', title: '最后收到', width: 190 }, { key: 'action', title: '操作', width: 110 }]
async function load() { loading.value = true; error.value = ''; try { sources.value = await dataHealthSources() } catch (cause) { error.value = cause.message } finally { loading.value = false } } onMounted(load)
</script>
