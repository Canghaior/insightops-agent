import { afterEach, describe, expect, it, vi } from 'vitest'
import { apiClient } from './client'
import { listKnowledgeSources, requestKnowledgeSync } from './admin'

describe('knowledge administration api', () => {
  afterEach(() => vi.restoreAllMocks())

  it('lists sources and requests a bounded official-document sync', async () => {
    const get = vi.spyOn(apiClient, 'get').mockResolvedValue({ data: { data: [{ sourceId: 'source-1' }] } })
    const post = vi.spyOn(apiClient, 'post').mockResolvedValue({ data: undefined })

    expect((await listKnowledgeSources())[0]?.sourceId).toBe('source-1')
    await requestKnowledgeSync('source-1')

    expect(get).toHaveBeenCalledWith('/admin/knowledge/sources')
    expect(post).toHaveBeenCalledWith('/admin/knowledge/sources/source-1/sync')
  })
})
