<script setup lang="ts">
import { computed } from 'vue'

import { renderSafeMarkdown } from '@/utils/markdown'

const props = defineProps<{ content: string }>()
const rendered = computed(() => renderSafeMarkdown(props.content))
</script>

<template>
  <!-- renderSafeMarkdown escapes raw HTML and only links trusted GitHub Release evidence. -->
  <!-- eslint-disable-next-line vue/no-v-html -->
  <div class="markdown-content" v-html="rendered"></div>
</template>
