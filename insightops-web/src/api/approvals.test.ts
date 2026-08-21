import { afterEach, describe, expect, it, vi } from 'vitest'

import { apiClient } from './client'
import { approveToolAction, compensateToolAction, listApprovals } from './approvals'

describe('agent approvals api', () => {
  afterEach(() => vi.restoreAllMocks())

  it('loads pending approvals without exposing a write endpoint', async () => {
    const get = vi.spyOn(apiClient, 'get').mockResolvedValue({ data: { data: [] } })
    await listApprovals('PENDING')
    expect(get).toHaveBeenCalledWith('/agent/approvals', { params: { status: 'PENDING' } })
  })

  it('uses explicit approve and compensate decisions', async () => {
    const post = vi.spyOn(apiClient, 'post').mockResolvedValue({ data: { data: { id: 'a1' } } })
    await approveToolAction('a1', '确认')
    await compensateToolAction('a1', '恢复')
    expect(post).toHaveBeenNthCalledWith(1, '/agent/approvals/a1/approve', { comment: '确认' })
    expect(post).toHaveBeenNthCalledWith(2, '/agent/approvals/a1/compensate', { comment: '恢复' })
  })
})
