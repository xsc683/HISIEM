<template>
  <div class="page-shell">
    <PageHeader title="调查案件" description="案件把同一实体的告警、证据、协作者和事件时间线组织为可持续处置的工作单元。">
      <a-button @click="load"><ReloadOutlined /> 刷新</a-button>
      <a-button type="primary" @click="router.push('/cases/new')"><PlusOutlined /> 手动建案</a-button>
    </PageHeader>
    <a-card class="surface-card" title="自动聚合">
      <div class="aggregation-grid">
        <a-form-item label="事件时间窗口（分钟）" style="margin: 0"><a-input-number v-model:value="aggregation.windowMinutes" :min="1" :max="1440" :precision="0" style="width: 180px" /></a-form-item>
        <a-form-item label="最少告警数" style="margin: 0"><a-input-number v-model:value="aggregation.threshold" :min="2" :max="1000" :precision="0" style="width: 150px" /></a-form-item>
        <a-form-item label="分组方式" style="margin: 0"><a-select v-model:value="aggregation.groupByRule" style="width: 180px" :options="groupOptions" /></a-form-item>
        <a-button type="primary" :loading="aggregating" @click="aggregate">立即执行一轮聚合</a-button>
      </div>
      <a-alert type="info" show-icon style="margin-top: 14px" :message="aggregationSummary" description="这里使用告警的事件时间，不是浏览器当前时间或告警写入时间。输入框始终显示实际数字。" />
    </a-card>
    <a-card class="surface-card">
      <div class="filter-bar" style="margin-bottom: 16px">
        <a-select v-model:value="status" style="width: 150px" :options="statusOptions" @change="load" />
        <a-input-search v-model:value="entity" allow-clear placeholder="按实体、标题或案件 ID 搜索" style="width: 320px" />
        <span class="muted">共 {{ filtered.length }} 个案件</span>
      </div>
      <LoadState :loading="loading" :error="error" :empty="!filtered.length" empty-text="尚未形成案件，可执行自动聚合或手动建案" @retry="load">
        <a-table row-key="case.id" :data-source="filtered" :columns="columns" :pagination="{ pageSize: 12 }">
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'case'">
              <router-link :to="`/cases/${encodeURIComponent(record['case.id'])}`"><strong>{{ record['case.title'] }}</strong></router-link>
              <div><code class="mono-id">{{ record['case.id'] }}</code></div>
            </template>
            <template v-else-if="column.key === 'status'"><StatusTag :value="record['case.status']" /></template>
            <template v-else-if="column.key === 'entities'"><a-tag v-for="item in record.entities || []" :key="`${item.type}:${item.value}`" color="blue">{{ item.type }}:{{ item.value }}</a-tag></template>
            <template v-else-if="column.key === 'alerts'">{{ record.alert_ids?.length || 0 }}</template>
            <template v-else-if="column.key === 'owner'">{{ record['case.owner'] || '未分配' }}</template>
            <template v-else-if="column.key === 'updated'"><TimeText :value="record['case.updated_at']" /></template>
            <template v-else-if="column.key === 'actions'"><a-button size="small" @click="router.push(`/cases/${encodeURIComponent(record['case.id'])}`)">进入调查</a-button></template>
          </template>
        </a-table>
      </LoadState>
    </a-card>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons-vue'
import { aggregateCases, listCases } from '../../api/index.js'
import PageHeader from '../../components/common/PageHeader.vue'
import LoadState from '../../components/common/LoadState.vue'
import StatusTag from '../../components/common/StatusTag.vue'
import TimeText from '../../components/common/TimeText.vue'
import { displayLabel } from '../../utils/display.js'

const router = useRouter()
const cases = ref([]); const loading = ref(false); const aggregating = ref(false); const error = ref('')
const status = ref('all'); const entity = ref('')
const aggregation = reactive({ windowMinutes: 30, threshold: 2, groupByRule: false })
const groupOptions = [{ value: false, label: '按实体分组' }, { value: true, label: '按规则 + 实体分组' }]
const statusOptions = [{ value: 'all', label: '全部状态' }, ...['open', 'investigating', 'resolved'].map((value) => ({ value, label: displayLabel('status', value) }))]
const columns = [{ key: 'case', title: '案件', width: 320 }, { key: 'status', title: '状态', width: 105 }, { key: 'entities', title: '关联实体', width: 250 }, { key: 'alerts', title: '告警数', width: 90 }, { key: 'owner', title: '负责人', width: 120 }, { key: 'updated', title: '更新时间', width: 190 }, { key: 'actions', title: '操作', width: 100 }]
const aggregationSummary = computed(() => `当前条件：${aggregation.windowMinutes} 分钟内，同一实体至少 ${aggregation.threshold} 条待处置告警，${aggregation.groupByRule ? '并要求属于同一规则' : '不同规则可合并'}`)
const filtered = computed(() => {
  const needle = entity.value.trim().toLowerCase()
  return cases.value.filter((item) => !needle || JSON.stringify(item).toLowerCase().includes(needle))
})
async function load() { loading.value = true; error.value = ''; try { cases.value = await listCases(status.value === 'all' ? '' : status.value) } catch (cause) { error.value = cause.message } finally { loading.value = false } }
async function aggregate() { aggregating.value = true; try { const result = await aggregateCases(aggregation); message.success(`聚合完成，新建 ${result.created} 个案件`); await load() } catch (cause) { message.error(cause.message) } finally { aggregating.value = false } }
onMounted(load)
</script>

<style scoped>
.aggregation-grid { display: flex; align-items: flex-end; gap: 18px; flex-wrap: wrap; }
@media (max-width: 680px) {
  .aggregation-grid { display: grid; grid-template-columns: 1fr; gap: 11px; }
  .aggregation-grid :deep(.ant-input-number), .aggregation-grid :deep(.ant-select) { width: 100% !important; }
  .aggregation-grid > .ant-btn { width: 100%; }
}
</style>
