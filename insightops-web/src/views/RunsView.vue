<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import {
  getRun,
  listRuns,
  type RunDetail,
  type RunPlanNode,
  type RunStatus,
  type RunSummary,
  type RunToolCall,
} from '@/api/runs'
import MarkdownContent from '@/components/MarkdownContent.vue'
import { getLatestAgentCheckpoint, pauseAgentRun, type AgentCheckpoint } from '@/api/checkpoints'
import {
  getWorkflowRun,
  retryWorkflowRun,
  type WorkflowRunDetail,
  type WorkflowRunNode,
} from '@/api/workflowRuns'

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
const checkpoint = ref<AgentCheckpoint | null>(null)
const workflowRun = ref<WorkflowRunDetail | null>(null)
const runActionLoading = ref(false)
const runActionError = ref('')
let detailPoll: ReturnType<typeof globalThis.setInterval> | undefined

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

function stopDetailPolling() {
  if (detailPoll) globalThis.clearInterval(detailPoll)
  detailPoll = undefined
}

function startDetailPolling(runId: string) {
  if (detailPoll) return
  detailPoll = globalThis.setInterval(() => void loadDetail(runId, true), 2_000)
}

async function loadDetail(runId: string, quiet = false) {
  if (!quiet) {
    detailLoading.value = true
    detailError.value = null
    selectedRun.value = null
    checkpoint.value = null
    workflowRun.value = null
    runActionError.value = ''
  }
  try {
    selectedRun.value = await getRun(runId)
    try {
      workflowRun.value = await getWorkflowRun(runId)
    } catch {
      workflowRun.value = null
    }
    if (selectedRun.value.status === 'PAUSED' || workflowRun.value) {
      try {
        checkpoint.value = await getLatestAgentCheckpoint(runId)
      } catch {
        checkpoint.value = null
      }
    }
  } catch {
    if (!quiet) detailError.value = '该执行记录不存在或暂时无法读取。'
  } finally {
    if (!quiet) detailLoading.value = false
    if (selectedRun.value?.id === runId && selectedRun.value.status === 'RUNNING') {
      startDetailPolling(runId)
    } else {
      stopDetailPolling()
    }
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

async function requestPause() {
  if (!selectedRun.value || selectedRun.value.status !== 'RUNNING') return
  runActionLoading.value = true
  runActionError.value = ''
  try {
    await pauseAgentRun(selectedRun.value.id)
    runActionError.value = '暂停请求已提交；Agent 会在下一个安全点保存检查点。'
  } catch {
    runActionError.value = '暂停请求失败；该 Run 可能已结束或不属于当前 Workspace。'
  } finally {
    runActionLoading.value = false
  }
}

function resumeCheckpoint() {
  if (!checkpoint.value) return
  void router.push({ path: '/chat', query: { checkpoint: checkpoint.value.id } })
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

function planRounds(nodes: RunPlanNode[]): Array<[number, RunPlanNode[]]> {
  const grouped = new Map<number, RunPlanNode[]>()
  for (const node of nodes) {
    const layer = grouped.get(node.round) ?? []
    layer.push(node)
    grouped.set(node.round, layer)
  }
  return [...grouped.entries()].sort(([left], [right]) => left - right)
}

function budgetPercent(run: RunDetail): number {
  if (!run.budget?.maxNodes) return 0
  return Math.min(100, Math.round((run.budget.usedNodes / run.budget.maxNodes) * 100))
}

function nodeStatusLabel(status: string): string {
  return ({
    PENDING: '等待', RUNNING: '执行中', SUCCEEDED: '成功', FAILED: '失败',
    SKIPPED: '跳过', REUSED: '已复用', BLOCKED: '条件未满足', WAITING_APPROVAL: '待审批', CANCELLED: '已取消',
  } as Record<string, string>)[status] ?? status
}

function pretty(value: unknown): string {
  return value == null ? '—' : JSON.stringify(value, null, 2)
}
async function retryWorkflow(node: WorkflowRunNode) {
  if (!selectedRun.value || !workflowRun.value || node.status !== 'FAILED') return
  runActionLoading.value = true
  runActionError.value = ''
  try {
    const result = await retryWorkflowRun(selectedRun.value.id, node.logicalNodeId)
    await router.push(`/runs/${result.runId}`)
    await loadRuns()
  } catch {
    runActionError.value = '从失败节点重试失败，请确认 Run 状态和节点血缘。'
  } finally {
    runActionLoading.value = false
  }
}

function workflowDuration(node: WorkflowRunNode): number | null {
  if (!node.startedAt || !node.finishedAt) return null
  return Math.max(0, new Date(node.finishedAt).getTime() - new Date(node.startedAt).getTime())
}

function workflowTokens(node: WorkflowRunNode): number {
  return node.inputTokens + node.outputTokens
}

watch(
  () => route.params.runId,
  (value) => {
    stopDetailPolling()
    const runId = Array.isArray(value) ? value[0] : value
    if (runId) void loadDetail(runId)
    else selectedRun.value = null
  },
  { immediate: true },
)

onMounted(loadRuns)
onUnmounted(stopDetailPolling)
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
          <option value="PAUSED">已暂停</option>
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
            <button v-if="selectedRun.status === 'RUNNING'" class="secondary-button" :disabled="runActionLoading" @click="requestPause">请求暂停</button>
            <button v-if="selectedRun.status === 'PAUSED' && checkpoint" class="primary-button" @click="resumeCheckpoint">从检查点恢复</button>
          </div>
          <p v-if="runActionError" class="checkpoint-notice">{{ runActionError }}</p>
          <p v-if="checkpoint" class="checkpoint-notice">
            检查点 #{{ checkpoint.sequence }} · {{ checkpoint.reason }} · {{ formatDate(checkpoint.createdAt) }}
          </p>

          <dl class="detail-metrics">
            <div><dt>模型</dt><dd>{{ selectedRun.modelName ?? '—' }}</dd></div>
            <div><dt>Token</dt><dd>{{ totalTokens(selectedRun) }}</dd></div>
            <div><dt>工具轮次</dt><dd>{{ selectedRun.toolRounds }}</dd></div>
            <div><dt>估算费用</dt><dd>{{ formatCost(selectedRun.estimatedCostCny) }}</dd></div>
            <div><dt>价格生效日</dt><dd>{{ selectedRun.pricingEffectiveDate ?? '—' }}</dd></div>
            <div><dt>Trace ID</dt><dd><code>{{ selectedRun.traceId }}</code></dd></div>
          </dl>

          <section v-if="workflowRun" class="detail-block workflow-run-block">
            <div class="detail-block-heading">
              <span class="eyebrow">Workflow Snapshot · {{ workflowRun.templateName }} v{{ workflowRun.templateVersion }}</span>
              <small>{{ workflowRun.nodes.length }} 节点 · 合同 {{ workflowRun.toolContractFingerprint.slice(0, 8) }}</small>
            </div>
            <p v-if="workflowRun.sourceRunId" class="checkpoint-notice">
              从 Run
              <RouterLink :to="`/runs/${workflowRun.sourceRunId}`">{{ shortId(workflowRun.sourceRunId) }}</RouterLink>
              的失败节点 {{ workflowRun.retryFromNodeId }} 重试；成功节点直接复用。
            </p>
            <details><summary>固化入口参数</summary><pre>{{ pretty(workflowRun.inputs) }}</pre></details>
            <div class="workflow-node-list">
              <article
                v-for="node in workflowRun.nodes"
                :key="node.id"
                class="workflow-runtime-node"
                :class="`is-${node.status.toLowerCase()}`"
              >
                <header>
                  <span><strong>{{ node.logicalNodeId }}</strong><code>{{ node.toolName }}@{{ node.toolVersion }}</code></span>
                  <i class="status-pill" :class="`status-${node.status.toLowerCase()}`">{{ nodeStatusLabel(node.status) }}</i>
                </header>
                <p>
                  {{ node.attemptCount }} 次节点执行 · {{ workflowTokens(node) }} Token ·
                  {{ formatCost(node.estimatedCostCny) }} · {{ formatDuration(workflowDuration(node)) }}
                </p>
                <p v-if="node.errorCode" class="budget-warning">{{ node.errorCode }}</p>
                <div class="workflow-node-actions">
                  <button
                    v-if="selectedRun.status === 'FAILED' && node.status === 'FAILED'"
                    class="secondary-button"
                    :disabled="runActionLoading"
                    @click="retryWorkflow(node)"
                  >
                    从此失败节点重试
                  </button>
                  <a v-if="node.toolCallId" href="#execution-timeline">查看 Tool Call {{ shortId(node.toolCallId) }}</a>
                </div>
                <details><summary>解析后输入</summary><pre>{{ pretty(node.resolvedInput) }}</pre></details>
                <details><summary>节点输出</summary><pre>{{ pretty(node.output) }}</pre></details>
                <details><summary>允许下游读取</summary><pre>{{ pretty(node.exposedOutput) }}</pre></details>
              </article>
            </div>
          </section>

          <section v-if="selectedRun.plan" class="detail-block run-plan-block">
            <div class="detail-block-heading">
              <span class="eyebrow">Task Graph · v{{ selectedRun.plan.version }}</span>
              <small>{{ selectedRun.plan.status }} · 并行上限 {{ selectedRun.plan.maxParallelism }}</small>
            </div>
            <div v-if="selectedRun.budget" class="orchestration-budget">
              <div><i :style="{ width: `${budgetPercent(selectedRun)}%` }"></i></div>
              <span>
                节点 {{ selectedRun.budget.usedNodes }}/{{ selectedRun.budget.maxNodes }} ·
                尝试 {{ selectedRun.budget.usedToolAttempts }}/{{ selectedRun.budget.maxToolAttempts }} ·
                Token {{ selectedRun.budget.usedModelTokens }}/{{ selectedRun.budget.maxModelTokens }} ·
                ¥{{ selectedRun.budget.estimatedCostCny.toFixed(6) }}/¥{{ selectedRun.budget.maxEstimatedCostCny.toFixed(6) }}
              </span>
              <strong v-if="selectedRun.budget.exhaustionReason" class="budget-warning">
                安全降级：{{ selectedRun.budget.exhaustionReason }}
              </strong>
            </div>
            <div v-for="[round, nodes] in planRounds(selectedRun.plan.nodes)" :key="round" class="plan-layer run-plan-layer">
              <b>第 {{ round }} 层</b>
              <span
                v-for="node in nodes"
                :key="node.id"
                class="plan-node"
                :class="`is-${node.status.toLowerCase()}`"
                :title="node.dependencyIds.length ? `依赖 ${node.dependencyIds.map(shortId).join(', ')}` : '根节点'"
              >{{ node.toolName }} · {{ nodeStatusLabel(node.status) }} · {{ node.conditionType }} · r{{ node.revision }}</span>
            </div>
          </section>

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

          <section id="execution-timeline" class="detail-block">
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
                <div v-if="tool.attempts?.length" class="tool-attempts">
                  <span>执行尝试 · {{ tool.attempts.length }}</span>
                  <div v-for="attempt in tool.attempts" :key="attempt.id">
                    <b>#{{ attempt.attemptNo }}</b>
                    <i class="status-pill" :class="`status-${attempt.status.toLowerCase()}`">{{ attempt.status }}</i>
                    <span>{{ formatDuration(attempt.durationMs) }}</span>
                    <code v-if="attempt.errorCode">{{ attempt.errorCode }}</code>
                    <small v-if="attempt.retryDelayMs">等待 {{ attempt.retryDelayMs }} ms 后重试</small>
                  </div>
                </div>
                <details><summary>工具请求</summary><pre>{{ pretty(tool.requestPayload) }}</pre></details>
                <details><summary>工具结果</summary><pre>{{ pretty(tool.resultPayload) }}</pre></details>
              </div>
            </article>
          </section>

          <section v-if="selectedRun.citationDetails?.length" class="detail-block detail-sources">
            <h3>结构化引用</h3>
            <a
              v-for="citation in selectedRun.citationDetails"
              :key="`${citation.label}-${citation.url}`"
              :href="citation.url"
              target="_blank"
              rel="noreferrer"
            >{{ citation.label }} · {{ citation.project || 'GitHub Release' }} · {{ citation.heading || citation.title }}</a>
          </section>
          <section v-else-if="selectedRun.sources.length" class="detail-block detail-sources">
            <span class="eyebrow">Official Sources</span>
            <a v-for="source in selectedRun.sources" :key="source" :href="source" target="_blank" rel="noreferrer">{{ source }}</a>
          </section>
        </template>
      </aside>
    </div>
  </section>
</template>
