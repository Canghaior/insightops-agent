<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { isAxiosError } from 'axios'
import { availableSystemUploads, getSystem, launchSystemDecision, type BusinessSystem, type SystemAttachment } from '@/api/businessSystems'
import { listProjects, type ProjectWatch } from '@/api/projects'
import { getWorkflowRun } from '@/api/workflowRuns'
import { assessmentTypeOf, decisionFormOf } from '@/utils/assessmentTypes'
import { systemSnapshotOf, selectedMaterialNames } from '@/utils/upgradeProfiles'
import SystemBackground from '@/components/SystemBackground.vue'

const route = useRoute(), router = useRouter()
const id = String(route.params.id)
const system = ref<BusinessSystem | null>(null)
const projects = ref<ProjectWatch[]>([]), files = ref<SystemAttachment[]>([])
const form = ref({ question: '', observations: '', environment: '', projectId: '', uploadIds: [] as string[] })
const loading = ref(true), refreshing = ref(false), launching = ref(false), conflict = ref(false)
const error = ref(''), notice = ref(''), ready = ref(false)
const previous = ref<BusinessSystem | null>(null), oldNames = new Map<string, string>()
const removed = ref(new Map<string, string>()), lostProject = ref(false)
const needsReview = ref(false), reviewed = ref(false)
const dossierFiles = computed(() => new Set(system.value?.attachments.map(f => f.uploadId)))
const additional = computed(() => files.value.filter(f => !dossierFiles.value.has(f.uploadId)))
const unavailableDossier = computed(() => system.value?.attachments.some(f => !f.name || !files.value.some(available => available.uploadId === f.uploadId)))
const selected = computed(() => new Set([...dossierFiles.value, ...form.value.uploadIds]))
const blocked = computed(() => !ready.value || loading.value || refreshing.value || launching.value || conflict.value
  || unavailableDossier.value || !form.value.question.trim() || (needsReview.value && !reviewed.value))
watch(form, () => { reviewed.value = false }, { deep: true, flush: 'sync' })
watch(selected, included => { for (const file of included) removed.value.delete(file) })
let lastPayload = '', requestId = ''

function explain(reason: unknown, fallback: string) {
  if (isAxiosError(reason)) return String(reason.response?.data?.detail ?? reason.response?.data?.message ?? fallback)
  return reason instanceof Error ? reason.message : fallback
}

async function loadCurrent() {
  const [current, available, watched] = await Promise.all([getSystem(id), availableSystemUploads(), listProjects()])
  if (current.id !== id) throw new Error('档案与当前评估不一致。')
  if (system.value && current.revision !== system.value.revision) {
    needsReview.value = true
    reviewed.value = false
  }
  system.value = current
  files.value = available
  projects.value = watched.filter(p => p.enabled)
  if (form.value.projectId && !projects.value.some(p => p.id === form.value.projectId)) {
    lostProject.value = true
    form.value.projectId = ''
  }
  const usable = new Set(available.map(f => f.uploadId))
  const retained = form.value.uploadIds.filter(file => usable.has(file) && !dossierFiles.value.has(file))
  for (const file of form.value.uploadIds) if (!usable.has(file)) removed.value.set(file, oldNames.get(file) ?? '')
  if (retained.length !== form.value.uploadIds.length) form.value.uploadIds = retained
  for (const file of previous.value?.attachments ?? []) {
    if (!selected.value.has(file.uploadId)) removed.value.set(file.uploadId, oldNames.get(file.uploadId) ?? file.name ?? '')
  }
  conflict.value = false
}

onMounted(async () => {
  try {
    const runId = route.query.runId
    if (runId !== undefined) {
      if (typeof runId !== 'string' || !runId) throw new Error('历史运行标识无效。')
      const run = await getWorkflowRun(runId)
      const snapshot = systemSnapshotOf(run)
      if (assessmentTypeOf(run) !== 'SYSTEM_DECISION' || snapshot?.id !== id) throw new Error('历史运行与本次系统决策评估不一致。')
      previous.value = snapshot
      form.value = decisionFormOf(run)
      for (const [file, name] of selectedMaterialNames((run.inputs as Record<string, unknown>).selectedMaterials)) oldNames.set(file, name)
      needsReview.value = true
    }
    await loadCurrent()
    ready.value = true
  } catch (reason) { error.value = explain(reason, '评估加载失败，请重新打开系统档案。') }
  finally { loading.value = false }
})

async function reload() {
  if (refreshing.value || launching.value) return
  refreshing.value = true
  try {
    await loadCurrent()
    notice.value = '已载入最新档案和资料状态。'
    error.value = ''
  } catch (reason) {
    conflict.value = true
    error.value = explain(reason, '载入失败，请重试。')
  } finally { refreshing.value = false }
}

async function launch() {
  if (blocked.value || !system.value) return
  const revision = system.value.revision
  const input = { ...form.value, question: form.value.question.trim(), projectId: form.value.projectId || null,
    uploadIds: [...form.value.uploadIds] }
  const payload = JSON.stringify([id, revision, input])
  if (lastPayload !== payload) { requestId = globalThis.crypto.randomUUID(); lastPayload = payload }
  launching.value = true
  error.value = ''
  try {
    const run = await launchSystemDecision(id, revision, input, requestId)
    await router.push(`/runs/${run.runId}`)
  } catch (reason) {
    if (isAxiosError(reason) && reason.response?.status === 409) {
      lastPayload = ''
      conflict.value = true; needsReview.value = true; reviewed.value = false
      error.value = '档案或请求已变化，请载入最新档案并重新核对。'
    } else error.value = explain(reason, '未能启动决策评估，请重试。')
  } finally { launching.value = false }
}
</script>

<template>
  <section class="decision-page">
    <RouterLink class="back" :to="{ name: 'business-system', params: { id } }">← 返回系统档案</RouterLink>
    <h2>系统决策评估</h2>
    <p>围绕一个业务问题比较可行方案，明确证据缺口和下一步验证。目标版本可以尚未确定。</p>
    <p v-if="loading" role="status">正在读取评估输入…</p>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <form v-if="ready && system" @submit.prevent="launch">
      <section class="panel">
        <SystemBackground :system="system" />
        <button type="button" class="text-button" :disabled="refreshing || launching" @click="reload">载入最新档案和资料</button>
        <p v-if="notice" role="status">{{ notice }}</p>
      </section>
      <section class="panel editor">
        <label>本次要解决的问题或希望达到的结果 <span>必填</span>
          <textarea v-model="form.question" required rows="3" maxlength="1000" placeholder="例如：高峰期订单确认变慢，应该先定位瓶颈，还是验证框架升级？" />
        </label>
        <label>观察到的现象（用户自述）<textarea v-model="form.observations" rows="2" maxlength="1000" placeholder="发生在哪个流程、什么条件下？" /></label>
        <label>补充环境（用户自述）<textarea v-model="form.environment" rows="2" maxlength="1000" placeholder="可填写已知的运行环境、部署方式或负载情况。" /></label>
        <label>相关外部框架<select v-model="form.projectId" @change="lostProject = false">
          <option value="">不选择框架</option><option v-for="p in projects" :key="p.id" :value="p.id">{{ p.owner }}/{{ p.name }}</option>
        </select></label>
        <p v-if="lostProject" class="warning" role="alert">原评估的框架已不可用，本次未选入。</p>
        <fieldset>
          <legend>本次资料</legend>
          <p>自动使用上方档案关联的资料。可追加当前可见的文件。</p>
          <label v-for="file in additional" :key="file.uploadId" class="check">
            <input v-model="form.uploadIds" type="checkbox" :value="file.uploadId" :disabled="!selected.has(file.uploadId) && selected.size >= 10" />{{ file.name }}
          </label>
          <p v-if="unavailableDossier" class="warning" role="alert">档案中有不可用的资料，请先编辑系统档案再载入。</p>
          <p v-if="removed.size" class="warning" role="alert">{{ removed.size }} 份原选资料未纳入本次研究。{{ [...removed.values()].filter(Boolean).join('、') }}。可在可见文件中重新选择。</p>
          <p v-if="!selected.size && !form.projectId">本次没有外部资料依据，将根据用户自述列出待验证的假设和补充材料。</p>
        </fieldset>
        <div v-if="needsReview" class="review">
          <p v-if="previous">上次使用档案修订 {{ previous.revision }}，本次使用修订 {{ system.revision }}。补充背景来自上次评估，请核对后发起。</p>
          <p v-else>请对照修订 {{ system.revision }} 核对本次问题和补充背景。</p>
          <label class="check"><input v-model="reviewed" type="checkbox" :disabled="refreshing || conflict || launching" />已核对本次背景</label>
        </div>
        <button class="primary-button" type="submit" :disabled="blocked">{{ launching ? '正在发起…' : '发起系统决策评估' }}</button>
      </section>
    </form>
  </section>
</template>
<style scoped>
.decision-page { max-width: 1000px; }
.panel { padding: 22px; margin-block: 16px; }
.editor, .editor > label { display: grid; gap: 8px; }
.editor { gap: 18px; }
.editor textarea, .editor select { width: 100%; box-sizing: border-box; padding: 9px; font: inherit; }
fieldset { border: 1px solid var(--line); padding: 12px 16px; }
.check { display: flex; align-items: center; gap: 8px; margin-block: 8px; }
.review, .warning { padding: 12px 16px; border-left: 3px solid var(--accent); background: var(--panel); }
.editor > button { justify-self: start; }
.back, .editor label span { color: var(--muted); }
.error { color: var(--danger); }
</style>
