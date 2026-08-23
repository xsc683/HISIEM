<template>
  <div class="page-shell">
    <PageHeader title="用户与权限" description="用户生命周期、角色矩阵与审计日志分别呈现，避免把创建表单堆在列表尾部。">
      <a-button @click="router.push('/rbac/roles')">角色矩阵</a-button>
      <a-button @click="router.push('/rbac/audit')">审计日志</a-button>
      <a-button type="primary" @click="router.push('/rbac/users/new')"><PlusOutlined /> 新建用户</a-button>
    </PageHeader>
    <a-card class="surface-card">
      <div class="filter-bar" style="margin-bottom: 16px"><a-input-search v-model:value="query" allow-clear placeholder="搜索用户名或角色" style="width: 300px" /></div>
      <LoadState :loading="loading" :error="error" :empty="!filtered.length" empty-text="暂无用户" @retry="load">
        <a-table row-key="username" :data-source="filtered" :columns="columns" :pagination="{ pageSize: 12 }">
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'username'"><router-link :to="`/rbac/users/${encodeURIComponent(record.username)}`"><strong>{{ record.username }}</strong></router-link></template>
            <template v-else-if="column.key === 'role'"><StatusTag group="role" :value="record.role" /></template>
            <template v-else-if="column.key === 'status'"><StatusTag :value="record.status" /></template>
            <template v-else-if="column.key === 'password'"><a-tag v-if="record.passwordChangeRequired" color="orange">等待首次改密</a-tag><span v-else class="muted">已轮换</span></template>
            <template v-else-if="column.key === 'created'"><TimeText :value="record.createdAt" /></template>
            <template v-else-if="column.key === 'actions'"><a-button size="small" @click="router.push(`/rbac/users/${encodeURIComponent(record.username)}`)">管理</a-button></template>
          </template>
        </a-table>
      </LoadState>
    </a-card>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { PlusOutlined } from '@ant-design/icons-vue'
import { listUsers } from '../../api/index.js'
import PageHeader from '../../components/common/PageHeader.vue'
import LoadState from '../../components/common/LoadState.vue'
import StatusTag from '../../components/common/StatusTag.vue'
import TimeText from '../../components/common/TimeText.vue'

const router = useRouter(); const users = ref([]); const query = ref(''); const loading = ref(false); const error = ref('')
const columns = [{ key: 'username', title: '用户名' }, { key: 'role', title: '角色', width: 130 }, { key: 'status', title: '状态', width: 110 }, { key: 'password', title: '密码状态', width: 150 }, { key: 'created', title: '创建时间', width: 190 }, { key: 'actions', title: '操作', width: 90 }]
const filtered = computed(() => { const needle = query.value.trim().toLowerCase(); return users.value.filter((user) => !needle || JSON.stringify(user).toLowerCase().includes(needle)) })
async function load() { loading.value = true; error.value = ''; try { users.value = await listUsers() } catch (cause) { error.value = cause.message } finally { loading.value = false } }
onMounted(load)
</script>
