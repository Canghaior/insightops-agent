<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { buildRoles, buildToolNames, evidenceError, importBuild, type BuildBatch, type BuildModuleFiles, type BuildRole, type BuildTool, type ImportFiles } from '@/api/buildEvidence'

const props = defineProps<{ reportId: string; role: BuildRole }>()
const emit = defineEmits<{ imported: [batch: BuildBatch]; cancel: []; busy: [value: boolean] }>()
const fields = reactive({ label: '', environment: '', executedBy: '', executedAt: '', exitCode: '', sourceNote: '' })
const files = ref<ImportFiles>({ xmlFiles: [] })
const busy = ref(false)
const error = ref('')
let submissionId: string | undefined
let nextModuleKey = 0
const reactor = ref(false)
const buildTool = ref<BuildTool>('MAVEN')
const gradle = computed(() => buildTool.value === 'GRADLE')
const logField = computed(() => gradle.value ? 'gradleLog' : 'mavenLog')
const reportDirectory = computed(() => gradle.value ? 'build/test-results/任务名' : 'target/surefire-reports')
const modules = ref<(BuildModuleFiles & { key: number })[]>([])
const xmlFiles = computed(() => reactor.value ? modules.value.flatMap(module => module.xmlFiles) : files.value.xmlFiles)
const selected = computed(() => [
  ...xmlFiles.value, files.value[logField.value], files.value.commandRecord, files.value.exclusionManifest,
].filter((file): file is globalThis.File => !!file))
function addModule() {
  dirty()
  modules.value.push({ key: nextModuleKey++, path: '', projectName: '', selected: null, xmlFiles: [] })
}
function removeModule(index: number) {
  dirty()
  modules.value.splice(index, 1)
}
function changeMode() {
  dirty()
  files.value.xmlFiles = []
  for (const module of modules.value) module.xmlFiles = []
  if (reactor.value && !modules.value.length) addModule()
}
function changeTool() {
  dirty()
  reactor.value = false
  modules.value = []
  files.value = { xmlFiles: [] }
}
function pickModule(index: number, event: globalThis.Event) {
  dirty()
  modules.value[index].xmlFiles = Array.from((event.target as globalThis.HTMLInputElement).files ?? [])
}

function dirty() { submissionId = undefined; error.value = '' }
function pick(field: keyof Omit<ImportFiles, 'modules'>, event: globalThis.Event) {
  dirty()
  const chosen = (event.target as globalThis.HTMLInputElement).files
  if (field === 'xmlFiles') files.value.xmlFiles = Array.from(chosen ?? [])
  else files.value[field] = chosen?.[0]
}
async function submit() {
  if (busy.value) return
  if (reactor.value && (!modules.value.length || modules.value.some(module => !module.path.trim() || !module.projectName.trim()))) {
    error.value = '请填写每个模块的目录和 Reactor 摘要项目名。'
    return
  }
  const maxXml = reactor.value ? 160 : 40
  if (xmlFiles.value.length > maxXml) {
    error.value = '每批最多 ' + maxXml + ' 份 XML。'
    return
  }
  if (!xmlFiles.value.length && (!reactor.value || !files.value.mavenLog)) {
    error.value = gradle.value ? '请选择同一次 Gradle 测试任务的 JUnit XML。' : '请选择 XML。缺少 XML 的多模块运行可以上传 Reactor 日志。'
    return
  }
  if (xmlFiles.value.some(file => !file.name.startsWith('TEST-') || !file.name.toLowerCase().endsWith('.xml') || file.name.length <= 9)) {
    error.value = '请选择 ' + reportDirectory.value + ' 下原名为 TEST-*.xml 的测试报告。'; return
  }
  if (selected.value.some(file => !file.size || file.size > 2 * 1024 * 1024)
    || selected.value.reduce((sum, file) => sum + file.size, 0) > 12 * 1024 * 1024) {
    error.value = '文件应有内容，每份最多 2 MiB，单批合计最多 12 MiB。'; return
  }
  const exitCode = String(fields.exitCode).trim() === '' ? null : Number(fields.exitCode)
  if (exitCode !== null && !Number.isInteger(exitCode)) { error.value = '构建退出码应为整数。'; return }
  const time = fields.executedAt ? new Date(fields.executedAt) : null
  if (time && !Number.isFinite(time.getTime())) { error.value = '请核对原测试执行时间。'; return }
  error.value = ''
  busy.value = true
  emit('busy', true)
  try {
    submissionId ??= globalThis.crypto.randomUUID()
    const result = await importBuild(props.reportId, {
      role: props.role, requestId: submissionId, label: fields.label.trim() || null,
      buildTool: buildTool.value,
      environment: fields.environment.trim() || null, sourceNote: fields.sourceNote.trim() || null,
      executedBy: fields.executedBy.trim() || null, executedAt: time?.toISOString() ?? null, exitCode,
    }, reactor.value ? { ...files.value, xmlFiles: [], modules: modules.value } : files.value)
    emit('imported', result)
  } catch (cause) {
    error.value = (cause as { response?: { status?: number } })?.response?.status === 413
      ? '服务拒绝了过大的上传。每份原件最多 2 MiB，单批合计最多 12 MiB。已符合时请检查部署环境的上传限制。'
      : evidenceError(cause, '导入结果尚未确认。可用同一组材料重试，或刷新已保存的批次。')
  } finally { busy.value = false; emit('busy', false) }
}
</script>

<template>
  <form class="build-import" @input="dirty" @submit.prevent="submit">
    <fieldset :disabled="busy">
      <legend>导入{{ buildRoles[role] }}</legend>
      <label class="wide">构建工具
        <select v-model="buildTool" @change="changeTool">
          <option v-for="(name, key) in buildToolNames" :key="key" :value="key">{{ name }}</option>
        </select>
      </label>
      <label v-if="!gradle" class="mode wide"><input v-model="reactor" type="checkbox" @change="changeMode" /> Maven 多模块构建</label>
      <template v-if="reactor">
        <p class="hint wide">为每个模块填写根目录相对路径和 Reactor 摘要项目名。聚合根使用 .。可登记无测试或未运行的模块。</p>
        <div v-for="(module, index) in modules" :key="module.key" class="field-grid module wide">
          <label>模块路径<input v-model.trim="module.path" maxlength="240" placeholder="例如 service/core" required /></label>
          <label>Reactor 项目名<input v-model.trim="module.projectName" maxlength="180" required /></label>
          <label>本次选择范围<select v-model="module.selected"><option :value="null">未知</option><option :value="true">选中</option><option :value="false">未选中</option></select></label>
          <label>模块的 Surefire XML<input type="file" multiple accept=".xml" @change="pickModule(index, $event)" /></label>
          <button type="button" class="text-button" @click="removeModule(index)">移除此模块</button>
        </div>
        <div class="wide"><button type="button" class="text-button" :disabled="modules.length >= 32" @click="addModule">增加模块</button></div>
        <p class="hint wide">每批最多 32 个模块、160 份 XML 和 4000 个测试项。每份原件最多 2 MiB，合计最多 12 MiB。</p>
      </template>
      <label v-if="!reactor" :key="buildTool" class="wide">{{ gradle ? 'JUnit XML 原件' : 'Surefire XML 原件' }}
        <input type="file" multiple required accept=".xml" @change="pick('xmlFiles', $event)" />
      </label>
      <p v-if="!reactor" class="hint wide">从 {{ reportDirectory }} 选取 TEST-*.xml。按子项目、测试任务及执行次数分别导入。每批最多 40 份 XML、4000 个测试项，每份最多 2 MiB，合计最多 12 MiB。</p>
      <label class="wide">构建标签或提交号（可选）<input v-model="fields.label" maxlength="180" placeholder="例如 Spring Boot 3.4.2 · 4a021e4c" /></label>
      <label>命令记录（可选 JSON）<input :key="buildTool" type="file" accept=".json" @change="pick('commandRecord', $event)" /></label>
      <label>{{ buildToolNames[buildTool] }} 构建日志（可选）<input :key="buildTool" type="file" accept=".log,.txt" @change="pick(logField, $event)" /></label>
      <label v-if="!gradle" class="wide">排除方法清单（可选 JSON）<input type="file" accept=".json" @change="pick('exclusionManifest', $event)" /></label>
      <details class="wide formats">
        <summary>可选文件格式</summary>
        <p>命令记录支持 command、exit、start、end，也支持 exitCode、startedAt、endedAt。时间需带时区，两种写法同时出现时须一致。</p>
        <pre>{{ gradle ? '{ "command": ["./gradlew", "--console=plain", "test"], "exitCode": 0 }' : '{ "command": ["mvn", "test"], "exitCode": 0 }' }}</pre>
        <template v-if="!gradle">
          <p>排除方法清单提供独立的源码位置，用于说明命令排除的类里列明了哪些方法。</p>
          <pre>{{ '{ "excludedMethods": [{ "className": "DatabaseTests", "methodName": "findOwner", "source": "src/test/java/DatabaseTests.java:42" }] }' }}</pre>
        </template>
      </details>
      <details class="wide declared">
        <summary>填写执行环境与来源（可选）</summary>
        <div class="field-grid">
          <label>原测试执行者<input v-model="fields.executedBy" maxlength="180" placeholder="例如 构建流水线或工程师" /></label>
          <label>原测试执行时间（本地时间）<input v-model="fields.executedAt" type="datetime-local" step="1" /></label>
          <label>执行环境<input v-model="fields.environment" maxlength="1000" placeholder="例如 Windows、JDK 21、内存 H2" /></label>
          <label>构建退出码<input v-model="fields.exitCode" type="number" step="1" /></label>
          <label class="wide">来源说明<textarea v-model="fields.sourceNote" maxlength="2000" rows="2" placeholder="这些文件从哪次构建取得？还有哪些材料缺失？" /></label>
        </div>
        <p class="hint">这些信息将标为用户填写。原测试时间和本次导入时间分别记录。</p>
      </details>
      <p v-if="error" class="stream-error wide" role="alert">{{ error }}</p>
      <div class="actions wide">
        <button class="send-button" type="submit">{{ busy ? '正在保存并解析…' : '保存并解析' }}</button>
        <button class="text-button" type="button" @click="emit('cancel')">取消导入</button>
      </div>
    </fieldset>
  </form>
</template>

<style scoped>
.build-import { margin: .85rem 0; padding: 1rem; border: 1px solid var(--line); border-radius: .6rem }
fieldset, .field-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(14rem, 100%), 1fr)); gap: .75rem }
fieldset { border: 0; padding: 0; margin: 0; min-width: 0 }
legend { font-weight: 700; padding: 0; margin-bottom: .75rem }
label { display: grid; align-content: start; gap: .3rem; min-width: 0 }
.mode { display: flex; align-items: center; gap: .4rem }
.module { border: 1px solid var(--line); border-radius: .4rem; padding: .6rem }
input, textarea { padding: .45rem; width: 100%; min-width: 0 }
.wide { grid-column: 1 / -1 }
.hint, .formats p { color: var(--muted); font-size: .9rem; margin: 0 }
summary { cursor: pointer }
.declared .field-grid, .formats p { margin-top: .65rem }
.declared .hint { margin-top: .5rem }
pre { white-space: pre-wrap; overflow-wrap: anywhere; font-size: .8rem }
.actions { display: flex; align-items: center; gap: .65rem; flex-wrap: wrap }
</style>
