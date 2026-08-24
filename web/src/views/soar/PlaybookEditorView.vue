<template>
  <div class="page-shell">
    <PageHeader :title="isNew ? '新建 Playbook' : form.name || '编辑 Playbook'" description="草稿可保存未闭合流程；发布时后端会验证路径、分支、字段、动作参数和无环性。">
      <a-button @click="router.push('/soar/playbooks')">返回列表</a-button>
      <template v-if="!isNew"><span class="save-state">{{ saveState }}</span><a-button :loading="saving" @click="saveNow">保存草稿</a-button><a-button type="primary" :loading="publishing" @click="publish">发布并启用</a-button></template>
    </PageHeader>
    <a-card class="surface-card" title="基本信息">
      <a-form layout="vertical">
        <div class="meta-grid">
          <a-form-item label="名称" required><a-input v-model:value="form.name" placeholder="例如：高危告警人工复核" /></a-form-item>
          <a-form-item label="入口对象" required><a-select v-model:value="form.entryType" :options="objectOptions" @change="changeEntryType" /></a-form-item>
          <a-form-item label="生命周期事件" required><a-checkbox-group v-model:value="form.eventTypes" :options="eventOptions" /></a-form-item>
          <a-form-item label="说明"><a-input v-model:value="form.description" /></a-form-item>
        </div>
      </a-form>
      <a-alert v-if="executionWarning" type="warning" show-icon :message="executionWarning" />
      <a-button v-if="isNew" type="primary" :loading="saving" @click="create">创建草稿并进入设计器</a-button>
    </a-card>
    <a-card v-if="!isNew && loaded && form.graph" class="surface-card" :body-style="{ padding: 0 }">
      <SoarMvpCanvas :model-value="form.graph" :fields="fields" :actions="actions" @update:model-value="updateGraph" />
    </a-card>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import { createSoarPlaybook, getSoarActionDictionary, getSoarFieldDictionary, getSoarPlaybook, hasSession, publishSoarPlaybook, updateSoarPlaybook } from '../../api/index.js'
import PageHeader from '../../components/common/PageHeader.vue'
import SoarMvpCanvas from '../../components/soar/SoarMvpCanvas.vue'
import { cloneGraph } from '../../components/soar/soarGraph.js'

const route = useRoute(); const router = useRouter(); const saving = ref(false); const publishing = ref(false); const loaded = ref(false); const syncing = ref(false); const dirty = ref(false); const saveState = ref(''); const fields = ref([]); const actions = ref([]); let saveTimer; let editVersion = 0; let activeSave = null
const isNew = computed(() => !route.params.id)
const form = reactive({ id: '', name: '', description: '', entryType: 'alert', eventTypes: ['alert.created'], graph: null, revision: 0, status: 'draft' })
const objectOptions = [{ value: 'alert', label: '告警' }, { value: 'case', label: '案件' }]
const eventOptions = computed(() => [{ value: `${form.entryType}.created`, label: '创建' }, { value: `${form.entryType}.updated`, label: '更新' }])
const executionWarning = computed(() => form.status === 'published' || form.status === 'disabled' ? '修改已发布/停用的 Playbook 会自动回到草稿并停用；现有执行继续使用旧快照。' : '')

watch(() => [form.name, form.description, form.entryType, [...form.eventTypes]], markDirty, { deep: true })

function assign(value) { syncing.value = true; Object.assign(form, { id: value.id, name: value.name, description: value.description, entryType: value.entryType, eventTypes: [...value.eventTypes], graph: cloneGraph(value.graph), revision: value.revision, status: value.status }); queueMicrotask(() => { syncing.value = false }) }
function graphSignature(graph) { return JSON.stringify(graph || { nodes: [], edges: [] }) }
function editablePayload() { return { name: form.name, description: form.description, entryType: form.entryType, eventTypes: [...form.eventTypes], graph: cloneGraph(form.graph) } }
function editableSignature(value = editablePayload()) { return JSON.stringify(value) }
function markDirty() {
  if (!loaded.value || syncing.value || isNew.value) return
  editVersion += 1
  dirty.value = true
  saveState.value = '有未保存更改'
  window.clearTimeout(saveTimer)
  saveTimer = window.setTimeout(saveNow, 900)
}
function updateGraph(graph) {
  const snapshot = cloneGraph(graph)
  if (graphSignature(snapshot) === graphSignature(form.graph)) return
  form.graph = snapshot
  // v-model 的父级赋值与 Vue watch 刷新并非同一个同步边界。图变更在这里直接
  // 递增版本，保存响应就没有机会在 watch 尚未执行时回填旧节点/旧连线。
  markDirty()
}
async function dictionaries() { [fields.value, actions.value] = await Promise.all([getSoarFieldDictionary(form.entryType), getSoarActionDictionary(form.entryType)]) }
async function load() { try { const value = await getSoarPlaybook(route.params.id); assign(value); await dictionaries(); loaded.value = true; saveState.value = `revision ${value.revision}` } catch (cause) { message.error(cause.message); router.push('/soar/playbooks') } }
async function create() {
  if (!form.name.trim() || !form.eventTypes.length) return message.error('请填写名称并选择生命周期事件')
  saving.value = true
  try {
    const value = await createSoarPlaybook({ name: form.name, description: form.description, entryType: form.entryType, eventTypes: form.eventTypes })
    assign(value); dirty.value = false; loaded.value = true; saveState.value = `revision ${value.revision}`
    message.success('草稿已创建')
    await router.replace(`/soar/playbooks/${encodeURIComponent(value.id)}/edit`)
  } catch (cause) { message.error(cause.message) } finally { saving.value = false }
}
function saveNow() {
  window.clearTimeout(saveTimer)
  if (!dirty.value || isNew.value) return Promise.resolve(true)
  if (activeSave) return activeSave.then((saved) => saved && dirty.value ? saveNow() : saved)
  saving.value = true
  const startedVersion = editVersion
  const editable = editablePayload()
  const payloadSignature = editableSignature(editable)
  const payload = { ...editable, revision: form.revision }
  activeSave = (async () => {
    try {
      const value = await updateSoarPlaybook(form.id, payload)
      const unchanged = editVersion === startedVersion && editableSignature() === payloadSignature
      // PUT 响应代表服务端已经接受该快照，只同步并发控制字段。整图仍以浏览器当前
      // 编辑状态为准，避免较慢的旧响应恢复刚删除的节点或连线。
      syncing.value = true
      form.revision = value.revision
      form.status = value.status
      queueMicrotask(() => { syncing.value = false })
      if (unchanged) {
        dirty.value = false; saveState.value = `已保存 · revision ${value.revision}`
      } else {
        dirty.value = true
        saveState.value = `revision ${value.revision} 已保存，继续同步新更改`
      }
      return true
    } catch (cause) {
      saveState.value = '保存失败'; message.error(cause.message); return false
    } finally {
      saving.value = false; activeSave = null
    }
  })()
  return activeSave
}
async function publish() {
  publishing.value = true
  try { const saved = await saveNow(); if (!saved || dirty.value) return; const value = await publishSoarPlaybook(form.id, form.revision); assign(value); dirty.value = false; saveState.value = `已发布 · revision ${value.revision}`; message.success('Playbook 已发布并启用') } catch (cause) { message.error(`发布失败：${cause.message}`) } finally { publishing.value = false }
}
async function changeEntryType(value) { form.eventTypes = [`${value}.created`]; await dictionaries() }
async function saveBeforeRouteLeave() {
  window.clearTimeout(saveTimer)
  if ((dirty.value || activeSave) && !hasSession()) {
    message.warning('会话已结束，未保存草稿无法提交')
    return true
  }
  const saved = await saveNow()
  if (!saved || dirty.value) {
    message.error('草稿尚未保存，已留在当前页面')
    return false
  }
  return true
}
function warnBeforeBrowserLeave(event) {
  if (!dirty.value && !activeSave) return
  event.preventDefault()
  event.returnValue = ''
}
onBeforeRouteLeave(saveBeforeRouteLeave)
onMounted(() => { if (isNew.value) { loaded.value = true; dictionaries() } else load() })
onMounted(() => window.addEventListener('beforeunload', warnBeforeBrowserLeave))
onBeforeUnmount(() => { window.clearTimeout(saveTimer); window.removeEventListener('beforeunload', warnBeforeBrowserLeave) })
</script>
<style scoped>
.meta-grid { display:grid; grid-template-columns:1.2fr .6fr 1fr 1.4fr; gap:14px; }
.save-state { color:#6c7f8e; font-size:12px; }
@media (max-width: 1180px) { .meta-grid { grid-template-columns: 1fr 1fr; } }
@media (max-width: 680px) {
  .meta-grid { grid-template-columns: 1fr; gap: 0; }
  .save-state { width: 100%; order: -1; }
}
</style>
