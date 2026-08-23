<template>
  <div class="page-shell">
    <PageHeader title="SOAR 人工审批" description="审批决定会持久化操作者与备注，并沿 approve / reject 分支恢复原执行。">
      <SoarSectionNav /><a-button @click="load"><ReloadOutlined /> 刷新</a-button>
    </PageHeader>
    <a-card class="surface-card"><div class="filter-bar"><span>状态</span><a-radio-group v-model:value="status" button-style="solid" @change="load"><a-radio-button value="pending">待审批</a-radio-button><a-radio-button value="">全部</a-radio-button></a-radio-group></div></a-card>
    <a-card class="surface-card">
      <LoadState :loading="loading" :error="error" :empty="!approvals.length" @retry="load">
        <a-table row-key="id" :data-source="approvals" :columns="columns" :pagination="{ pageSize: 20 }">
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'playbook'"><strong>{{ record.playbookName }}</strong><div><router-link :to="`/soar/executions/${encodeURIComponent(record.executionId)}`"><code>{{ record.executionId }}</code></router-link></div></template>
            <template v-else-if="column.key === 'object'"><a-tag>{{ record.objectType }}</a-tag><code>{{ record.objectId }}</code></template>
            <template v-else-if="column.key === 'status'"><StatusTag :value="record.status" /><div v-if="record.decidedBy" class="muted">{{ record.decidedBy }}</div></template>
            <template v-else-if="column.key === 'time'"><TimeText :value="record.decidedAt || record.createdAt" /></template>
            <template v-else-if="column.key === 'action'"><a-space v-if="record.status === 'pending'"><a-button size="small" type="primary" @click="open(record, true)">批准</a-button><a-button size="small" danger @click="open(record, false)">拒绝</a-button></a-space><span v-else>{{ record.decisionNote || '—' }}</span></template>
          </template>
        </a-table>
      </LoadState>
    </a-card>
    <a-modal v-model:open="decision.open" :title="decision.approved ? '批准自动化流程' : '拒绝自动化流程'" :confirm-loading="decision.saving" @ok="submit"><a-alert :type="decision.approved ? 'info' : 'warning'" show-icon :message="decision.item?.prompt" style="margin-bottom:14px" /><a-textarea v-model:value="decision.note" :rows="4" placeholder="审批备注（可选）" /></a-modal>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { message } from 'ant-design-vue'
import { ReloadOutlined } from '@ant-design/icons-vue'
import { decideSoarApproval, listSoarApprovals } from '../../api/index.js'
import LoadState from '../../components/common/LoadState.vue'
import PageHeader from '../../components/common/PageHeader.vue'
import StatusTag from '../../components/common/StatusTag.vue'
import TimeText from '../../components/common/TimeText.vue'
import SoarSectionNav from '../../components/soar/SoarSectionNav.vue'
const approvals = ref([]); const loading = ref(false); const error = ref(''); const status = ref('pending'); const decision = reactive({ open: false, approved: true, item: null, note: '', saving: false })
const columns = [{ key: 'playbook', title: 'Playbook / 执行' }, { key: 'object', title: '对象', width: 200 }, { dataIndex: 'prompt', title: '审批提示' }, { key: 'status', title: '状态', width: 120 }, { key: 'time', title: '时间', width: 180 }, { key: 'action', title: '决定 / 备注', width: 180 }]
async function load() { loading.value = true; error.value = ''; try { approvals.value = await listSoarApprovals(status.value) } catch (cause) { error.value = cause.message } finally { loading.value = false } }
function open(item, approved) { Object.assign(decision, { open: true, approved, item, note: '' }) }
async function submit() { decision.saving = true; try { await decideSoarApproval(decision.item.id, decision.approved, decision.note); message.success(decision.approved ? '已批准，执行将继续' : '已拒绝，执行将走拒绝分支'); decision.open = false; await load() } catch (cause) { message.error(cause.message) } finally { decision.saving = false } }
onMounted(load)
</script>
