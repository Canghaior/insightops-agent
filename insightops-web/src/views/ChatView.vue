<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'

import {
  cancelChat,
  streamChat,
  type ChatCitation,
  type ChatStreamEvent,
  type ModelUsage,
} from '@/api/agentStream'
import { getChatSessionHistory, type ChatHistoryMessage } from '@/api/chatHistory'
import {
  deleteConversation,
  listConversations,
  updateConversation,
  type ConversationSummary,
} from '@/api/conversations'
import MarkdownContent from '@/components/MarkdownContent.vue'
import { submitAnswerFeedback, submitCitationFeedback } from '@/api/feedback'
import { sourceHeading } from '@/utils/sourceClassification'

const route = useRoute()

type StreamStatus = 'idle' | 'connecting' | 'queued' | 'streaming' | 'paused' | 'completed' | 'cancelled' | 'error'

interface ToolExecution {
  id: string
  name: string
  status: 'running' | 'waiting_approval' | 'succeeded' | 'failed'
  resultCount?: number | null
  model?: string | null
  errorCode?: string | null
  progress?: string | null
}

interface PlanNodeState {
  id: string
  name: string
  round: number
  status: string
  dependencyIds: string[]
  errorCode?: string | null
}

interface OrchestrationState {
  planId: string
  version: number
  maxNodes: number
  maxParallelism: number
  nodes: PlanNodeState[]
  budgetStatus?: string | null
  usedNodes: number
  usedToolAttempts: number
  usedModelTokens: number
  estimatedCostCny: number
  exhaustionReason?: string | null
}

interface ConversationMessage {
  id: string
  role: ChatHistoryMessage['role']
  content: string
  createdAt: string
  status?: StreamStatus
  runId?: string
  traceId?: string
  model?: string
  usage?: ModelUsage | null
  durationMs?: number | null
  timeToFirstTokenMs?: number | null
  errorMessage?: string
  checkpointId?: string
  toolExecutions?: ToolExecution[]
  orchestration?: OrchestrationState
  toolName?: string
  toolRunning?: boolean
  releaseCount?: number | null
  ragRunning?: boolean
  retrievalCount?: number | null
  retrievalModel?: string | null
  sources?: string[]
  citations?: ChatCitation[]
  feedback?: 'helpful' | 'unhelpful'
  feedbackError?: string
}

const question = ref('')
const status = ref<StreamStatus>('idle')
const runId = ref('')
const sessionId = ref('')
const conversations = ref<ConversationSummary[]>([])
const sessionsLoading = ref(false)
const messages = ref<ConversationMessage[]>([])
const historyLoading = ref(false)
const historyError = ref('')
const resumeCheckpointId = ref(
  typeof route.query.checkpoint === 'string' ? route.query.checkpoint : '',
)
const hasEarlierMessages = ref(false)
const conversationThread = ref<InstanceType<typeof globalThis.HTMLElement> | null>(null)
let currentAssistantId = ''
let streamController: InstanceType<typeof globalThis.AbortController> | null = null
let historyRequestVersion = 0

const suggestions = [
  '用三点解释 Spring AI 对 Java AI 应用开发的价值。',
  '比较 Spring AI 和 LangChain4j 的核心设计取向。',
  '架构师评估 AI Agent 框架时应该关注哪些指标？',
]

const streaming = computed(() => status.value === 'connecting'
  || status.value === 'queued' || status.value === 'streaming')
const canSend = computed(() => question.value.trim().length > 0 && !streaming.value)

const errorLabels: Record<string, string> = {
  REQUEST_REJECTED: '模型拒绝了本次请求，请检查输入或模型配置。',
  TEMPORARILY_UNAVAILABLE: '模型服务暂时不可用，请稍后重试。',
  PROVIDER_ERROR: '模型调用失败，请稍后重试。',
  EMPTY_RESPONSE: '模型没有返回有效内容。',
  TIMED_OUT: '本次生成超过 90 秒，已自动停止。',
  PERSISTENCE_ERROR: '运行记录保存失败，请稍后重试。',
  TOOL_RATE_LIMITED: 'GitHub API 调用额度已用尽，请稍后重试。',
  TOOL_TIMEOUT: 'GitHub Release 查询超时，请稍后重试。',
  TOOL_VALIDATION_ERROR: 'Release 查询参数不在 P0 允许范围内。',
  TOOL_ERROR: 'GitHub Release 查询失败，请稍后重试。',
  OUTPUT_SOURCE_NOT_ALLOWED: '回答来源没有通过安全校验，本次结果已停止。',
  WORKSPACE_COST_MAX_CONCURRENT_RUNS: 'Workspace Agent 并发数已达上限，请等待其他任务结束。',
  WORKSPACE_COST_DAILY_TOKEN_LIMIT: 'Workspace 今日 Token 配额已用尽。',
  WORKSPACE_COST_DAILY_COST_LIMIT: 'Workspace 今日成本配额已用尽。',
  WORKSPACE_COST_MONTHLY_TOKEN_LIMIT: 'Workspace 本月 Token 配额已用尽。',
  WORKSPACE_COST_MONTHLY_COST_LIMIT: 'Workspace 本月成本配额已用尽。',
  CHECKPOINT_NOT_FOUND: '检查点不存在或不属于当前账号。',
  CHECKPOINT_ALREADY_CONSUMED: '该检查点已恢复过，不能重复消费。',
  CHECKPOINT_CONCURRENTLY_CONSUMED: '该检查点刚被其他请求恢复，请刷新执行记录。',
  CHECKPOINT_STATE_INVALID: '检查点数据无效，无法安全恢复。',
}

function statusLabel(message: ConversationMessage) {
  if (message.orchestration?.nodes.some((item) => item.status === 'RUNNING')) return 'Agent 正在并行执行任务图'
  if (message.toolExecutions?.some((item) => item.status === 'running')) return 'Agent 正在执行工具'
  if (message.ragRunning) return '正在检索官方知识库'
  if (message.toolRunning) return '正在执行工具'
  return ({
    idle: '历史回答',
    connecting: '正在创建 Agent Run',
    queued: '已入队，Agent 正在处理',
    streaming: '正在生成',
    completed: '生成完成',
    paused: '已暂停并保存检查点',
    cancelled: '已停止生成',
    error: '生成失败',
  } as Record<StreamStatus, string>)[message.status ?? 'idle']
}

function currentAssistant() {
  return messages.value.find((message) => message.id === currentAssistantId)
}

function isNearConversationBottom() {
  const element = conversationThread.value
  if (!element) return true
  return element.scrollHeight - element.scrollTop - element.clientHeight < 120
}

async function scrollConversationToBottom() {
  await nextTick()
  const element = conversationThread.value
  if (element) element.scrollTop = element.scrollHeight
}

function handleEvent(event: ChatStreamEvent) {
  const assistant = currentAssistant()
  if (!assistant) return
  runId.value = event.runId
  assistant.runId = event.runId
  if (event.sessionId) {
    sessionId.value = event.sessionId
  }
  assistant.traceId = event.traceId || assistant.traceId
  if (event.type === 'started') {
    status.value = 'queued'
    assistant.status = 'queued'
    return
  }
  if (event.type === 'run_recovered') {
    status.value = 'streaming'
    assistant.status = 'streaming'
    assistant.toolRunning = true
    assistant.toolName = event.content ?? '正在从安全点恢复'
    return
  }
  if (event.type === 'plan_created' && event.orchestration) {
    status.value = 'streaming'
    assistant.status = 'streaming'
    assistant.orchestration = {
      planId: event.orchestration.planId ?? '',
      version: event.orchestration.version ?? 1,
      maxNodes: event.orchestration.maxNodes ?? 0,
      maxParallelism: event.orchestration.maxParallelism ?? 1,
      nodes: [],
      budgetStatus: event.orchestration.status,
      usedNodes: 0,
      usedToolAttempts: 0,
      usedModelTokens: 0,
      estimatedCostCny: 0,
    }
    return
  }
  if (event.type === 'plan_node_state' && event.orchestration) {
    assistant.orchestration ??= {
      planId: '', version: 1, maxNodes: 0, maxParallelism: 1, nodes: [],
      usedNodes: 0, usedToolAttempts: 0, usedModelTokens: 0, estimatedCostCny: 0,
    }
    const payload = event.orchestration
    const nodeId = payload.nodeId ?? `node-${event.sequence}`
    let node = assistant.orchestration.nodes.find((item) => item.id === nodeId)
    if (!node) {
      node = {
        id: nodeId,
        name: event.toolName ?? 'unknown_tool',
        round: payload.round ?? 1,
        status: payload.status ?? 'PENDING',
        dependencyIds: payload.dependencyIds ?? [],
      }
      assistant.orchestration.nodes.push(node)
    }
    node.status = payload.status ?? node.status
    node.errorCode = payload.errorCode
    node.dependencyIds = payload.dependencyIds ?? node.dependencyIds
    return
  }
  if ((event.type === 'budget_updated' || event.type === 'budget_exhausted')
    && event.orchestration) {
    assistant.orchestration ??= {
      planId: '', version: 1, maxNodes: 0, maxParallelism: 1, nodes: [],
      usedNodes: 0, usedToolAttempts: 0, usedModelTokens: 0, estimatedCostCny: 0,
    }
    const payload = event.orchestration
    assistant.orchestration.budgetStatus = payload.status
    assistant.orchestration.usedNodes = payload.usedNodes ?? assistant.orchestration.usedNodes
    assistant.orchestration.usedToolAttempts = payload.usedToolAttempts ?? assistant.orchestration.usedToolAttempts
    assistant.orchestration.usedModelTokens = payload.usedModelTokens ?? assistant.orchestration.usedModelTokens
    assistant.orchestration.estimatedCostCny = payload.estimatedCostCny ?? assistant.orchestration.estimatedCostCny
    assistant.orchestration.exhaustionReason = payload.exhaustionReason
    return
  }
  if (event.type === 'tool_started') {
    assistant.toolExecutions ??= []
    assistant.toolExecutions.push({
      id: event.toolCallId ?? `tool-${event.sequence}`,
      name: event.toolName ?? 'unknown_tool',
      status: 'running',
    })
    if (event.toolName === 'knowledge_vector_search' || event.toolName === 'knowledge_hybrid_search') {
      assistant.ragRunning = true
      return
    }
    assistant.toolName = event.toolName ?? 'github_release_list'
    assistant.toolRunning = true
    return
  }
  if (event.type === 'tool_retrying') {
    assistant.toolExecutions ??= []
    const id = event.toolCallId ?? `tool-${event.sequence}`
    let execution = assistant.toolExecutions.find((item) => item.id === id)
    if (!execution) {
      execution = { id, name: event.toolName ?? 'unknown_tool', status: 'running' }
      assistant.toolExecutions.push(execution)
    }
    execution.status = 'running'
    execution.errorCode = event.errorCode
    execution.progress = event.content ?? '临时失败，正在重试'
    return
  }
  if (event.type === 'tool_approval_required') {
    assistant.toolExecutions ??= []
    const id = event.toolCallId ?? `tool-${event.sequence}`
    let execution = assistant.toolExecutions.find((item) => item.id === id)
    if (!execution) {
      execution = { id, name: event.toolName ?? 'user_memory_upsert', status: 'running' }
      assistant.toolExecutions.push(execution)
    }
    execution.status = 'waiting_approval'
    execution.progress = event.content ?? '写操作等待人工审批'
    assistant.toolRunning = false
    return
  }
  if (event.type === 'tool_completed' || event.type === 'tool_failed') {
    assistant.toolExecutions ??= []
    const id = event.toolCallId ?? `tool-${event.sequence}`
    let execution = assistant.toolExecutions.find((item) => item.id === id)
    if (!execution) {
      execution = { id, name: event.toolName ?? 'unknown_tool', status: 'running' }
      assistant.toolExecutions.push(execution)
    }
    execution.status = event.type === 'tool_failed' ? 'failed' : 'succeeded'
    execution.progress = undefined
    execution.errorCode = event.errorCode
    execution.resultCount = event.retrievalCount ?? event.releaseCount
    execution.model = event.retrievalModel
    if (event.toolName === 'knowledge_vector_search' || event.toolName === 'knowledge_hybrid_search') {
      assistant.ragRunning = false
      assistant.retrievalCount = event.retrievalCount
      assistant.retrievalModel = event.retrievalModel
      return
    }
    assistant.toolName = event.toolName ?? 'github_release_list'
    assistant.toolRunning = false
    assistant.releaseCount = event.releaseCount
    return
  }
  if (event.type === 'delta') {
    status.value = 'streaming'
    assistant.status = 'streaming'
    const shouldFollow = isNearConversationBottom()
    assistant.content += event.content ?? ''
    if (shouldFollow) void scrollConversationToBottom()
    return
  }
  if (event.type === 'plan_paused') {
    status.value = 'paused'
    assistant.status = 'paused'
    assistant.checkpointId = event.content ?? undefined
    assistant.toolRunning = false
    assistant.ragRunning = false
    void loadConversations()
    return
  }
  if (event.type === 'completed') {
    status.value = 'completed'
    assistant.status = 'completed'
    assistant.model = event.model ?? ''
    assistant.usage = event.usage
    assistant.durationMs = event.durationMs
    assistant.timeToFirstTokenMs = event.timeToFirstTokenMs
    assistant.sources = event.sources ?? []
    assistant.citations = event.citations ?? []
    assistant.toolRunning = false
    assistant.ragRunning = false
    void scrollConversationToBottom()
    void loadConversations()
    return
  }
  if (event.type === 'cancelled') {
    status.value = 'cancelled'
    assistant.status = 'cancelled'
    assistant.toolRunning = false
    assistant.ragRunning = false
    return
  }
  status.value = 'error'
  assistant.status = 'error'
  assistant.toolRunning = false
  assistant.ragRunning = false
  assistant.errorMessage = errorLabels[event.errorCode ?? ''] ?? '流式生成失败，请稍后重试。'
}

function clearStoredSession() {
  sessionId.value = ''
}

async function loadConversations(selectLatest = false) {
  const requestVersion = historyRequestVersion
  sessionsLoading.value = true
  try {
    conversations.value = await listConversations(true)
    if (selectLatest && requestVersion === historyRequestVersion && !sessionId.value) {
      const latest = conversations.value.find((item) => item.status === 'ACTIVE')
      if (latest) await selectConversation(latest)
    }
  } finally { sessionsLoading.value = false }
}

async function selectConversation(conversation: ConversationSummary) {
  if (streaming.value) return
  const requestVersion = ++historyRequestVersion
  if (conversation.status === 'ARCHIVED') {
    await updateConversation(conversation.id, { archived: false })
    await loadConversations()
  }
  sessionId.value = conversation.id
  messages.value = []
  await loadHistory(conversation.id, requestVersion)
}

async function renameConversation(conversation: ConversationSummary) {
  const title = globalThis.prompt('新的会话标题', conversation.title)?.trim()
  if (!title) return
  Object.assign(conversation, await updateConversation(conversation.id, { title }))
}

async function archiveConversation(conversation: ConversationSummary) {
  await updateConversation(conversation.id, { archived: conversation.status !== 'ARCHIVED' })
  if (sessionId.value === conversation.id) startNewConversation()
  await loadConversations()
}

async function removeConversation(conversation: ConversationSummary) {
  if (!globalThis.confirm(`永久删除会话“${conversation.title}”？执行审计记录会保留。`)) return
  await deleteConversation(conversation.id)
  if (sessionId.value === conversation.id) startNewConversation()
  await loadConversations()
}

async function loadHistory(selectedSessionId: string, requestVersion: number) {
  historyLoading.value = true
  historyError.value = ''
  try {
    const history = await getChatSessionHistory(selectedSessionId)
    if (requestVersion !== historyRequestVersion || sessionId.value !== selectedSessionId) return
    if (!history) {
      clearStoredSession()
      return
    }
    messages.value = history.messages.map((message) => ({
      id: message.id,
      role: message.role,
      content: message.content,
      createdAt: message.createdAt,
      sources: message.citations ?? [],
      citations: message.citationDetails ?? [],
    }))
    hasEarlierMessages.value = history.hasEarlierMessages
    await scrollConversationToBottom()
  } catch {
    if (requestVersion === historyRequestVersion && sessionId.value === selectedSessionId) {
      historyError.value = '历史消息加载失败，可以刷新页面重试；数据库中的记录不会丢失。'
    }
  } finally {
    if (requestVersion === historyRequestVersion) historyLoading.value = false
  }
}

function startNewConversation() {
  if (streaming.value) return
  historyRequestVersion += 1
  historyLoading.value = false
  clearStoredSession()
  question.value = ''
  status.value = 'idle'
  runId.value = ''
  currentAssistantId = ''
  messages.value = []
  historyError.value = ''
  hasEarlierMessages.value = false
}

async function sendQuestion() {
  const message = question.value.trim()
  if (!message || streaming.value) return

  const userMessageId = `local-user-${globalThis.crypto.randomUUID()}`
  currentAssistantId = `local-assistant-${globalThis.crypto.randomUUID()}`
  runId.value = ''
  historyError.value = ''
  messages.value.push(
    {
      id: userMessageId,
      role: 'USER',
      content: message,
      createdAt: new Date().toISOString(),
    },
    {
      id: currentAssistantId,
      role: 'ASSISTANT',
      content: '',
      createdAt: new Date().toISOString(),
      status: 'connecting',
      usage: null,
      sources: [],
      citations: [],
      toolExecutions: [],
    },
  )
  question.value = ''
  status.value = 'connecting'
  await scrollConversationToBottom()

  streamController = new globalThis.AbortController()
  try {
    const checkpointId = resumeCheckpointId.value
    resumeCheckpointId.value = ''
    await streamChat(
      message, handleEvent, streamController.signal, sessionId.value, checkpointId || undefined,
      (acceptedRunId) => {
        runId.value = acceptedRunId
        const assistant = currentAssistant()
        if (assistant) assistant.runId = acceptedRunId
      },
    )
    const assistant = currentAssistant()
    if (assistant && (status.value === 'connecting'
      || status.value === 'queued' || status.value === 'streaming')) {
      status.value = 'completed'
      assistant.status = 'completed'
    }
  } catch (error) {
    const assistant = currentAssistant()
    if (error instanceof globalThis.DOMException && error.name === 'AbortError') {
      status.value = 'cancelled'
      if (assistant) assistant.status = 'cancelled'
      return
    }
    if (!runId.value) {
      messages.value = messages.value.filter(
        (item) => item.id !== userMessageId && item.id !== currentAssistantId,
      )
      question.value = message
      historyError.value = error instanceof Error
        ? error.message : '无法连接聊天服务，请稍后重试。'
    } else if (assistant) {
      assistant.status = 'error'
      assistant.errorMessage = error instanceof Error ? error.message : '无法连接聊天服务。'
    }
    status.value = 'error'
  } finally {
    streamController = null
  }
}

function handleComposerEnter(event: InstanceType<typeof globalThis.KeyboardEvent>) {
  if (event.isComposing || event.shiftKey) return
  event.preventDefault()
  if (canSend.value) void sendQuestion()
}

async function stopGeneration() {
  if (!streaming.value) return
  const activeRunId = runId.value
  status.value = 'cancelled'
  const assistant = currentAssistant()
  if (assistant) {
    assistant.status = 'cancelled'
    assistant.toolRunning = false
  }
  if (activeRunId) {
    try {
      await cancelChat(activeRunId)
    } catch {
      // The durable cancellation request may already have reached another Server instance.
    }
  }
  streamController?.abort()
}

function formatMessageTime(value: string) {
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(value))
}

function planRounds(message: ConversationMessage) {
  const grouped = new Map<number, PlanNodeState[]>()
  for (const node of message.orchestration?.nodes ?? []) {
    const nodes = grouped.get(node.round) ?? []
    nodes.push(node)
    grouped.set(node.round, nodes)
  }
  return [...grouped.entries()].sort(([left], [right]) => left - right)
}

function planNodeStatus(status: string) {
  return ({
    PENDING: '等待', RUNNING: '执行中', SUCCEEDED: '成功', FAILED: '失败',
    SKIPPED: '跳过', WAITING_APPROVAL: '待审批', CANCELLED: '已取消',
  } as Record<string, string>)[status] ?? status
}

function budgetPercentage(message: ConversationMessage) {
  const budget = message.orchestration
  if (!budget?.maxNodes) return 0
  return Math.min(100, Math.round((budget.usedNodes / budget.maxNodes) * 100))
}

function toolExecutionLabel(execution: ToolExecution) {
  if (execution.name === 'github_release_list') return 'GitHub Release'
  if (execution.name === 'knowledge_hybrid_search') return '知识库混合检索'
  if (execution.name === 'project_intelligence_event_search') return '项目情报事件'
  if (execution.name === 'user_memory_upsert') return '长期记忆写入'
  if (execution.name === 'mcp_read_call') return '受控 MCP 只读调用'
  return execution.name
}

function toolExecutionResult(execution: ToolExecution) {
  if (execution.status === 'running') return execution.progress ?? '执行中'
  if (execution.status === 'waiting_approval') return '尚未执行 · 等待你的审批'
  if (execution.status === 'failed') return `安全降级 · ${execution.errorCode ?? '工具不可用'}`
  const count = execution.resultCount ?? 0
  return execution.model ? `已获取 ${count} 条 · ${execution.model}` : `已获取 ${count} 条结果`
}


async function rateAnswer(message: ConversationMessage, helpful: boolean) {
  if (!message.runId) return
  message.feedbackError = ''
  try {
    const comment = helpful ? undefined : globalThis.prompt('可以补充问题原因（可选）')?.trim()
    await submitAnswerFeedback(message.runId, helpful, helpful ? 'HELPFUL' : 'UNHELPFUL', comment)
    message.feedback = helpful ? 'helpful' : 'unhelpful'
  } catch { message.feedbackError = '反馈提交失败，请稍后重试。' }
}

async function rateCitation(message: ConversationMessage, citation: ChatCitation, correct: boolean) {
  if (!message.runId) return
  try {
    const comment = correct ? undefined : globalThis.prompt('请说明引用问题（可选）')?.trim()
    await submitCitationFeedback(message.runId, citation.url, correct, comment)
  } catch { message.feedbackError = '引用反馈提交失败，请稍后重试。' }
}

onMounted(() => {
  if (typeof route.query.question === 'string') question.value = route.query.question.slice(0, 4000)
  void loadConversations(true)
})

onBeforeUnmount(() => {
  streamController?.abort()
})
</script>

<template>
  <section class="research-layout">
    <aside class="conversation-sidebar">
      <div class="conversation-sidebar-heading">
        <div><span class="eyebrow">我的会话</span><strong>{{ conversations.length }}</strong></div>
        <button class="secondary-button" :disabled="streaming" @click="startNewConversation">＋ 新建</button>
      </div>
      <p v-if="sessionsLoading" class="subtle">加载中…</p>
      <div class="conversation-list">
        <article
          v-for="conversation in conversations"
          :key="conversation.id"
          :class="{ active: sessionId === conversation.id, archived: conversation.status === 'ARCHIVED' }"
        >
          <button class="conversation-select" @click="selectConversation(conversation)">
            <strong>{{ conversation.title }}</strong>
            <small>{{ conversation.messageCount }} 条消息 · {{ conversation.status === 'ACTIVE' ? '未归档' : '已归档' }}</small>
          </button>
          <div class="conversation-actions">
            <button @click="renameConversation(conversation)">改名</button>
            <button @click="archiveConversation(conversation)">{{ conversation.status === 'ACTIVE' ? '归档' : '恢复' }}</button>
            <button @click="removeConversation(conversation)">删除</button>
          </div>
        </article>
        <p v-if="!sessionsLoading && !conversations.length" class="subtle">还没有会话</p>
      </div>
    </aside>
    <div class="research-main">
      <span class="eyebrow">研究问答 · P1 个人工作区</span>
      <h2>与 DeepSeek 多轮实时问答</h2>
      <p class="lead">Agent Run 在后台持久执行；刷新、断网或实例切换后会从事件序号继续，只有点击“停止生成”才会取消任务。</p>

      <div v-if="resumeCheckpointId" class="checkpoint-resume-banner">
        <strong>准备从检查点 {{ resumeCheckpointId.slice(0, 8) }} 恢复</strong>
        <span>输入后续要求并发送；已有证据、来源、预算与已执行节点会继续复用。</span>
        <button class="text-button" @click="resumeCheckpointId = ''">取消恢复</button>
      </div>

      <div class="suggestion-list">
        <button
          v-for="item in suggestions"
          :key="item"
          :disabled="streaming"
          @click="question = item"
        >
          {{ item }}
        </button>
      </div>

      <p v-if="historyLoading" class="chat-history-state">正在恢复本会话的历史消息…</p>
      <p v-else-if="historyError" class="stream-error">{{ historyError }}</p>

      <section
        v-if="messages.length"
        ref="conversationThread"
        class="conversation-thread"
        aria-live="polite"
        aria-label="当前会话消息"
      >
        <p v-if="hasEarlierMessages" class="history-boundary">当前显示最近 100 条消息，更早记录仍保存在数据库。</p>

        <template v-for="message in messages" :key="message.id">
          <article v-if="message.role === 'USER'" class="chat-message user-message">
            <header><strong>你</strong><time>{{ formatMessageTime(message.createdAt) }}</time></header>
            <p>{{ message.content }}</p>
          </article>

          <article v-else-if="message.role === 'ASSISTANT'" class="answer-panel chat-message assistant-message">
            <header class="answer-heading">
              <div>
                <span class="stream-status" :class="`is-${message.status ?? 'completed'}`">
                  <i></i>{{ statusLabel(message) }}
                </span>
                <h3>DeepSeek 回答</h3>
              </div>
              <code v-if="message.runId">Run {{ message.runId.slice(0, 8) }}</code>
            </header>

            <section v-if="message.orchestration" class="orchestration-panel">
              <header>
                <span>任务图 · v{{ message.orchestration.version }}</span>
                <strong>并行上限 {{ message.orchestration.maxParallelism }}</strong>
              </header>
              <div class="orchestration-budget">
                <div><i :style="{ width: `${budgetPercentage(message)}%` }"></i></div>
                <span>
                  节点 {{ message.orchestration.usedNodes }}/{{ message.orchestration.maxNodes }} ·
                  工具尝试 {{ message.orchestration.usedToolAttempts }} ·
                  规划 Token {{ message.orchestration.usedModelTokens }} ·
                  ¥{{ message.orchestration.estimatedCostCny.toFixed(6) }}
                </span>
              </div>
              <div v-for="[round, nodes] in planRounds(message)" :key="round" class="plan-layer">
                <b>第 {{ round }} 层</b>
                <span
                  v-for="node in nodes"
                  :key="node.id"
                  class="plan-node"
                  :class="`is-${node.status.toLowerCase()}`"
                  :title="node.errorCode ?? node.dependencyIds.join(', ')"
                >{{ toolExecutionLabel({ id: node.id, name: node.name, status: 'running' }) }} · {{ planNodeStatus(node.status) }}</span>
              </div>
              <p v-if="message.orchestration.exhaustionReason" class="budget-warning">
                已安全停止新增节点：{{ message.orchestration.exhaustionReason }}
              </p>
            </section>

            <div
              v-for="execution in message.toolExecutions"
              :key="execution.id"
              class="tool-summary"
              :class="{ 'is-running': execution.status === 'running', 'is-failed': execution.status === 'failed' }"
            >
              <span>Agent 工具 · {{ toolExecutionLabel(execution) }}</span>
              <strong>{{ toolExecutionResult(execution) }}</strong>
              <RouterLink v-if="execution.status === 'waiting_approval'" class="text-button" to="/approvals">前往审批</RouterLink>
            </div>

            <div v-if="message.status === 'paused' && message.checkpointId" class="checkpoint-resume-banner">
              <strong>Run 已在安全点暂停</strong>
              <span>检查点 {{ message.checkpointId.slice(0, 8) }} 已持久化，可在执行记录中恢复。</span>
              <RouterLink class="text-button" :to="`/runs/${message.runId}`">查看检查点</RouterLink>
            </div>
            <p v-if="message.errorMessage" class="stream-error">{{ message.errorMessage }}</p>
            <div v-else-if="!message.content && (message.status === 'connecting' || message.status === 'streaming')" class="answer-skeleton">
              <span></span><span></span><span></span>
            </div>
            <div v-else class="answer-content">
              <MarkdownContent :content="message.content" />
              <span v-if="message.status === 'streaming'" class="stream-cursor"></span>
            </div>

            <dl v-if="message.status === 'completed' && message.usage" class="stream-metrics">
              <div><dt>模型</dt><dd>{{ message.model || 'deepseek-v4-flash' }}</dd></div>
              <div><dt>首 Token</dt><dd>{{ message.timeToFirstTokenMs ?? '—' }} ms</dd></div>
              <div><dt>总耗时</dt><dd>{{ message.durationMs ?? '—' }} ms</dd></div>
              <div><dt>Token</dt><dd>{{ message.usage.totalTokens ?? '—' }}</dd></div>
            </dl>
            <div v-if="message.status === 'completed' && message.runId" class="feedback-row">
              <span>{{ message.feedback ? '感谢反馈' : '这个回答有帮助吗？' }}</span>
              <button class="secondary-button" :disabled="!!message.feedback" @click="rateAnswer(message, true)">有帮助</button>
              <button class="secondary-button" :disabled="!!message.feedback" @click="rateAnswer(message, false)">需改进</button>
              <small v-if="message.feedbackError" class="stream-error">{{ message.feedbackError }}</small>
            </div>
            <div v-if="message.citations?.length" class="source-list">
              <strong>结构化官方引用</strong>
              <div class="citation-grid">
                <div
                  v-for="citation in message.citations"
                  :key="`${citation.label}-${citation.url}`"
                  class="citation-feedback-card"
                >
                  <a class="citation-card" :href="citation.url" target="_blank" rel="noreferrer">
                    <span>{{ citation.label }} · {{ citation.sourceType === 'GITHUB_RELEASE' ? 'Release' : citation.project }}</span>
                    <b>{{ citation.heading || citation.title }}</b>
                    <small v-if="citation.score != null">融合得分 {{ citation.score.toFixed(3) }}</small>
                  </a>
                  <div v-if="message.runId" class="citation-feedback"><button class="text-button" @click="rateCitation(message, citation, true)">引用正确</button><button class="text-button" @click="rateCitation(message, citation, false)">引用有误</button></div>
                </div>
              </div>
            </div>
            <div v-else-if="message.sources?.length" class="source-list">
              <strong>{{ sourceHeading(message.sources ?? []) }}</strong>
              <a v-for="source in message.sources" :key="source" :href="source" target="_blank" rel="noreferrer">
                {{ source }}
              </a>
            </div>
            <p v-if="message.traceId" class="trace-line">
              会话 · {{ sessionId.slice(0, 8) }} · TraceId · {{ message.traceId }}
            </p>
          </article>
        </template>
      </section>

      <div v-else-if="!historyLoading" class="conversation-empty">
        <strong>开始一个研究问题</strong>
        <p>发送后问题会进入消息列表，输入框会自动清空。</p>
      </div>

      <div class="composer chat-composer">
        <textarea
          v-model="question"
          rows="4"
          maxlength="4000"
          placeholder="输入关于 Spring AI、LangChain4j、Dify 或 Agent 架构的问题…"
          @keydown.enter="handleComposerEnter"
        ></textarea>
        <div>
          <span>{{ question.length }}/4000 · Enter 发送 · Shift + Enter 换行 · 90 秒超时</span>
          <div class="composer-actions">
            <button v-if="streaming" class="stop-button" @click="stopGeneration">停止生成</button>
            <button v-else-if="sessionId" class="secondary-button" @click="startNewConversation">新建会话</button>
            <button v-if="!streaming" class="send-button" :disabled="!canSend" @click="sendQuestion">发送问题</button>
          </div>
        </div>
      </div>
    </div>

    <aside class="evidence-panel">
      <span class="eyebrow">本步能力</span>
      <h3>多轮 Agent、可追溯、可回看</h3>
      <ul>
        <li>发送后立即清空输入框</li>
        <li>多轮问题与回答连续展示</li>
        <li>刷新后从数据库恢复会话</li>
        <li>复用最近 12 条消息理解追问</li>
        <li>DeepSeek Function Calling 动态选择只读工具</li>
        <li>分层 DAG、多只读工具受限并行与写操作独占</li>
        <li>节点、工具尝试、Token 与成本预算治理</li>
        <li>bge-m3 + PostgreSQL 全文混合检索</li>
        <li>RRF 融合排序与结构化引用卡片</li>
        <li>DeepSeek 基于证据生成并附官方来源</li>
        <li>记录 Token、耗时与 TraceId</li>
        <li>跨实例租约接管与安全点恢复</li>
        <li>断线自动续传；用户显式取消才终止</li>
      </ul>
      <div class="scope-warning">
        <strong>当前限制</strong>
        <p>知识库当前覆盖 Spring AI、LangChain4j 与 Dify 官方文档；未命中的问题会明确证据不足或降级为普通问答。</p>
      </div>
    </aside>
  </section>
</template>
