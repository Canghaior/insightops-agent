<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import MarkdownContent from './MarkdownContent.vue'
import BuildEvidencePanel from './BuildEvidencePanel.vue'
import ReportTasksPanel from './ReportTasksPanel.vue'
import type { EngineeringBaseline } from '@/api/engineeringBaselines'
import type { TaskResult } from '@/api/reportTasks'
import VerificationHandoffButton from './VerificationHandoffButton.vue'
import { batchName, type BuildSummary } from '@/api/buildEvidence'
import type { BuildComparison } from '@/api/buildComparison'
import { availableSystemUploads, type SystemAttachment } from '@/api/businessSystems'
import { getReview, saveDecision, saveVerification, executions, impacts, origins,
  type DecisionInput, type ReviewHistory, type Verification, type VerificationInput } from '@/api/reportReview'

const props = defineProps<{ reportId: string; originalAnswer?: string; reportTitle?: string; markdown?: string;
  baseline?: EngineeringBaseline | null; sources?: string[] }>()
const history = ref<ReviewHistory>()
const uploads = ref<SystemAttachment[]>([])
const uploadsReady = ref(false)
const error = ref(''), uploadError = ref(''), notice = ref(''), draftWarning = ref('')
const form = ref<'verification' | 'decision' | null>(null)
const editor = ref<globalThis.HTMLFormElement>()
const buildPanel = ref<InstanceType<typeof BuildEvidencePanel>>()
const taskPanel = ref<InstanceType<typeof ReportTasksPanel>>()
const builds = ref<BuildSummary[]>([])
const taskResults = ref<TaskResult[]>()
const taskVerificationIds = computed(() => new Set(taskResults.value?.map(r => r.verificationVersionId)))
const buildNames = ref(new Map<string, string>())
const comparisonTarget = ref('')
const draftSummary = ref('')
const busy = ref(false)
const previous = ref<Verification | null>(null)
const fresh = (): VerificationInput => ({ question: '', suggestionRef: '', origin: 'MODEL', status: 'NOT_EXECUTED',
  method: '', environment: '', observation: '', impact: 'INCONCLUSIVE', limits: '', blocker: '', uploadIds: [] })
const verification = ref(fresh())
const decision = ref<DecisionInput>({ choice: '', reason: '', reconsiderWhen: '', verificationVersionIds: [] })
const versions = computed(() => new Map(history.value?.verifications.map(item => [item.id, item])))
const corrected = computed(() => new Set(history.value?.verifications.map(item => item.previousVersionId)))
const files = computed(() => new Map(uploads.value.map(item => [item.uploadId, item.name])))
const knownUploadNames = new Map<string, string>()
const unavailableIds = computed(() => form.value === 'verification' && uploadsReady.value
  ? verification.value.uploadIds.filter(id => !files.value.has(id)) : [])
const unavailableNames = computed(() => unavailableIds.value.flatMap(id => knownUploadNames.get(id) ?? []))
const orderedVersions = computed(() => [...(history.value?.verifications ?? [])].reverse())
const correctableVersions = computed(() => taskResults.value ? orderedVersions.value.filter(item => !corrected.value.has(item.id) && !taskVerificationIds.value.has(item.id)) : [])
const orderedDecisions = computed(() => [...(history.value?.decisions ?? [])].reverse())
const stamp = (date: string) => new Date(date).toLocaleString('zh-CN', { hour12: false })
const lastAttempt = ref<{ payload: string; id: string; unconfirmed: boolean } | null>(null)
const submissionKey = computed(() => JSON.stringify(form.value === 'verification'
  ? ['record', previous.value?.id, verification.value] : ['decision', decision.value]))
const retryingUnconfirmed = computed(() => lastAttempt.value?.unconfirmed && lastAttempt.value.payload === submissionKey.value)
let historyRequest = 0
let uploadRequest = 0

function message(e: unknown) {
  const http = e as { response?: { status?: number; data?: { detail?: string; message?: string } } }
  const detail = http.response?.data?.detail || http.response?.data?.message
  if (detail) return detail
  if (http.response?.status === 409) return '提交发生冲突，请刷新记录并核对已保存的版本。'
  return '操作未确认，请重试或刷新记录。'
}
async function load() {
  const request = ++historyRequest
  error.value = ''
  try {
    const result = await getReview(props.reportId)
    if (request === historyRequest) history.value = result
  } catch (e) { if (request === historyRequest) error.value = message(e) }
}
async function loadUploads() {
  const request = ++uploadRequest
  try {
    const result = await availableSystemUploads()
    if (request !== uploadRequest) return
    for (const file of result) {
      if (file.name) knownUploadNames.set(file.uploadId, file.name)
    }
    uploads.value = result
    uploadsReady.value = true
    uploadError.value = ''
  } catch {
    if (request !== uploadRequest) return
    uploads.value = []
    uploadsReady.value = false
    uploadError.value = '暂时无法读取可关联资料。草稿保留已选资料，可重读资料或继续填写文字。'
  }
}
function refresh() { void load(); void loadUploads(); void taskPanel.value?.refresh() }
onMounted(refresh)
function openVerification(parent: Verification | null = null) {
  form.value = 'verification'; previous.value = parent; draftWarning.value = ''; notice.value = ''; error.value = ''; lastAttempt.value = null
  verification.value = parent ? { ...parent.fields, uploadIds: [...parent.fields.uploadIds] } : fresh()
  draftSummary.value = parent?.buildSummary ?? ''
}
function rememberBuilds(batches: BuildSummary[]) {
  builds.value = batches
  buildNames.value = new Map(batches.map(batch => [batch.id, batchName(batch)]))
}
function inspectBuilds(fields: VerificationInput) {
  if (fields.baselineBuildId && fields.candidateBuildId) void buildPanel.value?.inspect(fields.baselineBuildId, fields.candidateBuildId)
}
async function applyComparison(result: BuildComparison) {
  if (busy.value || retryingUnconfirmed.value || form.value === 'decision') return
  if (!form.value) {
    const target = correctableVersions.value.find(item => item.id === comparisonTarget.value)
    if (comparisonTarget.value && !target) { error.value = '这个版本已有更正。请重新选择要补充的版本。'; return }
    openVerification(target ?? null)
    if (!target) {
      verification.value.origin = 'HUMAN_OR_TOOL'
      verification.value.question = '两次构建的测试结果对本次改动有何影响？'
      verification.value.method = '核对两次构建的测试身份、状态变化、异常和对应原件。'
    }
  }
  verification.value.baselineBuildId = result.baselineId
  verification.value.candidateBuildId = result.candidateId
  draftSummary.value = result.objectiveSummary
  draftWarning.value = '构建对照已关联到草稿。请核对执行情况、实际观察和适用范围后保存。'
  await nextTick()
  editor.value?.scrollIntoView?.({ block: 'start' })
}
function removeUnavailableUploads() {
  const removed = new Set(unavailableIds.value)
  if (!removed.size) return
  verification.value.uploadIds = verification.value.uploadIds.filter(id => !removed.has(id))
  draftWarning.value = `已从本次草稿移除 ${removed.size} 份失效资料。`
  error.value = ''
}
function openDecision() {
  form.value = 'decision'; error.value = ''; notice.value = ''; draftWarning.value = ''; lastAttempt.value = null
  decision.value = { choice: '', reason: '', reconsiderWhen: '', verificationVersionIds: [] }
}
function changeStatus() {
  const f = verification.value
  if (f.status !== 'EXECUTED') { f.impact = 'INCONCLUSIVE'; f.observation = ''; f.executedBy = null }
  if (f.status !== 'BLOCKED') f.blocker = ''
}
function requestId(key: string) {
  if (lastAttempt.value?.payload !== key) lastAttempt.value = { payload: key, id: globalThis.crypto.randomUUID(), unconfirmed: false }
  return lastAttempt.value.id
}
async function save() {
  if (busy.value || !form.value) return
  const isVerification = form.value === 'verification'
  const f = verification.value, d = decision.value
  const key = submissionKey.value
  if (isVerification && unavailableIds.value.length && !retryingUnconfirmed.value) {
    error.value = '请先移除失效资料，再保存本次验证记录。'
    return
  }
  if (!isVerification && !d.verificationVersionIds.length) { error.value = '请选择至少一条验证记录版本。'; return }
  busy.value = true; error.value = ''
  try {
    if (isVerification) {
      await saveVerification(props.reportId, requestId(key), previous.value?.id ?? null, f)
    } else {
      await saveDecision(props.reportId, requestId(key), d)
    }
    form.value = null; lastAttempt.value = null; comparisonTarget.value = ''; notice.value = isVerification ? '验证记录已保存。' : '人工决定已保存。'
    await load()
  } catch (e) {
    const status = (e as { response?: { status?: number } }).response?.status
    if (lastAttempt.value?.payload === key) {
      // A transport or server failure can hide a committed write. Retry its original payload first.
      lastAttempt.value.unconfirmed = status === undefined || status === 408 || status >= 500
    }
    error.value = message(e)
  }
  finally { busy.value = false }
}
function basisName(id: string) {
  const item = versions.value.get(id)
  return item ? `${item.fields.question} · 第 ${item.version} 版 · ${executions[item.fields.status]}` : '记录版本暂不可用'
}
</script>

<template>
  <section class="review-panel" aria-label="验证记录与人工决定">
    <header><h3>验证记录与人工决定</h3><button type="button" class="text-button" :disabled="busy" @click="refresh">刷新记录</button></header>
    <p v-if="error" class="stream-error" role="alert">{{ error }}</p>
    <p v-if="notice" class="success-notice" role="status">{{ notice }}</p>
    <template v-if="history">
      <p class="subtle">报告采用档案修订 {{ history.context.systemRevision }} · 提供方 {{ history.context.modelProvider || '未知' }} · 请求模型 {{ history.context.requestedModel || '未知' }} · 响应模型 {{ history.context.responseModel || '未知' }}</p>
      <ReportTasksPanel
        ref="taskPanel" :report-id="reportId" :title="reportTitle || '已保存的研究报告'" :answer="originalAnswer || ''"
        :markdown="markdown || ''" :baseline="baseline" :history="history" :builds="builds" :uploads="uploads" :uploads-ready="uploadsReady" :upload-error="uploadError"
        @listed="taskResults = $event" @refresh-review="load" @refresh-uploads="loadUploads" @inspect="(a,b) => buildPanel?.inspect(a,b)"
      />
      <BuildEvidencePanel id="task-build-evidence" ref="buildPanel" :report-id="reportId" @listed="rememberBuilds">
        <template #draft="{ comparison }">
          <div class="build-draft">
            <p v-if="form === 'decision'">保存或取消当前人工决定后，可以使用这份构建对照。</p>
            <p v-else-if="retryingUnconfirmed">先核对上次保存结果，再更换构建依据。</p>
            <template v-else>
              <label v-if="!form && correctableVersions.length">将构建对照用于
                <select v-model="comparisonTarget"><option value="">新建验证记录</option>
                  <option v-for="item in correctableVersions" :key="item.id" :value="item.id">补充第 {{ item.version }} 版 · {{ item.fields.question }}</option>
                </select>
              </label>
              <p v-if="form === 'verification' && verification.baselineBuildId && (verification.baselineBuildId !== comparison.baselineId || verification.candidateBuildId !== comparison.candidateId)">更换批次会保留草稿中的文字、判断和补充资料，请核对原说明是否仍适用。</p>
              <button type="button" class="send-button" :disabled="busy" @click="applyComparison(comparison)">{{ form === 'verification' ? '将此对照用于当前草稿' : comparisonTarget ? '用此对照补充已有验证' : '用此对照生成验证草稿' }}</button>
            </template>
          </div>
        </template>
      </BuildEvidencePanel>
      <div v-if="!form" class="review-actions">
        <button class="send-button" @click="openVerification()">记录验证</button>
        <button class="secondary-button" :disabled="!orderedVersions.length" @click="openDecision">记录人工决定</button>
      </div>
      <form v-if="form" ref="editor" class="review-editor" @submit.prevent="save">
        <fieldset :disabled="busy">
          <legend>{{ form === 'decision' ? '记录人工决定' : previous ? '补充或更正第 ' + previous.version + ' 版' : '记录验证' }}</legend>
          <template v-if="form === 'verification'">
            <div v-if="verification.baselineBuildId && verification.candidateBuildId" class="bound-builds">
              <strong>草稿引用的构建对照</strong>
              <p>基线 {{ buildNames.get(verification.baselineBuildId) || '标签待核对' }} → 候选 {{ buildNames.get(verification.candidateBuildId) || '标签待核对' }}</p>
              <p class="pre-line">产品解析摘要 · {{ draftSummary || '可打开对应批次核对。' }}</p>
              <button type="button" class="text-button" @click="inspectBuilds(verification)">查看草稿引用的对照</button>
              <small>填写原验证的执行情况和记录者解释。导入已有材料不会重新执行测试。</small>
            </div>
            <details v-if="originalAnswer" class="original-answer">
              <summary>对照原模型回答</summary>
              <MarkdownContent :content="originalAnswer" :sources="sources" />
            </details>
            <label>待验证的问题或判断<input v-model="verification.question" maxlength="1000" required /></label>
            <label>原报告建议的摘录或位置<textarea v-model="verification.suggestionRef" maxlength="2000" required rows="2" placeholder="例如：下一步验证，第 3 项" /></label>
            <div class="review-columns">
              <label>建议来源<select v-model="verification.origin"><option v-for="(label, key) in origins" :key="key" :value="key">{{ label }}</option></select></label>
              <label>执行状态<select v-model="verification.status" @change="changeStatus"><option v-for="(label, key) in executions" :key="key" :value="key">{{ label }}</option></select></label>
            </div>
            <label>{{ verification.status === 'EXECUTED' ? '实际执行的方法' : '计划方法' }}<textarea v-model="verification.method" maxlength="2000" :required="verification.status !== 'BLOCKED'" rows="2" /></label>
            <label>{{ verification.status === 'EXECUTED' ? '实际执行环境' : '计划环境（可选）' }}<input v-model="verification.environment" maxlength="1000" :required="verification.status === 'EXECUTED'" /></label>
            <template v-if="verification.status === 'EXECUTED'">
              <label>实际执行者（可选）<input v-model="verification.executedBy" maxlength="200" placeholder="例如 执行原测试的工程师或构建流水线" /></label>
              <label>实际观察与证据说明<textarea v-model="verification.observation" maxlength="4000" required rows="3" /></label>
              <label>对判断的影响<select v-model="verification.impact"><option v-for="(label, key) in impacts" :key="key" :value="key">{{ label }}</option></select></label>
            </template>
            <label v-if="verification.status === 'BLOCKED'">受阻原因<textarea v-model="verification.blocker" maxlength="1000" required rows="2" /></label>
            <p v-if="verification.status !== 'EXECUTED'" class="subtle">对判断的影响为「仍无法判断」。</p>
            <label>适用范围、限制及尚未确认事项<textarea v-model="verification.limits" maxlength="2000" :required="verification.status === 'EXECUTED'" rows="2" /></label>
            <div v-if="unavailableIds.length" class="unavailable-uploads" role="alert">
              <strong>{{ unavailableIds.length }} 份已选资料当前不可关联。</strong>
              <span v-if="unavailableNames.length">此前显示的文件名为「{{ unavailableNames.join('」、「') }}」。</span>
              <span v-if="unavailableIds.length > unavailableNames.length">其中 {{ unavailableIds.length - unavailableNames.length }} 份名称未知。</span>
              <span v-if="retryingUnconfirmed">上次保存结果尚未确认。请再次点「保存验证记录」核对原提交，避免重复创建记录。</span>
              <template v-else>
                <span>可以从本次草稿中移除这些资料，保留已填文字和其他选择。</span>
                <button class="text-button" type="button" @click="removeUnavailableUploads">移除失效资料</button>
              </template>
            </div>
            <p v-if="draftWarning" role="status">{{ draftWarning }}</p>
            <div v-if="uploads.length" class="review-choices">
              <span>关联资料（可选）</span>
              <small>资料由记录者选用，证据的含义和适用范围由记录者说明。</small>
              <label v-for="file in uploads" :key="file.uploadId"><input v-model="verification.uploadIds" type="checkbox" :value="file.uploadId" />{{ file.name }}</label>
            </div>
            <p v-if="uploadError" class="subtle">{{ uploadError }} <button type="button" class="text-button" @click="loadUploads">重读资料</button></p>
          </template>
          <template v-else>
            <label>当前选择<input v-model="decision.choice" required maxlength="500" placeholder="例如：先在目标阅读器验证" /></label>
            <label>决定理由<textarea v-model="decision.reason" required maxlength="2000" rows="2" /></label>
            <div class="review-choices">
              <span>依据的验证记录版本</span>
              <label v-for="item in orderedVersions" :key="item.id"><input v-model="decision.verificationVersionIds" type="checkbox" :value="item.id" />{{ basisName(item.id) }} · {{ stamp(item.createdAt) }}</label>
            </div>
            <label>尚未满足的条件或何时重新考虑<textarea v-model="decision.reconsiderWhen" required maxlength="2000" rows="2" /></label>
          </template>
          <div class="review-actions">
            <button class="send-button" type="submit">{{ form === 'decision' ? '保存人工决定' : '保存验证记录' }}</button>
            <button class="secondary-button" type="button" @click="form = null">取消</button>
          </div>
        </fieldset>
      </form>
      <h4 v-if="orderedVersions.length">验证历史</h4>
      <article v-for="item in orderedVersions" :id="'verification-' + item.id" :key="item.id" class="review-entry" tabindex="-1">
        <header><strong>{{ item.fields.question }}</strong><small>第 {{ item.version }} 版 · {{ executions[item.fields.status] }}</small></header>
        <p>{{ impacts[item.fields.impact] }} · {{ origins[item.fields.origin] }}</p>
        <dl>
          <template v-if="item.fields.baselineBuildId && item.fields.candidateBuildId">
            <dt>产品解析摘要</dt><dd>{{ item.buildSummary || '可打开本版构建对照核对原件。' }}</dd>
            <dt>本版构建依据</dt><dd>
              基线 {{ buildNames.get(item.fields.baselineBuildId) || '标签待核对' }} → 候选 {{ buildNames.get(item.fields.candidateBuildId) || '标签待核对' }}
              <button type="button" class="text-button" @click="inspectBuilds(item.fields)">查看本版构建对照</button>
            </dd>
          </template>
          <dt>报告位置或摘录</dt><dd>{{ item.fields.suggestionRef }}</dd>
          <template v-if="item.fields.status === 'EXECUTED'"><dt>实际执行者</dt><dd>{{ item.fields.executedBy || '未记录' }}</dd></template>
          <template v-if="item.fields.method"><dt>{{ item.fields.status === 'EXECUTED' ? '实际方法' : '计划方法' }}</dt><dd>{{ item.fields.method }}</dd></template>
          <template v-if="item.fields.environment"><dt>环境</dt><dd>{{ item.fields.environment }}</dd></template>
          <template v-if="item.fields.observation"><dt>记录者的观察与证据</dt><dd>{{ item.fields.observation }}</dd></template>
          <template v-if="item.fields.blocker"><dt>受阻原因</dt><dd>{{ item.fields.blocker }}</dd></template>
          <template v-if="item.fields.limits"><dt>范围与限制</dt><dd>{{ item.fields.limits }}</dd></template>
          <template v-if="item.fields.uploadIds.length">
            <dt>关联资料</dt><dd v-for="id in item.fields.uploadIds" :key="id">
              <a v-if="files.get(id)" :href="'/api/v1/knowledge/uploads/' + encodeURIComponent(id) + '/content'" target="_blank" rel="noopener">{{ files.get(id) }}</a>
              <span v-else>{{ !uploadsReady || uploadError ? '资料状态待刷新' : '资料当前不可用' }}</span>
            </dd>
          </template>
        </dl>
        <small>{{ item.author.name }} · {{ stamp(item.createdAt) }}</small>
        <div class="review-actions">
          <a v-if="item.previousVersionId" :href="'#verification-' + item.previousVersionId">查看被更正的第 {{ item.version - 1 }} 版</a>
          <button v-if="taskResults" type="button" class="text-button" @click="taskPanel?.fromVerification(item)">据此验证版本建立任务</button>
          <button v-if="!corrected.has(item.id) && !form && taskVerificationIds.has(item.id)" class="text-button" @click="taskPanel?.openResult(item.id)">在所属任务中补充结果</button>
          <button v-else-if="!corrected.has(item.id) && !form && taskResults" class="text-button" :disabled="item.fields.uploadIds.length > 0 && !uploadsReady && !uploadError" @click="openVerification(item)">补充或更正</button>
          <VerificationHandoffButton :report-id="reportId" :version-id="item.id" :version="item.version" />
        </div>
      </article>
      <h4 v-if="orderedDecisions.length">人工决定历史</h4>
      <article v-for="item in orderedDecisions" :key="item.id" class="review-entry">
        <strong>{{ item.fields.choice }}</strong><dl>
          <dt>理由</dt><dd>{{ item.fields.reason }}</dd>
          <dt>依据版本</dt><dd v-for="id in item.fields.verificationVersionIds" :key="id"><a :href="'#verification-' + id">{{ basisName(id) }}</a></dd>
          <dt>重新考虑的条件</dt><dd>{{ item.fields.reconsiderWhen }}</dd>
        </dl><small>{{ item.author.name }} · {{ stamp(item.createdAt) }}</small>
      </article>
    </template>
  </section>
</template>

<style scoped>
.review-panel { margin: 1rem 0; padding-block: 1rem; border-block: 1px solid var(--line, #52696355); overflow-wrap: anywhere }
.review-panel header, .review-actions { display: flex; gap: .7rem; align-items: center; justify-content: space-between; flex-wrap: wrap }
.review-actions { justify-content: flex-start; margin-top: .65rem }
.review-panel h3, .review-panel h4 { margin: .55rem 0 }
.review-panel p { margin: .55rem 0 }
.review-panel small, .review-panel dt { opacity: .8 }
.review-panel a { color: var(--accent, #6bd4ba); text-decoration: underline; text-underline-offset: .15em }
.build-draft, .bound-builds { display: grid; justify-items: start; gap: .45rem; padding: .65rem; margin-top: .75rem; border-inline-start: 2px solid var(--accent); background: var(--panel-soft) }
.build-draft label { display: grid; gap: .25rem; max-width: 100% }
.build-draft select { min-width: 0; max-width: 100%; padding: .4rem }
.bound-builds p, .build-draft p { margin: .15rem 0 }
.pre-line { white-space: pre-wrap }
.review-editor fieldset { min-width: 0; display: grid; gap: .8rem; border: 0; margin: .8rem 0; padding: 0 }
.review-editor legend { font-weight: 700; margin-bottom: .65rem }
.original-answer .markdown-content { max-height: 20rem; overflow: auto; padding: .4rem .7rem }
.review-editor label { display: grid; gap: .3rem; min-width: 0 }
.review-editor input:not([type=checkbox]), .review-editor textarea, .review-editor select { width: 100%; box-sizing: border-box; padding: .5rem; font: inherit }
.review-columns { display: grid; gap: .75rem; grid-template-columns: repeat(auto-fit,minmax(min(12rem,100%),1fr)) }
.review-choices { display: grid; gap: .35rem }
.review-choices label { display: flex; align-items: baseline; gap: .5rem }
.unavailable-uploads { display: grid; justify-items: start; gap: .35rem; border-inline-start: 3px solid var(--warning, #dfad62); padding: .25rem .75rem }
.review-entry { border-left: 2px solid var(--accent, #49a78c); padding: .45rem .9rem; margin: .8rem 0 }
.review-entry dl { margin: .5rem 0; display: grid; grid-template-columns: minmax(5em,auto) 1fr; gap: .35rem .75rem; font-size: .94rem }
.review-entry dd { grid-column: 2; margin: 0; white-space: pre-wrap }
.review-entry:target { outline: 2px solid var(--accent, #49a78c) }
</style>
