<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import MarkdownContent from './MarkdownContent.vue'
import { availableSystemUploads, type SystemAttachment } from '@/api/businessSystems'
import { getReview, saveDecision, saveVerification, executions, impacts, origins,
  type DecisionInput, type ReviewHistory, type Verification, type VerificationInput } from '@/api/reportReview'

const props = defineProps<{ reportId: string; originalAnswer?: string; sources?: string[] }>()
const history = ref<ReviewHistory>()
const uploads = ref<SystemAttachment[]>([])
const uploadsReady = ref(false)
const error = ref(''), uploadError = ref(''), notice = ref(''), draftWarning = ref('')
const form = ref<'verification' | 'decision' | null>(null)
const busy = ref(false)
const previous = ref<Verification | null>(null)
const fresh = (): VerificationInput => ({ question: '', suggestionRef: '', origin: 'MODEL', status: 'NOT_EXECUTED',
  method: '', environment: '', observation: '', impact: 'INCONCLUSIVE', limits: '', blocker: '', uploadIds: [] })
const verification = ref(fresh())
const decision = ref<DecisionInput>({ choice: '', reason: '', reconsiderWhen: '', verificationVersionIds: [] })
const versions = computed(() => new Map(history.value?.verifications.map(item => [item.id, item])))
const corrected = computed(() => new Set(history.value?.verifications.map(item => item.previousVersionId)))
const files = computed(() => new Map(uploads.value.map(item => [item.uploadId, item.name])))
const orderedVersions = computed(() => [...(history.value?.verifications ?? [])].reverse())
const orderedDecisions = computed(() => [...(history.value?.decisions ?? [])].reverse())
const stamp = (date: string) => new Date(date).toLocaleString('zh-CN', { hour12: false })
let lastAttempt: { payload: string; id: string } | null = null
let historyRequest = 0

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
  try { uploads.value = await availableSystemUploads(); uploadsReady.value = true; uploadError.value = '' }
  catch { uploads.value = []; uploadsReady.value = false; uploadError.value = '暂时无法读取可关联资料，仍可填写文字证据。' }
}
function refresh() { void load(); void loadUploads() }
onMounted(refresh)
function openVerification(parent: Verification | null = null) {
  form.value = 'verification'; previous.value = parent; draftWarning.value = ''; notice.value = ''; error.value = ''; lastAttempt = null
  verification.value = parent ? { ...parent.fields, uploadIds: [...parent.fields.uploadIds] } : fresh()
  if (parent && uploadsReady.value && !uploadError.value) {
    verification.value.uploadIds = parent.fields.uploadIds.filter(id => files.value.has(id))
    if (verification.value.uploadIds.length !== parent.fields.uploadIds.length)
      draftWarning.value = '旧版本中有资料当前不可用，本次更正不会关联这些资料。'
  }
}
function openDecision() {
  form.value = 'decision'; error.value = ''; notice.value = ''; draftWarning.value = ''; lastAttempt = null
  decision.value = { choice: '', reason: '', reconsiderWhen: '', verificationVersionIds: [] }
}
function changeStatus() {
  const f = verification.value
  if (f.status !== 'EXECUTED') { f.impact = 'INCONCLUSIVE'; f.observation = '' }
  if (f.status !== 'BLOCKED') f.blocker = ''
}
function requestId(payload: unknown) {
  const key = JSON.stringify(payload)
  if (lastAttempt?.payload !== key) lastAttempt = { payload: key, id: globalThis.crypto.randomUUID() }
  return lastAttempt.id
}
async function save() {
  if (busy.value || !form.value) return
  const isVerification = form.value === 'verification'
  const f = verification.value, d = decision.value
  if (!isVerification && !d.verificationVersionIds.length) { error.value = '请选择至少一条验证记录版本。'; return }
  busy.value = true; error.value = ''
  try {
    if (isVerification) {
      await saveVerification(props.reportId, requestId(['record', previous.value?.id, f]), previous.value?.id ?? null, f)
    } else {
      await saveDecision(props.reportId, requestId(['decision', d]), d)
    }
    form.value = null; lastAttempt = null; notice.value = isVerification ? '验证记录已保存。' : '人工决定已保存。'
    await load()
  } catch (e) { error.value = message(e) }
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
      <div v-if="!form" class="review-actions">
        <button class="send-button" @click="openVerification()">记录验证</button>
        <button class="secondary-button" :disabled="!orderedVersions.length" @click="openDecision">记录人工决定</button>
      </div>
      <form v-if="form" class="review-editor" @submit.prevent="save">
        <fieldset :disabled="busy">
          <legend>{{ form === 'decision' ? '记录人工决定' : previous ? '补充或更正第 ' + previous.version + ' 版' : '记录验证' }}</legend>
          <template v-if="form === 'verification'">
            <p v-if="draftWarning" role="status">{{ draftWarning }}</p>
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
              <label>实际观察与证据说明<textarea v-model="verification.observation" maxlength="4000" required rows="3" /></label>
              <label>对判断的影响<select v-model="verification.impact"><option v-for="(label, key) in impacts" :key="key" :value="key">{{ label }}</option></select></label>
            </template>
            <label v-if="verification.status === 'BLOCKED'">受阻原因<textarea v-model="verification.blocker" maxlength="1000" required rows="2" /></label>
            <p v-if="verification.status !== 'EXECUTED'" class="subtle">对判断的影响为「仍无法判断」。</p>
            <label>适用范围、限制及尚未确认事项<textarea v-model="verification.limits" maxlength="2000" :required="verification.status === 'EXECUTED'" rows="2" /></label>
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
          <dt>报告位置或摘录</dt><dd>{{ item.fields.suggestionRef }}</dd>
          <template v-if="item.fields.method"><dt>{{ item.fields.status === 'EXECUTED' ? '实际方法' : '计划方法' }}</dt><dd>{{ item.fields.method }}</dd></template>
          <template v-if="item.fields.environment"><dt>环境</dt><dd>{{ item.fields.environment }}</dd></template>
          <template v-if="item.fields.observation"><dt>观察与证据</dt><dd>{{ item.fields.observation }}</dd></template>
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
          <button v-if="!corrected.has(item.id) && !form" class="text-button" :disabled="item.fields.uploadIds.length > 0 && !uploadsReady && !uploadError" @click="openVerification(item)">补充或更正</button>
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
.review-editor fieldset { min-width: 0; display: grid; gap: .8rem; border: 0; margin: .8rem 0; padding: 0 }
.review-editor legend { font-weight: 700; margin-bottom: .65rem }
.original-answer .markdown-content { max-height: 20rem; overflow: auto; padding: .4rem .7rem }
.review-editor label { display: grid; gap: .3rem; min-width: 0 }
.review-editor input:not([type=checkbox]), .review-editor textarea, .review-editor select { width: 100%; box-sizing: border-box; padding: .5rem; font: inherit }
.review-columns { display: grid; gap: .75rem; grid-template-columns: repeat(auto-fit,minmax(min(12rem,100%),1fr)) }
.review-choices { display: grid; gap: .35rem }
.review-choices label { display: flex; align-items: baseline; gap: .5rem }
.review-entry { border-left: 2px solid var(--accent, #49a78c); padding: .45rem .9rem; margin: .8rem 0 }
.review-entry dl { margin: .5rem 0; display: grid; grid-template-columns: minmax(5em,auto) 1fr; gap: .35rem .75rem; font-size: .94rem }
.review-entry dd { grid-column: 2; margin: 0; white-space: pre-wrap }
.review-entry:target { outline: 2px solid var(--accent, #49a78c) }
</style>
