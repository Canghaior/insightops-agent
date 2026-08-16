<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import axios from 'axios'
import { useRoute, useRouter } from 'vue-router'

import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()
const form = reactive({ currentPassword: '', newPassword: '', confirmPassword: '' })
const saving = ref(false)
const error = ref('')
const forced = computed(() => route.query.required === '1' || auth.account?.mustChangePassword)

async function submit() {
  error.value = ''
  if (form.newPassword !== form.confirmPassword) {
    error.value = '两次输入的新密码不一致'
    return
  }
  saving.value = true
  try {
    await auth.changeOwnPassword(form.currentPassword, form.newPassword)
    await router.replace({ name: 'login', query: { changed: '1' } })
  } catch (caught: unknown) {
    if (axios.isAxiosError<{ detail?: string; message?: string }>(caught)) {
      error.value = caught.response?.data?.detail ?? caught.response?.data?.message ?? '密码修改失败，请检查当前密码和新密码规则'
    } else {
      error.value = '密码修改失败，请检查当前密码和新密码规则'
    }
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <section class="settings-page">
    <div class="section-heading">
      <div><span class="eyebrow">账号安全</span><h2>修改登录密码</h2></div>
      <span class="subtle">{{ auth.account?.username }} · {{ auth.account?.displayName }}</span>
    </div>
    <div v-if="forced" class="guardrail-notice">
      <strong>首次登录必须修改临时密码</strong>
      <p>完成修改后会退出登录，请使用新密码重新登录。</p>
    </div>
    <form class="panel settings-form" @submit.prevent="submit">
      <label>当前密码<input v-model="form.currentPassword" type="password" autocomplete="current-password" maxlength="72" required /></label>
      <label>新密码<input v-model="form.newPassword" type="password" autocomplete="new-password" minlength="10" maxlength="72" required /></label>
      <label>确认新密码<input v-model="form.confirmPassword" type="password" autocomplete="new-password" minlength="10" maxlength="72" required /></label>
      <p class="subtle">密码需为 10–72 位，并同时包含大写字母、小写字母和数字。</p>
      <p v-if="error" class="stream-error">{{ error }}</p>
      <button class="send-button" :disabled="saving">{{ saving ? '正在保存…' : '修改密码并重新登录' }}</button>
    </form>
  </section>
</template>
