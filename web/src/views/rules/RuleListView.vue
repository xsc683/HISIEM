<template>
  <div class="page-shell">
    <PageHeader title="检测规则" description="先看懂规则在检测什么，再决定启停、编辑或部署。YAML 是 Flink 与控制面的共同来源。">
      <a-button @click="load"><ReloadOutlined /> 刷新</a-button>
      <a-button type="primary" @click="router.push('/rules/new')"><PlusOutlined /> 新建规则</a-button>
      <a-button type="primary" ghost :loading="deploying" @click="deploy"><ThunderboltOutlined /> 部署生效</a-button>
    </PageHeader>

    <a-alert v-if="deployResult" :type="deployResult.type" show-icon :message="deployResult.message" />
    <a-card class="surface-card">
      <div class="filter-bar" style="margin-bottom: 16px">
        <a-input-search v-model:value="query" placeholder="搜索规则 ID、名称、字段或类型" style="width: 330px" allow-clear />
        <a-select v-model:value="category" style="width: 150px" :options="categoryOptions" />
        <a-select v-model:value="enabled" style="width: 130px" :options="enabledOptions" />
        <span class="muted">当前显示 {{ filteredRules.length }} / {{ rules.length }} 条</span>
      </div>
      <LoadState :loading="loading" :error="error" :empty="!filteredRules.length" empty-text="没有符合条件的检测规则" @retry="load">
        <a-table row-key="id" size="middle" :data-source="filteredRules" :columns="columns" :pagination="{ pageSize: 12, showSizeChanger: true, showTotal: (total) => `共 ${total} 条` }" :scroll="{ x: 1380 }">
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'identity'">
              <router-link :to="`/rules/${encodeURIComponent(record.id)}`"><strong>{{ record.name }}</strong></router-link>
              <div><code class="mono-id">{{ record.id }}</code></div>
            </template>
            <template v-else-if="column.key === 'logic'"><RuleSummary :rule="record" /></template>
            <template v-else-if="column.key === 'category'"><StatusTag group="category" :value="record.category" /></template>
            <template v-else-if="column.key === 'severity'"><StatusTag group="severity" :value="record.severity" /> <a-tag :color="riskColor(record.riskScore)">{{ record.riskScore }}</a-tag></template>
            <template v-else-if="column.key === 'enabled'"><a-switch :checked="record.enabled" checked-children="启用" un-checked-children="停用" :loading="toggling === record.id" @change="toggle(record)" /></template>
            <template v-else-if="column.key === 'hits'"><a-badge :count="hits[record.id]" :show-zero="hits[record.id] != null" :overflow-count="9999" /><span v-if="hits[record.id] == null" class="muted">—</span></template>
            <template v-else-if="column.key === 'tags'"><a-tag v-for="tag in record.tags || []" :key="tag" color="geekblue">{{ tag }}</a-tag></template>
            <template v-else-if="column.key === 'actions'">
              <a-space>
                <a-button size="small" @click="router.push(`/rules/${encodeURIComponent(record.id)}`)">查看逻辑</a-button>
                <a-button v-if="['single_event', 'window'].includes(record.category)" size="small" @click="router.push(`/rules/${encodeURIComponent(record.id)}/edit`)">编辑</a-button>
              </a-space>
            </template>
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
import { PlusOutlined, ReloadOutlined, ThunderboltOutlined } from '@ant-design/icons-vue'
import { deployRules, getRuleHits, listDetectionRules, toggleRule } from '../../api/index.js'
import PageHeader from '../../components/common/PageHeader.vue'
import LoadState from '../../components/common/LoadState.vue'
import StatusTag from '../../components/common/StatusTag.vue'
import RuleSummary from '../../components/rules/RuleSummary.vue'
import { displayLabel, riskColor } from '../../utils/display.js'

const router = useRouter()
const rules = ref([])
const loading = ref(false)
const error = ref('')
const query = ref('')
const category = ref('all')
const enabled = ref('all')
const toggling = ref('')
const deploying = ref(false)
const deployResult = ref(null)
const hits = reactive({})
const categoryOptions = [
  { value: 'all', label: '全部类别' },
  ...['single_event', 'window', 'cep', 'baseline'].map((value) => ({ value, label: displayLabel('category', value) })),
]
const enabledOptions = [{ value: 'all', label: '全部状态' }, { value: 'enabled', label: '已启用' }, { value: 'disabled', label: '已停用' }]
const columns = [
  { key: 'identity', title: '规则', width: 260, fixed: 'left' },
  { key: 'logic', title: '检测逻辑', width: 430 },
  { key: 'category', title: '类别', width: 110 },
  { key: 'severity', title: '级别 / 风险', width: 130 },
  { key: 'enabled', title: '运行开关', width: 105 },
  { key: 'hits', title: '近 7 天命中', width: 105 },
  { key: 'tags', title: 'MITRE', width: 170 },
  { key: 'actions', title: '操作', width: 160, fixed: 'right' },
]

const filteredRules = computed(() => {
  const needle = query.value.trim().toLowerCase()
  return rules.value.filter((rule) => {
    if (category.value !== 'all' && rule.category !== category.value) return false
    if (enabled.value === 'enabled' && !rule.enabled) return false
    if (enabled.value === 'disabled' && rule.enabled) return false
    return !needle || JSON.stringify(rule).toLowerCase().includes(needle)
  })
})

async function load() {
  loading.value = true
  error.value = ''
  try {
    rules.value = await listDetectionRules()
    const results = await Promise.allSettled(rules.value.map((rule) => getRuleHits(rule.id)))
    results.forEach((result, index) => {
      hits[rules.value[index].id] = result.status === 'fulfilled' && result.value.count >= 0 ? result.value.count : null
    })
  } catch (cause) {
    error.value = cause.message
  } finally {
    loading.value = false
  }
}

async function toggle(rule) {
  toggling.value = rule.id
  try {
    const updated = await toggleRule(rule.id)
    Object.assign(rule, updated)
    deployResult.value = { type: 'warning', message: `${rule.name} 已${rule.enabled ? '启用' : '停用'}，需要部署后 Flink 才会采用新状态。` }
  } catch (cause) {
    message.error(cause.message)
  } finally {
    toggling.value = ''
  }
}

async function deploy() {
  deploying.value = true
  deployResult.value = null
  try {
    const result = await deployRules()
    deployResult.value = { type: 'success', message: `规则已经部署，检测 Job：${result.jobId}` }
  } catch (cause) {
    deployResult.value = { type: 'error', message: `部署失败，旧规则继续保留：${cause.message}` }
  } finally {
    deploying.value = false
  }
}

onMounted(load)
</script>
