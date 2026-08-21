import { describe, expect, it } from 'vitest'

import { parseSseEnvelope } from './agentStream'

describe('P2.0-D approval events', () => {
  it('accepts an approval-required event without write arguments', () => {
    const event = parseSseEnvelope(JSON.stringify({
      type: 'tool_approval_required', runId: 'run-p2d', sequence: 3,
      toolCallId: 'call-1', toolName: 'user_memory_upsert',
      content: '写入长期记忆，等待人工审批',
    }))
    expect(event.type).toBe('tool_approval_required')
    expect(event.content).toContain('等待人工审批')
    expect(JSON.stringify(event)).not.toContain('requestPayload')
  })
})
