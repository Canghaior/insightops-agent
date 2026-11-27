<script setup lang="ts">
import { computed, ref, shallowRef } from 'vue'
import { batchName, type BuildSummary } from '@/api/buildEvidence'
import type { SystemAttachment } from '@/api/businessSystems'
import { executions, impacts } from '@/api/reportReview'
import { saveTaskResult, type SavedTaskResult, type TaskResult, type TaskVersion } from '@/api/reportTasks'
import { useTaskSubmission } from '@/utils/taskSubmission'
import { localDateTime, resultDraft } from '@/utils/taskResultFields'
import TaskUploadChoices from './TaskUploadChoices.vue'

const props = defineProps<{ reportId: string; version: TaskVersion; previous?: SavedTaskResult; newest?: SavedTaskResult;
  uploads: SystemAttachment[]; uploadsReady: boolean; uploadError: string; builds: BuildSummary[] }>()
const emit = defineEmits<{ cancel: []; saved: [v: TaskResult]; refresh: []; refreshUploads: []; inspect: [string, string] }>()
const base = shallowRef(props.previous)
const fields = ref(resultDraft(base.value))
const when = ref(localDateTime(fields.value.executedAt))
const patch = shallowRef<globalThis.File | null>(null)
const { busy, error, unconfirmed, submit } = useTaskSubmission()
const stale = computed(() => base.value && props.newest && props.newest.verification.id !== base.value.verification.id)
const executed = computed(() => fields.value.status === 'EXECUTED')
function choosePatch(event: globalThis.Event) {
  const input = event.target as globalThis.HTMLInputElement
  const file = input.files?.[0] ?? null
  if (file && (!/\.(patch|diff)$/i.test(file.name) || file.size === 0 || file.size > 1024 * 1024)) {
    error.value = '请选择非空的 UTF-8 .patch 或 .diff 文件，最大 1 MiB。'
    input.value = ''
    patch.value = null
    return
  }
  patch.value = file
  if (file) fields.value.retainPreviousPatch = false
  error.value = ''
}
function adoptLatest() {
  if (!base.value || !props.newest) return
  const previous = resultDraft(base.value), updated = resultDraft(props.newest)
  for (const k of Object.keys(previous) as (keyof typeof previous)[]) {
    if (JSON.stringify(previous[k]) !== JSON.stringify(fields.value[k])) Object.assign(updated, { [k]: fields.value[k] })
  }
  if (localDateTime(previous.executedAt) === when.value) when.value = localDateTime(updated.executedAt)
  if (base.value.result.patch?.sha256 !== props.newest.result.patch?.sha256) updated.retainPreviousPatch = false
  fields.value = updated
  base.value = props.newest
  error.value = ''
}
function payload() {
  const f = { ...fields.value, uploadIds: [...fields.value.uploadIds] }
  if (executed.value) {
    if (when.value !== localDateTime(f.executedAt)) {
      const date = new Date(when.value)
      f.executedAt = Number.isFinite(date.getTime()) ? date.toISOString() : null
    }
  } else {
    f.executedAt = null
    f.executedBy = ''
    f.observation = ''
    f.impact = 'INCONCLUSIVE'
    f.retainPreviousPatch = false
  }
  if (f.status !== 'BLOCKED') f.blocker = ''
  return f
}
function validationMessage(f: ReturnType<typeof payload>) {
  if (stale.value) return '这个验证版本已有更正，请先核对最新记录。'
  if (f.status === 'BLOCKED' && !f.blocker.trim()) return '请说明受阻原因。'
  if (f.status === 'NOT_EXECUTED' && !f.method.trim()) return '请填写计划方法。'
  if (f.status === 'EXECUTED' && (!f.executedBy.trim() || !f.executedAt || !f.sourceRevision.trim() || !f.environment.trim()
    || !f.method.trim() || !f.observation.trim() || !f.limits.trim())) return '请填写实际执行者、时间、源码、环境、方法、观察及范围限制。'
  if ((!!f.baselineBuildId !== !!f.candidateBuildId)) return '请同时选择基线构建和候选构建，或将两项留空。'
  if (f.uploadIds.length > 10) return '每次结果最多关联 10 份补充资料。'
  if (f.uploadIds.length && (!props.uploadsReady || f.uploadIds.some(id => !props.uploads.some(u => u.uploadId === id)))) {
    return '请刷新可用资料并处理失效选择后保存。'
  }
  return ''
}
async function save() {
  if (busy.value) return
  const f = payload()
  if (!unconfirmed.value && (error.value = validationMessage(f))) return
  const priorId = base.value?.verification.id ?? null
  const file = executed.value ? patch.value : null
  const saved = await submit(JSON.stringify([props.version.id, priorId, f]), file,
    id => saveTaskResult(props.reportId, props.version.id, id, priorId, f, file), () => emit('refresh'))
  if (saved) emit('saved', saved)
}
</script>

<template>
  <form class="task-form" aria-label="执行结果草稿" @submit.prevent="save">
    <h5>{{ base ? '补充或更正执行结果' : '登记执行结果' }}</h5>
    <p>本次结果归属任务第 {{ version.version }} 版 · {{ version.fields.question }}</p>
    <p v-if="version.fields.readiness === 'NEEDS_MATERIALS'" class="subtle">交出时仍待补资料，请按实际执行条件记录。</p>
    <p v-if="error" class="stream-error" role="alert">{{ error }}</p>
    <fieldset :disabled="busy || unconfirmed">
      <label>任务执行状态<select v-model="fields.status"><option v-for="(label, key) in executions" :key="key" :value="key">{{ label }}</option></select></label>
      <template v-if="executed">
        <label>实际执行者<input v-model="fields.executedBy" required maxlength="200" /></label>
        <label>实际执行时间<input v-model="when" type="datetime-local" step="1" required /></label>
      </template>
      <label>{{ executed ? '源码基线说明' : '拟使用的源码基线（如已知）' }}<textarea v-model="fields.sourceRevision" rows="2" maxlength="1000" :required="executed" /></label>
      <label>候选版本说明<textarea v-model="fields.candidateRevision" rows="2" maxlength="1000" /></label>
      <label>{{ executed ? '实际执行环境' : '计划环境' }}<textarea v-model="fields.environment" rows="2" maxlength="1000" :required="executed" /></label>
      <label>{{ executed ? '实际执行的方法' : '计划方法' }}<textarea v-model="fields.method" rows="2" maxlength="2000" :required="fields.status !== 'BLOCKED'" /></label>
      <button v-if="fields.status === 'NOT_EXECUTED' && version.fields.method && !fields.method" type="button" class="text-button" @click="fields.method = version.fields.method">带入任务的计划方法</button>
      <template v-if="executed">
        <label>实际观察<textarea v-model="fields.observation" required maxlength="4000" rows="3" /></label>
        <label>对判断的影响<select v-model="fields.impact"><option v-for="(name, key) in impacts" :key="key" :value="key">{{ name }}</option></select></label>
        <label v-if="base?.result.patch" class="task-check"><input v-model="fields.retainPreviousPatch" type="checkbox" :disabled="!!patch" />沿用原版本补丁 · {{ base.result.patch.name }}</label>
        <label>返回的补丁<input type="file" accept=".patch,.diff" @change="choosePatch" /></label>
        <small>可选，UTF-8 的 .patch 或 .diff，最大 1 MiB。按原件归档，执行者另行说明源码与补丁关系。</small>
      </template>
      <p v-else>当前状态按「仍无法判断」记录。实际执行者、时间、观察和补丁仅在已执行时提交。</p>
      <label v-if="fields.status === 'BLOCKED'">受阻原因<textarea v-model="fields.blocker" required maxlength="1000" rows="2" /></label>
      <label>适用范围与限制<textarea v-model="fields.limits" maxlength="2000" rows="2" :required="executed" /></label>
      <label>未完成项<textarea v-model="fields.unfinished" maxlength="2000" rows="2" /></label>
      <fieldset class="task-choices">
        <legend>关联本次构建证据（可选）</legend>
        <a href="#task-build-evidence">打开本报告的构建导入与对照</a>
        <label>结果引用的基线构建<select v-model="fields.baselineBuildId"><option :value="null">未选择</option><option v-for="b in builds.filter(x => x.role === 'BASELINE')" :key="b.id" :value="b.id">{{ batchName(b) }}</option></select></label>
        <label>结果引用的候选构建<select v-model="fields.candidateBuildId"><option :value="null">未选择</option><option v-for="b in builds.filter(x => x.role === 'CANDIDATE')" :key="b.id" :value="b.id">{{ batchName(b) }}</option></select></label>
        <button v-if="fields.baselineBuildId && fields.candidateBuildId" type="button" class="text-button" @click="emit('inspect', fields.baselineBuildId, fields.candidateBuildId)">核对所选构建差异</button>
      </fieldset>
      <TaskUploadChoices v-model="fields.uploadIds" :uploads="uploads" :ready="uploadsReady" :error="uploadError" @refresh="emit('refreshUploads')" />
    </fieldset>
    <div v-if="stale && newest && !unconfirmed" class="task-warning" role="alert">
      验证第 {{ newest.verification.version }} 版已有更正。请对照该版的全部字段与草稿，再继续保存。
      <a :href="'#verification-' + newest.verification.id">查看最新验证记录</a>
      <p>新版执行时间 {{ newest.result.executedAt || '未记录' }} · 源码 {{ newest.result.sourceRevision || '未记录' }} · 候选 {{ newest.result.candidateRevision || '未记录' }} · 未完成项 {{ newest.result.unfinished || '未填写' }} · 补丁 {{ newest.result.patch?.name || '无' }}</p>
      <button type="button" class="secondary-button" @click="adoptLatest">已核对，以最新验证版为依据并保留已编辑字段</button>
    </div>
    <div class="task-actions">
      <button type="submit" class="send-button" :disabled="busy || (!!stale && !unconfirmed)">{{ unconfirmed ? '用原请求核对结果保存情况' : '保存执行结果' }}</button>
      <button type="button" class="text-button" :disabled="busy || unconfirmed" @click="emit('cancel')">取消结果编辑</button>
    </div>
  </form>
</template>
