import { afterEach, describe, expect, it, vi } from 'vitest'
import { apiClient } from './client'
import {
  createKnowledgeSource,
  deleteKnowledgeSource,
  listKnowledgeSources,
  requestKnowledgeSync,
  setKnowledgeSourceEnabled,
  updateKnowledgeSource,
} from './admin'

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

  it('creates, updates, pauses, and deletes a configurable source', async () => {
    const input = {
      projectId: 'project-1', name: 'Docs', sourceType: 'OFFICIAL_DOCUMENTATION',
      rootUrl: 'https://docs.example.com/guide/',
      discoveryUrl: 'https://docs.example.com/sitemap.xml',
      allowedPathPrefix: '/guide/', syncIntervalHours: 12,
    }
    const post = vi.spyOn(apiClient, 'post').mockResolvedValue({ data: { data: { sourceId: 'source-1' } } })
    const put = vi.spyOn(apiClient, 'put').mockResolvedValue({ data: { data: { sourceId: 'source-1' } } })
    const patch = vi.spyOn(apiClient, 'patch').mockResolvedValue({ data: { data: { sourceId: 'source-1' } } })
    const remove = vi.spyOn(apiClient, 'delete').mockResolvedValue({ data: undefined })

    await createKnowledgeSource(input)
    await updateKnowledgeSource('source-1', input)
    await setKnowledgeSourceEnabled('source-1', false)
    await deleteKnowledgeSource('source-1')

    expect(post).toHaveBeenCalledWith('/admin/knowledge/sources', input)
    expect(put).toHaveBeenCalledWith('/admin/knowledge/sources/source-1', input)
    expect(patch).toHaveBeenCalledWith('/admin/knowledge/sources/source-1/status', { enabled: false })
    expect(remove).toHaveBeenCalledWith('/admin/knowledge/sources/source-1')
  })
})
