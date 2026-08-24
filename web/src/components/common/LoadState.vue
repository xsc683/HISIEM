<template>
  <div v-if="error" class="state-panel error-state" role="alert">
    <a-alert type="error" show-icon :message="title" :description="error">
      <template #action><a-button size="small" @click="$emit('retry')">重新加载</a-button></template>
    </a-alert>
  </div>
  <div v-else-if="loading" class="state-panel loading-state" role="status" aria-live="polite">
    <div class="loading-copy"><a-spin size="small" /><strong>{{ loadingText }}</strong><span>正在获取最新数据，请稍候</span></div>
    <a-skeleton active :title="false" :paragraph="{ rows: 3, width: ['96%', '82%', '68%'] }" />
  </div>
  <div v-else-if="empty" class="state-panel empty-state">
    <a-empty :description="emptyText"><template #description><strong>{{ emptyText }}</strong><span>{{ emptyHint }}</span></template></a-empty>
  </div>
  <slot v-else />
</template>

<script setup>
defineProps({
  loading: Boolean,
  error: { type: String, default: '' },
  empty: Boolean,
  title: { type: String, default: '数据加载失败' },
  loadingText: { type: String, default: '正在加载…' },
  emptyText: { type: String, default: '暂无数据' },
  emptyHint: { type: String, default: '当前没有可展示的内容，可稍后刷新重试' },
})
defineEmits(['retry'])
</script>

<style scoped>
.state-panel { min-height: 148px; border: 1px dashed #dce5ea; border-radius: 9px; background: #fafcfd; }
.error-state { min-height: auto; border: 0; background: transparent; }
.loading-state { display: grid; grid-template-columns: minmax(180px, .42fr) 1fr; align-items: center; gap: 30px; padding: 24px 30px; }
.loading-copy { display: grid; grid-template-columns: auto 1fr; align-items: center; gap: 4px 9px; color: #2f5268; }
.loading-copy strong { font-size: 13px; }
.loading-copy span { grid-column: 2; color: #80919d; font-size: 11px; }
.empty-state { display: grid; place-items: center; }
.empty-state :deep(.ant-empty) { margin: 0; }
.empty-state :deep(.ant-empty-description strong), .empty-state :deep(.ant-empty-description span) { display: block; }
.empty-state :deep(.ant-empty-description strong) { color: #536b7b; font-weight: 600; }
.empty-state :deep(.ant-empty-description span) { margin-top: 3px; color: #91a0aa; font-size: 11px; }
@media (max-width: 680px) {
  .state-panel { min-height: 126px; }
  .loading-state { grid-template-columns: 1fr; gap: 16px; padding: 20px; }
}
</style>
