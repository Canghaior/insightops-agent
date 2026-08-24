<script setup lang="ts">
import { Bell, ChatDotRound, DataAnalysis, Document, FolderOpened, Operation, Setting, User, UserFilled } from '@element-plus/icons-vue'
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
  { path: '/updates', label: '技术情报', icon: Bell, badge: true },
  { path: '/watch-rules', label: '关注规则', icon: Operation },
  { path: '/intelligence', label: '情报分析', icon: DataAnalysis },
  { path: '/digests', label: '情报摘要', icon: Operation, noticeBadge: true },
  { path: '/reports', label: '报告交付', icon: DataAnalysis },
  { path: '/chat', label: '研究问答', icon: ChatDotRound },
  { path: '/agent-workflows', label: '研究工作流', icon: Operation },
  { path: '/knowledge-files', label: '知识文件', icon: Document },
  { path: '/memory', label: '长期记忆', icon: User },
  { path: '/approvals', label: '操作审批', icon: Operation },
  { path: '/runs', label: '执行记录', icon: Operation },
  { path: '/workspace', label: 'Workspace', icon: FolderOpened },
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

async function changeWorkspace(event: { target: unknown }) {
  const workspaceId = (event.target as { value: string }).value
  if (!workspaceId || workspaceId === auth.account?.workspaceId) return
  await auth.switchWorkspace(workspaceId)
  await router.replace('/')
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
        <RouterLink to="/admin/agent-tools"><el-icon><Operation /></el-icon><span>Agent 工具</span></RouterLink>
        <RouterLink to="/admin/agent-cost"><el-icon><DataAnalysis /></el-icon><span>成本治理</span></RouterLink>
        <RouterLink to="/admin/agent-evaluations"><el-icon><DataAnalysis /></el-icon><span>Agent 评测</span></RouterLink>
        <RouterLink to="/admin/agent-workflows"><el-icon><Operation /></el-icon><span>工作流模板</span></RouterLink>
        <RouterLink v-if="isSystemAdmin" to="/admin/knowledge"><el-icon><FolderOpened /></el-icon><span>知识库采集</span></RouterLink>
        <RouterLink v-if="isSystemAdmin" to="/admin/quality"><el-icon><DataAnalysis /></el-icon><span>质量复核</span></RouterLink>
      </nav>
      <div class="scope-note account-card">
        <span class="eyebrow">当前账号</span>
        <strong>{{ auth.account?.displayName }}</strong>
        <p>{{ auth.account?.workspaceName }} · {{ auth.account?.role }}</p>
        <label v-if="auth.workspaces.length > 1" class="workspace-switcher">切换 Workspace
          <select :value="auth.account?.workspaceId" @change="changeWorkspace">
            <option v-for="workspace in auth.workspaces" :key="workspace.id" :value="workspace.id">{{ workspace.name }} · {{ workspace.role }}</option>
          </select>
        </label>
        <p>{{ auth.account?.systemRole === 'SYSTEM_ADMIN' ? '系统管理员' : '普通用户' }}</p>
        <button class="text-button" @click="signOut">退出登录</button>
      </div>
    </aside>
    <main class="content">
      <header class="topbar">
        <div><span class="eyebrow">AI 开源情报工作台</span><h1>让技术选型有证据、可追溯</h1></div>
        <div class="alpha-chip"><span></span> P3.1 团队版</div>
      </header>
      <RouterView />
    </main>
  </div>
</template>
