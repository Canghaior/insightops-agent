<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'

import { cancelChat, streamChat, type ChatStreamEvent, type ModelUsage } from '@/api/agentStream'
import { getChatSessionHistory, type ChatHistoryMessage } from '@/api/chatHistory'
import MarkdownContent from '@/components/MarkdownContent.vue'

type StreamStatus = 'idle' | 'connecting' | 'streaming' | 'completed' | 'cancelled' | 'error'

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
  toolName?: string
  toolRunning?: boolean
  releaseCount?: number | null
  sources?: string[]
}

const SESSION_STORAGE_KEY = 'insightops.chat.sessionId'
const question = ref('')
const status = ref<StreamStatus>('idle')
const runId = ref('')
const sessionId = ref(globalThis.sessionStorage.getItem(SESSION_STORAGE_KEY) ?? '')
const messages = ref<ConversationMessage[]>([])
const historyLoading = ref(false)
const historyError = ref('')
const hasEarlierMessages = ref(false)
const conversationThread = ref<InstanceType<typeof globalThis.HTMLElement> | null>(null)
let currentAssistantId = ''
let streamController: InstanceType<typeof globalThis.AbortController> | null = null

const suggestions = [
  '用三点解释 Spring AI 对 Java AI 应用开发的价值。',
  '比较 Spring AI 和 LangChain4j 的核心设计取向。',
  '架构师评估 AI Agent 框架时应该关注哪些指标？',
]

const streaming = computed(() => status.value === 'connecting' || status.value === 'streaming')
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
}

function statusLabel(message: ConversationMessage) {
  if (message.toolRunning) return '正在查询 GitHub Releases'
  return ({
    idle: '历史回答',
    connecting: '正在连接 DeepSeek',
    streaming: '正在生成',
    completed: '生成完成',
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
    globalThis.sessionStorage.setItem(SESSION_STORAGE_KEY, event.sessionId)
  }
  assistant.traceId = event.traceId || assistant.traceId
  if (event.type === 'started') {
    status.value = 'streaming'
    assistant.status = 'streaming'
    return
  }
  if (event.type === 'tool_started') {
    assistant.toolName = event.toolName ?? 'github_release_list'
    assistant.toolRunning = true
    return
  }
  if (event.type === 'tool_completed') {
    assistant.toolName = event.toolName ?? 'github_release_list'
    assistant.toolRunning = false
    assistant.releaseCount = event.releaseCount
    return
  }
  if (event.type === 'delta') {
    const shouldFollow = isNearConversationBottom()
    assistant.content += event.content ?? ''
    if (shouldFollow) void scrollConversationToBottom()
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
    void scrollConversationToBottom()
    return
  }
  if (event.type === 'cancelled') {
    status.value = 'cancelled'
    assistant.status = 'cancelled'
    assistant.toolRunning = false
    return
  }
  status.value = 'error'
  assistant.status = 'error'
  assistant.toolRunning = false
  assistant.errorMessage = errorLabels[event.errorCode ?? ''] ?? '流式生成失败，请稍后重试。'
}

function clearStoredSession() {
  globalThis.sessionStorage.removeItem(SESSION_STORAGE_KEY)
  sessionId.value = ''
}

async function loadHistory() {
  if (!sessionId.value) return
  historyLoading.value = true
  historyError.value = ''
  try {
    const history = await getChatSessionHistory(sessionId.value)
    if (!history) {
      clearStoredSession()
      return
    }
    messages.value = history.messages.map((message) => ({
      id: message.id,
      role: message.role,
      content: message.content,
      createdAt: message.createdAt,
    }))
    hasEarlierMessages.value = history.hasEarlierMessages
    await scrollConversationToBottom()
  } catch {
    historyError.value = '历史消息加载失败，可以刷新页面重试；数据库中的记录不会丢失。'
  } finally {
    historyLoading.value = false
  }
}

function startNewConversation() {
  if (streaming.value) return
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
    },
  )
  question.value = ''
  status.value = 'connecting'
  await scrollConversationToBottom()

  streamController = new globalThis.AbortController()
  try {
    await streamChat(message, handleEvent, streamController.signal, sessionId.value)
    const assistant = currentAssistant()
    if (assistant && (status.value === 'connecting' || status.value === 'streaming')) {
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
      // Aborting the HTTP stream still triggers server-side disconnect cleanup.
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

onMounted(() => void loadHistory())

onBeforeUnmount(() => {
  const activeRunId = runId.value
  streamController?.abort()
  if (streaming.value && activeRunId) void cancelChat(activeRunId).catch(() => undefined)
})
</script>

<template>
  <section class="research-layout">
    <div class="research-main">
      <span class="eyebrow">研究问答 · P0 流式链路</span>
      <h2>与 DeepSeek 多轮实时问答</h2>
      <p class="lead">回答会按消息连续显示并保存到数据库；刷新当前标签页可以恢复本会话，模型使用最近 12 条消息理解指代。</p>

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

            <div v-if="message.toolName" class="tool-summary" :class="{ 'is-running': message.toolRunning }">
              <span>工具 · {{ message.toolName }}</span>
              <strong>{{ message.toolRunning ? '执行中' : `已获取 ${message.releaseCount ?? 0} 条 Release` }}</strong>
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
            <div v-if="message.sources?.length" class="source-list">
              <strong>GitHub 官方来源</strong>
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
      <h3>流式、可停、已保存、可回看</h3>
      <ul>
        <li>发送后立即清空输入框</li>
        <li>多轮问题与回答连续展示</li>
        <li>刷新后从数据库恢复会话</li>
        <li>复用最近 12 条消息理解追问</li>
        <li>记录 Token、耗时与 TraceId</li>
        <li>用户取消立即终止</li>
      </ul>
      <div class="scope-warning">
        <strong>当前限制</strong>
        <p>当前标签页最多回看最近 100 条消息；账号级会话列表、跨设备同步和可管理的长期记忆将在 P1 实现。</p>
      </div>
    </aside>
  </section>
</template>
