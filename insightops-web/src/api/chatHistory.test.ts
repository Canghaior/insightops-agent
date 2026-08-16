import { afterEach, describe, expect, it, vi } from 'vitest'

import { apiClient } from './client'
import { getChatSessionHistory } from './chatHistory'

describe('getChatSessionHistory', () => {
  afterEach(() => vi.restoreAllMocks())

  it('returns messages in the server order', async () => {
    vi.spyOn(apiClient, 'get').mockResolvedValue({
      data: {
        data: {
          sessionId: 'session-1',
          title: 'Spring AI',
          hasEarlierMessages: false,
          messages: [
            { id: 'm1', role: 'USER', content: '问题', sequenceNo: 1, createdAt: '2026-08-16T00:00:00Z' },
            { id: 'm2', role: 'ASSISTANT', content: '回答', sequenceNo: 2, createdAt: '2026-08-16T00:00:01Z' },
          ],
        },
      },
    })

    const history = await getChatSessionHistory('session-1')

    expect(history?.messages.map((message) => message.role)).toEqual(['USER', 'ASSISTANT'])
    expect(apiClient.get).toHaveBeenCalledWith(
      '/chat/sessions/session-1/messages',
      { params: { limit: 100 } },
    )
  })

  it('returns null when a stored browser session no longer exists', async () => {
    vi.spyOn(apiClient, 'get').mockRejectedValue({ response: { status: 404 } })

    await expect(getChatSessionHistory('missing')).resolves.toBeNull()
  })
})
