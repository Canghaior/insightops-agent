import { apiClient } from './client'

export interface WorkflowInputDefinition {
  name: string
  type: 'string' | 'integer' | 'boolean' | 'string_array' | 'json' | 'json_array'
  required: boolean
  maxLength: number | null
  minimum: number | null
  maximum: number | null
  defaultValue: unknown
}

export interface ActiveWorkflowTemplate {
  id: string
  name: string
  description: string
  category: string
  activeVersionId: string
  version: number
  summary: string
  entryQuestion: string
  inputs: Record<string, WorkflowInputDefinition>
  graphSpecJson: string
}

export interface WorkflowAttempt {
  id: string
  attemptNo: number
  runAttempt: number
  workerId: string | null
  toolCallId: string | null
  status: string
  resolvedInput: unknown
  output: unknown
  inputTokens: number
  outputTokens: number
  estimatedCostCny: number
  errorCode: string | null
  startedAt: string
  finishedAt: string | null
}

export interface WorkflowRunNode {
  id: string
  logicalNodeId: string
  toolName: string
  toolVersion: number
  riskLevel: string
  required: boolean
  conditionType: string
  dependencyNodeIds: string[]
  argumentTemplate: unknown
  exposeOutputs: string[]
  resolvedInput: unknown
  output: unknown
  exposedOutput: unknown
  status: string
  attemptCount: number
  toolCallId: string | null
  planNodeId: string | null
  reusedFromNodeId: string | null
  inputTokens: number
  outputTokens: number
  estimatedCostCny: number
  errorCode: string | null
  startedAt: string | null
  finishedAt: string | null
  attempts: WorkflowAttempt[]
}

export interface WorkflowRunDetail {
  runId: string
  templateId: string | null
  templateVersionId: string | null
  templateName: string
  templateVersion: number
  entryQuestion: string
  graphSpec: unknown
  inputs: unknown
  toolContractFingerprint: string
  sourceRunId: string | null
  retryRootRunId: string | null
  retryFromNodeId: string | null
  createdAt: string
  nodes: WorkflowRunNode[]
}

interface ApiResponse<T> { data: T }

export async function listActiveWorkflows(): Promise<ActiveWorkflowTemplate[]> {
  const response = await apiClient.get<ApiResponse<ActiveWorkflowTemplate[]>>('/agent-workflows')
  return response.data.data
}

export async function launchWorkflow(
  templateId: string,
  expectedVersionId: string,
  inputs: Record<string, unknown>,
): Promise<{ runId: string; sessionId: string | null; duplicate: boolean }> {
  const response = await apiClient.post<ApiResponse<{ runId: string; sessionId: string | null; duplicate: boolean }>>(
    `/agent-workflows/${encodeURIComponent(templateId)}/runs`,
    { expectedVersionId, requestId: crypto.randomUUID(), inputs },
  )
  return response.data.data
}

export async function getWorkflowRun(runId: string): Promise<WorkflowRunDetail> {
  const response = await apiClient.get<ApiResponse<WorkflowRunDetail>>(
    `/agent-workflows/runs/${encodeURIComponent(runId)}`,
  )
  return response.data.data
}

export async function retryWorkflowRun(runId: string, fromNodeId: string) {
  const response = await apiClient.post<ApiResponse<{ runId: string; sessionId: string | null; duplicate: boolean }>>(
    `/agent-workflows/runs/${encodeURIComponent(runId)}/retries`,
    { fromNodeId, requestId: crypto.randomUUID() },
  )
  return response.data.data
}
