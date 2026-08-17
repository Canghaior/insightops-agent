import { createRouter, createWebHistory } from 'vue-router'

import { useAuthStore } from '@/stores/auth'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    { path: '/login', name: 'login', component: () => import('@/views/LoginView.vue'), meta: { public: true } },
    { path: '/', name: 'dashboard', component: () => import('@/views/DashboardView.vue') },
    { path: '/projects', name: 'projects', component: () => import('@/views/ProjectsView.vue') },
    { path: '/updates', name: 'updates', component: () => import('@/views/UpdatesView.vue') },
    { path: '/intelligence', name: 'intelligence', component: () => import('@/views/IntelligenceView.vue') },
    { path: '/intelligence/:analysisId', name: 'intelligence-detail', component: () => import('@/views/IntelligenceView.vue') },
    { path: '/digests', name: 'digests', component: () => import('@/views/DigestsView.vue') },
    { path: '/chat', name: 'chat', component: () => import('@/views/ChatView.vue') },
    { path: '/memory', name: 'memory', component: () => import('@/views/MemoryView.vue') },
    { path: '/runs', name: 'runs', component: () => import('@/views/RunsView.vue') },
    { path: '/runs/:runId', name: 'run-detail', component: () => import('@/views/RunsView.vue') },
    { path: '/settings', name: 'settings', component: () => import('@/views/SettingsView.vue') },
    {
      path: '/admin/users',
      name: 'admin-users',
      component: () => import('@/views/AdminUsersView.vue'),
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
  if (to.name === 'login' && auth.account) return { name: 'dashboard' }
})

export default router
