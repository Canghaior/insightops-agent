<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { listProjects, setProjectWatch, type ProjectWatch } from '@/api/projects'

const projects = ref<ProjectWatch[]>([])
const loading = ref(true)
const error = ref('')
async function load() {
  try { projects.value = await listProjects() } catch { error.value = '项目列表加载失败' } finally { loading.value = false }
}
async function toggle(project: ProjectWatch) {
  const previous = project.enabled; project.enabled = !previous
  try { Object.assign(project, await setProjectWatch(project.id, project.enabled)) }
  catch { project.enabled = previous; error.value = '关注状态保存失败' }
}
onMounted(load)
</script>

<template>
  <section>
    <div class="section-heading"><div><span class="eyebrow">个人关注范围</span><h2>首批开源项目</h2></div><span class="subtle">每个账号独立保存</span></div>
    <p v-if="loading">加载中…</p><p v-if="error" class="stream-error">{{ error }}</p>
    <div class="project-grid">
      <article v-for="project in projects" :key="project.id" class="project-card">
        <span class="project-dot" :style="{ background: project.enabled ? '#67d4b4' : '#596568' }"></span>
        <div><span class="eyebrow">P{{ project.priority }}</span><h3>{{ project.name }}</h3></div>
        <p>{{ project.owner }}/{{ project.name }}</p><code>{{ project.url }}</code>
        <div class="project-footer"><span>{{ project.enabled ? '● 已关注' : '○ 未关注' }}</span><button class="secondary-button" @click="toggle(project)">{{ project.enabled ? '取消关注' : '开始关注' }}</button></div>
      </article>
    </div>
  </section>
</template>
