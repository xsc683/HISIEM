<template>
  <a-form layout="vertical">
    <a-form-item label="审批提示语" required extra="可引用 ${alert.id}、${case.id} 和已完成节点输出。">
      <a-textarea :value="modelValue.prompt" :rows="4" placeholder="请复核 ${alert.id} 的处置结果" @change="update($event.target.value)" />
    </a-form-item>
    <a-alert type="info" show-icon message="批准走 approve 分支，拒绝走 reject 分支；两条分支都必须连接。" />
  </a-form>
</template>

<script setup>
const props = defineProps({ modelValue: { type: Object, required: true } })
const emit = defineEmits(['update:modelValue'])
function update(prompt) { emit('update:modelValue', { ...props.modelValue, prompt }) }
</script>
