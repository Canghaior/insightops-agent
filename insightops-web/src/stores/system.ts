import { defineStore } from 'pinia'
import { ref } from 'vue'

import { getSystemStatus, type SystemStatus } from '@/api/system'

export const useSystemStore = defineStore('system', () => {
  const status = ref<SystemStatus | null>(null)
  const loading = ref(false)
  const error = ref<string | null>(null)

  async function refresh() {
    loading.value = true
    error.value = null
    try {
      status.value = await getSystemStatus()
    } catch {
      status.value = null
      error.value = '暂时无法读取能力状态，请稍后重试。'
    } finally {
      loading.value = false
    }
  }

  return { status, loading, error, refresh }
})
