import { apiClient } from './client'

export interface ConversationSummary {
  id: string
  title: string
  status: 'ACTIVE' | 'ARCHIVED'
  messageCount: number
  createdAt: string
  updatedAt: string
}

export async function listConversations(includeArchived = true): Promise<ConversationSummary[]> {
  const response = await apiClient.get<{ data: ConversationSummary[] }>('/chat/sessions', {
    params: { includeArchived },
  })
  return response.data.data
}

export async function updateConversation(
  id: string,
  update: { title?: string; archived?: boolean },
): Promise<ConversationSummary> {
  const response = await apiClient.patch<{ data: ConversationSummary }>(
    `/chat/sessions/${encodeURIComponent(id)}`,
    update,
  )
  return response.data.data
}

export async function deleteConversation(id: string): Promise<void> {
  await apiClient.delete(`/chat/sessions/${encodeURIComponent(id)}`)
}
