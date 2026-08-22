import { afterEach, describe, expect, it, vi } from 'vitest'

import { CHAT_RUN_ID_HEADER, createSseParser, parseSseEnvelope, streamChat } from './agentStream'

const started = '{"type":"started","runId":"run-1","sessionId":"session-1","sequence":1}'
const delta = '{"type":"delta","runId":"run-1","sequence":2,"content":"Spring AI"}'

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('parseSseEnvelope', () => {
  it('parses a valid chat event', () => {
    expect(parseSseEnvelope(started)).toMatchObject({
      type: 'started',
      runId: 'run-1',
      sessionId: 'session-1',
      sequence: 1,
    })
  })

  it('rejects an event without identity fields', () => {
    expect(() => parseSseEnvelope('{"type":"delta"}')).toThrow('缺少有效的')
  })
})

describe('createSseParser', () => {
  it('parses events split across arbitrary network chunks', () => {
    const events: ReturnType<typeof parseSseEnvelope>[] = []
    const parser = createSseParser((event) => events.push(event))

    parser.push(`event: started\r\ndata: ${started.slice(0, 25)}`)
    parser.push(`${started.slice(25)}\r\n\r\nevent: delta\ndata: ${delta}\n`)
    parser.push('\n')
    parser.finish()

    expect(events).toHaveLength(2)
    expect(events[1]).toMatchObject({ type: 'delta', content: 'Spring AI' })
  })

  it('preserves the complete tool-augmented event sequence and sources', () => {
    const payloads = [
      { type: 'started', runId: 'run-2', sessionId: 'session-2', sequence: 1 },
      { type: 'tool_started', runId: 'run-2', sequence: 2, toolName: 'github_release_list' },
      { type: 'tool_completed', runId: 'run-2', sequence: 3, toolName: 'github_release_list', releaseCount: 1 },
      { type: 'delta', runId: 'run-2', sequence: 4, content: 'v2.0.0' },
      {
        type: 'completed', runId: 'run-2', sequence: 5,
        sources: ['https://github.com/spring-projects/spring-ai/releases/tag/v2.0.0'],
      },
    ]
    const events: ReturnType<typeof parseSseEnvelope>[] = []
    const parser = createSseParser((event) => events.push(event))
    const wire = payloads.map((payload) => `data: ${JSON.stringify(payload)}\n\n`).join('')

    parser.push(wire.slice(0, 77))
    parser.push(wire.slice(77, 193))
    parser.push(wire.slice(193))
    parser.finish()

    expect(events.map((event) => event.type)).toEqual([
      'started', 'tool_started', 'tool_completed', 'delta', 'completed',
    ])
    expect(events.map((event) => event.sequence)).toEqual([1, 2, 3, 4, 5])
    expect(events.at(-1)?.sources).toEqual([
      'https://github.com/spring-projects/spring-ai/releases/tag/v2.0.0',
    ])
  })

  it('exposes only a normalized error code', () => {
    const event = parseSseEnvelope(JSON.stringify({
      type: 'error', runId: 'run-3', sequence: 2, errorCode: 'TEMPORARILY_UNAVAILABLE',
    }))

    expect(event.errorCode).toBe('TEMPORARILY_UNAVAILABLE')
    expect(JSON.stringify(event)).not.toContain('Authorization')
    expect(JSON.stringify(event)).not.toContain('sk-')
  })
  it('parses structured plan node and budget events', () => {
    const node = parseSseEnvelope(JSON.stringify({
      type: 'plan_node_state', runId: 'run-4', sequence: 3,
      toolName: 'knowledge_hybrid_search',
      orchestration: {
        nodeId: 'node-1', round: 1, status: 'RUNNING', dependencyIds: [],
      },
    }))
    const budget = parseSseEnvelope(JSON.stringify({
      type: 'budget_exhausted', runId: 'run-4', sequence: 4,
      orchestration: {
        status: 'EXHAUSTED', usedNodes: 12, usedModelTokens: 9000,
        estimatedCostCny: 0.12, exhaustionReason: 'MAX_NODES', dependencyIds: [],
      },
    }))

    expect(node.orchestration).toMatchObject({ nodeId: 'node-1', status: 'RUNNING' })
    expect(budget.orchestration).toMatchObject({
      usedNodes: 12, exhaustionReason: 'MAX_NODES',
    })

    const paused = parseSseEnvelope(JSON.stringify({
      type: 'plan_paused', runId: 'run-4', sequence: 5, content: 'checkpoint-1',
    }))
    expect(paused).toMatchObject({ type: 'plan_paused', content: 'checkpoint-1' })
  })

  it('parses a durable worker recovery event', () => {
    const recovered = parseSseEnvelope(JSON.stringify({
      type: 'run_recovered', runId: 'run-5', sequence: 8,
      content: '从安全点恢复', orchestration: { status: 'RECOVERING', dependencyIds: [] },
    }))

    expect(recovered).toMatchObject({ type: 'run_recovered', runId: 'run-5', sequence: 8 })
    expect(recovered.orchestration).toMatchObject({ status: 'RECOVERING' })
  })

  it('reconnects from the durable sequence cursor after a response ends early', async () => {
    const initialWire = 'data: {"type":"started","runId":"run-durable","sequence":1}\n\n'
    const resumedWire = [
      'data: {"type":"run_recovered","runId":"run-durable","sequence":2}\n\n',
      'data: {"type":"delta","runId":"run-durable","sequence":3,"content":"done"}\n\n',
      'data: {"type":"completed","runId":"run-durable","sequence":4}\n\n',
    ].join('')
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response(initialWire, { status: 200 }))
      .mockResolvedValueOnce(new Response(resumedWire, { status: 200 }))
    vi.stubGlobal('fetch', fetchMock)
    const events: ReturnType<typeof parseSseEnvelope>[] = []

    await streamChat('question', (event) => events.push(event), new AbortController().signal)

    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(fetchMock.mock.calls[1]?.[0]).toContain(
      '/chat/streams/run-durable?afterSequence=1',
    )
    expect(events.map((event) => [event.type, event.sequence])).toEqual([
      ['started', 1],
      ['run_recovered', 2],
      ['delta', 3],
      ['completed', 4],
    ])
  })

  it('replays from sequence zero when the accepted initial stream has no events', async () => {
    const resumedWire = [
      'data: {"type":"started","runId":"run-from-header","sessionId":"session-2","sequence":1}\n\n',
      'data: {"type":"delta","runId":"run-from-header","sequence":2,"content":"restored"}\n\n',
      'data: {"type":"completed","runId":"run-from-header","sequence":3}\n\n',
    ].join('')
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response('', {
        status: 200,
        headers: { [CHAT_RUN_ID_HEADER]: 'run-from-header' },
      }))
      .mockResolvedValueOnce(new Response(resumedWire, { status: 200 }))
    vi.stubGlobal('fetch', fetchMock)
    const events: ReturnType<typeof parseSseEnvelope>[] = []
    const accepted = vi.fn()

    await streamChat(
      'question', (event) => events.push(event), new AbortController().signal,
      undefined, undefined, accepted,
    )

    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(accepted).toHaveBeenCalledWith('run-from-header')
    expect(fetchMock.mock.calls[1]?.[0]).toContain(
      '/chat/streams/run-from-header?afterSequence=0',
    )
    expect(events.map((event) => [event.type, event.sequence])).toEqual([
      ['started', 1], ['delta', 2], ['completed', 3],
    ])
  })
})
