import { apiClient } from './client'

export type Execution = 'NOT_EXECUTED' | 'EXECUTED' | 'BLOCKED'
export type Impact = 'INCONCLUSIVE' | 'SUPPORTS' | 'CONTRADICTS'
export type Origin = 'MODEL' | 'HUMAN_OR_TOOL'
export const executions: Record<Execution, string> = { NOT_EXECUTED: '未执行', EXECUTED: '已执行', BLOCKED: '受阻' }
export const impacts: Record<Impact, string> = { INCONCLUSIVE: '仍无法判断', SUPPORTS: '支持该判断', CONTRADICTS: '出现反证' }
export const origins: Record<Origin, string> = { MODEL: '原模型建议', HUMAN_OR_TOOL: '人工或外部工具补充' }
export interface VerificationInput {
  question: string
  suggestionRef: string
  origin: Origin
  status: Execution
  method: string
  environment: string
  observation: string
  impact: Impact
  limits: string
  blocker: string
  uploadIds: string[]
  baselineBuildId?: string | null
  candidateBuildId?: string | null
  executedBy?: string | null
}
export interface DecisionInput {
  choice: string
  reason: string
  reconsiderWhen: string
  verificationVersionIds: string[]
}
interface Authored { id: string; author: { id: string; name: string }; createdAt: string }
export interface Verification extends Authored { previousVersionId: string | null; version: number; fields: VerificationInput; buildSummary?: string | null }
export interface HumanDecision extends Authored { fields: DecisionInput }
export interface ReviewHistory {
  context: { reportId: string; runId: string; systemId: string; systemRevision: number; requestedModel: string | null; responseModel: string | null; modelProvider: string | null }
  verifications: Verification[]
  decisions: HumanDecision[]
}

const path = (id: string) => `/reports/${encodeURIComponent(id)}/review`
export async function getReview(id: string): Promise<ReviewHistory> {
  return (await apiClient.get(path(id))).data.data
}
export async function saveVerification(id: string, requestId: string, previousVersionId: string | null, fields: VerificationInput): Promise<Verification> {
  return (await apiClient.post(path(id) + '/verifications', { requestId, previousVersionId, fields })).data.data
}
export async function saveDecision(id: string, requestId: string, fields: DecisionInput): Promise<HumanDecision> {
  return (await apiClient.post(path(id) + '/decisions', { requestId, fields })).data.data
}
export async function downloadVerificationHandoff(reportId: string, versionId: string): Promise<Blob> {
  return (await apiClient.get<Blob>(`${path(reportId)}/verifications/${encodeURIComponent(versionId)}/handoff.zip`,
    { responseType: 'blob', timeout: 120_000 })).data
}
