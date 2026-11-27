<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { displayTime, evidenceError } from '@/api/buildEvidence'
import type { TaskFields } from '@/api/reportTasks'
import { cancelLocalExecution, fetchLocalEvidence, fetchLocalExecution, fetchLocalPatch, localExecutionOverview,
  startLocalExecution, type LocalExecutionDetail, type LocalOverview, type LocalRun, type LocalRunEvent,
  type LocalRunStatus, type RegressionComparison } from '@/api/localExecution'

const props = defineProps<{ reportId: string; taskVersionId: string; frozenTask: TaskFields }>()
const emit = defineEmits<{ completed: [] }>()
const statusLabels: Record<LocalRunStatus, string> = {
  QUEUED: '已受理', ANALYZING: '分析工程', BASELINE: '验证原始版本', EDITING: '试改与核对', TESTING: '测试候选版本',
  SUCCEEDED: '候选测试通过', FAILED: '候选未通过', BLOCKED: '执行受阻', CANCELLED: '已取消', INTERRUPTED: '执行中断',
}
const tools: Record<string, string> = {
  list_files: '列出项目文件', search_code: '搜索代码', read_file: '读取文件', write_file: '实际写入文件',
  write_files: '成组写入文件', run_maven_tests: '运行 Maven',
}
const overview = ref<LocalOverview>()
const current = ref<LocalExecutionDetail>()
const selectedRun = ref('')
const selectedWorkspace = ref('')
const selectedModel = ref('')
const frozen = props.frozenTask
const instruction = ref([frozen.question, frozen.inScope, frozen.deliverable, frozen.completion].filter(Boolean).join('\n').slice(0, 6000))
const listedPaths = frozen.inScope.match(/(?:src\/(?:main|test)\/[\w.$/-]*|\bpom\.xml\b|\bREADME\.md\b)/g) ?? []
const scopedPaths = listedPaths.filter(path => !path.includes('..')).map(path =>
  path.endsWith('/') || path.split('/').at(-1)?.includes('.') ? path : path + '/')
const scopes = ref([...new Set(scopedPaths)].join('\n'))
const selectedClasses = ref(false)
const classNames = ref('')
const tests = computed(() => [...new Set(classNames.value.trim().split(/[\s,，]+/).filter(Boolean))])
const validTests = computed(() => !selectedClasses.value || (tests.value.length > 0 && tests.value.length <= 12
  && tests.value.every(name => name.length <= 160 && /^[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*$/.test(name))))
const patch = ref('')
const error = ref('')
const busy = ref(false)
const downloadBusy = ref(false)
const workspace = computed(() => overview.value?.workspaces.find(item => item.id === selectedWorkspace.value))
const canStart = computed(() => overview.value?.taskReady && workspace.value && selectedModel.value &&
  instruction.value.trim() && scopes.value.trim() && validTests.value && !overview.value.runs.some(run => !run.finishedAt))
const builds = computed(() => current.value?.events.filter(event => event.type === 'BUILD') ?? [])
const plan = computed(() => current.value?.events.find(event => event.type === 'PLAN'))
const regression = computed(() => current.value?.events.find(event => event.type === 'REGRESSION')?.details.comparison as RegressionComparison | undefined)
const writes = computed(() => current.value?.events.filter(event => event.type === 'TOOL_RESULT' && ['write_file', 'write_files'].includes(event.message)) ?? [])
let timer: ReturnType<typeof globalThis.setTimeout> | undefined
let disposed = false
let selection = 0
let lastAttempt: { key: string; id: string } | undefined
const announced = new Set<string>()

onMounted(load)
onUnmounted(() => { disposed = true; globalThis.clearTimeout(timer) })

async function load() {
  try {
    const information = await localExecutionOverview(props.reportId, props.taskVersionId)
    if (disposed) return
    overview.value = information
    selectedWorkspace.value = information.workspaces[0]?.id ?? ''
    selectedModel.value = information.models.find(model => model.id === 'gpt-6.2-astra')?.id ?? information.models[0]?.id ?? ''
    if (information.runs.length) await inspect(information.runs[0]!.id)
  } catch (cause) {
    if (!disposed) error.value = evidenceError(cause, '无法读取本地工程运行。')
  }
}

function remember(run: LocalRun) {
  if (!overview.value) return
  overview.value.runs = [run, ...overview.value.runs.filter(item => item.id !== run.id)]
    .sort((a, b) => b.createdAt.localeCompare(a.createdAt))
}

async function inspect(id: string) {
  const token = ++selection
  globalThis.clearTimeout(timer)
  selectedRun.value = id
  current.value = undefined
  patch.value = ''
  error.value = ''
  if (id) await poll(id, token)
}

async function poll(id: string, token: number) {
  try {
    const detail = await fetchLocalExecution(props.reportId, props.taskVersionId, id)
    if (disposed || token !== selection) return
    current.value = detail
    remember(detail.run)
    if (detail.run.finishedAt) {
      if (detail.run.verificationId && !announced.has(id)) {
        announced.add(id)
        emit('completed')
      }
      return
    }
  } catch (cause) {
    if (disposed || token !== selection) return
    error.value = evidenceError(cause, '暂时无法刷新本次执行。')
  }
  timer = globalThis.setTimeout(() => void poll(id, token), 1600)
}

async function start() {
  if (!canStart.value || !workspace.value || busy.value) return
  const data = { workspace: workspace.value.id, sourceRevision: workspace.value.revision, modelChoice: selectedModel.value,
    instruction: instruction.value.trim(), writeScopes: scopes.value.split(/\r?\n/).map(item => item.trim()).filter(Boolean),
    ...(selectedClasses.value ? { buildPlan: { mode: 'SELECTED_CLASSES' as const, testClasses: tests.value } } : {}) }
  const key = JSON.stringify(data)
  if (lastAttempt?.key !== key) lastAttempt = { key, id: globalThis.crypto.randomUUID() }
  error.value = ''
  busy.value = true
  try {
    const run = await startLocalExecution(props.reportId, props.taskVersionId, { ...data, requestId: lastAttempt.id })
    if (disposed) return
    lastAttempt = undefined
    remember(run)
    await inspect(run.id)
  } catch (cause) {
    if (!disposed) error.value = evidenceError(cause, '本地试改未能启动。')
  } finally { busy.value = false }
}

async function cancel() {
  const id = current.value?.run.id
  if (!id || busy.value) return
  busy.value = true
  try {
    const run = await cancelLocalExecution(props.reportId, props.taskVersionId, id)
    if (current.value?.run.id === id) current.value.run = run
    remember(run)
  } catch (cause) {
    error.value = evidenceError(cause, '暂时无法发送取消请求。')
  } finally { busy.value = false }
}

async function showPatch() {
  const id = current.value?.run.id
  if (!id) return
  try {
    const content = await fetchLocalPatch(props.reportId, props.taskVersionId, id)
    if (current.value?.run.id === id) patch.value = content
  } catch (cause) { error.value = evidenceError(cause, '暂时无法查看补丁。') }
}

async function download() {
  const id = current.value?.run.id
  if (!id || downloadBusy.value) return
  downloadBusy.value = true
  try {
    const file = await fetchLocalEvidence(props.reportId, props.taskVersionId, id)
    const url = globalThis.URL.createObjectURL(file)
    const link = globalThis.document.createElement('a')
    link.href = url
    link.download = `InsightOps-本地运行-${id}.zip`
    link.click()
    globalThis.setTimeout(() => globalThis.URL.revokeObjectURL(url), 30_000)
  } catch (cause) {
    error.value = evidenceError(cause, '暂时无法下载执行原件。')
  } finally { downloadBusy.value = false }
}

function buildSummary(event: LocalRunEvent) {
  const { exitCode, counts, issue } = event.details
  const c = counts as { passed: number; failures: number; errors: number; skipped: number } | null
  return `退出码 ${exitCode ?? '未取得'}${c ? `，${c.passed} 通过 / ${c.failures} 失败 / ${c.errors} 错误 / ${c.skipped} 跳过` : '，无可解析测试计数'}${issue ? `。${issue}` : ''}`
}

function written(event: LocalRunEvent) {
  type Receipt = { path: string; created: boolean }
  const output = event.details.output as { ok?: boolean; result?: Receipt | Receipt[]; error?: string }
  if (!output?.ok || !output.result) return `写入未完成 · ${output?.error ?? ''}`
  const receipts = Array.isArray(output.result) ? output.result : [output.result]
  return receipts.map(item => `${item.created ? '新增' : '修改'} ${item.path}`).join('、')
}
</script>

<template>
  <p v-if="!overview && error" class="stream-error" role="alert">{{ error }}</p>
  <section v-if="overview?.enabled" aria-label="本地工程执行" class="local-execution">
    <header><h5>本地工程执行</h5></header>
    <p v-if="!overview.taskReady">任务就绪且关联冻结的工程基线后，可从指定 Git 提交本地试改。</p>
    <p v-else-if="!overview.workspaces.length">此工作区尚未登记可用的 Maven 仓库。请核对工作区登记目录和仓库根 POM。</p>
    <p v-else-if="!overview.models.length">当前没有已启用的执行模型。</p>
    <details v-else :open="!selectedRun" class="start-form">
      <summary>发起本地试改</summary>
      <form @submit.prevent="start">
        <div class="fields">
          <label>本地仓库 <select v-model="selectedWorkspace"><option v-for="item in overview.workspaces" :key="item.id" :value="item.id">{{ item.id }}</option></select></label>
          <label>执行模型 <select v-model="selectedModel"><option v-for="item in overview.models" :key="item.id" :value="item.id">{{ item.label }} · {{ item.provider }}</option></select></label>
        </div>
        <p v-if="workspace" class="revision">Git 起点 <code>{{ workspace.revision }}</code></p>
        <label>本地改动指令 <textarea v-model="instruction" :maxlength="6000" rows="4" required placeholder="说明要改变的行为、要保留的行为和需要验证的结果。" /></label>
        <label>允许写入的路径 <textarea v-model="scopes" rows="3" required aria-describedby="local-execution-path-help" placeholder="src/main/java/模块目录/&#10;src/test/java/模块目录/" /></label>
        <p id="local-execution-path-help" class="hint">{{ scopedPaths.length ? '已从冻结任务范围提取路径，请核对。' : '冻结任务范围未列出具体路径，请填写本次授权的文件或目录。' }}每行一个，目录以 / 结尾。系统将在首次模型调用前执行原版本测试。</p>
        <label>构建方案 <select v-model="selectedClasses"><option :value="false">Maven test · 默认测试范围</option><option :value="true">Maven test · 指定测试类</option></select></label>
        <label v-if="selectedClasses">测试类名 <textarea v-model="classNames" rows="2" placeholder="AmountRuleTest, AmountRuleRegressionTest" required /></label>
        <p v-if="selectedClasses" class="hint">输入 1 至 12 个类名，可用逗号或换行分隔。请包含已有测试；计划新增的回归类也需列入。三次对照使用相同选择器。</p>
        <button type="submit" class="send-button" :disabled="busy || !canStart">开始本地试改</button>
      </form>
    </details>

    <p v-if="error" class="stream-error" role="alert">{{ error }}</p>
    <label v-if="overview.runs.length">本地运行记录
      <select :value="selectedRun" @change="inspect(($event.target as HTMLSelectElement).value)">
        <option v-for="item in overview.runs" :key="item.id" :value="item.id">{{ statusLabels[item.status] }} · {{ item.input.workspace }} · {{ displayTime(item.createdAt) }}</option>
      </select>
    </label>
    <article v-if="current" class="execution-detail">
      <div role="status" aria-live="polite"><strong>{{ statusLabels[current.run.status] }}</strong><span v-if="current.run.cancelRequested && !current.run.finishedAt"> · 已请求取消</span><p>{{ current.run.note }}</p></div>
      <p class="revision">任务基线 v{{ current.run.baselineVersion }} · {{ current.run.input.modelChoice }} · <code>{{ current.run.input.sourceRevision }}</code></p>
      <button v-if="!current.run.finishedAt" type="button" class="secondary-button" :disabled="busy || current.run.cancelRequested" @click="cancel">取消本地执行</button>
      <details v-if="plan"><summary>查看工程分析和试改计划</summary><pre>{{ plan.details.text }}</pre></details>
      <ul v-if="writes.length" aria-label="实际文件改动"><li v-for="event in writes" :key="event.sequence">{{ written(event) }}</li></ul>
      <ul v-if="builds.length" aria-label="实际构建结果">
        <li v-for="event in builds" :key="event.sequence">
          <strong>{{ event.message }}</strong> · {{ buildSummary(event) }}
          <code v-if="Array.isArray(event.details.command)">{{ event.details.command.join(' ') }}</code>
        </li>
      </ul>
      <section v-if="regression" aria-label="回归用例对照">
        <h6>回归用例对照</h6>
        <p>原回归通过 {{ regression.baselinePassingOnCandidate }} 项，基线实际执行 {{ regression.baselineExecuted }} 项。</p>
        <p>新增用例在旧实现断言失败、在候选通过 {{ regression.failedThenPassed }} 项。另有 {{ regression.undistinguished }} 组无法区分。</p>
        <p v-if="regression.limitation">{{ regression.limitation }}</p>
        <p v-if="regression.unobservedTestSources?.length">未在测试 XML 定位到的新增 Java 源文件：{{ regression.unobservedTestSources.join('、') }}</p>
        <details v-if="regression.cases.length">
          <summary>查看逐项匹配</summary>
          <ul>
            <li v-for="row in regression.cases" :key="JSON.stringify([row.suite, row.className, row.name])">
              {{ row.className }} · {{ row.name }} · {{ row.outcome === 'FAILURE_TO_PASS' ? '旧失败 → 新通过' : row.reason }}
            </li>
          </ul>
        </details>
      </section>
      <details v-if="current.events.length">
        <summary>查看 {{ current.events.length }} 条模型与执行记录</summary>
        <details v-for="event in current.events" :key="event.sequence" class="event"><summary>{{ tools[event.message] ?? event.message }} · {{ event.type }} · {{ displayTime(event.createdAt) }}</summary><pre>{{ JSON.stringify(event.details, null, 2) }}</pre></details>
      </details>
      <div v-if="current.run.finishedAt" class="actions">
        <button v-if="current.run.patchSha256" type="button" class="secondary-button" @click="showPatch">查看实际补丁</button>
        <button type="button" class="secondary-button" :disabled="downloadBusy" @click="download">下载本地运行原件</button>
        <a v-if="current.run.verificationId" :href="'#verification-' + current.run.verificationId">查看关联验证</a>
      </div>
      <pre v-if="patch" aria-label="实际补丁">{{ patch }}</pre>
    </article>
  </section>
</template>

<style scoped>
.local-execution { display: grid; gap: .75rem; padding: 1rem; border: 1px solid var(--border, #35484a); border-radius: .75rem; }
.local-execution h5, .local-execution p { margin: 0; }
.start-form form, .execution-detail { display: grid; gap: .65rem; padding-top: .7rem; }
.fields { display: grid; grid-template-columns: repeat(auto-fit, minmax(12rem, 1fr)); gap: .75rem; }
label { display: grid; gap: .35rem; }
textarea { resize: vertical; }
.hint, .revision { font-size: .875rem; overflow-wrap: anywhere; }
.actions { display: flex; flex-wrap: wrap; gap: .5rem; align-items: center; }
summary { cursor: pointer; }
.event { margin: .4rem 0 0 .75rem; }
pre { padding: .6rem; overflow: auto; max-height: 22rem; background: var(--surface, #111d20); white-space: pre-wrap; overflow-wrap: anywhere; }
ul { margin: 0; padding-inline-start: 1.25rem; }
.send-button, .secondary-button { justify-self: start; }
</style>
