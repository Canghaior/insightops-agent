import { apiClient } from './client'

export type SystemRole = 'USER' | 'SYSTEM_ADMIN'
export type WorkspaceRole = 'OWNER' | 'MEMBER'
export type AccountStatus = 'ACTIVE' | 'DISABLED'

export interface ManagedUser {
  userId: string
  username: string
  displayName: string
  status: AccountStatus
  systemRole: SystemRole
  workspaceRole: WorkspaceRole
  mustChangePassword: boolean
  createdAt: string
  updatedAt: string
}

export interface AccountAudit {
  id: string
  actorUserId: string | null
  actorUsername: string | null
  targetUserId: string | null
  targetUsername: string | null
  action: string
  detailsJson: string
  createdAt: string
}

export interface CreateUserInput {
  username: string
  displayName: string
  temporaryPassword: string
  systemRole: SystemRole
  workspaceRole: WorkspaceRole
}

export interface ManagedProject {
  projectId: string
  platform: string
  repositoryOwner: string
  repositoryName: string
  canonicalUrl: string
  priority: number
  enabled: boolean
  lastSyncStatus: 'NEVER' | 'RUNNING' | 'SUCCEEDED' | 'RETRY_WAIT' | 'FAILED'
  lastSyncAt: string | null
  nextSyncAt: string | null
  consecutiveFailures: number
  lastSyncError: string | null
  releaseCount: number
  knowledgeSourceCount: number
  watcherCount: number
  jobCount: number
  createdAt: string
  updatedAt: string
}

export interface ProjectInput {
  repositoryOwner: string
  repositoryName: string
  priority: number
}

export interface CollectionStatus {
  projectId: string
  projectName: string
  repositoryOwner: string
  status: 'NEVER' | 'RUNNING' | 'SUCCEEDED' | 'RETRY_WAIT' | 'FAILED'
  lastSyncAt: string | null
  nextSyncAt: string | null
  consecutiveFailures: number
  lastError: string | null
}

export interface AnalysisAdminStatus {
  analysisId: string; eventId: string; projectName: string; versionTag: string
  status: string; riskLevel: string | null; attempts: number; maxAttempts: number
  automatic: boolean; nextAttemptAt: string; completedAt: string | null; lastError: string | null
}
export interface IntelligenceAdminOverview {
  metrics: { todayCalls: number; todayCostCny: number; queued: number; failed: number }
  items: AnalysisAdminStatus[]
}

export interface KnowledgeCollectionJob {
  jobId: string
  status: 'RUNNING' | 'SUCCEEDED' | 'FAILED'
  pageCount: number
  newDocumentCount: number
  changedDocumentCount: number
  unchangedDocumentCount: number
  chunkCount: number
  maxPageCount: number
  discoveredUrlCount: number
  visitedUrlCount: number
  currentUrl: string | null
  heartbeatAt: string | null
  leaseExpiresAt: string | null
  errorCode: string | null
  errorMessage: string | null
  startedAt: string
  finishedAt: string | null
}

export interface KnowledgeSourceStatus {
  sourceId: string
  projectId: string
  projectName: string
  sourceKey: string
  name: string
  sourceType: string
  rootUrl: string
  trustTier: string
  enabled: boolean
  status: 'NEVER' | 'RUNNING' | 'SUCCEEDED' | 'RETRY_WAIT' | 'FAILED'
  lastSyncAt: string | null
  nextSyncAt: string
  consecutiveFailures: number
  lastError: string | null
  documentCount: number
  revisionCount: number
  chunkCount: number
  lockedUntil: string | null
  lastJob: KnowledgeCollectionJob | null
}

export interface KnowledgeEmbeddingSourceProgress {
  sourceId: string
  sourceName: string
  projectName: string
  total: number
  succeeded: number
  pending: number
  running: number
  retryWait: number
  failed: number
}

export interface KnowledgeEmbeddingOverview {
  provider: string
  model: string
  dimensions: number
  total: number
  succeeded: number
  pending: number
  running: number
  retryWait: number
  failed: number
  lastUpdatedAt: string | null
  sources: KnowledgeEmbeddingSourceProgress[]
}

export interface KnowledgeSearchResult {
  chunkId: string
  projectId: string
  projectName: string
  sourceName: string
  title: string
  canonicalUrl: string
  headingPath: string | null
  content: string
  language: string
  trustTier: string
  score: number
}

export interface KnowledgeSearchResponse {
  query: string
  provider: string
  model: string
  mode: string
  vectorAvailable: boolean
  durationMs: number
  results: KnowledgeSearchResult[]
}

export interface RagEvaluationCase {
  caseKey: string
  question: string
  expectedAnswerable: boolean
  expectedProject: string | null
  predictedAnswerable: boolean
  answerabilityCorrect: boolean
  projectHit: boolean
  reciprocalRank: number
  termCoverage: number
  retrievalMode: string
  topProjects: string[]
  sourceUrls: string[]
  citationPrecision: number | null
  citationCoverage: number | null
  faithfulness: number | null
  judgeReason: string | null
  generatedAnswer: string | null
}

export interface RagEvaluationSummary {
  recallAtK: number
  meanReciprocalRank: number
  projectHitRate: number
  termCoverage: number
  noAnswerAccuracy: number
  citationPrecision: number | null
  citationCoverage: number | null
  faithfulness: number | null
  passed: boolean
  modelName: string | null
}

export interface RagEvaluationReport {
  id: string
  datasetName: string
  status: 'RUNNING' | 'PASSED' | 'FAILED' | 'ERROR'
  caseCount: number
  generationSampleSize: number
  summary: RagEvaluationSummary | null
  errorMessage: string | null
  startedAt: string
  finishedAt: string | null
  cases: RagEvaluationCase[]
}

export async function listUsers(): Promise<ManagedUser[]> {
  const response = await apiClient.get<{ data: ManagedUser[] }>('/admin/users')
  return response.data.data
}

export async function listManagedProjects(): Promise<ManagedProject[]> {
  const response = await apiClient.get<{ data: ManagedProject[] }>('/admin/projects')
  return response.data.data
}

export async function createManagedProject(input: ProjectInput): Promise<ManagedProject> {
  const response = await apiClient.post<{ data: ManagedProject }>('/admin/projects', input)
  return response.data.data
}

export async function updateManagedProject(projectId: string, input: ProjectInput): Promise<ManagedProject> {
  const response = await apiClient.put<{ data: ManagedProject }>(`/admin/projects/${projectId}`, input)
  return response.data.data
}

export async function setManagedProjectEnabled(projectId: string, enabled: boolean): Promise<ManagedProject> {
  const response = await apiClient.patch<{ data: ManagedProject }>(`/admin/projects/${projectId}/status`, { enabled })
  return response.data.data
}

export async function deleteManagedProject(projectId: string): Promise<void> {
  await apiClient.delete(`/admin/projects/${projectId}`)
}

export async function createUser(input: CreateUserInput): Promise<ManagedUser> {
  const response = await apiClient.post<{ data: ManagedUser }>('/admin/users', input)
  return response.data.data
}

export async function updateStatus(userId: string, status: AccountStatus): Promise<ManagedUser> {
  const response = await apiClient.patch<{ data: ManagedUser }>(`/admin/users/${userId}/status`, { status })
  return response.data.data
}

export async function updateRole(userId: string, workspaceRole: WorkspaceRole): Promise<ManagedUser> {
  const response = await apiClient.patch<{ data: ManagedUser }>(`/admin/users/${userId}/role`, { workspaceRole })
  return response.data.data
}

export async function resetPassword(userId: string, temporaryPassword: string): Promise<void> {
  await apiClient.post(`/admin/users/${userId}/reset-password`, { temporaryPassword })
}

export async function listAudit(limit = 100): Promise<AccountAudit[]> {
  const response = await apiClient.get<{ data: AccountAudit[] }>('/admin/audit', { params: { limit } })
  return response.data.data
}

export async function listCollectionStatus(): Promise<CollectionStatus[]> {
  const response = await apiClient.get<{ data: CollectionStatus[] }>('/admin/collection')
  return response.data.data
}

export async function requestCollectionSync(projectId: string): Promise<void> {
  await apiClient.post(`/admin/collection/${projectId}/sync`)
}

export async function getIntelligenceAdminOverview(): Promise<IntelligenceAdminOverview> {
  const response = await apiClient.get<{ data: IntelligenceAdminOverview }>('/admin/intelligence')
  return response.data.data
}

export async function requestIntelligenceAnalysis(eventId: string): Promise<void> {
  await apiClient.post(`/admin/intelligence/events/${eventId}/analyze`)
}

export async function listKnowledgeSources(): Promise<KnowledgeSourceStatus[]> {
  const response = await apiClient.get<{ data: KnowledgeSourceStatus[] }>('/admin/knowledge/sources')
  return response.data.data
}

export async function requestKnowledgeSync(sourceId: string): Promise<void> {
  await apiClient.post(`/admin/knowledge/sources/${sourceId}/sync`)
}

export async function getKnowledgeEmbeddingOverview(): Promise<KnowledgeEmbeddingOverview> {
  const response = await apiClient.get<{ data: KnowledgeEmbeddingOverview }>('/admin/knowledge/embeddings')
  return response.data.data
}

export async function retryKnowledgeEmbeddings(): Promise<number> {
  const response = await apiClient.post<{ data: { resetCount: number } }>('/admin/knowledge/embeddings/retry')
  return response.data.data.resetCount
}

export async function searchKnowledge(query: string, limit = 8): Promise<KnowledgeSearchResponse> {
  const response = await apiClient.post<{ data: KnowledgeSearchResponse }>('/knowledge/search', { query, limit })
  return response.data.data
}

export async function getLatestRagEvaluation(): Promise<RagEvaluationReport | null> {
  const response = await apiClient.get<{ data: RagEvaluationReport | null }>('/admin/knowledge/evaluations/latest')
  return response.data.data
}

export async function runRagEvaluation(
  generationSampleSize = 3,
  judgeFaithfulness = true,
): Promise<RagEvaluationReport> {
  const response = await apiClient.post<{ data: RagEvaluationReport }>(
    '/admin/knowledge/evaluations',
    { generationSampleSize, judgeFaithfulness },
    { timeout: 120_000 },
  )
  return response.data.data
}
