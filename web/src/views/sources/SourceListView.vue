<template>
  <div class="page-shell">
    <PageHeader title="数据源" description="查看接入端点、解析模板和生命周期状态；创建配置与运行态列表分开维护。">
      <a-button @click="load"><ReloadOutlined /> 刷新</a-button>
      <a-button @click="router.push('/parser-templates')">解析规则库</a-button>
      <a-button type="primary" @click="router.push('/sources/new')"><PlusOutlined /> 新建数据源</a-button>
    </PageHeader>
    <a-card class="surface-card">
      <div class="filter-bar" style="margin-bottom: 16px">
        <a-input-search v-model:value="query" allow-clear placeholder="搜索名称、ID、模板或端点" style="width: 320px" />
        <a-select v-model:value="status" style="width: 140px" :options="statusOptions" />
      </div>
      <LoadState :loading="loading" :error="error" :empty="!filtered.length" empty-text="尚未创建数据源" @retry="load">
        <a-table row-key="id" :data-source="filtered" :columns="columns" :pagination="{ pageSize: 12 }">
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'name'">
              <router-link :to="`/sources/${encodeURIComponent(record.id)}`"><strong>{{ record.name }}</strong></router-link>
              <div><code class="mono-id">{{ record.id }}</code></div>
            </template>
            <template v-else-if="column.key === 'protocol'"><a-tag>{{ displayLabel('protocol', record.protocol) }}</a-tag></template>
            <template v-else-if="column.key === 'endpoint'"><code>{{ record.protocol === 'file' ? record.path : `${record.protocol}:${record.port}` }}</code></template>
            <template v-else-if="column.key === 'status'"><StatusTag :value="record.status" /></template>
            <template v-else-if="column.key === 'updatedAt'"><TimeText :value="record.updatedAt" /></template>
            <template v-else-if="column.key === 'actions'">
              <a-space>
                <a-button size="small" @click="router.push(`/sources/${encodeURIComponent(record.id)}`)">详情</a-button>
                <a-button v-if="['creating', 'failed', 'stopped'].includes(record.status)" size="small" type="primary" :loading="working === record.id" @click="activate(record)">生效</a-button>
                <a-button v-if="record.status === 'active'" size="small" :loading="working === record.id" @click="deactivate(record)">停用</a-button>
                <a-popconfirm v-if="record.status !== 'active'" title="确认删除该数据源声明？" @confirm="remove(record)"><a-button size="small" danger>删除</a-button></a-popconfirm>
              </a-space>
            </template>
          </template>
        </a-table>
      </LoadState>
    </a-card>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons-vue'
import { activateLogSource, deactivateLogSource, deleteLogSource, listLogSources } from '../../api/index.js'
import PageHeader from '../../components/common/PageHeader.vue'
import LoadState from '../../components/common/LoadState.vue'
import StatusTag from '../../components/common/StatusTag.vue'
import TimeText from '../../components/common/TimeText.vue'
import { displayLabel } from '../../utils/display.js'

const router = useRouter()
const sources = ref([])
const loading = ref(false)
const error = ref('')
const query = ref('')
const status = ref('all')
const working = ref('')
const statusOptions = [{ value: 'all', label: '全部状态' }, ...['active', 'creating', 'failed', 'stopped'].map((value) => ({ value, label: displayLabel('status', value) }))]
const columns = [
  { key: 'name', title: '数据源', width: 260 }, { dataIndex: 'templateId', title: '解析模板', width: 160 },
  { key: 'protocol', title: '协议', width: 100 }, { key: 'endpoint', title: '采集端点', width: 220 },
  { key: 'status', title: '状态', width: 110 }, { key: 'updatedAt', title: '更新时间', width: 190 },
  { key: 'actions', title: '操作', width: 240 },
]
const filtered = computed(() => sources.value.filter((source) => {
  if (status.value !== 'all' && source.status !== status.value) return false
  const needle = query.value.trim().toLowerCase()
  return !needle || JSON.stringify(source).toLowerCase().includes(needle)
}))

async function load() {
  loading.value = true
  error.value = ''
  try { sources.value = await listLogSources() } catch (cause) { error.value = cause.message } finally { loading.value = false }
}
async function activate(source) {
  working.value = source.id
  try { await activateLogSource(source.id); message.success('生效任务已提交，可进入详情查看进度'); await load() } catch (cause) { message.error(cause.message) } finally { working.value = '' }
}
async function deactivate(source) {
  working.value = source.id
  try { await deactivateLogSource(source.id); message.success('停用任务已提交'); await load() } catch (cause) { message.error(cause.message) } finally { working.value = '' }
}
async function remove(source) {
  working.value = source.id
  try { await deleteLogSource(source.id); message.success('数据源已删除'); await load() } catch (cause) { message.error(cause.message) } finally { working.value = '' }
}
onMounted(load)
</script>
