<script setup lang="ts">
import { ref, watch } from 'vue'
import { saveRunReport, downloadReport } from '@/api/reports'
const props = defineProps<{ runId: string; canSave: boolean; repeatUrl: string }>()
const busy = ref(false), reportId = ref(''), message = ref(''), failed = ref(false)
watch(() => props.runId, () => { reportId.value = ''; message.value = '' })
async function deliver(format?: 'md' | 'pdf') {
  const runId = props.runId
  busy.value = true; message.value = ''; failed.value = false
  try {
    const id = reportId.value || (await saveRunReport(runId)).id
    if (props.runId !== runId) return
    reportId.value = id
    if (format) {
      const blob = await downloadReport(id, format)
      const url = globalThis.URL.createObjectURL(blob), anchor = globalThis.document.createElement('a')
      anchor.href = url; anchor.download = `insightops-research-${runId}.${format}`
      globalThis.document.body.appendChild(anchor); anchor.click(); anchor.remove()
      globalThis.setTimeout(() => globalThis.URL.revokeObjectURL(url), 30_000)
      message.value = '下载已开始，报告已保存在「研究报告」中。'
    } else message.value = '已保存为研究报告，引用与输入快照一并保留。'
  } catch {
    failed.value = true
    message.value = reportId.value ? '报告已保存，但下载失败，请重试下载。' : '报告保存失败，请确认研究已成功完成后重试。'
  } finally { busy.value = false }
}
</script>
<template>
  <div class="result-actions">
    <div class="buttons">
      <button v-if="canSave" class="secondary-button" :disabled="busy" @click="deliver()">{{ reportId ? '研究报告已保存' : '保存为研究报告' }}</button>
      <button v-if="canSave" class="primary-button" :disabled="busy" @click="deliver('md')">下载 Markdown</button>
      <button v-if="canSave" class="secondary-button" :disabled="busy" @click="deliver('pdf')">下载 PDF</button>
      <RouterLink class="secondary-button" :to="repeatUrl">使用当前参数再次研究</RouterLink>
    </div>
    <p v-if="busy" role="status">正在准备报告…</p>
    <p v-if="message" :role="failed ? 'alert' : 'status'">{{ message }} <RouterLink v-if="reportId" to="/reports">查看报告</RouterLink></p>
  </div>
</template>
<style scoped>
.result-actions{padding:16px 0}.buttons{display:flex;flex-wrap:wrap;gap:10px}.result-actions p{color:var(--muted);font-size:13px}
</style>
