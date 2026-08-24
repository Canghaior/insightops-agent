import { defineStore } from 'pinia'
import { ref } from 'vue'

import * as authApi from '@/api/auth'
import type { Account } from '@/api/auth'
import * as workspaceApi from '@/api/workspaces'
import type { Workspace } from '@/api/workspaces'

export const useAuthStore = defineStore('auth', () => {
  const account = ref<Account | null>(null)
  const workspaces = ref<Workspace[]>([])
  const initialized = ref(false)

  async function initialize() {
    if (initialized.value) return
    try {
      account.value = await authApi.me()
      if (!account.value.mustChangePassword) await refreshWorkspaces()
    } catch { account.value = null; workspaces.value = [] }
    initialized.value = true
  }

  async function signIn(username: string, password: string, mfaCode?: string) {
    account.value = await authApi.login(username, password, mfaCode)
    if (!account.value.mustChangePassword) await refreshWorkspaces()
    initialized.value = true
  }

  async function signOut() {
    try { await authApi.logout() } finally { clear() }
  }

  async function changeOwnPassword(currentPassword: string, newPassword: string) {
    await authApi.changePassword(currentPassword, newPassword)
    clear()
  }

  async function refreshWorkspaces() {
    if (!account.value) { workspaces.value = []; return }
    workspaces.value = await workspaceApi.listWorkspaces()
  }

  async function switchWorkspace(workspaceId: string) {
    await workspaceApi.switchWorkspace(workspaceId)
    account.value = await authApi.me()
    await refreshWorkspaces()
  }

  function clear() {
    account.value = null; workspaces.value = []; initialized.value = true
  }

  return { account, workspaces, initialized, initialize, signIn, signOut,
    changeOwnPassword, refreshWorkspaces, switchWorkspace, clear }
})
