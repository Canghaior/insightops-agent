import { afterEach, describe, expect, it, vi } from 'vitest'

import { apiClient } from './client'
import {
  activateReleaseCandidate,
  createEvaluationDataset,
  getAgentEvaluationOverview,
  startAgentEvaluation,
} from './agentEvaluations'

afterEach(() => vi.restoreAllMocks())

describe('agent evaluation governance api', () => {
  it('loads datasets candidates runs and runtime defaults', async () => {
    const value = { governance: { datasets: [], candidates: [], recentRuns: [] }, defaults: {} }
    const get = vi.spyOn(apiClient, 'get').mockResolvedValue({ data: { data: value } })
    await expect(getAgentEvaluationOverview()).resolves.toEqual(value)
    expect(get).toHaveBeenCalledWith('/admin/agent-evaluations')
  })

  it('creates an immutable evaluation dataset', async () => {
    const input = { name: 'agent-core', description: '', gate: {} as never, cases: [] }
    const post = vi.spyOn(apiClient, 'post').mockResolvedValue({ data: { data: { id: 'd1' } } })
    await createEvaluationDataset(input)
    expect(post).toHaveBeenCalledWith('/admin/agent-evaluations/datasets', input)
  })

  it('queues a candidate evaluation', async () => {
    const post = vi.spyOn(apiClient, 'post').mockResolvedValue({ data: { data: { id: 'r1' } } })
    await startAgentEvaluation('d1', 'c1')
    expect(post).toHaveBeenCalledWith('/admin/agent-evaluations/runs', {
      datasetId: 'd1', candidateId: 'c1',
    })
  })

  it('activates only through the governed endpoint', async () => {
    const post = vi.spyOn(apiClient, 'post').mockResolvedValue({ data: { data: { id: 'c1' } } })
    await activateReleaseCandidate('c1', 'gate passed')
    expect(post).toHaveBeenCalledWith(
      '/admin/agent-evaluations/candidates/c1/activate', { reason: 'gate passed' },
    )
  })
})
