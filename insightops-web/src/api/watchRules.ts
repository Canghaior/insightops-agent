import { apiClient } from './client'

export const EVENT_TYPES = [
  'GITHUB_RELEASE', 'GITHUB_ISSUE', 'GITHUB_PULL_REQUEST', 'GITHUB_SECURITY_ADVISORY',
] as const

export type EventType = typeof EVENT_TYPES[number]

export interface WatchRule {
  id: string
  projectId: string | null
  projectName: string | null
  name: string
  keywords: string[]
  excludedKeywords: string[]
  eventTypes: EventType[]
  minimumImportance: number
  immediateNotification: boolean
  includeInDigest: boolean
  enabled: boolean
  matchCount: number
  createdAt: string
  updatedAt: string
}

export interface WatchRuleCommand {
  name: string
  projectId?: string | null
  keywords: string[]
  excludedKeywords: string[]
  eventTypes: EventType[]
  minimumImportance: number
  immediateNotification: boolean
  includeInDigest: boolean
  enabled: boolean
}

export async function listWatchRules(): Promise<WatchRule[]> {
  const response = await apiClient.get<{ data: WatchRule[] }>('/watch-rules')
  return response.data.data
}

export async function createWatchRule(command: WatchRuleCommand): Promise<WatchRule> {
  const response = await apiClient.post<{ data: WatchRule }>('/watch-rules', command)
  return response.data.data
}

export async function updateWatchRule(id: string, command: WatchRuleCommand): Promise<WatchRule> {
  const response = await apiClient.put<{ data: WatchRule }>(`/watch-rules/${id}`, command)
  return response.data.data
}

export async function deleteWatchRule(id: string): Promise<void> {
  await apiClient.delete(`/watch-rules/${id}`)
}
