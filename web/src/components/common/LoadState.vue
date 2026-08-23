<template>
  <a-alert v-if="error" type="error" show-icon :message="title" :description="error">
    <template #action><a-button size="small" @click="$emit('retry')">重试</a-button></template>
  </a-alert>
  <div v-else-if="loading" style="padding: 48px; text-align: center"><a-spin :tip="loadingText" /></div>
  <a-empty v-else-if="empty" :description="emptyText" />
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
})
defineEmits(['retry'])
</script>
