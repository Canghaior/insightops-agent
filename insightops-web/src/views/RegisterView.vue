<script setup lang="ts">
import axios from 'axios'
import { nextTick, onBeforeUnmount, onMounted, ref } from 'vue'

import { getPublicBetaStatus, registerPublicBeta, type PublicBetaStatus } from '@/api/publicBeta'

interface TurnstileApi {
  render: (target: unknown, options: Record<string, unknown>) => string
  remove: (widgetId: string) => void
  reset: (widgetId: string) => void
}
function turnstileApi(): TurnstileApi | undefined {
  return (globalThis as typeof globalThis & { turnstile?: TurnstileApi }).turnstile
}

const status = ref<PublicBetaStatus | null>(null)
const username = ref('')
const displayName = ref('')
const email = ref('')
const password = ref('')
const confirmPassword = ref('')
const ageConfirmed = ref(false)
const termsAccepted = ref(false)
const privacyAccepted = ref(false)
const acceptableUseAccepted = ref(false)
const turnstileToken = ref('')
const widgetHost = ref<unknown>(null)
const loading = ref(false)
const error = ref('')
const success = ref('')
let widgetId = ''

async function loadTurnstile() {
  if (!status.value?.registrationEnabled || !status.value.turnstileSiteKey) return
  await nextTick()
  if (!turnstileApi()) {
    await new Promise<void>((resolve, reject) => {
      const existing = globalThis.document.querySelector('script[data-insightops-turnstile]')
      if (existing) { existing.addEventListener('load', () => resolve(), { once: true }); return }
      const script = globalThis.document.createElement('script')
      script.src = 'https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit'
      script.async = true; script.defer = true; script.dataset.insightopsTurnstile = '1'
      script.onload = () => resolve(); script.onerror = () => reject(new Error('TURNSTILE_LOAD_FAILED'))
      globalThis.document.head.appendChild(script)
    })
  }
  if (turnstileApi() && widgetHost.value) {
    widgetId = turnstileApi()!.render(widgetHost.value, {
      sitekey: status.value.turnstileSiteKey,
      action: 'register',
      theme: 'dark',
      callback: (token: string) => { turnstileToken.value = token },
      'expired-callback': () => { turnstileToken.value = '' },
      'error-callback': () => { turnstileToken.value = ''; error.value = '人机验证暂时不可用，请稍后重试。' },
    })
  }
}

async function submit() {
  error.value = ''; success.value = ''
  if (password.value !== confirmPassword.value) { error.value = '两次输入的密码不一致。'; return }
  if (!turnstileToken.value) { error.value = '请先完成人机验证。'; return }
  loading.value = true
  try {
    const result = await registerPublicBeta({ username: username.value.trim(), displayName: displayName.value.trim(),
      email: email.value.trim(), password: password.value, turnstileToken: turnstileToken.value,
      ageConfirmed: ageConfirmed.value, termsAccepted: termsAccepted.value,
      privacyAccepted: privacyAccepted.value, acceptableUseAccepted: acceptableUseAccepted.value })
    success.value = `申请已占用第 ${result.registrationSlot} 个 Beta 名额。请在邮箱中完成验证后再登录。`
  } catch (caught) {
    error.value = axios.isAxiosError(caught)
      ? String(caught.response?.data?.detail ?? caught.response?.data?.message ?? '注册失败，请稍后重试。')
      : '注册失败，请稍后重试。'
    if (widgetId) turnstileApi()?.reset(widgetId)
    turnstileToken.value = ''
  } finally { loading.value = false }
}

onMounted(async () => {
  try { status.value = await getPublicBetaStatus(); await loadTurnstile() }
  catch { error.value = '无法读取公开 Beta 状态，请稍后重试。' }
})
onBeforeUnmount(() => { if (widgetId) turnstileApi()?.remove(widgetId) })
</script>

<template>
  <main class="login-page public-beta-page">
    <section class="login-card registration-card">
      <div class="brand"><span class="brand-mark">IO</span><div><strong>InsightOps</strong><small>免费公开 Beta</small></div></div>
      <span class="eyebrow">首批 {{ status?.maximumRegistrations ?? 100 }} 人</span>
      <h1>创建个人 Workspace</h1>
      <p>当前免费测试，不包含付费承诺。每个 Workspace 同时最多运行 1 个 Agent Run。</p>
      <div v-if="status && !status.registrationEnabled" class="stream-error">
        注册尚未开放：{{ status.statusMessage || status.reason }}
      </div>
      <form v-else-if="status" @submit.prevent="submit">
        <label>用户名<input v-model="username" autocomplete="username" minlength="3" maxlength="64" required /></label>
        <label>显示名称<input v-model="displayName" autocomplete="name" maxlength="128" required /></label>
        <label>邮箱<input v-model="email" type="email" autocomplete="email" maxlength="320" required /></label>
        <label>密码<input v-model="password" type="password" autocomplete="new-password" minlength="10" maxlength="72" required /></label>
        <small>10–72 位，至少包含大写字母、小写字母和数字。</small>
        <label>确认密码<input v-model="confirmPassword" type="password" autocomplete="new-password" maxlength="72" required /></label>
        <label class="consent-row"><input v-model="ageConfirmed" type="checkbox" required />我确认已满 {{ status.minimumAge }} 周岁。</label>
        <label class="consent-row"><input v-model="termsAccepted" type="checkbox" required />我已阅读并同意 <RouterLink to="/legal/terms" target="_blank">用户协议</RouterLink>。</label>
        <label class="consent-row"><input v-model="privacyAccepted" type="checkbox" required />我已阅读并同意 <RouterLink to="/legal/privacy" target="_blank">隐私政策</RouterLink>。</label>
        <label class="consent-row"><input v-model="acceptableUseAccepted" type="checkbox" required />我已阅读并同意 <RouterLink to="/legal/acceptable-use" target="_blank">可接受使用政策</RouterLink>。</label>
        <div ref="widgetHost" class="turnstile-host" aria-label="人机验证"></div>
        <p v-if="error" class="stream-error">{{ error }}</p>
        <p v-if="success" class="success-notice">{{ success }}</p>
        <button class="send-button" :disabled="loading || Boolean(success)">{{ loading ? '提交中…' : '免费注册' }}</button>
      </form>
      <RouterLink to="/login">已有账号？返回登录</RouterLink>
    </section>
  </main>
</template>
