import { apiClient } from './client'

export type ApprovalStatus = 'PENDING' | 'EXECUTED' | 'REJECTED' | 'EXPIRED' | 'FAILED' | 'COMPENSATED'

export interface AgentToolApproval {
  id: string
  runId: string
  toolCallId: string
  toolName: string
  summary: string
  status: ApprovalStatus
  requestPayload: string
  resultPayload: string | null
  errorCode: string | null
  decisionComment: string | null
  expiresAt: string
  decidedAt: string | null
  executedAt: string | null
  compensatedAt: string | null
  createdAt: string
  updatedAt: string
}

export async function listApprovals(status?: ApprovalStatus): Promise<AgentToolApproval[]> {
  const response = await apiClient.get<{ data: AgentToolApproval[] }>('/agent/approvals', {
    params: status ? { status } : undefined,
  })
  return response.data.data
}

async function decide(
  id: string,
  action: 'approve' | 'reject' | 'compensate',
  comment?: string,
): Promise<AgentToolApproval> {
  const response = await apiClient.post<{ data: AgentToolApproval }>(
    `/agent/approvals/${encodeURIComponent(id)}/${action}`,
    { comment: comment || null },
  )
  return response.data.data
}

export const approveToolAction = (id: string, comment?: string) => decide(id, 'approve', comment)
export const rejectToolAction = (id: string, comment?: string) => decide(id, 'reject', comment)
export const compensateToolAction = (id: string, comment?: string) => decide(id, 'compensate', comment)
