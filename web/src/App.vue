<template>
  <a-config-provider :theme="theme">
    <router-view />
  </a-config-provider>
</template>

<script setup>
const theme = {
  token: {
    colorPrimary: '#1d6fa5',
    colorInfo: '#1d6fa5',
    colorSuccess: '#27856a',
    colorWarning: '#d58b28',
    colorError: '#c94a52',
    borderRadius: 8,
    fontFamily: "Inter, 'PingFang SC', 'Microsoft YaHei', sans-serif",
  },
}
</script>
