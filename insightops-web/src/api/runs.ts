import { apiClient } from './client'
import type { ChatCitation } from './agentStream'

export type RunStatus = 'CREATED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED'

export interface RunSummary {
  id: string
  sessionId: string
  traceId: string
  status: RunStatus
  question: string
  modelProvider: string | null
  modelName: string | null
  toolRounds: number
  promptTokens: number | null
  completionTokens: number | null
  durationMs: number | null
  createdAt: string
  finishedAt: string | null
}

export interface RunStep {
  id: string
  stepNo: number
  stepType: string
  status: string
  inputPayload: unknown
  outputPayload: unknown
  durationMs: number | null
  startedAt: string | null
  finishedAt: string | null
}

export interface RunToolCall {
  id: string
  stepId: string | null
  toolName: string
  status: string
  requestPayload: unknown
  resultPayload: unknown
  errorMessage: string | null
  durationMs: number | null
  createdAt: string
  finishedAt: string | null
}

export interface RunDetail extends RunSummary {
  answer: string | null
  estimatedCostCny: number | null
  pricingEffectiveDate: string | null
  failureCode: string | null
  failureMessage: string | null
  startedAt: string | null
  sources: string[]
  citationDetails: ChatCitation[]
  steps: RunStep[]
  toolCalls: RunToolCall[]
}

export interface RunPage {
  items: RunSummary[]
  total: number
  page: number
  size: number
  totalPages: number
}

interface ApiResponse<T> {
  traceId: string
  data: T
}

export async function listRuns(page = 0, size = 20, status?: RunStatus): Promise<RunPage> {
  const response = await apiClient.get<ApiResponse<RunPage>>('/runs', {
    params: { page, size, ...(status ? { status } : {}) },
  })
  return response.data.data
}

export async function getRun(runId: string): Promise<RunDetail> {
  const response = await apiClient.get<ApiResponse<RunDetail>>(`/runs/${runId}`)
  return response.data.data
}
