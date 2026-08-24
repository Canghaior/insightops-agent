<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import * as workspaces from '@/api/workspaces'
import type { InvitationPreview } from '@/api/workspaces'
import { useAuthStore } from '@/stores/auth'
const token = new globalThis.URLSearchParams(globalThis.location.hash.slice(1)).get('token') ?? ''
const loginTarget = `/login?redirect=${globalThis.encodeURIComponent(`/invitation${globalThis.location.hash}`)}`
const auth = useAuthStore(); const router = useRouter(); const preview = ref<InvitationPreview | null>(null); const error = ref(''); const done = ref(false)
const form = reactive({ username: '', displayName: '', password: '' })
onMounted(async () => { try { preview.value = await workspaces.invitationPreview(token) } catch { error.value = '邀请链接无效、已撤销或已过期。' } })
async function accept() { error.value = ''; try { if (preview.value?.existingUser) { const id = await workspaces.acceptExistingInvitation(token); await auth.refreshWorkspaces(); await auth.switchWorkspace(id); await router.push('/') } else { await workspaces.acceptNewInvitation({ token, ...form }); done.value = true } } catch { error.value = preview.value?.existingUser ? '请先登录邀请邮箱对应的已验证账号。' : '账号创建失败，请更换用户名或检查密码规则。' } }
</script>
<template><main class="login-page"><section class="login-card"><span class="eyebrow">Workspace 邀请</span><h1>{{ preview?.workspaceName || '加载邀请…' }}</h1><p v-if="preview">邀请邮箱 {{ preview.maskedEmail }} · 权限 {{ preview.role }} · {{ new Date(preview.expiresAt).toLocaleString() }} 到期</p><p v-if="error" class="stream-error">{{ error }}</p><template v-if="preview && !done"><button v-if="preview.existingUser && auth.account" class="send-button" @click="accept">接受并切换工作区</button><RouterLink v-else-if="preview.existingUser" :to="loginTarget">登录已有账号</RouterLink><form v-else @submit.prevent="accept"><label>用户名<input v-model="form.username" pattern="[A-Za-z0-9._-]{3,64}" maxlength="64" required /></label><label>显示名称<input v-model="form.displayName" maxlength="128" required /></label><label>密码<input v-model="form.password" type="password" minlength="10" maxlength="72" required /></label><button class="send-button">创建账号并加入</button></form></template><p v-if="done" class="success-notice">账号已创建并加入工作区，现在可以登录。</p><RouterLink v-if="done" to="/login">前往登录</RouterLink></section></main></template>
