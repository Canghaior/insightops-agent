<script setup lang="ts">
import axios from 'axios'
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { useAuthStore } from '@/stores/auth'

const username = ref('alpha-owner')
const password = ref('')
const mfaCode = ref('')
const mfaRequired = ref(false)
const loading = ref(false)
const error = ref('')
const auth = useAuthStore()
const route = useRoute()
const router = useRouter()

async function submit() {
  if (!username.value.trim() || !password.value) return
  loading.value = true; error.value = ''
  try {
    await auth.signIn(username.value.trim(), password.value, mfaCode.value || undefined)
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/'
    await router.replace(redirect)
  } catch (caught) {
    if (axios.isAxiosError(caught) && caught.response?.status === 428) {
      mfaRequired.value = true; error.value = '请输入验证器代码或恢复码'
    } else error.value = '用户名、密码或 MFA 验证码不正确'
  } finally { loading.value = false }
}
</script>

<template>
  <main class="login-page">
    <section class="login-card">
      <div class="brand"><span class="brand-mark">IO</span><div><strong>InsightOps</strong><small>Agent · P1</small></div></div>
      <span class="eyebrow">个人工作区登录</span>
      <h1>欢迎回来</h1>
      <p>登录后，会话、Workspace、执行记录、长期记忆和项目关注都会按租户隔离。</p>
      <p v-if="route.query.changed === '1'" class="success-notice">密码已修改，请使用新密码重新登录。</p>
      <p v-if="route.query.deletion === '1'" class="success-notice">账号删除申请已提交；宽限期内重新登录可联系管理员或取消申请。</p>
      <form @submit.prevent="submit">
        <label>用户名<input v-model="username" autocomplete="username" maxlength="64" /></label>
        <label>密码<input v-model="password" type="password" autocomplete="current-password" maxlength="72" /></label>
        <label v-if="mfaRequired">MFA 验证码或恢复码<input v-model="mfaCode" autocomplete="one-time-code" maxlength="32" /></label>
        <p v-if="error" class="stream-error">{{ error }}</p>
        <button class="send-button" :disabled="loading">{{ loading ? '登录中…' : '登录' }}</button>
      </form>
      <RouterLink to="/forgot-password">忘记密码？</RouterLink>
      <p class="registration-note">当前为封闭邀请制，不开放自主注册。账号由管理员创建。</p>
    </section>
  </main>
</template>
