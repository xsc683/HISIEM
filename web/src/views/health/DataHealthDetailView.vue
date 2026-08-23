<template>
  <div class="page-shell">
    <PageHeader :title="source?.sourceName || '数据源健康详情'" description="趋势与解析失败来自不同索引分支，原始失败不会进入正常检测链路。"><a-button @click="router.push('/health')">返回数据健康</a-button><a-button @click="load">刷新</a-button></PageHeader>
    <LoadState :loading="loading" :error="error" :empty="!source" @retry="load">
      <template v-if="source">
        <div class="metric-strip"><div class="metric"><span class="metric-label">近 1h 成功</span><span class="metric-value">{{ source.events1h }}</span></div><div class="metric"><span class="metric-label">近 1h 失败</span><span class="metric-value">{{ source.failures1h }}</span></div><div class="metric"><span class="metric-label">失败率</span><span class="metric-value">{{ source.failRate }}%</span></div><div class="metric"><span class="metric-label">最后收到</span><span class="metric-value" style="font-size: 14px"><TimeText :value="source.lastSeen" /></span></div></div>
        <a-alert v-if="source.anomalous" type="error" show-icon message="数据源解析异常" :description="source.reason" />
        <a-card class="surface-card" title="近 24 小时事件趋势">
          <div v-if="trend.length" class="bars"><div v-for="(bucket, index) in trend" :key="index" class="bar-column" :title="`${bucket.bucket}: 成功 ${bucket.events || 0} / 失败 ${bucket.failures || 0}`"><div class="failure" :style="{ height: `${height(bucket.failures)}px` }"></div><div class="success" :style="{ height: `${height(bucket.events)}px` }"></div></div></div><a-empty v-else description="暂无趋势数据" />
        </a-card>
        <a-card class="surface-card" title="最近解析失败原文">
          <a-table row-key="_id" size="small" :data-source="failures" :columns="columns" :pagination="{ pageSize: 12 }"><template #bodyCell="{ column, record }"><template v-if="column.key === 'time'"><TimeText :value="record['@timestamp']" /></template><template v-else-if="column.key === 'message'"><span class="failure-message">{{ record.message || record['event.original'] }}</span></template></template></a-table>
        </a-card>
      </template>
    </LoadState>
  </div>
</template>
<script setup>
import { onMounted, ref } from 'vue'; import { useRoute, useRouter } from 'vue-router'; import { dataHealthFailures, dataHealthSources, dataHealthTrend } from '../../api/index.js'; import PageHeader from '../../components/common/PageHeader.vue'; import LoadState from '../../components/common/LoadState.vue'; import TimeText from '../../components/common/TimeText.vue'
const route = useRoute(); const router = useRouter(); const source = ref(null); const trend = ref([]); const failures = ref([]); const loading = ref(false); const error = ref(''); const columns = [{ key: 'time', title: '时间', width: 190 }, { key: 'message', title: '失败原文' }, { dataIndex: 'tags', title: '标签', width: 180 }]
function height(value) { return Math.max(2, Math.min(110, Number(value || 0) * 3)) } async function load() { loading.value = true; error.value = ''; try { const [all, trendData, failureData] = await Promise.all([dataHealthSources(), dataHealthTrend(route.params.sourceId), dataHealthFailures(route.params.sourceId)]); source.value = all.find((item) => item.sourceId === route.params.sourceId) || { sourceId: route.params.sourceId }; trend.value = trendData; failures.value = failureData } catch (cause) { error.value = cause.message } finally { loading.value = false } } onMounted(load)
</script>
<style scoped>.bars { height: 140px; display: flex; align-items: flex-end; gap: 4px; padding: 12px; border-bottom: 1px solid #dce5eb; overflow-x: auto; }.bar-column { width: 12px; min-width: 12px; display: flex; flex-direction: column; justify-content: flex-end; }.success { background: #3b9d80; }.failure { background: #d1545c; }.failure-message { white-space: pre-wrap; overflow-wrap: anywhere; }</style>
