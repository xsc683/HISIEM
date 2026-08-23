import { reactive } from 'vue'
import { authMe, hasSession, login, logout } from '../api/index.js'

const state = reactive({
  user: null,
  ready: false,
  loading: false,
})

let pending = null

export function useAuth() {
  async function ensure() {
    if (!hasSession()) {
      state.user = null
      state.ready = true
      return null
    }
    if (state.ready && state.user) return state.user
    if (pending) return pending
    state.loading = true
    pending = authMe()
      .then((user) => {
        state.user = user
        return user
      })
      .catch((error) => {
        state.user = null
        throw error
      })
      .finally(() => {
        state.loading = false
        state.ready = true
        pending = null
      })
    return pending
  }

  async function signIn(username, password) {
    state.loading = true
    try {
      await login(username, password)
      state.ready = false
      return await ensure()
    } finally {
      state.loading = false
    }
  }

  async function signOut() {
    try {
      await logout()
    } finally {
      state.user = null
      state.ready = true
    }
  }

  function reset() {
    state.user = null
    state.ready = true
  }

  return { state, ensure, signIn, signOut, reset }
}
