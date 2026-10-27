import { apiClient } from './client'
import type { ProjectWatch } from './projects'
import type { KnowledgeUpload } from './uploads'
import type { WorkflowPreset } from './workflowProducts'

export interface ResearchProfile {
  id: string; label: string; description: string
  fields: Array<{ key: string; label: string; placeholder: string; maxLength: number }>
  concerns: string[]
}
export interface SourceCoverage {
  projectId: string; name: string; sourceType: string; url: string; status: string; documentCount: number; lastSyncAt: string | null
  enabled: boolean; consecutiveFailures: number
}

export interface UpgradeSetup {
  templateId: string; versionId: string; projects: ProjectWatch[]; uploads: KnowledgeUpload[]; issueAvailable: boolean
  profiles: ResearchProfile[]; sources: SourceCoverage[]
}
export interface UpgradeInput {
  projectId: string; currentVersion: string; targetVersion: string; businessProject: string
  javaVersion: string; springBootVersion: string; concerns: string[]; uploadIds: string[]; issueUrls: string[]
  profileId: string; context: Record<string, string>
}
export async function listUpgradePresets(): Promise<WorkflowPreset[]> {
  return (await apiClient.get('/upgrade-assessments/presets')).data.data
}
export async function saveUpgradePreset(name: string, input: UpgradeInput): Promise<WorkflowPreset> {
  return (await apiClient.post('/upgrade-assessments/presets', { name, input })).data.data
}
export async function setupUpgrade(): Promise<UpgradeSetup> {
  return (await apiClient.post<{ data: UpgradeSetup }>('/upgrade-assessments/setup')).data.data
}
export async function upgradeVersions(projectId: string): Promise<{ releases: Array<{ tagName: string; prerelease: boolean }>; fetchedAt: string; truncated: boolean }> {
  return (await apiClient.get('/upgrade-assessments/versions', { params: { projectId }, timeout: 45_000 })).data.data
}
export async function launchUpgrade(input: UpgradeInput, requestId: string): Promise<{ runId: string }> {
  return (await apiClient.post('/upgrade-assessments/runs', { ...input, requestId })).data.data
}
