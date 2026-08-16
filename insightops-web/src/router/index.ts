import { createRouter, createWebHistory } from 'vue-router'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    { path: '/', name: 'dashboard', component: () => import('@/views/DashboardView.vue') },
    { path: '/projects', name: 'projects', component: () => import('@/views/ProjectsView.vue') },
    { path: '/chat', name: 'chat', component: () => import('@/views/ChatView.vue') },
    { path: '/runs', name: 'runs', component: () => import('@/views/RunsView.vue') },
    { path: '/runs/:runId', name: 'run-detail', component: () => import('@/views/RunsView.vue') },
  ],
})

export default router
