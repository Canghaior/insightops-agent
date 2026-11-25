<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import type { ResearchReport } from '@/api/reports'
import { addClaim, claimReviews, verdicts, type ClaimDraft, type SavedClaim } from '@/api/claimReviews'
import { executions, type Verification } from '@/api/reportReview'
import { frozenBaseline, originalUrl } from '@/api/engineeringBaselines'
import { evidenceError } from '@/api/buildEvidence'

const props = defineProps<{ report: ResearchReport; verifications: Verification[] }>()
const selected = ref(0)
const records = ref<SavedClaim[]>([]), error = ref(''), loading = ref(false), saving = ref(false)
const baseline = computed(() => frozenBaseline(props.report.research?.inputs))
const lines = computed(() => (props.report.research?.answer ?? '').split(/\r\n|\r|\n/)
  .map((body, i) => ({ line: i + 1, body: body.trim() }))
  .filter(item => item.body && item.body.length <= 4000 && !/^#{1,6}\s/.test(item.body)))
const sources = computed(() => props.report.research?.citations ?? [])
const passages = computed(() => (props.report.research?.evidence ?? []).flatMap(row => row.passages ?? [])
  .filter(p => p.label === draft.value.source))
const fresh = (): ClaimDraft => ({ answerLine: 0, path: '', codeLine: null, source: '', quotation: '',
  verdict: 'INSUFFICIENT', reason: '', test: '', verificationId: null })
const draft = ref<ClaimDraft>(fresh())
const composing = ref(false)
let retry: { body: string; key: string } | undefined
const selectedSource = computed(() => sources.value.find(item => item.label === draft.value.source))
function safeSource(url?: string) {
  if (!url) return
  if (url.startsWith('/api/v1/knowledge/uploads/')) return url
  try { const link = new globalThis.URL(url); if (link.protocol === 'https:') return link.href } catch { /* no link */ }
}
function begin() {
  draft.value = fresh()
  selected.value = lines.value.find(item => sources.value.some(s => item.body.includes('[' + s.label + ']')))?.line ?? lines.value[0]?.line ?? 0
  prefillEvidence()
  composing.value = true
  error.value = ''
}
function prefillEvidence() {
  const body = lines.value.find(item => item.line === selected.value)?.body ?? ''
  const cited = sources.value.filter(s => body.includes('[' + s.label + ']'))
  draft.value.source = cited.length === 1 ? cited[0]!.label : ''
  draft.value.quotation = ''
  draft.value.path = ''
  draft.value.codeLine = null
  for (const f of baseline.value?.content.evidence.files ?? []) {
    const i = body.indexOf(f.path)
    if (i < 0) continue
    const match = body.slice(i + f.path.length).match(/^(?::|#L)([1-9]\d*)/)
    if (match) { draft.value.path = f.path; draft.value.codeLine = Number(match[1]); break }
  }
}
watch(selected, prefillEvidence)
watch(() => draft.value.source, () => { draft.value.quotation = '' })
async function refresh() {
  loading.value = true
  try { records.value = await claimReviews(props.report.id); error.value = '' }
  catch (e) { error.value = evidenceError(e, '暂时无法读取建议核对记录。') }
  finally { loading.value = false }
}
onMounted(refresh)
async function save() {
  if (saving.value) return
  const data = { ...draft.value, answerLine: selected.value, codeLine: draft.value.path ? draft.value.codeLine : null }
  const body = JSON.stringify(data)
  if (retry?.body !== body) retry = { body, key: globalThis.crypto.randomUUID() }
  saving.value = true
  try {
    const result = await addClaim(props.report.id, retry.key, data)
    records.value = [result, ...records.value.filter(r => r.id !== result.id)]
    composing.value = false; retry = undefined; error.value = ''
  } catch (e) { error.value = evidenceError(e, '核对记录未能保存。') }
  finally { saving.value = false }
}
</script>

<template>
  <section class="claim-panel" aria-label="关键建议核对">
    <h3>关键建议核对</h3>
    <button v-if="!composing" class="secondary-button" type="button" @click="begin">核对一条建议</button>
    <p v-if="error" role="alert">{{ error }} <button type="button" class="text-button" @click="refresh">重读记录</button></p>
    <p v-if="loading" role="status">读取核对记录…</p>
    <form v-if="composing" @submit.prevent="save"><fieldset :disabled="saving">
      <label>原回答中的建议<select v-model.number="selected" required><option v-for="item in lines" :key="item.line" :value="item.line">第 {{ item.line }} 行 · {{ item.body.slice(0, 110) }}</option></select></label>
      <blockquote>{{ lines.find(item => item.line === selected)?.body }}</blockquote>
      <template v-if="baseline">
        <label>冻结工程原件<select v-model="draft.path"><option value="">未关联工程位置</option><option v-for="f in baseline.content.evidence.files" :key="f.path" :value="f.path">{{ f.path }}</option></select></label>
        <label v-if="draft.path">原件行号<input v-model.number="draft.codeLine" type="number" min="1" step="1" required /></label>
        <a v-if="draft.path" :href="originalUrl(baseline.baseline.systemId, baseline.baseline.id, draft.path)" target="_blank" rel="noopener">打开冻结工程原件</a>
      </template>
      <label>外部资料<select v-model="draft.source"><option value="">未引用外部资料</option><option v-for="s in sources" :key="s.label" :value="s.label">[{{ s.label }}] {{ s.title }} · {{ s.versionLabel || '未标版本' }}</option></select></label>
      <template v-if="selectedSource">
        <a v-if="safeSource(selectedSource.url)" :href="safeSource(selectedSource.url)" target="_blank" rel="noopener">打开 {{ selectedSource.heading || selectedSource.title }}</a>
        <blockquote v-for="p in passages" :key="p.text">{{ p.text }}</blockquote>
        <p v-if="!passages.length">这条引用没有可对照的冻结原文。</p>
        <label v-else>用于核对的原文摘录<textarea v-model="draft.quotation" maxlength="1200" rows="2" /></label>
      </template>
      <label>你对依据的判断<select v-model="draft.verdict"><option v-for="(label,key) in verdicts" :key="key" :value="key">{{ label }}</option></select></label>
      <label>理由<textarea v-model="draft.reason" required maxlength="1500" rows="2" placeholder="这段原文或工程代码能说明什么？还缺什么？" /></label>
      <label>可区分前后行为的验收用例<textarea v-model="draft.test" required maxlength="1000" rows="2" /></label>
      <label>关联本报告的实际验证版本<select v-model="draft.verificationId"><option :value="null">尚未关联</option><option v-for="v in verifications" :key="v.id" :value="v.id">{{ v.fields.question }} · v{{ v.version }} · {{ executions[v.fields.status] }}</option></select></label>
      <div><button class="send-button" type="submit">保存核对记录</button> <button type="button" class="text-button" @click="composing = false">取消</button></div>
    </fieldset></form>
    <article v-for="r in records" :key="r.id">
      <strong>{{ verdicts[r.draft.verdict] }} · 人工核对</strong> · 原回答第 {{ r.draft.answerLine }} 行
      <blockquote>{{ r.evidence.claim }}</blockquote>
      <p v-if="r.draft.path">工程依据 · {{ r.draft.path }}:{{ r.draft.codeLine }} <code>{{ r.evidence.code }}</code></p>
      <p v-if="r.draft.source">外部依据 · [{{ r.draft.source }}] {{ r.evidence.sourceVersion || '版本未标注' }}
        <a v-if="safeSource(r.evidence.sourceUrl)" :href="safeSource(r.evidence.sourceUrl)" target="_blank" rel="noopener">打开来源</a></p>
      <blockquote v-if="r.draft.quotation">{{ r.draft.quotation }}</blockquote>
      <p>{{ r.draft.reason }}</p><p>验收用例 · {{ r.draft.test }}</p>
      <p v-if="r.draft.verificationId"><a :href="'#verification-' + r.draft.verificationId">查看关联验证版本</a> · {{ r.evidence.observation || '该版本尚无实际观察' }}</p>
      <p v-else>尚未关联实际验证。</p>
    </article>
  </section>
</template>

<style scoped>
.claim-panel { display: grid; gap: .7rem; margin: 1rem 0; justify-items: start; }
form, fieldset, label, article { display: grid; gap: .5rem; min-width: 0; width: 100%; }
fieldset, article { padding: 1rem; border: 1px solid var(--line); border-radius: .65rem; }
select { max-width: 100%; } blockquote, code { white-space: pre-wrap; overflow-wrap: anywhere; }
blockquote { margin: .25rem 0; color: var(--muted); } p { margin: .2rem 0; }
</style>
