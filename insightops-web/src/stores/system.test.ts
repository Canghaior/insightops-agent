import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { apiClient } from '@/api/client'
import { useSystemStore } from './system'

describe('system capability state', () => {
  beforeEach(() => { setActivePinia(createPinia()); vi.restoreAllMocks() })

  it('removes a stale configuration snapshot when refresh fails without guessing the cause', async () => {
    vi.spyOn(apiClient, 'get')
      .mockResolvedValueOnce({ data: { data: {
        service: 'server', status: 'CONFIGURED', timestamp: '2026-11-09T00:00:00Z',
        model: { provider: 'deepseek', model: 'configured-model', ready: true, verified: false },
        verification: 'CONFIGURATION_ONLY', capabilities: [],
      } } })
      .mockRejectedValueOnce(new Error('network unavailable'))
    const store = useSystemStore()
    await store.refresh()
    expect(store.status?.model.verified).toBe(false)
    await store.refresh()
    expect(store.status).toBeNull()
    expect(store.error).toContain('无法读取')
    expect(store.error).not.toContain('未启动')
    expect(store.loading).toBe(false)
  })
})
