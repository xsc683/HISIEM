<template>
  <div class="page-heading">
    <div>
      <h1>{{ title }}</h1>
      <p v-if="description">{{ description }}</p>
    </div>
    <div class="page-actions"><slot /></div>
  </div>
</template>

<script setup>
defineProps({ title: { type: String, required: true }, description: { type: String, default: '' } })
</script>
