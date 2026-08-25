import { apiClient } from './client'

export interface ExportCreated { exportId: string; downloadToken: string; expiresAt: string }

export async function createPersonalDataExport(): Promise<ExportCreated> {
  const response = await apiClient.post<ExportCreated>('/identity/exports')
  return response.data
}

export async function downloadPersonalDataExport(value: ExportCreated): Promise<ArrayBuffer> {
  const response = await apiClient.post<ArrayBuffer>(`/identity/exports/${value.exportId}/download`,
    { token: value.downloadToken }, { responseType: 'arraybuffer' })
  return response.data
}

export async function requestPublicAccountDeletion(password: string, mfaCode: string): Promise<string> {
  const response = await apiClient.post<{ scheduledAt: string }>('/identity/public-account-deletion',
    { password, mfaCode })
  return response.data.scheduledAt
}
