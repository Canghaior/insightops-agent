<script setup lang="ts">
import type { EvidenceQuality } from '@/api/runs'
defineProps<{ quality: EvidenceQuality; compact?: boolean }>()
</script>

<template>
  <div class="evidence-quality" :class="[`quality-${quality.status.toLowerCase()}`, { compact }]" role="status">
    <strong>资料完整性：{{ quality.label }}</strong>
    <template v-if="!compact">
      <p>{{ quality.summary }}</p>
      <ul v-if="quality.reasons.length"><li v-for="reason in quality.reasons" :key="`${reason.code}:${reason.message}`">{{ reason.message }}</li></ul>
    </template>
    <small v-else>未核验结论与业务兼容性</small>
  </div>
</template>

<style scoped>
.evidence-quality { padding: 14px 16px; margin: 12px 0; border: 1px solid #bfccc6; border-radius: 10px; background: #f4f7f5; color: #33463d; overflow-wrap: anywhere; }
.quality-insufficient_evidence { border-color: #d5b187; background: #fff7ed; color: #704719; }
.evidence-quality p { margin: 8px 0; line-height: 1.6; }
.evidence-quality ul { margin: 8px 0 0; padding-left: 20px; line-height: 1.7; }
.evidence-quality.compact { padding: 0; border: 0; background: transparent; color: var(--muted); margin: 4px 0 0; font-size: 12px; }
.quality-insufficient_evidence.compact { color: #f1c68a; }
.evidence-quality small { display: block; font-weight: normal; opacity: .85; margin-top: 3px; }
</style>
