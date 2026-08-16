<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'

import { listProjects, type ProjectWatch } from '@/api/projects'
import {
  listUpdates,
  markAllUpdatesRead,
  markUpdateRead,
  type ProjectUpdate,
  type UpdatePage,
} from '@/api/updates'

const router = useRouter()
const projects = ref<ProjectWatch[]>([])
const page = ref<UpdatePage>({ items: [], page: 0, size: 20, total: 0, unreadCount: 0 })
const projectId = ref('')
const unreadOnly = ref(false)
const loading = ref(false)
const error = ref('')
const watchedProjects = computed(() => projects.value.filter((project) => project.enabled))

async function load(targetPage = 0) {
  loading.value = true; error.value = ''
  try {
    page.value = await listUpdates({
      page: targetPage, size: 20, projectId: projectId.value || undefined, unreadOnly: unreadOnly.value,
    })
  } catch { error.value = '项目更新加载失败，请稍后重试。' } finally { loading.value = false }
}

async function read(update: ProjectUpdate) {
  if (update.read) return
  await markUpdateRead(update.eventId)
  update.read = true
  page.value.unreadCount = Math.max(0, page.value.unreadCount - 1)
  globalThis.dispatchEvent(new globalThis.Event('insightops:updates-changed'))
}

async function readAll() {
  await markAllUpdatesRead()
  await load(page.value.page)
  globalThis.dispatchEvent(new globalThis.Event('insightops:updates-changed'))
}

async function research(update: ProjectUpdate) {
  await read(update)
  const question = `请分析 ${update.projectName} ${update.versionTag} 的主要变化、升级价值、风险和 Java 项目的下一步行动，并引用官方 Release 证据。`
  await router.push({ name: 'chat', query: { question } })
}

function formatDate(value: string) {
  return new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value))
}

onMounted(async () => {
  try {
    projects.value = await listProjects()
    await load()
  } catch {
    error.value = '项目更新加载失败，请稍后重试。'
  }
})
</script>

<template>
  <section>
    <div class="section-heading updates-heading">
      <div><span class="eyebrow">持续跟踪</span><h2>项目更新中心</h2></div>
      <div class="update-summary"><strong>{{ page.unreadCount }}</strong><span>条未读更新</span></div>
    </div>
    <div class="panel update-controls">
      <label>关注项目
        <select v-model="projectId" @change="load(0)">
          <option value="">全部项目</option>
          <option v-for="project in watchedProjects" :key="project.id" :value="project.id">{{ project.name }}</option>
        </select>
      </label>
      <label class="check-label"><input v-model="unreadOnly" type="checkbox" @change="load(0)" /> 只看未读</label>
      <button class="secondary-button" :disabled="page.unreadCount === 0" @click="readAll">全部标为已读</button>
    </div>
    <p v-if="error" class="stream-error">{{ error }}</p>
    <p v-if="loading" class="run-loading">正在加载更新…</p>
    <div v-else class="update-list">
      <article v-for="update in page.items" :key="update.eventId" class="update-card" :class="{ unread: !update.read }" @click="read(update)">
        <header>
          <div><span class="eyebrow">{{ update.repositoryOwner }}/{{ update.projectName }}</span><h3>{{ update.title }}</h3></div>
          <div class="update-badges"><i v-if="!update.read">未读</i><code>{{ update.versionTag }}</code></div>
        </header>
        <p>{{ update.summary }}</p>
        <footer>
          <time>{{ formatDate(update.occurredAt) }}</time>
          <div>
            <a :href="update.sourceUrl" target="_blank" rel="noreferrer" @click.stop="read(update)">官方 Release</a>
            <button class="send-button" @click.stop="research(update)">基于本次更新研究</button>
          </div>
        </footer>
      </article>
      <div v-if="!page.items.length" class="conversation-empty"><strong>暂时没有匹配的更新</strong><p>请先关注项目；Worker 完成首次采集后，Release 会显示在这里。</p></div>
    </div>
    <div v-if="page.total > page.size" class="run-pagination">
      <span>第 {{ page.page + 1 }} 页 · 共 {{ page.total }} 条</span>
      <div><button :disabled="page.page === 0" @click="load(page.page - 1)">上一页</button><button :disabled="(page.page + 1) * page.size >= page.total" @click="load(page.page + 1)">下一页</button></div>
    </div>
  </section>
</template>
