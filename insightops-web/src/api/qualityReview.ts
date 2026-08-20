import { apiClient } from './client'
import type { RagEvaluationReport } from './admin'

export type FeedbackType = 'ANSWER' | 'CITATION'
export type FeedbackStatus = 'PENDING' | 'REVIEWED' | 'ADDED_TO_EVAL' | 'DISMISSED'
export type CandidateStatus = 'DRAFT' | 'APPROVED' | 'REJECTED' | 'INCLUDED'

export interface QualityFeedback {
  id: string
  type: FeedbackType
  reviewStatus: FeedbackStatus
  userId: string
  username: string
  displayName: string
  runId: string
  sessionId: string | null
  traceId: string
  question: string
  answer: string | null
  modelProvider: string | null
  modelName: string | null
  citations: string[]
  helpful: boolean | null
  reason: string | null
  comment: string | null
  citationUrl: string | null
  citationCorrect: boolean | null
  reviewerNote: string | null
  reviewerDisplayName: string | null
  createdAt: string
  reviewedAt: string | null
  candidateId: string | null
}

export interface CandidateCommand {
  question: string
  expectedAnswerable: boolean
  expectedProject: string | null
  category: string
  mustHitTerms: string[]
  answerMustInclude: string[]
  sourceDomain: string | null
}

export interface EvaluationCandidate extends CandidateCommand {
  id: string
  sourceFeedbackType: FeedbackType
  sourceFeedbackId: string
  status: CandidateStatus
  reviewerNote: string | null
  reviewerDisplayName: string | null
  datasetVersionId: string | null
  createdAt: string
  updatedAt: string
}

export interface DatasetVersion {
  id: string
  versionNumber: number
  name: string
  status: 'DRAFT' | 'ACTIVE' | 'RETIRED'
  baseDatasetName: string
  candidateCount: number
  gateRunId: string | null
  gateStatus: string | null
  createdAt: string
  activatedAt: string | null
}

export interface Page<T> { items: T[]; total: number; page: number; size: number }

export async function listQualityFeedback(status?: string, type?: string): Promise<Page<QualityFeedback>> {
  const response = await apiClient.get<{ data: Page<QualityFeedback> }>('/admin/quality/feedback', {
    params: { status: status || undefined, type: type || undefined, page: 0, size: 100 },
  })
  return response.data.data
}

export async function reviewQualityFeedback(
  item: QualityFeedback,
  decision: 'REVIEWED' | 'DISMISSED' | 'ADD_TO_EVAL',
  note: string,
  candidate?: CandidateCommand,
): Promise<QualityFeedback> {
  const response = await apiClient.patch<{ data: QualityFeedback }>(
    `/admin/quality/feedback/${item.type}/${item.id}`,
    { decision, note, candidate },
  )
  return response.data.data
}

export async function listEvaluationCandidates(status?: string): Promise<Page<EvaluationCandidate>> {
  const response = await apiClient.get<{ data: Page<EvaluationCandidate> }>('/admin/quality/candidates', {
    params: { status: status || undefined, page: 0, size: 100 },
  })
  return response.data.data
}

export async function updateEvaluationCandidate(id: string, command: CandidateCommand): Promise<EvaluationCandidate> {
  const response = await apiClient.put<{ data: EvaluationCandidate }>(`/admin/quality/candidates/${id}`, command)
  return response.data.data
}

export async function decideEvaluationCandidate(
  id: string, decision: 'APPROVED' | 'REJECTED', note: string,
): Promise<EvaluationCandidate> {
  const response = await apiClient.patch<{ data: EvaluationCandidate }>(
    `/admin/quality/candidates/${id}/decision`, { decision, note },
  )
  return response.data.data
}

export async function listDatasetVersions(): Promise<DatasetVersion[]> {
  const response = await apiClient.get<{ data: DatasetVersion[] }>('/admin/quality/dataset-versions')
  return response.data.data
}

export async function createDatasetVersion(name: string, candidateIds: string[]): Promise<DatasetVersion> {
  const response = await apiClient.post<{ data: DatasetVersion }>('/admin/quality/dataset-versions', {
    name, candidateIds,
  })
  return response.data.data
}

export async function evaluateDatasetVersion(id: string): Promise<RagEvaluationReport> {
  const response = await apiClient.post<{ data: RagEvaluationReport }>(
    `/admin/quality/dataset-versions/${id}/evaluate`,
    { generationSampleSize: 3, judgeFaithfulness: true },
    { timeout: 120_000 },
  )
  return response.data.data
}

export async function activateDatasetVersion(id: string): Promise<DatasetVersion> {
  const response = await apiClient.post<{ data: DatasetVersion }>(
    `/admin/quality/dataset-versions/${id}/activate`,
  )
  return response.data.data
}
