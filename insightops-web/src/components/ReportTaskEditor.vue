<script setup lang="ts">
import { computed, ref, shallowRef } from 'vue'
import { readinessLabels, saveTask, type TaskFields, type TaskVersion } from '@/api/reportTasks'
import { executions, origins, type Verification } from '@/api/reportReview'
import type { SystemAttachment } from '@/api/businessSystems'
import { fieldLabels, readyFields, taskDraft, taskSections } from '@/utils/taskFields'
import { useTaskSubmission } from '@/utils/taskSubmission'
import TaskUploadChoices from './TaskUploadChoices.vue'

const props = defineProps<{ reportId: string; answer: string; markdown: string; previous?: TaskVersion; newest?: TaskVersion; seed?: Verification;
  verifications: Verification[]; uploads: SystemAttachment[]; uploadsReady: boolean; uploadError: string }>()
const emit = defineEmits<{ cancel: []; saved: [v: TaskVersion]; refresh: []; refreshUploads: [] }>()
const base = shallowRef(props.previous)
const fields = ref(taskDraft(base.value?.fields))
if (!base.value && props.seed) {
  const { id, fields: evidence } = props.seed
  fields.value.verificationVersionIds = [id]
  fields.value.question = evidence.question
  fields.value.origin = evidence.origin
  const source = evidence.origin === 'MODEL' ? props.answer : props.markdown
  if (source.includes(evidence.suggestionRef)) fields.value.excerpt = evidence.suggestionRef
}
if (!base.value && !props.answer.trim()) fields.value.origin = 'HUMAN_OR_TOOL'
const { busy, unconfirmed, error, submit } = useTaskSubmission()
const original = ref<globalThis.HTMLTextAreaElement>()
const sourceText = computed(() => fields.value.origin === 'MODEL' ? props.answer : props.markdown)
const missingVersions = computed(() => fields.value.verificationVersionIds.filter(id => !props.verifications.some(v => v.id === id)))
const stale = computed(() => base.value && props.newest && props.newest.id !== base.value.id)
const keys = Object.keys(fieldLabels) as (keyof TaskFields)[]
const changed = computed(() => !base.value || !props.newest ? [] : keys.filter(k =>
  JSON.stringify(base.value!.fields[k]) !== JSON.stringify(props.newest!.fields[k])))
function readChoice(k: keyof TaskFields, f: TaskFields) {
  if (k === 'origin') return origins[f.origin]
  if (k === 'readiness') return readinessLabels[f.readiness]
  if (k === 'verificationVersionIds') return f.verificationVersionIds.map(id => {
    const v = props.verifications.find(x => x.id === id)
    return v ? `${v.fields.question} · 第 ${v.version} 版` : '验证版本不可读'
  }).join('、') || '未选择'
  if (k === 'uploadIds') return f.uploadIds.map(id => props.uploads.find(x => x.uploadId === id)?.name || '资料暂不可读').join('、') || '未选择'
  return f[k] || '未填写'
}
function reconcile() {
  if (!base.value || !props.newest) return
  const merged = taskDraft(props.newest.fields)
  const mine = fields.value
  for (const k of keys) {
    if (JSON.stringify(mine[k]) !== JSON.stringify(base.value.fields[k])) Object.assign(merged, { [k]: mine[k] })
  }
  base.value = props.newest
  fields.value = merged
  error.value = ''
}
function adoptExcerpt() {
  const node = original.value
  if (!node || node.selectionEnd === node.selectionStart) {
    error.value = '请先在原文框中选中一段连续文字。'
    return
  }
  fields.value.excerpt = node.value.slice(node.selectionStart, node.selectionEnd)
  error.value = ''
}
function validationMessage() {
  const f = fields.value
  if (stale.value) return '已出现更新的任务版本，请先核对更正依据。'
  if (!f.question.trim()) return '请填写本次要解决的问题。'
  if (!f.excerpt.trim() || !sourceText.value.includes(f.excerpt.trim())) return '请从所选来源复制一段连续原文，或使用上方的原文选择。'
  if (f.readiness === 'READY') {
    if (f.missingMaterials.trim()) return '请核对仍缺少的材料。需要补充时可保存为「待补资料」。'
    const missing = readyFields.find(k => !f[k].trim())
    if (missing) return '交给执行者前，请填写「' + fieldLabels[missing] + '」。'
  } else if (!f.missingMaterials.trim()) return '请说明还需要补充哪些材料。'
  if (missingVersions.value.length) return '请先移除当前无法关联的验证版本。'
  if (f.uploadIds.length > 10 || f.verificationVersionIds.length > 50) return '每版任务最多关联 10 份资料和 50 个验证版本。'
  if (f.uploadIds.length && (!props.uploadsReady || f.uploadIds.some(id => !props.uploads.some(u => u.uploadId === id)))) {
    return '请刷新可用资料并处理失效选择后保存。'
  }
  return ''
}
async function save() {
  if (busy.value) return
  if (!unconfirmed.value && (error.value = validationMessage())) return
  const previous = base.value?.id ?? null
  const f = taskDraft(fields.value)
  if (f.origin === 'MODEL') f.originDetail = ''
  const saved = await submit(JSON.stringify([previous, f]), null, id => saveTask(props.reportId, id, previous, f), () => emit('refresh'))
  if (saved) emit('saved', saved)
}
</script>

<template>
  <form class="task-form" aria-label="任务草稿" @submit.prevent="save">
    <h5>{{ base ? '补充或更正任务第 ' + base.version + ' 版' : '建立单项任务' }}</h5>
    <p v-if="seed">已带入验证第 {{ seed.version }} 版。该记录的原建议位置或摘录为「{{ seed.fields.suggestionRef }}」。</p>
    <p v-if="error" class="stream-error" role="alert">{{ error }}</p>
    <fieldset :disabled="busy || unconfirmed">
      <label>要解决的问题<textarea v-model="fields.question" rows="2" maxlength="1000" required /></label>
      <label>原建议来源<select v-model="fields.origin"><option value="MODEL" :disabled="!answer">原模型建议</option><option value="HUMAN_OR_TOOL">人工或外部工具补充</option></select></label>
      <label v-if="fields.origin === 'HUMAN_OR_TOOL'">建议人或工具（如已知）<input v-model="fields.originDetail" maxlength="200" /></label>
      <details>
        <summary>从{{ fields.origin === 'MODEL' ? '原模型回答' : '已保存报告' }}选取原文</summary>
        <label>供选取的原文<textarea ref="original" readonly rows="6" :value="sourceText" /></label>
        <button type="button" class="secondary-button" @click="adoptExcerpt">采用选中原文</button>
      </details>
      <label>原报告摘录<textarea v-model="fields.excerpt" rows="2" maxlength="2000" required placeholder="可粘贴连续原文。人工补充建议使用报告中的原文标明出处。" /></label>
      <label>交接状态<select v-model="fields.readiness"><option v-for="(name, k) in readinessLabels" :key="k" :value="k">{{ name }}</option></select></label>
      <label v-if="fields.readiness === 'NEEDS_MATERIALS' || fields.missingMaterials">仍缺少的材料<textarea v-model="fields.missingMaterials" rows="2" maxlength="2000" :required="fields.readiness === 'NEEDS_MATERIALS'" /></label>
      <div v-for="group in taskSections" :key="group.title" class="task-section">
        <h6>{{ group.title }}</h6>
        <label v-for="[key, name] in group.fields" :key="key">{{ name }}<textarea v-model="fields[key]" rows="2" maxlength="2000" :required="fields.readiness === 'READY' && readyFields.some(k => k === key)" /></label>
      </div>
      <fieldset class="task-choices">
        <legend>依据的已保存验证版本（可选）</legend>
        <p v-if="!verifications.length">这份报告尚无已保存的验证版本。</p>
        <label v-for="v in verifications" :key="v.id" class="task-check"><input v-model="fields.verificationVersionIds" type="checkbox" :value="v.id" />{{ v.fields.question }} · 第 {{ v.version }} 版 · {{ executions[v.fields.status] }}</label>
        <p v-if="missingVersions.length" role="alert">{{ missingVersions.length }} 个已选验证版本当前不可关联。<button type="button" class="text-button" @click="fields.verificationVersionIds = fields.verificationVersionIds.filter(id => !missingVersions.includes(id))">移除失效的验证选择</button></p>
      </fieldset>
      <TaskUploadChoices v-model="fields.uploadIds" :uploads="uploads" :ready="uploadsReady" :error="uploadError" @refresh="emit('refreshUploads')" />
    </fieldset>
    <div v-if="stale && newest && !unconfirmed" class="task-warning" role="alert">
      已有人保存第 {{ newest.version }} 版。请核对这些字段。未编辑的字段将采用新版，同时编辑的字段保留你的草稿。
      <details open>
        <summary>对照最新版本与草稿</summary>
        <dl><template v-for="key in changed" :key="key"><dt>{{ fieldLabels[key] }}</dt><dd>新版 · {{ readChoice(key, newest.fields) }}<br />草稿 · {{ readChoice(key, fields) }}</dd></template></dl>
      </details>
      <button type="button" class="secondary-button" :disabled="busy" @click="reconcile">已核对，以第 {{ newest.version }} 版为更正依据</button>
    </div>
    <div class="task-actions">
      <button type="submit" class="send-button" :disabled="busy || (!!stale && !unconfirmed)">{{ unconfirmed ? '用原请求核对任务保存结果' : '保存任务版本' }}</button>
      <button type="button" class="text-button" :disabled="busy || unconfirmed" @click="emit('cancel')">取消任务编辑</button>
    </div>
  </form>
</template>
