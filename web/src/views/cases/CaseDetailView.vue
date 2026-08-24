<template>
  <div class="page-shell">
    <PageHeader :title="caseData?.['case.title'] || '案件详情'" description="完整调查工作区：关联告警、实体、负责人、证据和事件时间线均在独立深链页面。">
      <a-button @click="router.push('/cases')">返回案件列表</a-button>
      <a-button v-if="caseData" type="primary" @click="runSoar">运行 SOAR</a-button>
    </PageHeader>
    <LoadState :loading="loading" :error="error" :empty="!caseData" @retry="load">
      <template v-if="caseData">
        <a-card class="surface-card">
          <div class="case-toolbar">
            <a-space><StatusTag :value="caseData['case.status']" /><a-tag>{{ caseData['case.aggregation'] === 'auto' ? '自动聚合' : '手动聚合' }}</a-tag><code class="mono-id">{{ caseData['case.id'] }}</code></a-space>
            <a-space>
              <a-button v-if="caseData['case.status'] === 'open'" type="primary" :loading="saving" @click="setInvestigating">接手调查</a-button>
              <a-select v-if="caseData['case.status'] === 'investigating'" placeholder="选择结案结论" style="width: 180px" :options="verdictOptions" @change="resolve" />
            </a-space>
          </div>
          <a-descriptions bordered :column="3" style="margin-top: 16px">
            <a-descriptions-item label="负责人">{{ caseData['case.owner'] || '未分配' }}</a-descriptions-item>
            <a-descriptions-item label="协作者">{{ (caseData['case.collaborators'] || []).join(', ') || '—' }}</a-descriptions-item>
            <a-descriptions-item label="操作者">{{ caseData['case.operator'] || '—' }}</a-descriptions-item>
            <a-descriptions-item label="创建时间"><TimeText :value="caseData['case.created_at']" /></a-descriptions-item>
            <a-descriptions-item label="更新时间"><TimeText :value="caseData['case.updated_at']" /></a-descriptions-item>
            <a-descriptions-item label="结案时间"><TimeText :value="caseData['case.closed_at']" /></a-descriptions-item>
            <a-descriptions-item label="关联实体" :span="3"><a-tag v-for="item in caseData.entities || []" :key="`${item.type}:${item.value}`" color="blue">{{ item.type }}:{{ item.value }}</a-tag></a-descriptions-item>
          </a-descriptions>
        </a-card>

        <a-row :gutter="16">
          <a-col :span="14">
            <a-card class="surface-card" :title="`案内告警（${caseData.alert_ids?.length || 0}）`">
              <a-table row-key="_id" size="small" :data-source="linkedAlerts" :columns="alertColumns" :pagination="false">
                <template #bodyCell="{ column, record }">
                  <template v-if="column.key === 'rule'"><router-link :to="`/alerts/${encodeURIComponent(record._id)}`">{{ record['alert.rule_name'] || record._id }}</router-link></template>
                  <template v-else-if="column.key === 'severity'"><StatusTag group="severity" :value="record['alert.severity']" /></template>
                  <template v-else-if="column.key === 'entity'"><code>{{ entityOf(record) }}</code></template>
                  <template v-else-if="column.key === 'action'"><a-popconfirm title="将该告警移出案件？" @confirm="removeAlert(record._id)"><a-button size="small" danger>移出</a-button></a-popconfirm></template>
                </template>
              </a-table>
              <a-alert v-if="alertLoadWarnings.length" type="warning" show-icon :message="`${alertLoadWarnings.length} 条历史告警无法读取`" style="margin-top: 12px" />
            </a-card>
          </a-col>
          <a-col :span="10">
            <a-card class="surface-card" title="负责人、协作者与证据">
              <a-form layout="vertical">
                <a-form-item label="负责人"><a-input v-model:value="metadata.owner" placeholder="用户名" /></a-form-item>
                <a-form-item label="协作者"><a-select v-model:value="metadata.collaborators" mode="tags" placeholder="输入用户名后回车" /></a-form-item>
                <a-divider>新增证据</a-divider>
                <a-form-item label="证据标题"><a-input v-model:value="metadata.evidenceTitle" /></a-form-item>
                <a-form-item label="证据 URI"><a-input v-model:value="metadata.evidenceUri" placeholder="es://、case:// 或 HTTPS 链接" /></a-form-item>
                <a-space><a-button type="primary" :loading="saving" @click="saveMetadata">保存负责人/证据</a-button><a-button :loading="saving" @click="saveCollaborators">保存协作者</a-button></a-space>
              </a-form>
              <a-list v-if="caseData.evidence?.length" size="small" :data-source="caseData.evidence" style="margin-top: 14px">
                <template #renderItem="{ item }"><a-list-item><a-list-item-meta :title="item.title || '未命名证据'" :description="item.uri || item.note" /></a-list-item></template>
              </a-list>
            </a-card>
          </a-col>
        </a-row>

        <a-card class="surface-card" title="关联事件时间线（近 24 小时实时查询）">
          <a-table row-key="_id" size="small" :data-source="timeline" :columns="timelineColumns" :pagination="{ pageSize: 12 }">
            <template #bodyCell="{ column, record }">
              <template v-if="column.key === 'time'"><TimeText :value="record['@timestamp']" /></template>
              <template v-else-if="column.key === 'action'"><a-tag color="blue">{{ record['event.action'] || record['event.category'] || '事件' }}</a-tag></template>
            </template>
          </a-table>
        </a-card>
      </template>
    </LoadState>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { caseTimeline, getAlert, getCase, removeCaseAlert, updateCaseCollaborators, updateCaseMetadata, updateCaseStatus } from '../../api/index.js'
import PageHeader from '../../components/common/PageHeader.vue'
import LoadState from '../../components/common/LoadState.vue'
import StatusTag from '../../components/common/StatusTag.vue'
import TimeText from '../../components/common/TimeText.vue'
import { displayLabel, entityOf } from '../../utils/display.js'

const route = useRoute(); const router = useRouter()
const caseData = ref(null); const timeline = ref([]); const linkedAlerts = ref([]); const alertLoadWarnings = ref([])
const loading = ref(false); const saving = ref(false); const error = ref('')
const metadata = reactive({ owner: '', collaborators: [], evidenceTitle: '', evidenceUri: '' })
const verdictOptions = ['true_positive', 'false_positive', 'duplicate'].map((value) => ({ value, label: displayLabel('verdict', value) }))
const alertColumns = [{ key: 'rule', title: '规则' }, { key: 'severity', title: '级别', width: 80 }, { key: 'entity', title: '实体', width: 160 }, { key: 'action', title: '操作', width: 75 }]
const timelineColumns = [{ key: 'time', title: '事件时间', width: 190 }, { key: 'action', title: '动作', width: 170 }, { dataIndex: 'source.ip', title: '源 IP', width: 150 }, { dataIndex: 'user.name', title: '用户', width: 130 }, { dataIndex: 'message', title: '事件内容', ellipsis: true }]

async function load() {
  loading.value = true; error.value = ''; alertLoadWarnings.value = []
  try {
    const [detail, events] = await Promise.all([getCase(route.params.id), caseTimeline(route.params.id)])
    caseData.value = detail; timeline.value = events
    metadata.owner = detail['case.owner'] || ''
    metadata.collaborators = detail['case.collaborators'] || []
    const results = await Promise.allSettled((detail.alert_ids || []).map(getAlert))
    linkedAlerts.value = results.filter((item) => item.status === 'fulfilled').map((item) => item.value)
    alertLoadWarnings.value = results.filter((item) => item.status === 'rejected')
  } catch (cause) { error.value = cause.message } finally { loading.value = false }
}
async function setInvestigating() { await changeStatus('investigating') }
async function resolve(verdict) { await changeStatus('resolved', verdict) }
async function changeStatus(status, verdict) { saving.value = true; try { caseData.value = await updateCaseStatus(caseData.value['case.id'], status, verdict); message.success('案件状态已更新'); await load() } catch (cause) { message.error(cause.message) } finally { saving.value = false } }
async function saveMetadata() {
  const evidence = [...(caseData.value.evidence || [])]
  if (metadata.evidenceTitle || metadata.evidenceUri) evidence.push({ type: 'reference', title: metadata.evidenceTitle, uri: metadata.evidenceUri })
  saving.value = true
  try { await updateCaseMetadata(caseData.value['case.id'], { owner: metadata.owner, evidence }); metadata.evidenceTitle = ''; metadata.evidenceUri = ''; message.success('负责人和证据已保存'); await load() } catch (cause) { message.error(cause.message) } finally { saving.value = false }
}
async function saveCollaborators() { saving.value = true; try { await updateCaseCollaborators(caseData.value['case.id'], metadata.collaborators); message.success('协作者已保存'); await load() } catch (cause) { message.error(cause.message) } finally { saving.value = false } }
async function removeAlert(id) { try { await removeCaseAlert(caseData.value['case.id'], id); message.success('告警已移出案件'); await load() } catch (cause) { message.error(cause.message) } }
function runSoar() { router.push({ path: '/soar/executions', query: { resourceType: 'case', resourceId: caseData.value['case.id'], manual: '1' } }) }
onMounted(load)
</script>

<style scoped>
.case-toolbar { display: flex; justify-content: space-between; align-items: center; }
</style>
