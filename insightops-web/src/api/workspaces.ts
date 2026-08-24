import { apiClient } from './client'

export interface Workspace {
  id: string
  name: string
  slug: string
  description: string | null
  status: string
  role: 'OWNER' | 'MEMBER'
  createdAt: string
  updatedAt: string
}

export interface WorkspaceMember {
  userId: string
  username: string
  displayName: string
  email: string | null
  emailVerified: boolean
  status: string
  systemRole: string
  role: 'OWNER' | 'MEMBER'
  joinedAt: string
}

export interface WorkspaceInvitation {
  id: string
  workspaceId: string
  workspaceName: string
  email: string
  role: 'OWNER' | 'MEMBER'
  status: string
  inviterName: string
  expiresAt: string
  createdAt: string
  existingUser: boolean
}

export interface InvitationPreview {
  workspaceName: string
  maskedEmail: string
  role: 'OWNER' | 'MEMBER'
  expiresAt: string
  existingUser: boolean
}

export async function listWorkspaces(): Promise<Workspace[]> {
  const response = await apiClient.get<{ data: Workspace[] }>('/workspaces')
  return response.data.data
}

export async function createWorkspace(input: { name: string; slug: string; description: string }): Promise<Workspace> {
  const response = await apiClient.post<{ data: Workspace }>('/workspaces', input)
  return response.data.data
}

export async function updateWorkspace(id: string, input: { name: string; description: string }): Promise<Workspace> {
  const response = await apiClient.patch<{ data: Workspace }>(`/workspaces/${id}`, input)
  return response.data.data
}

export async function switchWorkspace(id: string): Promise<void> {
  await apiClient.post(`/workspaces/${id}/switch`)
}

export async function archiveWorkspace(id: string): Promise<void> {
  await apiClient.post(`/workspaces/${id}/archive`)
}

export async function listMembers(id: string): Promise<WorkspaceMember[]> {
  const response = await apiClient.get<{ data: WorkspaceMember[] }>(`/workspaces/${id}/members`)
  return response.data.data
}

export async function listInvitations(id: string): Promise<WorkspaceInvitation[]> {
  const response = await apiClient.get<{ data: WorkspaceInvitation[] }>(`/workspaces/${id}/invitations`)
  return response.data.data
}

export async function inviteMember(id: string, email: string, role: string): Promise<{ invitation: WorkspaceInvitation; deliveryQueued: boolean; manualInvitationLink: string | null }> {
  const response = await apiClient.post<{ data: { invitation: WorkspaceInvitation; deliveryQueued: boolean; manualInvitationLink: string | null } }>(`/workspaces/${id}/invitations`, { email, role })
  return response.data.data
}

export async function revokeInvitation(workspaceId: string, invitationId: string): Promise<void> {
  await apiClient.delete(`/workspaces/${workspaceId}/invitations/${invitationId}`)
}

export async function invitationPreview(token: string): Promise<InvitationPreview> {
  const response = await apiClient.post<{ data: InvitationPreview }>('/public/invitations/preview', { token })
  return response.data.data
}

export async function acceptNewInvitation(input: { token: string; username: string; displayName: string; password: string }): Promise<string> {
  const response = await apiClient.post<{ data: { userId: string } }>('/public/invitations/accept', input)
  return response.data.data.userId
}

export async function acceptExistingInvitation(token: string): Promise<string> {
  const response = await apiClient.post<{ data: { workspaceId: string } }>('/workspaces/invitations/accept', { token })
  return response.data.data.workspaceId
}

export async function updateMemberRole(workspaceId: string, userId: string, role: string): Promise<void> {
  await apiClient.patch(`/workspaces/${workspaceId}/members/${userId}`, { role })
}

export async function transferOwnership(workspaceId: string, userId: string): Promise<void> {
  await apiClient.post(`/workspaces/${workspaceId}/ownership/${userId}`)
}

export async function removeMember(workspaceId: string, userId: string): Promise<void> {
  await apiClient.delete(`/workspaces/${workspaceId}/members/${userId}`)
}

export async function leaveWorkspace(workspaceId: string): Promise<void> {
  await apiClient.delete(`/workspaces/${workspaceId}/membership`)
}
