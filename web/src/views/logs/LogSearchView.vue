<template>
  <div class="page-shell log-search-page">
    <PageHeader title="日志检索" description="按归一化字段组合筛选 Elasticsearch 安全事件，展开原始 JSON 追溯完整上下文。">
      <span v-if="result.tookMs !== null" class="muted">查询耗时 {{ result.tookMs }} ms</span>
      <a-button :loading="loading" @click="runSearch(page)"><ReloadOutlined /> 刷新</a-button>
      <a-button type="primary" :loading="loading" @click="runSearch(0)"><SearchOutlined /> 检索</a-button>
    </PageHeader>

    <a-card class="surface-card query-card" size="small">
      <div class="time-row">
        <span class="filter-label">事件时间</span>
        <a-range-picker
          v-model:value="timeRange"
          show-time
          value-format="YYYY-MM-DDTHH:mm:ssZ"
          :placeholder="['开始时间（可选）', '结束时间（可选）']"
          @ok="runSearch(0)"
        />
        <a-select v-model:value="sort" :options="sortOptions" style="width: 150px" />
        <a-button @click="resetFilters">重置条件</a-button>
      </div>
      <a-divider class="compact-divider" />
      <LogFilterBuilder
        v-model:filters="filters"
        v-model:logic="logic"
        :field-options="fieldOptions"
        :fields-loading="fieldsLoading"
        :fields-error="fieldsError"
        @retry-fields="loadFields"
        @search="runSearch(0)"
      />
    </a-card>

    <a-card class="surface-card result-card" size="small">
      <div class="result-heading">
        <div><strong>检索结果</strong><span class="result-count">共 {{ result.total.toLocaleString() }} 条</span></div>
        <span class="muted">点击“查看 JSON”可查看未截断的完整日志</span>
      </div>
      <LoadState :loading="loading" :error="error" :empty="!result.items.length" empty-text="当前条件下没有匹配日志" @retry="runSearch(page)">
        <a-table
          row-key="_rowKey"
          size="small"
          :data-source="rows"
          :columns="columns"
          :scroll="{ x: 1320 }"
          :pagination="pagination"
          @change="changeTable"
        >
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'timestamp'"><TimeText :value="sourceOf(record)['@timestamp']" /></template>
            <template v-else-if="column.key === 'category'">
              <strong>{{ sourceOf(record)['event.category'] || '—' }}</strong>
              <div class="muted">{{ sourceOf(record)['event.action'] || sourceOf(record)['event.type'] || '—' }}</div>
            </template>
            <template v-else-if="column.key === 'origin'">
              <code>{{ sourceOf(record)['source.ip'] || sourceOf(record)['client.ip'] || '—' }}</code>
              <div class="muted single-line">{{ sourceOf(record)['host.name'] || sourceOf(record)['user.name'] || '—' }}</div>
            </template>
            <template v-else-if="column.key === 'message'">
              <div class="message-cell">{{ sourceOf(record).message || sourceOf(record)['event.original'] || '—' }}</div>
            </template>
            <template v-else-if="column.key === 'index'"><code class="index-name">{{ record._index || '—' }}</code></template>
            <template v-else-if="column.key === 'actions'"><a-button size="small" @click="openDetail(record)">查看 JSON</a-button></template>
          </template>
        </a-table>
      </LoadState>
    </a-card>

    <a-drawer v-model:open="detailOpen" title="完整日志 JSON" width="min(760px, 72vw)" destroy-on-close>
      <div v-if="selectedRow" class="detail-meta">
        <span>索引 <code>{{ selectedRow._index || '—' }}</code></span>
        <span>文档 ID <code>{{ selectedRow._id || '—' }}</code></span>
      </div>
      <pre class="code-panel log-json">{{ selectedJson }}</pre>
    </a-drawer>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { message } from 'ant-design-vue'
import { ReloadOutlined, SearchOutlined } from '@ant-design/icons-vue'
import PageHeader from '../../components/common/PageHeader.vue'
import LoadState from '../../components/common/LoadState.vue'
import TimeText from '../../components/common/TimeText.vue'
import LogFilterBuilder from './LogFilterBuilder.vue'
import { fetchLogFields, searchLogs } from './logSearchApi.js'
import { createEmptyFilter, normalizeFieldOptions, validateLogFilters } from './logSearchQuery.js'

const fieldsLoading = ref(false)
const fieldsError = ref('')
const fieldOptions = ref([])
const loading = ref(false)
const error = ref('')
const filters = ref([createEmptyFilter('initial')])
const logic = ref('AND')
const timeRange = ref([])
const sort = ref('desc')
const page = ref(0)
const pageSize = ref(25)
const detailOpen = ref(false)
const selectedRow = ref(null)
const result = ref({ items: [], page: 0, size: 25, total: 0, tookMs: null })
const sortOptions = [{ value: 'desc', label: '时间从新到旧' }, { value: 'asc', label: '时间从旧到新' }]
const columns = [
  { key: 'timestamp', title: '事件时间', width: 185, fixed: 'left' },
  { key: 'category', title: '类别 / 动作', width: 175 },
  { key: 'origin', title: '来源 / 主体', width: 190 },
  { key: 'message', title: '日志摘要', width: 470 },
  { key: 'index', title: 'Elasticsearch 索引', width: 220 },
  { key: 'actions', title: '操作', width: 100, fixed: 'right' },
]

const rows = computed(() => result.value.items.map((item, index) => ({
  ...item,
  _rowKey: item._id ? `${item._index || ''}:${item._id}` : `${page.value}:${index}`,
})))
const pagination = computed(() => ({
  current: page.value + 1,
  pageSize: pageSize.value,
  total: result.value.total,
  showSizeChanger: true,
  pageSizeOptions: ['10', '25', '50', '100'],
  showTotal: (total) => `共 ${total.toLocaleString()} 条`,
}))
const selectedJson = computed(() => selectedRow.value ? JSON.stringify(documentOf(selectedRow.value), null, 2) : '')
let searchSequence = 0

function sourceOf(record) {
  return record?._source && typeof record._source === 'object' ? record._source : record || {}
}
function documentOf(record) {
  const source = sourceOf(record)
  if (source !== record) return source
  const { _rowKey, ...document } = source
  return document
}
async function loadFields() {
  fieldsLoading.value = true
  fieldsError.value = ''
  try {
    fieldOptions.value = normalizeFieldOptions(await fetchLogFields())
    if (!fieldOptions.value.length) fieldsError.value = '字段字典为空，请确认 Logstash 归一化字段已被后端发现。'
  } catch (cause) {
    fieldsError.value = `字段字典加载失败：${cause.message}`
  } finally {
    fieldsLoading.value = false
  }
}
async function runSearch(targetPage = 0) {
  const validationError = validateLogFilters(filters.value)
  if (validationError) {
    message.warning(validationError)
    return
  }
  const sequence = ++searchSequence
  loading.value = true
  error.value = ''
  try {
    const data = await searchLogs({
      filters: filters.value,
      logic: logic.value,
      from: timeRange.value?.[0],
      to: timeRange.value?.[1],
      page: targetPage,
      size: pageSize.value,
      sort: sort.value,
    })
    if (sequence !== searchSequence) return
    result.value = data
    page.value = data.page
    pageSize.value = data.size
  } catch (cause) {
    if (sequence !== searchSequence) return
    error.value = cause.message
    result.value = { items: [], page: targetPage, size: pageSize.value, total: 0, tookMs: null }
    page.value = targetPage
  } finally {
    if (sequence === searchSequence) loading.value = false
  }
}
function resetFilters() {
  filters.value = [createEmptyFilter()]
  logic.value = 'AND'
  timeRange.value = []
  sort.value = 'desc'
}
function changeTable(paginationState) {
  pageSize.value = paginationState.pageSize
  runSearch(Math.max(0, paginationState.current - 1))
}
function openDetail(record) {
  selectedRow.value = record
  detailOpen.value = true
}

onMounted(() => Promise.all([loadFields(), runSearch(0)]))
</script>

<style scoped>
.query-card :deep(.ant-card-body), .result-card :deep(.ant-card-body) { padding: 14px 16px; }
.time-row { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.filter-label { color: #526879; font-weight: 600; }
.compact-divider { margin: 12px 0; }
.result-heading { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin-bottom: 10px; }
.result-count { margin-left: 10px; color: #718294; font-size: 12px; }
.message-cell { display: -webkit-box; overflow: hidden; -webkit-box-orient: vertical; -webkit-line-clamp: 2; line-height: 1.45; overflow-wrap: anywhere; }
.single-line { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.index-name { overflow-wrap: anywhere; font-size: 11px; }
.detail-meta { display: flex; flex-direction: column; gap: 5px; margin-bottom: 12px; color: #526879; }
.detail-meta code { overflow-wrap: anywhere; }
.log-json { max-height: calc(100vh - 155px); white-space: pre; }
@media (max-width: 900px) {
  .time-row .ant-picker-range { min-width: 280px; flex: 1 1 380px; }
  .result-heading { align-items: flex-start; }
}
@media (max-width: 600px) {
  .query-card :deep(.ant-card-body), .result-card :deep(.ant-card-body) { padding: 12px; }
  .filter-label { width: 100%; }
  .time-row .ant-picker-range, .time-row .ant-select { width: 100% !important; min-width: 0; flex-basis: 100%; }
  .time-row > .ant-btn { flex: 1; }
  .result-heading { flex-direction: column; gap: 4px; }
  .result-count { display: inline-block; }
}
</style>
