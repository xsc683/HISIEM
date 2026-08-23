import { onMounted, ref } from 'vue'

export function useAsyncState(loader, { immediate = true, initial = null } = {}) {
  const data = ref(initial)
  const loading = ref(false)
  const error = ref('')

  async function run(...args) {
    loading.value = true
    error.value = ''
    try {
      data.value = await loader(...args)
      return data.value
    } catch (cause) {
      error.value = cause?.message || '加载失败'
      throw cause
    } finally {
      loading.value = false
    }
  }

  if (immediate) {
    onMounted(async () => {
      try {
        await run()
      } catch (cause) {
        console.error('页面初始化请求失败', cause)
      }
    })
  }
  return { data, loading, error, run }
}
