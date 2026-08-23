import { beforeEach, describe, expect, it, vi } from 'vitest'

const { get, post } = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
}))

vi.mock('./client', () => ({ apiClient: { get, post } }))

import {
  getWorkflowRun,
  launchWorkflow,
  listActiveWorkflows,
  retryWorkflowRun,
} from './workflowRuns'

describe('workflow run api', () => {
  beforeEach(() => vi.clearAllMocks())

  it('loads active workspace templates', async () => {
    get.mockResolvedValue({ data: { data: [{ id: 'template-1' }] } })
    await expect(listActiveWorkflows()).resolves.toEqual([{ id: 'template-1' }])
    expect(get).toHaveBeenCalledWith('/agent-workflows')
  })

  it('launches an immutable active version snapshot', async () => {
    post.mockResolvedValue({ data: { data: { runId: 'run-1', sessionId: 'session-1' } } })
    await launchWorkflow('template-1', 'version-2', { topic: 'Spring AI' })
    expect(post).toHaveBeenCalledWith(
      '/agent-workflows/template-1/runs',
      expect.objectContaining({
        expectedVersionId: 'version-2',
        inputs: { topic: 'Spring AI' },
        requestId: expect.any(String),
      }),
    )
  })

  it('reads workflow nodes and retries from a failed logical node', async () => {
    get.mockResolvedValue({ data: { data: { runId: 'run-1', nodes: [] } } })
    await getWorkflowRun('run-1')
    expect(get).toHaveBeenCalledWith('/agent-workflows/runs/run-1')

    post.mockResolvedValue({ data: { data: { runId: 'run-2' } } })
    await retryWorkflowRun('run-1', 'research')
    expect(post).toHaveBeenCalledWith(
      '/agent-workflows/runs/run-1/retries',
      { fromNodeId: 'research', requestId: expect.any(String) },
    )
  })
})
