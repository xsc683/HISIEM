<template>
  <div class="page-shell">
    <PageHeader title="手动创建案件" description="至少选择两条待处置告警；创建后告警与案件通过稳定 ID 双向关联。">
      <a-button @click="router.push('/cases')">取消</a-button>
      <a-button type="primary" :loading="saving" :disabled="selected.length < 2" @click="submit">创建案件</a-button>
    </PageHeader>
    <a-card class="surface-card">
      <a-form layout="vertical">
        <a-form-item label="案件标题" extra="留空时由后端根据实体生成"><a-input v-model:value="title" placeholder="例如：核心服务器 SSH 暴力破解调查" /></a-form-item>
      </a-form>
      <div class="filter-bar" style="margin-bottom: 14px">
        <a-input-search v-model:value="query" allow-clear placeholder="筛选规则、实体、用户或主机" style="width: 330px" />
        <a-tag color="blue">已选 {{ selected.length }} 条</a-tag>
      </div>
      <LoadState :loading="loading" :error="error" :empty="!filtered.length" empty-text="没有可用于建案的 open 告警" @retry="load">
        <a-table row-key="_id" :data-source="filtered" :row-selection="rowSelection" :columns="columns" :pagination="{ pageSize: 12 }">
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'rule'"><strong>{{ record['alert.rule_name'] }}</strong><div><code class="mono-id">{{ record['alert.rule_id'] }}</code></div></template>
            <template v-else-if="column.key === 'severity'"><StatusTag group="severity" :value="record['alert.severity']" /></template>
            <template v-else-if="column.key === 'entity'"><code>{{ entityOf(record) }}</code></template>
            <template v-else-if="column.key === 'time'"><TimeText :value="record['@timestamp']" /></template>
          </template>
        </a-table>
      </LoadState>
    </a-card>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { createCase, listAlerts } from '../../api/index.js'
import PageHeader from '../../components/common/PageHeader.vue'
import LoadState from '../../components/common/LoadState.vue'
import StatusTag from '../../components/common/StatusTag.vue'
import TimeText from '../../components/common/TimeText.vue'
import { entityOf } from '../../utils/display.js'

const route = useRoute(); const router = useRouter()
const alerts = ref([]); const selected = ref([]); const title = ref(''); const query = ref('')
const loading = ref(false); const saving = ref(false); const error = ref('')
const columns = [{ key: 'rule', title: '规则' }, { key: 'severity', title: '级别', width: 90 }, { key: 'entity', title: '实体', width: 210 }, { dataIndex: 'event_count', title: '事件数', width: 90 }, { key: 'time', title: '事件时间', width: 190 }]
const filtered = computed(() => { const needle = query.value.trim().toLowerCase(); return alerts.value.filter((item) => !needle || JSON.stringify(item).toLowerCase().includes(needle)) })
const rowSelection = computed(() => ({ selectedRowKeys: selected.value, onChange: (keys) => { selected.value = keys } }))
async function load() {
  loading.value = true; error.value = ''
  try {
    alerts.value = await listAlerts('open')
    const requested = String(route.query.alerts || '').split(',').filter(Boolean)
    selected.value = requested.filter((id) => alerts.value.some((alert) => alert._id === id))
  } catch (cause) { error.value = cause.message } finally { loading.value = false }
}
async function submit() {
  if (selected.value.length < 2) return message.warning('至少选择两条待处置告警')
  saving.value = true
  try { const created = await createCase(selected.value, title.value.trim()); message.success('案件已创建'); await router.push(`/cases/${encodeURIComponent(created['case.id'])}`) } catch (cause) { message.error(cause.message) } finally { saving.value = false }
}
onMounted(load)
</script>
