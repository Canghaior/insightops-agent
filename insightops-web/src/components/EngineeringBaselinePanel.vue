<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { isAxiosError } from 'axios'
import { baselines, baselineDetail, previewBaseline, saveBaseline, type BaselineSummary, type EngineeringBaseline, type BaselinePreview } from '@/api/engineeringBaselines'
import EngineeringBaselineFacts from './EngineeringBaselineFacts.vue'
import ModuleBaselinePicker from './ModuleBaselinePicker.vue'

const props = defineProps<{ systemId: string }>()
const rows = ref<BaselineSummary[]>([]), selected = ref<EngineeringBaseline | null>(null)
const preview = ref<BaselinePreview | null>(null), file = ref<globalThis.File | null>(null)
const label = ref(''), note = ref(''), expectedJava = ref(''), message = ref(''), error = ref('')
const busy = ref(false), importing = ref(false)
const mode = ref<'file' | 'directory'>('file'), packing = ref(false)
const fileError = computed(() => {
  const original = file.value
  if (!original) return ''
  const name = original.name.toLowerCase()
  const mib = 1024 * 1024
  if (!name.endsWith('.xml') && !name.endsWith('.zip')) return '请选择单模块 Maven 的 POM XML 或规范工程 ZIP。'
  if (!original.size) return '选中的工程文件为空，请重新选择。'
  if (name.endsWith('.xml') && original.size > mib) return 'POM 文件最多 1 MiB，请选择该模块的根 POM。'
  if (name.endsWith('.zip') && original.size > 6 * mib + 65536) return '工程 ZIP 超出大小上限，请缩小工程包范围，或单独导入模块的 POM。'
  return ''
})
let generation = 0, requestId = ''
function explain(e: unknown) {
  if (isAxiosError(e) && e.response?.status === 413) return '工程材料超过上传上限。根 POM 最多 1 MiB，工程包内材料和清单合计最多 6 MiB。'
  return isAxiosError(e) ? (e.response?.data?.detail || '读取失败，请重试。') : e instanceof Error ? e.message : '请求失败，请重试。'
}
function invalidate() { preview.value = null; requestId = ''; message.value = ''; error.value = '' }
watch([file, label, note, expectedJava], invalidate, { flush: 'sync' })
async function load() {
  const current = ++generation
  try { const items = await baselines(props.systemId); if (current === generation) rows.value = items }
  catch (e) { if (current === generation) error.value = explain(e) }
}
watch(() => props.systemId, () => { rows.value = []; selected.value = null; importing.value = false; busy.value = false; file.value = null; label.value = ''; note.value = ''; expectedJava.value = ''; invalidate(); void load() }, { immediate: true })
async function open(id: string) {
  const current = ++generation
  selected.value = null; error.value = ''
  try { const result = await baselineDetail(props.systemId, id); if (current === generation) selected.value = result }
  catch (e) { if (current === generation) error.value = explain(e) }
}
function pick(event: globalThis.Event) { file.value = (event.target as globalThis.HTMLInputElement).files?.[0] ?? null }
async function submit(save: boolean) {
  if (!file.value || fileError.value || busy.value || (save && !preview.value)) return
  const current = ++generation
  const body = new globalThis.FormData()
  body.append('file', file.value); body.append('label', label.value); body.append('note', note.value); body.append('expectedJava', expectedJava.value)
  busy.value = true; error.value = ''
  try {
    if (save && preview.value) {
      body.append('requestId', requestId); body.append('token', preview.value.token)
      const saved = await saveBaseline(props.systemId, body)
      if (generation !== current) return
      selected.value = saved; rows.value = [saved.baseline, ...rows.value.filter(r => r.id !== saved.baseline.id)]
      importing.value = false; preview.value = null; file.value = null; message.value = '已保存工程基线 v' + saved.baseline.version + '。'
    } else {
      const result = await previewBaseline(props.systemId, body)
      if (generation !== current) return
      preview.value = result; requestId = globalThis.crypto.randomUUID()
    }
  } catch (e) { if (current === generation) { if (isAxiosError(e) && e.response?.status === 409) invalidate(); error.value = explain(e) } }
  finally { if (current === generation) busy.value = false }
}
function begin() { importing.value = true; selected.value = null; mode.value = 'file'; packing.value = false; file.value = null; label.value = ''; note.value = ''; expectedJava.value = ''; invalidate() }
function changeMode(next: 'file' | 'directory') {
  if (mode.value === next) return
  mode.value = next; file.value = null; invalidate()
}
function packed(archive: globalThis.File) { file.value = archive; void submit(false) }
function downloadArchive() {
  if (!file.value) return
  const url = globalThis.URL.createObjectURL(file.value)
  const link = globalThis.document.createElement('a'); link.href = url; link.download = file.value.name; link.click()
  globalThis.setTimeout(() => globalThis.URL.revokeObjectURL(url), 30_000)
}
</script>

<template>
  <section class="panel baseline-panel" aria-label="工程基线">
    <header><h3>工程基线</h3><button v-if="!importing" type="button" class="secondary-button" :disabled="busy" @click="begin">导入工程资料</button></header>
    <p>保存单个 Maven 模块的工程事实与原件。发起评估时可选择要使用的版本。</p>
    <p v-if="error" role="alert">{{ error }} <button v-if="!importing" type="button" @click="load">重新读取</button></p><p v-if="message" role="status">{{ message }}</p>
    <div v-if="rows.length" class="versions"><button v-for="r in rows" :key="r.id" type="button" :disabled="busy || packing" @click="importing = false; open(r.id)">v{{ r.version }} · {{ r.label }}</button></div>
    <p v-else-if="!error">尚无已保存的工程基线。</p>
    <form v-if="importing" @submit.prevent="submit(!!preview)">
      <fieldset :disabled="busy || packing">
        <legend>导入一个模块</legend>
        <div class="versions"><button type="button" :aria-pressed="mode === 'file'" @click="changeMode('file')">已有 POM 或工程包</button>
          <button type="button" :aria-pressed="mode === 'directory'" @click="changeMode('directory')">选择本机模块目录</button></div>
        <template v-if="mode === 'file'">
        <label>工程原件 <input type="file" accept=".xml,.zip" required :aria-invalid="!!fileError" aria-describedby="baseline-file-help" @change="pick" /></label>
        <small id="baseline-file-help">先选一个 Maven 模块的根 POM。POM 可以保留自定义的 .xml 文件名。手头是普通源码 ZIP 时，先解压再选根 POM。</small>
        <details>
          <summary>规范 ZIP 需要哪些文件</summary>
          <small>使用 scripts/maven_baseline_pack.py 生成 v1 工程包，包含 baseline.json、sources/pom.xml 和 evidence/extractor.json。可随包带入该模块的构建记录。评估读取该版本的结构化事实，原件可单独下载。</small>
        </details>
        </template>
        <ModuleBaselinePicker v-else :key="systemId" :system-id="systemId" :label="label" @change="file = null; invalidate()" @ready="packed" @busy="packing = $event" />
        <p v-if="fileError" role="alert">{{ fileError }}</p>
        <label>基线名称 <input v-model="label" maxlength="160" placeholder="例如 Petclinic · 升级前" /></label>
        <label>我认为 POM 声明的 Java 版本（可选） <input v-model="expectedJava" maxlength="32" /></label>
        <label>来源或环境说明（用户提供，可选） <textarea v-model="note" maxlength="600" /></label>
      </fieldset>
      <EngineeringBaselineFacts v-if="preview" :content="preview.content" />
      <button v-if="mode === 'file' || file" type="submit" class="primary-button" :disabled="busy || packing || !file || !!fileError">{{ preview ? '保存为新基线版本' : '预览工程资料' }}</button>
      <button v-if="mode === 'directory' && file" type="button" :disabled="busy || packing" @click="downloadArchive">下载可导入的工程包</button>
      <button type="button" :disabled="busy || packing" @click="importing = false">取消</button>
    </form>
    <EngineeringBaselineFacts v-if="!importing && selected" :content="selected.content" :saved="selected.baseline" downloads />
  </section>
</template>
<style scoped>
.baseline-panel { padding: 20px; } header,.versions { display:flex; align-items:center; flex-wrap:wrap; gap:12px; } header { justify-content:space-between; }
fieldset,label { display:grid; gap:8px; } fieldset { margin:14px 0; border:1px solid var(--line); } label { margin:6px 0; }
button { cursor:pointer; margin:4px; } .versions button { text-align:left; } small { color:var(--muted); }
</style>

