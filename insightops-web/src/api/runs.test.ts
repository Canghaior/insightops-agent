import { afterEach, describe, expect, it, vi } from 'vitest'

import { apiClient } from './client'
import { getRun, listRuns, getRunEvidenceQuality, getRunEvidenceQualities } from './runs'

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

  it('deduplicates concurrent checks but refreshes later checks instead of retaining stale quality', async () => {
    const quality = { status: 'INSUFFICIENT_EVIDENCE', label: '证据不足', reasons: [], compatibilityVerified: false }
    const get = vi.spyOn(apiClient, 'get').mockResolvedValue({ data: { data: quality } })
    const first = getRunEvidenceQuality('run-1')
    const second = getRunEvidenceQuality('run-1')
    expect(first).toBe(second)
    expect(await first).toEqual(quality)
    await getRunEvidenceQuality('run-1')
    expect(get).toHaveBeenCalledTimes(2)
  })

  it('clears failed in-flight requests so a retry can actually fetch the assessment', async () => {
    const get = vi.spyOn(apiClient, 'get').mockRejectedValueOnce(new Error('offline'))
      .mockResolvedValueOnce({ data: { data: { status: 'REQUIRES_VALIDATION' } } })
    await expect(getRunEvidenceQuality('run-retry')).rejects.toThrow('offline')
    expect((await getRunEvidenceQuality('run-retry')).status).toBe('REQUIRES_VALIDATION')
    expect(get).toHaveBeenCalledTimes(2)
  })

  it('batches the current page without fetching when there are no runs', async () => {
    const get = vi.spyOn(apiClient, 'get').mockResolvedValue({ data: { data: {} } })
    await getRunEvidenceQualities([])
    expect(get).not.toHaveBeenCalled()
    await getRunEvidenceQualities(['a', 'a', 'b'])
    expect(get).toHaveBeenCalledWith('/runs/evidence-quality', { params: { runIds: 'a,b' } })
  })
})
