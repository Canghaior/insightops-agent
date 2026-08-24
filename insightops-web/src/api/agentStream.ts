import { apiClient } from './client'

export interface ModelUsage {
  inputTokens: number | null
  outputTokens: number | null
  totalTokens: number | null
  cacheReadInputTokens: number | null
  cacheWriteInputTokens: number | null
}

export interface AgentOrchestrationEvent {
  planId: string | null
  nodeId: string | null
  status: string | null
  version: number | null
  round: number | null
  maxNodes: number | null
  maxParallelism: number | null
  usedNodes: number | null
  usedToolAttempts: number | null
  usedModelTokens: number | null
  estimatedCostCny: number | null
  dependencyIds: string[]
  errorCode: string | null
  exhaustionReason: string | null
}

export interface ChatCitation {
  label: string
  title: string
  url: string
  project: string | null
  heading: string | null
  sourceType: 'OFFICIAL_DOCUMENT' | 'GITHUB_RELEASE' | 'GITHUB_ISSUE' | 'GITHUB_PULL_REQUEST' | 'GITHUB_SECURITY_ADVISORY' | 'USER_UPLOAD'
  score: number | null
}

export interface ChatStreamEvent {
  type: 'started' | 'run_recovered' | 'plan_created' | 'plan_node_state' | 'plan_paused' | 'budget_updated' | 'budget_exhausted' | 'tool_started' | 'tool_retrying' | 'tool_approval_required' | 'tool_completed' | 'tool_failed' | 'delta' | 'completed' | 'cancelled' | 'error'
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
  retrievalCount: number | null
  retrievalModel: string | null
  sources: string[]
  citations: ChatCitation[]
  orchestration: AgentOrchestrationEvent | null
}

const eventTypes = new Set<ChatStreamEvent['type']>([
  'started',
  'run_recovered',
  'plan_created',
  'plan_node_state',
  'plan_paused',
  'budget_updated',
  'budget_exhausted',
  'tool_started',
  'tool_retrying',
  'tool_approval_required',
  'tool_completed',
  'tool_failed',
  'delta',
  'completed',
  'cancelled',
  'error',
])

const FIRST_EVENT_TIMEOUT_MS = 5_000
const STREAM_IDLE_TIMEOUT_MS = 15_000

class StreamSilenceError extends Error {
  constructor() {
    super('聊天实时连接长时间没有收到事件')
  }
}

interface DurableEventBatch {
  events: unknown[]
  status: string
  terminal: boolean
  lastSequence: number
}

interface ApiResponse<T> {
  traceId: string
  data: T
}

export const CHAT_RUN_ID_HEADER = 'X-InsightOps-Run-Id'

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

async function consumeSseResponse(
  response: Response,
  onEvent: (event: ChatStreamEvent) => void,
): Promise<void> {
  if (!response.ok) throw new Error(`聊天流请求失败：HTTP ${response.status}`)
  if (!response.body) throw new Error('浏览器没有收到聊天流响应体')
  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let receivedEvent = false
  const firstEventDeadline = Date.now() + FIRST_EVENT_TIMEOUT_MS
  const parser = createSseParser((event) => {
    receivedEvent = true
    onEvent(event)
  })
  try {
    while (true) {
      const timeoutMs = receivedEvent
        ? STREAM_IDLE_TIMEOUT_MS
        : Math.max(1, firstEventDeadline - Date.now())
      let timeout: ReturnType<typeof globalThis.setTimeout> | undefined
      const read = reader.read()
      const silence = new Promise<never>((_, reject) => {
        timeout = globalThis.setTimeout(() => reject(new StreamSilenceError()), timeoutMs)
      })
      const { done, value } = await Promise.race([read, silence]).finally(() => {
        if (timeout !== undefined) globalThis.clearTimeout(timeout)
      })
      if (done) {
        parser.push(decoder.decode())
        parser.finish()
        return
      }
      parser.push(decoder.decode(value, { stream: true }))
    }
  } catch (error) {
    void reader.cancel().catch(() => undefined)
    throw error
  }
}

function reconnectDelay(signal: AbortSignal) {
  return new Promise<void>((resolve, reject) => {
    const timer = globalThis.setTimeout(resolve, 500)
    signal.addEventListener('abort', () => {
      globalThis.clearTimeout(timer)
      reject(new globalThis.DOMException('Aborted', 'AbortError'))
    }, { once: true })
  })
}

export async function streamChat(
  message: string,
  onEvent: (event: ChatStreamEvent) => void,
  signal: AbortSignal,
  sessionId?: string,
  resumeCheckpointId?: string,
  onRunAccepted?: (runId: string) => void,
): Promise<void> {
  const baseUrl = import.meta.env.VITE_API_BASE_URL ?? '/api/v1'
  let activeRunId = ''
  let lastSequence = 0
  let terminal = false
  const forward = (event: ChatStreamEvent) => {
    if (event.sequence <= lastSequence) return
    activeRunId = event.runId
    lastSequence = Math.max(lastSequence, event.sequence)
    terminal = event.type === 'completed' || event.type === 'cancelled'
      || event.type === 'error' || event.type === 'plan_paused'
    onEvent(event)
  }
  const initial = await fetch(`${baseUrl}/chat/streams`, {
    method: 'POST',
    headers: {
      Accept: 'text/event-stream',
      'Content-Type': 'application/json',
      'X-Trace-Id': crypto.randomUUID(),
    },
    body: JSON.stringify({
      message,
      sessionId: sessionId || null,
      resumeCheckpointId: resumeCheckpointId || null,
    }),
    credentials: 'include',
    signal,
  })
  if (initial.ok) {
    activeRunId = initial.headers.get(CHAT_RUN_ID_HEADER)?.trim() ?? ''
    if (activeRunId) onRunAccepted?.(activeRunId)
  }
  try {
    await consumeSseResponse(initial, forward)
  } catch (error) {
    if (!activeRunId || signal.aborted) throw error
    // The durable Run was accepted but the initial SSE body broke before its first event.
    // Reconnect below using the response-header identity and replay from sequence zero.
  }
  while (activeRunId && !terminal && !signal.aborted) {
    await reconnectDelay(signal)
    try {
      const replay = await fetch(
        `${baseUrl}/chat/streams/${encodeURIComponent(activeRunId)}/events?afterSequence=${lastSequence}`,
        {
          method: 'GET',
          headers: { Accept: 'application/json' },
          credentials: 'include',
          signal,
        },
      )
      if (!replay.ok) throw new Error(`聊天事件恢复失败：HTTP ${replay.status}`)
      const envelope = await replay.json() as ApiResponse<DurableEventBatch>
      const events = Array.isArray(envelope.data?.events) ? envelope.data.events : []
      for (const raw of events) {
        forward(parseSseEnvelope(JSON.stringify(raw)))
      }
    } catch (error) {
      if (signal.aborted) throw error
      // A different Server instance may be taking over; retry the durable event cursor.
    }
  }
}

export async function cancelChat(runId: string): Promise<boolean> {
  const response = await apiClient.post<{ data: { cancelled: boolean } }>(
    `/chat/streams/${encodeURIComponent(runId)}/cancel`,
  )
  return response.data.data.cancelled
}
