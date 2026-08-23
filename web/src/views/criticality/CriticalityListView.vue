<template>
  <div class="page-shell">
    <PageHeader title="资产关键度" description="关键度是实体风险计算的业务权重，不应与告警严重级别混为一谈。">
      <a-button :loading="recalculating" @click="recalculate"><SyncOutlined /> 重算实体风险</a-button>
      <a-button type="primary" @click="router.push('/criticality/new')"><PlusOutlined /> 新增资产</a-button>
    </PageHeader>
    <a-card class="surface-card">
      <div class="filter-bar" style="margin-bottom: 16px">
        <a-segmented v-model:value="type" :options="typeOptions" />
        <a-input-search v-model:value="query" allow-clear placeholder="搜索 IP、用户名或主机名" style="width: 300px" />
      </div>
      <LoadState :loading="loading" :error="error" :empty="!rows.length" empty-text="该类别尚未设置资产关键度" @retry="load">
        <a-table row-key="key" :data-source="rows" :columns="columns" :pagination="{ pageSize: 15 }">
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'key'"><code>{{ record.key }}</code></template>
            <template v-else-if="column.key === 'level'"><StatusTag group="criticality" :value="record.level" /></template>
            <template v-else-if="column.key === 'actions'">
              <a-space><a-button size="small" @click="edit(record)">编辑</a-button><a-popconfirm title="删除该关键度配置？" @confirm="remove(record)"><a-button size="small" danger>删除</a-button></a-popconfirm></a-space>
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
import { PlusOutlined, SyncOutlined } from '@ant-design/icons-vue'
import { deleteCriticality, listCriticality, recalcCriticality } from '../../api/index.js'
import PageHeader from '../../components/common/PageHeader.vue'
import LoadState from '../../components/common/LoadState.vue'
import StatusTag from '../../components/common/StatusTag.vue'

const router = useRouter(); const data = ref({}); const type = ref('ip'); const query = ref('')
const loading = ref(false); const recalculating = ref(false); const error = ref('')
const typeOptions = [{ value: 'ip', label: 'IP 地址' }, { value: 'user', label: '用户' }, { value: 'host', label: '主机' }]
const columns = [{ key: 'key', title: '资产标识' }, { key: 'level', title: '关键度', width: 130 }, { dataIndex: 'weight', title: '风险权重', width: 130 }, { key: 'actions', title: '操作', width: 150 }]
const rows = computed(() => {
  const needle = query.value.trim().toLowerCase()
  return Object.entries(data.value[type.value] || {}).map(([key, value]) => ({ key, ...value })).filter((row) => !needle || row.key.toLowerCase().includes(needle))
})
async function load() { loading.value = true; error.value = ''; try { data.value = await listCriticality() } catch (cause) { error.value = cause.message } finally { loading.value = false } }
function edit(row) { router.push(`/criticality/${type.value}/${encodeURIComponent(row.key)}/edit`) }
async function remove(row) { try { await deleteCriticality(type.value, row.key); message.success('关键度配置已删除'); await load() } catch (cause) { message.error(cause.message) } }
async function recalculate() { recalculating.value = true; try { const result = await recalcCriticality(); message.success(result?.message || '实体风险重算任务已提交') } catch (cause) { message.error(cause.message) } finally { recalculating.value = false } }
onMounted(load)
</script>
