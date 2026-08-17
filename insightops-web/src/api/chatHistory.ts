import { apiClient } from './client'

export interface ChatHistoryMessage {
  id: string
  role: 'SYSTEM' | 'USER' | 'ASSISTANT' | 'TOOL'
  content: string
  citations: string[]
  sequenceNo: number
  createdAt: string
}

export interface ChatSessionHistory {
  sessionId: string
  title: string
  messages: ChatHistoryMessage[]
  hasEarlierMessages: boolean
}

export async function getChatSessionHistory(
  sessionId: string,
  limit = 100,
): Promise<ChatSessionHistory | null> {
  try {
    const response = await apiClient.get<{ data: ChatSessionHistory }>(
      `/chat/sessions/${encodeURIComponent(sessionId)}/messages`,
      { params: { limit } },
    )
    return response.data.data
  } catch (error: unknown) {
    if (
      typeof error === 'object'
      && error !== null
      && 'response' in error
      && (error as { response?: { status?: number } }).response?.status === 404
    ) {
      return null
    }
    throw error
  }
}
