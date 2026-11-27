<script setup lang="ts">
import { computed } from 'vue'
import type { SystemAttachment } from '@/api/businessSystems'

const ids = defineModel<string[]>({ required: true })
const props = defineProps<{ uploads: SystemAttachment[]; ready: boolean; error: string; locked?: boolean }>()
defineEmits<{ refresh: [] }>()
const missing = computed(() => props.ready ? ids.value.filter(id => !props.uploads.some(f => f.uploadId === id)) : [])
function removeMissing() {
  ids.value = ids.value.filter(id => !missing.value.includes(id))
}
</script>

<template>
  <fieldset class="task-choices">
    <legend>补充资料（可选）</legend>
    <p v-if="error" role="status">{{ error }}</p>
    <p v-else-if="!ready">正在读取可选资料…</p>
    <p v-else-if="!uploads.length">当前没有可关联资料。</p>
    <label v-for="file in uploads" :key="file.uploadId" class="task-check">
      <input v-model="ids" type="checkbox" :value="file.uploadId" :disabled="locked" />{{ file.name }}
    </label>
    <div v-if="missing.length" role="alert" class="warning">
      {{ missing.length }} 份已选资料现在无法关联。移除后可保留文字和其余选择。
      <button type="button" class="text-button" :disabled="locked" @click="removeMissing">移除失效的资料选择</button>
    </div>
    <button type="button" class="text-button" @click="$emit('refresh')">刷新可用资料</button>
  </fieldset>
</template>
