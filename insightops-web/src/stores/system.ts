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
      error.value = '后端暂未启动'
    } finally {
      loading.value = false
    }
  }

  return { status, loading, error, refresh }
})
