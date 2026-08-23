<template>
  <div class="page-shell">
    <PageHeader :title="rule?.name || '规则详情'" description="结构化逻辑与原始规则声明来自同一份 YAML。">
      <a-button @click="router.push('/rules')">返回列表</a-button>
      <a-button v-if="editable" @click="router.push(`/rules/${encodeURIComponent(route.params.id)}/edit`)">编辑规则</a-button>
      <a-button v-if="rule" :loading="toggling" @click="toggle">{{ rule.enabled ? '停用' : '启用' }}</a-button>
    </PageHeader>
    <LoadState :loading="loading" :error="error" :empty="!rule" empty-text="规则不存在" @retry="load">
      <template v-if="rule">
        <a-alert v-if="!editable" type="info" show-icon message="CEP/基线规则当前只读" description="这些规则包含序列或统计模型，仍通过规则即代码评审维护，避免简化表单丢失执行语义。" />
        <a-card class="surface-card">
          <div class="metric-strip">
            <div class="metric"><span class="metric-label">类别</span><span class="metric-value">{{ displayLabel('category', rule.category) }}</span></div>
            <div class="metric"><span class="metric-label">严重级别</span><span class="metric-value"><StatusTag group="severity" :value="rule.severity" /></span></div>
            <div class="metric"><span class="metric-label">风险分</span><span class="metric-value">{{ rule.riskScore }}</span></div>
            <div class="metric"><span class="metric-label">近 7 天命中</span><span class="metric-value">{{ hitCount }}</span></div>
            <div class="metric"><span class="metric-label">运行状态</span><span class="metric-value"><a-badge :status="rule.enabled ? 'success' : 'default'" :text="rule.enabled ? '已启用' : '已停用'" /></span></div>
          </div>
          <a-descriptions bordered size="small" :column="2" style="margin-top: 20px">
            <a-descriptions-item label="规则 ID"><code>{{ rule.id }}</code></a-descriptions-item>
            <a-descriptions-item label="告警类型"><code>{{ rule.type }}</code></a-descriptions-item>
            <a-descriptions-item label="版本">{{ rule.version }}</a-descriptions-item>
            <a-descriptions-item label="成熟度">{{ rule.status }}</a-descriptions-item>
            <a-descriptions-item label="说明" :span="2">{{ rule.description || '—' }}</a-descriptions-item>
            <a-descriptions-item label="MITRE 标签" :span="2"><a-tag v-for="tag in rule.tags || []" :key="tag" color="geekblue">{{ tag }}</a-tag></a-descriptions-item>
          </a-descriptions>
        </a-card>

        <a-card class="surface-card" title="检测逻辑">
          <a-descriptions v-if="rule.category === 'window'" bordered size="small" :column="4" style="margin-bottom: 16px">
            <a-descriptions-item label="分组字段"><code>{{ rule.keyField }}</code></a-descriptions-item>
            <a-descriptions-item label="窗口">{{ rule.windowMinutes }} 分钟</a-descriptions-item>
            <a-descriptions-item label="阈值">≥ {{ rule.threshold }} 次</a-descriptions-item>
            <a-descriptions-item label="抑制">{{ rule.alertSuppressionMinutes || rule.windowMinutes }} 分钟</a-descriptions-item>
          </a-descriptions>
          <RuleConditionTree v-if="rule.condition" :condition="rule.condition" />
          <a-alert v-else type="info" message="该类别的执行逻辑位于下方 DSL 声明中" />
        </a-card>

        <a-card class="surface-card">
          <a-tabs>
            <a-tab-pane key="yaml" tab="只读 YAML"><pre class="code-panel">{{ yamlText }}</pre></a-tab-pane>
            <a-tab-pane key="json" tab="只读 DSL/JSON"><pre class="code-panel">{{ JSON.stringify(rule, null, 2) }}</pre></a-tab-pane>
          </a-tabs>
        </a-card>
      </template>
    </LoadState>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import YAML from 'yaml'
import { getDetectionRule, getRuleHits, toggleRule } from '../../api/index.js'
import PageHeader from '../../components/common/PageHeader.vue'
import LoadState from '../../components/common/LoadState.vue'
import StatusTag from '../../components/common/StatusTag.vue'
import RuleConditionTree from '../../components/rules/RuleConditionTree.vue'
import { displayLabel } from '../../utils/display.js'

const route = useRoute()
const router = useRouter()
const rule = ref(null)
const hitCount = ref('—')
const loading = ref(false)
const toggling = ref(false)
const error = ref('')
const editable = computed(() => ['single_event', 'window'].includes(rule.value?.category))
const yamlText = computed(() => rule.value ? YAML.stringify(rule.value) : '')

async function load() {
  loading.value = true
  error.value = ''
  try {
    rule.value = await getDetectionRule(route.params.id)
    const result = await getRuleHits(route.params.id).catch(() => null)
    hitCount.value = result?.count >= 0 ? result.count : '不可用'
  } catch (cause) {
    error.value = cause.message
  } finally {
    loading.value = false
  }
}

async function toggle() {
  toggling.value = true
  try {
    rule.value = await toggleRule(rule.value.id)
    message.warning('状态已写入 YAML，需要执行“部署生效”后检测 Job 才会采用。')
  } catch (cause) {
    message.error(cause.message)
  } finally {
    toggling.value = false
  }
}
onMounted(load)
</script>
