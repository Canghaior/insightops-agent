import { apiClient } from './client'

export type MemoryCategory = 'PROFILE' | 'PREFERENCE' | 'INTEREST' | 'CONSTRAINT'
export interface UserMemory {
  id: string; key: string; value: string; category: MemoryCategory; enabled: boolean
  createdAt: string; updatedAt: string
}

export async function listMemories(): Promise<UserMemory[]> {
  const response = await apiClient.get<{ data: UserMemory[] }>('/memories')
  return response.data.data
}
export async function createMemory(
  key: string, value: string, category: MemoryCategory,
): Promise<UserMemory> {
  const response = await apiClient.post<{ data: UserMemory }>('/memories', { key, value, category })
  return response.data.data
}
export async function updateMemory(memory: UserMemory): Promise<UserMemory> {
  const response = await apiClient.put<{ data: UserMemory }>(`/memories/${memory.id}`, {
    value: memory.value, category: memory.category, enabled: memory.enabled,
  })
  return response.data.data
}
export async function deleteMemory(id: string): Promise<void> {
  await apiClient.delete(`/memories/${id}`)
}
