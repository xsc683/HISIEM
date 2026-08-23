<template>
  <div class="page-shell">
    <PageHeader title="通知中心" description="集中处理规则部署、实体风险重算、接入失败和健康异常通知。"><a-button :disabled="!unreadCount" @click="markAll">全部标为已读</a-button><a-button @click="load">刷新</a-button></PageHeader>
    <a-card class="surface-card">
      <LoadState :loading="loading" :error="error" :empty="!notifications.length" empty-text="暂无平台通知" @retry="load">
        <a-list :data-source="ordered" item-layout="horizontal">
          <template #renderItem="{ item }"><a-list-item class="notification" :class="{ unread: !item.read }"><template #actions><a-button v-if="!item.read" size="small" @click="markRead(item)">标为已读</a-button><a-popconfirm title="删除该通知？" @confirm="remove(item)"><a-button size="small" danger>删除</a-button></a-popconfirm></template><a-list-item-meta :title="item.message"><template #avatar><a-badge :dot="!item.read"><BellOutlined class="notification-icon" /></a-badge></template><template #description><a-space><a-tag color="blue">{{ typeLabel(item.type) }}</a-tag><TimeText :value="item.timestamp" /></a-space></template></a-list-item-meta></a-list-item></template>
        </a-list>
      </LoadState>
    </a-card>
  </div>
</template>
<script setup>
import { computed, onMounted, ref } from 'vue'; import { message } from 'ant-design-vue'; import { BellOutlined } from '@ant-design/icons-vue'; import { deleteNotification, listNotifications, readAllNotifications, readNotification } from '../../api/index.js'; import PageHeader from '../../components/common/PageHeader.vue'; import LoadState from '../../components/common/LoadState.vue'; import TimeText from '../../components/common/TimeText.vue'
const notifications = ref([]); const loading = ref(false); const error = ref(''); const ordered = computed(() => notifications.value.slice().reverse()); const unreadCount = computed(() => notifications.value.filter((item) => !item.read).length); const types = { rule_deploy: '规则部署', entity_risk: '风险重算', ingest_failed: '接入失败', health: '健康异常' }; const typeLabel = (type) => types[type] || type
async function load() { loading.value = true; error.value = ''; try { notifications.value = await listNotifications() } catch (cause) { error.value = cause.message } finally { loading.value = false } } async function markRead(item) { try { await readNotification(item.id); await load() } catch (cause) { message.error(cause.message) } } async function markAll() { try { await readAllNotifications(); await load() } catch (cause) { message.error(cause.message) } } async function remove(item) { try { await deleteNotification(item.id); await load() } catch (cause) { message.error(cause.message) } } onMounted(load)
</script>
<style scoped>.notification { padding: 15px 12px; border-radius: 8px; }.notification.unread { background: #fffbe9; }.notification-icon { padding: 10px; border-radius: 50%; background: #e8f3f8; color: #1d6fa5; font-size: 18px; }</style>
