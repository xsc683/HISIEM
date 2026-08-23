<template>
  <div class="page-shell">
    <PageHeader title="告警台" description="围绕风险、规则、实体和处置状态排序；完整证据在独立详情页中展开。">
      <a-button @click="load"><ReloadOutlined /> 刷新</a-button>
      <a-button type="primary" :disabled="!selectedKeys.length" @click="createCaseFromSelection">选中建案</a-button>
    </PageHeader>
    <a-card class="surface-card">
      <div class="filter-bar" style="margin-bottom: 16px">
        <a-select v-model:value="status" style="width: 155px" :options="statusOptions" @change="load" />
        <a-input-search v-model:value="query" allow-clear placeholder="搜索规则、实体、用户或主机" style="width: 320px" />
        <a-select placeholder="批量分析结论" style="width: 170px" :disabled="!selectedKeys.length" :options="verdictOptions" @change="batchVerdict" />
        <a-button :disabled="!selectedKeys.length" @click="batchStatus('acknowledged')">批量确认</a-button>
        <a-button danger :disabled="!selectedKeys.length" @click="batchStatus('closed')">批量关闭</a-button>
        <span class="muted">已选择 {{ selectedKeys.length }} 条</span>
      </div>
      <LoadState :loading="loading" :error="error" :empty="!filtered.length" empty-text="当前筛选条件下没有告警" @retry="load">
        <a-table row-key="_id" :data-source="filtered" :columns="columns" :row-selection="rowSelection" :scroll="{ x: 1450 }" :pagination="{ pageSize: 15, showSizeChanger: true }">
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'risk'"><a-tag :color="riskColor(record['alert.risk_score'])">{{ record['alert.risk_score'] ?? '—' }}</a-tag></template>
            <template v-else-if="column.key === 'rule'">
              <router-link :to="`/alerts/${encodeURIComponent(record._id)}`"><strong>{{ record['alert.rule_name'] || '未命名告警' }}</strong></router-link>
              <div><code class="mono-id">{{ record['alert.rule_id'] }}</code></div>
            </template>
            <template v-else-if="column.key === 'severity'"><StatusTag group="severity" :value="record['alert.severity']" /></template>
            <template v-else-if="column.key === 'entity'"><code>{{ entityOf(record) }}</code><div class="muted">{{ record['user.name'] || record['host.name'] || '—' }}</div></template>
            <template v-else-if="column.key === 'status'"><StatusTag :value="record['alert.status']" /></template>
            <template v-else-if="column.key === 'verdict'"><StatusTag v-if="record['alert.analyst_verdict']" group="verdict" :value="record['alert.analyst_verdict']" /><span v-else class="muted">未判定</span></template>
            <template v-else-if="column.key === 'case'"><router-link v-if="record['alert.case_id']" :to="`/cases/${encodeURIComponent(record['alert.case_id'])}`">{{ record['alert.case_id'] }}</router-link><span v-else class="muted">未归案</span></template>
            <template v-else-if="column.key === 'eventTime'"><TimeText :value="record['@timestamp']" /></template>
            <template v-else-if="column.key === 'createdTime'"><TimeText :value="record['alert.created_at']" /></template>
            <template v-else-if="column.key === 'actions'"><a-button size="small" @click="router.push(`/alerts/${encodeURIComponent(record._id)}`)">调查详情</a-button></template>
          </template>
        </a-table>
      </LoadState>
    </a-card>
    <a-card class="surface-card" title="规则误报率观察">
      <LoadState :loading="loading" :error="''" :empty="!fpRates.length" empty-text="暂无已标注的误报率数据">
        <a-table row-key="ruleId" size="small" :data-source="fpRates" :pagination="false" :columns="fpColumns">
          <template #bodyCell="{ column, record }"><template v-if="column.key === 'rate'"><a-tag :color="record.high ? 'red' : 'green'">{{ record.fpRate }}%</a-tag></template></template>
        </a-table>
      </LoadState>
    </a-card>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { ReloadOutlined } from '@ant-design/icons-vue'
import { batchAlertStatus, batchAlertVerdict, fpRate, listAlerts } from '../../api/index.js'
import PageHeader from '../../components/common/PageHeader.vue'
import LoadState from '../../components/common/LoadState.vue'
import StatusTag from '../../components/common/StatusTag.vue'
import TimeText from '../../components/common/TimeText.vue'
import { displayLabel, entityOf, riskColor } from '../../utils/display.js'

const router = useRouter()
const alerts = ref([])
const fpRates = ref([])
const status = ref('open')
const query = ref('')
const selectedKeys = ref([])
const loading = ref(false)
const error = ref('')
const statusOptions = ['open', 'acknowledged', 'investigating', 'resolved', 'closed'].map((value) => ({ value, label: displayLabel('status', value) }))
const verdictOptions = ['true_positive', 'false_positive', 'duplicate'].map((value) => ({ value, label: displayLabel('verdict', value) }))
const columns = [
  { key: 'risk', title: '风险', width: 75, fixed: 'left' }, { key: 'rule', title: '规则', width: 260, fixed: 'left' },
  { key: 'severity', title: '级别', width: 85 }, { key: 'entity', title: '实体', width: 180 }, { key: 'status', title: '状态', width: 100 },
  { key: 'verdict', title: '分析结论', width: 115 }, { key: 'case', title: '案件', width: 150 },
  { key: 'eventTime', title: '事件时间 / 窗口结束', width: 185 }, { key: 'createdTime', title: '告警生成时间', width: 185 },
  { key: 'actions', title: '操作', width: 100, fixed: 'right' },
]
const fpColumns = [{ dataIndex: 'ruleId', title: '规则 ID' }, { dataIndex: 'total', title: '总数' }, { dataIndex: 'fp', title: '误报' }, { dataIndex: 'tp', title: '真实攻击' }, { key: 'rate', title: '误报率' }]
const filtered = computed(() => {
  const needle = query.value.trim().toLowerCase()
  return alerts.value.filter((alert) => !needle || JSON.stringify(alert).toLowerCase().includes(needle))
})
const rowSelection = computed(() => ({ selectedRowKeys: selectedKeys.value, onChange: (keys) => { selectedKeys.value = keys } }))

async function load() {
  loading.value = true; error.value = ''; selectedKeys.value = []
  try {
    const [alertData, rateData] = await Promise.all([listAlerts(status.value), fpRate()])
    alerts.value = alertData
    fpRates.value = rateData
  } catch (cause) { error.value = cause.message } finally { loading.value = false }
}
async function batchStatus(value) {
  try { await batchAlertStatus(selectedKeys.value, value); message.success('批量状态已更新'); await load() } catch (cause) { message.error(cause.message) }
}
async function batchVerdict(value) {
  try { await batchAlertVerdict(selectedKeys.value, value); message.success('批量分析结论已更新'); await load() } catch (cause) { message.error(cause.message) }
}
function createCaseFromSelection() { router.push({ path: '/cases/new', query: { alerts: selectedKeys.value.join(',') } }) }
onMounted(load)
</script>
