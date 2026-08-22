import { apiClient } from './client'

export interface EvaluationGate {
  minimumSuccessRate: number
  minimumToolAccuracy: number
  minimumRecoveryRate: number
  minimumCitationRate: number
  maxAverageDurationMs: number
  maxAverageTokens: number
  maxAverageCostCny: number
}

export interface EvaluationCaseInput {
  caseKey: string
  question: string
  expectedTools: string[]
  forbiddenTools: string[]
  requiredSourceDomains: string[]
  expectRecovery: boolean
  maxToolRounds: number
  maxDurationMs: number
  maxTokens: number
  maxCostCny: number
  required: boolean
}

export interface EvaluationCase extends EvaluationCaseInput {
  id: string
  datasetId: string
  sourceRunId: string | null
}

export interface EvaluationDataset {
  id: string
  name: string
  description: string
  version: number
  status: string
  gate: EvaluationGate
  createdAt: string
  cases: EvaluationCase[]
}

export interface ReleaseCandidate {
  id: string
  name: string
  version: number
  status: 'DRAFT' | 'PASSED' | 'FAILED' | 'ACTIVE' | 'RETIRED' | string
  plannerPromptAppendix: string
  modelName: string
  temperature: number
  maxOutputTokens: number
  toolContractHash: string
  basedOnId: string | null
  createdAt: string
  evaluatedAt: string | null
  activatedAt: string | null
}

export interface EvaluationSummary {
  caseCount: number
  passedCaseCount: number
  successRate: number
  toolAccuracy: number
  recoveryRate: number
  citationRate: number
  averageDurationMs: number
  averageTokens: number
  averageCostCny: number
  passed: boolean
}

export interface EvaluationCaseResult {
  id: string
  caseId: string
  caseKey: string
  question: string
  agentRunId: string | null
  status: string
  actualTools: string[]
  missingTools: string[]
  forbiddenToolsUsed: string[]
  sourceUrls: string[]
  toolSelectionCorrect: boolean
  planCompleted: boolean
  recoveryObserved: boolean
  citationRequirementsMet: boolean
  durationMs: number
  totalTokens: number
  estimatedCostCny: number
  failureCode: string | null
}

export interface EvaluationRun {
  id: string
  datasetId: string
  datasetName: string
  datasetVersion: number
  candidateId: string
  candidateName: string
  candidateVersion: number
  baselineRunId: string | null
  status: 'QUEUED' | 'RUNNING' | 'PASSED' | 'FAILED' | string
  summary: EvaluationSummary | null
  baselineSummary: EvaluationSummary | null
  failureCode: string | null
  attemptCount: number
  claimedBy: string | null
  heartbeatAt: string | null
  leaseExpiresAt: string | null
  startedAt: string | null
  finishedAt: string | null
  createdAt: string
  results: EvaluationCaseResult[]
}

export interface RuntimeProfile {
  candidateId: string
  version: number
  name: string
  modelName: string
  temperature: number
  maxOutputTokens: number
  toolContractHash: string
  activatedAt: string
}

export interface AgentEvaluationOverview {
  governance: {
    datasets: EvaluationDataset[]
    candidates: ReleaseCandidate[]
    recentRuns: EvaluationRun[]
    activeProfile: RuntimeProfile | null
  }
  defaults: {
    modelName: string
    temperature: number
    maxOutputTokens: number
    toolContractHash: string
  }
}

interface ApiResponse<T> { traceId: string; data: T }

export async function getAgentEvaluationOverview(): Promise<AgentEvaluationOverview> {
  const response = await apiClient.get<ApiResponse<AgentEvaluationOverview>>(
    '/admin/agent-evaluations',
  )
  return response.data.data
}

export async function createEvaluationDataset(input: {
  name: string; description: string; gate: EvaluationGate; cases: EvaluationCaseInput[]
}): Promise<EvaluationDataset> {
  const response = await apiClient.post<ApiResponse<EvaluationDataset>>(
    '/admin/agent-evaluations/datasets', input,
  )
  return response.data.data
}

export async function deriveEvaluationDataset(input: {
  datasetId: string; sourceRunId: string; evaluationCase: EvaluationCaseInput
}): Promise<EvaluationDataset> {
  const response = await apiClient.post<ApiResponse<EvaluationDataset>>(
    `/admin/agent-evaluations/datasets/${input.datasetId}/derive-from-run`,
    { sourceRunId: input.sourceRunId, evaluationCase: input.evaluationCase },
  )
  return response.data.data
}

export async function createReleaseCandidate(input: {
  name: string
  plannerPromptAppendix: string
  modelName: string
  temperature: number
  maxOutputTokens: number
  basedOnId: string | null
}): Promise<ReleaseCandidate> {
  const response = await apiClient.post<ApiResponse<ReleaseCandidate>>(
    '/admin/agent-evaluations/candidates', input,
  )
  return response.data.data
}

export async function startAgentEvaluation(
  datasetId: string, candidateId: string,
): Promise<EvaluationRun> {
  const response = await apiClient.post<ApiResponse<EvaluationRun>>(
    '/admin/agent-evaluations/runs', { datasetId, candidateId },
  )
  return response.data.data
}

export async function getAgentEvaluationRun(runId: string): Promise<EvaluationRun> {
  const response = await apiClient.get<ApiResponse<EvaluationRun>>(
    `/admin/agent-evaluations/runs/${runId}`,
  )
  return response.data.data
}

export async function activateReleaseCandidate(
  candidateId: string, reason: string,
): Promise<ReleaseCandidate> {
  const response = await apiClient.post<ApiResponse<ReleaseCandidate>>(
    `/admin/agent-evaluations/candidates/${candidateId}/activate`, { reason },
  )
  return response.data.data
}
