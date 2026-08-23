<template>
  <div class="page-shell">
    <PageHeader title="新建用户" description="新用户使用临时密码，首次登录必须完成密码轮换。">
      <a-button @click="router.push('/rbac/users')">取消</a-button><a-button type="primary" :loading="saving" @click="submit">创建用户</a-button>
    </PageHeader>
    <a-card class="surface-card" style="max-width: 760px">
      <a-form ref="formRef" layout="vertical" :model="form">
        <a-form-item label="用户名" name="username" :rules="[{ required: true, message: '请输入用户名' }]"><a-input v-model:value="form.username" /></a-form-item>
        <a-form-item label="临时密码" name="password" :rules="[{ required: true, min: 12, message: '临时密码至少 12 位' }]"><a-input-password v-model:value="form.password" /></a-form-item>
        <a-form-item label="平台角色" name="role" :rules="[{ required: true }]"><a-select v-model:value="form.role" :options="roleOptions" /></a-form-item>
      </a-form>
      <a-alert type="warning" show-icon message="请通过安全通道交付临时密码" description="系统不会再次展示密码明文；用户登录后必须立即修改。" />
    </a-card>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { createUser } from '../../api/index.js'
import PageHeader from '../../components/common/PageHeader.vue'
import { displayLabel } from '../../utils/display.js'
const router = useRouter(); const formRef = ref(); const saving = ref(false); const form = reactive({ username: '', password: '', role: 'analyst' })
const roleOptions = ['analyst', 'ops', 'audit', 'admin'].map((value) => ({ value, label: displayLabel('role', value) }))
async function submit() { try { await formRef.value.validate(); saving.value = true; const user = await createUser(form); message.success('用户已创建'); await router.push(`/rbac/users/${encodeURIComponent(user.username)}`) } catch (cause) { if (!cause?.errorFields) message.error(cause.message) } finally { saving.value = false } }
</script>
