export interface MutableText {
  value: string
}

export function beginKnowledgeStatusLoad(
  error: MutableText,
  refreshError: MutableText,
  silent: boolean,
): void {
  if (silent) return
  error.value = ''
  refreshError.value = ''
}

export function completeKnowledgeStatusLoad(refreshError: MutableText): void {
  refreshError.value = ''
}

export function failKnowledgeStatusLoad(
  error: MutableText,
  refreshError: MutableText,
  silent: boolean,
  detail: string,
): void {
  if (silent) refreshError.value = `状态自动刷新失败：${detail}`
  else error.value = detail
}
