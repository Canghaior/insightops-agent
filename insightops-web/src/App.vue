<script setup lang="ts">
import { Bell, ChatDotRound, DataAnalysis, Document, FolderOpened, Operation, UserFilled } from '@element-plus/icons-vue'
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
  { path: '/', label: '工作台', icon: DataAnalysis, routes: ['/'] },
  { path: '/projects', label: '我的项目', icon: FolderOpened, routes: ['/projects', '/knowledge-files'] },
  { path: '/updates', label: '更新与提醒', icon: Bell, routes: ['/updates', '/watch-rules', '/intelligence', '/digests'] },
  { path: '/upgrade-assessment', label: '研究', icon: ChatDotRound, routes: ['/upgrade-assessment', '/chat', '/runs', '/agent-workflows'] },
  { path: '/reports', label: '报告', icon: Document, routes: ['/reports'] },
]
const secondaryNavigation = [
  { path: '/knowledge-files', label: '项目资料', group: '/projects' },
  { path: '/intelligence', label: '影响分析', group: '/updates' },
  { path: '/digests', label: '摘要与通知', group: '/updates' },
  { path: '/watch-rules', label: '提醒规则', group: '/updates' },
  { path: '/upgrade-assessment', label: '升级评估', group: '/upgrade-assessment' },
  { path: '/chat', label: '研究问答', group: '/upgrade-assessment' },
  { path: '/runs', label: '研究记录', group: '/upgrade-assessment' },
  { path: '/agent-workflows', label: '更多研究模板', group: '/upgrade-assessment' },
]
const activeGroup = computed(() => navigation.find(item => item.routes.some(path =>
  path === '/' ? route.path === '/' : route.path === path || route.path.startsWith(`${path}/`))))
const secondaryItems = computed(() => secondaryNavigation.filter(item => item.group === activeGroup.value?.path))

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
      <div class="brand"><span class="brand-mark">IO</span><div><strong>InsightOps</strong><small>技术研究工作台</small></div></div>
      <nav class="navigation" aria-label="主导航">
        <RouterLink v-for="item in navigation" :key="item.path" :to="item.path" :title="item.label" :aria-label="item.label" :class="{ 'section-active': activeGroup?.path === item.path }">
          <el-icon><component :is="item.icon" /></el-icon><span>{{ item.label }}</span><b v-if="item.path === '/updates' && unreadUpdates + unreadNotifications" class="nav-badge" aria-label="有未读更新或通知">●</b>
        </RouterLink>
      </nav>
      <details class="navigation-group" :open="['/memory', '/approvals', '/workspace', '/settings', '/privacy'].includes(route.path)">
        <summary>个人与工作区</summary>
        <nav class="navigation" aria-label="个人与工作区">
          <RouterLink to="/memory">研究偏好与记忆</RouterLink>
          <RouterLink to="/approvals">待确认操作</RouterLink>
          <RouterLink to="/workspace">工作区成员</RouterLink>
          <RouterLink to="/settings">账号设置</RouterLink>
          <RouterLink to="/privacy">隐私与数据</RouterLink>
        </nav>
        <div class="scope-note account-card">
          <span class="eyebrow">当前账号</span>
          <strong>{{ auth.account?.displayName }}</strong>
          <p>{{ auth.account?.workspaceName }} · {{ auth.account?.role }}</p>
          <label v-if="auth.workspaces.length > 1" class="workspace-switcher">切换工作区
            <select :value="auth.account?.workspaceId" @change="changeWorkspace">
              <option v-for="workspace in auth.workspaces" :key="workspace.id" :value="workspace.id">{{ workspace.name }} · {{ workspace.role }}</option>
            </select>
          </label>
          <p>{{ auth.account?.systemRole === 'SYSTEM_ADMIN' ? '系统管理员' : '普通用户' }}</p>
          <button class="text-button" @click="signOut">退出登录</button>
        </div>
      </details>
      <details v-if="canManageAccounts" class="navigation-group" :open="route.path.startsWith('/admin/')">
        <summary>管理与诊断</summary>
        <nav class="navigation" aria-label="管理导航">
          <RouterLink to="/admin/users"><el-icon><UserFilled /></el-icon><span>用户管理</span></RouterLink>
          <RouterLink to="/admin/projects"><el-icon><FolderOpened /></el-icon><span>项目管理</span></RouterLink>
          <RouterLink to="/admin/agent-tools"><el-icon><Operation /></el-icon><span>Agent 工具</span></RouterLink>
          <RouterLink to="/admin/agent-cost"><el-icon><DataAnalysis /></el-icon><span>成本治理</span></RouterLink>
          <RouterLink to="/admin/agent-evaluations"><el-icon><DataAnalysis /></el-icon><span>Agent 评测</span></RouterLink>
          <RouterLink to="/admin/agent-workflows"><el-icon><Operation /></el-icon><span>工作流模板</span></RouterLink>
          <RouterLink v-if="isSystemAdmin" to="/admin/knowledge"><el-icon><FolderOpened /></el-icon><span>知识库采集</span></RouterLink>
          <RouterLink v-if="isSystemAdmin" to="/admin/quality"><el-icon><DataAnalysis /></el-icon><span>质量复核</span></RouterLink>
          <RouterLink v-if="isSystemAdmin" to="/admin/public-beta"><el-icon><Operation /></el-icon><span>公开 Beta</span></RouterLink>
        </nav>
      </details>
    </aside>
    <main class="content">
      <header class="topbar">
        <div><span class="eyebrow">AI 开源情报工作台</span><h1>让技术选型有证据、可追溯</h1></div>
        <RouterLink class="alpha-chip" to="/getting-started">使用指南与试用反馈</RouterLink>
      </header>
      <nav v-if="secondaryItems.length" class="section-navigation" :aria-label="`${activeGroup?.label}相关功能`">
        <RouterLink v-for="item in secondaryItems" :key="item.path" :to="item.path">{{ item.label }}<b v-if="item.path === '/digests' && unreadNotifications"> {{ unreadNotifications > 99 ? '99+' : unreadNotifications }}</b></RouterLink>
      </nav>
      <RouterView />
    </main>
  </div>
</template>

<style scoped>
.navigation a.section-active { background: var(--panel-soft); color: white; box-shadow: inset 3px 0 var(--accent); }
.navigation-group { margin-top: 18px; border-top: 1px solid var(--line); padding-top: 12px; }
.navigation-group summary { padding: 8px 4px; cursor: pointer; color: var(--muted); font-size: 13px; }
.section-navigation { display: flex; flex-wrap: wrap; gap: 8px; margin: -12px 0 24px; }
.section-navigation a { padding: 9px 14px; border: 1px solid var(--line); border-radius: 9px; color: var(--muted); }
.section-navigation a.router-link-active { color: var(--accent); background: var(--panel-soft); }
@media (max-width: 960px) {
  .app-shell { grid-template-columns: 190px minmax(0, 1fr); }
  .navigation span { display: inline; }
  .navigation a { justify-content: flex-start; padding: 10px 8px; }
  .navigation-group .navigation { display: grid; margin-left: 0; }
  .navigation-group .el-icon { display: none; }
  .alpha-chip { display: block; }
  .scope-note { display: block; margin-top: 16px; }
}
@media (max-width: 640px) {
  .app-shell { display: block; }
  .sidebar { flex-wrap: wrap; gap: 10px; align-items: flex-start; }
  .brand { display: none; }
  .sidebar > .navigation { width: 100%; justify-content: space-between; flex-wrap: wrap; margin-left: 0; }
  .sidebar > .navigation a { flex: 1; flex-direction: column; gap: 4px; font-size: 12px; white-space: nowrap; }
  .navigation-group { margin-top: 0; padding-top: 0; border: 0; flex: 1; min-width: 125px; }
  .scope-note { width: 100%; margin-top: 0; }
  .topbar { flex-wrap: wrap; gap: 12px; }
}
</style>
