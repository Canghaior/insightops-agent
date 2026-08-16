<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { useAuthStore } from '@/stores/auth'

const username = ref('alpha-owner')
const password = ref('')
const loading = ref(false)
const error = ref('')
const auth = useAuthStore()
const route = useRoute()
const router = useRouter()

async function submit() {
  if (!username.value.trim() || !password.value) return
  loading.value = true; error.value = ''
  try {
    await auth.signIn(username.value.trim(), password.value)
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/'
    await router.replace(redirect)
  } catch { error.value = '用户名或密码不正确' } finally { loading.value = false }
}
</script>

<template>
  <main class="login-page">
    <section class="login-card">
      <div class="brand"><span class="brand-mark">IO</span><div><strong>InsightOps</strong><small>Agent · P1</small></div></div>
      <span class="eyebrow">个人工作区登录</span>
      <h1>欢迎回来</h1>
      <p>登录后，会话、执行记录、长期记忆和项目关注都会按账号隔离。</p>
      <form @submit.prevent="submit">
        <label>用户名<input v-model="username" autocomplete="username" maxlength="64" /></label>
        <label>密码<input v-model="password" type="password" autocomplete="current-password" maxlength="72" /></label>
        <p v-if="error" class="stream-error">{{ error }}</p>
        <button class="send-button" :disabled="loading">{{ loading ? '登录中…' : '登录' }}</button>
      </form>
    </section>
  </main>
</template>
