<script setup lang="ts">
const projects = [
  { name: 'Spring AI', repo: 'spring-projects/spring-ai', priority: 'P1', focus: 'Java AI 应用框架', color: '#67d4b4' },
  { name: 'LangChain4j', repo: 'langchain4j/langchain4j', priority: 'P2', focus: 'Java LLM 集成', color: '#efb96b' },
  { name: 'Dify', repo: 'langgenius/dify', priority: 'P3', focus: 'LLM 应用平台', color: '#899cff' },
]
</script>

<template>
  <section>
    <div class="section-heading">
      <div><span class="eyebrow">跟踪边界</span><h2>首批开源项目</h2></div>
      <span class="subtle">P0 仅按需查询 GitHub Releases</span>
    </div>
    <div class="project-grid">
      <article v-for="project in projects" :key="project.repo" class="project-card">
        <span class="project-dot" :style="{ background: project.color }"></span>
        <div><span class="eyebrow">{{ project.priority }}</span><h3>{{ project.name }}</h3></div>
        <p>{{ project.focus }}</p>
        <code>{{ project.repo }}</code>
        <div class="project-footer"><span>● 已纳入 P0</span><span>按需查询</span></div>
      </article>
    </div>
  </section>
</template>
