<script setup lang="ts">
import { ref } from 'vue'
import { forgotPassword } from '@/api/identity'
const email = ref(''); const sent = ref(false); const loading = ref(false)
async function submit() { loading.value = true; try { await forgotPassword(email.value); sent.value = true } finally { loading.value = false } }
</script>
<template><main class="login-page"><section class="login-card"><span class="eyebrow">账号恢复</span><h1>找回密码</h1><p>如果邮箱对应有效账号，系统会发送一次性重置链接。无论账号是否存在，页面都显示相同结果。</p><form v-if="!sent" @submit.prevent="submit"><label>已验证邮箱<input v-model="email" type="email" maxlength="320" required /></label><button class="send-button" :disabled="loading">{{ loading ? '提交中…' : '发送重置链接' }}</button></form><p v-else class="success-notice">请求已受理，请检查邮箱。</p><RouterLink to="/login">返回登录</RouterLink></section></main></template>
