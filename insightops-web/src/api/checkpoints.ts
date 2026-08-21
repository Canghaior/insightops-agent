import { apiClient } from './client'

export interface AgentCheckpoint {
  id: string
  runId: string
  sequence: number
  reason: string
  status: string
  createdAt: string
  resumedRunId: string | null
}

interface ApiResponse<T> {
  traceId: string
  data: T
}

export async function pauseAgentRun(runId: string): Promise<'PAUSE_REQUESTED'> {
  const response = await apiClient.post<ApiResponse<{ runId: string; status: 'PAUSE_REQUESTED' }>>(
    `/runs/${encodeURIComponent(runId)}/pause`,
  )
  return response.data.data.status
}

export async function getLatestAgentCheckpoint(runId: string): Promise<AgentCheckpoint> {
  const response = await apiClient.get<ApiResponse<AgentCheckpoint>>(
    `/runs/${encodeURIComponent(runId)}/checkpoint`,
  )
  return response.data.data
}
