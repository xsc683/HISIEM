<template>
  <a-tooltip :title="description" placement="top">
    <a-tag :color="color" class="authority-tag" :data-authority="authority">
      <component :is="icon" class="authority-icon" />
      <span class="authority-text">{{ label }}</span>
    </a-tag>
  </a-tooltip>
</template>

<script setup>
import { computed } from 'vue'
import {
  BookOutlined,
  GlobalOutlined,
  QuestionCircleOutlined,
  SafetyCertificateOutlined,
  SettingOutlined,
} from '@ant-design/icons-vue'
import { AUTHORITY_LABELS, authorityDescription } from '../../utils/copilot.js'

// 权威类别只做展示：类别本身由服务端已持久化的来源分类映射得到（见 utils/copilot.js）。
// 图标 + 文案 + 颜色三者同时表达，颜色只是辅助，绝不单独承载语义。
const props = defineProps({ authority: { type: String, default: 'UNKNOWN' } })

const ICONS = {
  PLATFORM_FACT: SafetyCertificateOutlined,
  KNOWLEDGE_CONTEXT: BookOutlined,
  SYSTEM_CONTEXT: SettingOutlined,
  THREAT_INTEL: GlobalOutlined,
  UNKNOWN: QuestionCircleOutlined,
}
const COLORS = {
  PLATFORM_FACT: 'blue',
  KNOWLEDGE_CONTEXT: 'purple',
  SYSTEM_CONTEXT: 'default',
  THREAT_INTEL: 'cyan',
  UNKNOWN: 'orange',
}

const icon = computed(() => ICONS[props.authority] || ICONS.UNKNOWN)
const color = computed(() => COLORS[props.authority] || COLORS.UNKNOWN)
const label = computed(() => AUTHORITY_LABELS[props.authority] || AUTHORITY_LABELS.UNKNOWN)
const description = computed(() => authorityDescription(props.authority))
</script>

<style scoped>
.authority-tag { display: inline-flex; align-items: center; gap: 5px; margin: 0; font-weight: 600; }
.authority-icon { font-size: 12px; }
</style>
