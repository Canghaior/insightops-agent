<script setup lang="ts">
import axios from 'axios'
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'

import { listProjects, type ProjectWatch } from '@/api/projects'
import {
  deleteKnowledgeUpload,
  knowledgeUploadDownloadUrl,
  listKnowledgeUploads,
  retryKnowledgeUpload,
  uploadKnowledgeFile,
  type KnowledgeUpload,
  type UploadVisibility,
} from '@/api/uploads'
import { useAuthStore } from '@/stores/auth'
import {
  beginKnowledgeStatusLoad,
  completeKnowledgeStatusLoad,
  failKnowledgeStatusLoad,
} from './adminKnowledgeLoadState'

const auth = useAuthStore()
const projects = ref<ProjectWatch[]>([])
const uploads = ref<KnowledgeUpload[]>([])
const projectId = ref('')
const visibility = ref<UploadVisibility>('PRIVATE')
const selectedFile = ref<InstanceType<typeof globalThis.File> | null>(null)
const input = ref<InstanceType<typeof globalThis.HTMLInputElement> | null>(null)
const loading = ref(false)
const submitting = ref(false)
const error = ref('')
const refreshError = ref('')
const notice = ref('')
let timer: number | null = null

const usedBytes = computed(() => uploads.value.reduce((total, upload) => total + upload.byteSize, 0))

async function load(silent = false) {
  if (!silent) loading.value = true
  beginKnowledgeStatusLoad(error, refreshError, silent)
  try {
    const [projectData, uploadData] = await Promise.all([listProjects(), listKnowledgeUploads()])
    projects.value = projectData
    uploads.value = uploadData
    if (!projectId.value && projectData.length) projectId.value = projectData[0].id
    completeKnowledgeStatusLoad(refreshError)
  } catch (caught: unknown) {
    failKnowledgeStatusLoad(error, refreshError, silent, message(caught))
  } finally { if (!silent) loading.value = false }
}

function choose(event: InstanceType<typeof globalThis.Event>) {
  selectedFile.value = (event.target as InstanceType<typeof globalThis.HTMLInputElement>).files?.[0] ?? null
}

async function submit() {
  if (!selectedFile.value || !projectId.value) return
  submitting.value = true
  error.value = ''
  notice.value = ''
  try {
    const created = await uploadKnowledgeFile(projectId.value, visibility.value, selectedFile.value)
    notice.value = `${created.originalName} 已安全保存，Worker 将开始解析和向量化。`
    selectedFile.value = null
    if (input.value) input.value.value = ''
    await load(true)
  } catch (caught: unknown) { error.value = message(caught) }
  finally { submitting.value = false }
}

async function retry(upload: KnowledgeUpload) {
  error.value = ''
  try { await retryKnowledgeUpload(upload.uploadId); await load(true) }
  catch (caught: unknown) { error.value = message(caught) }
}

async function remove(upload: KnowledgeUpload) {
  if (!globalThis.confirm(`确定删除 ${upload.originalName}？对应切片和向量也会删除。`)) return
  error.value = ''
  try { await deleteKnowledgeUpload(upload.uploadId); await load(true) }
  catch (caught: unknown) { error.value = message(caught) }
}

function status(upload: KnowledgeUpload): string {
  return ({ PENDING: '等待处理', PROCESSING: '解析中', SUCCEEDED: '可检索', FAILED: '处理失败', DELETING: '删除中' } as Record<string, string>)[upload.status] ?? upload.status
}

function statusClass(upload: KnowledgeUpload): string {
  if (upload.status === 'SUCCEEDED') return 'status-succeeded'
  if (upload.status === 'FAILED') return 'status-failed'
  return 'status-running'
}

function size(bytes: number): string {
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

function time(value: string | null): string {
  return value ? new Date(value).toLocaleString() : '—'
}

function message(caught: unknown): string {
  if (axios.isAxiosError<{ detail?: string; message?: string }>(caught)) {
    return caught.response?.data?.detail ?? caught.response?.data?.message ?? '操作失败，请稍后重试'
  }
  return '操作失败，请稍后重试'
}

onMounted(() => {
  void load()
  timer = globalThis.setInterval(() => {
    if (uploads.value.some(item => item.status === 'PENDING' || item.status === 'PROCESSING')) void load(true)
  }, 5000)
})
onBeforeUnmount(() => { if (timer) globalThis.clearInterval(timer) })
</script>

<template>
  <section>
    <div class="section-heading">
      <div><span class="eyebrow">P1.9-B · Workspace RAG</span><h2>上传知识文件</h2></div>
      <button class="secondary-button" :disabled="loading" @click="load()">刷新状态</button>
    </div>

    <div class="upload-summary">
      <article class="panel"><span>可见文件</span><strong>{{ uploads.length }}</strong></article>
      <article class="panel"><span>已使用容量</span><strong>{{ size(usedBytes) }}</strong></article>
      <article class="panel"><span>可检索</span><strong>{{ uploads.filter(item => item.status === 'SUCCEEDED').length }}</strong></article>
    </div>

    <form class="panel upload-form" @submit.prevent="submit">
      <div class="admin-form-heading">
        <strong>添加 PDF、Markdown 或 TXT</strong>
        <span>单文件最大 20 MB；PDF 最大 500 页。文件通过异步解析、切片和向量化后进入研究问答。</span>
      </div>
      <label>所属项目
        <select v-model="projectId" required>
          <option v-for="project in projects" :key="project.id" :value="project.id">{{ project.owner }}/{{ project.name }}</option>
        </select>
      </label>
      <label>可见范围
        <select v-model="visibility">
          <option value="PRIVATE">仅自己</option>
          <option value="WORKSPACE">Workspace 成员</option>
        </select>
      </label>
      <label class="upload-file">文件
        <input ref="input" type="file" accept=".pdf,.md,.markdown,.txt,application/pdf,text/markdown,text/plain" required @change="choose">
      </label>
      <button class="send-button" :disabled="submitting || !selectedFile || !projectId">{{ submitting ? '上传中…' : '上传并处理' }}</button>
    </form>

    <p v-if="error || refreshError" class="stream-error">{{ error || refreshError }}</p>
    <p v-if="notice" class="success-notice">{{ notice }}</p>
    <p v-if="loading" class="run-loading">正在读取上传文件…</p>

    <div v-else class="upload-grid">
      <article v-for="upload in uploads" :key="upload.uploadId" class="panel upload-card">
        <header><div><span class="eyebrow">{{ upload.projectName }}</span><h3>{{ upload.originalName }}</h3></div><i class="status-pill" :class="statusClass(upload)">{{ status(upload) }}</i></header>
        <dl>
          <div><dt>大小</dt><dd>{{ size(upload.byteSize) }}</dd></div>
          <div><dt>范围</dt><dd>{{ upload.visibility === 'PRIVATE' ? '仅自己' : 'Workspace' }}</dd></div>
          <div><dt>页数</dt><dd>{{ upload.pageCount || '—' }}</dd></div>
          <div><dt>上传者</dt><dd>{{ upload.uploaderName }}</dd></div>
          <div><dt>更新时间</dt><dd>{{ time(upload.updatedAt) }}</dd></div>
          <div><dt>心跳</dt><dd>{{ time(upload.heartbeatAt) }}</dd></div>
          <div><dt>租约到期</dt><dd>{{ time(upload.leaseExpiresAt) }}</dd></div>
        </dl>
        <p v-if="upload.currentItem" class="upload-progress">当前：{{ upload.currentItem }}</p>
        <p v-if="upload.errorMessage" class="stream-error">{{ upload.errorMessage }}</p>
        <footer>
          <small>SHA-256 · {{ upload.sha256.slice(0, 16) }}…</small>
          <div>
            <a v-if="upload.status === 'SUCCEEDED'" class="secondary-button" :href="knowledgeUploadDownloadUrl(upload.uploadId)">下载原文件</a>
            <button v-if="upload.status === 'FAILED'" class="secondary-button" @click="retry(upload)">重试</button>
            <button v-if="upload.uploadedBy === auth.account?.userId || auth.account?.systemRole === 'SYSTEM_ADMIN'" class="danger-button" :disabled="upload.status === 'PROCESSING'" @click="remove(upload)">删除</button>
          </div>
        </footer>
      </article>
      <article v-if="!uploads.length" class="empty-state"><div><h3>还没有知识文件</h3><p>上传团队方案、评估报告或官方 PDF，处理完成后即可在研究问答中检索并按页引用。</p></div></article>
    </div>
  </section>
</template>

<style scoped>
.upload-summary { display:grid; grid-template-columns:repeat(3,1fr); gap:16px; margin-bottom:18px; }
.upload-summary span { color:var(--muted); }
.upload-summary strong { display:block; margin-top:8px; font-size:28px; }
.upload-form { display:grid; grid-template-columns:1fr 1fr 2fr auto; gap:14px; align-items:end; margin-bottom:18px; }
.upload-form label { display:grid; gap:7px; color:#b9c5c7; font-size:12px; }
.upload-form select,.upload-form input { width:100%; padding:11px; color:#eef4f4; border:1px solid var(--line); border-radius:9px; background:#0d1315; }
.upload-form .send-button { height:42px; padding:0 16px; border:0; border-radius:9px; }
.upload-grid { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:16px; }
.upload-card header,.upload-card footer,.upload-card dl div { display:flex; justify-content:space-between; gap:12px; align-items:center; }
.upload-card h3 { margin:6px 0 0; overflow-wrap:anywhere; }
.upload-card dl { display:grid; gap:9px; margin:18px 0; }
.upload-card dt { color:var(--muted); }
.upload-card dd { margin:0; text-align:right; }
.upload-card footer { padding-top:14px; border-top:1px solid var(--line); }
.upload-card footer div { display:flex; gap:8px; }
.upload-card footer small { color:var(--muted); }
.upload-progress { padding:10px; border-radius:8px; color:#91dccc; background:rgba(103,212,180,.07); overflow-wrap:anywhere; }
.danger-button { color:#ffaaaa; border:1px solid rgba(255,120,120,.3); border-radius:9px; padding:9px 12px; background:rgba(145,42,42,.12); cursor:pointer; }
@media(max-width:960px){.upload-form{grid-template-columns:1fr 1fr}.upload-file{grid-column:1/-1}.upload-grid{grid-template-columns:1fr}}
@media(max-width:640px){.upload-summary,.upload-form{grid-template-columns:1fr}.upload-file{grid-column:auto}}
</style>
