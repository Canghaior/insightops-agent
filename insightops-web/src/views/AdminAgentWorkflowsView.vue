<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'

import {
  activateAgentWorkflowVersion,
  createAgentWorkflowTemplate,
  createAgentWorkflowVersion,
  getAgentWorkflowOverview,
  previewAgentWorkflow,
  type AgentWorkflowOverview,
  type WorkflowNodePreview,
  type WorkflowPreview,
  type WorkflowTemplate,
  type WorkflowVersion,
} from '@/api/agentWorkflows'

interface EditableNode {
  id: string
  toolName: string
  argumentsJson: string
  dependsOn: string
  condition: string
  required: boolean
}

const router = useRouter()
const overview = ref<AgentWorkflowOverview>({ templates: [], tools: [], maxNodes: 32 })
const selectedTemplateId = ref('')
const selectedVersionId = ref('')
const nodes = ref<EditableNode[]>([])
const preview = ref<WorkflowPreview | null>(null)
const loading = ref(true)
const saving = ref(false)
const error = ref('')
const success = ref('')
const form = reactive({
  name: '', description: '', category: 'TECH_RESEARCH', summary: '',
  entryQuestion: '', reason: '',
})

const selectedTemplate = computed(() => overview.value.templates.find(
  (item) => item.id === selectedTemplateId.value,
))
const selectedVersion = computed(() => selectedTemplate.value?.versions.find(
  (item) => item.id === selectedVersionId.value,
))
const isNewTemplate = computed(() => !selectedTemplateId.value)

function defaultArguments(toolName: string) {
  if (toolName === 'knowledge_hybrid_search') {
    return JSON.stringify({ query: '填写本节点需要检索的官方证据', candidateLimit: 12 }, null, 2)
  }
  if (toolName === 'github_release_list') {
    return JSON.stringify({ projectIds: [], maxReleasesPerProject: 10, includePrereleases: false }, null, 2)
  }
  return '{}'
}

function resetEditor() {
  selectedTemplateId.value = ''
  selectedVersionId.value = ''
  form.name = ''
  form.description = ''
  form.category = 'TECH_RESEARCH'
  form.summary = ''
  form.entryQuestion = ''
  form.reason = ''
  const toolName = overview.value.tools[0]?.name ?? 'knowledge_hybrid_search'
  nodes.value = [{
    id: 'research_1', toolName, argumentsJson: defaultArguments(toolName),
    dependsOn: '', condition: 'ALWAYS', required: true,
  }]
  preview.value = null
  error.value = ''
  success.value = ''
}

function parseVersion(item: WorkflowVersion) {
  const graph = JSON.parse(item.graphSpecJson) as {
    reason?: string
    nodes?: Array<{
      id?: string; toolName?: string; arguments?: Record<string, unknown>
      dependsOn?: string[]; condition?: string; required?: boolean
    }>
  }
  form.summary = item.summary
  form.entryQuestion = item.entryQuestion
  form.reason = graph.reason ?? ''
  nodes.value = (graph.nodes ?? []).map((node) => ({
    id: node.id ?? '',
    toolName: node.toolName ?? overview.value.tools[0]?.name ?? '',
    argumentsJson: JSON.stringify(node.arguments ?? {}, null, 2),
    dependsOn: (node.dependsOn ?? []).join(', '),
    condition: node.condition ?? 'ALL_SUCCESS',
    required: node.required !== false,
  }))
  preview.value = null
}

function selectVersion(versionId: string) {
  const version = selectedTemplate.value?.versions.find((item) => item.id === versionId)
  if (!version) return
  selectedVersionId.value = version.id
  parseVersion(version)
}

function selectTemplate(item: WorkflowTemplate, preferredVersionId = '', preserveMessages = false) {
  selectedTemplateId.value = item.id
  form.name = item.name
  form.description = item.description
  form.category = item.category
  const version = item.versions.find((current) => current.id === preferredVersionId)
    ?? item.versions.find((current) => current.id === item.activeVersionId)
    ?? item.versions[0]
  if (version) selectVersion(version.id)
  if (!preserveMessages) error.value = ''
  if (!preserveMessages) success.value = ''
}

async function load(preferredId = selectedTemplateId.value, preferredVersionId = '') {
  loading.value = true
  error.value = ''
  try {
    overview.value = await getAgentWorkflowOverview()
    const preferred = overview.value.templates.find((item) => item.id === preferredId)
      ?? overview.value.templates[0]
    if (preferred) selectTemplate(preferred, preferredVersionId, true)
    else resetEditor()
  } catch {
    error.value = '工作流模板加载失败，请稍后重试。'
  } finally {
    loading.value = false
  }
}

function dependencies(value: string) {
  return value.split(',').map((item) => item.trim()).filter(Boolean)
}

function graph(): Record<string, unknown> {
  return {
    reason: form.reason.trim(),
    nodes: nodes.value.map((node) => ({
      id: node.id.trim(),
      toolName: node.toolName,
      arguments: JSON.parse(node.argumentsJson) as Record<string, unknown>,
      dependsOn: dependencies(node.dependsOn),
      condition: node.condition,
      required: node.required,
    })),
  }
}

async function runPreview() {
  error.value = ''
  success.value = ''
  try {
    preview.value = await previewAgentWorkflow(graph())
    success.value = `预检通过：${preview.value.nodeCount} 个节点，${preview.value.waves.length} 层。`
    return true
  } catch {
    preview.value = null
    error.value = '预检失败：请检查节点 ID、依赖、工具参数和 JSON 格式。'
    return false
  }
}

function addNode() {
  if (nodes.value.length >= overview.value.maxNodes) return
  const toolName = overview.value.tools[0]?.name ?? ''
  nodes.value.push({
    id: `research_${nodes.value.length + 1}`, toolName,
    argumentsJson: defaultArguments(toolName), dependsOn: '',
    condition: nodes.value.length ? 'ALL_SUCCESS' : 'ALWAYS', required: true,
  })
  preview.value = null
}

function removeNode(index: number) {
  if (nodes.value.length === 1) return
  nodes.value.splice(index, 1)
  preview.value = null
}

function changeTool(node: EditableNode) {
  node.argumentsJson = defaultArguments(node.toolName)
  preview.value = null
}

async function save() {
  if (!form.entryQuestion.trim() || !form.summary.trim()) {
    error.value = '请填写版本摘要和入口研究问题。'
    return
  }
  if (!(await runPreview())) return
  saving.value = true
  try {
    const version = {
      summary: form.summary.trim(), entryQuestion: form.entryQuestion.trim(), graph: graph(),
    }
    const result = isNewTemplate.value
      ? await createAgentWorkflowTemplate({
        name: form.name.trim(), description: form.description.trim(),
        category: form.category.trim(), version,
      })
      : await createAgentWorkflowVersion(selectedTemplateId.value, version)
    const savedVersionId = result.versions[0]?.id ?? ''
    await load(result.id, savedVersionId)
    success.value = isNewTemplate.value ? '模板 v1 已保存为草稿。' : '不可变新版本已创建。'
  } catch {
    error.value = '保存失败：模板名称可能重复，或工作流未通过治理校验。'
  } finally {
    saving.value = false
  }
}

async function activate() {
  const template = selectedTemplate.value
  const version = selectedVersion.value
  if (!template || !version) return
  if (!(await runPreview())) return
  if (!globalThis.confirm(`激活“${template.name}”v${version.version}？`)) return
  saving.value = true
  try {
    await activateAgentWorkflowVersion(template.id, version.id, 'P2.4-A visual review passed')
    await load(template.id, version.id)
    success.value = `v${version.version} 已激活，旧活动版本已自动退役。`
  } catch {
    error.value = '激活失败，请重新执行预检。'
  } finally {
    saving.value = false
  }
}

async function useQuestion() {
  if (!form.entryQuestion.trim()) return
  await router.push({ path: '/chat', query: { question: form.entryQuestion.trim() } })
}

function nodePreview(nodeId: string): WorkflowNodePreview | undefined {
  return preview.value?.nodes.find((item) => item.id === nodeId)
}

onMounted(() => load())
</script>

<template>
  <section>
    <div class="section-heading">
      <div><span class="eyebrow">P2.4-A · Workflow Studio</span><h2>工作流模板与运行前预检</h2></div>
      <div class="heading-actions"><button class="secondary-button" @click="resetEditor">新建模板</button><button class="secondary-button" @click="load()">刷新</button></div>
    </div>

    <div class="panel workflow-boundary">
      <strong>当前边界</strong>
      <p>这里管理可复用的 Agent 任务图、版本和激活状态。预检不会创建 Run；写工具即使进入模板，运行时仍必须逐项人工审批。</p>
    </div>
    <p v-if="error" class="stream-error">{{ error }}</p>
    <p v-if="success" class="success-banner">{{ success }}</p>
    <div v-if="loading" class="panel workflow-loading">正在读取模板版本与工具合同…</div>

    <div v-else class="workflow-layout">
      <aside class="panel template-library">
        <header><div><span class="eyebrow">Template Library</span><h3>模板库</h3></div><span class="subtle">{{ overview.templates.length }} 个</span></header>
        <button
          v-for="item in overview.templates" :key="item.id" type="button"
          class="template-card" :class="{ active: item.id === selectedTemplateId }"
          @click="selectTemplate(item)"
        >
          <span class="template-category">{{ item.category }}</span><strong>{{ item.name }}</strong>
          <small>{{ item.description }}</small>
          <span>v{{ item.versions[0]?.version ?? 0 }} · {{ item.activeVersionId ? '已激活' : '草稿' }}</span>
        </button>
        <div v-if="!overview.templates.length" class="subtle">尚无模板。</div>
      </aside>

      <main class="workflow-main">
        <form class="panel workflow-editor" @submit.prevent="save">
          <header><div><span class="eyebrow">Definition</span><h3>{{ isNewTemplate ? '创建工作流模板' : `${form.name} · 新版本` }}</h3></div><span class="subtle">最多 {{ overview.maxNodes }} 节点</span></header>
          <div class="metadata-grid">
            <label>模板名称<input v-model="form.name" :disabled="!isNewTemplate" maxlength="128" required /></label>
            <label>分类<input v-model="form.category" :disabled="!isNewTemplate" maxlength="48" required /></label>
            <label class="wide">说明<textarea v-model="form.description" :disabled="!isNewTemplate" maxlength="1000" /></label>
            <label>版本摘要<input v-model="form.summary" maxlength="500" required /></label>
            <label v-if="selectedTemplate" class="version-select">查看版本
              <select :value="selectedVersionId" @change="selectVersion(($event.target as HTMLSelectElement).value)">
                <option v-for="item in selectedTemplate.versions" :key="item.id" :value="item.id">v{{ item.version }} · {{ item.status }}</option>
              </select>
            </label>
            <label class="wide">入口研究问题<textarea v-model="form.entryQuestion" maxlength="4000" required /></label>
            <label class="wide">规划理由<textarea v-model="form.reason" maxlength="500" /></label>
          </div>

          <div class="node-heading"><div><span class="eyebrow">DAG Nodes</span><h3>任务节点</h3></div><button type="button" class="secondary-button" @click="addNode">增加节点</button></div>
          <article v-for="(node, index) in nodes" :key="index" class="node-editor">
            <header><strong>节点 {{ index + 1 }}</strong><button type="button" class="text-button" :disabled="nodes.length === 1" @click="removeNode(index)">删除</button></header>
            <div class="node-grid">
              <label>节点 ID<input v-model="node.id" maxlength="64" /></label>
              <label>工具<select v-model="node.toolName" @change="changeTool(node)"><option v-for="tool in overview.tools" :key="tool.name" :value="tool.name">{{ tool.name }} · {{ tool.riskLevel }}</option></select></label>
              <label>依赖节点（逗号分隔）<input v-model="node.dependsOn" placeholder="research_1, research_2" /></label>
              <label>条件<select v-model="node.condition"><option>ALWAYS</option><option>ALL_SUCCESS</option><option>ANY_SUCCESS</option><option>ANY_FAILED</option><option>ALL_TERMINAL</option></select></label>
              <label class="wide">工具参数 JSON<textarea v-model="node.argumentsJson" spellcheck="false" /></label>
              <label class="checkbox"><input v-model="node.required" type="checkbox" /> 必须成功节点</label>
            </div>
          </article>
          <footer class="editor-actions">
            <button type="button" class="secondary-button" @click="runPreview">运行预检</button>
            <button class="send-button" :disabled="saving || !form.name.trim()">{{ isNewTemplate ? '保存模板 v1' : '创建不可变新版本' }}</button>
            <button v-if="selectedVersion" type="button" class="secondary-button" :disabled="saving || selectedVersion.status === 'ACTIVE'" @click="activate">激活当前版本</button>
            <button type="button" class="secondary-button" :disabled="!form.entryQuestion.trim()" @click="useQuestion">带入研究问答</button>
          </footer>
        </form>

        <section class="panel workflow-canvas">
          <header><div><span class="eyebrow">Preflight Graph</span><h3>分层 DAG 预览</h3></div><span class="subtle">{{ preview ? `${preview.nodeCount} 节点 · 并行上限 ${preview.maxParallelism}` : '尚未预检' }}</span></header>
          <div v-if="preview" class="wave-list">
            <div v-for="wave in preview.waves" :key="wave.index" class="wave-column">
              <span class="wave-label">第 {{ wave.index }} 层</span>
              <article v-for="nodeId in wave.nodeIds" :key="nodeId" class="graph-node" :class="{ mutating: nodePreview(nodeId)?.riskLevel === 'MUTATING' }">
                <strong>{{ nodeId }}</strong><code>{{ nodePreview(nodeId)?.toolName }}</code>
                <small>{{ nodePreview(nodeId)?.condition }} · {{ nodePreview(nodeId)?.required ? '必需' : '可选' }}</small>
                <span v-if="nodePreview(nodeId)?.dependencyIds.length">← {{ nodePreview(nodeId)?.dependencyIds.join(' · ') }}</span>
              </article>
            </div>
          </div>
          <div v-else class="graph-empty"><strong>先运行预检</strong><p>校验通过后，这里会按依赖层展示并行节点、条件和风险等级。</p></div>
          <ul v-if="preview?.warnings.length" class="workflow-warnings"><li v-for="warning in preview.warnings" :key="warning">{{ warning }}</li></ul>
        </section>
      </main>
    </div>
  </section>
</template>

<style scoped>
.heading-actions,.editor-actions,.node-heading,.workflow-editor > header,.workflow-canvas > header,.template-library > header,.node-editor > header { display:flex;align-items:center;justify-content:space-between;gap:12px; }
.workflow-boundary { padding:20px 24px;margin-bottom:18px; }.workflow-boundary p { margin:8px 0 0;color:var(--muted); }.workflow-loading { padding:30px; }
.workflow-layout { display:grid;grid-template-columns:280px minmax(0,1fr);gap:18px;align-items:start; }.template-library { padding:18px;position:sticky;top:18px;max-height:calc(100vh - 40px);overflow:auto; }.template-library header { margin-bottom:14px; }
.template-card { width:100%;display:flex;flex-direction:column;align-items:flex-start;gap:7px;text-align:left;padding:15px;margin-bottom:10px;border:1px solid var(--line);border-radius:16px;background:transparent;color:var(--text); }.template-card:hover,.template-card.active { border-color:var(--accent);background:rgba(99,214,181,.08); }.template-card small,.template-card span:last-child { color:var(--muted); }.template-category { color:var(--accent);font-size:11px;letter-spacing:.08em;text-transform:uppercase; }
.workflow-main { min-width:0;display:grid;gap:18px; }.workflow-editor,.workflow-canvas { padding:24px; }.metadata-grid,.node-grid { display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:14px;margin-top:20px; }.metadata-grid label,.node-grid label { display:grid;gap:7px;color:var(--muted); }.wide { grid-column:1/-1; }.metadata-grid textarea { min-height:76px; }.node-heading { margin:28px 0 12px; }.node-editor { border:1px solid var(--line);border-radius:18px;padding:18px;margin-top:12px;background:rgba(255,255,255,.015); }.node-grid textarea { min-height:120px;font-family:ui-monospace,SFMono-Regular,Consolas,monospace; }.checkbox { display:flex!important;grid-template-columns:auto 1fr!important;align-items:center;justify-content:start; }.checkbox input { width:auto; }.editor-actions { justify-content:flex-end;flex-wrap:wrap;margin-top:22px; }
.wave-list { display:flex;gap:18px;align-items:stretch;overflow-x:auto;padding:24px 4px 8px; }.wave-column { min-width:230px;display:flex;flex-direction:column;gap:10px;position:relative; }.wave-column:not(:last-child)::after { content:'→';position:absolute;right:-15px;top:50%;color:var(--accent);font-size:22px; }.wave-label { color:var(--muted);font-size:12px; }.graph-node { display:grid;gap:8px;padding:15px;border:1px solid rgba(99,214,181,.35);border-radius:15px;background:rgba(99,214,181,.07); }.graph-node.mutating { border-color:#d99a5d;background:rgba(217,154,93,.08); }.graph-node code { color:var(--accent);overflow-wrap:anywhere; }.graph-node small,.graph-node span { color:var(--muted);font-size:12px; }.graph-empty { min-height:220px;display:grid;place-content:center;text-align:center;color:var(--muted); }.workflow-warnings { margin-top:18px;color:#d9aa6c; }
@media (max-width:1050px) { .workflow-layout { grid-template-columns:1fr; }.template-library { position:static;max-height:none;display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:10px; }.template-library header { grid-column:1/-1; }.template-card { margin:0; } }
@media (max-width:720px) { .metadata-grid,.node-grid,.template-library { grid-template-columns:1fr; }.wide { grid-column:auto; }.workflow-editor,.workflow-canvas { padding:18px; } }
</style>
