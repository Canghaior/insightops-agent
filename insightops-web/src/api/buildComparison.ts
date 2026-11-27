import { apiClient } from './client'
import { reviewPath, type BuildModule, type CaseOccurrence } from './buildEvidence'

export type TestChange = 'UNCHANGED' | 'CHANGED' | 'AMBIGUOUS' | 'BASELINE_ONLY' | 'CANDIDATE_ONLY'
export const testChanges: Record<TestChange, string> = {
  UNCHANGED: '状态相同', CHANGED: '状态变化', AMBIGUOUS: '重复身份', BASELINE_ONLY: '仅见于基线', CANDIDATE_ONLY: '仅见于候选',
}
export interface ComparisonCounts { passed: number; failure: number; error: number; skipped: number; total: number }
export interface CaseGroup {
  modulePath?: string | null
  suite: string
  className: string
  name: string
  change: TestChange
  baseline: CaseOccurrence[]
  candidate: CaseOccurrence[]
  baselineCounts: ComparisonCounts
  candidateCounts: ComparisonCounts
}
export interface BuildComparison {
  baselineId: string
  candidateId: string
  scope: 'SAME_OBSERVED' | 'DIFFERENT_OBSERVED'
  baselineCounts: ComparisonCounts
  candidateCounts: ComparisonCounts
  groups: CaseGroup[]
  modules?: { path: string; baseline: BuildModule | null; candidate: BuildModule | null }[]
  objectiveSummary: string
  limitations: string[]
}
export async function compareBuilds(reportId: string, baselineId: string, candidateId: string): Promise<BuildComparison> {
  return (await apiClient.get(reviewPath(reportId) + '/comparison', { params: { baselineId, candidateId } })).data.data
}
