<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'

import {
  launchWorkflow,
  listActiveWorkflows,
  type ActiveWorkflowTemplate,
  type WorkflowInputDefinition,
} from '@/api/workflowRuns'
import {
  deleteWorkflowPreset,
  listWorkflowPresets,
  saveWorkflowPreset,
  type WorkflowPreset,
} from '@/api/workflowProducts'

const router = useRouter()
const templates = ref<ActiveWorkflowTemplate[]>([])
const selectedId = ref('')
const values = ref<Record<string, unknown>>({})
const loading = ref(false)
const launching = ref(false)
const error = ref('')
const presets = ref<WorkflowPreset[]>([])
const selectedPresetId = ref('')
const presetName = ref('')
const presetLoading = ref(false)

const selected = computed(() => templates.value.find((item) => item.id === selectedId.value) ?? null)
const fields = computed(() => Object.values(selected.value?.inputs ?? {}))

async function select(template: ActiveWorkflowTemplate) {
  selectedId.value = template.id
  values.value = Object.fromEntries(Object.values(template.inputs).map((input) => [
    input.name,
    input.defaultValue ?? (input.type === 'boolean' ? false : ''),
  ]))
  selectedPresetId.value = ''
  presetName.value = ''
  error.value = ''
  await loadPresets(template)
}

async function loadPresets(template: ActiveWorkflowTemplate) {
  presetLoading.value = true
  try {
    presets.value = await listWorkflowPresets(template.id, template.activeVersionId)
  } catch {
    presets.value = []
    error.value = '参数预设加载失败。'
  } finally {
    presetLoading.value = false
  }
}

function applyPreset() {
  const preset = presets.value.find((item) => item.id === selectedPresetId.value)
  if (!preset) return
  values.value = { ...preset.values }
  presetName.value = preset.name
}

async function savePreset() {
  if (!selected.value || !presetName.value.trim()) {
    error.value = '请填写预设名称。'
    return
  }
  try {
    const saved = await saveWorkflowPreset(
      selected.value.id, selected.value.activeVersionId,
      presetName.value.trim(), normalizedInputs(),
    )
    await loadPresets(selected.value)
    selectedPresetId.value = saved.id
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '参数预设保存失败。'
  }
}

async function removePreset() {
  const preset = presets.value.find((item) => item.id === selectedPresetId.value)
  if (!preset || !selected.value) return
  if (!globalThis.confirm(`删除预设“${preset.name}”？`)) return
  try {
    await deleteWorkflowPreset(preset.id)
    selectedPresetId.value = ''
    presetName.value = ''
    await loadPresets(selected.value)
  } catch {
    error.value = '参数预设删除失败。'
  }
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
    if (templates.value[0]) await select(templates.value[0])
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
      <div><span class="eyebrow">P2.4-C · Durable Workflow</span><h2>研究工作流与参数预设</h2></div>
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
        <div class="preset-toolbar">
          <label>参数预设
            <select v-model="selectedPresetId" :disabled="presetLoading" @change="applyPreset">
              <option value="">不使用预设</option>
              <option v-for="preset in presets" :key="preset.id" :value="preset.id">{{ preset.name }}</option>
            </select>
          </label>
          <label>预设名称<input v-model="presetName" maxlength="80" placeholder="例如：Spring AI 升级评估" /></label>
          <button type="button" class="secondary-button" @click="savePreset">保存当前参数</button>
          <button type="button" class="text-button" :disabled="!selectedPresetId" @click="removePreset">删除预设</button>
        </div>
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
.workflow-intro{color:var(--muted);margin:-8px 0 22px}.workflow-grid{display:grid;grid-template-columns:300px minmax(0,1fr);gap:18px;align-items:start}.template-list,.workflow-launcher{padding:20px}.template-list{display:grid;gap:10px}.template-list button{display:grid;gap:6px;text-align:left;padding:15px;border:1px solid var(--line);border-radius:14px;background:transparent;color:inherit}.template-list button.active{border-color:var(--accent);background:rgba(99,214,181,.08)}.template-list span,.template-list small,.workflow-launcher>p{color:var(--muted)}.workflow-launcher header{display:flex;justify-content:space-between;align-items:start}.workflow-launcher blockquote{margin:18px 0;padding:14px 16px;border-left:3px solid var(--accent);background:rgba(99,214,181,.05)}.preset-toolbar{display:grid;grid-template-columns:minmax(160px,1fr) minmax(220px,1fr) auto auto;gap:10px;align-items:end;margin:0 0 18px;padding:14px;border:1px solid var(--line);border-radius:13px;background:rgba(99,214,181,.035)}.preset-toolbar label{display:grid;gap:6px;color:var(--muted)}form{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:14px}label{display:grid;gap:7px;color:var(--muted)}label i{font-style:normal;color:#d7a86e;font-size:11px}.primary-button,.no-inputs{grid-column:1/-1}.workflow-error{padding:12px 15px;margin-bottom:16px;border:1px solid rgba(222,100,100,.45);border-radius:12px;color:#efaaaa}.workflow-loading,.workflow-empty{padding:28px}@media(max-width:1000px){.preset-toolbar{grid-template-columns:1fr 1fr}}@media(max-width:800px){.workflow-grid,form,.preset-toolbar{grid-template-columns:1fr}}
</style>
