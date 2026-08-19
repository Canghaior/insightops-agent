<script setup lang="ts">
import { Bell, ChatDotRound, DataAnalysis, FolderOpened, Operation, Setting, User, UserFilled } from '@element-plus/icons-vue'
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { useAuthStore } from '@/stores/auth'
import { getUnreadCount } from '@/api/updates'
import { getNotificationUnreadCount } from '@/api/intelligence'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const canManageAccounts = computed(() => auth.account?.systemRole === 'SYSTEM_ADMIN' || auth.account?.role === 'OWNER')
const isSystemAdmin = computed(() => auth.account?.systemRole === 'SYSTEM_ADMIN')
const unreadUpdates = ref(0)
const unreadNotifications = ref(0)
const navigation = [
  { path: '/', label: '概览', icon: DataAnalysis },
  { path: '/projects', label: '跟踪项目', icon: FolderOpened },
  { path: '/updates', label: '项目更新', icon: Bell, badge: true },
  { path: '/intelligence', label: '情报分析', icon: DataAnalysis },
  { path: '/digests', label: '情报摘要', icon: Operation, noticeBadge: true },
  { path: '/chat', label: '研究问答', icon: ChatDotRound },
  { path: '/memory', label: '长期记忆', icon: User },
  { path: '/runs', label: '执行记录', icon: Operation },
  { path: '/settings', label: '账号设置', icon: Setting },
]

async function loadUnread() {
  if (!auth.account) return
  try { unreadUpdates.value = await getUnreadCount() } catch { unreadUpdates.value = 0 }
}

async function loadNotificationUnread() {
  if (!auth.account) return
  try { unreadNotifications.value = await getNotificationUnreadCount() } catch { unreadNotifications.value = 0 }
}

async function loadBadges() { await Promise.all([loadUnread(), loadNotificationUnread()]) }

onMounted(() => {
  void loadBadges()
  globalThis.addEventListener('insightops:updates-changed', loadBadges)
  globalThis.addEventListener('insightops:notifications-changed', loadBadges)
})
onBeforeUnmount(() => {
  globalThis.removeEventListener('insightops:updates-changed', loadBadges)
  globalThis.removeEventListener('insightops:notifications-changed', loadBadges)
})
watch(() => route.fullPath, loadBadges)

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
          <el-icon><component :is="item.icon" /></el-icon><span>{{ item.label }}</span><b v-if="item.badge && unreadUpdates" class="nav-badge">{{ unreadUpdates > 99 ? '99+' : unreadUpdates }}</b><b v-if="item.noticeBadge && unreadNotifications" class="nav-badge">{{ unreadNotifications > 99 ? '99+' : unreadNotifications }}</b>
        </RouterLink>
      </nav>
      <nav v-if="canManageAccounts" class="navigation admin-navigation" aria-label="管理导航">
        <RouterLink to="/admin/users"><el-icon><UserFilled /></el-icon><span>用户管理</span></RouterLink>
        <RouterLink to="/admin/projects"><el-icon><FolderOpened /></el-icon><span>项目管理</span></RouterLink>
        <RouterLink v-if="isSystemAdmin" to="/admin/knowledge"><el-icon><FolderOpened /></el-icon><span>知识库采集</span></RouterLink>
      </nav>
      <div class="scope-note account-card">
        <span class="eyebrow">当前账号</span>
        <strong>{{ auth.account?.displayName }}</strong>
        <p>{{ auth.account?.workspaceName }} · {{ auth.account?.role }}</p>
        <p>{{ auth.account?.systemRole === 'SYSTEM_ADMIN' ? '系统管理员' : '普通用户' }}</p>
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
