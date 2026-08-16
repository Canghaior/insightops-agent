import { defineStore } from 'pinia'
import { ref } from 'vue'

import * as authApi from '@/api/auth'
import type { Account } from '@/api/auth'

export const useAuthStore = defineStore('auth', () => {
  const account = ref<Account | null>(null)
  const initialized = ref(false)

  async function initialize() {
    if (initialized.value) return
    try { account.value = await authApi.me() } catch { account.value = null }
    initialized.value = true
  }

  async function signIn(username: string, password: string) {
    account.value = await authApi.login(username, password)
    initialized.value = true
  }

  async function signOut() {
    try { await authApi.logout() } finally { account.value = null; initialized.value = true }
  }

  async function changeOwnPassword(currentPassword: string, newPassword: string) {
    await authApi.changePassword(currentPassword, newPassword)
    account.value = null
    initialized.value = true
  }

  return { account, initialized, initialize, signIn, signOut, changeOwnPassword }
})
