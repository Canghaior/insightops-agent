<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import {
  activateDatasetVersion, createDatasetVersion, decideEvaluationCandidate,
  evaluateDatasetVersion, listDatasetVersions, listEvaluationCandidates,
  listQualityFeedback, reviewQualityFeedback, updateEvaluationCandidate,
  type CandidateCommand, type DatasetVersion, type EvaluationCandidate,
  type QualityFeedback,
} from '@/api/qualityReview'

const activeTab = ref<'feedback' | 'candidates' | 'versions'>('feedback')
const feedback = ref<QualityFeedback[]>([])
const candidates = ref<EvaluationCandidate[]>([])
const versions = ref<DatasetVersion[]>([])
const selectedFeedback = ref<QualityFeedback | null>(null)
const selectedCandidate = ref<EvaluationCandidate | null>(null)
const selectedCandidateIds = ref<string[]>([])
const feedbackStatus = ref('PENDING')
const feedbackType = ref('')
const candidateStatus = ref('')
const loading = ref(false)
const saving = ref(false)
const error = ref('')
const notice = ref('')
const reviewNote = ref('')
const versionName = ref('')
const latestGate = ref('')

const candidateForm = reactive({
  question: '', expectedAnswerable: true, expectedProject: 'spring-ai',
  category: 'feedback-regression', mustHitTerms: '', answerMustInclude: '', sourceDomain: '',
})

const approvedCandidates = computed(() => candidates.value.filter(item => item.status === 'APPROVED'))

function message(caught: unknown): string {
  const value = caught as { response?: { data?: { message?: string } }; message?: string }
  return value.response?.data?.message || value.message || '操作失败，请稍后重试。'
}

function words(value: string): string[] {
  return value.split(/[,，\n]/).map(item => item.trim()).filter(Boolean)
}

function projectFor(question: string): string {
  const lower = question.toLowerCase()
  if (lower.includes('langchain4j')) return 'langchain4j'
  if (lower.includes('dify')) return 'dify'
  return 'spring-ai'
}

function domainFor(url: string | null): string {
  if (!url) return ''
  try { return new globalThis.URL(url).hostname }
  catch { return '' }
}

function candidateCommand(): CandidateCommand {
  return {
    question: candidateForm.question.trim(),
    expectedAnswerable: candidateForm.expectedAnswerable,
    expectedProject: candidateForm.expectedAnswerable ? candidateForm.expectedProject.trim() || null : null,
    category: candidateForm.category.trim(),
    mustHitTerms: words(candidateForm.mustHitTerms),
    answerMustInclude: words(candidateForm.answerMustInclude),
    sourceDomain: candidateForm.sourceDomain.trim() || null,
  }
}

function selectFeedback(item: QualityFeedback) {
  selectedFeedback.value = item
  selectedCandidate.value = null
  reviewNote.value = item.reviewerNote || ''
  Object.assign(candidateForm, {
    question: item.question,
    expectedAnswerable: true,
    expectedProject: projectFor(item.question),
    category: item.type === 'CITATION' ? 'citation-correction' : 'answer-feedback',
    mustHitTerms: '', answerMustInclude: '',
    sourceDomain: domainFor(item.citationUrl),
  })
}

function selectCandidate(item: EvaluationCandidate) {
  selectedCandidate.value = item
  selectedFeedback.value = null
  reviewNote.value = item.reviewerNote || ''
  Object.assign(candidateForm, {
    question: item.question, expectedAnswerable: item.expectedAnswerable,
    expectedProject: item.expectedProject || '', category: item.category,
    mustHitTerms: item.mustHitTerms.join(', '),
    answerMustInclude: item.answerMustInclude.join(', '), sourceDomain: item.sourceDomain || '',
  })
}

async function loadAll() {
  loading.value = true
  error.value = ''
  try {
    const [feedbackPage, candidatePage, versionItems] = await Promise.all([
      listQualityFeedback(feedbackStatus.value, feedbackType.value),
      listEvaluationCandidates(candidateStatus.value), listDatasetVersions(),
    ])
    feedback.value = feedbackPage.items
    candidates.value = candidatePage.items
    versions.value = versionItems
    selectedCandidateIds.value = selectedCandidateIds.value.filter(id =>
      candidates.value.some(item => item.id === id && item.status === 'APPROVED'))
  }
  catch (caught) { error.value = message(caught) }
  finally { loading.value = false }
}

async function review(decision: 'REVIEWED' | 'DISMISSED' | 'ADD_TO_EVAL') {
  if (!selectedFeedback.value) return
  if (decision === 'ADD_TO_EVAL' && (!candidateForm.question.trim()
      || (candidateForm.expectedAnswerable && (!words(candidateForm.mustHitTerms).length
        || !words(candidateForm.answerMustInclude).length || !candidateForm.sourceDomain.trim())))) {
    error.value = '可回答案例必须填写问题、检索必命中词、答案应包含词和来源域名。'
    return
  }
  saving.value = true; error.value = ''; notice.value = ''
  try {
    await reviewQualityFeedback(selectedFeedback.value, decision, reviewNote.value,
      decision === 'ADD_TO_EVAL' ? candidateCommand() : undefined)
    notice.value = decision === 'ADD_TO_EVAL' ? '反馈已进入评测候选池。' : '反馈复核状态已更新。'
    selectedFeedback.value = null
    await loadAll()
  }
  catch (caught) { error.value = message(caught) }
  finally { saving.value = false }
}

async function saveCandidate() {
  if (!selectedCandidate.value) return
  saving.value = true; error.value = ''
  try {
    await updateEvaluationCandidate(selectedCandidate.value.id, candidateCommand())
    notice.value = '候选题已保存。'; await loadAll()
  }
  catch (caught) { error.value = message(caught) }
  finally { saving.value = false }
}

async function decideCandidate(decision: 'APPROVED' | 'REJECTED') {
  if (!selectedCandidate.value) return
  saving.value = true; error.value = ''
  try {
    await decideEvaluationCandidate(selectedCandidate.value.id, decision, reviewNote.value)
    notice.value = decision === 'APPROVED' ? '候选题已批准，可加入新版本。' : '候选题已驳回。'
    selectedCandidate.value = null; await loadAll()
  }
  catch (caught) { error.value = message(caught) }
  finally { saving.value = false }
}

async function createVersion() {
  if (!versionName.value.trim() || !selectedCandidateIds.value.length) {
    error.value = '请填写版本名称并选择至少一个已批准候选题。'; return
  }
  saving.value = true; error.value = ''
  try {
    await createDatasetVersion(versionName.value.trim(), selectedCandidateIds.value)
    notice.value = '评测版本已创建；必须先运行并通过质量门禁才能激活。'
    versionName.value = ''; selectedCandidateIds.value = []; await loadAll()
  }
  catch (caught) { error.value = message(caught) }
  finally { saving.value = false }
}

async function evaluateVersion(version: DatasetVersion) {
  saving.value = true; error.value = ''; latestGate.value = ''
  try {
    const report = await evaluateDatasetVersion(version.id)
    latestGate.value = `${report.datasetName}：${report.status} · ${report.caseCount} 题`
    notice.value = report.status === 'PASSED' ? '版本评测通过，现在可以激活。' : '版本未通过质量门禁。'
    await loadAll()
  }
  catch (caught) { error.value = message(caught) }
  finally { saving.value = false }
}

async function activateVersion(version: DatasetVersion) {
  saving.value = true; error.value = ''
  try { await activateDatasetVersion(version.id); notice.value = '新评测版本已激活。'; await loadAll() }
  catch (caught) { error.value = message(caught) }
  finally { saving.value = false }
}

function time(value: string | null): string { return value ? new Date(value).toLocaleString() : '—' }

onMounted(loadAll)
</script>

<template>
  <section class="quality-review-page">
    <div class="section-heading">
      <div>
        <span class="eyebrow">P1.7 · 质量闭环</span><h2>反馈复核与评测回流</h2>
        <p>从用户反馈追溯 Run、证据和模型，经人工审核后进入版本化 RAG 发布门禁。</p>
      </div>
      <button class="secondary-button" :disabled="loading" @click="loadAll">刷新</button>
    </div>
    <div class="quality-tabs">
      <button :class="{ active: activeTab === 'feedback' }" @click="activeTab = 'feedback'">反馈复核（{{ feedback.length }}）</button>
      <button :class="{ active: activeTab === 'candidates' }" @click="activeTab = 'candidates'">评测候选（{{ candidates.length }}）</button>
      <button :class="{ active: activeTab === 'versions' }" @click="activeTab = 'versions'">版本门禁（{{ versions.length }}）</button>
    </div>
    <p v-if="error" class="stream-error">{{ error }}</p>
    <p v-if="notice" class="success-notice">{{ notice }}</p>
    <p v-if="latestGate" class="guardrail-notice">{{ latestGate }}</p>

    <div v-if="activeTab === 'feedback'" class="quality-layout">
      <div>
        <div class="quality-filters panel">
          <label>状态<select v-model="feedbackStatus" @change="loadAll"><option value="">全部</option><option value="PENDING">待复核</option><option value="REVIEWED">已处理</option><option value="ADDED_TO_EVAL">已加入评测</option><option value="DISMISSED">已忽略</option></select></label>
          <label>类型<select v-model="feedbackType" @change="loadAll"><option value="">全部</option><option value="ANSWER">答案反馈</option><option value="CITATION">引用反馈</option></select></label>
        </div>
        <div class="quality-list">
          <button v-for="item in feedback" :key="`${item.type}-${item.id}`" class="quality-list-item" :class="{ active: selectedFeedback?.id === item.id }" @click="selectFeedback(item)">
            <span><b>{{ item.type === 'ANSWER' ? '答案' : '引用' }}</b><i>{{ item.reviewStatus }}</i></span>
            <strong>{{ item.question }}</strong><small>{{ item.displayName }} · {{ time(item.createdAt) }} · {{ item.modelName || '未知模型' }}</small>
          </button>
          <div v-if="!loading && !feedback.length" class="conversation-empty"><strong>当前没有匹配的反馈</strong></div>
        </div>
      </div>
      <article v-if="selectedFeedback" class="panel quality-detail">
        <header><div><span class="eyebrow">{{ selectedFeedback.traceId }}</span><h3>{{ selectedFeedback.question }}</h3></div><RouterLink :to="`/runs/${selectedFeedback.runId}`" class="text-button">查看 Run</RouterLink></header>
        <dl><div><dt>用户</dt><dd>{{ selectedFeedback.displayName }}（{{ selectedFeedback.username }}）</dd></div><div><dt>模型</dt><dd>{{ selectedFeedback.modelProvider }}/{{ selectedFeedback.modelName }}</dd></div><div><dt>反馈</dt><dd>{{ selectedFeedback.type === 'ANSWER' ? (selectedFeedback.helpful ? '有帮助' : '需改进') : (selectedFeedback.citationCorrect ? '引用正确' : '引用有误') }}</dd></div></dl>
        <p v-if="selectedFeedback.comment"><b>用户说明：</b>{{ selectedFeedback.comment }}</p>
        <a v-if="selectedFeedback.citationUrl" :href="selectedFeedback.citationUrl" target="_blank" rel="noopener noreferrer">{{ selectedFeedback.citationUrl }}</a>
        <details><summary>查看回答与引用</summary><p>{{ selectedFeedback.answer || '尚无回答' }}</p><div class="tag-row"><a v-for="url in selectedFeedback.citations" :key="url" :href="url" target="_blank" rel="noopener noreferrer">{{ url }}</a></div></details>
        <label>复核说明<textarea v-model="reviewNote" maxlength="1000" placeholder="记录判断依据和修复方向" /></label>
        <div class="quality-actions"><button class="secondary-button" :disabled="saving" @click="review('REVIEWED')">标记已处理</button><button class="danger-button" :disabled="saving" @click="review('DISMISSED')">忽略</button></div>
        <fieldset class="candidate-editor">
          <legend>转为评测候选</legend>
          <label>问题<textarea v-model="candidateForm.question" maxlength="4000" /></label>
          <div class="quality-form-grid"><label>分类<input v-model="candidateForm.category" /></label><label>预期项目<input v-model="candidateForm.expectedProject" :disabled="!candidateForm.expectedAnswerable" /></label><label>来源域名<input v-model="candidateForm.sourceDomain" /></label></div>
          <label class="check-label"><input v-model="candidateForm.expectedAnswerable" type="checkbox" /> 应当可以回答</label>
          <label>检索必命中词<input v-model="candidateForm.mustHitTerms" placeholder="逗号分隔" /></label><label>答案应包含词<input v-model="candidateForm.answerMustInclude" placeholder="逗号分隔" /></label>
          <button class="send-button" :disabled="saving" @click="review('ADD_TO_EVAL')">加入候选池</button>
        </fieldset>
      </article>
      <div v-else class="conversation-empty"><strong>选择一条反馈查看完整上下文</strong></div>
    </div>

    <div v-if="activeTab === 'candidates'" class="quality-layout">
      <div>
        <div class="quality-filters panel"><label>状态<select v-model="candidateStatus" @change="loadAll"><option value="">全部</option><option value="DRAFT">草稿</option><option value="APPROVED">已批准</option><option value="REJECTED">已驳回</option><option value="INCLUDED">已入版本</option></select></label></div>
        <div class="quality-list"><button v-for="item in candidates" :key="item.id" class="quality-list-item" :class="{ active: selectedCandidate?.id === item.id }" @click="selectCandidate(item)"><span><b>{{ item.category }}</b><i>{{ item.status }}</i></span><strong>{{ item.question }}</strong><small>{{ item.expectedAnswerable ? item.expectedProject : '预期拒答' }} · {{ time(item.updatedAt) }}</small></button></div>
      </div>
      <article v-if="selectedCandidate" class="panel quality-detail">
        <header><div><span class="eyebrow">{{ selectedCandidate.sourceFeedbackType }}</span><h3>候选题编辑</h3></div><i class="status-pill">{{ selectedCandidate.status }}</i></header>
        <fieldset class="candidate-editor" :disabled="selectedCandidate.status !== 'DRAFT'"><label>问题<textarea v-model="candidateForm.question" /></label><div class="quality-form-grid"><label>分类<input v-model="candidateForm.category" /></label><label>预期项目<input v-model="candidateForm.expectedProject" :disabled="!candidateForm.expectedAnswerable" /></label><label>来源域名<input v-model="candidateForm.sourceDomain" /></label></div><label class="check-label"><input v-model="candidateForm.expectedAnswerable" type="checkbox" /> 应当可以回答</label><label>检索必命中词<input v-model="candidateForm.mustHitTerms" /></label><label>答案应包含词<input v-model="candidateForm.answerMustInclude" /></label><label>审核说明<textarea v-model="reviewNote" /></label><div class="quality-actions"><button class="secondary-button" @click="saveCandidate">保存</button><button class="send-button" @click="decideCandidate('APPROVED')">批准</button><button class="danger-button" @click="decideCandidate('REJECTED')">驳回</button></div></fieldset>
      </article><div v-else class="conversation-empty"><strong>选择候选题进行编辑和审批</strong></div>
    </div>

    <div v-if="activeTab === 'versions'">
      <div class="panel version-builder"><div><span class="eyebrow">新评测版本</span><h3>从已批准候选构建版本</h3><p>版本先保持草稿；只有完整 RAG 评测通过后才能激活。</p></div><label>版本名称<input v-model="versionName" placeholder="例如：p1-rag-feedback-v1" /></label><div class="candidate-checklist"><label v-for="item in approvedCandidates" :key="item.id" class="check-label"><input v-model="selectedCandidateIds" type="checkbox" :value="item.id" /> {{ item.question }}</label><span v-if="!approvedCandidates.length">暂无已批准候选题</span></div><button class="send-button" :disabled="saving || !approvedCandidates.length" @click="createVersion">创建草稿版本</button></div>
      <div class="version-grid"><article v-for="version in versions" :key="version.id" class="panel version-card"><header><div><span class="eyebrow">版本 {{ version.versionNumber }}</span><h3>{{ version.name }}</h3></div><i class="status-pill" :class="version.status === 'ACTIVE' ? 'status-succeeded' : ''">{{ version.status }}</i></header><dl><div><dt>基础集</dt><dd>{{ version.baseDatasetName }}</dd></div><div><dt>新增案例</dt><dd>{{ version.candidateCount }}</dd></div><div><dt>最近门禁</dt><dd>{{ version.gateStatus || '尚未运行' }}</dd></div><div><dt>激活时间</dt><dd>{{ time(version.activatedAt) }}</dd></div></dl><footer v-if="version.status === 'DRAFT'"><button class="secondary-button" :disabled="saving" @click="evaluateVersion(version)">运行完整评测</button><button class="send-button" :disabled="saving || version.gateStatus !== 'PASSED'" @click="activateVersion(version)">激活版本</button></footer></article><div v-if="!versions.length" class="conversation-empty"><strong>尚未创建反馈评测版本</strong></div></div>
    </div>
  </section>
</template>
