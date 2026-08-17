import { apiClient } from './client'

export interface ProjectUpdate {
  eventId: string
  projectId: string
  projectName: string
  repositoryOwner: string
  versionTag: string
  title: string
  summary: string
  sourceUrl: string
  prerelease: boolean
  occurredAt: string
  collectedAt: string
  read: boolean
  analysisId: string | null
  analysisStatus: 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'RETRY_WAIT' | 'FAILED' | null
  riskLevel: 'LOW' | 'MEDIUM' | 'HIGH' | null
  recommendation: 'WATCH' | 'TRY' | 'UPGRADE' | null
  intelligenceSummary: string | null
}

export interface UpdatePage {
  items: ProjectUpdate[]
  page: number
  size: number
  total: number
  unreadCount: number
}

export async function listUpdates(options: {
  page?: number; size?: number; projectId?: string; unreadOnly?: boolean
} = {}): Promise<UpdatePage> {
  const response = await apiClient.get<{ data: UpdatePage }>('/updates', { params: options })
  return response.data.data
}

export async function getUnreadCount(): Promise<number> {
  const response = await apiClient.get<{ data: { count: number } }>('/updates/unread-count')
  return response.data.data.count
}

export async function markUpdateRead(eventId: string): Promise<void> {
  await apiClient.post(`/updates/${eventId}/read`)
}

export async function markAllUpdatesRead(): Promise<number> {
  const response = await apiClient.post<{ data: { count: number } }>('/updates/read-all')
  return response.data.data.count
}
