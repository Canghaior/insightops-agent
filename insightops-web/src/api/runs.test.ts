import { afterEach, describe, expect, it, vi } from 'vitest'

import { apiClient } from './client'
import { getRun, listRuns } from './runs'

describe('runs api', () => {
  afterEach(() => vi.restoreAllMocks())

  it('requests a filtered run page', async () => {
    const get = vi.spyOn(apiClient, 'get').mockResolvedValue({
      data: { traceId: 'trace-1', data: { items: [], total: 0, page: 0, size: 20, totalPages: 0 } },
    })

    const page = await listRuns(0, 20, 'SUCCEEDED')

    expect(page.total).toBe(0)
    expect(get).toHaveBeenCalledWith('/runs', {
      params: { page: 0, size: 20, status: 'SUCCEEDED' },
    })
  })

  it('requests one run detail', async () => {
    const get = vi.spyOn(apiClient, 'get').mockResolvedValue({
      data: { traceId: 'trace-2', data: { id: 'run-1', steps: [], toolCalls: [], sources: [] } },
    })

    const run = await getRun('run-1')

    expect(run.id).toBe('run-1')
    expect(get).toHaveBeenCalledWith('/runs/run-1')
  })
})
