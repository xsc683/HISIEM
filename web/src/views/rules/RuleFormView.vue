<template>
  <div class="page-shell">
    <PageHeader :title="isEdit ? '编辑检测规则' : '新建检测规则'" description="保存会原子写入 infra/rules YAML；部署前不会改变正在运行的 Flink Job。">
      <a-button @click="cancel">取消</a-button>
      <a-button type="primary" :loading="saving" @click="submit">保存为待部署版本</a-button>
    </PageHeader>
    <a-alert v-if="loadError" type="error" show-icon :message="loadError" />
    <a-form ref="formRef" :model="form" layout="vertical" :disabled="loading" @finish="submit">
      <div class="form-grid">
        <a-card class="surface-card" title="规则元数据">
          <div class="two-columns">
            <a-form-item label="规则 ID" name="id" :rules="rules.id"><a-input v-model:value="form.id" :disabled="isEdit" placeholder="rule-custom-login-001" /></a-form-item>
            <a-form-item label="规则名称" name="name" :rules="rules.required"><a-input v-model:value="form.name" placeholder="异常登录行为" /></a-form-item>
            <a-form-item label="检测类别" name="category" :rules="rules.required"><a-select v-model:value="form.category" :options="categoryOptions" /></a-form-item>
            <a-form-item label="告警类型" name="type" :rules="rules.type"><a-input v-model:value="form.type" placeholder="custom_auth_detection" /></a-form-item>
            <a-form-item label="严重级别" name="severity" :rules="rules.required"><a-select v-model:value="form.severity" :options="severityOptions" /></a-form-item>
            <a-form-item label="风险分" name="riskScore" :rules="rules.required"><a-input-number v-model:value="form.riskScore" :min="0" :max="100" style="width: 100%" /></a-form-item>
            <a-form-item label="状态"><a-select v-model:value="form.status" :options="statusOptions" /></a-form-item>
            <a-form-item label="版本"><a-input v-model:value="form.version" /></a-form-item>
          </div>
          <a-form-item label="规则说明"><a-textarea v-model:value="form.description" :rows="3" placeholder="说明检测目标、使用边界和预期调查方向" /></a-form-item>
          <a-form-item label="MITRE/业务标签" extra="多个标签用逗号分隔"><a-input v-model:value="form.tagsText" placeholder="attack.t1110.001, authentication" /></a-form-item>
          <a-form-item><a-checkbox v-model:checked="form.enabled">创建后启用（仍需部署才会生效）</a-checkbox></a-form-item>
        </a-card>

        <a-card v-if="form.category === 'window'" class="surface-card" title="窗口聚合参数">
          <div class="two-columns">
            <a-form-item label="分组字段" name="keyField" :rules="rules.required"><a-auto-complete v-model:value="form.keyField" :options="fieldOptions" placeholder="source.ip" /></a-form-item>
            <a-form-item label="窗口（分钟）" name="windowMinutes" :rules="rules.required"><a-input-number v-model:value="form.windowMinutes" :min="1" :max="1440" style="width: 100%" /></a-form-item>
            <a-form-item label="触发阈值" name="threshold" :rules="rules.required"><a-input-number v-model:value="form.threshold" :min="2" :max="1000000" style="width: 100%" /></a-form-item>
            <a-form-item label="滑动步长（分钟）"><a-input-number v-model:value="form.slidingMinutes" :min="1" :max="form.windowMinutes" style="width: 100%" /></a-form-item>
            <a-form-item label="告警抑制（分钟）"><a-input-number v-model:value="form.alertSuppressionMinutes" :min="1" :max="10080" style="width: 100%" /></a-form-item>
          </div>
          <a-alert type="info" show-icon message="窗口规则按事件时间和分组字段计数" :description="`满足下方条件的事件，在 ${form.windowMinutes} 分钟内按 ${form.keyField || '分组字段'} 累计达到 ${form.threshold} 次时生成告警。`" />
        </a-card>
      </div>

      <a-card class="surface-card" title="检测条件">
        <template #extra><a-button type="dashed" @click="addCondition"><PlusOutlined /> 添加条件</a-button></template>
        <a-radio-group v-if="form.conditions.length > 1" v-model:value="form.matchMode" button-style="solid" style="margin-bottom: 16px">
          <a-radio-button value="all">全部满足（AND）</a-radio-button>
          <a-radio-button value="any">任一满足（OR）</a-radio-button>
        </a-radio-group>
        <div class="condition-list">
          <div v-for="(condition, index) in form.conditions" :key="condition.key" class="condition-row">
            <span class="condition-index">{{ index + 1 }}</span>
            <a-select v-model:value="condition.type" style="width: 140px" :options="conditionTypes" />
            <a-auto-complete v-model:value="condition.field" :options="fieldOptions" style="width: 240px" placeholder="ECS 点分字段" />
            <a-input v-model:value="condition.value" style="flex: 1" :placeholder="condition.type === 'field_in' ? '多个值用逗号分隔' : '比较值'" />
            <a-button danger type="text" :disabled="form.conditions.length === 1" @click="removeCondition(index)"><DeleteOutlined /></a-button>
          </div>
        </div>
        <a-alert v-if="conditionError" type="error" show-icon :message="conditionError" style="margin-top: 12px" />
      </a-card>

      <a-card class="surface-card" title="保存影响">
        <a-alert type="warning" show-icon message="保存与部署是两个阶段" description="保存只更新经过校验的 YAML 并记录操作者；必须回到规则列表执行“部署生效”，部署失败时后端恢复旧规则目录和旧 Flink Job。" />
      </a-card>
    </a-form>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { DeleteOutlined, PlusOutlined } from '@ant-design/icons-vue'
import { createDetectionRule, getDetectionRule, updateDetectionRule } from '../../api/index.js'
import PageHeader from '../../components/common/PageHeader.vue'
import { displayLabel } from '../../utils/display.js'

const route = useRoute()
const router = useRouter()
const formRef = ref()
const saving = ref(false)
const loading = ref(false)
const loadError = ref('')
const conditionError = ref('')
let conditionKey = 1
const isEdit = computed(() => Boolean(route.params.id))
const form = reactive({
  id: '', name: '', category: 'single_event', type: '', enabled: true, severity: 'medium', description: '', riskScore: 50,
  tagsText: '', status: 'experimental', version: '1.0', keyField: 'source.ip', windowMinutes: 5, threshold: 5,
  slidingMinutes: 1, alertSuppressionMinutes: 5, matchMode: 'all',
  conditions: [{ key: conditionKey++, type: 'field_equals', field: 'event.action', value: 'authentication_failure' }],
})

const commonFields = ['event.action', 'event.category', 'event.outcome', 'event.type', 'source.ip', 'destination.ip', 'source.port', 'destination.port', 'user.name', 'host.name', 'process.name', 'file.hash.sha256', 'network.protocol']
const fieldOptions = commonFields.map((value) => ({ value }))
const categoryOptions = ['single_event', 'window'].map((value) => ({ value, label: displayLabel('category', value) }))
const severityOptions = ['low', 'medium', 'high', 'critical'].map((value) => ({ value, label: displayLabel('severity', value) }))
const statusOptions = [{ value: 'experimental', label: '实验性' }, { value: 'stable', label: '稳定' }, { value: 'deprecated', label: '已弃用' }]
const conditionTypes = [{ value: 'field_equals', label: '字段等于' }, { value: 'field_in', label: '字段属于集合' }]
const rules = {
  required: [{ required: true, message: '该字段不能为空' }],
  id: [{ required: true, pattern: /^[a-z0-9][a-z0-9-]{2,95}$/, message: '使用 3-96 位小写字母、数字和连字符' }],
  type: [{ required: true, pattern: /^[a-z0-9][a-z0-9_]{1,95}$/, message: '使用小写字母、数字和下划线' }],
}

function addCondition() {
  form.conditions.push({ key: conditionKey++, type: 'field_equals', field: '', value: '' })
}
function removeCondition(index) { form.conditions.splice(index, 1) }

function leafFrom(condition) {
  if (!condition.field.trim() || !condition.value.trim()) throw new Error('每条条件都必须填写字段和值')
  if (condition.type === 'field_in') {
    const values = condition.value.split(',').map((value) => value.trim()).filter(Boolean)
    if (!values.length) throw new Error('集合条件至少需要一个值')
    return { type: 'field_in', field: condition.field.trim(), values }
  }
  return { type: 'field_equals', field: condition.field.trim(), value: condition.value.trim() }
}

function buildPayload() {
  const leaves = form.conditions.map(leafFrom)
  const condition = leaves.length === 1 ? leaves[0] : { type: form.matchMode, conditions: leaves }
  const payload = {
    id: form.id.trim(), name: form.name.trim(), category: form.category, type: form.type.trim(), enabled: form.enabled,
    severity: form.severity, description: form.description.trim(), riskScore: form.riskScore,
    tags: form.tagsText.split(',').map((value) => value.trim()).filter(Boolean), status: form.status, version: form.version.trim(),
    references: [], condition,
  }
  if (form.category === 'window') Object.assign(payload, {
    keyField: form.keyField.trim(), windowMinutes: form.windowMinutes, threshold: form.threshold,
    slidingMinutes: form.slidingMinutes || null, alertSuppressionMinutes: form.alertSuppressionMinutes || null,
  })
  return payload
}

function flattenCondition(condition) {
  if (!condition) return []
  if (['all', 'any'].includes(condition.type)) {
    form.matchMode = condition.type
    return (condition.conditions || []).flatMap(flattenCondition)
  }
  if (!['field_equals', 'field_in'].includes(condition.type)) throw new Error('该规则包含当前表单不支持的 NOT/复杂嵌套条件')
  return [{ key: conditionKey++, type: condition.type, field: condition.field || '', value: condition.type === 'field_in' ? (condition.values || []).join(', ') : String(condition.value ?? '') }]
}

async function load() {
  if (!isEdit.value) return
  loading.value = true
  try {
    const rule = await getDetectionRule(route.params.id)
    if (!['single_event', 'window'].includes(rule.category)) throw new Error('CEP/基线规则只能通过 YAML 代码评审修改')
    Object.assign(form, {
      ...rule,
      tagsText: (rule.tags || []).join(', '),
      keyField: rule.keyField || 'source.ip', windowMinutes: rule.windowMinutes || 5, threshold: rule.threshold || 5,
      slidingMinutes: rule.slidingMinutes || 1, alertSuppressionMinutes: rule.alertSuppressionMinutes || rule.windowMinutes || 5,
      conditions: flattenCondition(rule.condition),
    })
  } catch (cause) {
    loadError.value = cause.message
  } finally {
    loading.value = false
  }
}

async function submit() {
  conditionError.value = ''
  try {
    await formRef.value.validate()
    const payload = buildPayload()
    saving.value = true
    const saved = isEdit.value ? await updateDetectionRule(route.params.id, payload) : await createDetectionRule(payload)
    message.success('规则已保存为待部署配置')
    await router.push(`/rules/${encodeURIComponent(saved.id)}`)
  } catch (cause) {
    if (!cause?.errorFields) conditionError.value = cause?.message || '规则保存失败'
  } finally {
    saving.value = false
  }
}

function cancel() { router.push(isEdit.value ? `/rules/${encodeURIComponent(route.params.id)}` : '/rules') }
onMounted(load)
</script>

<style scoped>
.form-grid { display: grid; grid-template-columns: minmax(0, 1.2fr) minmax(360px, .8fr); gap: 16px; }
.two-columns { display: grid; grid-template-columns: 1fr 1fr; gap: 0 16px; }
.condition-list { display: grid; gap: 10px; }
.condition-row { display: flex; align-items: center; gap: 10px; padding: 12px; border: 1px solid #dfe7ed; border-radius: 8px; background: #f8fafb; }
.condition-index { display: grid; place-items: center; width: 25px; height: 25px; border-radius: 50%; background: #dcecf5; color: #1d6fa5; font-size: 12px; font-weight: 700; }
@media (max-width: 1120px) { .form-grid { grid-template-columns: 1fr; } }
@media (max-width: 680px) {
  .two-columns { grid-template-columns: 1fr; gap: 0; }
  .condition-row { display: grid; grid-template-columns: 26px minmax(0, 1fr) auto; gap: 8px; }
  .condition-index { grid-column: 1; grid-row: 1; }
  .condition-row > :deep(.ant-select), .condition-row > :deep(.ant-select-auto-complete),
  .condition-row > :deep(.ant-input) { grid-column: 2; width: 100% !important; }
  .condition-row > :deep(.ant-btn) { grid-column: 3; grid-row: 1; }
}
</style>
