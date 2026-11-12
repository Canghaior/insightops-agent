<script setup lang="ts">
import { ref, watch } from 'vue'
import { getRunEvidenceQuality, type EvidenceQuality } from '@/api/runs'
import EvidenceQualityPanel from './EvidenceQualityPanel.vue'
const props = defineProps<{ runId: string; assessedQuality?: EvidenceQuality | null; compact?: boolean }>()
const quality = ref<EvidenceQuality | null>(null)
const loading = ref(false)
const failed = ref(false)
let generation = 0
async function load() {
  const current = ++generation
  quality.value = props.assessedQuality ?? null
  failed.value = false
  loading.value = false
  if (quality.value || !props.runId) return
  loading.value = true
  try {
    const result = await getRunEvidenceQuality(props.runId)
    if (current === generation) quality.value = result
  } catch { if (current === generation) failed.value = true }
  finally { if (current === generation) loading.value = false }
}
watch(() => [props.runId, props.assessedQuality], load, { immediate: true })
</script>

<template>
  <EvidenceQualityPanel v-if="quality" :quality="quality" :compact="compact" />
  <div v-else class="quality-pending" role="status">
    <span v-if="loading">正在检查资料完整性…</span>
    <template v-else-if="failed">资料完整性检查暂不可用；执行完成不代表结论已核验。<button v-if="!compact" type="button" @click.stop="load">重试检查</button></template>
    <span v-else>资料完整性尚未检查。</span>
  </div>
</template>

<style scoped>
.quality-pending { margin: 8px 0; color: var(--muted); font-size: 12px; line-height: 1.5; }
.quality-pending button { margin-left: 6px; border: 0; background: transparent; color: var(--accent); text-decoration: underline; cursor: pointer; }
</style>
