<template>
  <div class="page-shell">
    <PageHeader :title="source?.name || '数据源详情'" description="生命周期、任务和错误均从服务端读取，不把处理中状态伪装成成功。">
      <a-button @click="router.push('/sources')">返回列表</a-button>
      <a-button v-if="source && ['creating', 'failed', 'stopped'].includes(source.status)" type="primary" :loading="working" @click="activate">生效</a-button>
      <a-button v-if="source?.status === 'active'" :loading="working" @click="deactivate">停用</a-button>
    </PageHeader>
    <LoadState :loading="loading" :error="error" :empty="!source" @retry="load">
      <template v-if="source">
        <a-alert v-if="source.lastError" type="error" show-icon message="最近一次生命周期操作失败" :description="source.lastError" />
        <a-card class="surface-card">
          <a-descriptions bordered :column="2">
            <a-descriptions-item label="数据源 ID"><code>{{ source.id }}</code></a-descriptions-item>
            <a-descriptions-item label="状态"><StatusTag :value="source.status" /></a-descriptions-item>
            <a-descriptions-item label="解析模板"><router-link to="/parser-templates">{{ source.templateId }}</router-link></a-descriptions-item>
            <a-descriptions-item label="协议">{{ displayLabel('protocol', source.protocol) }}</a-descriptions-item>
            <a-descriptions-item label="采集端点"><code>{{ source.protocol === 'file' ? source.path : `${source.protocol}:${source.port}` }}</code></a-descriptions-item>
            <a-descriptions-item label="后台任务"><code>{{ source.taskId || '—' }}</code></a-descriptions-item>
            <a-descriptions-item label="创建时间"><TimeText :value="source.createdAt" /></a-descriptions-item>
            <a-descriptions-item label="更新时间"><TimeText :value="source.updatedAt" /></a-descriptions-item>
          </a-descriptions>
        </a-card>
        <a-card class="surface-card" title="运行说明">
          <a-timeline>
            <a-timeline-item color="green">声明已写入配置仓库</a-timeline-item>
            <a-timeline-item :color="source.status === 'failed' ? 'red' : ['active', 'stopped'].includes(source.status) ? 'green' : 'blue'">生成并校验 Logstash pipeline</a-timeline-item>
            <a-timeline-item :color="source.status === 'active' ? 'green' : 'gray'">原子同步配置并确认运行态</a-timeline-item>
          </a-timeline>
          <a-alert v-if="source.status === 'creating'" type="info" show-icon message="正在等待后台任务完成" description="页面使用有上限的退避轮询；离开页面不会影响后台任务。" />
        </a-card>
      </template>
    </LoadState>
  </div>
</template>

<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { activateLogSource, deactivateLogSource, getLogSource } from '../../api/index.js'
import PageHeader from '../../components/common/PageHeader.vue'
import LoadState from '../../components/common/LoadState.vue'
import StatusTag from '../../components/common/StatusTag.vue'
import TimeText from '../../components/common/TimeText.vue'
import { displayLabel } from '../../utils/display.js'

const route = useRoute()
const router = useRouter()
const source = ref(null)
const loading = ref(false)
const working = ref(false)
const error = ref('')
let pollTimer
let pollStarted = 0
let pollDelay = 1500

async function load() {
  loading.value = !source.value
  error.value = ''
  try {
    source.value = await getLogSource(route.params.id)
    if (source.value.status === 'creating' && source.value.taskId) schedulePoll()
  } catch (cause) { error.value = cause.message } finally { loading.value = false }
}
function schedulePoll() {
  window.clearTimeout(pollTimer)
  if (!pollStarted) pollStarted = Date.now()
  if (Date.now() - pollStarted > 120_000) {
    message.warning('任务仍未结束，已停止自动轮询，请稍后手动刷新。')
    return
  }
  pollTimer = window.setTimeout(async () => {
    await load()
    pollDelay = Math.min(5000, Math.round(pollDelay * 1.5))
  }, pollDelay)
}
async function activate() {
  working.value = true
  try { await activateLogSource(source.value.id); pollStarted = 0; pollDelay = 1500; await load(); message.success('生效任务已提交') } catch (cause) { message.error(cause.message) } finally { working.value = false }
}
async function deactivate() {
  working.value = true
  try { await deactivateLogSource(source.value.id); pollStarted = 0; pollDelay = 1500; await load(); message.success('停用任务已提交') } catch (cause) { message.error(cause.message) } finally { working.value = false }
}
onMounted(load)
onBeforeUnmount(() => window.clearTimeout(pollTimer))
</script>
