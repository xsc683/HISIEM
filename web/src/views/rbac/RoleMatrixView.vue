<template>
  <div class="page-shell">
    <PageHeader title="角色权限矩阵" description="平台角色决定控制面操作权限；租户角色另外决定 SOAR 租户成员关系。"><a-button @click="router.push('/rbac/users')">返回用户列表</a-button></PageHeader>
    <a-card class="surface-card"><LoadState :loading="loading" :error="error" :empty="!roles.length" @retry="load"><a-table row-key="name" :data-source="roles" :columns="columns" :pagination="false"><template #bodyCell="{ column, record }"><template v-if="column.key === 'name'"><StatusTag group="role" :value="record.name" /></template><template v-else-if="column.key === 'permissions'"><a-tag v-for="permission in record.permissions || []" :key="permission">{{ permission }}</a-tag></template></template></a-table></LoadState></a-card>
  </div>
</template>
<script setup>
import { onMounted, ref } from 'vue'; import { useRouter } from 'vue-router'; import { listRoles } from '../../api/index.js'; import PageHeader from '../../components/common/PageHeader.vue'; import LoadState from '../../components/common/LoadState.vue'; import StatusTag from '../../components/common/StatusTag.vue'
const router = useRouter(); const roles = ref([]); const loading = ref(false); const error = ref(''); const columns = [{ key: 'name', title: '角色', width: 180 }, { key: 'permissions', title: '权限' }]
async function load() { loading.value = true; error.value = ''; try { roles.value = await listRoles() } catch (cause) { error.value = cause.message } finally { loading.value = false } } onMounted(load)
</script>
