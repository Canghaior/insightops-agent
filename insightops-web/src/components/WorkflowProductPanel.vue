<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'

import {
  createWorkflowShare,
  exportWorkflowBundle,
  getWorkflowAnalytics,
  importSharedWorkflow,
  importWorkflowBundle,
  listWorkflowShares,
  previewSharedWorkflow,
  revokeWorkflowShare,
  type WorkflowAnalytics,
  type WorkflowExportBundle,
  type WorkflowShare,
} from '@/api/workflowProducts'

const props = defineProps<{
  templateId: string
  versionId: string
  templateName: string
}>()
const emit = defineEmits<{ changed: [templateId: string] }>()

const analytics = ref<WorkflowAnalytics | null>(null)
const shares = ref<WorkflowShare[]>([])
const loading = ref(false)
const error = ref('')
const success = ref('')
const windowDays = ref(30)
const createdShareUrl = ref('')
const sharedToken = ref('')
const sharedBundle = ref<WorkflowExportBundle | null>(null)

const maxDailyRuns = computed(() => Math.max(
  1,
  ...(analytics.value?.daily.map((item) => item.runCount) ?? [1]),
))

async function refresh() {
  if (!props.templateId) return
  loading.value = true
  error.value = ''
  try {
    const [quality, activeShares] = await Promise.all([
      getWorkflowAnalytics(props.templateId, windowDays.value),
      listWorkflowShares(props.templateId),
    ])
    analytics.value = quality
    shares.value = activeShares
  } catch {
    error.value = '模板质量趋势或分享记录加载失败。'
  } finally {
    loading.value = false
  }
}

async function downloadBundle() {
  if (!props.templateId || !props.versionId) return
  error.value = ''
  try {
    const bundle = await exportWorkflowBundle(props.templateId, props.versionId)
    const blob = new globalThis.Blob([JSON.stringify(bundle, null, 2)], { type: 'application/json' })
    const url = globalThis.URL.createObjectURL(blob)
    const anchor = globalThis.document.createElement('a')
    anchor.href = url
    anchor.download = `${props.templateName}-v${bundle.version.sourceVersion}.workflow.json`
    anchor.click()
    globalThis.URL.revokeObjectURL(url)
    success.value = '模板包已导出；包内不包含 Run、用户数据或分享令牌。'
  } catch {
    error.value = '模板导出失败。'
  }
}

async function importFile(event: globalThis.Event) {
  const input = event.target as globalThis.HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  error.value = ''
  try {
    const bundle = JSON.parse(await file.text()) as WorkflowExportBundle
    const name = globalThis.prompt('导入后的模板名称', `${bundle.template?.name ?? '工作流'}（导入）`)
    if (!name?.trim()) return
    const imported = await importWorkflowBundle(name.trim(), bundle)
    success.value = `“${imported.name}”已作为草稿模板导入，激活前仍需预检。`
    emit('changed', imported.id)
  } catch {
    error.value = '导入失败：请确认文件为 P2.4-C workflow bundle，且图和工具合同有效。'
  }
}

async function createShare() {
  if (!props.templateId || !props.versionId) return
  error.value = ''
  try {
    const result = await createWorkflowShare(props.templateId, props.versionId, 30)
    const url = new globalThis.URL('/admin/agent-workflows', globalThis.location.origin)
    url.hash = `share=${encodeURIComponent(result.token)}`
    createdShareUrl.value = url.toString()
    success.value = '30 天分享链接已创建。原始令牌只显示这一次，请立即复制。'
    await refresh()
  } catch {
    error.value = '创建分享链接失败。'
  }
}

async function copyShare() {
  if (!createdShareUrl.value) return
  try {
    await globalThis.navigator.clipboard.writeText(createdShareUrl.value)
    success.value = '分享链接已复制。'
  } catch {
    error.value = '浏览器禁止自动复制，请手动选择链接。'
  }
}

async function revoke(shareId: string) {
  if (!globalThis.confirm('撤销这个分享链接？撤销后不能恢复。')) return
  try {
    await revokeWorkflowShare(shareId)
    createdShareUrl.value = ''
    await refresh()
    success.value = '分享已撤销。'
  } catch {
    error.value = '撤销分享失败。'
  }
}

async function inspectShared() {
  if (!sharedToken.value.trim()) return
  try {
    const result = await previewSharedWorkflow(sharedToken.value.trim())
    sharedBundle.value = result.bundle
    success.value = `已验证分享模板“${result.bundle.template.name}”v${result.bundle.version.sourceVersion}。`
  } catch {
    sharedBundle.value = null
    error.value = '分享令牌无效、已撤销或已过期。'
  }
}

async function importShared() {
  if (!sharedBundle.value || !sharedToken.value.trim()) return
  const name = globalThis.prompt('导入后的模板名称', `${sharedBundle.value.template.name}（分享导入）`)
  if (!name?.trim()) return
  try {
    const imported = await importSharedWorkflow(sharedToken.value.trim(), name.trim())
    sharedBundle.value = null
    sharedToken.value = ''
    success.value = `“${imported.name}”已导入为草稿。`
    emit('changed', imported.id)
  } catch {
    error.value = '分享模板导入失败。'
  }
}

function percent(value: number) {
  return `${Math.round(value * 100)}%`
}

function duration(value: number) {
  if (!value) return '—'
  return value >= 60_000 ? `${(value / 60_000).toFixed(1)} 分` : `${(value / 1000).toFixed(1)} 秒`
}

watch(() => props.templateId, () => refresh())
watch(windowDays, () => refresh())

onMounted(async () => {
  const token = new globalThis.URLSearchParams(
    globalThis.location.hash.replace(/^#/, ''),
  ).get('share')
  if (token) {
    sharedToken.value = token
    await inspectShared()
  }
})
</script>

<template>
  <section class="panel product-panel">
    <header>
      <div><span class="eyebrow">P2.4-C · Productization</span><h3>分享、导入导出与质量趋势</h3></div>
      <div class="panel-actions">
        <button type="button" class="secondary-button" @click="downloadBundle">导出当前版本</button>
        <label class="secondary-button file-button">导入模板包<input type="file" accept="application/json,.json" @change="importFile" /></label>
        <button type="button" class="secondary-button" @click="createShare">创建分享链接</button>
      </div>
    </header>
    <p v-if="error" class="product-error">{{ error }}</p>
    <p v-if="success" class="product-success">{{ success }}</p>

    <div v-if="createdShareUrl" class="share-once">
      <strong>仅本次显示的分享链接</strong>
      <input :value="createdShareUrl" readonly />
      <button type="button" class="secondary-button" @click="copyShare">复制</button>
    </div>

    <div class="share-import">
      <label>导入分享令牌或链接中的 token<input v-model="sharedToken" placeholder="粘贴 share 参数" /></label>
      <button type="button" class="secondary-button" @click="inspectShared">验证分享</button>
      <button v-if="sharedBundle" type="button" class="secondary-button" @click="importShared">
        导入“{{ sharedBundle.template.name }}”
      </button>
    </div>

    <div class="quality-heading">
      <div><span class="eyebrow">Quality Trend</span><h4>真实运行质量</h4></div>
      <label>窗口<select v-model="windowDays"><option :value="7">7 天</option><option :value="30">30 天</option><option :value="90">90 天</option><option :value="365">365 天</option></select></label>
    </div>
    <div v-if="loading" class="subtle">正在聚合真实 Run、节点、反馈和费用…</div>
    <template v-else-if="analytics">
      <div class="quality-cards">
        <article><span>运行</span><strong>{{ analytics.summary.runCount }}</strong></article>
        <article><span>成功率</span><strong>{{ percent(analytics.summary.successRate) }}</strong></article>
        <article><span>节点成功率</span><strong>{{ percent(analytics.summary.nodeSuccessRate) }}</strong></article>
        <article><span>平均耗时</span><strong>{{ duration(analytics.summary.averageDurationMs) }}</strong></article>
        <article><span>Token</span><strong>{{ analytics.summary.totalTokens.toLocaleString() }}</strong></article>
        <article><span>费用</span><strong>¥{{ Number(analytics.summary.estimatedCostCny).toFixed(4) }}</strong></article>
        <article><span>有帮助</span><strong>{{ analytics.summary.feedbackCount ? percent(analytics.summary.helpfulRate) : '—' }}</strong></article>
        <article><span>引用正确率</span><strong>{{ analytics.summary.citationCount ? percent(analytics.summary.citationCorrectRate) : '—' }}</strong></article>
      </div>
      <div v-if="analytics.daily.length" class="trend-bars">
        <article v-for="point in analytics.daily" :key="point.bucket">
          <div class="bar-track"><span :style="{ height: `${Math.max(8, point.runCount / maxDailyRuns * 100)}%` }" /></div>
          <strong>{{ point.runCount }}</strong><small>{{ point.bucket.slice(5) }}</small>
        </article>
      </div>
      <div v-else class="quality-empty">当前窗口尚无该模板的真实 Run。</div>
      <div v-if="analytics.versions.length" class="version-quality">
        <span v-for="version in analytics.versions" :key="version.bucket">
          <strong>{{ version.bucket }}</strong> {{ version.runCount }} 次 · 成功 {{ percent(version.successRate) }} · ¥{{ Number(version.estimatedCostCny).toFixed(4) }}
        </span>
      </div>
    </template>

    <div class="share-list">
      <h4>分享审计</h4>
      <article v-for="share in shares" :key="share.id">
        <span><strong>{{ share.status }}</strong> · 到期 {{ new Date(share.expiresAt).toLocaleString() }} · 已导入 {{ share.importCount }} 次</span>
        <button v-if="share.status === 'ACTIVE'" type="button" class="text-button" @click="revoke(share.id)">撤销</button>
      </article>
      <p v-if="!shares.length" class="subtle">尚未创建分享链接。</p>
    </div>
  </section>
</template>

<style scoped>
.product-panel{padding:24px}.product-panel>header,.panel-actions,.quality-heading,.share-import,.share-once,.share-list article{display:flex;align-items:center;justify-content:space-between;gap:10px}.panel-actions{flex-wrap:wrap;justify-content:flex-end}.file-button{cursor:pointer}.file-button input{display:none}.product-error,.product-success{padding:11px 14px;border-radius:11px}.product-error{color:#efaaaa;border:1px solid rgba(222,100,100,.4)}.product-success{color:var(--accent);border:1px solid rgba(99,214,181,.3)}
.share-once{margin:16px 0;padding:14px;border:1px solid #d99a5d;border-radius:13px;flex-wrap:wrap}.share-once input{min-width:280px;flex:1}.share-import{margin:16px 0 26px;justify-content:flex-start;flex-wrap:wrap}.share-import label{display:grid;gap:6px;flex:1;min-width:260px;color:var(--muted)}
.quality-heading{margin:12px 0}.quality-heading label{display:flex;align-items:center;gap:8px;color:var(--muted)}.quality-cards{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:10px}.quality-cards article{display:grid;gap:5px;padding:13px;border:1px solid var(--line);border-radius:13px}.quality-cards span{color:var(--muted);font-size:12px}.quality-cards strong{font-size:20px}
.trend-bars{height:160px;display:flex;align-items:end;gap:8px;margin:22px 0;padding:10px;border-bottom:1px solid var(--line);overflow-x:auto}.trend-bars article{min-width:32px;text-align:center;display:grid;gap:4px}.bar-track{height:100px;display:flex;align-items:end;justify-content:center}.bar-track span{display:block;width:18px;min-height:8px;border-radius:6px 6px 2px 2px;background:linear-gradient(var(--accent),rgba(99,214,181,.28))}.trend-bars small{color:var(--muted);font-size:9px}.version-quality{display:flex;gap:8px;flex-wrap:wrap}.version-quality span{padding:8px 10px;border-radius:10px;background:rgba(99,214,181,.06);color:var(--muted)}.version-quality strong{color:var(--text)}.quality-empty{padding:22px;color:var(--muted);text-align:center}
.share-list{margin-top:24px;border-top:1px solid var(--line);padding-top:16px}.share-list article{padding:9px 0;border-bottom:1px solid rgba(255,255,255,.04);color:var(--muted)}
@media(max-width:900px){.product-panel>header,.quality-heading{align-items:flex-start;flex-direction:column}.quality-cards{grid-template-columns:repeat(2,minmax(0,1fr))}}@media(max-width:600px){.quality-cards{grid-template-columns:1fr}.share-once input{min-width:100%}}
</style>
