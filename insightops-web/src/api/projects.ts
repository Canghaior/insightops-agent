import { apiClient } from './client'

export interface ProjectWatch {
  id: string; owner: string; name: string; url: string; priority: number
  enabled: boolean; updatedAt: string
}
export async function listProjects(): Promise<ProjectWatch[]> {
  const response = await apiClient.get<{ data: ProjectWatch[] }>('/projects')
  return response.data.data
}
export async function setProjectWatch(id: string, enabled: boolean): Promise<ProjectWatch> {
  const response = await apiClient.patch<{ data: ProjectWatch }>(`/projects/${id}/watch`, { enabled })
  return response.data.data
}
