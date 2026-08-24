import { apiClient } from './client'
import type { WorkflowTemplate } from './agentWorkflows'

interface ApiResponse<T> { traceId: string; data: T }

export interface WorkflowPreset {
  id: string
  templateId: string
  templateVersionId: string
  name: string
  values: Record<string, unknown>
  createdAt: string
  updatedAt: string
}

export interface WorkflowExportBundle {
  schemaVersion: number
  exportedAt: string
  template: { name: string; description: string; category: string }
  version: {
    sourceVersion: number
    summary: string
    entryQuestion: string
    graph: Record<string, unknown>
  }
}

export interface WorkflowShare {
  id: string
  templateId: string
  templateVersionId: string
  status: 'ACTIVE' | 'REVOKED' | string
  expiresAt: string
  createdAt: string
  revokedAt: string | null
  importCount: number
  lastImportedAt: string | null
}

export interface WorkflowQualityMetric {
  bucket: string
  runCount: number
  succeededCount: number
  failedCount: number
  cancelledCount: number
  successRate: number
  averageDurationMs: number
  totalTokens: number
  estimatedCostCny: number
  feedbackCount: number
  helpfulRate: number
  citationCount: number
  citationCorrectRate: number
  nodeCount: number
  nodeSuccessRate: number
}

export interface WorkflowAnalytics {
  windowDays: number
  summary: WorkflowQualityMetric
  daily: WorkflowQualityMetric[]
  versions: WorkflowQualityMetric[]
  recentRuns: Array<{
    runId: string
    templateVersion: number
    status: string
    durationMs: number
    totalTokens: number
    estimatedCostCny: number
    helpful: boolean | null
    createdAt: string
  }>
}

export async function listWorkflowPresets(templateId: string, versionId: string) {
  const response = await apiClient.get<ApiResponse<WorkflowPreset[]>>('/agent-workflow-presets', {
    params: { templateId, versionId },
  })
  return response.data.data
}

export async function saveWorkflowPreset(
  templateId: string,
  versionId: string,
  name: string,
  values: Record<string, unknown>,
) {
  const response = await apiClient.post<ApiResponse<WorkflowPreset>>('/agent-workflow-presets', {
    templateId, versionId, name, values,
  })
  return response.data.data
}

export async function deleteWorkflowPreset(presetId: string) {
  await apiClient.delete(`/agent-workflow-presets/${encodeURIComponent(presetId)}`)
}

export async function getWorkflowAnalytics(templateId: string, days = 30) {
  const response = await apiClient.get<ApiResponse<WorkflowAnalytics>>(
    `/admin/agent-workflow-products/templates/${encodeURIComponent(templateId)}/analytics`,
    { params: { days } },
  )
  return response.data.data
}

export async function exportWorkflowBundle(templateId: string, versionId: string) {
  const response = await apiClient.get<ApiResponse<WorkflowExportBundle>>(
    `/admin/agent-workflow-products/templates/${encodeURIComponent(templateId)}`
      + `/versions/${encodeURIComponent(versionId)}/export`,
  )
  return response.data.data
}

export async function importWorkflowBundle(name: string, bundle: WorkflowExportBundle) {
  const response = await apiClient.post<ApiResponse<WorkflowTemplate>>(
    '/admin/agent-workflow-products/imports', { name, bundle },
  )
  return response.data.data
}

export async function listWorkflowShares(templateId: string) {
  const response = await apiClient.get<ApiResponse<WorkflowShare[]>>(
    `/admin/agent-workflow-products/templates/${encodeURIComponent(templateId)}/shares`,
  )
  return response.data.data
}

export async function createWorkflowShare(
  templateId: string, versionId: string, expiresInDays = 30,
) {
  const response = await apiClient.post<ApiResponse<{ share: WorkflowShare; token: string }>>(
    `/admin/agent-workflow-products/templates/${encodeURIComponent(templateId)}`
      + `/versions/${encodeURIComponent(versionId)}/shares`,
    { expiresInDays },
  )
  return response.data.data
}

export async function revokeWorkflowShare(shareId: string) {
  await apiClient.delete(`/admin/agent-workflow-products/shares/${encodeURIComponent(shareId)}`)
}

export async function previewSharedWorkflow(token: string) {
  const response = await apiClient.post<ApiResponse<{
    share: WorkflowShare
    bundle: WorkflowExportBundle
  }>>('/admin/agent-workflow-products/shared/preview', { token })
  return response.data.data
}

export async function importSharedWorkflow(token: string, name: string) {
  const response = await apiClient.post<ApiResponse<WorkflowTemplate>>(
    '/admin/agent-workflow-products/shared/imports', { token, name },
  )
  return response.data.data
}
