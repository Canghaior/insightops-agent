import { apiClient } from './client'
import type { AssessmentType } from '@/utils/assessmentTypes'
import type { UpgradeInput } from './upgradeAssessments'

export interface SystemBackground {
  name: string
  purpose: string
  keyProcesses: string
  technologyStack: string
  currentProblems: string
  constraints: string
}

export const backgroundFields = [
  { key: 'purpose', label: '用途' },
  { key: 'keyProcesses', label: '关键流程' },
  { key: 'technologyStack', label: '已知技术栈' },
  { key: 'currentProblems', label: '当前问题' },
  { key: 'constraints', label: '约束' },
] as const

export interface SystemAttachment { uploadId: string; name: string | null }
export interface BusinessSystem {
  id: string
  revision: number
  background: SystemBackground
  attachments: SystemAttachment[]
}
export interface SystemSummary { id: string; name: string; purpose: string; revision: number; updatedAt: string }
export interface SystemRun {
  runId: string; projectName: string | null; currentVersion: string | null; targetVersion: string | null
  assessmentType?: AssessmentType; question?: string | null
  systemRevision: number; status: string; createdAt: string
}
export interface SystemDraft { background: SystemBackground; uploadIds: string[] }

const base = '/business-systems'
const resource = (id: string) => `${base}/${encodeURIComponent(id)}`

export async function listSystems(): Promise<SystemSummary[]> {
  return (await apiClient.get(base)).data.data
}
export async function getSystem(id: string): Promise<BusinessSystem> {
  return (await apiClient.get(resource(id))).data.data
}
export async function availableSystemUploads(): Promise<SystemAttachment[]> {
  return (await apiClient.get(`${base}/available-uploads`)).data.data
}
export async function createSystem(draft: SystemDraft): Promise<BusinessSystem> {
  return (await apiClient.post(base, draft)).data.data
}
export async function updateSystem(id: string, expectedRevision: number, draft: SystemDraft): Promise<BusinessSystem> {
  return (await apiClient.put(resource(id), { ...draft, expectedRevision })).data.data
}
export async function systemHistory(id: string, page: number): Promise<{ items: SystemRun[]; hasMore: boolean }> {
  return (await apiClient.get(`${resource(id)}/runs`, { params: { page } })).data.data
}
export interface DecisionInput {
  engineeringBaselineId?: string
  modelChoice?: string
  question: string; observations: string; environment: string; projectId: string | null; uploadIds: string[]
}
export async function launchSystemDecision(id: string, expectedRevision: number, input: DecisionInput, requestId: string): Promise<{ runId: string }> {
  return (await apiClient.post(`${resource(id)}/decision-runs`, { ...input, requestId }, { params: { expectedRevision } })).data.data
}
export async function launchSystemUpgrade(id: string, expectedRevision: number, input: UpgradeInput, requestId: string): Promise<{ runId: string }> {
  return (await apiClient.post(`${resource(id)}/upgrade-runs`, { ...input, requestId }, { params: { expectedRevision } })).data.data
}
