<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { isAxiosError } from 'axios'
import { getResearchEvent, launchUpgrade, setupUpgrade, upgradeVersions, listUpgradePresets, saveUpgradePreset, type ResearchEventContext, type SourceCoverage, type UpgradeInput, type UpgradeSetup } from '@/api/upgradeAssessments'
import SystemBackgroundPanel from '@/components/SystemBackground.vue'
import { getSystem, launchSystemUpgrade, type BusinessSystem } from '@/api/businessSystems'
import type { WorkflowPreset } from '@/api/workflowProducts'
import { applyReleaseEvent, previousAssessmentInput, restoreUpgradeInput, selectedMaterialNames, suggestResearchProfile, systemSnapshotOf } from '@/utils/upgradeProfiles'
import { getWorkflowRun } from '@/api/workflowRuns'

const router = useRouter(), route = useRoute()
const setup = ref<UpgradeSetup | null>(null)
const system = ref<BusinessSystem | null>(null)
const systemChanged = ref(false), reloadingSystem = ref(false), initializationFailed = ref(false)
const systemFiles = computed(() => new Set(system.value?.attachments.map(file => file.uploadId)))
const selectedFileIds = computed(() => new Set([...systemFiles.value, ...form.value.uploadIds]))
const loading = ref(true), busy = ref(false), saving = ref(false), error = ref(''), notice = ref('')
const form = ref<UpgradeInput>({ projectId: '', currentVersion: '', targetVersion: '', businessProject: '',
  javaVersion: '', springBootVersion: '', concerns: ['API 与配置变更', '依赖与兼容性'], uploadIds: [], issueUrls: [], profileId: 'java_backend', context: {} })
const issuesText = ref(''), presets = ref<WorkflowPreset[]>([]), selectedPreset = ref(''), presetName = ref('')
const versions = ref<string[]>([]), versionLoading = ref(false), versionNote = ref('')
const sourceEvent = ref<ResearchEventContext | null>(null), eventFailed = ref(false), refreshingSources = ref(false)
const recovery = ref({ profile: false, project: false, event: false })
let originalFileNames = new Map<string, string>()
const removedFiles = ref(new Map<string, string>())
const omittedNames = computed(() => [...removedFiles.value.values()].filter(Boolean).join('、'))
watch(selectedFileIds, included => {
  for (const id of included) removedFiles.value.delete(id)
})
const previousSystem = ref<{ revision: number | null; hasSupplement: boolean } | null>(null)
const reviewRequired = computed(() => !!previousSystem.value && !!system.value && previousSystem.value.revision !== system.value.revision)
const reviewed = ref(false)
function clearReview() { reviewed.value = false }
watch([() => system.value?.revision, () => form.value.profileId], clearReview, { flush: 'sync' })
watch(() => form.value.context, clearReview, { deep: true, flush: 'sync' })
const launchBlocked = computed(() => loading.value || busy.value || systemChanged.value || reloadingSystem.value
  || refreshingSources.value || (reviewRequired.value && !reviewed.value))
const availablePresets = computed(() => sourceEvent.value ? presets.value.filter(item => item.values.projectId === sourceEvent.value?.projectId) : presets.value)
const selectedProject = computed(() => setup.value?.projects.find(item => item.id === form.value.projectId))
const selectedProfile = computed(() => setup.value?.profiles.find(item => item.id === form.value.profileId))
const concernOptions = computed(() => [...new Set([...(selectedProfile.value?.concerns ?? []), ...form.value.concerns])])
const projectSources = computed(() => setup.value?.sources.filter(item => item.projectId === form.value.projectId) ?? [])
const usableUploads = computed(() => setup.value?.uploads.filter(item => item.status === 'SUCCEEDED') ?? [])
const unfinishedUploads = computed(() => setup.value?.uploads.filter(item => item.projectId === form.value.projectId && item.status !== 'SUCCEEDED') ?? [])
const collectedSources = computed(() => projectSources.value.filter(item => item.documentCount > 0))
const attentionSources = computed(() => projectSources.value.filter(item => !item.enabled || item.status === 'FAILED' || item.consecutiveFailures > 0 || isRefreshDue(item)))
let versionRequest = 0, lastPayload = '', requestId = globalThis.crypto.randomUUID()

function sourceState(source: SourceCoverage) {
  if (!source.enabled) return '已停用，已有资料不会自动更新'
  if (source.status === 'RUNNING') return '正在采集'
  if (source.status === 'FAILED') return '采集失败'
  if (source.status === 'RETRY_WAIT' && source.consecutiveFailures > 0) return '上次采集失败，等待重试'
  return source.documentCount ? `已有 ${source.documentCount} 篇文档` : '尚未采集完成'
}
function isRefreshDue(source: SourceCoverage) {
  return source.enabled && source.status !== 'RUNNING' && !!source.nextSyncAt && new Date(source.nextSyncAt).getTime() < Date.now()
}
async function refreshSources() {
  refreshingSources.value = true
  try {
    setup.value = await setupUpgrade()
    removeUnavailableUploads()
    notice.value = '资料状态已刷新。'
  }
  catch (reason) { error.value = message(reason, '资料状态刷新失败，请重试。') }
  finally { refreshingSources.value = false }
}
async function detachEvent() {
  sourceEvent.value = null; delete form.value.eventId
  const query = { ...route.query }; delete query.eventId
  await router.replace({ query })
  notice.value = '已改为独立升级评估；当前业务背景与版本仍保留。'
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
  recovery.value.profile = false
  form.value.context = {}; form.value.javaVersion = ''; form.value.springBootVersion = ''
  form.value.concerns = selectedProfile.value?.concerns.slice(0, 2) ?? []
}
function changeProject() {
  recovery.value.project = false
  form.value.currentVersion = ''; form.value.targetVersion = ''; issuesText.value = ''
  if (selectedProject.value) form.value.profileId = system.value ? 'java_backend' : suggestResearchProfile(selectedProject.value.owner, selectedProject.value.name)
  changeProfile()
}
function removeUnavailableUploads() {
  const available = new Set(usableUploads.value.map(file => file.uploadId))
  for (const id of form.value.uploadIds) {
    if (!available.has(id)) removedFiles.value.set(id, originalFileNames.get(id) ?? '')
  }
  form.value.uploadIds = form.value.uploadIds.filter(id => available.has(id))
}
function restore(raw: Record<string, unknown>) {
  recovery.value = { profile: false, project: false, event: false }
  originalFileNames = selectedMaterialNames(raw.selectedMaterials)
  removedFiles.value.clear()
  form.value = restoreUpgradeInput(raw)
  if (!setup.value?.profiles.some(item => item.id === form.value.profileId)) {
    form.value.profileId = 'general'; changeProfile(); recovery.value.profile = true
  }
  issuesText.value = form.value.issueUrls.join('\n')
  removeUnavailableUploads()
  if (!setup.value?.projects.some(item => item.id === form.value.projectId)) {
    form.value.projectId = ''; recovery.value.project = true
  }
}
function applyPreset() {
  const preset = availablePresets.value.find(item => item.id === selectedPreset.value)
  if (preset) {
    restore(preset.values)
    if (sourceEvent.value) form.value = applyReleaseEvent(form.value, sourceEvent.value)
    presetName.value = preset.name
    notice.value = sourceEvent.value ? '已保留业务配置中的当前版本与背景，目标版本采用本次 Release。' : '已载入业务配置。'
  }
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
async function reloadSystem() {
  if (!system.value || reloadingSystem.value || busy.value) return
  reloadingSystem.value = true
  try {
    system.value = await getSystem(system.value.id)
    systemChanged.value = false
    error.value = ''
    notice.value = '已载入最新档案，请核对背景后发起研究。'
  } catch (reason) { error.value = message(reason, '无法读取系统档案。') }
  finally { reloadingSystem.value = false }
}
async function launch() {
  if (launchBlocked.value || initializationFailed.value || eventFailed.value) return
  if (!form.value.concerns.length) { error.value = '请至少选择一个关注项。'; return }
  busy.value = true; error.value = ''
  const input = values()
  const payload = JSON.stringify([input, system.value?.id, system.value?.revision])
  if (payload !== lastPayload) { lastPayload = payload; requestId = globalThis.crypto.randomUUID() }
  try {
    const result = system.value
      ? await launchSystemUpgrade(system.value.id, system.value.revision, input, requestId)
      : await launchUpgrade(input, requestId)
    await router.push(`/runs/${result.runId}`)
  }
  catch (reason) {
    systemChanged.value = !!system.value && isAxiosError(reason) && reason.response?.status === 409
    if (systemChanged.value) reviewed.value = false
    error.value = systemChanged.value ? '档案或本次请求已变化，请载入最新档案后核对。' : message(reason, '研究启动失败，请检查输入后重试。')
  }
  finally { busy.value = false }
}
onMounted(async () => {
  try {
    const { systemId: requested, runId, eventId } = route.query
    if (requested !== undefined && (typeof requested !== 'string' || !requested)) throw new Error('业务系统标识无效。')
    let systemId = requested
    setup.value = await setupUpgrade()
    presets.value = await listUpgradePresets()
    form.value.projectId = setup.value.projects[0]?.id ?? ''
    if (selectedProject.value) form.value.profileId = suggestResearchProfile(selectedProject.value.owner, selectedProject.value.name)
    changeProfile()
    if (typeof runId === 'string') {
      const previous = await getWorkflowRun(runId)
      if (systemId && previous.businessSystemId !== systemId) throw new Error('历史运行与所选系统不一致。')
      systemId = previous.businessSystemId ?? systemId
      restore(previousAssessmentInput(previous))
      if (previous.businessSystemId) {
        previousSystem.value = { revision: systemSnapshotOf(previous)?.revision ?? null,
          hasSupplement: Object.values(form.value.context).some(value => value.trim() !== '') }
      }
      notice.value = '已带入上次研究参数。'
    }
    if (systemId) {
      system.value = await getSystem(systemId)
      if (!runId) { form.value.profileId = 'java_backend'; changeProfile() }
      notice.value = '本次研究将使用下方档案修订及其关联资料。'
    }
    if (typeof eventId === 'string') {
      try {
        sourceEvent.value = await getResearchEvent(eventId)
        if (!setup.value.projects.some(item => item.id === sourceEvent.value?.projectId)) throw new Error('事件对应的框架已不在关注列表。')
        if (typeof runId !== 'string') {
          form.value.projectId = sourceEvent.value.projectId
          if (selectedProject.value) form.value.profileId = suggestResearchProfile(selectedProject.value.owner, selectedProject.value.name)
          changeProfile()
        }
        form.value = applyReleaseEvent(form.value, sourceEvent.value)
        notice.value = '已从所选事件带入框架与目标版本。选择已有业务配置可保留当前版本和项目背景。'
      } catch (reason) {
        eventFailed.value = true
        error.value = message(reason, reason instanceof Error ? reason.message : '所选事件无法读取，请从更新列表重新选择。')
      }
    } else if (form.value.eventId) {
      try { sourceEvent.value = await getResearchEvent(form.value.eventId) }
      catch { delete form.value.eventId; recovery.value.event = true }
    }
  } catch (reason) {
    initializationFailed.value = true
    error.value = message(reason, reason instanceof Error ? reason.message : '升级评估加载失败，请刷新重试。')
  }
  finally { loading.value = false }
})
</script>

<template>
  <section class="upgrade-page">
    <div class="section-heading"><div><span class="eyebrow">版本变化 → 业务影响 → 验证清单</span><h2>升级评估</h2></div><RouterLink class="secondary-button" to="/runs">最近研究</RouterLink></div>
    <p class="intro">选好框架与版本，结合你的项目背景研究。完成后可直接下载带来源的 Markdown 或 PDF。</p>
    <p v-if="error" role="alert" class="error">{{ error }}</p>
    <p v-if="notice" role="status" class="notice">{{ notice }}</p>
    <div v-if="removedFiles.size || recovery.profile || recovery.project || recovery.event" class="recovery-warnings" role="alert">
      <p v-if="removedFiles.size">
        <strong>{{ removedFiles.size }} 份原选资料未纳入本次研究。</strong>
        <span v-if="omittedNames">其中有「{{ omittedNames }}」。</span>
        载入或刷新时这些资料不可用。可在「关联团队资料」中重新勾选可用文件。
      </p>
      <p v-if="recovery.profile">原研究方向已不可用，已切换为通用研究。请核对当前项目背景。</p>
      <p v-if="recovery.project">原框架已不在关注列表中，请重新选择。</p>
      <p v-if="recovery.event">原触发事件已不可访问，本次将作为独立研究。</p>
    </div>
    <div v-if="loading" class="panel padded">正在加载关注框架与保存的配置…</div>
    <div v-else-if="initializationFailed" class="panel padded"><RouterLink to="/business-systems">返回业务系统</RouterLink></div>
    <div v-else-if="setup && !setup.projects.length" class="panel padded">还没有关注的框架。<RouterLink to="/projects">先选择关注项目</RouterLink>，再回来填写版本。</div>
    <div v-else-if="eventFailed" class="panel padded"><p>无法确认所选事件，尚未发起研究。</p><RouterLink to="/updates">返回更新列表重新选择</RouterLink></div>
    <form v-else-if="setup" class="panel padded" @submit.prevent="launch">
      <div v-if="system" class="linked-system">
        <SystemBackgroundPanel :system="system" />
        <button v-if="previousSystem || systemChanged" type="button" class="text-button reload-system" :disabled="reloadingSystem || busy" @click="reloadSystem">{{ reloadingSystem ? '正在载入档案…' : '载入最新档案' }}</button>
      </div>
      <div v-if="sourceEvent" class="event-context">
        <strong>本次研究来自：{{ sourceEvent.repositoryOwner }}/{{ sourceEvent.repositoryName }} · {{ sourceEvent.versionTag }}</strong>
        <a :href="sourceEvent.sourceUrl" target="_blank" rel="noreferrer">{{ sourceEvent.title }} · 打开原始事件</a>
        <p class="hint">发布：{{ sourceEvent.occurredAt ? new Date(sourceEvent.occurredAt).toLocaleString('zh-CN') : '未记录' }} · 采集：{{ sourceEvent.collectedAt ? new Date(sourceEvent.collectedAt).toLocaleString('zh-CN') : '未记录' }}。来源线索会随研究保存；其内容仍须核实。</p>
        <p v-if="form.targetVersion !== sourceEvent.versionTag" class="hint">目标版本已修改；原事件仅作为本次研究的触发来源，不代表其变更均适用于新目标版本。</p>
        <button type="button" class="text-button" @click="detachEvent">改为独立研究</button>
      </div>
      <div v-if="!system && availablePresets.length" class="saved-config"><label for="saved-config">复用项目配置{{ sourceEvent ? '（当前框架）' : '' }}</label><select id="saved-config" v-model="selectedPreset" @change="applyPreset"><option value="">新建一次研究</option><option v-for="preset in availablePresets" :key="preset.id" :value="preset.id">{{ preset.name }}</option></select></div>
      <div class="fields">
        <label>关注的框架 <span>必填</span><select v-model="form.projectId" required :disabled="!!sourceEvent" @change="changeProject"><option value="" disabled>选择框架</option><option v-for="project in setup.projects" :key="project.id" :value="project.id">{{ project.owner }}/{{ project.name }}</option></select></label>
        <label v-if="!system">业务项目 <span>选填</span><input v-model="form.businessProject" maxlength="120" placeholder="例如：订单服务 / 智能客服" /></label>
        <label>当前版本 <span>必填</span><input v-model="form.currentVersion" maxlength="80" placeholder="例如：1.0.0" required /></label>
        <label>目标版本 <span>必填</span><input v-model="form.targetVersion" list="release-versions" maxlength="80" placeholder="选择已发布版本或手动填写" required /><datalist id="release-versions"><option v-for="version in versions" :key="version" :value="version" /></datalist></label>
      </div>
      <p class="hint" aria-live="polite">{{ versionLoading ? '正在获取 GitHub 官方版本列表…' : versionNote }}</p>
      <div class="profile-block">
        <div v-if="reviewRequired && previousSystem && system" class="review-note" role="note">
          <strong>请核对本次补充背景</strong>
          <p>
            <template v-if="previousSystem.revision">上次评估使用档案修订 {{ previousSystem.revision }}，本次使用修订 {{ system.revision }}。</template>
            <template v-else>上次评估未记录档案修订，本次使用修订 {{ system.revision }}。</template>
            <template v-if="previousSystem.hasSupplement">本次表单已带入上次评估的补充背景。请对照当前档案核对环境字段和项目补充说明，可修改或清空。</template>
            <template v-else>上次评估没有可沿用的补充背景，请核对本次填写的内容。</template>
          </p>
          <p v-if="system.background.technologyStack" class="review-stack">本次档案技术栈 · {{ system.background.technologyStack }}</p>
        </div>
        <label for="research-profile">项目技术方向</label>
        <select id="research-profile" v-model="form.profileId" @change="changeProfile"><option v-for="profile in setup.profiles" :key="profile.id" :value="profile.id">{{ profile.label }}</option></select>
        <p class="hint">{{ selectedProfile?.description }}</p>
        <div class="fields"><label v-for="field in selectedProfile?.fields" :key="field.key">{{ field.label }}<input v-model="form.context[field.key]" :maxlength="field.maxLength" :placeholder="field.placeholder" /></label></div>
        <template v-if="reviewRequired">
          <label class="review-notes">项目补充说明<textarea v-model="form.context.notes" maxlength="1000" rows="3" /></label>
          <label class="review-confirm"><input v-model="reviewed" type="checkbox" :disabled="systemChanged || reloadingSystem || busy" />已核对本次背景</label>
        </template>
      </div>
      <fieldset><legend>重点关注什么？</legend><div class="checks"><label v-for="item in concernOptions" :key="item"><input v-model="form.concerns" type="checkbox" :value="item" />{{ item }}</label></div></fieldset>
      <details class="optional">
        <summary>补充项目背景与资料（选填）</summary>
        <label v-if="!reviewRequired">项目补充说明<textarea v-model="form.context.notes" maxlength="1000" rows="3" placeholder="例如：使用哪些功能、已有约束和重点验证场景；不填写密码或密钥。" /></label>
        <fieldset><legend>关联团队资料</legend><p class="hint">{{ system ? '档案资料会自动参与检索，可以在此追加其他资料。' : '仅检索本次勾选的文件。' }}</p><div v-if="usableUploads.length" class="checks files"><label v-for="file in usableUploads" :key="file.uploadId"><input v-if="systemFiles.has(file.uploadId)" type="checkbox" checked disabled /><input v-else v-model="form.uploadIds" type="checkbox" :value="file.uploadId" :disabled="!form.uploadIds.includes(file.uploadId) && selectedFileIds.size >= 10" />{{ file.originalName }} · {{ file.visibility === 'PRIVATE' ? '私有' : '团队' }}</label></div><p v-else class="hint">暂无处理完成的资料。可先到 <RouterLink to="/knowledge-files">我的资料</RouterLink> 上传。</p></fieldset>
        <label>已知 Issue 链接（每行一个，最多 3 个）<textarea v-model="issuesText" rows="3" :placeholder="`https://github.com/${selectedProject?.owner ?? 'owner'}/${selectedProject?.name ?? 'repo'}/issues/123`" /></label>
        <p class="hint">读取所选框架 Issue 的正文与状态。评论和相关 PR 不在本次读取范围内。{{ setup.issueAvailable ? '' : '当前 Issue 工具不可用。' }}</p>
      </details>
      <section class="source-readiness" aria-labelledby="source-readiness-title">
        <div class="readiness-heading"><h3 id="source-readiness-title">研究前的资料准备</h3><button type="button" class="text-button" :disabled="refreshingSources" @click="refreshSources">{{ refreshingSources ? '刷新中…' : '刷新资料状态' }}</button></div>
        <ul class="readiness-checklist">
          <li><strong>版本信息</strong><span>发起研究时重新读取官方 Release；{{ versionLoading ? '版本列表读取中' : versions.length ? `当前可选 ${versions.length} 个近期版本` : '当前未取得版本列表，可手动填写' }}。</span></li>
          <li><strong>官方文档</strong><span>{{ collectedSources.length }} / {{ projectSources.length }} 个来源已有文档{{ attentionSources.length ? `；${attentionSources.length} 个来源有同步提醒` : '' }}。</span></li>
          <li><strong>版本适用性</strong><span>尚未核实文档是否覆盖 {{ form.currentVersion || '当前版本' }} → {{ form.targetVersion || '目标版本' }}。文档数量和采集成功不能替代版本匹配；研究结果会列出资料缺口。</span></li>
          <li><strong>业务资料</strong><span>已选择 {{ selectedFileIds.size }} 份；{{ unfinishedUploads.length ? `${unfinishedUploads.length} 份当前框架资料尚未处理完成，暂不参与检索。` : '可在上方补充背景与资料。' }}没有执行你的业务构建或测试。</span></li>
        </ul>
        <p v-if="unfinishedUploads.length" class="hint"><RouterLink to="/knowledge-files">查看资料处理状态</RouterLink>，处理完成后刷新本页资料状态再勾选。</p>
        <details class="optional">
          <summary>查看 {{ projectSources.length }} 个来源的同步详情</summary>
          <p v-if="!projectSources.length" class="notice">尚未配置该框架的官方文档来源，评估可能缺少迁移依据。</p>
          <ul v-else class="source-list"><li v-for="source in projectSources" :key="source.url"><a :href="source.url" target="_blank" rel="noreferrer">{{ source.name }}</a><span>{{ sourceState(source) }} · {{ source.lastSyncAt ? `最近采集：${new Date(source.lastSyncAt).toLocaleString('zh-CN')}` : '尚无成功采集时间' }}{{ isRefreshDue(source) ? ' · 已到计划刷新时间，等待同步' : '' }}</span></li></ul>
        </details>
      </section>
      <details v-if="!system" class="optional"><summary>保存配置，下次复用</summary><p class="hint">配置按当前工作区和你的账号保存；用团队与业务项目命名，方便切换。</p><div class="save-config"><input v-model="presetName" maxlength="80" aria-label="配置名称" placeholder="例如：支付团队 · 订单服务" /><button type="button" class="secondary-button" :disabled="saving" @click="savePreset">{{ saving ? '保存中…' : '保存当前配置' }}</button></div></details>
      <div class="launch-row"><button class="primary-button" type="submit" :disabled="launchBlocked">{{ busy ? '正在启动研究…' : '开始升级评估' }}</button><p class="hint">{{ reviewRequired && !reviewed ? '请先在项目技术方向下勾选「已核对本次背景」。' : '输出主要变化、可能影响、验证清单、来源依据和尚未确认事项。资料不足会明确提示。' }}</p></div>
    </form>
    <RouterLink class="other-workflows" to="/agent-workflows">查看其他研究模板 →</RouterLink>
  </section>
</template>

<style scoped>
.recovery-warnings, .review-note { margin: 12px 0 20px; padding: 14px 18px; border-left: 3px solid #d6a65c; background: var(--panel-soft); border-radius: 4px; }
.recovery-warnings p, .review-note p { margin: 4px 0; line-height: 1.65; overflow-wrap: anywhere; }
.review-stack { white-space: pre-line; }
.reload-system, .profile-block .review-notes, .profile-block .review-confirm { margin-top: 14px; }
.profile-block .review-notes { display: grid; gap: 8px; }
.profile-block .review-confirm { display: flex; align-items: center; gap: 8px; }
.review-confirm input { width: auto; }
.linked-system { padding-bottom: 20px; margin-bottom: 18px; border-bottom: 1px solid var(--line); }
.event-context,.source-readiness{margin-bottom:24px;padding:18px;border:1px solid var(--line);border-radius:10px;background:var(--panel-soft)}.event-context>a{display:block;margin-top:8px;color:var(--accent);overflow-wrap:anywhere}.readiness-heading{display:flex;align-items:center;justify-content:space-between;gap:12px}.readiness-heading h3{margin:0}.readiness-checklist{padding:0;list-style:none;display:grid;gap:12px}.readiness-checklist li{display:grid;grid-template-columns:90px 1fr;gap:12px;line-height:1.6}.readiness-checklist span{color:var(--muted)}
input:not([type="checkbox"]),select,textarea{padding:10px 12px;border:1px solid var(--line);border-radius:8px;background:var(--panel-soft);color:inherit;font:inherit}input:focus-visible,select:focus-visible,textarea:focus-visible{outline:2px solid var(--accent);outline-offset:2px}input[type="checkbox"]{accent-color:var(--accent)}
.profile-block{border-top:1px solid var(--line);padding:20px 0 0}.profile-block>label{display:block;font-weight:600;margin-bottom:8px}.profile-block>select{max-width:300px}.source-list{padding-left:20px}.source-list li{margin:12px 0}.source-list span{display:block;color:var(--muted);font-size:12px;margin-top:5px}
.upgrade-page{max-width:1080px;margin:auto}.intro,.hint{color:var(--muted);line-height:1.7}.padded{padding:28px}.fields{display:grid;grid-template-columns:1fr 1fr;gap:20px}.fields label,.saved-config,label:has(textarea){display:grid;gap:8px}.fields label span{font-size:12px;color:var(--muted)}input,select,textarea{width:100%;box-sizing:border-box}fieldset{border:0;padding:16px 0;margin:10px 0}legend{font-weight:600}.checks{display:flex;gap:14px 24px;flex-wrap:wrap}.checks label{display:flex;align-items:center;gap:8px}.checks input{width:auto}.files{display:grid}.optional{border-top:1px solid var(--line);padding:18px 0}.optional summary{cursor:pointer;margin-bottom:14px}.save-config{display:flex;gap:10px}.save-config input{flex:1}.launch-row{display:flex;align-items:center;gap:20px;margin-top:14px}.launch-row button{min-width:175px}.launch-row p{font-size:13px}.saved-config{margin-bottom:24px}.error{padding:14px;background:rgba(220,80,80,.1);color:#d86b6b;border-radius:10px}.notice{padding:14px;background:rgba(80,180,140,.12);border-radius:10px}.other-workflows{display:inline-block;margin-top:20px}@media(max-width:700px){.fields{grid-template-columns:1fr}.padded{padding:18px}.launch-row,.save-config{flex-direction:column;align-items:stretch}}
</style>
