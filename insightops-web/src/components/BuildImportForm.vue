<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { buildRoles, evidenceError, importBuild, type BuildBatch, type BuildRole, type ImportFiles } from '@/api/buildEvidence'

const props = defineProps<{ reportId: string; role: BuildRole }>()
const emit = defineEmits<{ imported: [batch: BuildBatch]; cancel: []; busy: [value: boolean] }>()
const fields = reactive({ label: '', environment: '', executedBy: '', executedAt: '', exitCode: '', sourceNote: '' })
const files = ref<ImportFiles>({ xmlFiles: [] })
const busy = ref(false)
const error = ref('')
let submissionId: string | undefined
const count = computed(() => files.value.xmlFiles.length)
const selected = computed(() => [
  ...files.value.xmlFiles, files.value.mavenLog, files.value.commandRecord, files.value.exclusionManifest,
].filter((file): file is globalThis.File => !!file))

function dirty() { submissionId = undefined; error.value = '' }
function pick(field: keyof ImportFiles, event: globalThis.Event) {
  dirty()
  const chosen = (event.target as globalThis.HTMLInputElement).files
  if (field === 'xmlFiles') files.value.xmlFiles = Array.from(chosen ?? [])
  else files.value[field] = chosen?.[0]
}
async function submit() {
  if (busy.value) return
  if (count.value < 1 || count.value > 40) { error.value = '每个构建请选 1 至 40 份 Surefire XML。'; return }
  if (files.value.xmlFiles.some(file => !file.name.startsWith('TEST-') || !file.name.toLowerCase().endsWith('.xml') || file.name.length <= 9)) {
    error.value = '请选择该模块 target/surefire-reports 下原名为 TEST-*.xml 的测试报告。'; return
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
      environment: fields.environment.trim() || null, sourceNote: fields.sourceNote.trim() || null,
      executedBy: fields.executedBy.trim() || null, executedAt: time?.toISOString() ?? null, exitCode,
    }, files.value)
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
      <label class="wide">Surefire XML 原件
        <input type="file" multiple required accept=".xml" @change="pick('xmlFiles', $event)" />
      </label>
      <p class="hint wide">在该模块的 target/surefire-reports 中选取同一次构建的 TEST-*.xml。每批最多 40 份 XML、4000 个测试项，每份最多 2 MiB，合计最多 12 MiB。按模块和执行次数分别导入。</p>
      <label class="wide">构建标签或提交号（可选）<input v-model="fields.label" maxlength="180" placeholder="例如 Spring Boot 3.4.2 · 4a021e4c" /></label>
      <label>命令记录（可选 JSON）<input type="file" accept=".json" @change="pick('commandRecord', $event)" /></label>
      <label>Maven 构建日志（可选）<input type="file" accept=".log,.txt" @change="pick('mavenLog', $event)" /></label>
      <label class="wide">排除方法清单（可选 JSON）<input type="file" accept=".json" @change="pick('exclusionManifest', $event)" /></label>
      <details class="wide formats">
        <summary>可选文件格式</summary>
        <p>命令记录支持 command、exit、start、end，也支持 exitCode、startedAt、endedAt。时间需带时区，两种写法同时出现时须一致。</p>
        <pre>{{ '{ "command": ["mvn", "test"], "exitCode": 0 }' }}</pre>
        <p>排除方法清单提供独立的源码位置，用于说明命令排除的类里列明了哪些方法。</p>
        <pre>{{ '{ "excludedMethods": [{ "className": "DatabaseTests", "methodName": "findOwner", "source": "src/test/java/DatabaseTests.java:42" }] }' }}</pre>
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
input, textarea { padding: .45rem; width: 100%; min-width: 0 }
.wide { grid-column: 1 / -1 }
.hint, .formats p { color: var(--muted); font-size: .9rem; margin: 0 }
summary { cursor: pointer }
.declared .field-grid, .formats p { margin-top: .65rem }
.declared .hint { margin-top: .5rem }
pre { white-space: pre-wrap; overflow-wrap: anywhere; font-size: .8rem }
.actions { display: flex; align-items: center; gap: .65rem; flex-wrap: wrap }
</style>
