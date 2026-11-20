<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { getResearchModels, modelChoiceLabel, type ResearchModelCatalog } from '@/api/researchModels'

defineProps<{ disabled?: boolean }>()
const choice = defineModel<string>({ required: true })
const catalog = ref<ResearchModelCatalog>({ defaultLabel: '服务端默认', options: [] })
const failed = ref(false)
const loading = ref(false)
const providers = [{ id: 'openai', name: 'OpenAI' }, { id: 'deepseek', name: 'DeepSeek' }]

async function load() {
  loading.value = true
  failed.value = false
  try {
    catalog.value = await getResearchModels()
  } catch {
    failed.value = true
  } finally {
    loading.value = false
  }
}
onMounted(load)
</script>

<template>
  <div class="model-picker">
    <label>研究模型
      <select v-model="choice" :disabled="disabled || loading" aria-label="研究模型">
        <option value="">{{ catalog.defaultLabel === '服务端默认' ? '服务端默认' : '默认 · ' + catalog.defaultLabel }}</option>
        <option v-if="choice && !loading && !catalog.options.some(option => option.id === choice)" :value="choice" disabled>{{ modelChoiceLabel(choice) }} · 当前不可用</option>
        <template v-for="provider in providers" :key="provider.id">
          <optgroup v-if="catalog.options.some(option => option.provider === provider.id)" :label="provider.name">
            <option v-for="option in catalog.options.filter(item => item.provider === provider.id)" :key="option.id" :value="option.id">{{ option.label }}</option>
          </optgroup>
        </template>
      </select>
    </label>
    <button v-if="failed" type="button" class="text-button" @click="load">重新加载模型列表</button>
  </div>
</template>

<style scoped>
.model-picker { display: flex; align-items: center; flex-wrap: wrap; gap: .5rem; }
.model-picker label { display: inline-flex; align-items: center; gap: .6rem; margin: 0; font-weight: 600; }
.model-picker select { width: auto; max-width: 100%; min-height: 2.4rem; padding: .35rem .6rem; color: inherit; background: var(--panel-soft); border: 1px solid var(--line); color-scheme: dark; border-radius: .5rem; }
</style>
