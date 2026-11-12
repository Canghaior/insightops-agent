import { apiClient } from './client'

export interface ModelStatus {
  provider: string
  model: string
  ready: boolean
  state?: 'CONFIGURED_UNVERIFIED' | 'DISABLED' | 'MISSING_CONFIGURATION'
  verified?: boolean
}

export interface SystemCapability {
  id: string
  label: string
  status: 'CONFIGURED_UNVERIFIED' | 'DISABLED' | 'MISSING_CONFIGURATION' | 'DEGRADED' | 'UNKNOWN'
  detail: string
}

export interface SystemStatus {
  service: string
  status: string
  timestamp: string
  model: ModelStatus
  verification?: 'CONFIGURATION_ONLY'
  capabilities?: SystemCapability[]
}

interface ApiResponse<T> {
  traceId: string
  data: T
}

export async function getSystemStatus(): Promise<SystemStatus> {
  const response = await apiClient.get<ApiResponse<SystemStatus>>('/system/status')
  return response.data.data
}
