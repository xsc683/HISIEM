<template>
  <a-drawer
    :open="open" :width="DRAWER_WIDTH" placement="right" title="证据详情"
    :body-style="{ paddingBottom: '24px' }" @close="emit('update:open', false)">
    <a-empty v-if="!evidence" :image="Empty.PRESENTED_IMAGE_SIMPLE" description="未选择证据" />
    <template v-else>
      <div class="drawer-title">
        <AuthorityTag :authority="authority" />
        <h3 class="drawer-summary">{{ evidence.summary || evidence.evidence_id }}</h3>
      </div>

      <a-alert
        v-if="supportingContext" type="info" show-icon class="drawer-context-note"
        message="支持性上下文"
        description="这条证据是检索/元数据来源，用于理解与解释，不是本次调查观测到的平台事实，不能单独支撑确定性结论。" />

      <!-- 知识来源身份（Stage D §10）：标题 / citation / 来源类型与版本 / 摘录 / 检索时间 / ATT&CK release。 -->
      <section v-if="facts" class="drawer-block" data-testid="knowledge-block">
        <h4 class="drawer-subtitle">知识来源</h4>
        <a-descriptions bordered size="small" :column="1">
          <a-descriptions-item v-if="facts.title" label="标题">{{ facts.title }}</a-descriptions-item>
          <a-descriptions-item label="Citation">
            <code class="wrap" data-testid="knowledge-citation">{{ citationText(facts) }}</code>
          </a-descriptions-item>
          <a-descriptions-item v-if="facts.sourceKind" label="来源类型">{{ facts.sourceKind }}</a-descriptions-item>
          <a-descriptions-item v-if="facts.sourceVersion" label="来源版本">{{ facts.sourceVersion }}</a-descriptions-item>
          <a-descriptions-item v-if="facts.retrievalMode" label="检索模式">{{ facts.retrievalMode }}</a-descriptions-item>
          <a-descriptions-item v-if="facts.retrievedAt" label="检索时间">
            <TimeText :value="facts.retrievedAt" />
          </a-descriptions-item>
          <a-descriptions-item v-if="facts.techniqueId" label="ATT&CK 技术">
            <strong>{{ facts.techniqueId }}</strong>
            <span v-if="facts.framework"> · {{ facts.framework }}</span>
          </a-descriptions-item>
          <a-descriptions-item v-if="facts.attackRelease" label="ATT&CK Release">{{ facts.attackRelease }}</a-descriptions-item>
        </a-descriptions>
        <p class="drawer-note">检索打分 / 排序位置属于检索执行元数据，不作为权威或置信度展示。</p>
      </section>

      <section class="drawer-block">
        <h4 class="drawer-subtitle">观测内容</h4>
        <pre class="code-panel">{{ observationText }}</pre>
      </section>

      <section class="drawer-block">
        <h4 class="drawer-subtitle">来源类别</h4>
        <a-descriptions bordered size="small" :column="1">
          <a-descriptions-item label="权威类别">
            <AuthorityTag :authority="authority" />
          </a-descriptions-item>
          <a-descriptions-item label="来源类型">{{ evidence.source?.type || '—' }}</a-descriptions-item>
          <a-descriptions-item label="提供方">{{ evidence.source?.provider || '—' }}</a-descriptions-item>
          <a-descriptions-item label="操作">{{ evidence.source?.operation || '—' }}</a-descriptions-item>
        </a-descriptions>
      </section>

      <section class="drawer-block">
        <h4 class="drawer-subtitle">时间</h4>
        <a-descriptions bordered size="small" :column="1">
          <a-descriptions-item label="观测时间"><TimeText :value="evidence.observed_at" /></a-descriptions-item>
          <a-descriptions-item label="采集时间"><TimeText :value="evidence.collected_at" /></a-descriptions-item>
        </a-descriptions>
      </section>

      <section v-if="evidence.entity_refs?.length" class="drawer-block">
        <h4 class="drawer-subtitle">实体</h4>
        <a-descriptions bordered size="small" :column="1">
          <a-descriptions-item v-for="(ref, index) in evidence.entity_refs" :key="index" :label="ref.kind">
            {{ ref.value }}
          </a-descriptions-item>
        </a-descriptions>
      </section>

      <section class="drawer-block">
        <h4 class="drawer-subtitle">完整性</h4>
        <a-descriptions bordered size="small" :column="1">
          <a-descriptions-item label="内容哈希"><code class="wrap">{{ evidence.content_hash || '—' }}</code></a-descriptions-item>
          <a-descriptions-item label="去重键"><code class="wrap">{{ evidence.dedup_key || '—' }}</code></a-descriptions-item>
        </a-descriptions>
      </section>

      <!-- 技术溯源排在最后：分析师判读所需的信息全部在前面，内部身份是次级信息。 -->
      <section class="drawer-block" data-testid="provenance-block">
        <h4 class="drawer-subtitle">技术溯源（次级信息）</h4>
        <a-descriptions bordered size="small" :column="1">
          <a-descriptions-item label="证据 ID"><code class="wrap">{{ evidence.evidence_id || '—' }}</code></a-descriptions-item>
          <a-descriptions-item label="取证工具调用 ID"><code class="wrap">{{ evidence.source_tool_invocation_id || '—' }}</code></a-descriptions-item>
          <a-descriptions-item label="原始引用">
            <pre class="code-panel small">{{ referenceText }}</pre>
          </a-descriptions-item>
        </a-descriptions>
      </section>
    </template>
  </a-drawer>
</template>

<script setup>
import { computed } from 'vue'
import { Empty } from 'ant-design-vue'
import TimeText from '../common/TimeText.vue'
import AuthorityTag from './AuthorityTag.vue'
import {
  citationText,
  evidenceAuthority,
  isSupportingContext,
  knowledgeFacts,
} from '../../utils/copilot.js'

// 抽屉宽度跟随视口：窄屏占满整屏，避免抽屉比屏幕还宽。
const DRAWER_WIDTH = 'min(560px, 100vw)'

const props = defineProps({
  open: { type: Boolean, default: false },
  evidence: { type: Object, default: null },
})
const emit = defineEmits(['update:open'])


const authority = computed(() => evidenceAuthority(props.evidence?.source?.type))
const supportingContext = computed(() => isSupportingContext(authority.value))
// 只有知识类来源才渲染知识来源区块；其它来源没有 citation 身份可言。
const facts = computed(() => {
  const type = props.evidence?.source?.type
  if (type !== 'KNOWLEDGE') return null
  return knowledgeFacts(props.evidence)
})

const observationText = computed(() => {
  const value = props.evidence?.observation
  if (value == null || value === '') return '—'
  if (typeof value === 'string') return value
  try { return JSON.stringify(value, null, 2) } catch { return String(value) }
})

const referenceText = computed(() => {
  const value = props.evidence?.raw_reference
  if (value == null || value === '') return '—'
  if (typeof value === 'string') return value
  try { return JSON.stringify(value, null, 2) } catch { return String(value) }
})
</script>

<style scoped>
.drawer-title { display: flex; align-items: baseline; flex-wrap: wrap; gap: 8px; margin-bottom: 12px; }
.drawer-summary { margin: 0; color: #1d3244; font-size: 16px; line-height: 1.6; }
.drawer-context-note { margin-bottom: 4px; }
.drawer-block { margin-top: 20px; }
.drawer-subtitle { margin: 0 0 8px; color: #2f5268; font-size: 13px; font-weight: 600; }
.drawer-note { margin: 8px 0 0; color: #80919d; font-size: 11px; line-height: 1.5; }
.wrap { overflow-wrap: anywhere; }
.code-panel { max-height: 360px; }
.code-panel.small { max-height: 220px; font-size: 11px; }
</style>
