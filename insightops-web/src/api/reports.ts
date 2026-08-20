import { apiClient } from './client'

export type ReportEventType = 'GITHUB_RELEASE' | 'GITHUB_ISSUE' | 'GITHUB_PULL_REQUEST' | 'GITHUB_SECURITY_ADVISORY'
export type DeliveryStatus = 'PENDING' | 'RUNNING' | 'RETRY_WAIT' | 'SUCCEEDED' | 'FAILED'

export interface ReportItem {
  analysisId: string; projectId: string; projectName: string; eventType: ReportEventType
  versionTag: string | null; eventTitle: string; sourceUrl: string; riskLevel: 'LOW' | 'MEDIUM' | 'HIGH'
  recommendation: string; evidenceStatus: string; oneLineSummary: string; majorChanges: string[]
  javaImpact: string | null; upgradeValue: string | null; risks: string[]; recommendedActions: string[]
  evidenceUrls: string[]; occurredAt: string; completedAt: string
}

export interface ResearchReport {
  id: string; title: string; reportType: string; periodStart: string; periodEnd: string
  projectIds: string[]; eventTypes: ReportEventType[]; itemCount: number; highRiskCount: number
  items: ReportItem[]; markdown: string; createdAt: string
}
export interface ReportPage { items: ResearchReport[]; page: number; size: number; total: number }
export interface CreateReportCommand {
  title: string; periodStart: string; periodEnd: string; projectIds: string[]
  eventTypes: ReportEventType[]; maxItems: number
}
export interface DeliveryChannel {
  id: string; name: string; type: 'WEBHOOK'; endpointMasked: string
  enabled: boolean; createdAt: string; updatedAt: string
}
export interface DeliveryRecord {
  id: string; reportId: string; reportTitle: string; channelId: string; channelName: string
  channelType: 'WEBHOOK'; endpointMasked: string; status: DeliveryStatus; attempts: number
  maxAttempts: number; responseCode: number | null; durationMs: number | null; lastError: string | null
  nextAttemptAt: string; sentAt: string | null; createdAt: string; updatedAt: string
}
export interface DeliveryPage { items: DeliveryRecord[]; page: number; size: number; total: number }

export async function listReports(): Promise<ReportPage> {
  const response = await apiClient.get<{ data: ReportPage }>('/reports', { params: { page: 0, size: 50 } })
  return response.data.data
}
export async function createReport(command: CreateReportCommand): Promise<ResearchReport> {
  const response = await apiClient.post<{ data: ResearchReport }>('/reports', command)
  return response.data.data
}
export async function downloadReport(reportId: string, format: 'md' | 'pdf'): Promise<Blob> {
  const response = await apiClient.get<Blob>(`/reports/${reportId}/export.${format}`, {
    responseType: 'blob', timeout: 60_000,
  })
  return response.data
}
export async function listDeliveryChannels(): Promise<DeliveryChannel[]> {
  const response = await apiClient.get<{ data: DeliveryChannel[] }>('/delivery-channels')
  return response.data.data
}
export async function createDeliveryChannel(name: string, endpointUrl: string, enabled = true): Promise<DeliveryChannel> {
  const response = await apiClient.post<{ data: DeliveryChannel }>('/delivery-channels', { name, endpointUrl, enabled })
  return response.data.data
}
export async function updateDeliveryChannel(channel: DeliveryChannel, enabled: boolean): Promise<DeliveryChannel> {
  const response = await apiClient.put<{ data: DeliveryChannel }>(`/delivery-channels/${channel.id}`, {
    name: channel.name, endpointUrl: '', enabled,
  })
  return response.data.data
}
export async function deleteDeliveryChannel(channelId: string): Promise<void> {
  await apiClient.delete(`/delivery-channels/${channelId}`)
}
export async function enqueueReportDelivery(reportId: string, channelId: string): Promise<DeliveryRecord> {
  const response = await apiClient.post<{ data: DeliveryRecord }>(`/reports/${reportId}/deliveries`, { channelId })
  return response.data.data
}
export async function listReportDeliveries(): Promise<DeliveryPage> {
  const response = await apiClient.get<{ data: DeliveryPage }>('/report-deliveries', { params: { page: 0, size: 100 } })
  return response.data.data
}
export async function retryReportDelivery(deliveryId: string): Promise<DeliveryRecord> {
  const response = await apiClient.post<{ data: DeliveryRecord }>(`/report-deliveries/${deliveryId}/retry`)
  return response.data.data
}
