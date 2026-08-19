<script setup lang="ts">
import axios from 'axios'
import { computed, onMounted, reactive, ref } from 'vue'

import {
  createManagedProject,
  deleteManagedProject,
  listManagedProjects,
  setManagedProjectEnabled,
  updateManagedProject,
  type ManagedProject,
} from '@/api/admin'

const projects = ref<ManagedProject[]>([])
const loading = ref(false)
const saving = ref(false)
const editingId = ref<string | null>(null)
const error = ref('')
const notice = ref('')
const form = reactive({ repositoryOwner: '', repositoryName: '', priority: 3 })

const editingProject = computed(() => projects.value.find(project => project.projectId === editingId.value) ?? null)
const coordinatesLocked = computed(() => Boolean(
  editingProject.value
  && (editingProject.value.releaseCount > 0 || editingProject.value.knowledgeSourceCount > 0),
))

async function load() {
  loading.value = true
  error.value = ''
  try { projects.value = await listManagedProjects() }
  catch (caught: unknown) { error.value = message(caught) }
  finally { loading.value = false }
}

async function save() {
  saving.value = true
  error.value = ''
  notice.value = ''
  try {
    const input = {
      repositoryOwner: form.repositoryOwner.trim(),
      repositoryName: form.repositoryName.trim(),
      priority: form.priority,
    }
    if (editingId.value) {
      await updateManagedProject(editingId.value, input)
      notice.value = `${input.repositoryOwner}/${input.repositoryName} 已更新。`
    } else {
      await createManagedProject(input)
      notice.value = `${input.repositoryOwner}/${input.repositoryName} 已加入采集队列。`
    }
    cancelEdit()
    await load()
  } catch (caught: unknown) { error.value = message(caught) }
  finally { saving.value = false }
}

function edit(project: ManagedProject) {
  editingId.value = project.projectId
  form.repositoryOwner = project.repositoryOwner
  form.repositoryName = project.repositoryName
  form.priority = project.priority
  error.value = ''
  notice.value = ''
}

function cancelEdit() {
  editingId.value = null
  form.repositoryOwner = ''
  form.repositoryName = ''
  form.priority = 3
}

async function toggle(project: ManagedProject) {
  error.value = ''
  notice.value = ''
  try {
    await setManagedProjectEnabled(project.projectId, !project.enabled)
    notice.value = `${project.repositoryOwner}/${project.repositoryName} 已${project.enabled ? '停用' : '启用'}。`
    await load()
  } catch (caught: unknown) { error.value = message(caught) }
}

async function remove(project: ManagedProject) {
  const repository = `${project.repositoryOwner}/${project.repositoryName}`
  if (!globalThis.confirm(`确定删除 ${repository}？只有尚未产生数据和关联任务的项目可以删除。`)) return
  error.value = ''
  notice.value = ''
  try {
    await deleteManagedProject(project.projectId)
    if (editingId.value === project.projectId) cancelEdit()
    notice.value = `${repository} 已删除。`
    await load()
  } catch (caught: unknown) { error.value = message(caught) }
}

function message(caught: unknown): string {
  if (axios.isAxiosError<{ detail?: string; message?: string }>(caught)) {
    return caught.response?.data?.detail ?? caught.response?.data?.message ?? '操作失败，请稍后重试'
  }
  return '操作失败，请稍后重试'
}

function time(value: string | null): string {
  return value ? new Date(value).toLocaleString() : '尚未执行'
}

function statusClass(status: string): string {
  if (status === 'SUCCEEDED') return 'status-succeeded'
  if (status === 'FAILED') return 'status-failed'
  if (status === 'RUNNING' || status === 'RETRY_WAIT') return 'status-running'
  return ''
}

onMounted(load)
</script>

<template>
  <section>
    <div class="section-heading">
      <div><span class="eyebrow">P1.5-A · 配置化项目</span><h2>GitHub 项目管理</h2></div>
      <button class="secondary-button" :disabled="loading" @click="load">刷新列表</button>
    </div>

    <form class="panel project-admin-form" @submit.prevent="save">
      <div class="admin-form-heading">
        <strong>{{ editingId ? '编辑项目' : '添加 GitHub 仓库' }}</strong>
        <span>启用后 Worker 会自动增量采集 Release；优先级 1 最高、5 最低。</span>
      </div>
      <label>
        仓库所有者
        <input v-model="form.repositoryOwner" :disabled="coordinatesLocked" maxlength="39" pattern="[A-Za-z0-9-]{1,39}" placeholder="spring-projects" required>
      </label>
      <label>
        仓库名称
        <input v-model="form.repositoryName" :disabled="coordinatesLocked" maxlength="100" pattern="[A-Za-z0-9._-]{1,100}" placeholder="spring-ai" required>
      </label>
      <label>
        优先级
        <select v-model.number="form.priority"><option v-for="priority in 5" :key="priority" :value="priority">{{ priority }}</option></select>
      </label>
      <div class="project-form-actions">
        <button v-if="editingId" type="button" class="secondary-button" @click="cancelEdit">取消</button>
        <button class="send-button" :disabled="saving">{{ saving ? '保存中…' : editingId ? '保存修改' : '添加项目' }}</button>
      </div>
      <p v-if="coordinatesLocked" class="project-form-note">此项目已有采集数据，仓库坐标已锁定；仍可修改优先级或停用项目。</p>
    </form>

    <p v-if="error" class="stream-error">{{ error }}</p>
    <p v-if="notice" class="success-notice">{{ notice }}</p>
    <p v-if="loading" class="run-loading">正在读取项目列表…</p>

    <div v-else class="project-admin-grid">
      <article v-for="project in projects" :key="project.projectId" class="panel project-admin-card">
        <header>
          <div>
            <span class="eyebrow">优先级 {{ project.priority }}</span>
            <h3>{{ project.repositoryOwner }}/{{ project.repositoryName }}</h3>
          </div>
          <div class="project-statuses">
            <i class="status-pill" :class="project.enabled ? 'status-succeeded' : 'status-cancelled'">{{ project.enabled ? '已启用' : '已停用' }}</i>
            <i class="status-pill" :class="statusClass(project.lastSyncStatus)">{{ project.lastSyncStatus }}</i>
          </div>
        </header>
        <a :href="project.canonicalUrl" target="_blank" rel="noopener noreferrer">{{ project.canonicalUrl }}</a>
        <dl>
          <div><dt>Release 快照</dt><dd>{{ project.releaseCount }}</dd></div>
          <div><dt>知识源</dt><dd>{{ project.knowledgeSourceCount }}</dd></div>
          <div><dt>关注人数</dt><dd>{{ project.watcherCount }}</dd></div>
          <div><dt>活动任务</dt><dd>{{ project.activeJobCount }}</dd></div>
          <div><dt>上次采集</dt><dd>{{ time(project.lastSyncAt) }}</dd></div>
          <div><dt>下次计划</dt><dd>{{ project.enabled ? time(project.nextSyncAt) : '已停用' }}</dd></div>
        </dl>
        <p v-if="project.lastSyncError" class="stream-error">{{ project.lastSyncError }}</p>
        <footer>
          <button class="secondary-button" @click="edit(project)">编辑</button>
          <button class="secondary-button" @click="toggle(project)">{{ project.enabled ? '停用' : '启用' }}</button>
          <button class="danger-button" :disabled="project.releaseCount + project.knowledgeSourceCount + project.watcherCount + project.activeJobCount > 0" @click="remove(project)">删除</button>
        </footer>
      </article>
      <p v-if="!projects.length" class="panel empty-state">尚未配置项目。请添加一个 GitHub 仓库。</p>
    </div>
  </section>
</template>
