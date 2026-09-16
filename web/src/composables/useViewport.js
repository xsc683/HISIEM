import { onBeforeUnmount, onMounted, ref } from 'vue'

// 窄屏判定（Stage D §15/§16）：单一来源，供页面把双列描述降为单列、把时间线保持线性。
// 用 matchMedia 而不是 window.innerWidth 轮询，避免 resize 抖动；非浏览器环境安全降级为 false。
const NARROW_QUERY = '(max-width: 767px)'

export function useNarrowViewport() {
  const isNarrow = ref(false)
  if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') {
    return { isNarrow }
  }
  const query = window.matchMedia(NARROW_QUERY)
  isNarrow.value = query.matches
  const onChange = (event) => { isNarrow.value = event.matches }
  onMounted(() => {
    isNarrow.value = query.matches
    query.addEventListener('change', onChange)
  })
  onBeforeUnmount(() => query.removeEventListener('change', onChange))
  return { isNarrow }
}
