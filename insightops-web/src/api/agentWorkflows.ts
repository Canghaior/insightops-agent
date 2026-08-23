import { apiClient } from './client'

export interface WorkflowVersion {
  id: string
  templateId: string
  version: number
  status: 'DRAFT' | 'ACTIVE' | 'RETIRED' | string
  summary: string
  entryQuestion: string
  graphSpecJson: string
  createdAt: string
  activatedAt: string | null
}

export interface WorkflowTemplate {
  id: string
  name: string
  description: string
  category: string
  status: string
  activeVersionId: string | null
  createdAt: string
  updatedAt: string
  versions: WorkflowVersion[]
}

export interface WorkflowTool {
  name: string
  description: string
  riskLevel: 'READ_ONLY' | 'MUTATING' | string
  approvalPolicy: string
  inputSchema: Record<string, unknown>
}

export interface WorkflowNodePreview {
  id: string
  toolName: string
  argumentsJson: string
  dependencyIds: string[]
  condition: string
  required: boolean
  riskLevel: string
}

export interface WorkflowPreview {
  graphSpecJson: string
  reason: string
  nodes: WorkflowNodePreview[]
  waves: Array<{ index: number; nodeIds: string[] }>
  nodeCount: number
  maxParallelism: number
  mutatingNodeCount: number
  warnings: string[]
}

export interface AgentWorkflowOverview {
  templates: WorkflowTemplate[]
  tools: WorkflowTool[]
  maxNodes: number
}

interface ApiResponse<T> { traceId: string; data: T }

export async function getAgentWorkflowOverview(): Promise<AgentWorkflowOverview> {
  const response = await apiClient.get<ApiResponse<AgentWorkflowOverview>>('/admin/agent-workflows')
  return response.data.data
}

export async function previewAgentWorkflow(graph: Record<string, unknown>): Promise<WorkflowPreview> {
  const response = await apiClient.post<ApiResponse<WorkflowPreview>>(
    '/admin/agent-workflows/preview', { graph },
  )
  return response.data.data
}

export async function createAgentWorkflowTemplate(input: {
  name: string
  description: string
  category: string
  version: { summary: string; entryQuestion: string; graph: Record<string, unknown> }
}): Promise<WorkflowTemplate> {
  const response = await apiClient.post<ApiResponse<WorkflowTemplate>>(
    '/admin/agent-workflows/templates', input,
  )
  return response.data.data
}

export async function createAgentWorkflowVersion(
  templateId: string,
  input: { summary: string; entryQuestion: string; graph: Record<string, unknown> },
): Promise<WorkflowTemplate> {
  const response = await apiClient.post<ApiResponse<WorkflowTemplate>>(
    `/admin/agent-workflows/templates/${templateId}/versions`, input,
  )
  return response.data.data
}

export async function activateAgentWorkflowVersion(
  templateId: string, versionId: string, reason: string,
): Promise<WorkflowTemplate> {
  const response = await apiClient.post<ApiResponse<WorkflowTemplate>>(
    `/admin/agent-workflows/templates/${templateId}/versions/${versionId}/activate`, { reason },
  )
  return response.data.data
}
