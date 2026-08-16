<script setup lang="ts">
import { ChatDotRound, DataAnalysis, FolderOpened, Operation, User } from '@element-plus/icons-vue'
import { useRoute, useRouter } from 'vue-router'

import { useAuthStore } from '@/stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const navigation = [
  { path: '/', label: '概览', icon: DataAnalysis },
  { path: '/projects', label: '跟踪项目', icon: FolderOpened },
  { path: '/chat', label: '研究问答', icon: ChatDotRound },
  { path: '/memory', label: '长期记忆', icon: User },
  { path: '/runs', label: '执行记录', icon: Operation },
]

async function signOut() {
  await auth.signOut()
  await router.push('/login')
}
</script>

<template>
  <RouterView v-if="route.meta.public" />
  <div v-else class="app-shell">
    <aside class="sidebar">
      <div class="brand"><span class="brand-mark">IO</span><div><strong>InsightOps</strong><small>Agent · P1</small></div></div>
      <nav class="navigation" aria-label="主导航">
        <RouterLink v-for="item in navigation" :key="item.path" :to="item.path">
          <el-icon><component :is="item.icon" /></el-icon><span>{{ item.label }}</span>
        </RouterLink>
      </nav>
      <div class="scope-note account-card">
        <span class="eyebrow">当前账号</span>
        <strong>{{ auth.account?.displayName }}</strong>
        <p>{{ auth.account?.workspaceName }} · {{ auth.account?.role }}</p>
        <button class="text-button" @click="signOut">退出登录</button>
      </div>
    </aside>
    <main class="content">
      <header class="topbar">
        <div><span class="eyebrow">AI 开源情报工作台</span><h1>让技术选型有证据、可追溯</h1></div>
        <div class="alpha-chip"><span></span> P1 开发版</div>
      </header>
      <RouterView />
    </main>
  </div>
</template>
