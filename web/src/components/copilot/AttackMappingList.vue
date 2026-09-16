<template>
  <a-card class="surface-card" size="small" :title="`ATT&CK 映射 (${items.length})`">
    <a-empty v-if="!items.length" :image="Empty.PRESENTED_IMAGE_SIMPLE" description="未映射到 ATT&CK 技术" />
    <a-space v-else wrap>
      <a-tag v-for="(item, index) in items" :key="index" color="geekblue" class="attack-tag">
        <strong>{{ item.technique_id || item.name }}</strong>
        <span v-if="item.name"> · {{ item.name }}</span>
        <span v-if="releaseOf(item)" class="attack-meta">（{{ releaseOf(item) }}）</span>
      </a-tag>
    </a-space>
  </a-card>
</template>

<script setup>
import { Empty } from 'ant-design-vue'

defineProps({ items: { type: Array, default: () => [] } })

// 框架与版本拼成一个完整字符串：避免依赖模板里的空白文本节点来决定是否有空格。
function releaseOf(item) {
  const framework = String(item?.framework || '').trim()
  const version = String(item?.version || '').trim()
  if (framework && version) return `${framework} ${version}`
  return framework || version
}
</script>

<style scoped>
.attack-tag { padding: 4px 10px; font-size: 12px; }
.attack-meta { color: #7c8b96; font-weight: 400; }
</style>
