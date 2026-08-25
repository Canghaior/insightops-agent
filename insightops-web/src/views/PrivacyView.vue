<script setup lang="ts">
import { ref } from 'vue'

import { createPersonalDataExport, downloadPersonalDataExport,
  requestPublicAccountDeletion, type ExportCreated } from '@/api/privacy'

const pendingExport = ref<ExportCreated | null>(null)
const exportMessage = ref('')
const password = ref('')
const mfaCode = ref('')
const deletionMessage = ref('')
const busy = ref(false)

async function createExport() {
  busy.value = true; exportMessage.value = ''
  try { pendingExport.value = await createPersonalDataExport(); exportMessage.value = '加密快照已生成，请在 24 小时内一次性下载。' }
  catch { exportMessage.value = '导出生成失败，请稍后重试。' }
  finally { busy.value = false }
}
async function downloadExport() {
  if (!pendingExport.value) return
  busy.value = true
  try {
    const bytes = await downloadPersonalDataExport(pendingExport.value)
    const url = globalThis.URL.createObjectURL(new globalThis.Blob([bytes], { type: 'application/json' }))
    const link = globalThis.document.createElement('a'); link.href = url
    link.download = 'insightops-personal-data.json'; link.click(); globalThis.URL.revokeObjectURL(url)
    pendingExport.value = null; exportMessage.value = '下载完成；该令牌已失效。'
  } catch { exportMessage.value = '下载链接无效、已过期或已经使用。' }
  finally { busy.value = false }
}
async function requestDeletion() {
  if (!globalThis.confirm('确认申请删除公开 Beta 个人账号和个人 Workspace？宽限期结束后不可恢复。')) return
  busy.value = true; deletionMessage.value = ''
  try { deletionMessage.value = `删除计划时间：${await requestPublicAccountDeletion(password.value, mfaCode.value)}` }
  catch { deletionMessage.value = '申请失败。共享 Workspace 用户请先转移所有权；公开个人 Workspace 可直接申请。' }
  finally { busy.value = false }
}
</script>

<template>
  <section class="page-card">
    <span class="eyebrow">隐私控制</span><h2>导出与删除</h2>
    <div class="p31-security-grid">
      <article class="workspace-card">
        <h3>个人数据导出</h3>
        <p>生成不含密码、会话 Token 和内部密钥的 JSON 快照。服务器仅保存 AES 加密文件，24 小时过期，下载令牌只能使用一次。</p>
        <button class="send-button" :disabled="busy" @click="createExport">生成加密导出</button>
        <button v-if="pendingExport" class="text-button" :disabled="busy" @click="downloadExport">一次性下载</button>
        <p>{{ exportMessage }}</p>
      </article>
      <article class="workspace-card danger-zone">
        <h3>删除公开 Beta 个人账号</h3>
        <p>宽限期结束后清理个人会话、Agent Run、记忆、上传记录与文件，并匿名化账号。加密异地备份最长 30 天自然过期。</p>
        <label>当前密码<input v-model="password" type="password" autocomplete="current-password" /></label>
        <label>MFA（如已启用）<input v-model="mfaCode" autocomplete="one-time-code" /></label>
        <button class="danger-button" :disabled="busy || !password" @click="requestDeletion">申请删除</button>
        <p>{{ deletionMessage }}</p>
      </article>
    </div>
  </section>
</template>
