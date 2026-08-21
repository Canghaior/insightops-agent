import { afterEach, describe, expect, it, vi } from 'vitest'

import { apiClient } from './client'
import { getLatestAgentCheckpoint, pauseAgentRun } from './checkpoints'

afterEach(() => vi.restoreAllMocks())

describe('agent checkpoint api', () => {
  it('requests a safe-point pause', async () => {
    const post = vi.spyOn(apiClient, 'post').mockResolvedValue({
      data: { data: { runId: 'run-1', status: 'PAUSE_REQUESTED' } },
    })

    await expect(pauseAgentRun('run/1')).resolves.toBe('PAUSE_REQUESTED')
    expect(post).toHaveBeenCalledWith('/runs/run%2F1/pause')
  })

  it('loads the latest resumable checkpoint', async () => {
    const checkpoint = {
      id: 'checkpoint-1', runId: 'run-1', sequence: 2, reason: 'SAFE_POINT',
      status: 'AVAILABLE', createdAt: '2026-08-22T00:00:00Z', resumedRunId: null,
    }
    const get = vi.spyOn(apiClient, 'get').mockResolvedValue({ data: { data: checkpoint } })

    await expect(getLatestAgentCheckpoint('run-1')).resolves.toEqual(checkpoint)
    expect(get).toHaveBeenCalledWith('/runs/run-1/checkpoint')
  })
})
