<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'

import { listProjects, type ProjectWatch } from '@/api/projects'
import {
  createDeliveryChannel, createReport, deleteDeliveryChannel, downloadReport,
  enqueueReportDelivery, listDeliveryChannels, listReportDeliveries, listReports,
  retryReportDelivery, updateDeliveryChannel, type DeliveryChannel, type DeliveryRecord,
  type ReportEventType, type ResearchReport,
} from '@/api/reports'

const reports = ref<ResearchReport[]>([])
const channels = ref<DeliveryChannel[]>([])
const deliveries = ref<DeliveryRecord[]>([])
const projects = ref<ProjectWatch[]>([])
const selectedReport = ref<ResearchReport | null>(null)
const selectedChannelId = ref('')
const loading = ref(false)
const saving = ref(false)
const error = ref('')
const notice = ref('')
const channelName = ref('')
const endpointUrl = ref('')
const today = new Date()
const weekAgo = new Date(today.getTime() - 7 * 86_400_000)
const isoDate = (value: Date) => value.toISOString().slice(0, 10)
const form = reactive({
  title: `技术情报周报 ${isoDate(today)}`,
  periodStart: isoDate(weekAgo), periodEnd: isoDate(today), projectIds: [] as string[],
  eventTypes: ['GITHUB_RELEASE', 'GITHUB_ISSUE', 'GITHUB_PULL_REQUEST', 'GITHUB_SECURITY_ADVISORY'] as ReportEventType[],
  maxItems: 50,
})
const enabledChannels = computed(() => channels.value.filter(item => item.enabled))
const activeProjects = computed(() => projects.value.filter(item => item.enabled))

function message(caught: unknown): string {
  const value = caught as { response?: { data?: { message?: string; detail?: string } }; message?: string }
  return value.response?.data?.message || value.response?.data?.detail || value.message || '操作失败，请稍后重试。'
}

async function load() {
  loading.value = true; error.value = ''
  try {
    const [reportPage, channelItems, deliveryPage, projectItems] = await Promise.all([
      listReports(), listDeliveryChannels(), listReportDeliveries(), listProjects(),
    ])
    reports.value = reportPage.items; channels.value = channelItems
    deliveries.value = deliveryPage.items; projects.value = projectItems
    if (!form.projectIds.length) form.projectIds = projectItems.filter(item => item.enabled).map(item => item.id)
    if (selectedReport.value) selectedReport.value = reports.value.find(item => item.id === selectedReport.value?.id) || null
    if (!enabledChannels.value.some(item => item.id === selectedChannelId.value)) {
      selectedChannelId.value = enabledChannels.value[0]?.id || ''
    }
  }
  catch (caught) { error.value = message(caught) }
  finally { loading.value = false }
}

async function generate() {
  if (!form.title.trim() || !form.periodStart || !form.periodEnd || !form.eventTypes.length) {
    error.value = '请填写报告标题、周期并选择至少一种事件类型。'; return
  }
  saving.value = true; error.value = ''; notice.value = ''
  try {
    const report = await createReport({
      title: form.title.trim(), periodStart: new Date(`${form.periodStart}T00:00:00`).toISOString(),
      periodEnd: new Date(`${form.periodEnd}T23:59:59.999`).toISOString(),
      projectIds: form.projectIds, eventTypes: form.eventTypes, maxItems: form.maxItems,
    })
    notice.value = `报告已生成，共 ${report.itemCount} 条情报。`
    await load(); selectedReport.value = reports.value.find(item => item.id === report.id) || report
  }
  catch (caught) { error.value = message(caught) }
  finally { saving.value = false }
}

async function download(report: ResearchReport, format: 'md' | 'pdf') {
  saving.value = true; error.value = ''
  try {
    const blob = await downloadReport(report.id, format)
    const url = globalThis.URL.createObjectURL(blob)
    const link = globalThis.document.createElement('a'); link.href = url
    link.download = `insightops-${report.id}.${format}`; link.click()
    globalThis.URL.revokeObjectURL(url); notice.value = `${format.toUpperCase()} 报告已下载。`
  }
  catch (caught) { error.value = message(caught) }
  finally { saving.value = false }
}

async function addChannel() {
  if (!channelName.value.trim() || !endpointUrl.value.trim()) { error.value = '请填写渠道名称和 HTTPS Webhook 地址。'; return }
  saving.value = true; error.value = ''
  try {
    await createDeliveryChannel(channelName.value.trim(), endpointUrl.value.trim())
    channelName.value = ''; endpointUrl.value = ''; notice.value = 'Webhook 渠道已加密保存。'; await load()
  }
  catch (caught) { error.value = message(caught) }
  finally { saving.value = false }
}

async function toggleChannel(channel: DeliveryChannel) {
  saving.value = true; error.value = ''
  try { await updateDeliveryChannel(channel, !channel.enabled); await load() }
  catch (caught) { error.value = message(caught) }
  finally { saving.value = false }
}

async function removeChannel(channel: DeliveryChannel) {
  saving.value = true; error.value = ''
  try { await deleteDeliveryChannel(channel.id); notice.value = '渠道已删除，历史投递审计保留。'; await load() }
  catch (caught) { error.value = message(caught) }
  finally { saving.value = false }
}

async function deliver(report: ResearchReport) {
  if (!selectedChannelId.value) { error.value = '请先配置并选择一个已启用 Webhook 渠道。'; return }
  saving.value = true; error.value = ''
  try {
    await enqueueReportDelivery(report.id, selectedChannelId.value)
    notice.value = '报告已进入投递队列；重复提交不会重复发送。'; await load()
  }
  catch (caught) { error.value = message(caught) }
  finally { saving.value = false }
}

async function retry(item: DeliveryRecord) {
  saving.value = true; error.value = ''
  try { await retryReportDelivery(item.id); notice.value = '失败投递已重新入队。'; await load() }
  catch (caught) { error.value = message(caught) }
  finally { saving.value = false }
}

function time(value: string | null): string { return value ? new Date(value).toLocaleString() : '—' }
onMounted(load)
</script>

<template>
  <section class="reports-page">
    <div class="section-heading"><div><span class="eyebrow">P1.8 · 结果交付</span><h2>报告与站外交付</h2><p>把已完成情报分析固化为可追溯快照，导出 Markdown / PDF，或通过安全 Webhook 投递。</p></div><button class="secondary-button" :disabled="loading" @click="load">刷新</button></div>
    <p v-if="error" class="stream-error">{{ error }}</p><p v-if="notice" class="success-notice">{{ notice }}</p>
    <div class="report-create-grid">
      <form class="panel report-builder" @submit.prevent="generate"><div><span class="eyebrow">生成专题报告</span><h3>选择周期和情报范围</h3></div><label>报告标题<input v-model="form.title" maxlength="200" /></label><div class="quality-form-grid"><label>开始日期<input v-model="form.periodStart" type="date" /></label><label>结束日期<input v-model="form.periodEnd" type="date" /></label><label>最多条目<input v-model.number="form.maxItems" type="number" min="1" max="100" /></label></div><fieldset><legend>项目</legend><label v-for="project in activeProjects" :key="project.id" class="check-label"><input v-model="form.projectIds" type="checkbox" :value="project.id" /> {{ project.name }}</label></fieldset><fieldset><legend>事件类型</legend><label class="check-label"><input v-model="form.eventTypes" type="checkbox" value="GITHUB_RELEASE" /> Release</label><label class="check-label"><input v-model="form.eventTypes" type="checkbox" value="GITHUB_ISSUE" /> Issue</label><label class="check-label"><input v-model="form.eventTypes" type="checkbox" value="GITHUB_PULL_REQUEST" /> Pull Request</label><label class="check-label"><input v-model="form.eventTypes" type="checkbox" value="GITHUB_SECURITY_ADVISORY" /> Security Advisory</label></fieldset><button class="send-button" :disabled="saving">生成不可变报告快照</button></form>
      <form class="panel report-builder" @submit.prevent="addChannel"><div><span class="eyebrow">站外交付</span><h3>Webhook 渠道</h3><p>仅接受公网 HTTPS 地址；完整地址使用 AES-GCM 加密，页面只显示掩码。</p></div><label>渠道名称<input v-model="channelName" maxlength="100" placeholder="例如：架构组机器人" /></label><label>Webhook 地址<input v-model="endpointUrl" type="url" maxlength="2048" placeholder="https://hooks.example.com/..." /></label><button class="send-button" :disabled="saving">添加渠道</button><div class="delivery-channels"><article v-for="channel in channels" :key="channel.id"><div><strong>{{ channel.name }}</strong><small>{{ channel.endpointMasked }}</small></div><i :class="channel.enabled ? 'status-succeeded' : ''">{{ channel.enabled ? '已启用' : '已停用' }}</i><button type="button" class="text-button" @click="toggleChannel(channel)">{{ channel.enabled ? '停用' : '启用' }}</button><button type="button" class="danger-link" @click="removeChannel(channel)">删除</button></article><p v-if="!channels.length" class="subtle">尚未配置站外渠道。</p></div></form>
    </div>
    <div class="report-workspace"><div class="report-list"><h3 class="subsection-title">历史报告</h3><button v-for="report in reports" :key="report.id" :class="{active:selectedReport?.id===report.id}" @click="selectedReport=report"><span><b>{{ report.title }}</b><i>{{ report.highRiskCount }} 高风险</i></span><small>{{ report.itemCount }} 条 · {{ time(report.createdAt) }}</small></button><div v-if="!reports.length" class="conversation-empty"><strong>尚未生成报告</strong></div></div><article v-if="selectedReport" class="panel report-detail"><header><div><span class="eyebrow">{{ selectedReport.reportType }}</span><h3>{{ selectedReport.title }}</h3><p>{{ time(selectedReport.periodStart) }} — {{ time(selectedReport.periodEnd) }}</p></div><i class="status-pill">{{ selectedReport.itemCount }} 条</i></header><div class="quality-actions"><button class="secondary-button" :disabled="saving" @click="download(selectedReport,'md')">下载 Markdown</button><button class="secondary-button" :disabled="saving" @click="download(selectedReport,'pdf')">下载 PDF</button><select v-model="selectedChannelId"><option value="">选择 Webhook</option><option v-for="channel in enabledChannels" :key="channel.id" :value="channel.id">{{ channel.name }}</option></select><button class="send-button" :disabled="saving || !selectedChannelId" @click="deliver(selectedReport)">加入投递队列</button></div><details open><summary>报告预览</summary><pre>{{ selectedReport.markdown }}</pre></details></article><div v-else class="conversation-empty"><strong>选择一份报告查看与交付</strong></div></div>
    <section class="delivery-audit"><h3 class="subsection-title">投递审计</h3><div class="panel delivery-table"><article v-for="item in deliveries" :key="item.id"><div><strong>{{ item.reportTitle }}</strong><small>{{ item.channelName }} · {{ item.endpointMasked }}</small></div><i class="status-pill" :class="item.status==='SUCCEEDED'?'status-succeeded':''">{{ item.status }}</i><span>尝试 {{ item.attempts }}/{{ item.maxAttempts }}</span><span>HTTP {{ item.responseCode || '—' }} · {{ item.durationMs ?? '—' }} ms</span><p v-if="item.lastError">{{ item.lastError }}</p><button v-if="item.status==='FAILED'" class="secondary-button" :disabled="saving" @click="retry(item)">重试</button></article><p v-if="!deliveries.length" class="subtle">暂无投递记录。</p></div></section>
  </section>
</template>
