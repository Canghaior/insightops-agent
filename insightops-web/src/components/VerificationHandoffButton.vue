<script setup lang="ts">
import { ref } from 'vue'
import { downloadVerificationHandoff } from '@/api/reportReview'
import { downloadExecution } from '@/api/reportTasks'
import { evidenceError } from '@/api/buildEvidence'

const props = defineProps<{ reportId: string; versionId: string; version: number; execution?: boolean; label?: string }>()
const busy = ref(false)
const error = ref('')
async function download() {
  if (busy.value) return
  busy.value = true
  error.value = ''
  try {
    const file = await (props.execution ? downloadExecution : downloadVerificationHandoff)(props.reportId, props.versionId)
    const url = globalThis.URL.createObjectURL(file)
    const anchor = globalThis.document.createElement('a')
    anchor.href = url
    anchor.download = `InsightOps-${props.execution ? '任务执行' : '验证交接'}-第${props.version}版-${props.versionId}.zip`
    globalThis.document.body.appendChild(anchor)
    anchor.click()
    anchor.remove()
    globalThis.setTimeout(() => globalThis.URL.revokeObjectURL(url), 30_000)
  } catch (cause) {
    error.value = evidenceError(cause, '暂时无法取得所选版本的交接包，请重试。')
    const data = (cause as { response?: { data?: globalThis.Blob } }).response?.data
    if (data instanceof globalThis.Blob && data.size < 16_384) {
      try {
        const problem = JSON.parse(await data.text()) as { detail?: string }
        if (problem.detail) error.value = problem.detail
      } catch { /* Keep the local message when the server has no problem detail. */ }
    }
  } finally { busy.value = false }
}
</script>

<template>
  <div class="handoff-action">
    <button type="button" class="secondary-button" :disabled="busy" @click="download">{{ busy ? '正在生成交接包…' : label || '下载第 ' + version + ' 版验证交接包' }}</button>
    <span v-if="error" role="alert" class="stream-error">{{ error }}</span>
  </div>
</template>

<style scoped>
.handoff-action { display: grid; justify-items: start; gap: .3rem }
</style>
