<script setup lang="ts">
import { computed, onBeforeUnmount, ref, shallowRef } from 'vue'
import { packageBaseline } from '@/api/engineeringBaselines'
import { localDirectory, moduleRoots, scanModule, type DirectoryEntry } from '@/utils/moduleMaterials'

const props = defineProps<{ systemId: string; label: string }>()
const emit = defineEmits<{ ready: [file: globalThis.File]; change: []; busy: [value: boolean] }>()
const directory = shallowRef<DirectoryEntry[]>([])
const roots = computed(() => moduleRoots(directory.value))
const module = ref(''), checked = ref<string[]>([]), focus = ref(''), filter = ref('')
const working = ref(false), error = ref(''), choseDirectory = ref(false)
const scanned = computed(() => scanModule(directory.value, module.value))
const optional = computed(() => scanned.value.materials.filter(row => row.path !== 'pom.xml'))
const matches = computed(() => optional.value.filter(row => row.path.toLowerCase().includes(filter.value.toLowerCase())))
const included = computed(() => scanned.value.materials.filter(row => row.path === 'pom.xml' || checked.value.includes(row.path)))
const totalBytes = computed(() => included.value.reduce((total, row) => total + row.file.size, 0))
const selectionError = computed(() => {
  if (new Set(included.value.map(row => row.archivePath)).size !== included.value.length) return '同类执行记录有多个来源，请每类选择一份。'
  if (included.value.length > 30 || totalBytes.value > 6 * 1024 * 1024) return '每包最多 30 份原件，包内清单和材料合计最多 6 MiB。'
  const invalid = included.value.find(row => !row.file.size || row.file.size > 1024 * 1024)
  return invalid ? invalid.path + ' 须为 1 字节至 1 MiB。' : ''
})
const missing = computed(() => [
  ['dependency-tree.txt', '实际依赖树'], ['maven-version.txt', '构建时的 JDK'], ['application.log', '应用启动观察'],
].filter(([name]) => !included.value.some(row => row.archivePath === 'evidence/' + name)).map(([, label]) => label))
let active = true
onBeforeUnmount(() => { active = false })
function changed() { error.value = ''; emit('change') }
function chooseModule() { checked.value = []; filter.value = ''; focus.value = ''; changed() }
function chooseFolder(event: globalThis.Event) {
  const files = (event.target as globalThis.HTMLInputElement).files
  directory.value = localDirectory(Array.from(files ?? []))
  choseDirectory.value = !!files?.length
  module.value = roots.value.length === 1 ? roots.value[0]! : ''
  chooseModule()
}
async function prepare() {
  if (!module.value || working.value || selectionError.value) return
  working.value = true; emit('busy', true); error.value = ''
  try {
    const form = new globalThis.FormData()
    const selection = { label: props.label.trim() || module.value.split('/').at(-1),
      paths: included.value.map(row => row.archivePath),
      focus: focus.value.trim() ? [...new Set(focus.value.trim().split(/\s+/))] : [] }
    form.append('selection', new globalThis.Blob([JSON.stringify(selection)], { type: 'application/json' }), 'selection.json')
    for (const row of included.value) form.append('files', row.file, row.file.name)
    const archive = await packageBaseline(props.systemId, form)
    if (active) emit('ready', new globalThis.File([archive], 'maven-baseline.zip', { type: 'application/zip' }))
  } catch (e) { if (active) error.value = e instanceof Error ? e.message : '工程包生成失败，请核对材料后重试。' }
  finally { if (active) { working.value = false; emit('busy', false) } }
}
</script>

<template>
  <fieldset :disabled="working" class="module-picker">
    <legend>从本机模块准备工程包</legend>
    <label>选择本机目录 <input type="file" webkitdirectory multiple @change="chooseFolder" /></label>
    <small>先在本机查看文件清单，只有确认纳入的原件会提交到当前工作区。</small>
    <p v-if="choseDirectory && !roots.length">目录内未找到模块的 pom.xml。可以改选项目目录或直接导入 POM。</p>
    <label v-if="roots.length">Maven 模块 <select v-model="module" @change="chooseModule"><option value="">选择包含业务代码的模块</option><option v-for="root in roots" :key="root" :value="root">{{ root }}</option></select></label>
    <template v-if="module">
      <p>已纳入根 pom.xml。以下文件按需勾选。</p>
      <label v-if="optional.length > 10">筛选文件 <input v-model="filter" type="search" /></label>
      <label v-for="entry in matches.slice(0, 80)" :key="entry.path" class="file-choice">
        <input v-model="checked" type="checkbox" :value="entry.path" @change="changed" /> {{ entry.path }} · {{ entry.file.size }} 字节
      </label>
      <small v-if="matches.length > 80">显示前 80 项，可用文件名或路径筛选其余文件。</small>
      <details><summary>将提交 {{ included.length }} 份原件 · {{ totalBytes }} 字节</summary><ul><li v-for="entry in included" :key="entry.path">{{ entry.path }}</li></ul></details>
      <p v-if="missing.length">尚未纳入的依据：{{ missing.join('、') }}。</p>
      <details v-if="scanned.tests.length"><summary>发现 {{ scanned.tests.length }} 份 Surefire XML</summary><p>在报告的构建对照中按本次执行另行导入。</p><ul><li v-for="test in scanned.tests" :key="test">{{ test }}</li></ul></details>
      <label>关注的依赖（可选）<input v-model="focus" placeholder="groupId:artifactId，多项以空格分隔" @input="changed" /></label>
      <p v-if="error || selectionError" role="alert">{{ error || selectionError }}</p>
      <button type="button" class="secondary-button" :disabled="working || !!selectionError" @click="prepare">{{ working ? '正在制备工程包…' : '生成工程包并预览' }}</button>
    </template>
  </fieldset>
</template>
<style scoped>
.module-picker { min-width: 0; display: grid; gap: .5rem; border: 1px solid var(--line); }
.file-choice { overflow-wrap: anywhere; } small { color: var(--muted); } button { justify-self: start; }
</style>
