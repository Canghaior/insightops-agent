<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import axios from 'axios'
import { useRoute, useRouter } from 'vue-router'

import SecuritySettingsPanel from '@/components/SecuritySettingsPanel.vue'
import { useAuthStore } from '@/stores/auth'
import { getDigestPreference, saveDigestPreference, type DigestPreference } from '@/api/intelligence'
import { listProjects, type ProjectWatch } from '@/api/projects'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()
const form = reactive({ currentPassword: '', newPassword: '', confirmPassword: '' })
const saving = ref(false)
const error = ref('')
const forced = computed(() => route.query.required === '1' || auth.account?.mustChangePassword)
const projects = ref<ProjectWatch[]>([])
const digest = reactive<DigestPreference>({ cadence: 'OFF', timeZone: 'Asia/Shanghai', deliveryHour: 9, projectIds: [] })
const digestNotice = ref('')

async function submit() {
  error.value = ''
  if (form.newPassword !== form.confirmPassword) {
    error.value = '两次输入的新密码不一致'
    return
  }
  saving.value = true
  try {
    await auth.changeOwnPassword(form.currentPassword, form.newPassword)
    await router.replace({ name: 'login', query: { changed: '1' } })
  } catch (caught: unknown) {
    if (axios.isAxiosError<{ detail?: string; message?: string }>(caught)) {
      error.value = caught.response?.data?.detail ?? caught.response?.data?.message ?? '密码修改失败，请检查当前密码和新密码规则'
    } else {
      error.value = '密码修改失败，请检查当前密码和新密码规则'
    }
  } finally {
    saving.value = false
  }
}

async function saveDigest() {
  error.value = ''; digestNotice.value = ''
  try { Object.assign(digest, await saveDigestPreference({ ...digest })); digestNotice.value = '情报摘要偏好已保存。' }
  catch { error.value = '摘要偏好保存失败，请检查时区和时间设置。' }
}

onMounted(async () => {
  try {
    const [preference, projectList] = await Promise.all([getDigestPreference(), listProjects()])
    Object.assign(digest, preference); projects.value = projectList.filter((project) => project.enabled)
  } catch { error.value = '账号设置加载失败，请稍后刷新。' }
})
</script>

<template>
  <section class="settings-page">
    <div class="section-heading">
      <div><span class="eyebrow">账号安全</span><h2>修改登录密码</h2></div>
      <span class="subtle">{{ auth.account?.username }} · {{ auth.account?.displayName }}</span>
    </div>
    <div v-if="forced" class="guardrail-notice">
      <strong>首次登录必须修改临时密码</strong>
      <p>完成修改后会退出登录，请使用新密码重新登录。</p>
    </div>
    <form class="panel settings-form" @submit.prevent="submit">
      <label>当前密码<input v-model="form.currentPassword" type="password" autocomplete="current-password" maxlength="72" required /></label>
      <label>新密码<input v-model="form.newPassword" type="password" autocomplete="new-password" minlength="10" maxlength="72" required /></label>
      <label>确认新密码<input v-model="form.confirmPassword" type="password" autocomplete="new-password" minlength="10" maxlength="72" required /></label>
      <p class="subtle">密码需为 10–72 位，并同时包含大写字母、小写字母和数字。</p>
      <p v-if="error" class="stream-error">{{ error }}</p>
      <button class="send-button" :disabled="saving">{{ saving ? '正在保存…' : '修改密码并重新登录' }}</button>
    </form>
    <SecuritySettingsPanel v-if="!forced" />
    <div class="section-heading settings-subheading"><div><span class="eyebrow">技术情报投递</span><h2>摘要偏好</h2></div><span class="subtle">只在站内生成，不发送邮件或微信</span></div>
    <form class="panel digest-preference-form" @submit.prevent="saveDigest">
      <label>摘要频率<select v-model="digest.cadence"><option value="OFF">关闭</option><option value="DAILY">每日</option><option value="WEEKLY">每周一</option></select></label>
      <label>时区<input v-model="digest.timeZone" maxlength="64" required /></label>
      <label>生成时间<select v-model.number="digest.deliveryHour"><option v-for="hour in 24" :key="hour-1" :value="hour-1">{{ String(hour-1).padStart(2,'0') }}:00</option></select></label>
      <fieldset><legend>摘要项目（不选表示全部关注项目）</legend><label v-for="project in projects" :key="project.id" class="check-label"><input v-model="digest.projectIds" type="checkbox" :value="project.id" /> {{ project.name }}</label></fieldset>
      <p v-if="digestNotice" class="success-notice">{{ digestNotice }}</p><button class="send-button">保存摘要偏好</button>
    </form>
  </section>
</template>
