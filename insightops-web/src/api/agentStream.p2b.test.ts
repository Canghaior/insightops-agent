import { describe, expect, it } from 'vitest'

import { parseSseEnvelope } from './agentStream'

describe('P2.0-B tool loop events', () => {
  it('accepts a normalized tool failure so the UI can show a failed round', () => {
    const event = parseSseEnvelope(JSON.stringify({
      type: 'tool_failed',
      runId: 'run-p2b',
      sequence: 4,
      toolCallId: 'tool-call-1',
      toolName: 'knowledge_hybrid_search',
      round: 2,
      errorCode: 'EMBEDDING_UNAVAILABLE',
    }))

    expect(event).toMatchObject({
      type: 'tool_failed',
      toolName: 'knowledge_hybrid_search',
      round: 2,
      errorCode: 'EMBEDDING_UNAVAILABLE',
    })
    expect(JSON.stringify(event)).not.toContain('Authorization')
  })

  it('accepts a retry notification without exposing tool arguments', () => {
    const event = parseSseEnvelope(JSON.stringify({
      type: 'tool_retrying',
      runId: 'run-p2c',
      sequence: 5,
      toolCallId: 'tool-call-2',
      toolName: 'github_releases',
      round: 1,
      content: '第 2 次尝试将在 250 ms 后开始',
      errorCode: 'TOOL_TRANSIENT_REMOTE',
    }))

    expect(event).toMatchObject({
      type: 'tool_retrying',
      toolName: 'github_releases',
      content: '第 2 次尝试将在 250 ms 后开始',
      errorCode: 'TOOL_TRANSIENT_REMOTE',
    })
    expect(JSON.stringify(event)).not.toContain('arguments')
  })
})
