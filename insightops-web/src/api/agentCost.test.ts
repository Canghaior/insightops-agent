import { afterEach, describe, expect, it, vi } from 'vitest'

import { apiClient } from './client'
import { getAgentCostOverview, updateAgentCostPolicy } from './agentCost'

afterEach(() => vi.restoreAllMocks())

describe('agent cost governance api', () => {
  it('loads the workspace policy, usage and ledger', async () => {
    const value = {
      policy: { workspaceId: 'w1', dailyTokenLimit: 500000 },
      usage: { dailyTokens: 1200 },
      ledger: [{ id: 'l1', entryType: 'SETTLED' }],
    }
    const get = vi.spyOn(apiClient, 'get').mockResolvedValue({ data: { data: value } })

    await expect(getAgentCostOverview()).resolves.toEqual(value)
    expect(get).toHaveBeenCalledWith('/admin/agent-cost')
  })

  it('updates all quota policy fields atomically', async () => {
    const update = {
      enabled: true,
      dailyTokenLimit: 100,
      dailyCostLimitCny: 1,
      monthlyTokenLimit: 1000,
      monthlyCostLimitCny: 10,
      maxConcurrentRuns: 2,
      warningPercent: 80,
      hardLimitEnabled: true,
    }
    const put = vi.spyOn(apiClient, 'put').mockResolvedValue({
      data: { data: { workspaceId: 'w1', ...update, version: 2 } },
    })

    await updateAgentCostPolicy(update)
    expect(put).toHaveBeenCalledWith('/admin/agent-cost/policy', update)
  })
})
