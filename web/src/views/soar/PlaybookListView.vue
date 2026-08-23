<template>
  <div class="page-shell">
    <PageHeader title="SOAR Playbook" description="由告警/案件生命周期消息触发；发布且启用的 Playbook 才会创建执行实例。">
      <SoarSectionNav /><a-button @click="load"><ReloadOutlined /> 刷新</a-button><a-button v-if="isAdmin" type="primary" @click="router.push('/soar/playbooks/new')"><PlusOutlined /> 新建 Playbook</a-button>
    </PageHeader>
    <a-alert type="info" show-icon message="触发边界" description="SOAR 不读取原始 siem-events，也不提供手工启动入口。每条生命周期消息对每个匹配 Playbook 最多创建一个执行实例。" />
    <a-card class="surface-card">
      <LoadState :loading="loading" :error="error" :empty="!playbooks.length" @retry="load">
        <a-table row-key="id" :data-source="playbooks" :columns="columns" :pagination="{ pageSize: 15 }">
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'name'"><strong>{{ record.name }}</strong><div><code class="mono-id">{{ record.id }}</code></div></template>
            <template v-else-if="column.key === 'status'"><StatusTag :value="record.status" /><a-tag v-if="record.enabled" color="green">已启用</a-tag></template>
            <template v-else-if="column.key === 'trigger'"><a-tag color="blue">{{ record.entryType === 'alert' ? '告警' : '案件' }}</a-tag><div class="event-list">{{ record.eventTypes.join(' / ') }}</div></template>
            <template v-else-if="column.key === 'nodes'">{{ record.graph.nodes.length }}</template>
            <template v-else-if="column.key === 'updated'"><TimeText :value="record.updatedAt" /><div class="muted">revision {{ record.revision }}</div></template>
            <template v-else-if="column.key === 'action'">
              <a-space wrap><a-button v-if="isAdmin" size="small" @click="router.push(`/soar/playbooks/${encodeURIComponent(record.id)}/edit`)">编辑</a-button>
                <a-button v-if="isAdmin && record.status === 'draft'" size="small" type="primary" @click="publish(record)">发布</a-button>
                <a-button v-if="isAdmin && record.status !== 'draft'" size="small" @click="toggle(record)">{{ record.enabled ? '停用' : '启用' }}</a-button>
                <a-popconfirm v-if="isAdmin" title="删除后历史执行仍保留，确定删除？" @confirm="remove(record)"><a-button size="small" danger>删除</a-button></a-popconfirm>
              </a-space>
            </template>
          </template>
        </a-table>
      </LoadState>
    </a-card>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { message } from 'ant-design-vue'
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons-vue'
import { useRouter } from 'vue-router'
import { deleteSoarPlaybook, listSoarPlaybooks, publishSoarPlaybook, setSoarPlaybookEnabled } from '../../api/index.js'
import { useAuth } from '../../composables/useAuth.js'
import LoadState from '../../components/common/LoadState.vue'
import PageHeader from '../../components/common/PageHeader.vue'
import StatusTag from '../../components/common/StatusTag.vue'
import TimeText from '../../components/common/TimeText.vue'
import SoarSectionNav from '../../components/soar/SoarSectionNav.vue'

const router = useRouter(); const auth = useAuth(); const playbooks = ref([]); const loading = ref(false); const error = ref('')
const isAdmin = computed(() => auth.state.user?.role === 'admin')
const columns = [{ key: 'name', title: '名称' }, { key: 'status', title: '状态', width: 160 }, { key: 'trigger', title: '生命周期入口', width: 250 }, { key: 'nodes', title: '节点', width: 70 }, { key: 'updated', title: '更新时间', width: 190 }, { key: 'action', title: '操作', width: 280 }]
async function load() { loading.value = true; error.value = ''; try { playbooks.value = await listSoarPlaybooks() } catch (cause) { error.value = cause.message } finally { loading.value = false } }
async function publish(record) { try { await publishSoarPlaybook(record.id, record.revision); message.success('已发布并启用'); await load() } catch (cause) { message.error(cause.message) } }
async function toggle(record) { try { await setSoarPlaybookEnabled(record.id, !record.enabled); message.success(record.enabled ? '已停用' : '已启用'); await load() } catch (cause) { message.error(cause.message) } }
async function remove(record) { try { await deleteSoarPlaybook(record.id); message.success('Playbook 已删除'); await load() } catch (cause) { message.error(cause.message) } }
onMounted(load)
</script>
<style scoped>.event-list { margin-top:4px; color:#617687; font-size:12px; }</style>
