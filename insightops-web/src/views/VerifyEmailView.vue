<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { verifyEmail } from '@/api/identity'
const state = ref('正在验证…')
onMounted(async () => { const token = new globalThis.URLSearchParams(globalThis.location.hash.slice(1)).get('token') ?? ''; try { await verifyEmail(token); state.value = '邮箱验证成功。' } catch { state.value = '验证链接无效、已过期或已使用。' } })
</script>
<template><main class="login-page"><section class="login-card"><span class="eyebrow">邮箱验证</span><h1>{{ state }}</h1><RouterLink to="/settings">返回账号设置</RouterLink></section></main></template>
