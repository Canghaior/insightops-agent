<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import {
  getRun,
  listRuns,
  type RunDetail,
  type RunStatus,
  type RunSummary,
  type RunToolCall,
} from '@/api/runs'
import MarkdownContent from '@/components/MarkdownContent.vue'

const route = useRoute()
const router = useRouter()
const runs = ref<RunSummary[]>([])
const selectedRun = ref<RunDetail | null>(null)
const loading = ref(false)
const detailLoading = ref(false)
const error = ref<string | null>(null)
const detailError = ref<string | null>(null)
const page = ref(0)
const size = 20
const total = ref(0)
const totalPages = ref(0)
const status = ref<RunStatus | ''>('')

const rangeLabel = computed(() => {
  if (total.value === 0) return '0 条记录'
  const start = page.value * size + 1
  const end = Math.min((page.value + 1) * size, total.value)
  return `${start}–${end} / ${total.value}`
})

async function loadRuns() {
  loading.value = true
  error.value = null
  try {
    const result = await listRuns(page.value, size, status.value || undefined)
    runs.value = result.items
    total.value = result.total
    totalPages.value = result.totalPages
  } catch {
    error.value = '执行记录加载失败，请确认后端和数据库已启动。'
  } finally {
    loading.value = false
  }
}

async function loadDetail(runId: string) {
  detailLoading.value = true
  detailError.value = null
  selectedRun.value = null
  try {
    selectedRun.value = await getRun(runId)
  } catch {
    detailError.value = '该执行记录不存在或暂时无法读取。'
  } finally {
    detailLoading.value = false
  }
}

function applyFilter() {
  page.value = 0
  void loadRuns()
}

function changePage(nextPage: number) {
  page.value = nextPage
  void loadRuns()
}

function openRun(runId: string) {
  void router.push(`/runs/${runId}`)
}

function closeDetail() {
  void router.push('/runs')
}

function toolsFor(stepId: string): RunToolCall[] {
  return selectedRun.value?.toolCalls.filter((tool) => tool.stepId === stepId) ?? []
}

function shortId(value: string): string {
  return value.slice(0, 8)
}

function formatDate(value: string | null): string {
  if (!value) return '—'
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit',
  }).format(new Date(value))
}

function formatDuration(value: number | null): string {
  if (value == null) return '—'
  return value < 1000 ? `${value} ms` : `${(value / 1000).toFixed(2)} s`
}

function totalTokens(run: RunSummary): string {
  if (run.promptTokens == null && run.completionTokens == null) return '—'
  return String((run.promptTokens ?? 0) + (run.completionTokens ?? 0))
}

function formatCost(value: number | null): string {
  return value == null ? '—' : `¥${value.toFixed(6)}`
}

function pretty(value: unknown): string {
  return value == null ? '—' : JSON.stringify(value, null, 2)
}

watch(
  () => route.params.runId,
  (value) => {
    const runId = Array.isArray(value) ? value[0] : value
    if (runId) void loadDetail(runId)
    else selectedRun.value = null
  },
  { immediate: true },
)

onMounted(loadRuns)
</script>

<template>
  <section>
    <div class="section-heading runs-heading">
      <div><span class="eyebrow">可观测</span><h2>Agent 执行记录</h2></div>
      <div class="run-controls">
        <select v-model="status" aria-label="按状态筛选" @change="applyFilter">
          <option value="">全部状态</option>
          <option value="SUCCEEDED">成功</option>
          <option value="RUNNING">运行中</option>
          <option value="FAILED">失败</option>
          <option value="CANCELLED">已取消</option>
        </select>
        <button class="secondary-button" :disabled="loading" @click="loadRuns">刷新</button>
      </div>
    </div>

    <div v-if="error" class="run-error">{{ error }}</div>

    <article v-else class="run-table-panel">
      <div class="run-table-head">
        <span>状态 / Run</span><span>研究问题</span><span>执行信息</span><span>开始时间</span>
      </div>
      <div v-if="loading && runs.length === 0" class="run-loading">正在读取执行记录…</div>
      <div v-else-if="runs.length === 0" class="run-empty">
        <div class="run-orbit"><span></span></div>
        <h3>还没有 Agent Run</h3>
        <p>先在研究问答中提交一个问题，这里会保存完整执行记录。</p>
        <RouterLink class="primary-action" to="/chat">开始研究问答 →</RouterLink>
      </div>
      <button
        v-for="run in runs"
        v-else
        :key="run.id"
        class="run-row"
        :class="{ active: selectedRun?.id === run.id }"
        @click="openRun(run.id)"
      >
        <span class="run-identity">
          <i class="status-pill" :class="`status-${run.status.toLowerCase()}`">{{ run.status }}</i>
          <code>{{ shortId(run.id) }}</code>
        </span>
        <span class="run-question">{{ run.question }}</span>
        <span class="run-facts">
          <strong>{{ run.modelName ?? '—' }}</strong>
          <small>{{ run.toolRounds }} 工具 · {{ totalTokens(run) }} Token · {{ formatDuration(run.durationMs) }}</small>
        </span>
        <time>{{ formatDate(run.createdAt) }}</time>
      </button>
      <footer v-if="runs.length" class="run-pagination">
        <span>{{ rangeLabel }}</span>
        <div>
          <button :disabled="page === 0 || loading" @click="changePage(page - 1)">上一页</button>
          <button :disabled="page + 1 >= totalPages || loading" @click="changePage(page + 1)">下一页</button>
        </div>
      </footer>
    </article>

    <div v-if="route.params.runId" class="run-detail-backdrop" @click.self="closeDetail">
      <aside class="run-detail-panel" aria-label="Agent Run 详情">
        <div class="detail-topbar">
          <div><span class="eyebrow">Run Detail</span><h3>{{ selectedRun ? shortId(selectedRun.id) : '读取中' }}</h3></div>
          <button aria-label="关闭详情" @click="closeDetail">×</button>
        </div>

        <div v-if="detailLoading" class="run-loading">正在加载完整执行链路…</div>
        <div v-else-if="detailError" class="run-error">{{ detailError }}</div>
        <template v-else-if="selectedRun">
          <div class="detail-status-line">
            <i class="status-pill" :class="`status-${selectedRun.status.toLowerCase()}`">{{ selectedRun.status }}</i>
            <span>{{ formatDate(selectedRun.startedAt) }} · {{ formatDuration(selectedRun.durationMs) }}</span>
          </div>

          <dl class="detail-metrics">
            <div><dt>模型</dt><dd>{{ selectedRun.modelName ?? '—' }}</dd></div>
            <div><dt>Token</dt><dd>{{ totalTokens(selectedRun) }}</dd></div>
            <div><dt>工具轮次</dt><dd>{{ selectedRun.toolRounds }}</dd></div>
            <div><dt>估算费用</dt><dd>{{ formatCost(selectedRun.estimatedCostCny) }}</dd></div>
            <div><dt>价格生效日</dt><dd>{{ selectedRun.pricingEffectiveDate ?? '—' }}</dd></div>
            <div><dt>Trace ID</dt><dd><code>{{ selectedRun.traceId }}</code></dd></div>
          </dl>

          <section class="detail-block">
            <span class="eyebrow">Question</span>
            <p>{{ selectedRun.question }}</p>
          </section>
          <section v-if="selectedRun.answer" class="detail-block">
            <span class="eyebrow">Final Answer</span>
            <MarkdownContent class="detail-answer" :content="selectedRun.answer" />
          </section>
          <section v-if="selectedRun.failureCode" class="detail-block detail-failure">
            <span class="eyebrow">Failure</span>
            <p>{{ selectedRun.failureCode }}<template v-if="selectedRun.failureMessage"> · {{ selectedRun.failureMessage }}</template></p>
          </section>

          <section class="detail-block">
            <div class="detail-block-heading"><span class="eyebrow">Execution Timeline</span><small>{{ selectedRun.steps.length }} Steps</small></div>
            <div v-if="selectedRun.steps.length === 0" class="detail-placeholder">本次执行没有调用工具。</div>
            <article v-for="step in selectedRun.steps" :key="step.id" class="step-card">
              <header>
                <span><b>{{ step.stepNo }}</b><strong>{{ step.stepType }}</strong></span>
                <span><i class="status-pill" :class="`status-${step.status.toLowerCase()}`">{{ step.status }}</i> {{ formatDuration(step.durationMs) }}</span>
              </header>
              <div class="payload-grid">
                <details><summary>Step 输入</summary><pre>{{ pretty(step.inputPayload) }}</pre></details>
                <details><summary>Step 输出</summary><pre>{{ pretty(step.outputPayload) }}</pre></details>
              </div>
              <div v-for="tool in toolsFor(step.id)" :key="tool.id" class="tool-call-card">
                <div><strong>{{ tool.toolName }}</strong><i class="status-pill" :class="`status-${tool.status.toLowerCase()}`">{{ tool.status }}</i><span>{{ formatDuration(tool.durationMs) }}</span></div>
                <p v-if="tool.errorMessage">{{ tool.errorMessage }}</p>
                <details><summary>工具请求</summary><pre>{{ pretty(tool.requestPayload) }}</pre></details>
                <details><summary>工具结果</summary><pre>{{ pretty(tool.resultPayload) }}</pre></details>
              </div>
            </article>
          </section>

          <section v-if="selectedRun.sources.length" class="detail-block detail-sources">
            <span class="eyebrow">Official Sources</span>
            <a v-for="source in selectedRun.sources" :key="source" :href="source" target="_blank" rel="noreferrer">{{ source }}</a>
          </section>
        </template>
      </aside>
    </div>
  </section>
</template>
