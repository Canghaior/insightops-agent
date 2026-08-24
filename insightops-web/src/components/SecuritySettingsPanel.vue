<script setup lang="ts">
import axios from 'axios'
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'

import * as identity from '@/api/identity'
import type { IdentitySession, SecuritySummary } from '@/api/identity'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const router = useRouter()
const security = ref<SecuritySummary | null>(null)
const sessions = ref<IdentitySession[]>([])
const error = ref('')
const notice = ref('')
const manualLink = ref('')
const emailForm = reactive({ email: '', password: '' })
const mfaForm = reactive({ password: '', code: '' })
const mfaSetup = ref<{ secret: string; otpauthUri: string } | null>(null)
const recoveryCodes = ref<string[]>([])
const deletion = reactive({ password: '', mfaCode: '' })

function message(caught: unknown) {
  if (axios.isAxiosError<{ detail?: string; message?: string }>(caught)) {
    return caught.response?.data?.detail ?? caught.response?.data?.message ?? '操作失败，请稍后重试'
  }
  return '操作失败，请稍后重试'
}

async function load() {
  try {
    [security.value, sessions.value] = await Promise.all([identity.getSecurity(), identity.listSessions()])
    emailForm.email = security.value.email ?? ''
  } catch (caught) { error.value = message(caught) }
}

async function saveEmail() {
  error.value = ''; notice.value = ''; manualLink.value = ''
  try {
    const result = await identity.requestEmail(emailForm.password, emailForm.email)
    notice.value = result.deliveryQueued ? '验证邮件已进入可靠发送队列。' : 'SMTP 尚未启用，请使用下面的一次性验证链接。'
    manualLink.value = result.manualVerificationLink ?? ''
    emailForm.password = ''
    await load()
  } catch (caught) { error.value = message(caught) }
}

async function startMfa() {
  error.value = ''; notice.value = ''; recoveryCodes.value = []
  try { mfaSetup.value = await identity.beginMfa(mfaForm.password); mfaForm.password = '' }
  catch (caught) { error.value = message(caught) }
}

async function confirmMfa() {
  error.value = ''
  try {
    recoveryCodes.value = await identity.confirmMfa(mfaForm.code)
    mfaSetup.value = null; mfaForm.code = ''; notice.value = 'MFA 已启用，请立即离线保存恢复码。'
    await load()
  } catch (caught) { error.value = message(caught) }
}

async function stopMfa() {
  error.value = ''
  try {
    await identity.disableMfa(mfaForm.password, mfaForm.code)
    mfaForm.password = ''; mfaForm.code = ''; notice.value = 'MFA 已关闭，其他登录会话已撤销。'
    await load()
  } catch (caught) { error.value = message(caught) }
}

async function revokeSession(session: IdentitySession) {
  if (!globalThis.confirm(session.current ? '撤销当前会话并退出登录？' : '撤销这个会话？')) return
  try {
    await identity.revokeSession(session.id)
    if (session.current) { auth.clear(); await router.push('/login'); return }
    await load()
  } catch (caught) { error.value = message(caught) }
}

async function revokeOthers() {
  try { const count = await identity.revokeOtherSessions(); notice.value = `已撤销 ${count} 个其他会话。`; await load() }
  catch (caught) { error.value = message(caught) }
}

async function deleteAccount() {
  if (!globalThis.confirm('账号会立即退出，并在宽限期后匿名化。确定继续？')) return
  try {
    await identity.requestDeletion(deletion.password, deletion.mfaCode)
    auth.clear(); await router.push('/login?deletion=1')
  } catch (caught) { error.value = message(caught) }
}

onMounted(load)
async function cancelDeletion() {
  error.value = ''
  try {
    await identity.cancelDeletion(deletion.password)
    deletion.password = ''; notice.value = '账号删除申请已取消。'; await load()
  } catch (caught) { error.value = message(caught) }
}

</script>

<template>
  <div class="section-heading settings-subheading"><div><span class="eyebrow">身份与会话</span><h2>邮箱、MFA 与登录设备</h2></div><span class="subtle">单次令牌 · 加密密钥 · 会话可撤销</span></div>
  <p v-if="error" class="stream-error">{{ error }}</p><p v-if="notice" class="success-notice">{{ notice }}</p>
  <div class="p31-security-grid">
    <form class="panel settings-form" @submit.prevent="saveEmail">
      <strong>验证邮箱</strong><p class="subtle">当前状态：{{ security?.emailVerified ? '已验证' : '未验证' }}</p>
      <label>邮箱<input v-model="emailForm.email" type="email" maxlength="320" required /></label>
      <label>当前密码<input v-model="emailForm.password" type="password" maxlength="72" required /></label>
      <button class="send-button">发送验证链接</button>
      <a v-if="manualLink" class="manual-link" :href="manualLink">打开一次性验证链接</a>
    </form>
    <form class="panel settings-form" @submit.prevent="security?.mfaEnabled ? stopMfa() : (mfaSetup ? confirmMfa() : startMfa())">
      <strong>双因素认证</strong><p class="subtle">{{ security?.mfaEnabled ? `已启用 · 剩余恢复码 ${security.unusedRecoveryCodes}` : '使用任意 TOTP 验证器' }}</p>
      <template v-if="!security?.mfaEnabled && !mfaSetup"><label>当前密码<input v-model="mfaForm.password" type="password" maxlength="72" required /></label><button class="send-button">开始设置</button></template>
      <template v-else-if="mfaSetup"><code class="secret-code">{{ mfaSetup.secret }}</code><p class="subtle">在验证器中导入上述密钥，再输入 6 位验证码。</p><label>验证码<input v-model="mfaForm.code" inputmode="numeric" maxlength="32" required /></label><button class="send-button">确认启用</button></template>
      <template v-else><label>当前密码<input v-model="mfaForm.password" type="password" maxlength="72" required /></label><label>验证码或恢复码<input v-model="mfaForm.code" maxlength="32" required /></label><button class="danger-button">关闭 MFA</button></template>
      <div v-if="recoveryCodes.length" class="recovery-codes"><code v-for="code in recoveryCodes" :key="code">{{ code }}</code></div>
    </form>
  </div>
  <div class="panel session-panel"><header><div><strong>活跃会话</strong><p class="subtle">IP 仅保存不可逆指纹，不保存明文地址。</p></div><button class="secondary-button" @click="revokeOthers">撤销其他会话</button></header><article v-for="session in sessions" :key="session.id"><div><strong>{{ session.current ? '当前设备' : (session.userAgent || '未知设备') }}</strong><small>{{ session.workspaceName || '无活动工作区' }} · 最近活动 {{ new Date(session.lastSeenAt).toLocaleString() }}</small></div><button class="secondary-button" @click="revokeSession(session)">撤销</button></article></div>
  <form class="panel settings-form danger-zone" @submit.prevent="security?.deletionScheduledAt ? cancelDeletion() : deleteAccount()"><strong>删除账号</strong><p class="subtle">{{ security?.deletionScheduledAt ? `计划于 ${new Date(security.deletionScheduledAt).toLocaleString()} 匿名化，可在宽限期内取消。` : '提交后立即撤销会话，宽限期结束后匿名化账号。唯一 Owner 必须先转移所有权或归档工作区。' }}</p><label>当前密码<input v-model="deletion.password" type="password" maxlength="72" required /></label><label v-if="security?.mfaEnabled && !security.deletionScheduledAt">MFA 验证码<input v-model="deletion.mfaCode" maxlength="32" required /></label><button :class="security?.deletionScheduledAt ? 'secondary-button' : 'danger-button'">{{ security?.deletionScheduledAt ? '取消删除申请' : '申请删除账号' }}</button></form>
</template>
