<script setup lang="ts">
import { ChatDotRound, DataAnalysis, FolderOpened, Operation } from '@element-plus/icons-vue'

const navigation = [
  { path: '/', label: '概览', icon: DataAnalysis },
  { path: '/projects', label: '跟踪项目', icon: FolderOpened },
  { path: '/chat', label: '研究问答', icon: ChatDotRound },
  { path: '/runs', label: '执行记录', icon: Operation },
]
</script>

<template>
  <div class="app-shell">
    <aside class="sidebar">
      <div class="brand">
        <span class="brand-mark">IO</span>
        <div>
          <strong>InsightOps</strong>
          <small>Agent · Alpha</small>
        </div>
      </div>

      <nav class="navigation" aria-label="主导航">
        <RouterLink v-for="item in navigation" :key="item.path" :to="item.path">
          <el-icon><component :is="item.icon" /></el-icon>
          <span>{{ item.label }}</span>
        </RouterLink>
      </nav>

      <div class="scope-note">
        <span class="eyebrow">P0 范围</span>
        <strong>3 个开源项目</strong>
        <p>Spring AI · LangChain4j · Dify</p>
      </div>
    </aside>

    <main class="content">
      <header class="topbar">
        <div>
          <span class="eyebrow">AI 开源情报工作台</span>
          <h1>让技术选型有证据、可追溯</h1>
        </div>
        <div class="alpha-chip"><span></span> Alpha 内测</div>
      </header>
      <RouterView />
    </main>
  </div>
</template>
