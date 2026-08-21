import { apiClient } from './client'

export interface AgentCostPolicy {
  workspaceId: string
  enabled: boolean
  dailyTokenLimit: number
  dailyCostLimitCny: number
  monthlyTokenLimit: number
  monthlyCostLimitCny: number
  maxConcurrentRuns: number
  warningPercent: number
  hardLimitEnabled: boolean
  version: number
  updatedBy: string | null
  updatedAt: string
}

export interface AgentCostUsage {
  dailyTokens: number
  dailyCostCny: number
  monthlyTokens: number
  monthlyCostCny: number
  activeReservations: number
}

export interface AgentCostLedgerEntry {
  id: string
  runId: string
  userId: string
  entryType: 'RESERVED' | 'SETTLED' | 'RELEASED' | 'REJECTED' | string
  tokenDelta: number
  costDeltaCny: number
  reason: string | null
  createdAt: string
}

export interface AgentCostOverview {
  policy: AgentCostPolicy
  usage: AgentCostUsage
  ledger: AgentCostLedgerEntry[]
}

interface ApiResponse<T> {
  traceId: string
  data: T
}

export type AgentCostPolicyUpdate = Pick<
  AgentCostPolicy,
  | 'enabled'
  | 'dailyTokenLimit'
  | 'dailyCostLimitCny'
  | 'monthlyTokenLimit'
  | 'monthlyCostLimitCny'
  | 'maxConcurrentRuns'
  | 'warningPercent'
  | 'hardLimitEnabled'
>

export async function getAgentCostOverview(): Promise<AgentCostOverview> {
  const response = await apiClient.get<ApiResponse<AgentCostOverview>>('/admin/agent-cost')
  return response.data.data
}

export async function updateAgentCostPolicy(
  policy: AgentCostPolicyUpdate,
): Promise<AgentCostPolicy> {
  const response = await apiClient.put<ApiResponse<AgentCostPolicy>>(
    '/admin/agent-cost/policy',
    policy,
  )
  return response.data.data
}
