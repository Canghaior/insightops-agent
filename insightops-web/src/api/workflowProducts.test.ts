import { afterEach, describe, expect, it, vi } from 'vitest'

import { apiClient } from './client'
import {
  createWorkflowShare,
  exportWorkflowBundle,
  getWorkflowAnalytics,
  importWorkflowBundle,
  listWorkflowPresets,
  previewSharedWorkflow,
  importSharedWorkflow,
  saveWorkflowPreset,
} from './workflowProducts'

afterEach(() => vi.restoreAllMocks())

describe('P2.4-C workflow product api', () => {
  it('loads user presets for the immutable active version', async () => {
    const presets = [{ id: 'preset-1', name: 'Spring AI' }]
    const get = vi.spyOn(apiClient, 'get').mockResolvedValue({ data: { data: presets } })

    await expect(listWorkflowPresets('template-1', 'version-1')).resolves.toEqual(presets)
    expect(get).toHaveBeenCalledWith('/agent-workflow-presets', {
      params: { templateId: 'template-1', versionId: 'version-1' },
    })
  })

  it('saves validated preset values', async () => {
    const post = vi.spyOn(apiClient, 'post').mockResolvedValue({ data: { data: { id: 'p1' } } })
    await saveWorkflowPreset('t1', 'v1', 'Upgrade', { topic: 'Spring AI' })
    expect(post).toHaveBeenCalledWith('/agent-workflow-presets', {
      templateId: 't1', versionId: 'v1', name: 'Upgrade', values: { topic: 'Spring AI' },
    })
  })

  it('exports and reimports the portable schema bundle', async () => {
    const bundle = {
      schemaVersion: 1,
      exportedAt: '2026-08-24T00:00:00Z',
      template: { name: 'Research', description: '', category: 'TECH_RESEARCH' },
      version: { sourceVersion: 1, summary: '', entryQuestion: 'Research', graph: { nodes: [] } },
    }
    const get = vi.spyOn(apiClient, 'get').mockResolvedValue({ data: { data: bundle } })
    const post = vi.spyOn(apiClient, 'post').mockResolvedValue({ data: { data: { id: 'copy' } } })

    await expect(exportWorkflowBundle('t1', 'v1')).resolves.toEqual(bundle)
    await importWorkflowBundle('Research copy', bundle)

    expect(get).toHaveBeenCalledWith(
      '/admin/agent-workflow-products/templates/t1/versions/v1/export',
    )
    expect(post).toHaveBeenCalledWith('/admin/agent-workflow-products/imports', {
      name: 'Research copy', bundle,
    })
  })

  it('creates a time-limited share whose raw token is returned once', async () => {
    const value = { share: { id: 's1' }, token: 'one-time-token' }
    const post = vi.spyOn(apiClient, 'post').mockResolvedValue({ data: { data: value } })
    await expect(createWorkflowShare('t1', 'v1', 30)).resolves.toEqual(value)
    expect(post).toHaveBeenCalledWith(
      '/admin/agent-workflow-products/templates/t1/versions/v1/shares',
      { expiresInDays: 30 },
    )
  })

  it('keeps raw share tokens out of request URLs', async () => {
    const post = vi.spyOn(apiClient, 'post').mockResolvedValue({ data: { data: {} } })
    await previewSharedWorkflow('secret-token')
    await importSharedWorkflow('secret-token', 'Shared copy')
    expect(post).toHaveBeenNthCalledWith(1,
      '/admin/agent-workflow-products/shared/preview', { token: 'secret-token' })
    expect(post).toHaveBeenNthCalledWith(2,
      '/admin/agent-workflow-products/shared/imports',
      { token: 'secret-token', name: 'Shared copy' })
  })

  it('loads quality trends from real run aggregates', async () => {
    const value = { windowDays: 30, summary: { runCount: 2 }, daily: [] }
    const get = vi.spyOn(apiClient, 'get').mockResolvedValue({ data: { data: value } })
    await expect(getWorkflowAnalytics('t1', 30)).resolves.toEqual(value)
    expect(get).toHaveBeenCalledWith(
      '/admin/agent-workflow-products/templates/t1/analytics', { params: { days: 30 } },
    )
  })
})
