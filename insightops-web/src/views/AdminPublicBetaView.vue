<script setup lang="ts">
import { onMounted, ref } from 'vue'

import { getPublicBetaAdminStatus, updatePublicBetaControl, type PublicBetaAdminStatus } from '@/api/publicBeta'

const state = ref<PublicBetaAdminStatus | null>(null)
const registrationEnabled = ref(false)
const runsEnabled = ref(true)
const statusMessage = ref('')
const saving = ref(false)
const message = ref('')

function assign(value: PublicBetaAdminStatus) {
  state.value = value; registrationEnabled.value = value.control.registrationEnabled
  runsEnabled.value = value.control.runsEnabled; statusMessage.value = value.control.statusMessage ?? ''
}
async function save() {
  saving.value = true; message.value = ''
  try { assign(await updatePublicBetaControl({ registrationEnabled: registrationEnabled.value,
    runsEnabled: runsEnabled.value, statusMessage: statusMessage.value })); message.value = '公开 Beta 开关已更新。' }
  catch { message.value = '更新失败；只有配置完整时才能开放注册。' }
  finally { saving.value = false }
}
onMounted(async () => assign(await getPublicBetaAdminStatus()))
</script>

<template>
  <section v-if="state" class="page-card">
    <span class="eyebrow">公开免费 Beta</span><h2>注册与紧急控制</h2>
    <div class="beta-stat-grid">
      <article><small>已激活</small><strong>{{ state.publicStatus.activeRegistrations }}</strong></article>
      <article><small>待验证</small><strong>{{ state.publicStatus.pendingRegistrations }}</strong></article>
      <article><small>已占名额</small><strong>{{ state.publicStatus.occupiedSlots }}/{{ state.publicStatus.maximumRegistrations }}</strong></article>
      <article><small>就绪状态</small><strong>{{ state.publicStatus.reason || 'READY' }}</strong></article>
    </div>
    <form class="beta-control-form" @submit.prevent="save">
      <label class="consent-row"><input v-model="registrationEnabled" type="checkbox" />开放新用户注册</label>
      <label class="consent-row"><input v-model="runsEnabled" type="checkbox" />允许公开 Beta Workspace 创建新 Run</label>
      <label>公开状态说明<textarea v-model="statusMessage" maxlength="500"></textarea></label>
      <button class="send-button" :disabled="saving">{{ saving ? '保存中…' : '保存开关' }}</button>
      <p>{{ message }}</p>
    </form>
  </section>
</template>
