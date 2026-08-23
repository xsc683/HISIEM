<template>
  <a-segmented :value="active" :options="options" @change="navigate" />
</template>

<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
const route = useRoute(); const router = useRouter()
const options = [
  { value: '/soar/playbooks', label: 'Playbook' },
  { value: '/soar/executions', label: '执行实例' },
  { value: '/soar/approvals', label: '人工审批' },
]
const active = computed(() => options.find((item) => route.path.startsWith(item.value))?.value || '/soar/playbooks')
function navigate(value) { router.push(value) }
</script>
