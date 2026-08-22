<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'

import {
  activateReleaseCandidate,
  createEvaluationDataset,
  createReleaseCandidate,
  deriveEvaluationDataset,
  getAgentEvaluationOverview,
  startAgentEvaluation,
  type AgentEvaluationOverview,
  type EvaluationCaseInput,
  type EvaluationRun,
} from '@/api/agentEvaluations'

interface EditableCase {
  caseKey: string
  question: string
  expectedTools: string
  forbiddenTools: string
  requiredSourceDomains: string
  expectRecovery: boolean
  maxToolRounds: number
  maxDurationMs: number
  maxTokens: number
  maxCostCny: number
  required: boolean
}

const overview = ref<AgentEvaluationOverview | null>(null)
const loading = ref(false)
const saving = ref(false)
const error = ref('')
const notice = ref('')
const selectedDatasetId = ref('')
const selectedCandidateId = ref('')
const expandedRun = ref<EvaluationRun | null>(null)
let pollHandle: number | undefined

const datasetForm = reactive({
  name: 'agent-core-regression',
  description: '核心只读 Agent 任务、工具选择、恢复与引用回归集',
  gate: {
    minimumSuccessRate: 0.8,
    minimumToolAccuracy: 0.9,
    minimumRecoveryRate: 0.8,
    minimumCitationRate: 0.8,
    maxAverageDurationMs: 90_000,
    maxAverageTokens: 16_000,
    maxAverageCostCny: 0.5,
  },
  cases: [newCase()],
})

const candidateForm = reactive({
  name: 'Planner 候选版本',
  plannerPromptAppendix: '',
  modelName: '',
  temperature: 0,
  maxOutputTokens: 1024,
  basedOnId: null as string | null,
})

const deriveForm = reactive({
  datasetId: '',
  sourceRunId: '',
  caseKey: 'online-failure-001',
  expectedTools: '',
  forbiddenTools: 'user_memory_upsert',
  requiredSourceDomains: '',
})

const running = computed(() => overview.value?.governance.recentRuns.some(
  item => item.status === 'QUEUED' || item.status === 'RUNNING',
) ?? false)

function newCase(): EditableCase {
  return {
    caseKey: `agent-case-${Date.now().toString().slice(-6)}`,
    question: '',
    expectedTools: 'knowledge_hybrid_search',
    forbiddenTools: 'user_memory_upsert',
    requiredSourceDomains: '',
    expectRecovery: false,
    maxToolRounds: 6,
    maxDurationMs: 90_000,
    maxTokens: 16_000,
    maxCostCny: 0.5,
    required: true,
  }
}

function values(value: string): string[] {
  return value.split(',').map(item => item.trim()).filter(Boolean)
}

function caseInput(item: EditableCase): EvaluationCaseInput {
  return {
    ...item,
    expectedTools: values(item.expectedTools),
    forbiddenTools: values(item.forbiddenTools),
    requiredSourceDomains: values(item.requiredSourceDomains),
  }
}

async function load(silent = false) {
  if (!silent) loading.value = true
  if (!silent) error.value = ''
  try {
    const result = await getAgentEvaluationOverview()
    overview.value = result
    candidateForm.modelName ||= result.defaults.modelName
    candidateForm.temperature = candidateForm.temperature ?? result.defaults.temperature
    candidateForm.maxOutputTokens ||= result.defaults.maxOutputTokens
    selectedDatasetId.value ||= result.governance.datasets[0]?.id ?? ''
    selectedCandidateId.value ||= result.governance.candidates[0]?.id ?? ''
    deriveForm.datasetId ||= result.governance.datasets[0]?.id ?? ''
    if (expandedRun.value) {
      expandedRun.value = result.governance.recentRuns.find(
        item => item.id === expandedRun.value?.id,
      ) ?? expandedRun.value
    }
    schedulePoll()
  } catch {
    if (!silent) error.value = 'Agent 评测治理数据加载失败，请稍后重试。'
  } finally {
    if (!silent) loading.value = false
  }
}

function schedulePoll() {
  if (pollHandle) globalThis.clearTimeout(pollHandle)
  if (running.value) pollHandle = globalThis.setTimeout(() => load(true), 2500)
}

async function createDataset() {
  saving.value = true
  error.value = ''
  notice.value = ''
  try {
    const created = await createEvaluationDataset({
      name: datasetForm.name,
      description: datasetForm.description,
      gate: { ...datasetForm.gate },
      cases: datasetForm.cases.map(caseInput),
    })
    notice.value = `评测集 ${created.name} v${created.version} 已锁定。`
    selectedDatasetId.value = created.id
    await load(true)
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '评测集创建失败。'
  } finally {
    saving.value = false
  }
}

async function createCandidate() {
  saving.value = true
  error.value = ''
  notice.value = ''
  try {
    const created = await createReleaseCandidate({ ...candidateForm })
    notice.value = `候选版本 v${created.version} 已创建，等待评测。`
    selectedCandidateId.value = created.id
    await load(true)
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '候选版本创建失败。'
  } finally {
    saving.value = false
  }
}

async function startEvaluation() {
  if (!selectedDatasetId.value || !selectedCandidateId.value) return
  saving.value = true
  error.value = ''
  notice.value = ''
  try {
    const queued = await startAgentEvaluation(selectedDatasetId.value, selectedCandidateId.value)
    notice.value = `评测 Run ${queued.id.slice(0, 8)} 已进入队列。`
    expandedRun.value = queued
    await load(true)
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '评测启动失败。'
  } finally {
    saving.value = false
  }
}

async function activate(candidateId: string) {
  saving.value = true
  error.value = ''
  notice.value = ''
  try {
    const candidate = await activateReleaseCandidate(candidateId, 'Agent evaluation gate passed')
    notice.value = `候选版本 v${candidate.version} 已激活；后续新 Run 将使用该配置。`
    await load(true)
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '候选版本激活失败。'
  } finally {
    saving.value = false
  }
}

async function deriveFromRun() {
  if (!deriveForm.datasetId || !deriveForm.sourceRunId) return
  saving.value = true
  error.value = ''
  notice.value = ''
  try {
    const created = await deriveEvaluationDataset({
      datasetId: deriveForm.datasetId,
      sourceRunId: deriveForm.sourceRunId,
      evaluationCase: {
        caseKey: deriveForm.caseKey,
        question: '',
        expectedTools: values(deriveForm.expectedTools),
        forbiddenTools: values(deriveForm.forbiddenTools),
        requiredSourceDomains: values(deriveForm.requiredSourceDomains),
        expectRecovery: false,
        maxToolRounds: 6,
        maxDurationMs: 90_000,
        maxTokens: 16_000,
        maxCostCny: 0.5,
        required: true,
      },
    })
    notice.value = `线上 Run 已回流为 ${created.name} v${created.version}。`
    selectedDatasetId.value = created.id
    await load(true)
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '线上失败案例回流失败。'
  } finally {
    saving.value = false
  }
}

function percent(value: number | undefined): string {
  return value == null ? '—' : `${(value * 100).toFixed(1)}%`
}

function formatCost(value: number | undefined): string {
  return value == null ? '—' : `¥${Number(value).toFixed(6)}`
}

function formatDuration(value: number | undefined): string {
  return value == null ? '—' : `${(value / 1000).toFixed(2)} s`
}

function statusLabel(status: string): string {
  return ({ QUEUED: '排队中', RUNNING: '执行中', PASSED: '通过', FAILED: '失败',
    DRAFT: '草稿', ACTIVE: '生产中', RETIRED: '已退役' } as Record<string, string>)[status] ?? status
}

onMounted(load)
onBeforeUnmount(() => { if (pollHandle) globalThis.clearTimeout(pollHandle) })
</script>

<template>
  <section>
    <div class="section-heading runs-heading">
      <div><span class="eyebrow">P2.2 · Release Gate</span><h2>Agent 评测与发布治理</h2></div>
      <button class="secondary-button" :disabled="loading" @click="load()">刷新</button>
    </div>

    <div v-if="error" class="run-error">{{ error }}</div>
    <div v-if="notice" class="success-banner">{{ notice }}</div>

    <template v-if="overview">
      <div class="evaluation-summary">
        <article><span>评测集版本</span><strong>{{ overview.governance.datasets.length }}</strong></article>
        <article><span>候选版本</span><strong>{{ overview.governance.candidates.length }}</strong></article>
        <article><span>评测 Run</span><strong>{{ overview.governance.recentRuns.length }}</strong></article>
        <article><span>当前生产</span><strong>{{ overview.governance.activeProfile ? `v${overview.governance.activeProfile.version}` : '默认配置' }}</strong><small>{{ overview.governance.activeProfile?.modelName || overview.defaults.modelName }}</small></article>
      </div>

      <div class="evaluation-grid">
        <article class="form-panel">
          <div class="detail-block-heading"><div><span class="eyebrow">P2.2-A</span><h3>不可变评测集</h3></div><small>1～100 题</small></div>
          <form class="evaluation-form" @submit.prevent="createDataset">
            <label><span>名称</span><input v-model="datasetForm.name" maxlength="128" required /></label>
            <label><span>说明</span><textarea v-model="datasetForm.description" maxlength="1000" /></label>
            <div class="gate-grid">
              <label><span>成功率门禁</span><input v-model.number="datasetForm.gate.minimumSuccessRate" type="number" min="0" max="1" step="0.01" /></label>
              <label><span>工具准确率</span><input v-model.number="datasetForm.gate.minimumToolAccuracy" type="number" min="0" max="1" step="0.01" /></label>
              <label><span>恢复率</span><input v-model.number="datasetForm.gate.minimumRecoveryRate" type="number" min="0" max="1" step="0.01" /></label>
              <label><span>引用率</span><input v-model.number="datasetForm.gate.minimumCitationRate" type="number" min="0" max="1" step="0.01" /></label>
            </div>
            <div v-for="(item, index) in datasetForm.cases" :key="index" class="case-editor">
              <div class="case-heading"><strong>案例 {{ index + 1 }}</strong><button v-if="datasetForm.cases.length > 1" type="button" class="text-button" @click="datasetForm.cases.splice(index, 1)">移除</button></div>
              <label><span>Case Key</span><input v-model="item.caseKey" required /></label>
              <label><span>用户问题</span><textarea v-model="item.question" required /></label>
              <label><span>预期工具（逗号分隔）</span><input v-model="item.expectedTools" /></label>
              <label><span>禁止工具</span><input v-model="item.forbiddenTools" /></label>
              <label><span>必须引用域名</span><input v-model="item.requiredSourceDomains" placeholder="github.com,spring.io" /></label>
              <div class="checkbox-row"><label><input v-model="item.expectRecovery" type="checkbox" />要求观察到恢复</label><label><input v-model="item.required" type="checkbox" />必过案例</label></div>
            </div>
            <div class="form-actions"><button type="button" class="secondary-button" @click="datasetForm.cases.push(newCase())">增加案例</button><button class="primary-button" :disabled="saving">创建并锁定</button></div>
          </form>
        </article>

        <article class="form-panel">
          <div class="detail-block-heading"><div><span class="eyebrow">P2.2-B</span><h3>Prompt / 模型候选</h3></div><small>工具合同 {{ overview.defaults.toolContractHash.slice(0, 8) }}</small></div>
          <form class="evaluation-form" @submit.prevent="createCandidate">
            <label><span>候选名称</span><input v-model="candidateForm.name" required /></label>
            <label><span>模型</span><input v-model="candidateForm.modelName" required /></label>
            <div class="gate-grid"><label><span>Temperature</span><input v-model.number="candidateForm.temperature" type="number" min="0" max="2" step="0.1" /></label><label><span>最大输出 Token</span><input v-model.number="candidateForm.maxOutputTokens" type="number" min="1" max="8192" /></label></div>
            <label><span>Planner 策略附录</span><textarea v-model="candidateForm.plannerPromptAppendix" rows="7" placeholder="只填写需要追加的受控策略；基础安全 Prompt 不会被覆盖。" /></label>
            <button class="primary-button" :disabled="saving">创建候选版本</button>
          </form>

          <div class="candidate-list">
            <div v-for="candidate in overview.governance.candidates" :key="candidate.id" class="candidate-row">
              <div><strong>v{{ candidate.version }} · {{ candidate.name }}</strong><small>{{ candidate.modelName }} · {{ candidate.maxOutputTokens }} Token</small></div>
              <div><i class="status-pill" :class="`status-${candidate.status.toLowerCase()}`">{{ statusLabel(candidate.status) }}</i><button v-if="candidate.status === 'PASSED' || candidate.status === 'RETIRED'" class="text-button" :disabled="saving" @click="activate(candidate.id)">激活</button></div>
            </div>
          </div>
        </article>
      </div>

      <article class="run-table-panel evaluation-run-panel">
        <div class="detail-block-heading"><div><span class="eyebrow">P2.2-C</span><h3>运行、基线和趋势</h3></div><small>{{ running ? '实时轮询中' : '当前无运行任务' }}</small></div>
        <div class="evaluation-launch">
          <select v-model="selectedDatasetId"><option value="" disabled>选择评测集</option><option v-for="dataset in overview.governance.datasets" :key="dataset.id" :value="dataset.id">{{ dataset.name }} v{{ dataset.version }} · {{ dataset.cases.length }} 题</option></select>
          <select v-model="selectedCandidateId"><option value="" disabled>选择候选版本</option><option v-for="candidate in overview.governance.candidates" :key="candidate.id" :value="candidate.id">v{{ candidate.version }} · {{ candidate.name }}</option></select>
          <button class="primary-button" :disabled="saving || !selectedDatasetId || !selectedCandidateId" @click="startEvaluation">开始评测</button>
        </div>
        <div v-if="overview.governance.recentRuns.length === 0" class="detail-placeholder">尚无 Agent 评测 Run。</div>
        <button v-for="run in overview.governance.recentRuns" :key="run.id" class="evaluation-run-row" @click="expandedRun = run">
          <i class="status-pill" :class="`status-${run.status.toLowerCase()}`">{{ statusLabel(run.status) }}</i>
          <div><strong>{{ run.datasetName }} v{{ run.datasetVersion }} → 候选 v{{ run.candidateVersion }}</strong><small>Run {{ run.id.slice(0, 8) }} · {{ run.failureCode || `${run.results.length}/${run.summary?.caseCount || '…'} 案例` }}</small></div>
          <span>{{ percent(run.summary?.successRate) }}<small>成功率</small></span>
          <span>{{ percent(run.summary?.toolAccuracy) }}<small>工具准确率</small></span>
          <span>{{ formatDuration(run.summary?.averageDurationMs) }}<small>平均耗时</small></span>
          <span>{{ formatCost(run.summary?.averageCostCny) }}<small>平均费用</small></span>
        </button>
      </article>

      <article v-if="expandedRun" class="run-table-panel evaluation-detail">
        <div class="detail-block-heading"><div><span class="eyebrow">Run {{ expandedRun.id.slice(0, 8) }}</span><h3>逐案例 Trace 断言</h3></div><button class="text-button" @click="expandedRun = null">关闭</button></div>
        <div v-if="expandedRun.baselineSummary" class="baseline-note">生产基线：成功率 {{ percent(expandedRun.baselineSummary.successRate) }} · 工具准确率 {{ percent(expandedRun.baselineSummary.toolAccuracy) }} · 平均费用 {{ formatCost(expandedRun.baselineSummary.averageCostCny) }}</div>
        <div v-for="item in expandedRun.results" :key="item.id" class="case-result-row">
          <i class="status-pill" :class="`status-${item.status.toLowerCase()}`">{{ statusLabel(item.status) }}</i>
          <div><strong>{{ item.caseKey }}</strong><small>{{ item.question }}</small><small>工具：{{ item.actualTools.join(', ') || '未调用' }} · 缺失：{{ item.missingTools.join(', ') || '无' }} · {{ item.failureCode || '无错误码' }}</small></div>
          <RouterLink v-if="item.agentRunId" :to="`/runs/${item.agentRunId}`">查看 Trace</RouterLink>
        </div>
      </article>

      <article class="form-panel derive-panel">
        <div class="detail-block-heading"><div><span class="eyebrow">Feedback Loop</span><h3>线上失败 Run 回流</h3></div><small>创建同名不可变新版本</small></div>
        <form class="derive-form" @submit.prevent="deriveFromRun">
          <select v-model="deriveForm.datasetId" required><option value="" disabled>基础评测集</option><option v-for="dataset in overview.governance.datasets" :key="dataset.id" :value="dataset.id">{{ dataset.name }} v{{ dataset.version }}</option></select>
          <input v-model="deriveForm.sourceRunId" required placeholder="失败 Agent Run UUID" />
          <input v-model="deriveForm.caseKey" required placeholder="Case Key" />
          <input v-model="deriveForm.expectedTools" placeholder="预期工具，逗号分隔" />
          <input v-model="deriveForm.requiredSourceDomains" placeholder="必须引用域名" />
          <button class="secondary-button" :disabled="saving">回流为新版本</button>
        </form>
      </article>
    </template>
    <div v-else-if="loading" class="run-loading">正在读取 Agent 评测集、候选版本与基线…</div>
  </section>
</template>

<style scoped>
.evaluation-summary { display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:16px;margin-bottom:20px }
.evaluation-summary article { padding:20px;border:1px solid var(--line);border-radius:18px;background:var(--panel) }
.evaluation-summary span,.evaluation-summary small { display:block;color:var(--muted) }
.evaluation-summary strong { display:block;font-size:24px;margin:8px 0 }
.evaluation-grid { display:grid;grid-template-columns:1.15fr .85fr;gap:20px;margin-bottom:20px }
.evaluation-form { display:grid;gap:12px }
.evaluation-form label { display:grid;gap:6px }
.evaluation-form input,.evaluation-form textarea,.evaluation-launch select,.derive-form input,.derive-form select { width:100%;box-sizing:border-box }
.gate-grid { display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:10px }
.case-editor { border:1px solid var(--line);border-radius:14px;padding:14px;display:grid;gap:10px }
.case-heading,.form-actions,.checkbox-row,.candidate-row { display:flex;justify-content:space-between;gap:12px;align-items:center }
.checkbox-row label { display:flex;grid-auto-flow:column;justify-content:start;align-items:center }
.candidate-list { display:grid;gap:10px;margin-top:18px }
.candidate-row { border-top:1px solid var(--line);padding-top:12px }
.candidate-row small { display:block;color:var(--muted);margin-top:4px }
.evaluation-run-panel,.evaluation-detail,.derive-panel { margin-top:20px }
.evaluation-launch,.derive-form { display:grid;grid-template-columns:1fr 1fr auto;gap:12px;margin-bottom:16px }
.derive-form { grid-template-columns:1fr 1.4fr 1fr 1fr auto }
.evaluation-run-row { width:100%;display:grid;grid-template-columns:auto 2fr repeat(4,minmax(90px,.7fr));gap:14px;align-items:center;text-align:left;background:transparent;border:0;border-top:1px solid var(--line);padding:14px 0;color:inherit }
.evaluation-run-row small,.case-result-row small { display:block;color:var(--muted);margin-top:4px }
.case-result-row { display:grid;grid-template-columns:auto 1fr auto;gap:14px;align-items:start;border-top:1px solid var(--line);padding:14px 0 }
.baseline-note { padding:12px;border-radius:12px;background:rgba(74,222,128,.08);margin-bottom:10px }
@media (max-width:1100px) { .evaluation-grid{grid-template-columns:1fr}.evaluation-summary{grid-template-columns:repeat(2,1fr)}.evaluation-run-row{grid-template-columns:auto 1fr}.evaluation-run-row>span{display:none}.derive-form,.evaluation-launch{grid-template-columns:1fr} }
</style>
