import { apiClient } from './client'

export interface SecuritySummary {
  email: string | null
  emailVerified: boolean
  mfaEnabled: boolean
  unusedRecoveryCodes: number
  deletionScheduledAt: string | null
  mailDeliveryEnabled: boolean
}

export interface IdentitySession {
  id: string
  createdAt: string
  lastSeenAt: string
  expiresAt: string
  userAgent: string | null
  addressFingerprint: string | null
  workspaceId: string | null
  workspaceName: string | null
  current: boolean
}

export interface EmailChangeResult {
  deliveryQueued: boolean
  manualVerificationLink: string | null
  expiresAt: string
}

export async function getSecurity(): Promise<SecuritySummary> {
  const response = await apiClient.get<{ data: SecuritySummary }>('/identity/security')
  return response.data.data
}

export async function requestEmail(password: string, email: string): Promise<EmailChangeResult> {
  const response = await apiClient.post<{ data: EmailChangeResult }>('/identity/email', { password, email })
  return response.data.data
}

export async function listSessions(): Promise<IdentitySession[]> {
  const response = await apiClient.get<{ data: IdentitySession[] }>('/identity/sessions')
  return response.data.data
}

export async function revokeSession(id: string): Promise<void> {
  await apiClient.delete(`/identity/sessions/${id}`)
}

export async function revokeOtherSessions(): Promise<number> {
  const response = await apiClient.post<{ data: { count: number } }>('/identity/sessions/revoke-others')
  return response.data.data.count
}

export async function beginMfa(password: string): Promise<{ secret: string; otpauthUri: string }> {
  const response = await apiClient.post<{ data: { secret: string; otpauthUri: string } }>('/identity/mfa/setup', { password })
  return response.data.data
}

export async function confirmMfa(code: string): Promise<string[]> {
  const response = await apiClient.post<{ data: { recoveryCodes: string[] } }>('/identity/mfa/confirm', { code })
  return response.data.data.recoveryCodes
}

export async function disableMfa(password: string, code: string): Promise<void> {
  await apiClient.post('/identity/mfa/disable', { password, code })
}

export async function requestDeletion(password: string, mfaCode: string): Promise<string> {
  const response = await apiClient.post<{ data: { scheduledAt: string } }>('/identity/deletion', { password, mfaCode })
  return response.data.data.scheduledAt
}

export async function cancelDeletion(password: string): Promise<void> {
  await apiClient.delete('/identity/deletion', { data: { password } })
}

export async function verifyEmail(token: string): Promise<void> {
  await apiClient.post('/public/identity/email/verify', { token })
}

export async function forgotPassword(email: string): Promise<void> {
  await apiClient.post('/public/identity/password/forgot', { email })
}

export async function resetPassword(token: string, newPassword: string): Promise<void> {
  await apiClient.post('/public/identity/password/reset', { token, newPassword })
}
