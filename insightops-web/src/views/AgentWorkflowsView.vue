<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'

import {
  launchWorkflow,
  listActiveWorkflows,
  type ActiveWorkflowTemplate,
  type WorkflowInputDefinition,
} from '@/api/workflowRuns'

const router = useRouter()
const templates = ref<ActiveWorkflowTemplate[]>([])
const selectedId = ref('')
const values = ref<Record<string, unknown>>({})
const loading = ref(false)
const launching = ref(false)
const error = ref('')

const selected = computed(() => templates.value.find((item) => item.id === selectedId.value) ?? null)
const fields = computed(() => Object.values(selected.value?.inputs ?? {}))

function select(template: ActiveWorkflowTemplate) {
  selectedId.value = template.id
  values.value = Object.fromEntries(Object.values(template.inputs).map((input) => [
    input.name,
    input.defaultValue ?? (input.type === 'boolean' ? false : ''),
  ]))
  error.value = ''
}

function inputType(input: WorkflowInputDefinition) {
  return input.type === 'integer' ? 'number' : 'text'
}

function normalizedInputs() {
  const result: Record<string, unknown> = {}
  for (const input of fields.value) {
    const raw = values.value[input.name]
    if ((raw === '' || raw == null) && !input.required) continue
    if ((raw === '' || raw == null) && input.required) throw new Error(`请填写 ${input.name}`)
    if (input.type === 'integer') result[input.name] = Number(raw)
    else if (input.type === 'string_array') result[input.name] = String(raw).split(',').map((item) => item.trim()).filter(Boolean)
    else if (input.type === 'json' || input.type === 'json_array') result[input.name] = JSON.parse(String(raw))
    else result[input.name] = raw
  }
  return result
}

async function launch() {
  if (!selected.value) return
  launching.value = true
  error.value = ''
  try {
    const result = await launchWorkflow(selected.value.id, selected.value.activeVersionId, normalizedInputs())
    await router.push(`/runs/${result.runId}`)
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '工作流启动失败，请刷新活动版本后重试。'
  } finally {
    launching.value = false
  }
}

onMounted(async () => {
  loading.value = true
  try {
    templates.value = await listActiveWorkflows()
    if (templates.value[0]) select(templates.value[0])
  } catch {
    error.value = '活动工作流加载失败。'
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <section>
    <div class="section-heading">
      <div><span class="eyebrow">P2.4-B · Durable Workflow</span><h2>研究工作流</h2></div>
      <RouterLink class="secondary-button" to="/runs">执行记录</RouterLink>
    </div>
    <p class="workflow-intro">从当前活动版本启动真实 Agent Run。版本、任务图和入口参数会在创建时固化。</p>
    <div v-if="error" class="workflow-error">{{ error }}</div>
    <div v-if="loading" class="panel workflow-loading">正在读取活动模板…</div>
    <div v-else-if="templates.length === 0" class="panel workflow-empty">当前 Workspace 还没有已激活的工作流模板。</div>
    <div v-else class="workflow-grid">
      <aside class="panel template-list">
        <button
          v-for="template in templates"
          :key="template.id"
          :class="{ active: template.id === selectedId }"
          @click="select(template)"
        >
          <strong>{{ template.name }}</strong>
          <span>{{ template.category }} · v{{ template.version }}</span>
          <small>{{ template.description }}</small>
        </button>
      </aside>
      <main v-if="selected" class="panel workflow-launcher">
        <header>
          <div><span class="eyebrow">Active v{{ selected.version }}</span><h3>{{ selected.name }}</h3></div>
          <code>{{ selected.activeVersionId.slice(0, 8) }}</code>
        </header>
        <p>{{ selected.summary || selected.description }}</p>
        <blockquote>{{ selected.entryQuestion }}</blockquote>
        <form @submit.prevent="launch">
          <label v-for="input in fields" :key="input.name">
            <span>{{ input.name }} <i v-if="input.required">必填</i></span>
            <input
              v-if="input.type !== 'boolean'"
              v-model="values[input.name]"
              :type="inputType(input)"
              :maxlength="input.maxLength ?? undefined"
              :min="input.minimum ?? undefined"
              :max="input.maximum ?? undefined"
            />
            <input v-else v-model="values[input.name]" type="checkbox" />
            <small>{{ input.type }}</small>
          </label>
          <div v-if="fields.length === 0" class="no-inputs">该模板不需要额外入口参数。</div>
          <button class="primary-button" :disabled="launching" type="submit">
            {{ launching ? '正在创建持久 Run…' : '启动真实工作流' }}
          </button>
        </form>
      </main>
    </div>
  </section>
</template>

<style scoped>
.workflow-intro{color:var(--muted);margin:-8px 0 22px}.workflow-grid{display:grid;grid-template-columns:300px minmax(0,1fr);gap:18px;align-items:start}.template-list,.workflow-launcher{padding:20px}.template-list{display:grid;gap:10px}.template-list button{display:grid;gap:6px;text-align:left;padding:15px;border:1px solid var(--line);border-radius:14px;background:transparent;color:inherit}.template-list button.active{border-color:var(--accent);background:rgba(99,214,181,.08)}.template-list span,.template-list small,.workflow-launcher>p{color:var(--muted)}.workflow-launcher header{display:flex;justify-content:space-between;align-items:start}.workflow-launcher blockquote{margin:18px 0;padding:14px 16px;border-left:3px solid var(--accent);background:rgba(99,214,181,.05)}form{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:14px}label{display:grid;gap:7px;color:var(--muted)}label i{font-style:normal;color:#d7a86e;font-size:11px}.primary-button,.no-inputs{grid-column:1/-1}.workflow-error{padding:12px 15px;margin-bottom:16px;border:1px solid rgba(222,100,100,.45);border-radius:12px;color:#efaaaa}.workflow-loading,.workflow-empty{padding:28px}@media(max-width:800px){.workflow-grid,form{grid-template-columns:1fr}}
</style>
