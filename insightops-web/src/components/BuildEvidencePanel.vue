<script setup lang="ts">
import { nextTick, onMounted, reactive, ref, shallowRef } from 'vue'
import {
  batchName, buildRoles, displayTime, evidenceError, getBuild, listBuilds,
  type BuildBatch, type BuildRole, type BuildSummary,
} from '@/api/buildEvidence'
import { compareBuilds, type BuildComparison } from '@/api/buildComparison'
import BuildImportForm from './BuildImportForm.vue'
import BuildBatchDetails from './BuildBatchDetails.vue'
import BuildComparisonResults from './BuildComparisonResults.vue'

const props = defineProps<{ reportId: string }>()
const emit = defineEmits<{ listed: [batches: BuildSummary[]] }>()
const root = ref<globalThis.HTMLElement>()
const summaries = shallowRef<BuildSummary[]>([])
const selected = reactive<Record<BuildRole, string>>({ BASELINE: '', CANDIDATE: '' })
const roles: BuildRole[] = ['BASELINE', 'CANDIDATE']
const importing = ref<BuildRole | null>(null)
const saving = ref(false)
const loading = ref(false)
const listError = ref('')
const actionError = ref('')
const notice = ref('')
const preview = shallowRef<BuildBatch>()
const previewRole = ref<BuildRole>()
const comparing = ref(false)
const compared = shallowRef<{ baseline: BuildBatch; candidate: BuildBatch; result: BuildComparison }>()
let listRequest = 0
let actionRequest = 0

async function refresh() {
  const request = ++listRequest
  loading.value = true
  listError.value = ''
  try {
    const found = await listBuilds(props.reportId)
    if (request !== listRequest) return
    summaries.value = found
    emit('listed', found)
  } catch (cause) {
    if (request === listRequest) listError.value = evidenceError(cause, '构建批次暂时无法读取，请重试。')
  } finally { if (request === listRequest) loading.value = false }
}
onMounted(refresh)

function resetView() {
  ++actionRequest
  comparing.value = false
  actionError.value = ''
  previewRole.value = undefined
  preview.value = undefined
  compared.value = undefined
}
function choose(role: BuildRole, id: string) {
  selected[role] = id
  resetView()
  notice.value = ''
}
function addSummaries(...items: BuildSummary[]) {
  const known = new Map(summaries.value.map(item => [item.id, item]))
  for (const item of items) known.set(item.id, item)
  summaries.value = [...known.values()].sort((a, b) => b.importedAt.localeCompare(a.importedAt))
  emit('listed', summaries.value)
}
function saved(batch: BuildBatch) {
  importing.value = null
  ++listRequest
  loading.value = false
  addSummaries(batch.summary)
  choose(batch.summary.role, batch.summary.id)
  previewRole.value = batch.summary.role
  preview.value = batch
  notice.value = buildRoles[batch.summary.role] + '已保存并解析。可查看原件，或继续对照。'
  void refresh()
}
async function showDetails(role: BuildRole) {
  const id = selected[role]
  if (!id) return
  if (previewRole.value === role) { ++actionRequest; previewRole.value = undefined; preview.value = undefined; return }
  const request = ++actionRequest
  previewRole.value = role
  preview.value = undefined
  actionError.value = ''
  try {
    const batch = await getBuild(props.reportId, id)
    if (request === actionRequest && selected[role] === id) preview.value = batch
  } catch (cause) {
    if (request === actionRequest) { previewRole.value = undefined; actionError.value = evidenceError(cause, '读取原件清单失败，请重试。') }
  }
}
async function compare() {
  const baselineId = selected.BASELINE, candidateId = selected.CANDIDATE
  if (!baselineId || !candidateId || comparing.value) return
  const request = ++actionRequest
  comparing.value = true
  actionError.value = ''
  previewRole.value = undefined
  preview.value = undefined
  compared.value = undefined
  try {
    const [baseline, candidate, result] = await Promise.all([
      getBuild(props.reportId, baselineId), getBuild(props.reportId, candidateId), compareBuilds(props.reportId, baselineId, candidateId),
    ])
    if (request !== actionRequest) return
    if (result.baselineId !== baseline.summary.id || result.candidateId !== candidate.summary.id) {
      actionError.value = '响应批次与本次选择不一致，请重新对照。'
      return
    }
    addSummaries(baseline.summary, candidate.summary)
    compared.value = { baseline, candidate, result }
  } catch (cause) {
    if (request === actionRequest) actionError.value = evidenceError(cause, '暂时无法取得这两批构建的对照，请重试。')
  } finally { if (request === actionRequest) comparing.value = false }
}
async function inspect(baselineId: string, candidateId: string) {
  selected.BASELINE = baselineId
  selected.CANDIDATE = candidateId
  resetView()
  notice.value = '已定位到此验证版本引用的两批构建。'
  await nextTick()
  root.value?.scrollIntoView?.({ block: 'start' })
  await compare()
}
defineExpose({ inspect })
</script>

<template>
  <section ref="root" class="build-panel" aria-label="构建证据与对照">
    <header>
      <h4>构建证据与对照</h4>
      <button type="button" class="text-button" :disabled="loading || saving" @click="refresh">刷新构建批次</button>
    </header>
    <p class="subtle">导入本系统两次已有的 Maven 构建结果。原件用于验证和交接，独立于研究检索。</p>
    <p v-if="listError" role="alert" class="stream-error">{{ listError }}</p>
    <p v-if="notice" role="status" class="success-notice">{{ notice }}</p>
    <div class="build-sides">
      <div v-for="role in roles" :key="role" class="build-slot">
        <label>{{ buildRoles[role] }}批次
          <select :value="selected[role]" :disabled="saving" @change="choose(role, ($event.target as HTMLSelectElement).value)">
            <option value="">请选择{{ buildRoles[role] }}</option>
            <option v-for="batch in summaries.filter(item => item.role === role)" :key="batch.id" :value="batch.id">
              {{ batch.label ? batch.label + ' · ' + displayTime(batch.importedAt) : batchName(batch) }}
            </option>
          </select>
        </label>
        <div class="build-actions">
          <button type="button" class="text-button" :disabled="!!importing" @click="importing = role; notice = ''">导入{{ buildRoles[role] }}</button>
          <button v-if="selected[role]" type="button" class="text-button" :disabled="comparing" @click="showDetails(role)">{{ previewRole === role ? '收起' : '查看' }}{{ role === 'BASELINE' ? '基线' : '候选' }}材料</button>
        </div>
      </div>
    </div>
    <BuildImportForm v-if="importing" :key="importing" :report-id="reportId" :role="importing" @imported="saved"
      @cancel="importing = null" @busy="saving = $event" />
    <p v-if="!loading && !listError && !summaries.length && !importing" class="subtle">本系统还没有构建证据。先导入基线，再导入要核对的候选构建。</p>
    <button type="button" class="secondary-button compare-button" :disabled="!selected.BASELINE || !selected.CANDIDATE || comparing || saving" @click="compare">{{ comparing ? '正在对照…' : '对照两次构建' }}</button>
    <p v-if="actionError" role="alert" class="stream-error">{{ actionError }}</p>
    <p v-if="previewRole && !preview" role="status" class="subtle">正在读取构建材料…</p>
    <BuildBatchDetails v-if="preview" :key="preview.summary.id" :report-id="reportId" :batch="preview" />
    <template v-if="compared">
      <BuildComparisonResults :key="compared.baseline.summary.id + '/' + compared.candidate.summary.id" :report-id="reportId"
        :comparison="compared.result" :baseline="compared.baseline" :candidate="compared.candidate" />
      <slot name="draft" :comparison="compared.result" :baseline="compared.baseline" :candidate="compared.candidate" />
    </template>
  </section>
</template>

<style scoped>
.build-panel { border: 1px solid var(--line); border-radius: .65rem; padding: .85rem; margin: .75rem 0 }
header, .build-actions { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: .35rem .7rem }
h4 { margin: 0; font-size: 1.05rem }
p { margin: .45rem 0 }
.build-sides { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(13rem,100%),1fr)); gap: .6rem }
.build-slot { background: var(--panel-soft); border-radius: .4rem; padding: .6rem; min-width: 0 }
.build-slot label { display: grid; gap: .3rem; font-weight: 600 }
.build-slot select { min-width: 0; width: 100%; padding: .45rem }
.build-actions { justify-content: flex-start; margin-top: .3rem }
.compare-button { margin-top: .7rem }
</style>
