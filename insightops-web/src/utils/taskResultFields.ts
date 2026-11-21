import type { SavedTaskResult, TaskResultFields } from '@/api/reportTasks'

export function resultDraft(previous?: SavedTaskResult): TaskResultFields {
  const f = previous?.verification.fields
  const r = previous?.result
  return {
    status: f?.status ?? 'NOT_EXECUTED', impact: f?.impact ?? 'INCONCLUSIVE',
    method: f?.method ?? '', environment: f?.environment ?? '', observation: f?.observation ?? '',
    limits: f?.limits ?? '', blocker: f?.blocker ?? '', executedBy: f?.executedBy ?? '',
    executedAt: r?.executedAt ?? null, sourceRevision: r?.sourceRevision ?? '',
    candidateRevision: r?.candidateRevision ?? '', unfinished: r?.unfinished ?? '',
    uploadIds: [...(f?.uploadIds ?? [])], baselineBuildId: f?.baselineBuildId ?? null,
    candidateBuildId: f?.candidateBuildId ?? null, retainPreviousPatch: !!r?.patch,
  }
}

export function localDateTime(instant: string | null) {
  if (!instant) return ''
  const date = new Date(instant)
  return Number.isFinite(date.getTime())
    ? new Date(date.getTime() - date.getTimezoneOffset() * 60_000).toISOString().slice(0, 19) : ''
}
