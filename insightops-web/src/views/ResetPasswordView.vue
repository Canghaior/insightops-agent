<script setup lang="ts">
import { computed, ref } from 'vue'
import { resetPassword } from '@/api/identity'
const token = new globalThis.URLSearchParams(globalThis.location.hash.slice(1)).get('token') ?? ''
const password = ref(''); const confirmPassword = ref(''); const done = ref(false); const error = ref('')
const valid = computed(() => token.length >= 32 && password.value === confirmPassword.value && password.value.length >= 10)
async function submit() { error.value = ''; if (!valid.value) { error.value = '请确认两次密码一致且符合规则'; return } try { await resetPassword(token, password.value); done.value = true } catch { error.value = '链接无效、已过期或已使用' } }
</script>
<template><main class="login-page"><section class="login-card"><span class="eyebrow">一次性链接</span><h1>重置密码</h1><form v-if="!done" @submit.prevent="submit"><label>新密码<input v-model="password" type="password" minlength="10" maxlength="72" required /></label><label>确认密码<input v-model="confirmPassword" type="password" minlength="10" maxlength="72" required /></label><p v-if="error" class="stream-error">{{ error }}</p><button class="send-button">重置密码</button></form><p v-else class="success-notice">密码已重置，全部旧会话已撤销。</p><RouterLink to="/login">前往登录</RouterLink></section></main></template>
