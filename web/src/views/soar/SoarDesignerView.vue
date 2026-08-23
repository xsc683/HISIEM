<template>
  <div class="page-shell">
    <PageHeader title="Playbook 设计与发布" description="图结构、版本、四眼审批和灰度流量是同一条治理链；历史执行继续使用启动时快照。">
      <a-button @click="router.push('/soar')">返回 SOAR 运行台</a-button>
      <a-button :disabled="!isAdmin" @click="openNew"><PlusOutlined /> 新建</a-button>
      <a-button :disabled="!isAdmin || !definition" @click="copyDraft">复制为新草稿</a-button>
      <a-button type="primary" :disabled="!editable || !definition" :loading="saving" @click="save">保存草稿</a-button>
    </PageHeader>

    <a-alert v-if="!isAdmin" type="info" show-icon message="当前角色为只读模式" description="只有管理员可以保存、提交、审批和发布 Playbook。" />
    <a-card class="surface-card">
      <div class="revision-toolbar">
        <a-select :value="currentKey || undefined" show-search placeholder="选择 Playbook revision" style="width: 520px" :options="revisionOptions" @change="selectRevision" />
        <template v-if="current"><StatusTag :value="current.state" /><a-tag>revision {{ current.revision }}</a-tag><a-tag>lock {{ current.lockVersion }}</a-tag><span class="muted">创建：{{ current.createdBy }} · 审批：{{ current.reviewedBy || '—' }}</span></template>
        <span v-else-if="definition" class="muted">尚未保存的新草稿</span>
      </div>
    </a-card>

    <template v-if="definition">
      <a-card class="surface-card" title="Playbook 元数据">
        <div class="metadata-grid">
          <a-form-item label="ID" style="margin:0"><a-input :value="definition.id" disabled /></a-form-item>
          <a-form-item label="名称" style="margin:0"><a-input v-model:value="definition.name" :disabled="!editable" /></a-form-item>
          <a-form-item label="语义版本" style="margin:0"><a-input v-model:value="definition.version" :disabled="!editable" /></a-form-item>
          <a-form-item label="资源类型" style="margin:0"><a-select v-model:value="definition.resourceTypes" mode="multiple" :disabled="!editable" :options="[{ value: 'alert', label: '告警' }, { value: 'case', label: '案件' }]" /></a-form-item>
          <a-form-item label="说明" style="margin:0; grid-column: 1 / -1"><a-input v-model:value="definition.description" :disabled="!editable" /></a-form-item>
        </div>
      </a-card>

      <a-card class="surface-card" :body-style="{ padding: 0 }">
        <PlaybookFlowEditor :key="editorKey" ref="flowEditor" v-model:definition="definition" v-model:layout="layout" :editable="editable" />
      </a-card>

      <a-card class="surface-card" title="版本治理">
        <div class="governance-actions">
          <a-button v-if="current?.state === 'draft'" type="primary" :disabled="!isAdmin" @click="submitReview">提交审批</a-button>
          <template v-if="current?.state === 'pending_approval'">
            <a-tooltip :title="isCreator ? '创建者不能审批自己的 revision' : ''"><a-button type="primary" :disabled="!isAdmin || isCreator" @click="review(true)">审批通过</a-button></a-tooltip>
            <a-button danger :disabled="!isAdmin || isCreator" @click="review(false)">驳回修改</a-button>
          </template>
          <template v-if="current?.state === 'approved'">
            <a-input-number v-model:value="rollout" :min="1" :max="100" addon-after="%" />
            <a-button type="primary" :disabled="!isAdmin" @click="publish">灰度发布</a-button>
            <span class="muted">首次发布必须为 100%；已有稳定版时稳定版与候选版比例之和必须为 100%。</span>
          </template>
          <span v-if="current?.reviewNote" class="muted">审批意见：{{ current.reviewNote }}</span>
        </div>
        <a-table row-key="revision" size="small" :data-source="relatedRevisions" :columns="revisionColumns" :pagination="false" style="margin-top: 16px">
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'state'"><StatusTag :value="record.state" /></template>
            <template v-else-if="column.key === 'rollout'">{{ record.rolloutPercentage }}%</template>
            <template v-else-if="column.key === 'actors'">{{ record.createdBy }} / {{ record.reviewedBy || '—' }} / {{ record.publishedBy || '—' }}</template>
            <template v-else-if="column.key === 'updated'"><TimeText :value="record.updatedAt" /></template>
          </template>
        </a-table>
      </a-card>
    </template>
    <a-empty v-else description="选择已有 revision，或新建一个 Start–Action–End Playbook" />

    <a-modal v-model:open="newOpen" title="新建图式 Playbook" ok-text="创建本地草稿" @ok="createEmpty">
      <a-form layout="vertical"><a-form-item label="Playbook ID" required><a-input v-model:value="newMeta.id" placeholder="alert-enrichment-response" /></a-form-item><a-form-item label="名称" required><a-input v-model:value="newMeta.name" /></a-form-item><a-form-item label="版本"><a-input v-model:value="newMeta.version" /></a-form-item></a-form>
    </a-modal>
  </div>
</template>

<script setup>
import { computed, h, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { message, Modal } from 'ant-design-vue'
import { PlusOutlined } from '@ant-design/icons-vue'
import { createSoarDraft, listSoarRevisions, publishSoarRevision, reviewSoarRevision, submitSoarRevision, updateSoarDraft } from '../../api/index.js'
import { useAuth } from '../../composables/useAuth.js'
import PageHeader from '../../components/common/PageHeader.vue'
import StatusTag from '../../components/common/StatusTag.vue'
import TimeText from '../../components/common/TimeText.vue'
import PlaybookFlowEditor from '../../components/soar/PlaybookFlowEditor.vue'

const router = useRouter(); const auth = useAuth(); const revisions = ref([]); const current = ref(null); const definition = ref(null); const layout = ref({}); const editorKey = ref(0); const flowEditor = ref(); const saving = ref(false); const rollout = ref(10); const newOpen = ref(false); const newMeta = reactive({ id: '', name: '', version: '1.0.0' })
const isAdmin = computed(() => auth.state.user?.role === 'admin')
const editable = computed(() => isAdmin.value && (!current.value || ['draft', 'rejected'].includes(current.value.state)))
const isCreator = computed(() => current.value?.createdBy === auth.state.user?.username)
const currentKey = computed(() => current.value ? keyOf(current.value) : '')
const revisionOptions = computed(() => revisions.value.map((item) => ({ value: keyOf(item), label: `${item.playbookId} · r${item.revision} · ${item.state} · ${item.rolloutPercentage}%` })))
const relatedRevisions = computed(() => revisions.value.filter((item) => item.playbookId === definition.value?.id))
const revisionColumns = [{ dataIndex: 'revision', title: 'Revision', width: 90 }, { dataIndex: 'semanticVersion', title: '版本', width: 100 }, { key: 'state', title: '状态', width: 120 }, { key: 'rollout', title: '流量', width: 80 }, { key: 'actors', title: '创建 / 审批 / 发布' }, { key: 'updated', title: '更新时间', width: 190 }]

function clone(value) { return JSON.parse(JSON.stringify(value)) }
function keyOf(item) { return `${item.playbookId}:${item.revision}` }
async function refresh(selectKey) {
  try {
    revisions.value = await listSoarRevisions()
    if (selectKey) { const found = revisions.value.find((item) => keyOf(item) === selectKey); if (found) loadRevision(found) }
  } catch (cause) { message.error(`版本目录加载失败：${cause.message}`) }
}
function loadRevision(revision) { current.value = revision; definition.value = clone(revision.definition); layout.value = clone(revision.layout || {}); rollout.value = revision.rolloutPercentage || 10; editorKey.value++ }
function selectRevision(key) { const revision = revisions.value.find((item) => keyOf(item) === key); if (revision) loadRevision(revision) }
function openNew() { Object.assign(newMeta, { id: '', name: '', version: '1.0.0' }); newOpen.value = true }
function createEmpty() {
  if (!/^[a-z0-9][a-z0-9-]{2,95}$/.test(newMeta.id) || !newMeta.name.trim()) return message.warning('请填写合法的小写连字符 ID 和名称')
  const action = { id: 'action-1', name: '记录自动化上下文', type: 'action', action: 'context.set', with: { values: { note: 'new playbook' } }, exclusive: false, join: 'any', transitions: [{ target: 'end-1', on: 'success' }] }
  const end = { id: 'end-1', name: '流程成功结束', type: 'end', result: 'succeeded', exclusive: false, join: 'any', transitions: [] }
  current.value = null
  definition.value = { formatVersion: '2', id: newMeta.id, name: newMeta.name.trim(), description: '', version: newMeta.version || '1.0.0', enabled: true, resourceTypes: ['alert'], entrypoint: action.id, defaults: { timeoutSeconds: 30, retry: { maxAttempts: 2, delaySeconds: 2, backoffMultiplier: 2 } }, triggers: [], nodes: [action, end], steps: null }
  layout.value = { __start__: { x: 30, y: 240 }, 'action-1': { x: 270, y: 210 }, 'end-1': { x: 560, y: 210 } }
  editorKey.value++; newOpen.value = false
}
function copyDraft() { if (!definition.value) return; current.value = null; definition.value = { ...clone(definition.value), version: bumpVersion(definition.value.version) }; layout.value = clone(layout.value); editorKey.value++; message.info('已复制为本地新草稿，保存后生成新 revision') }
async function save() {
  const problems = flowEditor.value?.issues?.value || flowEditor.value?.issues || []
  if (problems.length) return message.error(`请先修复 ${problems.length} 个图校验问题`)
  saving.value = true
  try {
    const saved = current.value ? await updateSoarDraft(current.value.playbookId, current.value.revision, definition.value, layout.value, current.value.lockVersion) : await createSoarDraft(definition.value, layout.value)
    message.success(`草稿已保存：revision ${saved.revision}`); await refresh(keyOf(saved))
  } catch (cause) { message.error(`保存失败：${cause.message}`) } finally { saving.value = false }
}
async function submitReview() { await transition(() => submitSoarRevision(current.value.playbookId, current.value.revision), '已提交审批') }
async function review(approved) {
  const note = await askReviewNote(approved)
  if (note == null) return
  await transition(() => reviewSoarRevision(current.value.playbookId, current.value.revision, approved, note), approved ? '审批通过' : '已驳回')
}
function askReviewNote(approved) {
  return new Promise((resolve) => {
    let note = approved ? '图结构与动作参数检查通过' : ''
    Modal.confirm({ title: approved ? '确认批准该 revision？' : '驳回该 revision', content: () => h('textarea', { class: 'ant-input', rows: 4, placeholder: '填写审批意见', onInput: (event) => { note = event.target.value } }), onOk: () => resolve(note), onCancel: () => resolve(null) })
  })
}
async function publish() { await transition(() => publishSoarRevision(current.value.playbookId, current.value.revision, rollout.value), `已按 ${rollout.value}% 发布`) }
async function transition(action, success) { try { const next = await action(); message.success(success); await refresh(keyOf(next)) } catch (cause) { message.error(cause.message) } }
function bumpVersion(version) { const parts = String(version || '1.0.0').split('.'); return `${parts[0] || 1}.${parts[1] || 0}.${Number(parts[2] || 0) + 1}` }
onMounted(refresh)
</script>

<style scoped>
.revision-toolbar { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }.metadata-grid { display: grid; grid-template-columns: 1fr 1.3fr .65fr 1fr; gap: 14px; }.governance-actions { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
</style>
