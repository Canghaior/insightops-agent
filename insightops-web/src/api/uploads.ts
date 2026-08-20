import { apiClient } from './client'

export type UploadVisibility = 'PRIVATE' | 'WORKSPACE'
export type UploadStatus = 'PENDING' | 'PROCESSING' | 'SUCCEEDED' | 'FAILED' | 'DELETING'

export interface KnowledgeUpload {
  uploadId: string
  sourceId: string
  projectId: string
  projectName: string
  uploadedBy: string
  uploaderName: string
  originalName: string
  mediaType: string
  byteSize: number
  sha256: string
  visibility: UploadVisibility
  status: UploadStatus
  pageCount: number
  errorMessage: string | null
  currentItem: string | null
  heartbeatAt: string | null
  leaseExpiresAt: string | null
  createdAt: string
  updatedAt: string
}

export async function listKnowledgeUploads(): Promise<KnowledgeUpload[]> {
  const response = await apiClient.get<{ data: KnowledgeUpload[] }>('/knowledge/uploads')
  return response.data.data
}

export async function uploadKnowledgeFile(
  projectId: string,
  visibility: UploadVisibility,
  file: File,
): Promise<KnowledgeUpload> {
  const form = new FormData()
  form.append('projectId', projectId)
  form.append('visibility', visibility)
  form.append('file', file)
  const response = await apiClient.post<{ data: KnowledgeUpload }>('/knowledge/uploads', form, {
    timeout: 120_000,
  })
  return response.data.data
}

export async function retryKnowledgeUpload(uploadId: string): Promise<void> {
  await apiClient.post(`/knowledge/uploads/${uploadId}/retry`)
}

export async function deleteKnowledgeUpload(uploadId: string): Promise<void> {
  await apiClient.delete(`/knowledge/uploads/${uploadId}`)
}

export function knowledgeUploadDownloadUrl(uploadId: string): string {
  return `/api/v1/knowledge/uploads/${uploadId}/content`
}
