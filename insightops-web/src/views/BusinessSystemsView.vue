<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { isAxiosError } from 'axios'
import {
  availableSystemUploads, backgroundFields, createSystem, getSystem, listSystems, systemHistory, updateSystem,
  type BusinessSystem, type SystemAttachment, type SystemBackground, type SystemRun, type SystemSummary,
} from '@/api/businessSystems'
import SystemBackgroundPanel from '@/components/SystemBackground.vue'

const route = useRoute()
const router = useRouter()
const mode = computed(() => route.name)
const id = computed(() => String(route.params.id ?? ''))
const editing = computed(() => mode.value === 'business-system-edit' || mode.value === 'business-system-new')
const empty = (): SystemBackground => ({ name: '', purpose: '', keyProcesses: '', technologyStack: '', currentProblems: '', constraints: '' })
const background = ref(empty())
const uploadIds = ref<string[]>([])
const files = ref<SystemAttachment[]>([])
const systems = ref<SystemSummary[]>([])
const detail = ref<BusinessSystem | null>(null)
const runs = ref<SystemRun[]>([])
const page = ref(0), hasMore = ref(false), historyBusy = ref(false), historyError = ref('')
const loading = ref(false), saving = ref(false), failed = ref(false), conflict = ref(false), error = ref('')
let loadSequence = 0
let historySequence = 0
const runStatus: Record<string, string> = { RUNNING: '执行中', SUCCEEDED: '执行完成', FAILED: '失败', PAUSED: '已暂停', CANCELLED: '已取消', PENDING: '等待中' }
const date = (value: string) => new Date(value).toLocaleString('zh-CN', { hour12: false })
const choices = computed(() => {
  const known = new Set(files.value.map(file => file.uploadId))
  return [...files.value, ...(detail.value?.attachments ?? []).filter(file => !known.has(file.uploadId))]
})

function message(reason: unknown, fallback: string) {
  if (!isAxiosError(reason)) return fallback
  if (reason.response?.status === 404) return '未找到该业务系统。'
  return reason.response?.data?.detail || reason.response?.data?.message || fallback
}

async function history(next: number) {
  const current = ++historySequence
  historyBusy.value = true
  historyError.value = ''
  try {
    const result = await systemHistory(id.value, next)
    if (current !== historySequence) return
    runs.value = result.items
    page.value = next
    hasMore.value = result.hasMore
  } catch (reason) {
    if (current === historySequence) historyError.value = message(reason, '历史运行加载失败。')
  } finally { if (current === historySequence) historyBusy.value = false }
}

async function load() {
  const current = ++loadSequence
  ++historySequence
  loading.value = true
  failed.value = false
  conflict.value = false
  error.value = ''
  detail.value = null
  files.value = []
  runs.value = []
  historyError.value = ''
  page.value = 0
  hasMore.value = false
  try {
    if (mode.value === 'business-systems') {
      const result = await listSystems()
      if (current === loadSequence) systems.value = result
    } else {
      const [profile, options] = await Promise.all([
        id.value ? getSystem(id.value) : null,
        editing.value ? availableSystemUploads() : [],
      ])
      if (current !== loadSequence) return
      detail.value = profile
      background.value = profile ? { ...profile.background } : empty()
      uploadIds.value = profile?.attachments.map(file => file.uploadId) ?? []
      files.value = options
      if (!editing.value) await history(0)
    }
  } catch (reason) {
    if (current === loadSequence) {
      failed.value = true
      error.value = message(reason, '档案加载失败，请重试。')
    }
  } finally { if (current === loadSequence) loading.value = false }
}
watch(() => [route.name, route.params.id], load, { immediate: true })
onBeforeUnmount(() => {
  ++loadSequence
  ++historySequence
})

async function save() {
  if (saving.value || conflict.value) return
  error.value = ''
  const generation = loadSequence
  saving.value = true
  try {
    const draft = { background: { ...background.value }, uploadIds: [...uploadIds.value] }
    const saved = detail.value ? await updateSystem(id.value, detail.value.revision, draft) : await createSystem(draft)
    if (generation === loadSequence) await router.push({ name: 'business-system', params: { id: saved.id } })
  } catch (reason) {
    if (generation !== loadSequence) return
    conflict.value = isAxiosError(reason) && reason.response?.status === 409
    error.value = conflict.value ? '档案已被更新。请保留需要的文字，重新载入最新版后再编辑。' : message(reason, '保存失败，请检查填写内容后重试。')
  } finally { saving.value = false }
}
</script>

<template>
  <section class="systems-page">
    <div class="section-heading">
      <div>
        <RouterLink v-if="mode !== 'business-systems'" class="back" to="/business-systems">← 业务系统列表</RouterLink>
        <h2>{{ editing ? (id ? '编辑业务系统' : '新建业务系统') : mode === 'business-systems' ? '业务系统' : '系统档案' }}</h2>
      </div>
      <RouterLink v-if="mode === 'business-systems'" class="primary-button" to="/business-systems/new">新建系统</RouterLink>
      <div v-if="detail && !editing" class="actions">
        <RouterLink class="secondary-button" :to="{ name: 'business-system-edit', params: { id } }">编辑档案</RouterLink>
        <RouterLink class="primary-button" :to="{ path: '/upgrade-assessment', query: { systemId: id } }">发起升级评估</RouterLink>
      </div>
    </div>
    <p v-if="error" class="error" role="alert">{{ error }} <button v-if="conflict || failed" class="text-button" @click="load">重新载入</button></p>
    <p v-if="loading" role="status">正在载入…</p>
    <template v-else-if="!failed">
      <section v-if="mode === 'business-systems'" class="panel">
        <p class="scope">当前工作区 · 仅本人可见</p>
        <p v-if="!systems.length">还没有业务系统。创建档案后，即可从同一系统发起和回看升级评估。</p>
        <RouterLink v-for="system in systems" :key="system.id" class="system-row" :to="{ name: 'business-system', params: { id: system.id } }">
          <span><strong>{{ system.name }}</strong><small>{{ system.purpose || '用途未填写' }}</small></span>
          <span class="row-meta">修订 {{ system.revision }}<small>{{ date(system.updatedAt) }}</small></span>
        </RouterLink>
      </section>
      <form v-else-if="editing" class="panel editor" @submit.prevent="save">
        <label>名称<input v-model="background.name" required maxlength="120" placeholder="例如：订单履约系统" /></label>
        <div class="field-grid">
          <label v-for="field in backgroundFields" :key="field.key">{{ field.label }}
            <textarea v-model="background[field.key]" rows="3" maxlength="1000" :placeholder="field.key === 'technologyStack' ? '例如：Java 17、Spring Boot 3.2、PostgreSQL' : undefined" />
          </label>
        </div>
        <fieldset>
          <legend>关联资料</legend>
          <label v-for="file in choices" :key="file.uploadId" class="check">
            <input v-model="uploadIds" type="checkbox" :value="file.uploadId" :disabled="!uploadIds.includes(file.uploadId) && (!file.name || uploadIds.length >= 10)" />
            {{ file.name || '资料已不可用，请取消关联' }}
          </label>
          <span v-if="!choices.length" class="subtle">暂无已处理完成的可见资料。可在「项目资料」中上传。</span>
        </fieldset>
        <div class="actions">
          <button class="primary-button" :disabled="saving || conflict" type="submit">{{ saving ? '正在保存…' : '保存档案' }}</button>
          <RouterLink class="secondary-button" :to="id ? { name: 'business-system', params: { id } } : '/business-systems'">取消</RouterLink>
        </div>
      </form>
      <template v-else-if="detail">
        <div class="panel"><SystemBackgroundPanel :system="detail" /></div>
        <section class="panel history">
          <h3>评估历史</h3>
          <p v-if="historyError" role="alert">{{ historyError }} <button class="text-button" @click="history(page)">重试</button></p>
          <p v-else-if="!runs.length" class="subtle">该系统还没有升级评估。</p>
          <RouterLink v-for="run in runs" :key="run.runId" class="system-row" :to="`/runs/${run.runId}`">
            <span><strong>{{ run.projectName }} · {{ run.currentVersion }} → {{ run.targetVersion }}</strong>
              <small>档案修订 {{ run.systemRevision }} · {{ date(run.createdAt) }}</small></span>
            <span class="row-meta">{{ runStatus[run.status] ?? run.status }} ›</span>
          </RouterLink>
          <div v-if="page || hasMore" class="actions pages">
            <button class="secondary-button" :disabled="!page || historyBusy" @click="history(page - 1)">上一页</button>
            <button class="secondary-button" :disabled="!hasMore || historyBusy" @click="history(page + 1)">下一页</button>
          </div>
        </section>
      </template>
    </template>
  </section>
</template>

<style scoped>
.systems-page { max-width: 1050px; }
.section-heading, .actions { display: flex; flex-wrap: wrap; gap: 10px; align-items: center; }
.primary-button { background: var(--accent); color: #081611; border: 0; border-radius: 9px; padding: 10px 16px; font-weight: 700; cursor: pointer; }
.primary-button:disabled { opacity: .5; cursor: default; }
.back, .scope, .row-meta, .system-row small { color: var(--muted); font-size: .85rem; }
.scope, .history h3 { margin: 0 0 15px; }
.history, .pages { margin-top: 18px; }
.system-row { display: flex; justify-content: space-between; align-items: center; gap: 16px; padding: 15px 0; border-top: 1px solid var(--line); color: inherit; }
.system-row strong, .system-row small { display: block; overflow-wrap: anywhere; }
.system-row small { margin-top: 4px; }
.system-row:hover strong, .back:hover { color: var(--accent); }
.row-meta { text-align: right; flex-shrink: 0; }
.editor { display: grid; gap: 18px; }
.field-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; }
.editor label { display: grid; gap: 6px; font-size: .9rem; }
.editor input:not([type=checkbox]), .editor textarea { width: 100%; box-sizing: border-box; background: #0c1315; color: inherit; font: inherit; border: 1px solid var(--line); border-radius: 8px; padding: 10px; }
.editor textarea { resize: vertical; }
fieldset { border: 1px solid var(--line); border-radius: 8px; padding: 12px; }
.check { display: flex !important; align-items: center; margin: 6px 0; }
.error { color: #ffb9b1; }
@media (max-width: 640px) { .field-grid { grid-template-columns: 1fr; } .row-meta { max-width: 36%; } }
</style>
