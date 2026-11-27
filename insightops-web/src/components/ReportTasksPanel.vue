<script setup lang="ts">
import { computed, nextTick, onMounted, ref, shallowRef } from 'vue'
import { batchName, displayTime, evidenceError, type BuildSummary } from '@/api/buildEvidence'
import type { SystemAttachment } from '@/api/businessSystems'
import type { EngineeringBaseline } from '@/api/engineeringBaselines'
import { executions, impacts, origins, type ReviewHistory, type Verification } from '@/api/reportReview'
import { getTasks, readinessLabels, type SavedTaskResult, type TaskHistory, type TaskResult, type TaskVersion } from '@/api/reportTasks'
import { taskSections } from '@/utils/taskFields'
import EngineeringBaselineFacts from './EngineeringBaselineFacts.vue'
import LocalExecutionPanel from './LocalExecutionPanel.vue'
import ReportTaskEditor from './ReportTaskEditor.vue'
import ReportTaskResultEditor from './ReportTaskResultEditor.vue'
import VerificationHandoffButton from './VerificationHandoffButton.vue'

const props = defineProps<{ reportId: string; title: string; answer: string; markdown: string; baseline?: EngineeringBaseline | null;
  history: ReviewHistory; builds: BuildSummary[]; uploads: SystemAttachment[]; uploadsReady: boolean; uploadError: string }>()
const emit = defineEmits<{ listed: [results: TaskResult[]]; refreshReview: []; refreshUploads: []; inspect: [string, string] }>()
const tasks = ref<TaskHistory>({ versions: [], results: [] })
const selectedId = ref('')
const ready = ref(false), notice = ref(''), error = ref('')
const section = ref<globalThis.HTMLElement>()
const draft = shallowRef<{ kind: 'task'; base?: TaskVersion; seed?: Verification } | { kind: 'result'; task: TaskVersion; base?: SavedTaskResult }>()
const versions = computed(() => [...tasks.value.versions].sort((a,b) => b.createdAt.localeCompare(a.createdAt) || b.version - a.version))
const selected = computed(() => versions.value.find(v => v.id === selectedId.value))
const verifications = computed(() => new Map(props.history.verifications.map(v => [v.id, v])))
const pairs = computed(() => {
  const map = new Map<string, SavedTaskResult>()
  for (const r of tasks.value.results) {
    const verification = verifications.value.get(r.verificationVersionId)
    if (verification) map.set(verification.id, { result: r, verification })
  }
  return map
})
const results = computed(() => tasks.value.results.filter(r => r.taskVersionId === selectedId.value)
  .sort((a, b) => b.createdAt.localeCompare(a.createdAt))
  .map(r => ({ ...r, verification: verifications.value.get(r.verificationVersionId) })))
const corrected = computed(() => new Map(props.history.verifications.filter(v => v.previousVersionId).map(v => [v.previousVersionId, v.id])))
const latestTask = (v?: TaskVersion) => v && versions.value.filter(t => t.taskId === v.taskId).sort((a, b) => b.version - a.version)[0]
function latestResult(base?: SavedTaskResult) {
  if (!base) return
  let id = base.verification.id
  while (corrected.value.has(id)) id = corrected.value.get(id)!
  return pairs.value.get(id)
}
let request = 0
async function refresh() {
  const current = ++request
  try {
    const data = await getTasks(props.reportId)
    if (request !== current) return
    tasks.value = data
    ready.value = true
    error.value = ''
    if (!selectedId.value) selectedId.value = versions.value[0]?.id ?? ''
    emit('listed', data.results)
  } catch (cause) {
    if (request === current) error.value = evidenceError(cause, '暂时无法读取本报告的任务和执行结果，请刷新任务重试。')
  }
}
onMounted(refresh)
function refreshEvidence() { void refresh(); emit('refreshReview'); emit('refreshUploads') }
function editTask(v?: TaskVersion, seed?: Verification) {
  if (draft.value) {
    notice.value = '先保存或取消当前草稿，再建立其他任务。'
    return
  }
  notice.value = ''
  draft.value = { kind: 'task', base: v, seed }
}
function editResult(v: TaskVersion, previous?: SavedTaskResult) {
  if (draft.value) {
    notice.value = '先保存或取消当前草稿，再打开其他结果。'
    return
  }
  notice.value = ''
  selectedId.value = v.id
  draft.value = { kind: 'result', task: v, base: previous }
}
function openResult(verificationId: string) {
  const p = pairs.value.get(verificationId)
  const t = p && versions.value.find(v => v.id === p.result.taskVersionId)
  if (t && p) {
    editResult(t, p)
    void nextTick(() => section.value?.querySelector('form')?.scrollIntoView?.({ block: 'start' }))
  }
}
function fromVerification(v: Verification) {
  if (ready.value && verifications.value.has(v.id)) editTask(undefined, v)
  void nextTick(() => section.value?.scrollIntoView?.({ block: 'start' }))
}
defineExpose({ refresh, openResult, fromVerification })
async function onTaskSaved(v: TaskVersion) {
  tasks.value.versions = [...tasks.value.versions.filter(x => x.id !== v.id), v]
  draft.value = undefined
  selectedId.value = v.id
  notice.value = '任务第 ' + v.version + ' 版已保存，可以导出执行包。'
  await refresh()
}
async function onResultSaved(r: TaskResult) {
  tasks.value.results = [...tasks.value.results.filter(v => v.verificationVersionId !== r.verificationVersionId), r]
  emit('listed', tasks.value.results)
  draft.value = undefined
  selectedId.value = r.taskVersionId
  notice.value = '执行结果已保存，已关联本次任务版本和验证版本。'
  await refresh()
  emit('refreshReview')
}
const baselineName = computed(() => props.baseline ? '工程基线 v' + props.baseline.baseline.version + ' · ' + props.baseline.baseline.label : '本报告未关联工程基线')
function evidenceName(id: string) {
  const v = verifications.value.get(id)
  return v ? v.fields.question + ' · 第 ' + v.version + ' 版' : '验证版本当前不可读'
}
function buildName(id: string) {
  const b = props.builds.find(item => item.id === id)
  return b ? batchName(b) : '标签待核对'
}
</script>

<template>
  <section ref="section" class="task-panel" aria-label="单项任务">
    <header><h4>单项任务</h4><button type="button" class="text-button" @click="refreshEvidence">刷新任务</button></header>
    <p v-if="error" role="alert" class="stream-error">{{ error }}</p>
    <p v-if="notice" role="status" class="success-notice">{{ notice }}</p>
    <template v-if="ready">
      <p v-if="!versions.length">选取原报告的一项建议或问题，写清允许的范围和完成条件，即可交给外部执行者。</p>
      <div class="task-actions">
        <button v-if="!draft" type="button" class="send-button" @click="editTask()">建立单项任务</button>
        <label v-if="versions.length">查看任务版本
          <select v-model="selectedId" :disabled="!!draft"><option v-for="v in versions" :key="v.id" :value="v.id">{{ v.fields.question }} · 第 {{ v.version }} 版 · {{ displayTime(v.createdAt) }}</option></select>
        </label>
      </div>
      <div v-if="draft || selected" class="task-source">
        <strong>来自原报告 · {{ title }}</strong>
        <span>档案修订 {{ history.context.systemRevision }} · {{ baselineName }}</span>
        <div><a :href="'/runs/' + encodeURIComponent(history.context.runId)">查看源运行</a> · <a :href="'/business-systems/' + encodeURIComponent(history.context.systemId)">查看系统档案</a></div>
        <details v-if="baseline"><summary>核对本报告采用的工程事实及原件</summary><EngineeringBaselineFacts :content="baseline.content" :saved="baseline.baseline" downloads /></details>
      </div>
      <ReportTaskEditor
        v-if="draft?.kind === 'task'" :report-id="reportId" :answer="answer" :markdown="markdown"
        :previous="draft.base" :newest="latestTask(draft.base)" :seed="draft.seed" :verifications="history.verifications" :uploads="uploads" :uploads-ready="uploadsReady" :upload-error="uploadError"
        @cancel="draft = undefined" @saved="onTaskSaved" @refresh="refreshEvidence" @refresh-uploads="emit('refreshUploads')"
      />
      <template v-if="selected && draft?.kind !== 'task'">
        <h5>{{ selected.fields.question }} · 任务第 {{ selected.version }} 版</h5>
        <p><b>{{ readinessLabels[selected.fields.readiness] }}</b> · {{ selected.author.name }} 编写或更正 · {{ displayTime(selected.createdAt) }}</p>
        <p v-if="selected.fields.missingMaterials" class="task-warning">待补材料 · {{ selected.fields.missingMaterials }}</p>
        <p>{{ origins[selected.fields.origin] }}<span v-if="selected.fields.originDetail"> · {{ selected.fields.originDetail }}</span> · 原报告第 {{ selected.excerptLine }} 行</p>
        <blockquote>{{ selected.fields.excerpt }}</blockquote>
        <details>
          <summary>查看本版任务范围、依据与完成条件</summary>
          <div v-for="group in taskSections" :key="group.title">
            <h6>{{ group.title }}</h6>
            <dl><template v-for="[key, name] in group.fields" :key="key"><dt>{{ name }}</dt><dd>{{ selected.fields[key] || '未填写' }}</dd></template></dl>
          </div>
          <p v-if="selected.fields.verificationVersionIds.length">依据的验证版本</p>
          <ul v-if="selected.fields.verificationVersionIds.length"><li v-for="id in selected.fields.verificationVersionIds" :key="id"><a :href="'#verification-' + id">{{ evidenceName(id) }}</a></li></ul>
          <p v-if="selected.fields.uploadIds.length">选用的补充资料</p>
          <ul v-if="selected.fields.uploadIds.length"><li v-for="id in selected.fields.uploadIds" :key="id"><a v-if="uploads.some(f => f.uploadId === id)" :href="'/api/v1/knowledge/uploads/' + encodeURIComponent(id) + '/content'" target="_blank" rel="noopener">{{ uploads.find(f => f.uploadId === id)?.name }}</a><span v-else>{{ uploadsReady ? '资料当前不可用' : '资料状态待刷新' }}</span></li></ul>
        </details>
        <div class="task-actions">
          <VerificationHandoffButton :key="selected.id" :report-id="reportId" :version-id="selected.id" :version="selected.version" execution label="导出执行包" />
          <button v-if="!draft" type="button" class="secondary-button" @click="editResult(selected)">登记执行结果</button>
          <button v-if="!draft && latestTask(selected)?.id === selected.id" type="button" class="text-button" @click="editTask(selected)">补充或更正任务</button>
        </div>
        <LocalExecutionPanel
          v-if="!draft && selected.engineeringBaselineId" :key="selected.id"
          :report-id="reportId" :task-version-id="selected.id" :frozen-task="selected.fields" @completed="refreshEvidence"
        />
        <ReportTaskResultEditor
          v-if="draft?.kind === 'result'" :report-id="reportId" :version="draft.task" :previous="draft.base" :newest="latestResult(draft.base)"
          :uploads="uploads" :uploads-ready="uploadsReady" :upload-error="uploadError" :builds="builds"
          @cancel="draft = undefined" @saved="onResultSaved" @refresh="refreshEvidence" @refresh-uploads="emit('refreshUploads')" @inspect="(a,b) => emit('inspect', a,b)"
        />
        <h5>本版任务的执行结果</h5>
        <p v-if="!results.length">还没有关联到这一版的执行结果。</p>
        <article v-for="r in results" :key="r.verificationVersionId" class="task-result">
          <template v-if="r.verification">
            <strong>验证第 {{ r.verification.version }} 版 · {{ executions[r.verification.fields.status] }} · {{ impacts[r.verification.fields.impact] }}</strong>
            <dl>
              <template v-if="r.verification.fields.status === 'EXECUTED'"><dt>实际执行者与时间</dt><dd>{{ r.verification.fields.executedBy }} · {{ displayTime(r.executedAt) }}</dd></template>
              <template v-if="r.sourceRevision || r.candidateRevision"><dt>记录者提供的版本说明</dt><dd>源码 · {{ r.sourceRevision || '未填写' }}<br />候选 · {{ r.candidateRevision || '未填写' }}</dd></template>
              <template v-if="r.unfinished"><dt>未完成项</dt><dd>{{ r.unfinished }}</dd></template>
              <template v-if="r.patch"><dt>补丁原件</dt><dd>{{ r.patch.name }} · {{ r.patch.size }} 字节<br />SHA-256 {{ r.patch.sha256 }}</dd></template>
              <template v-if="r.verification.fields.baselineBuildId && r.verification.fields.candidateBuildId">
                <dt>结果关联的构建</dt><dd>{{ buildName(r.verification.fields.baselineBuildId) }} → {{ buildName(r.verification.fields.candidateBuildId) }}</dd>
              </template>
            </dl>
            <p>{{ r.recordedBy.name }} 记录 · {{ displayTime(r.createdAt) }}</p>
            <div class="task-actions">
              <a :href="'#verification-' + r.verificationVersionId">查看本次验证的观察、依据及限制</a>
              <button v-if="!draft && !corrected.has(r.verificationVersionId)" type="button" class="text-button" @click="openResult(r.verificationVersionId)">补充或更正执行结果</button>
              <VerificationHandoffButton :report-id="reportId" :version-id="r.verificationVersionId" :version="r.verification.version" label="下载结果交接包" />
            </div>
          </template>
          <p v-else>执行结果已关联。刷新记录后可查看对应验证版本。</p>
        </article>
      </template>
    </template>
  </section>
</template>

<style scoped>
.task-panel { margin: 1rem 0; padding: 1rem; border: 1px solid var(--line); border-radius: .65rem; overflow-wrap: anywhere; min-width: 0 }
.task-panel header, .task-panel :deep(.task-actions) { display: flex; gap: .8rem; flex-wrap: wrap; align-items: center }
.task-panel header { justify-content: space-between }
.task-panel :deep(.task-actions) { margin-block: .6rem }
.task-panel h4, .task-panel :deep(h5), .task-panel :deep(h6) { font-size: 1rem; margin: .8rem 0 .4rem }
.task-panel :deep(label) { display: grid; gap: .3rem; min-width: 0 }
.task-panel :deep(input:not([type=checkbox])), .task-panel :deep(textarea), .task-panel :deep(select) { padding: .5rem; box-sizing: border-box; max-width: 100%; font: inherit; min-width: 0 }
.task-panel :deep(.task-form fieldset), .task-source { display: grid; gap: .7rem; border: 0; margin: .6rem 0; padding: 0; min-width: 0 }
.task-panel :deep(.task-form .task-section) { display: grid; gap: .65rem; grid-template-columns: repeat(auto-fit,minmax(min(100%,16rem), 1fr)) }
.task-panel :deep(.task-section h6) { grid-column: 1 / -1 }
.task-panel :deep(.task-check) { display: flex; gap: .45rem; align-items: baseline }
.task-panel :deep(.task-choices) { padding: .5rem; border: 1px solid var(--line); border-radius: .3rem }
.task-source, .task-warning, .task-panel :deep(.task-warning), .task-result { padding: .55rem .8rem; border-inline-start: 2px solid var(--accent); background: var(--panel-soft) }
.task-warning, .task-panel :deep(.task-warning) { border-color: var(--warning,#dfad62) }
.task-result { margin: .6rem 0 }
.task-panel :deep(dl) { display: grid; grid-template-columns: minmax(4em,auto) 1fr; gap: .4rem .8rem }
.task-panel :deep(dd), blockquote { white-space: pre-wrap; margin: 0 }
blockquote { border-inline-start: 2px solid var(--line); padding-inline-start: .75rem }
</style>
