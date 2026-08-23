import { afterEach, describe, expect, it, vi } from 'vitest'

import { apiClient } from './client'
import {
  activateAgentWorkflowVersion,
  createAgentWorkflowTemplate,
  getAgentWorkflowOverview,
  previewAgentWorkflow,
} from './agentWorkflows'

afterEach(() => vi.restoreAllMocks())

describe('agent workflow governance api', () => {
  it('loads template versions and tool contracts', async () => {
    const value = { templates: [], tools: [], maxNodes: 32 }
    const get = vi.spyOn(apiClient, 'get').mockResolvedValue({ data: { data: value } })
    await expect(getAgentWorkflowOverview()).resolves.toEqual(value)
    expect(get).toHaveBeenCalledWith('/admin/agent-workflows')
  })

  it('previews a graph without starting an agent run', async () => {
    const graph = { nodes: [] }
    const post = vi.spyOn(apiClient, 'post').mockResolvedValue({ data: { data: { nodeCount: 0 } } })
    await previewAgentWorkflow(graph)
    expect(post).toHaveBeenCalledWith('/admin/agent-workflows/preview', { graph })
  })

  it('creates immutable version one', async () => {
    const input = {
      name: 'Framework selection', description: '', category: 'RESEARCH',
      version: { summary: '', entryQuestion: 'compare', graph: { nodes: [] } },
    }
    const post = vi.spyOn(apiClient, 'post').mockResolvedValue({ data: { data: { id: 'w1' } } })
    await createAgentWorkflowTemplate(input)
    expect(post).toHaveBeenCalledWith('/admin/agent-workflows/templates', input)
  })

  it('activates a reviewed version through the audit endpoint', async () => {
    const post = vi.spyOn(apiClient, 'post').mockResolvedValue({ data: { data: { id: 'w1' } } })
    await activateAgentWorkflowVersion('w1', 'v1', 'reviewed')
    expect(post).toHaveBeenCalledWith(
      '/admin/agent-workflows/templates/w1/versions/v1/activate', { reason: 'reviewed' },
    )
  })
})
