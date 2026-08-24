<template>
  <div class="page-shell">
    <PageHeader title="解析规则库" description="解析模板是独立资产：先验证样例和 ECS 输出，再由数据源引用模板。">
      <a-button @click="router.push('/sources')">查看数据源</a-button>
      <a-button v-if="canManage" type="primary" @click="openEditor('new')">新建自定义规则</a-button>
      <a-button type="primary" @click="router.push('/sources/new')">使用模板接入</a-button>
    </PageHeader>
    <div class="template-grid">
      <a-card class="surface-card" title="模板目录">
        <LoadState :loading="loading" :error="error" :empty="!templates.length" @retry="load">
          <a-list :data-source="templates" item-layout="vertical">
            <template #renderItem="{ item }">
              <a-list-item class="template-item" :class="{ selected: selected?.id === item.id }" @click="select(item)">
                <a-list-item-meta :title="item.name" :description="item.description" />
                <a-space><a-tag color="blue">{{ item.id }}</a-tag><a-tag>{{ item.protocol || '通用' }}</a-tag><StatusTag :value="item.status" /></a-space>
              </a-list-item>
            </template>
          </a-list>
        </LoadState>
      </a-card>
      <a-card class="surface-card" :title="selected ? `${selected.name} · 解析测试` : '解析测试'">
        <a-empty v-if="!selected" description="从左侧选择模板" />
        <template v-else>
          <a-alert type="info" show-icon :message="`${selected.patterns?.length || 0} 个 Grok pattern · 时区 ${selected.timestamp?.timezone || '未指定'}`" style="margin-bottom: 14px" />
          <a-space v-if="canManage" style="margin-bottom: 14px">
            <a-button @click="openEditor('edit')">编辑当前规则</a-button>
            <a-button @click="openEditor('copy')">复制为自定义规则</a-button>
          </a-space>
          <a-textarea v-model:value="sample" :rows="7" placeholder="粘贴一条真实日志；测试不会保存数据" />
          <a-button type="primary" :loading="testing" style="margin-top: 12px" @click="test">测试解析</a-button>
          <template v-if="result">
            <a-result v-if="!result.ok" status="warning" title="样例未匹配任何 pattern" sub-title="请确认日志类型和模板是否一致。" />
            <a-descriptions v-else bordered size="small" :column="1" style="margin-top: 16px">
              <a-descriptions-item v-for="(value, key) in result.fields" :key="key" :label="key"><code>{{ value }}</code></a-descriptions-item>
            </a-descriptions>
          </template>
          <a-divider>模板逻辑</a-divider>
          <a-collapse>
            <a-collapse-panel key="patterns" header="Grok Patterns"><pre class="code-panel">{{ (selected.patterns || []).join('\n\n') }}</pre></a-collapse-panel>
            <a-collapse-panel key="ecs" header="固定 ECS 字段"><pre class="code-panel">{{ JSON.stringify(selected.ecs || {}, null, 2) }}</pre></a-collapse-panel>
          </a-collapse>
        </template>
      </a-card>
    </div>
    <ParserTemplateEditor v-model:open="editorOpen" :template="editorTemplate" :mode="editorMode" @saved="saved" />
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { listTemplates, testParse } from '../../api/index.js'
import { useAuth } from '../../composables/useAuth.js'
import PageHeader from '../../components/common/PageHeader.vue'
import LoadState from '../../components/common/LoadState.vue'
import StatusTag from '../../components/common/StatusTag.vue'
import ParserTemplateEditor from '../../components/sources/ParserTemplateEditor.vue'

const router = useRouter()
const auth = useAuth()
const templates = ref([])
const selected = ref(null)
const sample = ref('')
const result = ref(null)
const loading = ref(false)
const testing = ref(false)
const error = ref('')
const editorOpen = ref(false)
const editorMode = ref('new')
const editorTemplate = ref(null)
const canManage = computed(() => ['admin', 'ops'].includes(auth.state.user?.role))
async function load(preferredId = selected.value?.id) {
  loading.value = true; error.value = ''
  try {
    templates.value = await listTemplates()
    const next = templates.value.find((template) => template.id === preferredId) || templates.value[0]
    if (next) select(next)
  } catch (cause) { error.value = cause.message } finally { loading.value = false }
}
function select(template) {
  selected.value = template
  sample.value = template.tests?.[0]?.sample || ''
  result.value = null
}
async function test() {
  if (!sample.value.trim()) return message.warning('请先粘贴日志样例')
  testing.value = true
  try { result.value = await testParse(selected.value.id, sample.value) } catch (cause) { message.error(cause.message) } finally { testing.value = false }
}
function openEditor(mode) {
  editorMode.value = mode
  editorTemplate.value = mode === 'new' ? null : JSON.parse(JSON.stringify(selected.value))
  editorOpen.value = true
}
async function saved(template) { await load(template.id) }
onMounted(load)
</script>

<style scoped>
.template-grid { display: grid; grid-template-columns: minmax(360px, .8fr) minmax(580px, 1.2fr); gap: 16px; }
.template-item { cursor: pointer; border: 1px solid transparent; border-radius: 8px; padding: 12px !important; transition: background .16s, border-color .16s; }
.template-item:hover, .template-item.selected { background: #edf6fa; }
.template-item.selected { border-color: #b9d8e7; }
@media (max-width: 1120px) {
  .template-grid { grid-template-columns: 1fr; }
}
@media (max-width: 600px) {
  .template-grid { gap: 12px; }
  .template-item { padding: 10px !important; }
}
</style>
