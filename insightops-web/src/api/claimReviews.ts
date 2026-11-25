import { apiClient } from './client'

export interface SourcePassage { label: string; url: string; version?: string; heading?: string; text: string }
export interface ClaimSource { label: string; url: string; title: string; heading?: string; versionLabel?: string }
export const verdicts = { INSUFFICIENT: '依据不足', SUPPORTS: '依据支持', CONTRADICTS: '依据与建议矛盾' } as const
export interface ClaimDraft {
  answerLine: number; path: string; codeLine: number | null; source: string; quotation: string
  verdict: keyof typeof verdicts; reason: string; test: string; verificationId: string | null
}
export interface SavedClaim {
  id: string; authorId: string; createdAt: string; draft: ClaimDraft
  evidence: { claim: string; code: string; targetVersion: string; sourceUrl: string; sourceVersion: string
    verificationStatus: string; observation: string }
}
const path = (id: string) => '/reports/' + encodeURIComponent(id) + '/claim-reviews'
export async function claimReviews(id: string): Promise<SavedClaim[]> {
  return (await apiClient.get(path(id))).data.data
}
export async function addClaim(id: string, requestId: string, draft: ClaimDraft): Promise<SavedClaim> {
  return (await apiClient.post(path(id), { requestId, draft })).data.data
}
