<template>
  <a-modal :open="open" :title="title" width="1040px" :footer="null" @cancel="close">
    <div class="editor-body">
      <a-alert type="info" show-icon message="保存门禁：至少一个 Grok pattern、一个能命中的正样本；负样本必须全部不命中。" />

      <a-form layout="vertical" class="section">
        <div class="base-grid">
          <a-form-item label="规则 ID" required extra="保存后不允许改名；小写字母、数字、-、_">
            <a-input v-model:value="draft.id" :disabled="mode === 'edit'" placeholder="例如 custom-vpn-auth" />
          </a-form-item>
          <a-form-item label="名称" required><a-input v-model:value="draft.name" placeholder="例如 VPN 认证日志" /></a-form-item>
          <a-form-item label="建议协议"><a-select v-model:value="draft.protocol" :options="protocolOptions" /></a-form-item>
          <a-form-item label="成熟度"><a-select v-model:value="draft.status" :options="statusOptions" /></a-form-item>
        </div>
        <a-form-item label="说明"><a-input v-model:value="draft.description" /></a-form-item>
      </a-form>

      <EditorList title="Grok Patterns" hint="按顺序尝试，第一条命中后提取命名字段" :items="draft.patterns" required @add="draft.patterns.push('')" @remove="remove(draft.patterns, $event)">
        <template #default="{ index }"><a-textarea v-model:value="draft.patterns[index]" :rows="2" placeholder="%{SYSLOGTIMESTAMP:timestamp} %{HOSTNAME:host.name} ..." /></template>
      </EditorList>

      <section class="section">
        <div class="section-title"><div><strong>固定 ECS 字段</strong><small>为每条成功解析的事件补充稳定字段</small></div><a-button size="small" @click="draft.ecs.push({ key: '', value: '' })">添加字段</a-button></div>
        <div v-for="(row, index) in draft.ecs" :key="index" class="pair-row">
          <a-input v-model:value="row.key" placeholder="event.category" /><a-input v-model:value="row.value" placeholder="authentication" />
          <a-button danger type="text" @click="remove(draft.ecs, index)">删除</a-button>
        </div>
        <a-empty v-if="!draft.ecs.length" :image="null" description="未配置固定字段" />
      </section>

      <section class="section">
        <div class="section-title"><div><strong>事件时间</strong><small>从解析字段生成 @timestamp；未配置时使用接收时间</small></div></div>
        <div class="time-grid">
          <a-form-item label="源字段"><a-input v-model:value="draft.timestamp.source" placeholder="timestamp" /></a-form-item>
          <a-form-item label="格式（每行一个）"><a-textarea v-model:value="draft.timestamp.formatsText" :rows="2" placeholder="ISO8601&#10;MMM dd HH:mm:ss" /></a-form-item>
          <a-form-item label="时区"><a-input v-model:value="draft.timestamp.timezone" placeholder="Asia/Shanghai" /></a-form-item>
        </div>
      </section>

      <EditorList title="条件动作" hint="消息匹配正则后补充字段；字段使用 JSON 对象" :items="draft.actions" @add="addAction" @remove="remove(draft.actions, $event)">
        <template #default="{ item }"><div class="action-grid"><a-input v-model:value="item.match" placeholder="/Failed password/" /><a-textarea v-model:value="item.fieldsText" :rows="3" placeholder="{ &quot;event.outcome&quot;: &quot;failure&quot; }" /></div></template>
      </EditorList>

      <EditorList title="正样本" hint="保存门禁会实际解析样本，并核对 expect JSON 中的字段" :items="draft.tests" required @add="draft.tests.push({ sample: '', expectText: '{}' })" @remove="remove(draft.tests, $event)">
        <template #default="{ item }"><div class="sample-grid"><a-textarea v-model:value="item.sample" :rows="3" placeholder="粘贴一条应当命中的完整日志" /><a-textarea v-model:value="item.expectText" :rows="3" placeholder="{ &quot;source.ip&quot;: &quot;192.0.2.1&quot; }" /></div></template>
      </EditorList>

      <EditorList title="负样本" hint="这些样本不应命中任何 pattern" :items="draft.negative" @add="draft.negative.push('')" @remove="remove(draft.negative, $event)">
        <template #default="{ index }"><a-textarea v-model:value="draft.negative[index]" :rows="2" placeholder="粘贴一条不应命中的日志" /></template>
      </EditorList>

      <section class="section test-section">
        <div class="section-title"><div><strong>保存前测试</strong><small>直接测试当前草稿，不会写入规则目录</small></div></div>
        <a-textarea v-model:value="testSample" :rows="3" placeholder="输入一条测试日志" />
        <a-space><a-button :loading="testing" @click="testDraft">测试当前草稿</a-button><a-tag v-if="testResult" :color="testResult.ok ? 'success' : 'warning'">{{ testResult.ok ? '匹配成功' : '未匹配' }}</a-tag></a-space>
        <pre v-if="testResult?.ok" class="result-panel">{{ JSON.stringify(testResult.fields, null, 2) }}</pre>
      </section>
    </div>
    <div class="modal-actions"><a-button @click="close">取消</a-button><a-button type="primary" :loading="saving" @click="save">保存解析规则</a-button></div>
  </a-modal>
</template>

<script setup>
import { computed, defineComponent, h, ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import { saveTemplate, testCustomParse } from '../../api/index.js'

const EditorList = defineComponent({
  props: { title: String, hint: String, items: Array, required: Boolean },
  emits: ['add', 'remove'],
  setup(props, { emit, slots }) {
    return () => h('section', { class: 'section' }, [
      h('div', { class: 'section-title' }, [h('div', [h('strong', [props.title, props.required ? h('em', ' *') : null]), h('small', props.hint)]), h('button', { class: 'ant-btn ant-btn-sm', type: 'button', onClick: () => emit('add') }, '添加')]),
      ...(props.items || []).map((item, index) => h('div', { class: 'list-row', key: index }, [h('div', { class: 'row-content' }, slots.default?.({ item, index })), h('button', { class: 'ant-btn ant-btn-text ant-btn-dangerous', type: 'button', onClick: () => emit('remove', index) }, '删除')])),
      !props.items?.length ? h('div', { class: 'empty-text' }, '暂无配置') : null,
    ])
  },
})

const props = defineProps({ open: Boolean, template: { type: Object, default: null }, mode: { type: String, default: 'new' } })
const emit = defineEmits(['update:open', 'saved'])
const draft = ref(emptyDraft())
const testSample = ref('')
const testResult = ref(null)
const testing = ref(false)
const saving = ref(false)
const title = computed(() => ({ edit: '编辑解析规则', copy: '复制为自定义规则' })[props.mode] || '新建自定义解析规则')
const protocolOptions = ['tcp', 'udp', 'file', 'beats'].map((value) => ({ value, label: value.toUpperCase() }))
const statusOptions = ['experimental', 'test', 'stable'].map((value) => ({ value, label: value }))

watch(() => props.open, (open) => {
  if (!open) return
  draft.value = fromTemplate(props.template, props.mode)
  testSample.value = draft.value.tests[0]?.sample || ''
  testResult.value = null
})

function emptyDraft() {
  return { id: '', name: '', description: '', protocol: 'tcp', status: 'experimental', patterns: [''], ecs: [], timestamp: { source: '', formatsText: '', timezone: 'Asia/Shanghai' }, actions: [], tests: [{ sample: '', expectText: '{}' }], negative: [] }
}

function fromTemplate(template, mode) {
  if (!template) return emptyDraft()
  const copy = JSON.parse(JSON.stringify(template))
  return {
    id: mode === 'copy' ? `${copy.id}-custom` : copy.id,
    name: mode === 'copy' ? `${copy.name}（自定义）` : copy.name,
    description: copy.description || '', protocol: copy.protocol || 'tcp', status: copy.status || 'experimental',
    patterns: copy.patterns?.length ? copy.patterns : [''],
    ecs: Object.entries(copy.ecs || {}).map(([key, value]) => ({ key, value })),
    timestamp: { source: copy.timestamp?.source || '', formatsText: (copy.timestamp?.formats || []).join('\n'), timezone: copy.timestamp?.timezone || 'Asia/Shanghai' },
    actions: (copy.actions || []).map((action) => ({ match: action.match || '', fieldsText: JSON.stringify(action.fields || {}, null, 2) })),
    tests: (copy.tests || []).map((test) => ({ sample: test.sample || '', expectText: JSON.stringify(test.expect || {}, null, 2) })),
    negative: [...(copy.negative || [])],
  }
}

function objectFromRows(rows, label) {
  const result = {}
  for (const row of rows) {
    if (!row.key.trim()) throw new Error(`${label}存在空字段名`)
    result[row.key.trim()] = row.value
  }
  return result
}

function jsonObject(text, label) {
  let value
  try { value = JSON.parse(text || '{}') } catch { throw new Error(`${label}不是合法 JSON`) }
  if (!value || Array.isArray(value) || typeof value !== 'object') throw new Error(`${label}必须是 JSON 对象`)
  return value
}

function payload(includeSamples = true) {
  const value = draft.value
  const result = {
    id: value.id.trim(), name: value.name.trim(), description: value.description.trim(), protocol: value.protocol, status: value.status,
    patterns: value.patterns.map((item) => item.trim()).filter(Boolean), ecs: objectFromRows(value.ecs, '固定 ECS 字段'),
    actions: value.actions.filter((item) => item.match.trim()).map((item, index) => ({ match: item.match.trim(), fields: jsonObject(item.fieldsText, `条件动作 ${index + 1} 的字段`) })),
  }
  if (value.timestamp.source.trim()) result.timestamp = { source: value.timestamp.source.trim(), formats: value.timestamp.formatsText.split('\n').map((item) => item.trim()).filter(Boolean), timezone: value.timestamp.timezone.trim() }
  if (includeSamples) {
    result.tests = value.tests.filter((item) => item.sample.trim()).map((item, index) => ({ sample: item.sample.trim(), expect: jsonObject(item.expectText, `正样本 ${index + 1} 的期望字段`) }))
    result.negative = value.negative.map((item) => item.trim()).filter(Boolean)
  }
  return result
}

function remove(items, index) { items.splice(index, 1) }
function addAction() { draft.value.actions.push({ match: '', fieldsText: JSON.stringify({ 'event.action': '' }, null, 2) }) }
function close() { emit('update:open', false) }

async function testDraft() {
  if (!testSample.value.trim()) return message.warning('请先输入测试日志')
  testing.value = true
  try { testResult.value = await testCustomParse(payload(false), testSample.value); if (!testResult.value.ok) message.warning('当前日志未命中任何 Grok pattern') } catch (error) { message.error(error.message) } finally { testing.value = false }
}

async function save() {
  saving.value = true
  try { const saved = await saveTemplate(payload(true)); message.success('解析规则已保存并通过样本门禁'); emit('saved', saved); close() } catch (error) { message.error(error.message) } finally { saving.value = false }
}
</script>

<style scoped>
.editor-body { max-height: calc(100vh - 210px); padding-right: 6px; overflow-y: auto; }.section { margin-top: 16px; padding: 14px; border: 1px solid #dce6ec; border-radius: 9px; background: #fafcfd; }
.base-grid { display: grid; grid-template-columns: 1.2fr 1.2fr .7fr .7fr; gap: 12px; }.time-grid { display: grid; grid-template-columns: 1fr 1.5fr 1fr; gap: 12px; }.section-title { display: flex; align-items: center; justify-content: space-between; margin-bottom: 10px; }.section-title strong, .section-title small { display: block; }.section-title strong { color: #18364a; }.section-title small { margin-top: 2px; color: #718592; font-size: 12px; }.section-title em { color: #c83d49; font-style: normal; }
:deep(.list-row) { display: grid; grid-template-columns: 1fr auto; gap: 8px; align-items: start; margin: 8px 0; }:deep(.empty-text) { padding: 10px; color: #8696a2; text-align: center; }.pair-row { display: grid; grid-template-columns: 1fr 1fr auto; gap: 8px; margin: 8px 0; }.action-grid { display: grid; grid-template-columns: .8fr 1.5fr; gap: 8px; }.sample-grid { display: grid; grid-template-columns: 1.4fr 1fr; gap: 8px; }.test-section > .ant-space { margin-top: 10px; }.result-panel { max-height: 220px; margin-top: 10px; padding: 10px; overflow: auto; border-radius: 6px; background: #102b3d; color: #d7edf5; }.modal-actions { display: flex; justify-content: flex-end; gap: 8px; padding-top: 16px; }
</style>
