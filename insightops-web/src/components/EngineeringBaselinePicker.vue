<script setup lang="ts">
import { ref, watch } from 'vue'
import { baselines, baselineDetail, type BaselineSummary, type EngineeringBaseline } from '@/api/engineeringBaselines'
import EngineeringBaselineFacts from './EngineeringBaselineFacts.vue'
const props = defineProps<{ systemId: string; previous?: EngineeringBaseline | null; repeating?: boolean; disabled?: boolean }>()
const model = defineModel<string>({ required: true })
const emit = defineEmits<{ ready: [boolean] }>()
const rows = ref<BaselineSummary[]>([]), detail = ref<EngineeringBaseline | null>(null), error = ref('')
const attempt = ref(0)
let generation = 0
watch([() => props.systemId, model, attempt], async ([system, id]) => {
  const seq = ++generation
  error.value = ''; detail.value = null; emit('ready', !id)
  try {
    const [items, snapshot] = await Promise.all([baselines(system), id ? baselineDetail(system, id) : Promise.resolve(null)])
    if (seq !== generation) return
    rows.value = items
    if (id && (!items.some(r => r.id === id) || snapshot?.baseline.id !== id || snapshot.baseline.systemId !== system)) throw new Error()
    detail.value = snapshot
    emit('ready', true)
  } catch {
    if (seq !== generation) return
    rows.value = []
    error.value = id ? '无法核对已选基线。请重新读取，或明确改选「不关联工程基线」。' : '工程基线列表暂时无法读取。当前选择为不关联。'
  }
}, { immediate: true, flush: 'sync' })
</script>

<template>
  <fieldset class="picker">
    <legend>本次工程基线</legend>
    <p v-if="repeating">上次评估{{ previous ? '使用 v' + previous.baseline.version + ' · ' + previous.baseline.label : '未关联工程基线' }}。要改变本次输入，请在下方另选。</p>
    <label>选择工程基线 <select v-model="model" :disabled="disabled"><option value="">不关联工程基线</option>
      <option v-if="model && !rows.some(r => r.id === model)" :value="model">已选基线（待读取）</option>
      <option v-for="r in rows" :key="r.id" :value="r.id">v{{ r.version }} · {{ r.label }}</option>
    </select></label>
    <p v-if="error" role="status">{{ error }} <button type="button" :disabled="disabled" @click="attempt++">重新读取基线</button></p>
    <details v-if="detail">
      <summary>查看本次采用的事实与来源 · v{{ detail.baseline.version }}</summary>
      <EngineeringBaselineFacts :content="detail.content" :saved="detail.baseline" />
    </details>
  </fieldset>
</template>
<style scoped>
.picker { border:1px solid var(--line); padding:12px 16px; margin-block:14px; } select { margin:6px 0; width:100%; padding:8px; }
summary { cursor:pointer; } p { color:var(--muted); }
</style>

