<script setup lang="ts">
import axios from 'axios'
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'

import {
  createKnowledgeSource,
  deleteKnowledgeSource,
  getLatestRagEvaluation,
  getKnowledgeEmbeddingOverview,
  listKnowledgeSources,
  listManagedProjects,
  retryKnowledgeEmbeddings,
  requestKnowledgeSync,
  runRagEvaluation,
  searchKnowledge,
  setKnowledgeSourceEnabled,
  updateKnowledgeSource,
  type KnowledgeCollectionJob,
  type KnowledgeEmbeddingOverview,
  type KnowledgeSearchResult,
  type KnowledgeSourceStatus,
  type ManagedProject,
  type RagEvaluationCase,
  type RagEvaluationReport,
} from '@/api/admin'
import {
  beginKnowledgeStatusLoad,
  completeKnowledgeStatusLoad,
  failKnowledgeStatusLoad,
} from './adminKnowledgeLoadState'

const sources = ref<KnowledgeSourceStatus[]>([])
const projects = ref<ManagedProject[]>([])
const loading = ref(false)
const syncing = ref<string | null>(null)
const error = ref('')
const refreshError = ref('')
const notice = ref('')
const embeddings = ref<KnowledgeEmbeddingOverview | null>(null)
const retryingEmbeddings = ref(false)
const searchQuery = ref('')
const searching = ref(false)
const searchResults = ref<KnowledgeSearchResult[]>([])
const searchMeta = ref('')
const evaluation = ref<RagEvaluationReport | null>(null)
const evaluating = ref(false)
const savingSource = ref(false)
const editingSourceId = ref<string | null>(null)
const sourceForm = reactive({
  projectId: '', name: '', sourceType: 'OFFICIAL_DOCUMENTATION',
  rootUrl: '', discoveryUrl: '', allowedPathPrefix: '/', syncIntervalHours: 24,
})
let refreshTimer: ReturnType<typeof globalThis.setInterval> | undefined

const editingSource = computed(() => sources.value.find(
  source => source.sourceId === editingSourceId.value,
) ?? null)
const boundaryLocked = computed(() => Boolean(editingSource.value?.documentCount))

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
  beginKnowledgeStatusLoad(error, refreshError, silent)
  try {
    const [sourceData, projectData, embeddingData, evaluationData] = await Promise.all([
      listKnowledgeSources(), listManagedProjects(), getKnowledgeEmbeddingOverview(), getLatestRagEvaluation(),
    ])
    sources.value = sourceData
    projects.value = projectData
    if (!sourceForm.projectId && projectData.length) sourceForm.projectId = projectData[0].projectId
    embeddings.value = embeddingData
    evaluation.value = evaluationData
    completeKnowledgeStatusLoad(refreshError)
  }
  catch (caught: unknown) {
    const detail = message(caught)
    failKnowledgeStatusLoad(error, refreshError, silent, detail)
  }
  finally { if (!silent) loading.value = false }
}

async function saveSource() {
  savingSource.value = true
  error.value = ''
  notice.value = ''
  try {
    const input = {
      projectId: sourceForm.projectId,
      name: sourceForm.name.trim(),
      sourceType: sourceForm.sourceType,
      rootUrl: sourceForm.rootUrl.trim(),
      discoveryUrl: sourceForm.discoveryUrl.trim(),
      allowedPathPrefix: sourceForm.allowedPathPrefix.trim(),
      syncIntervalHours: sourceForm.syncIntervalHours,
    }
    if (editingSourceId.value) {
      await updateKnowledgeSource(editingSourceId.value, input)
      notice.value = `${input.name} 已更新。`
    } else {
      await createKnowledgeSource(input)
      notice.value = `${input.name} 已创建并加入采集队列。`
    }
    cancelSourceEdit()
    await load()
  } catch (caught: unknown) { error.value = message(caught) }
  finally { savingSource.value = false }
}

function editSource(source: KnowledgeSourceStatus) {
  editingSourceId.value = source.sourceId
  sourceForm.projectId = source.projectId
  sourceForm.name = source.name
  sourceForm.sourceType = source.sourceType
  sourceForm.rootUrl = source.rootUrl
  sourceForm.discoveryUrl = source.discoveryUrl
  sourceForm.allowedPathPrefix = source.allowedPathPrefix
  sourceForm.syncIntervalHours = source.syncIntervalHours
  error.value = ''
  notice.value = ''
}

function cancelSourceEdit() {
  editingSourceId.value = null
  Object.assign(sourceForm, {
    projectId: projects.value[0]?.projectId ?? '', name: '',
    sourceType: 'OFFICIAL_DOCUMENTATION', rootUrl: '', discoveryUrl: '',
    allowedPathPrefix: '/', syncIntervalHours: 24,
  })
}

async function toggleSource(source: KnowledgeSourceStatus) {
  error.value = ''
  notice.value = ''
  try {
    await setKnowledgeSourceEnabled(source.sourceId, !source.enabled)
    notice.value = `${source.name} 已${source.enabled ? '停用' : '启用'}。`
    await load()
  } catch (caught: unknown) { error.value = message(caught) }
}

async function removeSource(source: KnowledgeSourceStatus) {
  if (!globalThis.confirm(`确定删除 ${source.name}？已有文档的来源只能停用，不能删除。`)) return
  error.value = ''
  notice.value = ''
  try {
    await deleteKnowledgeSource(source.sourceId)
    if (editingSourceId.value === source.sourceId) cancelSourceEdit()
    notice.value = `${source.name} 已删除。`
    await load()
  } catch (caught: unknown) { error.value = message(caught) }
}

async function evaluateRag() {
  evaluating.value = true
  error.value = ''
  notice.value = ''
  try {
    evaluation.value = await runRagEvaluation(3, true)
    notice.value = evaluation.value.summary?.passed
      ? `RAG 质量评测已通过。${evaluation.value.caseCount} 道题的完整结果已经保存。`
      : 'RAG 质量评测已完成，但有指标未达到门禁，请查看失败题目。'
  } catch (caught: unknown) { error.value = message(caught) }
  finally { evaluating.value = false }
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

function progressLabel(job: KnowledgeCollectionJob): string {
  const maxPages = job.maxPageCount ?? 0
  const limit = maxPages > 0 ? ` / 上限 ${maxPages}` : ''
  return `已访问 ${job.visitedUrlCount ?? 0} · 已发现 ${job.discoveredUrlCount ?? 0} · 有效页面 ${job.pageCount}${limit}`
}

function leaseLabel(job: KnowledgeCollectionJob): string {
  if (!job.heartbeatAt) return '尚无心跳'
  const age = Date.now() - new Date(job.heartbeatAt).getTime()
  if (job.status === 'RUNNING' && age > 60_000) return `心跳可能中断 · ${time(job.heartbeatAt)}`
  return `最近心跳 ${time(job.heartbeatAt)}`
}

function statusClass(source: KnowledgeSourceStatus): string {
  if (source.status === 'SUCCEEDED') return 'status-succeeded'
  if (source.status === 'RUNNING' || source.status === 'RETRY_WAIT') return 'status-running'
  if (source.status === 'FAILED') return 'status-failed'
  return ''
}

function percent(value: number | null | undefined): string {
  return value == null ? '未评测' : `${(value * 100).toFixed(1)}%`
}

function evaluationStatus(): string {
  if (!evaluation.value) return '尚未运行'
  const labels: Record<string, string> = {
    PASSED: '通过', FAILED: '未通过', ERROR: '执行错误', RUNNING: '运行中',
  }
  return labels[evaluation.value.status] ?? evaluation.value.status
}

function failedCase(item: RagEvaluationCase): boolean {
  return !item.answerabilityCorrect || (item.expectedAnswerable && !item.projectHit)
    || (item.citationPrecision != null && item.citationPrecision < 0.9)
    || (item.faithfulness != null && item.faithfulness < 0.75)
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
      <div><span class="eyebrow">P1.5-B · 可配置知识源</span><h2>知识库、Embedding 与 RAG 质量管理</h2></div>
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

    <form class="panel knowledge-source-form" @submit.prevent="saveSource">
      <div class="admin-form-heading">
        <strong>{{ editingSourceId ? '编辑知识源' : '添加官方知识源' }}</strong>
        <span>仅允许公开 HTTPS 域名；采集时仍会执行 DNS、重定向、路径和 robots.txt 安全检查。</span>
      </div>
      <label>所属项目
        <select v-model="sourceForm.projectId" :disabled="boundaryLocked" required>
          <option v-for="project in projects" :key="project.projectId" :value="project.projectId">
            {{ project.repositoryOwner }}/{{ project.repositoryName }}
          </option>
        </select>
      </label>
      <label>来源名称<input v-model="sourceForm.name" maxlength="256" placeholder="OpenAI Java Documentation" required></label>
      <label>来源类型
        <select v-model="sourceForm.sourceType">
          <option value="OFFICIAL_DOCUMENTATION">官方文档</option>
          <option value="MIGRATION_GUIDE">迁移指南</option>
          <option value="OFFICIAL_RELEASE_NOTES">官方发布说明</option>
        </select>
      </label>
      <label>采集周期（小时）<input v-model.number="sourceForm.syncIntervalHours" type="number" min="1" max="720" required></label>
      <label class="knowledge-url-field">根 URL<input v-model="sourceForm.rootUrl" :disabled="boundaryLocked" type="url" maxlength="1024" placeholder="https://docs.example.com/guide/" required></label>
      <label class="knowledge-url-field">发现 URL<input v-model="sourceForm.discoveryUrl" type="url" maxlength="1024" placeholder="https://docs.example.com/sitemap.xml" required></label>
      <label>允许路径前缀<input v-model="sourceForm.allowedPathPrefix" :disabled="boundaryLocked" maxlength="512" placeholder="/guide/" required></label>
      <div class="project-form-actions">
        <button v-if="editingSourceId" type="button" class="secondary-button" @click="cancelSourceEdit">取消</button>
        <button class="send-button" :disabled="savingSource || !projects.length">
          {{ savingSource ? '保存中…' : editingSourceId ? '保存修改' : '添加来源' }}
        </button>
      </div>
      <p v-if="boundaryLocked" class="project-form-note">已有文档，所属项目、根 URL 与路径边界已锁定；仍可修改名称、发现 URL、类型和采集周期。</p>
    </form>

    <p v-if="error || refreshError" class="stream-error">{{ error || refreshError }}</p>
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

    <article class="panel rag-evaluation-panel">
      <header>
        <div>
          <span class="eyebrow">自动化质量门禁</span>
          <h3>RAG 评测集</h3>
          <p>固定运行 50 道题：42 道三项目知识题与 8 道安全/越界题，并覆盖多语言、版本冲突、跨来源、多轮指代和提示注入；抽样 3 题调用 DeepSeek 检查引用和忠实度。</p>
        </div>
        <div class="evaluation-action">
          <i class="status-pill" :class="evaluation?.status === 'PASSED' ? 'status-succeeded' : evaluation ? 'status-failed' : ''">
            {{ evaluationStatus() }}
          </i>
          <button class="send-button" :disabled="evaluating" @click="evaluateRag">
            {{ evaluating ? '评测中…' : '一键运行评测' }}
          </button>
        </div>
      </header>
      <template v-if="evaluation?.summary">
        <div class="evaluation-metrics">
          <span><small>Recall@10</small><b>{{ percent(evaluation.summary.recallAtK) }}</b></span>
          <span><small>MRR</small><b>{{ percent(evaluation.summary.meanReciprocalRank) }}</b></span>
          <span><small>术语覆盖</small><b>{{ percent(evaluation.summary.termCoverage) }}</b></span>
          <span><small>拒答准确率</small><b>{{ percent(evaluation.summary.noAnswerAccuracy) }}</b></span>
          <span><small>引用准确率</small><b>{{ percent(evaluation.summary.citationPrecision) }}</b></span>
          <span><small>引用覆盖率</small><b>{{ percent(evaluation.summary.citationCoverage) }}</b></span>
          <span><small>忠实度</small><b>{{ percent(evaluation.summary.faithfulness) }}</b></span>
        </div>
        <small>数据集 {{ evaluation.datasetName }} · {{ evaluation.caseCount }} 题 · {{ time(evaluation.finishedAt || evaluation.startedAt) }} · {{ evaluation.summary.modelName || '仅检索' }}</small>
        <details v-if="evaluation.cases.some(failedCase)" class="evaluation-failures">
          <summary>查看未达标题目（{{ evaluation.cases.filter(failedCase).length }}）</summary>
          <div v-for="item in evaluation.cases.filter(failedCase)" :key="item.caseKey">
            <b>{{ item.caseKey }} · {{ item.question }}</b>
            <span>预期 {{ item.expectedProject || '拒答' }} · 实际 {{ item.predictedAnswerable ? (item.topProjects[0] || '有结果') : '拒答' }}</span>
          </div>
        </details>
      </template>
      <p v-else-if="evaluation?.errorMessage" class="stream-error">{{ evaluation.errorMessage }}</p>
      <p v-else class="muted-copy">首次运行会生成基线，结果及每题明细会保存到 PostgreSQL。</p>
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
          <div><dt>采集周期</dt><dd>{{ source.syncIntervalHours }} 小时</dd></div>
          <div><dt>路径边界</dt><dd>{{ source.allowedPathPrefix }}</dd></div>
        </dl>
        <div v-if="source.lastJob" class="knowledge-job">
          <div class="knowledge-job-summary">
            <b>最近任务：{{ jobStatusLabel(source.lastJob.status) }}</b>
            <span>{{ progressLabel(source.lastJob) }}</span>
            <span>{{ source.lastJob.chunkCount }} 个新切片 · {{ leaseLabel(source.lastJob) }}</span>
            <span v-if="source.lastJob.status === 'RUNNING' && source.lastJob.leaseExpiresAt">
              租约到期 {{ time(source.lastJob.leaseExpiresAt) }}
            </span>
          </div>
          <a
            v-if="source.lastJob.currentUrl"
            class="knowledge-current-url"
            :href="source.lastJob.currentUrl"
            target="_blank"
            rel="noopener noreferrer"
            :title="source.lastJob.currentUrl"
          >
            {{ source.lastJob.status === 'RUNNING' ? '当前 URL' : '最后 URL' }}：{{ source.lastJob.currentUrl }}
          </a>
        </div>
        <p v-if="source.lastError" class="stream-error">{{ source.lastError }}</p>
        <footer>
          <span>{{ source.trustTier }}</span>
          <div class="knowledge-source-actions">
            <button class="secondary-button" :disabled="source.status === 'RUNNING'" @click="editSource(source)">编辑</button>
            <button class="secondary-button" :disabled="source.status === 'RUNNING'" @click="toggleSource(source)">{{ source.enabled ? '停用' : '启用' }}</button>
            <button class="danger-button" :disabled="source.documentCount > 0 || source.status === 'RUNNING'" @click="removeSource(source)">删除</button>
            <button class="send-button" :disabled="!source.enabled || source.status === 'RUNNING' || syncing === source.sourceId" @click="sync(source)">
              {{ syncing === source.sourceId ? '提交中…' : '立即采集' }}
            </button>
          </div>
        </footer>
      </article>
    </div>
  </section>
</template>
