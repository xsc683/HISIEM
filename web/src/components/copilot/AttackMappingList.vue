<template>
  <a-card class="surface-card" size="small" :title="`ATT&CK 映射 (${items.length})`">
    <a-empty v-if="!items.length" :image="Empty.PRESENTED_IMAGE_SIMPLE" description="未映射到 ATT&CK 技术" />
    <a-space v-else wrap>
      <a-tag v-for="(item, index) in items" :key="index" color="geekblue" class="attack-tag">
        <strong>{{ item.technique_id || item.name }}</strong>
        <span v-if="item.name"> · {{ item.name }}</span>
        <span v-if="item.framework" class="attack-meta">（{{ item.framework }}<template v-if="item.version"> {{ item.version }}</template>）</span>
      </a-tag>
    </a-space>
  </a-card>
</template>

<script setup>
import { Empty } from 'ant-design-vue'

defineProps({ items: { type: Array, default: () => [] } })
</script>

<style scoped>
.attack-tag { padding: 4px 10px; font-size: 12px; }
.attack-meta { color: #7c8b96; font-weight: 400; }
</style>
