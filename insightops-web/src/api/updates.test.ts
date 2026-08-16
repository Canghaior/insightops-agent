import { afterEach, describe, expect, it, vi } from 'vitest'

import { apiClient } from './client'
import { getUnreadCount, listUpdates, markAllUpdatesRead, markUpdateRead } from './updates'

describe('updates api', () => {
  afterEach(() => vi.restoreAllMocks())

  it('requests a filtered project update page', async () => {
    const get = vi.spyOn(apiClient, 'get').mockResolvedValue({
      data: { traceId: 'trace-1', data: { items: [], page: 1, size: 10, total: 0, unreadCount: 0 } },
    })

    const page = await listUpdates({ page: 1, size: 10, projectId: 'project-1', unreadOnly: true })

    expect(page.page).toBe(1)
    expect(get).toHaveBeenCalledWith('/updates', {
      params: { page: 1, size: 10, projectId: 'project-1', unreadOnly: true },
    })
  })

  it('reads the unread count and records read state', async () => {
    const get = vi.spyOn(apiClient, 'get').mockResolvedValue({ data: { data: { count: 7 } } })
    const post = vi.spyOn(apiClient, 'post')
      .mockResolvedValueOnce({ data: undefined })
      .mockResolvedValueOnce({ data: { data: { count: 7 } } })

    expect(await getUnreadCount()).toBe(7)
    await markUpdateRead('event-1')
    expect(await markAllUpdatesRead()).toBe(7)

    expect(get).toHaveBeenCalledWith('/updates/unread-count')
    expect(post).toHaveBeenNthCalledWith(1, '/updates/event-1/read')
    expect(post).toHaveBeenNthCalledWith(2, '/updates/read-all')
  })
})
