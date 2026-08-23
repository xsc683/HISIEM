<template>
  <div class="page-shell">
    <PageHeader :title="user?.username || '用户详情'" description="查看安全状态并单独调整角色；密码哈希永远不会返回前端。">
      <a-button @click="router.push('/rbac/users')">返回用户列表</a-button>
    </PageHeader>
    <LoadState :loading="loading" :error="error" :empty="!user" @retry="load">
      <a-card v-if="user" class="surface-card" style="max-width: 900px">
        <a-descriptions bordered :column="2">
          <a-descriptions-item label="用户名"><strong>{{ user.username }}</strong></a-descriptions-item>
          <a-descriptions-item label="状态"><StatusTag :value="user.status" /></a-descriptions-item>
          <a-descriptions-item label="当前角色"><StatusTag group="role" :value="user.role" /></a-descriptions-item>
          <a-descriptions-item label="首次改密"><a-tag :color="user.passwordChangeRequired ? 'orange' : 'green'">{{ user.passwordChangeRequired ? '待完成' : '已完成' }}</a-tag></a-descriptions-item>
          <a-descriptions-item label="创建时间"><TimeText :value="user.createdAt" /></a-descriptions-item>
        </a-descriptions>
        <a-divider>权限调整</a-divider>
        <a-space><a-select v-model:value="role" style="width: 180px" :options="roleOptions" /><a-button type="primary" :loading="saving" @click="saveRole">保存角色</a-button></a-space>
        <a-divider>危险操作</a-divider>
        <a-popconfirm title="确认删除该用户？相关会话会同时失效。" @confirm="remove"><a-button danger :disabled="user.username === auth.state.user?.username">删除用户</a-button></a-popconfirm>
      </a-card>
    </LoadState>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { deleteUser, listUsers, updateUserRole } from '../../api/index.js'
import { useAuth } from '../../composables/useAuth.js'
import PageHeader from '../../components/common/PageHeader.vue'
import LoadState from '../../components/common/LoadState.vue'
import StatusTag from '../../components/common/StatusTag.vue'
import TimeText from '../../components/common/TimeText.vue'
import { displayLabel } from '../../utils/display.js'
const route = useRoute(); const router = useRouter(); const auth = useAuth(); const user = ref(null); const role = ref('analyst'); const loading = ref(false); const saving = ref(false); const error = ref('')
const roleOptions = ['analyst', 'ops', 'audit', 'admin'].map((value) => ({ value, label: displayLabel('role', value) }))
async function load() { loading.value = true; error.value = ''; try { user.value = (await listUsers()).find((item) => item.username === route.params.username) || null; if (!user.value) throw new Error('用户不存在'); role.value = user.value.role } catch (cause) { error.value = cause.message } finally { loading.value = false } }
async function saveRole() { saving.value = true; try { user.value = await updateUserRole(user.value.username, role.value); message.success('用户角色已更新') } catch (cause) { message.error(cause.message) } finally { saving.value = false } }
async function remove() { try { await deleteUser(user.value.username); message.success('用户已删除'); await router.push('/rbac/users') } catch (cause) { message.error(cause.message) } }
onMounted(load)
</script>
