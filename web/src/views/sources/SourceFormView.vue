<template>
  <div class="page-shell">
    <PageHeader title="新建数据源" description="先选择并验证解析模板，再预览将要生成的 Logstash 配置，最后创建声明。">
      <a-button @click="router.push('/sources')">取消</a-button>
      <a-button type="primary" :loading="saving" @click="create">创建数据源</a-button>
    </PageHeader>
    <div class="source-grid">
      <a-card class="surface-card" title="1. 接入信息">
        <a-form ref="formRef" layout="vertical" :model="form">
          <a-form-item label="数据源名称" name="name" :rules="[{ required: true, message: '请输入数据源名称' }]"><a-input v-model:value="form.name" placeholder="例如：核心区 SSH 审计" /></a-form-item>
          <a-form-item label="解析模板" name="templateId" :rules="[{ required: true, message: '请选择解析模板' }]">
            <a-select v-model:value="form.templateId" show-search option-filter-prop="label" :options="templates.map((item) => ({ value: item.id, label: `${item.name} (${item.id})` }))" />
          </a-form-item>
          <a-form-item label="采集协议"><a-segmented v-model:value="form.protocol" :options="protocolOptions" /></a-form-item>
          <a-form-item v-if="form.protocol === 'file'" label="容器内文件路径" name="path" :rules="[{ required: true, message: '请输入文件路径' }]"><a-input v-model:value="form.path" placeholder="/var/log/auth.log" /></a-form-item>
          <a-form-item v-else label="监听端口" name="port" :rules="[{ required: true, message: '请输入端口' }]"><a-input-number v-model:value="form.port" :min="1" :max="65535" style="width: 100%" /></a-form-item>
        </a-form>
        <a-space>
          <a-button :loading="previewing" @click="preview">校验并预览配置</a-button>
          <router-link to="/parser-templates">前往解析规则库测试日志</router-link>
        </a-space>
      </a-card>
      <a-card class="surface-card" title="2. 配置预览">
        <a-empty v-if="!previewResult" description="填写左侧信息后生成预览；预览和运行态由同一生成器编译。" />
        <template v-else>
          <a-alert type="success" show-icon :message="`模板 ${previewResult.template} 校验通过`" style="margin-bottom: 12px" />
          <h4>Input</h4><pre class="code-panel">{{ previewResult.input }}</pre>
          <h4>Filter</h4><pre class="code-panel">{{ previewResult.config }}</pre>
        </template>
      </a-card>
    </div>
    <a-alert type="info" show-icon message="创建不会自动隐藏结果" description="创建成功后进入独立详情页，由你确认配置并启动生效任务；失败状态、任务 ID 和错误会保留。" />
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { createLogSource, listTemplates, previewLogSource } from '../../api/index.js'
import PageHeader from '../../components/common/PageHeader.vue'

const router = useRouter()
const formRef = ref()
const templates = ref([])
const previewResult = ref(null)
const previewing = ref(false)
const saving = ref(false)
const protocolOptions = [{ value: 'tcp', label: 'TCP' }, { value: 'syslog', label: 'Syslog' }, { value: 'file', label: '文件' }]
const form = reactive({ name: '', templateId: '', protocol: 'tcp', port: 5001, path: '' })

function payload() { return { name: form.name.trim(), templateId: form.templateId, protocol: form.protocol, port: form.protocol === 'file' ? 0 : form.port, path: form.protocol === 'file' ? form.path.trim() : null } }
async function preview() {
  try {
    await formRef.value.validate()
    previewing.value = true
    previewResult.value = await previewLogSource(payload())
  } catch (cause) {
    if (!cause?.errorFields) message.error(cause.message)
  } finally { previewing.value = false }
}
async function create() {
  try {
    await formRef.value.validate()
    saving.value = true
    const source = await createLogSource(payload())
    message.success('数据源声明已创建')
    await router.push(`/sources/${encodeURIComponent(source.id)}`)
  } catch (cause) {
    if (!cause?.errorFields) message.error(cause.message)
  } finally { saving.value = false }
}
onMounted(async () => {
  try {
    templates.value = await listTemplates()
    if (templates.value.length) form.templateId = templates.value[0].id
  } catch (cause) { message.error(`解析模板加载失败：${cause.message}`) }
})
</script>

<style scoped>
.source-grid { display: grid; grid-template-columns: minmax(380px, .8fr) minmax(560px, 1.2fr); gap: 16px; }
.code-panel { max-height: 230px; }
@media (max-width: 1120px) { .source-grid { grid-template-columns: 1fr; } }
@media (max-width: 560px) {
  .source-grid { gap: 12px; }
  .source-grid :deep(.ant-space) { display: flex; align-items: flex-start; flex-direction: column; }
}
</style>
