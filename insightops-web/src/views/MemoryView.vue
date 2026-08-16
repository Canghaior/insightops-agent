<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { createMemory, deleteMemory, listMemories, updateMemory, type MemoryCategory, type UserMemory } from '@/api/memories'

const memories = ref<UserMemory[]>([]); const error = ref(''); const saving = ref(false)
const form = reactive<{ key: string; value: string; category: MemoryCategory }>({ key: '', value: '', category: 'PREFERENCE' })
async function load() { try { memories.value = await listMemories() } catch { error.value = '长期记忆加载失败' } }
async function add() {
  if (!form.key.trim() || !form.value.trim()) return
  saving.value = true; error.value = ''
  try { memories.value.unshift(await createMemory(form.key.trim(), form.value.trim(), form.category)); form.key = ''; form.value = '' }
  catch { error.value = '保存失败：记忆名称可能已经存在' } finally { saving.value = false }
}
async function save(memory: UserMemory) { try { Object.assign(memory, await updateMemory(memory)) } catch { error.value = '更新记忆失败' } }
async function remove(memory: UserMemory) { if (!globalThis.confirm(`删除“${memory.key}”？`)) return; await deleteMemory(memory.id); memories.value = memories.value.filter(item => item.id !== memory.id) }
onMounted(load)
</script>

<template>
  <section>
    <div class="section-heading"><div><span class="eyebrow">可管理的长期记忆</span><h2>告诉 Agent 如何为你工作</h2></div><span class="subtle">仅用于个性化，不作为事实证据</span></div>
    <form class="panel memory-form" @submit.prevent="add">
      <input v-model="form.key" maxlength="80" placeholder="名称，例如：回答风格" />
      <select v-model="form.category"><option value="PROFILE">个人资料</option><option value="PREFERENCE">偏好</option><option value="INTEREST">关注领域</option><option value="CONSTRAINT">约束</option></select>
      <textarea v-model="form.value" maxlength="1000" placeholder="例如：先给结论，再列证据和风险" />
      <button class="send-button" :disabled="saving">添加记忆</button>
    </form>
    <p v-if="error" class="stream-error">{{ error }}</p>
    <div class="memory-list">
      <article v-for="memory in memories" :key="memory.id" class="panel memory-item">
        <header><strong>{{ memory.key }}</strong><label><input v-model="memory.enabled" type="checkbox" @change="save(memory)" /> 启用</label></header>
        <textarea v-model="memory.value" maxlength="1000" @change="save(memory)" />
        <footer><code>{{ memory.category }}</code><button class="text-button" @click="remove(memory)">删除</button></footer>
      </article>
      <div v-if="!memories.length" class="conversation-empty"><strong>还没有长期记忆</strong><p>添加后会在之后的模型对话中自动生效。</p></div>
    </div>
  </section>
</template>
