<script setup lang="ts">
import { computed } from 'vue'
import type { EvidenceQuality } from '@/api/runs'
const props = defineProps<{ quality: EvidenceQuality; compact?: boolean }>()
const sections = computed(() => {
  const coverage = props.quality.coverage
  if (!coverage) return []
  const targetGap = coverage.external.reasons.find(reason => reason.code === 'TARGET_VERSION_EVIDENCE_MISSING')
  const externalNotes = targetGap ? [targetGap] : coverage.external.reasons.slice(0, 3)
  return [
    { kind: 'engineering', title: '工程依据', evidence: coverage.engineering, notes: coverage.engineering.reasons, more: [] },
    { kind: 'external', title: '外部资料', evidence: coverage.external, notes: externalNotes,
      more: coverage.external.reasons.filter(reason => !externalNotes.includes(reason)) },
    { kind: 'validation', title: '实际验证', evidence: coverage.validation, notes: coverage.validation.reasons, more: [] },
  ]
})
</script>

<template>
  <div v-if="sections.length" class="evidence-coverage" :class="{ compact }" aria-label="评估依据与验证">
    <section v-for="section in sections" :key="section.kind" class="coverage-section" :aria-label="section.title"
      :class="{ 'coverage-missing': section.evidence.missing }">
      <div class="coverage-heading"><strong>{{ section.title }}</strong><span>{{ section.evidence.label }}</span></div>
      <template v-if="!compact">
        <p>{{ section.evidence.summary }}</p>
        <ul v-if="section.notes.length">
          <li v-for="reason in section.notes" :key="`${reason.code}:${reason.message}`">{{ reason.message }}</li>
        </ul>
        <details v-if="section.more.length" class="coverage-details">
          <summary>其余资料检查（{{ section.more.length }}）</summary>
          <ul><li v-for="reason in section.more" :key="`${reason.code}:${reason.message}`">{{ reason.message }}</li></ul>
        </details>
        <slot v-if="section.kind === 'validation'" name="validation" />
      </template>
    </section>
  </div>
  <div v-else class="evidence-quality" :class="[`quality-${quality.status.toLowerCase()}`, { compact }]" role="status">
    <strong>资料完整性：{{ quality.label }}</strong>
    <template v-if="!compact">
      <p>{{ quality.summary }}</p>
      <ul v-if="quality.reasons.length"><li v-for="reason in quality.reasons" :key="`${reason.code}:${reason.message}`">{{ reason.message }}</li></ul>
    </template>
    <small v-else>未核验结论与业务兼容性</small>
  </div>
</template>

<style scoped>
.evidence-coverage { display: grid; gap: 8px; margin: 12px 0; overflow-wrap: anywhere; }
.coverage-section { padding: 12px 14px; border: 1px solid var(--line); border-radius: 10px; background: var(--panel); }
.coverage-heading { display: flex; align-items: baseline; gap: 6px 12px; flex-wrap: wrap; }
.coverage-heading span, .coverage-section p { color: var(--muted); }
.coverage-section p, .coverage-section ul { margin: 6px 0 0; }
.coverage-section ul { padding-left: 20px; }
.coverage-details { margin-top: 8px; }
.coverage-details summary { cursor: pointer; color: var(--muted); }
.coverage-missing { border-color: #d5b187; }
.coverage-missing .coverage-heading span, .coverage-missing ul { color: #f1c68a; }
.evidence-coverage.compact { gap: 3px; margin: 6px 0 0; font-size: 12px; }
.compact .coverage-section { padding: 0; border: 0; background: transparent; }
.compact .coverage-heading { gap: 3px 8px; }
.evidence-quality { padding: 14px 16px; margin: 12px 0; border: 1px solid #bfccc6; border-radius: 10px; background: #f4f7f5; color: #33463d; overflow-wrap: anywhere; }
.quality-insufficient_evidence { border-color: #d5b187; background: #fff7ed; color: #704719; }
.evidence-quality p { margin: 8px 0; line-height: 1.6; }
.evidence-quality ul { margin: 8px 0 0; padding-left: 20px; line-height: 1.7; }
.evidence-quality.compact { padding: 0; border: 0; background: transparent; color: var(--muted); margin: 4px 0 0; font-size: 12px; }
.quality-insufficient_evidence.compact { color: #f1c68a; }
.evidence-quality small { display: block; font-weight: normal; opacity: .85; margin-top: 3px; }
</style>
