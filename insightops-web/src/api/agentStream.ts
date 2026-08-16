import { apiClient } from './client'

export interface ModelUsage {
  inputTokens: number | null
  outputTokens: number | null
  totalTokens: number | null
  cacheReadInputTokens: number | null
  cacheWriteInputTokens: number | null
}

export interface ChatStreamEvent {
  type: 'started' | 'tool_started' | 'tool_completed' | 'delta' | 'completed' | 'cancelled' | 'error'
  runId: string
  sessionId: string
  sequence: number
  timestamp: string
  traceId: string
  content: string | null
  provider: string | null
  model: string | null
  usage: ModelUsage | null
  durationMs: number | null
  timeToFirstTokenMs: number | null
  errorCode: string | null
  toolName: string | null
  toolCallId: string | null
  releaseCount: number | null
  sources: string[]
}

const eventTypes = new Set<ChatStreamEvent['type']>([
  'started',
  'tool_started',
  'tool_completed',
  'delta',
  'completed',
  'cancelled',
  'error',
])

export function parseSseEnvelope(raw: string): ChatStreamEvent {
  const parsed = JSON.parse(raw) as ChatStreamEvent
  if (!eventTypes.has(parsed.type) || !parsed.runId || !Number.isInteger(parsed.sequence)) {
    throw new Error('聊天 SSE 事件缺少有效的 type、runId 或 sequence')
  }
  return parsed
}

export function createSseParser(onEvent: (event: ChatStreamEvent) => void) {
  let buffer = ''

  const parseBlock = (block: string) => {
    const data = block
      .split('\n')
      .filter((line) => line.startsWith('data:'))
      .map((line) => line.slice(5).trimStart())
      .join('\n')
    if (data) {
      onEvent(parseSseEnvelope(data))
    }
  }

  const drain = (flush: boolean) => {
    buffer = buffer.replaceAll('\r\n', '\n')
    let boundary = buffer.indexOf('\n\n')
    while (boundary >= 0) {
      parseBlock(buffer.slice(0, boundary))
      buffer = buffer.slice(boundary + 2)
      boundary = buffer.indexOf('\n\n')
    }
    if (flush && buffer.trim()) {
      parseBlock(buffer)
      buffer = ''
    }
  }

  return {
    push(chunk: string) {
      buffer += chunk
      drain(false)
    },
    finish() {
      drain(true)
    },
  }
}

export async function streamChat(
  message: string,
  onEvent: (event: ChatStreamEvent) => void,
  signal: AbortSignal,
  sessionId?: string,
): Promise<void> {
  const baseUrl = import.meta.env.VITE_API_BASE_URL ?? '/api/v1'
  const response = await fetch(`${baseUrl}/chat/streams`, {
    method: 'POST',
    headers: {
      Accept: 'text/event-stream',
      'Content-Type': 'application/json',
      'X-Trace-Id': crypto.randomUUID(),
    },
    body: JSON.stringify({ message, sessionId: sessionId || null }),
    signal,
  })

  if (!response.ok) {
    throw new Error(`聊天流请求失败：HTTP ${response.status}`)
  }
  if (!response.body) {
    throw new Error('浏览器没有收到聊天流响应体')
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  const parser = createSseParser(onEvent)
  while (true) {
    const { done, value } = await reader.read()
    if (done) {
      parser.push(decoder.decode())
      parser.finish()
      return
    }
    parser.push(decoder.decode(value, { stream: true }))
  }
}

export async function cancelChat(runId: string): Promise<boolean> {
  const response = await apiClient.post<{ data: { cancelled: boolean } }>(
    `/chat/streams/${encodeURIComponent(runId)}/cancel`,
  )
  return response.data.data.cancelled
}
