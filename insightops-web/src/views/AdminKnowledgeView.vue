<script setup lang="ts">
import axios from 'axios'
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'

import {
  getKnowledgeEmbeddingOverview,
  listKnowledgeSources,
  retryKnowledgeEmbeddings,
  requestKnowledgeSync,
  searchKnowledge,
  type KnowledgeEmbeddingOverview,
  type KnowledgeSearchResult,
  type KnowledgeSourceStatus,
} from '@/api/admin'

const sources = ref<KnowledgeSourceStatus[]>([])
const loading = ref(false)
const syncing = ref<string | null>(null)
const error = ref('')
const notice = ref('')
const embeddings = ref<KnowledgeEmbeddingOverview | null>(null)
const retryingEmbeddings = ref(false)
const searchQuery = ref('')
const searching = ref(false)
const searchResults = ref<KnowledgeSearchResult[]>([])
const searchMeta = ref('')
let refreshTimer: ReturnType<typeof globalThis.setInterval> | undefined

const totals = computed(() => sources.value.reduce((value, source) => ({
  documents: value.documents + source.documentCount,
  revisions: value.revisions + source.revisionCount,
  chunks: value.chunks + source.chunkCount,
}), { documents: 0, revisions: 0, chunks: 0 }))

const embeddingPercent = computed(() => {
  if (!embeddings.value?.total) return 0
  return Math.round(embeddings.value.succeeded * 100 / embeddings.value.total)
})

async function load(silent = false) {
  if (!silent) loading.value = true
  if (!silent) error.value = ''
  try {
    const [sourceData, embeddingData] = await Promise.all([
      listKnowledgeSources(), getKnowledgeEmbeddingOverview(),
    ])
    sources.value = sourceData
    embeddings.value = embeddingData
  }
  catch (caught: unknown) { error.value = message(caught) }
  finally { if (!silent) loading.value = false }
}

async function retryEmbeddings() {
  retryingEmbeddings.value = true
  error.value = ''
  try {
    const count = await retryKnowledgeEmbeddings()
    notice.value = count > 0 ? `${count} 个失败切片已重新加入向量化队列。` : '当前没有需要重试的失败切片。'
    await load(true)
  } catch (caught: unknown) { error.value = message(caught) }
  finally { retryingEmbeddings.value = false }
}

async function testSearch() {
  const query = searchQuery.value.trim()
  if (!query) return
  searching.value = true
  error.value = ''
  searchResults.value = []
  try {
    const response = await searchKnowledge(query)
    searchResults.value = response.results
    searchMeta.value = `${response.model} · ${response.durationMs} ms · ${response.results.length} 条结果`
  } catch (caught: unknown) { error.value = message(caught) }
  finally { searching.value = false }
}

async function sync(source: KnowledgeSourceStatus) {
  syncing.value = source.sourceId
  error.value = ''
  notice.value = ''
  try {
    await requestKnowledgeSync(source.sourceId)
    notice.value = `${source.name} 已加入采集队列。Worker 开启后会在下一轮执行。`
    await load()
  } catch (caught: unknown) { error.value = message(caught) }
  finally { syncing.value = null }
}

function message(caught: unknown): string {
  if (axios.isAxiosError<{ detail?: string; message?: string }>(caught)) {
    return caught.response?.data?.detail ?? caught.response?.data?.message ?? '操作失败，请稍后重试'
  }
  return '操作失败，请稍后重试'
}

function time(value: string | null): string {
  if (!value) return '尚未执行'
  const date = new Date(value)
  if (date.getUTCFullYear() >= 2999) return '等待管理员首次触发'
  return date.toLocaleString()
}

function statusLabel(source: KnowledgeSourceStatus): string {
  if (source.status === 'RUNNING') return '采集中'
  if (source.status === 'SUCCEEDED') return '已完成'
  if (source.status === 'FAILED') return '失败'
  if (source.status === 'RETRY_WAIT') return source.consecutiveFailures > 0 ? '等待重试' : '等待执行'
  return '尚未采集'
}

function jobStatusLabel(status: string): string {
  return ({ RUNNING: '采集中', SUCCEEDED: '已完成', FAILED: '失败' } as Record<string, string>)[status] ?? status
}

function statusClass(source: KnowledgeSourceStatus): string {
  if (source.status === 'SUCCEEDED') return 'status-succeeded'
  if (source.status === 'RUNNING' || source.status === 'RETRY_WAIT') return 'status-running'
  if (source.status === 'FAILED') return 'status-failed'
  return ''
}

onMounted(() => {
  void load()
  refreshTimer = globalThis.setInterval(() => {
    const active = sources.value.some(source => source.status === 'RUNNING'
      || (source.status === 'RETRY_WAIT' && source.consecutiveFailures === 0))
    const embeddingActive = Boolean(embeddings.value
      && (embeddings.value.pending + embeddings.value.running + embeddings.value.retryWait > 0))
    if (active || embeddingActive) void load(true)
  }, 5000)
})
onBeforeUnmount(() => { if (refreshTimer) globalThis.clearInterval(refreshTimer) })
</script>

<template>
  <section>
    <div class="section-heading">
      <div><span class="eyebrow">P1.4-B · 向量检索</span><h2>知识库与 Embedding 管理</h2></div>
      <button class="secondary-button" :disabled="loading" @click="load()">刷新状态</button>
    </div>

    <div class="knowledge-summary">
      <article class="panel"><span>文档</span><strong>{{ totals.documents }}</strong></article>
      <article class="panel"><span>历史版本</span><strong>{{ totals.revisions }}</strong></article>
      <article class="panel"><span>文本切片</span><strong>{{ totals.chunks }}</strong></article>
    </div>

    <div class="panel knowledge-safety-note">
      <strong>安全模式</strong>
      <p>只采集登记过的官方 HTTPS 域名与路径；切片使用本机 Ollama bge-m3 生成向量，原文和向量均保存在本机 PostgreSQL，不调用 DeepSeek。</p>
    </div>

    <p v-if="error" class="stream-error">{{ error }}</p>
    <p v-if="notice" class="success-notice">{{ notice }}</p>
    <p v-if="loading" class="run-loading">正在读取知识源状态…</p>

    <article v-if="embeddings" class="panel embedding-panel">
      <header>
        <div><span class="eyebrow">{{ embeddings.provider || 'ollama' }}</span><h3>{{ embeddings.model }} · {{ embeddings.dimensions || 1024 }} 维</h3></div>
        <strong>{{ embeddingPercent }}%</strong>
      </header>
      <div class="embedding-progress"><i :style="{ width: `${embeddingPercent}%` }"></i></div>
      <div class="embedding-stats">
        <span>完成 <b>{{ embeddings.succeeded }}</b></span>
        <span>待处理 <b>{{ embeddings.pending }}</b></span>
        <span>运行中 <b>{{ embeddings.running }}</b></span>
        <span>等待重试 <b>{{ embeddings.retryWait }}</b></span>
        <span>失败 <b>{{ embeddings.failed }}</b></span>
      </div>
      <button v-if="embeddings.failed" class="secondary-button" :disabled="retryingEmbeddings" @click="retryEmbeddings">
        {{ retryingEmbeddings ? '提交中…' : '重试失败切片' }}
      </button>
    </article>

    <article class="panel knowledge-search-panel">
      <div><span class="eyebrow">语义检索验收</span><h3>测试知识库召回</h3></div>
      <form class="knowledge-search-form" @submit.prevent="testSearch">
        <input v-model="searchQuery" maxlength="2000" placeholder="例如：Spring AI 如何配置 Ollama Embedding？">
        <button class="send-button" :disabled="searching || !searchQuery.trim()">{{ searching ? '检索中…' : '语义检索' }}</button>
      </form>
      <small v-if="searchMeta">{{ searchMeta }}</small>
      <div v-if="searchResults.length" class="knowledge-search-results">
        <a v-for="result in searchResults" :key="result.chunkId" :href="result.canonicalUrl" target="_blank" rel="noopener noreferrer">
          <header><b>{{ result.title }}</b><em>{{ (result.score * 100).toFixed(1) }}%</em></header>
          <span>{{ result.projectName }} · {{ result.headingPath || result.sourceName }}</span>
          <p>{{ result.content }}</p>
        </a>
      </div>
    </article>

    <div class="knowledge-source-grid">
      <article v-for="source in sources" :key="source.sourceId" class="panel knowledge-source-card">
        <header>
          <div><span class="eyebrow">{{ source.projectName }}</span><h3>{{ source.name }}</h3></div>
          <i class="status-pill" :class="statusClass(source)">{{ statusLabel(source) }}</i>
        </header>
        <a :href="source.rootUrl" target="_blank" rel="noopener noreferrer">{{ source.rootUrl }}</a>
        <dl>
          <div><dt>文档 / 切片</dt><dd>{{ source.documentCount }} / {{ source.chunkCount }}</dd></div>
          <div><dt>上次采集</dt><dd>{{ time(source.lastSyncAt) }}</dd></div>
          <div><dt>下次计划</dt><dd>{{ time(source.nextSyncAt) }}</dd></div>
          <div><dt>连续失败</dt><dd>{{ source.consecutiveFailures }}</dd></div>
        </dl>
        <div v-if="source.lastJob" class="knowledge-job">
          <b>最近任务：{{ jobStatusLabel(source.lastJob.status) }}</b>
          <span>{{ source.lastJob.pageCount }} 页 · {{ source.lastJob.chunkCount }} 个新切片</span>
        </div>
        <p v-if="source.lastError" class="stream-error">{{ source.lastError }}</p>
        <footer>
          <span>{{ source.trustTier }}</span>
          <button class="send-button" :disabled="source.status === 'RUNNING' || syncing === source.sourceId" @click="sync(source)">
            {{ syncing === source.sourceId ? '提交中…' : '立即采集' }}
          </button>
        </footer>
      </article>
    </div>
  </section>
</template>
