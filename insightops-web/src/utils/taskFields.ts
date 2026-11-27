import type { TaskFields } from '@/api/reportTasks'

export const taskSections = [
  { title: '交付与范围', fields: [
    ['deliverable', '预期交付'], ['inScope', '本次修改或检查范围'], ['outOfScope', '明确排除的范围'],
  ] },
  { title: '判断依据', fields: [
    ['facts', '已知事实与出处'], ['inferences', '待验证的推断与依据'], ['unknowns', '未知项'],
  ] },
  { title: '执行与停止', fields: [
    ['method', '拟执行方法'], ['environment', '所需执行环境'],
    ['completion', '预期观察与完成条件'], ['stopConditions', '失败、受阻或停止条件'],
  ] },
] as const

export const readyFields = ['deliverable', 'inScope', 'outOfScope', 'method', 'environment', 'completion', 'stopConditions'] as const
export const fieldLabels: Record<keyof TaskFields, string> = {
  question: '要解决的问题', origin: '原建议来源', originDetail: '建议人或工具', excerpt: '原报告摘录', readiness: '交接状态',
  deliverable: '预期交付', inScope: '本次修改或检查范围', outOfScope: '明确排除的范围', facts: '已知事实与出处',
  inferences: '待验证的推断与依据', unknowns: '未知项', method: '拟执行方法', environment: '所需执行环境',
  completion: '预期观察与完成条件', stopConditions: '失败、受阻或停止条件', missingMaterials: '仍缺少的材料',
  verificationVersionIds: '依据的验证版本', uploadIds: '补充资料',
}
export function taskDraft(original?: TaskFields): TaskFields {
  if (original) return { ...original, uploadIds: [...original.uploadIds], verificationVersionIds: [...original.verificationVersionIds] }
  return { origin: 'MODEL', originDetail: '', excerpt: '', readiness: 'NEEDS_MATERIALS', question: '', deliverable: '',
    inScope: '', outOfScope: '', facts: '', inferences: '', unknowns: '', method: '', completion: '',
    stopConditions: '', environment: '', missingMaterials: '', verificationVersionIds: [], uploadIds: [] }
}
