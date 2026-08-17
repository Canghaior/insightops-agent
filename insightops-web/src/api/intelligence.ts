import { apiClient } from './client'

export type AnalysisStatus = 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'RETRY_WAIT' | 'FAILED'
export type RiskLevel = 'LOW' | 'MEDIUM' | 'HIGH'
export type Recommendation = 'WATCH' | 'TRY' | 'UPGRADE'

export interface AnalysisSummary {
  analysisId: string; eventId: string; projectId: string; projectName: string
  versionTag: string; releaseTitle: string; sourceUrl: string; status: AnalysisStatus
  riskLevel: RiskLevel | null; recommendation: Recommendation | null
  evidenceStatus: 'SUFFICIENT' | 'INSUFFICIENT' | null; oneLineSummary: string | null
  occurredAt: string; completedAt: string | null
}
export interface AnalysisPage { items: AnalysisSummary[]; page: number; size: number; total: number }
export interface AnalysisDetail {
  summary: AnalysisSummary; majorChanges: string[]; releaseSummary: string
  javaImpact: string | null; upgradeValue: string | null; risks: string[]
  recommendedActions: string[]; evidenceUrls: string[]; modelProvider: string | null
  modelName: string | null; promptTokens: number | null; completionTokens: number | null
  estimatedCostCny: number | null; pricingEffectiveDate: string | null; attempts: number; lastError: string | null
}
export interface DigestPreference { cadence: 'OFF' | 'DAILY' | 'WEEKLY'; timeZone: string; deliveryHour: number; projectIds: string[] }
export interface DigestSummary {
  id: string; cadence: 'DAILY' | 'WEEKLY'; periodStart: string; periodEnd: string
  title: string; items: AnalysisSummary[]; itemCount: number; highRiskCount: number
  read: boolean; createdAt: string
}
export interface DigestPage { items: DigestSummary[]; page: number; size: number; total: number; unreadCount: number }
export interface NotificationItem {
  id: string; type: string; severity: 'INFO' | 'WARNING' | 'CRITICAL'; title: string
  body: string; entityId: string; read: boolean; createdAt: string
}
export interface NotificationPage { items: NotificationItem[]; page: number; size: number; total: number; unreadCount: number }

export async function listIntelligence(options: { page?: number; size?: number; projectId?: string; riskLevel?: string } = {}): Promise<AnalysisPage> {
  const response = await apiClient.get<{ data: AnalysisPage }>('/intelligence', { params: options })
  return response.data.data
}
export async function getIntelligence(id: string): Promise<AnalysisDetail> {
  const response = await apiClient.get<{ data: AnalysisDetail }>(`/intelligence/${id}`)
  return response.data.data
}
export async function listDigests(page = 0, size = 20): Promise<DigestPage> {
  const response = await apiClient.get<{ data: DigestPage }>('/digests', { params: { page, size } })
  return response.data.data
}
export async function markDigestRead(id: string): Promise<void> { await apiClient.post(`/digests/${id}/read`) }
export async function getDigestPreference(): Promise<DigestPreference> {
  const response = await apiClient.get<{ data: DigestPreference }>('/digests/preference')
  return response.data.data
}
export async function saveDigestPreference(value: DigestPreference): Promise<DigestPreference> {
  const response = await apiClient.put<{ data: DigestPreference }>('/digests/preference', value)
  return response.data.data
}
export async function listNotifications(unreadOnly = false): Promise<NotificationPage> {
  const response = await apiClient.get<{ data: NotificationPage }>('/notifications', { params: { page: 0, size: 50, unreadOnly } })
  return response.data.data
}
export async function getNotificationUnreadCount(): Promise<number> {
  const response = await apiClient.get<{ data: { count: number } }>('/notifications/unread-count')
  return response.data.data.count
}
export async function markNotificationRead(id: string): Promise<void> { await apiClient.post(`/notifications/${id}/read`) }
