import type { WorkflowRunDetail } from '@/api/workflowRuns'
import { previousAssessmentInput } from './upgradeProfiles'

export type AssessmentType = 'UPGRADE_ASSESSMENT' | 'SYSTEM_DECISION'

export function assessmentTypeOf(run: WorkflowRunDetail | null): AssessmentType | undefined {
  if (!run) return
  const inputs = run.inputs as Record<string, unknown> | null
  if (run.businessSystemId && inputs?.assessmentType === 'SYSTEM_DECISION') return 'SYSTEM_DECISION'
  if (run.businessSystemId || run.templateName === '升级评估（版本范围）') return 'UPGRADE_ASSESSMENT'
}

export function decisionFormOf(run: WorkflowRunDetail) {
  const input = previousAssessmentInput(run)
  const context = input.context as Record<string, unknown> | null
  return {
    question: typeof input.decisionQuestion === 'string' ? input.decisionQuestion : '',
    observations: typeof context?.observations === 'string' ? context.observations : '',
    environment: typeof context?.environment === 'string' ? context.environment : '',
    projectId: typeof input.projectId === 'string' ? input.projectId : '',
    uploadIds: Array.isArray(input.uploadIds) ? input.uploadIds.filter((id): id is string => typeof id === 'string') : [],
  }
}
