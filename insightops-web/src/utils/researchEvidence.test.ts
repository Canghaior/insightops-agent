import { describe, expect, it } from 'vitest'
import { researchWarnings } from './researchEvidence'
import type { RunToolCall } from '@/api/runs'
describe('research evidence warnings', () => {
  it('keeps missing evidence visible even after answer generation succeeds', () => {
    const tools = [
      { toolName: 'github_release_list', status: 'SUCCEEDED', resultPayload: { warnings: ['范围起点未找到'], truncated: true } },
      { toolName: 'knowledge_hybrid_search', status: 'SUCCEEDED', resultPayload: { answerable: false, vectorAvailable: false } },
      { toolName: 'github_issue_read', status: 'FAILED' },
    ] as RunToolCall[]
    expect(researchWarnings(tools)).toEqual(expect.arrayContaining(['范围起点未找到', 'github_issue_read 获取失败，相关结论尚未核实。']))
    expect(researchWarnings(tools).join(' ')).toContain('关键词检索')
    expect(researchWarnings(tools).join(' ')).toContain('没有找到足够证据')
  })
})
