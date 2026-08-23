<template>
  <a-form layout="vertical">
    <a-form-item label="等待时长" required>
      <a-input-number :value="Number(modelValue.amount || 1)" :min="1" :max="modelValue.unit === 'hours' ? 720 : 43200" style="width:100%" @change="patch({ amount: $event })" />
    </a-form-item>
    <a-form-item label="单位" required>
      <a-radio-group :value="modelValue.unit || 'minutes'" @change="patch({ unit: $event.target.value })">
        <a-radio-button value="minutes">分钟</a-radio-button><a-radio-button value="hours">小时</a-radio-button>
      </a-radio-group>
    </a-form-item>
    <a-alert type="info" show-icon message="等待会持久化 next_run_at；服务重启后仍会在到期时继续。" />
  </a-form>
</template>

<script setup>
const props = defineProps({ modelValue: { type: Object, required: true } })
const emit = defineEmits(['update:modelValue'])
function patch(value) { emit('update:modelValue', { ...props.modelValue, ...value }) }
</script>
