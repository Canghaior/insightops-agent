import { apiClient } from './client'

export async function submitAnswerFeedback(
  runId: string, helpful: boolean, reason?: string, comment?: string,
): Promise<void> {
  await apiClient.put(`/research-feedback/runs/${runId}`, { helpful, reason, comment })
}

export async function submitCitationFeedback(
  runId: string, citationUrl: string, correct: boolean, comment?: string,
): Promise<void> {
  await apiClient.put(`/research-feedback/runs/${runId}/citations`, {
    citationUrl, correct, comment,
  })
}
