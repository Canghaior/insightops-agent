import type { RunToolCall } from '@/api/runs'
import type { WorkflowRunNode } from '@/api/workflowRuns'
export function researchWarnings(tools: RunToolCall[], nodes: WorkflowRunNode[] = []): string[] {
  const warnings: string[] = []
  for (const tool of tools) {
    if (tool.status === 'FAILED') warnings.push(`${tool.toolName} 获取失败，相关结论尚未核实。`)
    const result = tool.resultPayload as Record<string, unknown> | null
    if (!result || typeof result !== 'object') continue
    if (Array.isArray(result.warnings)) warnings.push(...result.warnings.filter((item): item is string => typeof item === 'string'))
    if (result.truncated === true && !Array.isArray(result.warnings)) warnings.push('Release 查询范围可能不完整。')
    if (result.answerable === false) warnings.push('部分知识检索没有找到足够证据，不能据此保证兼容。')
    if (result.vectorAvailable === false) warnings.push('向量检索不可用，本次使用关键词检索，可能遗漏相关资料。')
    if (tool.toolName === 'github_issue_read') warnings.push('已知 Issue 仅读取正文与状态，未读取评论或关联 PR，不能据此确认修复情况。')
    if (result.issue && typeof result.issue === 'object' && 'bodyTruncated' in result.issue && result.issue.bodyTruncated === true)
      warnings.push('Issue 正文已截断，完整上下文需返回原文核查。')
    if (Array.isArray(result.sources) && result.sources.some(item => item && typeof item === 'object' && item.version === '未标注'))
      warnings.push('部分文档未标注版本，尚未确认是否适用于目标版本。')
  }
  for (const node of nodes) {
    if (['FAILED', 'SKIPPED', 'BLOCKED', 'CANCELLED'].includes(node.status))
      warnings.push(`研究步骤 ${node.logicalNodeId} 未成功（${node.status}），相关结论缺少该步骤证据。`)
  }
  return [...new Set(warnings)]
}
