import { apiClient } from './client'

export type LocalRunStatus = 'QUEUED' | 'ANALYZING' | 'BASELINE' | 'EDITING' | 'TESTING' |
  'SUCCEEDED' | 'FAILED' | 'BLOCKED' | 'CANCELLED' | 'INTERRUPTED'

export interface LocalExecutionInput {
  requestId: string
  workspace: string
  sourceRevision: string
  modelChoice: string
  instruction: string
  writeScopes: string[]
  buildPlan?: { mode: 'ALL_TESTS' | 'SELECTED_CLASSES'; testClasses: string[] }
}

export interface LocalRun {
  id: string
  taskVersionId: string
  baselineVersion: number
  input: LocalExecutionInput
  status: LocalRunStatus
  createdAt: string
  finishedAt: string | null
  cancelRequested: boolean
  note: string
  verificationId: string | null
  patchSha256: string | null
}

export interface LocalRunEvent {
  sequence: number
  type: string
  message: string
  createdAt: string
  details: Record<string, unknown>
}

export interface LocalOverview {
  enabled: boolean
  taskReady: boolean
  workspaces: { id: string; revision: string }[]
  models: { id: string; label: string; provider: string }[]
  runs: LocalRun[]
}

export interface LocalExecutionDetail { run: LocalRun; events: LocalRunEvent[] }

export interface RegressionComparison {
  baselineExecuted: number
  baselinePassingOnCandidate: number
  failedThenPassed: number
  undistinguished: number
  cases: { suite: string; className: string; name: string; outcome: 'FAILURE_TO_PASS' | 'UNCONFIRMED'; reason: string }[]
  testFiles: string[]
  unobservedTestSources?: string[]
  limitation: string
}

const path = (report: string, task: string) =>
  `/reports/${encodeURIComponent(report)}/tasks/versions/${encodeURIComponent(task)}/local-execution`
const runPath = (report: string, task: string, id: string) => `${path(report, task)}/runs/${encodeURIComponent(id)}`

export async function localExecutionOverview(report: string, task: string) {
  return (await apiClient.get<{ data: LocalOverview }>(path(report, task))).data.data
}

export async function startLocalExecution(report: string, task: string, input: LocalExecutionInput) {
  return (await apiClient.post<{ data: LocalRun }>(`${path(report, task)}/runs`, input, { timeout: 35_000 })).data.data
}

export async function fetchLocalExecution(report: string, task: string, id: string) {
  return (await apiClient.get<{ data: LocalExecutionDetail }>(runPath(report, task, id))).data.data
}

export async function cancelLocalExecution(report: string, task: string, id: string) {
  return (await apiClient.post<{ data: LocalRun }>(`${runPath(report, task, id)}/cancel`)).data.data
}

export async function fetchLocalPatch(report: string, task: string, id: string) {
  return (await apiClient.get<string>(`${runPath(report, task, id)}/patch`, { responseType: 'text' })).data
}

export async function fetchLocalEvidence(report: string, task: string, id: string) {
  return (await apiClient.get<Blob>(`${runPath(report, task, id)}/evidence.zip`,
    { responseType: 'blob', timeout: 90_000 })).data
}
