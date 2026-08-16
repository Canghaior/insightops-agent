import { apiClient } from './client'

export interface ModelStatus {
  provider: string
  model: string
  ready: boolean
}

export interface SystemStatus {
  service: string
  status: string
  timestamp: string
  model: ModelStatus
}

interface ApiResponse<T> {
  traceId: string
  data: T
}

export async function getSystemStatus(): Promise<SystemStatus> {
  const response = await apiClient.get<ApiResponse<SystemStatus>>('/system/status')
  return response.data.data
}
