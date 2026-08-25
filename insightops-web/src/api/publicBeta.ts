import { apiClient } from './client'

export interface PublicBetaStatus {
  registrationEnabled: boolean
  reason: string | null
  turnstileSiteKey: string
  minimumAge: number
  maximumRegistrations: number
  activeRegistrations: number
  pendingRegistrations: number
  occupiedSlots: number
  runsEnabled: boolean
  statusMessage: string | null
  operatorName: string
  contactEmail: string
  termsVersion: string
  privacyVersion: string
  acceptableUseVersion: string
}

export interface RegistrationRequest {
  username: string
  displayName: string
  email: string
  password: string
  turnstileToken: string
  ageConfirmed: boolean
  termsAccepted: boolean
  privacyAccepted: boolean
  acceptableUseAccepted: boolean
}

export interface RegistrationResult {
  registrationSlot: number
  verificationExpiresAt: string
  nextStep: string
}

export interface PublicBetaAdminStatus {
  publicStatus: PublicBetaStatus
  control: {
    registrationEnabled: boolean
    runsEnabled: boolean
    statusMessage: string | null
    updatedAt: string
  }
}

export async function getPublicBetaStatus(): Promise<PublicBetaStatus> {
  const response = await apiClient.get<PublicBetaStatus>('/public/identity/registration/status')
  return response.data
}

export async function registerPublicBeta(body: RegistrationRequest): Promise<RegistrationResult> {
  const response = await apiClient.post<RegistrationResult>('/public/identity/registration', body)
  return response.data
}

export async function getPublicBetaAdminStatus(): Promise<PublicBetaAdminStatus> {
  const response = await apiClient.get<PublicBetaAdminStatus>('/admin/public-beta')
  return response.data
}

export async function updatePublicBetaControl(body: {
  registrationEnabled: boolean
  runsEnabled: boolean
  statusMessage: string
}): Promise<PublicBetaAdminStatus> {
  const response = await apiClient.patch<PublicBetaAdminStatus>('/admin/public-beta', body)
  return response.data
}
