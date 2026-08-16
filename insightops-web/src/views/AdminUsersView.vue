<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import axios from 'axios'

import {
  createUser,
  listCollectionStatus,
  listAudit,
  listUsers,
  resetPassword,
  requestCollectionSync,
  updateRole,
  updateStatus,
  type AccountAudit,
  type CollectionStatus,
  type ManagedUser,
  type SystemRole,
  type WorkspaceRole,
} from '@/api/admin'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const users = ref<ManagedUser[]>([])
const audit = ref<AccountAudit[]>([])
const collection = ref<CollectionStatus[]>([])
const loading = ref(false)
const error = ref('')
const notice = ref('')
const resetValues = reactive<Record<string, string>>({})
const form = reactive({
  username: '', displayName: '', temporaryPassword: '',
  systemRole: 'USER' as SystemRole, workspaceRole: 'MEMBER' as WorkspaceRole,
})
const isSystemAdmin = computed(() => auth.account?.systemRole === 'SYSTEM_ADMIN')

async function load() {
  loading.value = true; error.value = ''
  try {
    [users.value, audit.value] = await Promise.all([listUsers(), listAudit()])
    collection.value = isSystemAdmin.value ? await listCollectionStatus() : []
  }
  catch (caught: unknown) { error.value = message(caught) } finally { loading.value = false }
}

async function addUser() {
  error.value = ''; notice.value = ''
  try {
    await createUser({ ...form })
    notice.value = `账号 ${form.username} 已创建；请通过安全渠道发送临时密码。`
    form.username = ''; form.displayName = ''; form.temporaryPassword = ''
    form.systemRole = 'USER'; form.workspaceRole = 'MEMBER'
    await load()
  } catch (caught: unknown) { error.value = message(caught) }
}

async function setStatus(user: ManagedUser) {
  error.value = ''
  try { await updateStatus(user.userId, user.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE'); await load() }
  catch (caught: unknown) { error.value = message(caught) }
}

async function setRole(user: ManagedUser, role: WorkspaceRole) {
  error.value = ''
  try { await updateRole(user.userId, role); await load() }
  catch (caught: unknown) { error.value = message(caught) }
}

async function reset(user: ManagedUser) {
  const password = resetValues[user.userId]
  if (!password) return
  error.value = ''; notice.value = ''
  try {
    await resetPassword(user.userId, password)
    resetValues[user.userId] = ''
    notice.value = `${user.username} 的密码已重置，原会话已失效，下次登录必须改密。`
    await load()
  } catch (caught: unknown) { error.value = message(caught) }
}

async function syncNow(project: CollectionStatus) {
  error.value = ''; notice.value = ''
  try {
    await requestCollectionSync(project.projectId)
    notice.value = `${project.projectName} 已加入采集队列，Worker 会在下一轮处理。`
    collection.value = await listCollectionStatus()
  } catch (caught: unknown) { error.value = message(caught) }
}

function message(caught: unknown): string {
  if (axios.isAxiosError<{ detail?: string; message?: string }>(caught)) {
    return caught.response?.data?.detail ?? caught.response?.data?.message ?? '操作失败，请稍后重试'
  }
  return '操作失败，请稍后重试'
}

function actionLabel(action: string): string {
  return ({
    USER_CREATED: '创建用户', USER_ENABLED: '启用用户', USER_DISABLED: '停用用户',
    PASSWORD_RESET: '重置密码', WORKSPACE_ROLE_CHANGED: '修改工作区角色',
    LOGIN_SUCCEEDED: '登录成功', LOGOUT: '退出登录', PASSWORD_CHANGED: '修改自己的密码',
  } as Record<string, string>)[action] ?? action
}

onMounted(load)
</script>

<template>
  <section>
    <div class="section-heading">
      <div><span class="eyebrow">封闭邀请制</span><h2>用户与权限管理</h2></div>
      <span class="subtle">不开放公开注册 · 新用户首次登录强制改密</span>
    </div>

    <form class="panel admin-create-form" autocomplete="off" @submit.prevent="addUser">
      <div class="admin-form-heading"><strong>创建内部账号</strong><span>临时密码不会被页面保存或再次显示</span></div>
      <label>用户名<input v-model="form.username" name="new-insightops-username" autocomplete="off" pattern="[A-Za-z0-9._-]{3,64}" maxlength="64" required /></label>
      <label>显示名称<input v-model="form.displayName" name="new-insightops-display-name" autocomplete="off" maxlength="128" required /></label>
      <label>临时密码<input v-model="form.temporaryPassword" name="new-insightops-password" type="password" autocomplete="new-password" minlength="10" maxlength="72" required /></label>
      <label v-if="isSystemAdmin">系统角色<select v-model="form.systemRole"><option value="USER">普通用户</option><option value="SYSTEM_ADMIN">系统管理员</option></select></label>
      <label v-if="isSystemAdmin">工作区角色<select v-model="form.workspaceRole"><option value="MEMBER">成员</option><option value="OWNER">Owner</option></select></label>
      <button class="send-button">创建账号</button>
    </form>

    <p v-if="error" class="stream-error">{{ error }}</p>
    <p v-if="notice" class="success-notice">{{ notice }}</p>

    <div class="panel admin-table-panel">
      <div class="admin-table-head"><span>用户</span><span>权限</span><span>状态</span><span>账号操作</span></div>
      <article v-for="user in users" :key="user.userId" class="admin-user-row">
        <div><strong>{{ user.displayName }}</strong><small>@{{ user.username }}</small></div>
        <div class="role-stack">
          <b>{{ user.systemRole === 'SYSTEM_ADMIN' ? '系统管理员' : '普通用户' }}</b>
          <select v-if="isSystemAdmin" :value="user.workspaceRole" @change="setRole(user, ($event.target as HTMLSelectElement).value as WorkspaceRole)">
            <option value="OWNER">OWNER</option><option value="MEMBER">MEMBER</option>
          </select>
          <small v-else>{{ user.workspaceRole }}</small>
        </div>
        <div><i class="status-pill" :class="user.status === 'ACTIVE' ? 'status-succeeded' : 'status-cancelled'">{{ user.status }}</i><small v-if="user.mustChangePassword">待首次改密</small></div>
        <div class="account-actions">
          <button class="secondary-button" :disabled="user.userId === auth.account?.userId" @click="setStatus(user)">{{ user.status === 'ACTIVE' ? '停用' : '启用' }}</button>
          <input v-model="resetValues[user.userId]" :name="`reset-password-${user.userId}`" type="password" autocomplete="new-password" minlength="10" maxlength="72" placeholder="新临时密码" />
          <button class="secondary-button" :disabled="!resetValues[user.userId]" @click="reset(user)">重置密码</button>
        </div>
      </article>
      <p v-if="loading" class="run-loading">正在加载账号…</p>
    </div>

    <template v-if="isSystemAdmin">
      <div class="section-heading audit-heading"><div><span class="eyebrow">Worker 可观测性</span><h2>Release 采集状态</h2></div><span class="subtle">默认每 6 小时增量同步</span></div>
      <div class="collection-grid">
        <article v-for="project in collection" :key="project.projectId" class="panel collection-card">
          <header><div><strong>{{ project.projectName }}</strong><small>{{ project.repositoryOwner }}/{{ project.projectName }}</small></div><i class="status-pill" :class="project.status === 'SUCCEEDED' ? 'status-succeeded' : project.status === 'FAILED' ? 'status-failed' : 'status-running'">{{ project.status }}</i></header>
          <dl><div><dt>上次采集</dt><dd>{{ project.lastSyncAt ? new Date(project.lastSyncAt).toLocaleString() : '尚未执行' }}</dd></div><div><dt>下次计划</dt><dd>{{ project.nextSyncAt ? new Date(project.nextSyncAt).toLocaleString() : '等待关注' }}</dd></div></dl>
          <p v-if="project.lastError" class="stream-error">{{ project.lastError }}</p>
          <footer><span>连续失败 {{ project.consecutiveFailures }} 次</span><button class="secondary-button" @click="syncNow(project)">立即同步</button></footer>
        </article>
      </div>
    </template>

    <div class="section-heading audit-heading"><div><span class="eyebrow">操作留痕</span><h2>账号审计日志</h2></div></div>
    <div class="panel audit-list">
      <article v-for="entry in audit" :key="entry.id">
        <time>{{ new Date(entry.createdAt).toLocaleString() }}</time>
        <strong>{{ entry.actorUsername ?? '系统' }}</strong>
        <span>{{ actionLabel(entry.action) }}</span>
        <b>{{ entry.targetUsername ?? '-' }}</b>
      </article>
      <p v-if="!audit.length" class="subtle">暂无账号操作记录。</p>
    </div>
  </section>
</template>
