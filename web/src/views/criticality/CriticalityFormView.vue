<template>
  <div class="page-shell">
    <PageHeader :title="isEdit ? '编辑资产关键度' : '新增资产关键度'" description="配置保存后需要执行实体风险重算，历史告警风险分不会被直接改写。">
      <a-button @click="router.push('/criticality')">取消</a-button>
      <a-button type="primary" :loading="saving" @click="submit">保存</a-button>
    </PageHeader>
    <a-card class="surface-card" style="max-width: 760px">
      <a-form ref="formRef" layout="vertical" :model="form">
        <a-form-item label="资产类型" name="type" :rules="[{ required: true, message: '请选择资产类型' }]"><a-select v-model:value="form.type" :disabled="isEdit" :options="typeOptions" /></a-form-item>
        <a-form-item label="资产标识" name="key" :rules="[{ required: true, message: '请输入资产标识' }]"><a-input v-model:value="form.key" :disabled="isEdit" :placeholder="form.type === 'ip' ? '192.0.2.10' : form.type === 'user' ? 'admin' : 'server-01'" /></a-form-item>
        <a-form-item label="关键度" name="level" :rules="[{ required: true, message: '请选择关键度' }]"><a-radio-group v-model:value="form.level" :options="levelOptions" option-type="button" button-style="solid" /></a-form-item>
      </a-form>
      <a-alert type="info" show-icon message="关键度影响后续实体风险重算" description="低/中/高/极高对应不同权重；保存本身不会启动重算，便于批量修改后统一执行。" />
    </a-card>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { listCriticality, setCriticality } from '../../api/index.js'
import PageHeader from '../../components/common/PageHeader.vue'
import { displayLabel } from '../../utils/display.js'

const route = useRoute(); const router = useRouter(); const formRef = ref(); const saving = ref(false)
const isEdit = computed(() => Boolean(route.params.key))
const form = reactive({ type: route.params.type || 'ip', key: route.params.key || '', level: 'high' })
const typeOptions = [{ value: 'ip', label: 'IP 地址' }, { value: 'user', label: '用户' }, { value: 'host', label: '主机' }]
const levelOptions = ['low', 'medium', 'high', 'extreme'].map((value) => ({ value, label: displayLabel('criticality', value) }))
async function submit() { try { await formRef.value.validate(); saving.value = true; await setCriticality(form.type, form.key.trim(), form.level); message.success('资产关键度已保存'); await router.push('/criticality') } catch (cause) { if (!cause?.errorFields) message.error(cause.message) } finally { saving.value = false } }
onMounted(async () => { if (!isEdit.value) return; try { const data = await listCriticality(); form.level = data[form.type]?.[form.key]?.level || 'high' } catch (cause) { message.error(cause.message) } })
</script>
