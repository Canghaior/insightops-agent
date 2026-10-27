<script setup lang="ts">
import { computed } from 'vue'

import { renderSafeMarkdown } from '@/utils/markdown'

const props = defineProps<{ content: string; sources?: string[] }>()
const rendered = computed(() => renderSafeMarkdown(props.content, props.sources))
</script>

<template>
  <!-- Raw HTML is escaped; links require a Release URL or an actual evidence source. -->
  <!-- eslint-disable-next-line vue/no-v-html -->
  <div class="markdown-content" v-html="rendered"></div>
</template>
<style scoped>
.markdown-content :deep(.markdown-table-scroll){overflow-x:auto;margin:12px 0}
.markdown-content :deep(table){width:100%;border-collapse:collapse;font-size:13px}
.markdown-content :deep(th),.markdown-content :deep(td){padding:8px;border:1px solid var(--border);text-align:left;vertical-align:top}
</style>
