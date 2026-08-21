<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'

import {
  createMcpConnection,
  deleteMcpConnection,
  listMcpConnections,
  updateMcpConnection,
  type McpConnection,
} from '@/api/mcpConnections'

const connections = ref<McpConnection[]>([])
const saving = ref(false)
const error = ref('')
const editingId = ref('')
const form = reactive({ name: '', endpoint: '', tools: '', enabled: false })

async function load() {
  try { connections.value = await listMcpConnections() }
  catch { error.value = 'MCP 连接加载失败。' }
}

function allowedTools(text: string): Record<string, string> {
  const result: Record<string, string> = {}
  for (const line of text.split('\n').map((item) => item.trim()).filter(Boolean)) {
    const [name, ...description] = line.split('=')
    if (name?.trim()) result[name.trim()] = description.join('=').trim()
  }
  return result
}

function edit(item: McpConnection) {
  const tools = JSON.parse(item.allowedToolsJson) as Record<string, string>
  editingId.value = item.id
  form.name = item.name
  form.endpoint = item.endpoint
  form.tools = Object.entries(tools).map(([name, description]) => `${name}=${description}`).join('\n')
  form.enabled = item.enabled
}

function reset() {
  editingId.value = ''; form.name = ''; form.endpoint = ''; form.tools = ''; form.enabled = false
}

async function save() {
  const tools = allowedTools(form.tools)
  if (!form.name.trim() || !form.endpoint.trim() || !Object.keys(tools).length) return
  saving.value = true; error.value = ''
  try {
    const payload = { name: form.name.trim(), endpoint: form.endpoint.trim(), allowedTools: tools, enabled: form.enabled }
    if (editingId.value) await updateMcpConnection(editingId.value, payload)
    else await createMcpConnection(payload)
    reset(); await load()
  } catch { error.value = '保存失败：只允许公网 HTTPS 443 端点，请检查名称和工具白名单。' }
  finally { saving.value = false }
}

async function remove(item: McpConnection) {
  if (!globalThis.confirm(`删除 MCP 连接“${item.name}”？`)) return
  await deleteMcpConnection(item.id)
  connections.value = connections.value.filter((current) => current.id !== item.id)
}

onMounted(load)
</script>

<template>
  <section>
    <div class="section-heading"><div><span class="eyebrow">P2.0-D · Controlled extension</span><h2>Agent 工具与 MCP</h2></div><span class="subtle">默认关闭 · 只读白名单</span></div>
    <div class="panel mcp-policy">
      <strong>当前安全边界</strong>
      <ul><li>只允许公网 HTTPS 443、无重定向的 JSON-RPC MCP 端点。</li><li>Agent 只能调用这里明确列出的工具名；连接默认关闭。</li><li>不支持本地进程、任意命令、私网地址或 MCP 写工具。</li></ul>
    </div>
    <form class="panel mcp-form" @submit.prevent="save">
      <input v-model="form.name" maxlength="100" placeholder="连接名称" />
      <input v-model="form.endpoint" maxlength="1000" placeholder="https://mcp.example.com/mcp" />
      <textarea v-model="form.tools" placeholder="每行一个白名单工具：search_docs=搜索官方文档" />
      <label><input v-model="form.enabled" type="checkbox" /> 验证配置后启用</label>
      <div><button v-if="editingId" type="button" class="secondary-button" @click="reset">取消编辑</button><button class="send-button" :disabled="saving">{{ editingId ? '保存修改' : '添加连接' }}</button></div>
    </form>
    <p v-if="error" class="stream-error">{{ error }}</p>
    <div class="mcp-grid">
      <article v-for="item in connections" :key="item.id" class="panel mcp-card">
        <header><div><span class="eyebrow">{{ item.enabled ? '已启用' : '已停用' }}</span><h3>{{ item.name }}</h3></div><span class="approval-status">{{ Object.keys(JSON.parse(item.allowedToolsJson)).length }} 个工具</span></header>
        <code>{{ item.endpoint }}</code>
        <p>{{ Object.keys(JSON.parse(item.allowedToolsJson)).join(' · ') }}</p>
        <footer><button class="secondary-button" @click="edit(item)">编辑</button><button class="text-button" @click="remove(item)">删除</button></footer>
      </article>
      <div v-if="!connections.length" class="panel conversation-empty"><strong>尚未配置 MCP</strong><p>系统当前不会向任何外部 MCP 端点发送数据。</p></div>
    </div>
  </section>
</template>

<style scoped>
.mcp-policy,.mcp-form { padding: 22px 24px; margin-bottom: 18px; }.mcp-policy li { margin: 7px 0; color: var(--muted); }
.mcp-form { display: grid; grid-template-columns: 1fr 2fr; gap: 14px; }.mcp-form textarea { grid-column: 1 / -1; min-height: 110px; }.mcp-form > div { display: flex; justify-content: flex-end; gap: 10px; }
.mcp-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; }.mcp-card { padding: 22px; }.mcp-card header,.mcp-card footer { display: flex; justify-content: space-between; align-items: center; gap: 14px; }.mcp-card code { display: block; margin: 16px 0; overflow-wrap: anywhere; }.mcp-card footer { justify-content: flex-end; }
@media (max-width: 900px) { .mcp-form,.mcp-grid { grid-template-columns: 1fr; } }
</style>
