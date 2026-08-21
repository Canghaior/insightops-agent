import { createRouter, createWebHistory } from 'vue-router'

import { useAuthStore } from '@/stores/auth'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    { path: '/login', name: 'login', component: () => import('@/views/LoginView.vue'), meta: { public: true } },
    { path: '/', name: 'dashboard', component: () => import('@/views/DashboardView.vue') },
    { path: '/projects', name: 'projects', component: () => import('@/views/ProjectsView.vue') },
    { path: '/updates', name: 'updates', component: () => import('@/views/UpdatesView.vue') },
    { path: '/watch-rules', name: 'watch-rules', component: () => import('@/views/WatchRulesView.vue') },
    { path: '/intelligence', name: 'intelligence', component: () => import('@/views/IntelligenceView.vue') },
    { path: '/intelligence/:analysisId', name: 'intelligence-detail', component: () => import('@/views/IntelligenceView.vue') },
    { path: '/digests', name: 'digests', component: () => import('@/views/DigestsView.vue') },
    { path: '/reports', name: 'reports', component: () => import('@/views/ReportsView.vue') },
    { path: '/chat', name: 'chat', component: () => import('@/views/ChatView.vue') },
    { path: '/knowledge-files', name: 'knowledge-files', component: () => import('@/views/KnowledgeFilesView.vue') },
    { path: '/memory', name: 'memory', component: () => import('@/views/MemoryView.vue') },
    { path: '/approvals', name: 'approvals', component: () => import('@/views/ApprovalsView.vue') },
    { path: '/runs', name: 'runs', component: () => import('@/views/RunsView.vue') },
    { path: '/runs/:runId', name: 'run-detail', component: () => import('@/views/RunsView.vue') },
    { path: '/settings', name: 'settings', component: () => import('@/views/SettingsView.vue') },
    {
      path: '/admin/users',
      name: 'admin-users',
      component: () => import('@/views/AdminUsersView.vue'),
      meta: { manager: true },
    },
    {
      path: '/admin/projects',
      name: 'admin-projects',
      component: () => import('@/views/AdminProjectsView.vue'),
      meta: { manager: true },
    },
    {
      path: '/admin/knowledge',
      name: 'admin-knowledge',
      component: () => import('@/views/AdminKnowledgeView.vue'),
      meta: { systemAdmin: true },
    },
    {
      path: '/admin/quality',
      name: 'admin-quality',
      component: () => import('@/views/AdminQualityReviewView.vue'),
      meta: { systemAdmin: true },
    },
    {
      path: '/admin/agent-tools',
      name: 'admin-agent-tools',
      component: () => import('@/views/AdminAgentToolsView.vue'),
      meta: { manager: true },
    },
    {
      path: '/admin/agent-cost',
      name: 'admin-agent-cost',
      component: () => import('@/views/AdminAgentCostView.vue'),
      meta: { manager: true },
    },
  ],
})

router.beforeEach(async (to) => {
  const auth = useAuthStore()
  await auth.initialize()
  if (!to.meta.public && !auth.account) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  if (auth.account?.mustChangePassword && to.name !== 'settings') {
    return { name: 'settings', query: { required: '1' } }
  }
  if (to.meta.manager && auth.account
      && auth.account.systemRole !== 'SYSTEM_ADMIN' && auth.account.role !== 'OWNER') {
    return { name: 'dashboard' }
  }
  if (to.meta.systemAdmin && auth.account?.systemRole !== 'SYSTEM_ADMIN') {
    return { name: 'dashboard' }
  }
  if (to.name === 'login' && auth.account) return { name: 'dashboard' }
})

export default router
