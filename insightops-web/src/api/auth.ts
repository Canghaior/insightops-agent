import { apiClient } from './client'

export interface Account {
  userId: string
  username: string
  displayName: string
  workspaceId: string
  workspaceName: string
  systemRole: 'USER' | 'SYSTEM_ADMIN'
  role: string
  mustChangePassword: boolean
}

export async function login(username: string, password: string, mfaCode?: string): Promise<Account> {
  const response = await apiClient.post<{ data: Account }>('/auth/login', { username, password, mfaCode })
  return response.data.data
}

export async function me(): Promise<Account> {
  const response = await apiClient.get<{ data: Account }>('/auth/me')
  return response.data.data
}

export async function logout(): Promise<void> {
  await apiClient.post('/auth/logout')
}

export async function changePassword(currentPassword: string, newPassword: string): Promise<void> {
  await apiClient.post('/auth/password', { currentPassword, newPassword })
}
