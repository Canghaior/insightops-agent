<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { isAxiosError } from 'axios'
import { launchUpgrade, setupUpgrade, upgradeVersions, listUpgradePresets, saveUpgradePreset, type SourceCoverage, type UpgradeInput, type UpgradeSetup } from '@/api/upgradeAssessments'
import type { WorkflowPreset } from '@/api/workflowProducts'
import { restoreUpgradeInput, suggestResearchProfile } from '@/utils/upgradeProfiles'
import { getWorkflowRun } from '@/api/workflowRuns'

const router = useRouter(), route = useRoute()
const setup = ref<UpgradeSetup | null>(null)
const loading = ref(true), busy = ref(false), saving = ref(false), error = ref(''), notice = ref('')
const form = ref<UpgradeInput>({ projectId: '', currentVersion: '', targetVersion: '', businessProject: '',
  javaVersion: '', springBootVersion: '', concerns: ['API 与配置变更', '依赖与兼容性'], uploadIds: [], issueUrls: [], profileId: 'java_backend', context: {} })
const issuesText = ref(''), presets = ref<WorkflowPreset[]>([]), selectedPreset = ref(''), presetName = ref('')
const versions = ref<string[]>([]), versionLoading = ref(false), versionNote = ref('')
const selectedProject = computed(() => setup.value?.projects.find(item => item.id === form.value.projectId))
const selectedProfile = computed(() => setup.value?.profiles.find(item => item.id === form.value.profileId))
const concernOptions = computed(() => [...new Set([...(selectedProfile.value?.concerns ?? []), ...form.value.concerns])])
const projectSources = computed(() => setup.value?.sources.filter(item => item.projectId === form.value.projectId) ?? [])
const usableUploads = computed(() => setup.value?.uploads.filter(item => item.status === 'SUCCEEDED') ?? [])
let versionRequest = 0, lastPayload = '', requestId = globalThis.crypto.randomUUID()

function sourceState(source: SourceCoverage) {
  if (!source.enabled) return '已停用，已有资料不会自动更新'
  if (source.status === 'RUNNING') return '正在采集'
  if (source.status === 'FAILED') return '采集失败'
  if (source.status === 'RETRY_WAIT' && source.consecutiveFailures > 0) return '上次采集失败，等待重试'
  return source.documentCount ? `已有 ${source.documentCount} 篇文档` : '尚未采集完成'
}
function message(reason: unknown, fallback: string) {
  return isAxiosError(reason) ? String(reason.response?.data?.detail ?? reason.response?.data?.message ?? fallback) : fallback
}
async function loadVersions(projectId: string) {
  const request = ++versionRequest
  versions.value = []; versionNote.value = ''; versionLoading.value = !!projectId
  if (!projectId) return
  try {
    const result = await upgradeVersions(projectId)
    if (request !== versionRequest) return
    versions.value = result.releases.map(item => item.tagName)
    versionNote.value = `已获取 ${versions.value.length} 个近期版本（${new Date(result.fetchedAt).toLocaleString('zh-CN')}）。可手动填写更早版本，研究时会检查范围。`
  } catch {
    if (request === versionRequest) versionNote.value = '暂时无法读取版本列表。可手动填写目标版本，研究时将再次获取 Release 并说明资料缺口。'
  } finally { if (request === versionRequest) versionLoading.value = false }
}
watch(() => form.value.projectId, loadVersions)

function values(): UpgradeInput {
  return { ...form.value, currentVersion: form.value.currentVersion.trim(), targetVersion: form.value.targetVersion.trim(),
    context: { ...form.value.context }, javaVersion: form.value.profileId === 'java_backend' ? form.value.context.javaVersion ?? '' : '',
    springBootVersion: form.value.profileId === 'java_backend' ? form.value.context.springBootVersion ?? '' : '',
    issueUrls: issuesText.value.split(/\s+/).map(item => item.trim()).filter(Boolean) }
}
function changeProfile() {
  form.value.context = {}; form.value.javaVersion = ''; form.value.springBootVersion = ''
  form.value.concerns = selectedProfile.value?.concerns.slice(0, 2) ?? []
}
function changeProject() {
  form.value.currentVersion = ''; form.value.targetVersion = ''; issuesText.value = ''
  if (selectedProject.value) form.value.profileId = suggestResearchProfile(selectedProject.value.owner, selectedProject.value.name)
  changeProfile()
}
function restore(raw: Record<string, unknown>) {
  form.value = restoreUpgradeInput(raw)
  if (!setup.value?.profiles.some(item => item.id === form.value.profileId)) {
    form.value.profileId = 'general'; changeProfile(); notice.value = '原研究方向已不可用，请补充当前项目背景。'
  }
  issuesText.value = form.value.issueUrls.join('\n')
  const invalidUploads = form.value.uploadIds.filter(id => !usableUploads.value.some(item => item.uploadId === id))
  if (invalidUploads.length) {
    form.value.uploadIds = form.value.uploadIds.filter(id => !invalidUploads.includes(id))
    notice.value = '原配置中的部分资料已不可用，已移除；请检查关联资料后再研究。'
  }
  if (!setup.value?.projects.some(item => item.id === form.value.projectId)) {
    form.value.projectId = ''; notice.value = '原框架已不在关注列表中，请重新选择。'
  }
}
function applyPreset() {
  const preset = presets.value.find(item => item.id === selectedPreset.value)
  if (preset) { restore(preset.values); presetName.value = preset.name }
}
async function savePreset() {
  if (!setup.value || !presetName.value.trim()) { error.value = '请填写配置名称，例如「支付团队 · 订单服务」。'; return }
  saving.value = true; error.value = ''
  try {
    const saved = await saveUpgradePreset(presetName.value.trim(), values())
    presets.value = await listUpgradePresets()
    selectedPreset.value = saved.id; notice.value = '配置已保存，下次选择后主要修改目标版本即可。'
  } catch (reason) { error.value = message(reason, '配置保存失败，请检查必填项。') }
  finally { saving.value = false }
}
async function launch() {
  if (!form.value.concerns.length) { error.value = '请至少选择一个关注项。'; return }
  busy.value = true; error.value = ''
  const input = values(), payload = JSON.stringify(input)
  if (payload !== lastPayload) { lastPayload = payload; requestId = globalThis.crypto.randomUUID() }
  try { const result = await launchUpgrade(input, requestId); await router.push(`/runs/${result.runId}`) }
  catch (reason) { error.value = message(reason, '研究启动失败，请重试。重复点击不会创建相同请求的多份研究。') }
  finally { busy.value = false }
}
onMounted(async () => {
  try {
    setup.value = await setupUpgrade()
    presets.value = await listUpgradePresets()
    form.value.projectId = setup.value.projects[0]?.id ?? ''
    if (selectedProject.value) form.value.profileId = suggestResearchProfile(selectedProject.value.owner, selectedProject.value.name)
    changeProfile()
    if (typeof route.query.runId === 'string') {
      const previous = await getWorkflowRun(route.query.runId)
      if (previous.inputs && typeof previous.inputs === 'object') restore(previous.inputs as Record<string, unknown>)
      notice.value ||= '已带入上次研究参数。修改目标版本后即可再次研究；会重新查询 Release 和知识库，文档采集时间可在引用中查看。'
    }
  } catch (reason) { error.value = message(reason, '升级评估加载失败，请刷新重试。') }
  finally { loading.value = false }
})
</script>

<template>
  <section class="upgrade-page">
    <div class="section-heading"><div><span class="eyebrow">版本变化 → 业务影响 → 验证清单</span><h2>升级评估</h2></div><RouterLink class="secondary-button" to="/runs">最近研究</RouterLink></div>
    <p class="intro">选好框架与版本，结合你的项目背景研究。完成后可直接下载带来源的 Markdown 或 PDF。</p>
    <p v-if="error" role="alert" class="error">{{ error }}</p>
    <p v-if="notice" role="status" class="notice">{{ notice }}</p>
    <div v-if="loading" class="panel padded">正在加载关注框架与保存的配置…</div>
    <div v-else-if="setup && !setup.projects.length" class="panel padded">还没有关注的框架。<RouterLink to="/projects">先选择关注项目</RouterLink>，再回来填写版本。</div>
    <form v-else-if="setup" class="panel padded" @submit.prevent="launch">
      <div v-if="presets.length" class="saved-config"><label for="saved-config">复用项目配置</label><select id="saved-config" v-model="selectedPreset" @change="applyPreset"><option value="">新建一次研究</option><option v-for="preset in presets" :key="preset.id" :value="preset.id">{{ preset.name }}</option></select></div>
      <div class="fields">
        <label>关注的框架 <span>必填</span><select v-model="form.projectId" required @change="changeProject"><option value="" disabled>选择框架</option><option v-for="project in setup.projects" :key="project.id" :value="project.id">{{ project.owner }}/{{ project.name }}</option></select></label>
        <label>业务项目 <span>选填</span><input v-model="form.businessProject" maxlength="120" placeholder="例如：订单服务 / 智能客服" /></label>
        <label>当前版本 <span>必填</span><input v-model="form.currentVersion" maxlength="80" placeholder="例如：1.0.0" required /></label>
        <label>目标版本 <span>必填</span><input v-model="form.targetVersion" list="release-versions" maxlength="80" placeholder="选择已发布版本或手动填写" required /><datalist id="release-versions"><option v-for="version in versions" :key="version" :value="version" /></datalist></label>
      </div>
      <p class="hint" aria-live="polite">{{ versionLoading ? '正在获取 GitHub 官方版本列表…' : versionNote }}</p>
      <div class="profile-block">
        <label for="research-profile">项目技术方向</label>
        <select id="research-profile" v-model="form.profileId" @change="changeProfile"><option v-for="profile in setup.profiles" :key="profile.id" :value="profile.id">{{ profile.label }}</option></select>
        <p class="hint">{{ selectedProfile?.description }}</p>
        <div class="fields"><label v-for="field in selectedProfile?.fields" :key="field.key">{{ field.label }}<input v-model="form.context[field.key]" :maxlength="field.maxLength" :placeholder="field.placeholder" /></label></div>
      </div>
      <fieldset><legend>重点关注什么？</legend><div class="checks"><label v-for="item in concernOptions" :key="item"><input v-model="form.concerns" type="checkbox" :value="item" />{{ item }}</label></div></fieldset>
      <details class="optional">
        <summary>补充项目背景与资料（选填）</summary>
        <label>项目补充说明<textarea v-model="form.context.notes" maxlength="1000" rows="3" placeholder="例如：使用哪些功能、已有约束和重点验证场景；不填写密码或密钥。" /></label>
        <fieldset><legend>关联团队资料</legend><p class="hint">仅检索本次勾选的文件。你的私有资料仍只对有权限的用户开放。</p><div v-if="usableUploads.length" class="checks files"><label v-for="file in usableUploads" :key="file.uploadId"><input v-model="form.uploadIds" type="checkbox" :value="file.uploadId" />{{ file.originalName }} · {{ file.visibility === 'PRIVATE' ? '私有' : '团队' }}</label></div><p v-else class="hint">暂无处理完成的资料。可先到 <RouterLink to="/knowledge-files">我的资料</RouterLink> 上传。</p></fieldset>
        <label>已知 Issue 链接（每行一个，最多 3 个）<textarea v-model="issuesText" rows="3" :placeholder="`https://github.com/${selectedProject?.owner ?? 'owner'}/${selectedProject?.name ?? 'repo'}/issues/123`" /></label>
        <p class="hint">读取所选框架 Issue 的正文与状态。评论和相关 PR 不在本次读取范围内。{{ setup.issueAvailable ? '' : '当前 Issue 工具不可用。' }}</p>
      </details>
      <details class="optional">
        <summary>查看已配置的官方资料（{{ projectSources.length }} 个来源）</summary>
        <p class="hint">Release 会在研究时获取；以下文档来自知识库。采集成功不等于已经覆盖当前与目标版本。</p>
        <p v-if="!projectSources.length" class="notice">尚未配置该框架的官方文档来源，评估可能缺少迁移依据。</p>
        <ul v-else class="source-list"><li v-for="source in projectSources" :key="source.url"><a :href="source.url" target="_blank" rel="noreferrer">{{ source.name }}</a><span>{{ sourceState(source) }} · {{ source.lastSyncAt ? `最近采集：${new Date(source.lastSyncAt).toLocaleString('zh-CN')}` : '尚无成功采集时间' }}</span></li></ul>
      </details>
      <details class="optional"><summary>保存配置，下次复用</summary><p class="hint">配置按当前工作区和你的账号保存；用团队与业务项目命名，方便切换。</p><div class="save-config"><input v-model="presetName" maxlength="80" aria-label="配置名称" placeholder="例如：支付团队 · 订单服务" /><button type="button" class="secondary-button" :disabled="saving" @click="savePreset">{{ saving ? '保存中…' : '保存当前配置' }}</button></div></details>
      <div class="launch-row"><button class="primary-button" type="submit" :disabled="busy">{{ busy ? '正在启动研究…' : '开始升级评估' }}</button><p class="hint">输出主要变化、可能影响、验证清单、来源依据和尚未确认事项。资料不足会明确提示。</p></div>
    </form>
    <RouterLink class="other-workflows" to="/agent-workflows">查看其他研究模板 →</RouterLink>
  </section>
</template>

<style scoped>
input:not([type="checkbox"]),select,textarea{padding:10px 12px;border:1px solid var(--line);border-radius:8px;background:var(--panel-soft);color:inherit;font:inherit}input:focus-visible,select:focus-visible,textarea:focus-visible{outline:2px solid var(--accent);outline-offset:2px}input[type="checkbox"]{accent-color:var(--accent)}
.profile-block{border-top:1px solid var(--line);padding:20px 0 0}.profile-block>label{display:block;font-weight:600;margin-bottom:8px}.profile-block>select{max-width:300px}.source-list{padding-left:20px}.source-list li{margin:12px 0}.source-list span{display:block;color:var(--muted);font-size:12px;margin-top:5px}
.upgrade-page{max-width:1080px;margin:auto}.intro,.hint{color:var(--muted);line-height:1.7}.padded{padding:28px}.fields{display:grid;grid-template-columns:1fr 1fr;gap:20px}.fields label,.saved-config,label:has(textarea){display:grid;gap:8px}.fields label span{font-size:12px;color:var(--muted)}input,select,textarea{width:100%;box-sizing:border-box}fieldset{border:0;padding:16px 0;margin:10px 0}legend{font-weight:600}.checks{display:flex;gap:14px 24px;flex-wrap:wrap}.checks label{display:flex;align-items:center;gap:8px}.checks input{width:auto}.files{display:grid}.optional{border-top:1px solid var(--line);padding:18px 0}.optional summary{cursor:pointer;margin-bottom:14px}.save-config{display:flex;gap:10px}.save-config input{flex:1}.launch-row{display:flex;align-items:center;gap:20px;margin-top:14px}.launch-row button{min-width:175px}.launch-row p{font-size:13px}.saved-config{margin-bottom:24px}.error{padding:14px;background:rgba(220,80,80,.1);color:#d86b6b;border-radius:10px}.notice{padding:14px;background:rgba(80,180,140,.12);border-radius:10px}.other-workflows{display:inline-block;margin-top:20px}@media(max-width:700px){.fields{grid-template-columns:1fr}.padded{padding:18px}.launch-row,.save-config{flex-direction:column;align-items:stretch}}
</style>
