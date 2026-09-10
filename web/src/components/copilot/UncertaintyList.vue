<template>
  <a-card class="surface-card" size="small" :title="`不确定性 (${items.length})`">
    <a-empty v-if="!items.length" :image="Empty.PRESENTED_IMAGE_SIMPLE" description="未记录不确定性" />
    <ul v-else class="uncertainty-list">
      <li v-for="(item, index) in items" :key="index">
        <div class="uncertainty-desc">{{ item.description || '—' }}</div>
        <div v-if="item.missing_information" class="uncertainty-missing">
          <strong>缺失信息：</strong>{{ item.missing_information }}
        </div>
      </li>
    </ul>
  </a-card>
</template>

<script setup>
import { Empty } from 'ant-design-vue'

defineProps({ items: { type: Array, default: () => [] } })
</script>

<style scoped>
.uncertainty-list { margin: 0; padding-left: 18px; }
.uncertainty-list li { margin-bottom: 10px; }
.uncertainty-list li:last-child { margin-bottom: 0; }
.uncertainty-desc { color: #1d3244; line-height: 1.6; }
.uncertainty-missing { margin-top: 4px; color: #a8710f; font-size: 12px; }
</style>
