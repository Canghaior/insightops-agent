import { describe, expect, it } from 'vitest'

import { createSseParser, parseSseEnvelope } from './agentStream'

const started = '{"type":"started","runId":"run-1","sessionId":"session-1","sequence":1}'
const delta = '{"type":"delta","runId":"run-1","sequence":2,"content":"Spring AI"}'

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
})
