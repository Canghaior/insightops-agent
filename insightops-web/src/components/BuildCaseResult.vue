<script setup lang="ts">
import { buildFileUrl, caseStatuses, type CaseOccurrence } from '@/api/buildEvidence'

defineProps<{ reportId: string; batchId: string; result: CaseOccurrence; fileName?: string }>()
</script>

<template>
  <div class="case-outcome">
    <span :class="{ attention: result.status === 'ERROR' || result.status === 'FAILURE' }">{{ caseStatuses[result.status] }}</span>
    <a :href="buildFileUrl(reportId, batchId, result.fileId)" :title="fileName" download>原件 · 第 {{ result.ordinal }} 项</a>
    <details v-if="result.type || result.message || result.stack">
      <summary>{{ result.status === 'SKIPPED' ? '跳过原因' : '异常详情与堆栈' }}</summary>
      <p v-if="fileName">{{ fileName }}</p>
      <p v-if="result.type"><strong>{{ result.type }}</strong></p>
      <p v-if="result.message">{{ result.message }}</p>
      <pre v-if="result.stack">{{ result.stack }}</pre>
    </details>
  </div>
</template>

<style scoped>
.case-outcome { font-size: .88rem; overflow-wrap: anywhere }
.case-outcome + .case-outcome { border-top: 1px solid var(--line); margin-top: .45rem; padding-top: .45rem }
span { font-weight: 600; margin-inline-end: .45rem }
.attention { color: #ffbc91 }
summary { cursor: pointer; margin-top: .25rem }
p, pre { margin: .4rem 0; white-space: pre-wrap; overflow-wrap: anywhere }
pre { padding: .65rem; background: var(--panel-soft); border-radius: .35rem; max-height: 20rem; overflow: auto }
</style>
