<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'

import { cancelChat, streamChat, type ChatStreamEvent, type ModelUsage } from '@/api/agentStream'

type StreamStatus = 'idle' | 'connecting' | 'streaming' | 'completed' | 'cancelled' | 'error'

const question = ref('')
const answer = ref('')
const status = ref<StreamStatus>('idle')
const runId = ref('')
const sessionId = ref('')
const traceId = ref('')
const model = ref('')
const usage = ref<ModelUsage | null>(null)
const durationMs = ref<number | null>(null)
const timeToFirstTokenMs = ref<number | null>(null)
const errorMessage = ref('')
const toolName = ref('')
const toolRunning = ref(false)
const releaseCount = ref<number | null>(null)
const sources = ref<string[]>([])
let streamController: InstanceType<typeof globalThis.AbortController> | null = null

const suggestions = [
  '用三点解释 Spring AI 对 Java AI 应用开发的价值。',
  '比较 Spring AI 和 LangChain4j 的核心设计取向。',
  '架构师评估 AI Agent 框架时应该关注哪些指标？',
]

const streaming = computed(() => status.value === 'connecting' || status.value === 'streaming')
const canSend = computed(() => question.value.trim().length > 0 && !streaming.value)
const statusLabel = computed(() => ({
  idle: '等待提问',
  connecting: '正在连接 DeepSeek',
  streaming: '正在生成',
  completed: '生成完成',
  cancelled: '已停止生成',
  error: '生成失败',
})[status.value])

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
}

function resetResult() {
  answer.value = ''
  runId.value = ''
  traceId.value = ''
  model.value = ''
  usage.value = null
  durationMs.value = null
  timeToFirstTokenMs.value = null
  errorMessage.value = ''
  toolName.value = ''
  toolRunning.value = false
  releaseCount.value = null
  sources.value = []
}

function handleEvent(event: ChatStreamEvent) {
  runId.value = event.runId
  sessionId.value = event.sessionId || sessionId.value
  traceId.value = event.traceId || traceId.value
  if (event.type === 'started') {
    status.value = 'streaming'
    return
  }
  if (event.type === 'tool_started') {
    toolName.value = event.toolName ?? 'github_release_list'
    toolRunning.value = true
    return
  }
  if (event.type === 'tool_completed') {
    toolName.value = event.toolName ?? 'github_release_list'
    toolRunning.value = false
    releaseCount.value = event.releaseCount
    return
  }
  if (event.type === 'delta') {
    answer.value += event.content ?? ''
    return
  }
  if (event.type === 'completed') {
    status.value = 'completed'
    model.value = event.model ?? ''
    usage.value = event.usage
    durationMs.value = event.durationMs
    timeToFirstTokenMs.value = event.timeToFirstTokenMs
    sources.value = event.sources ?? []
    return
  }
  if (event.type === 'cancelled') {
    status.value = 'cancelled'
    return
  }
  status.value = 'error'
  errorMessage.value = errorLabels[event.errorCode ?? ''] ?? '流式生成失败，请稍后重试。'
}

async function sendQuestion() {
  const message = question.value.trim()
  if (!message || streaming.value) return

  resetResult()
  status.value = 'connecting'
  streamController = new globalThis.AbortController()
  try {
    await streamChat(message, handleEvent, streamController.signal, sessionId.value)
    if (status.value === 'connecting' || status.value === 'streaming') {
      status.value = 'completed'
    }
  } catch (error) {
    if (error instanceof globalThis.DOMException && error.name === 'AbortError') {
      status.value = 'cancelled'
      return
    }
    status.value = 'error'
    errorMessage.value = error instanceof Error ? error.message : '无法连接聊天服务。'
  } finally {
    streamController = null
  }
}

async function stopGeneration() {
  if (!streaming.value) return
  const activeRunId = runId.value
  status.value = 'cancelled'
  if (activeRunId) {
    try {
      await cancelChat(activeRunId)
    } catch {
      // Aborting the HTTP stream still triggers server-side disconnect cleanup.
    }
  }
  streamController?.abort()
}

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
      <h2>与 DeepSeek 单轮实时问答</h2>
      <p class="lead">回答会增量显示，并保存会话、消息和 Agent Run，同时记录模型、Token、首 Token 时间和 TraceId；P0 尚未把历史消息传给模型。</p>

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

      <article v-if="status !== 'idle'" class="answer-panel" aria-live="polite">
        <header class="answer-heading">
          <div>
            <span class="stream-status" :class="`is-${status}`">
              <i></i>{{ toolRunning ? '正在查询 GitHub Releases' : statusLabel }}
            </span>
            <h3>DeepSeek 回答</h3>
          </div>
          <code v-if="runId">Run {{ runId.slice(0, 8) }}</code>
        </header>

        <div v-if="toolName" class="tool-summary" :class="{ 'is-running': toolRunning }">
          <span>工具 · {{ toolName }}</span>
          <strong>{{ toolRunning ? '执行中' : `已获取 ${releaseCount ?? 0} 条 Release` }}</strong>
        </div>

        <p v-if="errorMessage" class="stream-error">{{ errorMessage }}</p>
        <div v-else-if="!answer && streaming" class="answer-skeleton">
          <span></span><span></span><span></span>
        </div>
        <div v-else class="answer-content">
          {{ answer }}<span v-if="streaming" class="stream-cursor"></span>
        </div>

        <dl v-if="status === 'completed'" class="stream-metrics">
          <div><dt>模型</dt><dd>{{ model || 'deepseek-v4-flash' }}</dd></div>
          <div><dt>首 Token</dt><dd>{{ timeToFirstTokenMs ?? '—' }} ms</dd></div>
          <div><dt>总耗时</dt><dd>{{ durationMs ?? '—' }} ms</dd></div>
          <div><dt>Token</dt><dd>{{ usage?.totalTokens ?? '—' }}</dd></div>
        </dl>
        <div v-if="sources.length" class="source-list">
          <strong>GitHub 官方来源</strong>
          <a v-for="source in sources" :key="source" :href="source" target="_blank" rel="noreferrer">
            {{ source }}
          </a>
        </div>
        <p v-if="traceId" class="trace-line">
          会话 · {{ sessionId.slice(0, 8) }} · TraceId · {{ traceId }}
        </p>
      </article>

      <div class="composer">
        <textarea
          v-model="question"
          rows="5"
          maxlength="4000"
          :disabled="streaming"
          placeholder="输入关于 Spring AI、LangChain4j、Dify 或 Agent 架构的问题…"
          @keydown.ctrl.enter.prevent="sendQuestion"
        ></textarea>
        <div>
          <span>{{ question.length }}/4000 · Ctrl + Enter 发送 · 90 秒超时</span>
          <div class="composer-actions">
            <button v-if="streaming" class="stop-button" @click="stopGeneration">停止生成</button>
            <button v-else class="send-button" :disabled="!canSend" @click="sendQuestion">发送问题</button>
          </div>
        </div>
      </div>
    </div>

    <aside class="evidence-panel">
      <span class="eyebrow">本步能力</span>
      <h3>流式、可停、已保存（暂不记忆）</h3>
      <ul>
        <li>DeepSeek V4 Flash 真实输出</li>
        <li>增量 Chunk 实时显示</li>
        <li>用户取消立即终止</li>
        <li>记录 Token、耗时与 TraceId</li>
        <li>保存会话、消息与 Agent Run</li>
      </ul>
      <div class="scope-warning">
        <strong>当前限制</strong>
        <p>GitHub Release 查询与完整 Step、Tool Call 审计已接入；会话消息虽已保存，但多轮上下文记忆将在 P1 实现。</p>
      </div>
    </aside>
  </section>
</template>
