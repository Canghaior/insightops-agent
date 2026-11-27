import { apiClient } from './client'
import type { Execution, Impact, Origin } from './reportReview'

export type Readiness = 'READY' | 'NEEDS_MATERIALS'
export const readinessLabels: Record<Readiness, string> = {
  READY: '可交给执行者', NEEDS_MATERIALS: '待补资料',
}
export interface TaskFields {
  origin: Origin
  originDetail?: string
  excerpt: string
  readiness: Readiness
  question: string
  deliverable: string
  inScope: string
  outOfScope: string
  facts: string
  inferences: string
  unknowns: string
  method: string
  completion: string
  stopConditions: string
  environment: string
  missingMaterials: string
  verificationVersionIds: string[]
  uploadIds: string[]
}
export interface TaskVersion {
  id: string
  taskId: string
  previousVersionId: string | null
  version: number
  fields: TaskFields
  reportId: string
  runId: string
  systemId: string
  systemRevision: number
  engineeringBaselineId: string | null
  engineeringBaselineVersion: number | null
  excerptLine: number
  author: { id: string; name: string }
  createdAt: string
}
export interface TaskResultFields {
  status: Execution
  impact: Impact
  method: string
  environment: string
  observation: string
  limits: string
  blocker: string
  executedBy: string
  executedAt: string | null
  sourceRevision: string
  candidateRevision: string
  unfinished: string
  uploadIds: string[]
  retainPreviousPatch?: boolean
  baselineBuildId: string | null
  candidateBuildId: string | null
}
export interface TaskResult {
  taskVersionId: string
  verificationVersionId: string
  executedAt: string | null
  sourceRevision: string
  candidateRevision: string
  unfinished: string
  patch: { name: string; size: number; sha256: string } | null
  recordedBy: { id: string; name: string }
  createdAt: string
}
export interface TaskHistory { versions: TaskVersion[]; results: TaskResult[] }
export interface SavedTaskResult {
  result: TaskResult
  verification: import('./reportReview').Verification
}
const tasksPath = (reportId: string) => `/reports/${encodeURIComponent(reportId)}/tasks`
const versionPath = (reportId: string, version: string) =>
  `${tasksPath(reportId)}/versions/${encodeURIComponent(version)}`

export async function getTasks(reportId: string): Promise<TaskHistory> {
  return (await apiClient.get<{ data: TaskHistory }>(tasksPath(reportId))).data.data
}
export async function saveTask(reportId: string, requestId: string, previousVersionId: string | null, fields: TaskFields) {
  return (await apiClient.post<{ data: TaskVersion }>(tasksPath(reportId), { requestId, previousVersionId, fields })).data.data
}
export async function saveTaskResult(reportId: string, taskVersionId: string, requestId: string,
  previousVerificationVersionId: string | null, fields: TaskResultFields, patch: File | null) {
  const form = new FormData()
  form.append('command', JSON.stringify({ ...fields, requestId, previousVerificationVersionId }))
  if (patch) form.append('patch', patch)
  return (await apiClient.post<{ data: TaskResult }>(versionPath(reportId, taskVersionId) + '/results', form,
    { timeout: 60_000 })).data.data
}
export async function downloadExecution(reportId: string, taskVersionId: string): Promise<Blob> {
  return (await apiClient.get<Blob>(versionPath(reportId, taskVersionId) + '/execution.zip',
    { responseType: 'blob', timeout: 120_000 })).data
}
