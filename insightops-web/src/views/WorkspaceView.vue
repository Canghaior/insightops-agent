<script setup lang="ts">
import axios from 'axios'
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'

import * as api from '@/api/workspaces'
import type { WorkspaceInvitation, WorkspaceMember } from '@/api/workspaces'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore(); const router = useRouter()
const selectedId = ref(auth.account?.workspaceId ?? '')
const members = ref<WorkspaceMember[]>([]); const invitations = ref<WorkspaceInvitation[]>([])
const error = ref(''); const notice = ref(''); const manualLink = ref('')
const createForm = reactive({ name: '', slug: '', description: '' })
const editForm = reactive({ name: '', description: '' })
const inviteForm = reactive({ email: '', role: 'MEMBER' })
const selected = computed(() => auth.workspaces.find(value => value.id === selectedId.value) ?? null)
const canManage = computed(() => selected.value?.role === 'OWNER' || auth.account?.systemRole === 'SYSTEM_ADMIN')

function message(caught: unknown) {
  if (axios.isAxiosError<{ detail?: string; message?: string }>(caught)) return caught.response?.data?.detail ?? caught.response?.data?.message ?? '操作失败'
  return '操作失败'
}

async function loadDetails() {
  members.value = []; invitations.value = []; error.value = ''
  if (!selected.value) return
  editForm.name = selected.value.name; editForm.description = selected.value.description ?? ''
  if (canManage.value) {
    try { [members.value, invitations.value] = await Promise.all([api.listMembers(selected.value.id), api.listInvitations(selected.value.id)]) }
    catch (caught) { error.value = message(caught) }
  }
}

async function createWorkspace() {
  try { const created = await api.createWorkspace({ ...createForm }); await auth.refreshWorkspaces(); selectedId.value = created.id; Object.assign(createForm, { name: '', slug: '', description: '' }); notice.value = 'Workspace 已创建。' }
  catch (caught) { error.value = message(caught) }
}

async function switchTo(id: string) {
  try { await auth.switchWorkspace(id); selectedId.value = id; notice.value = '已切换 Workspace。'; await router.replace('/workspace') }
  catch (caught) { error.value = message(caught) }
}

async function updateWorkspace() {
  if (!selected.value) return
  try { await api.updateWorkspace(selected.value.id, { ...editForm }); await auth.refreshWorkspaces(); notice.value = 'Workspace 信息已更新。' }
  catch (caught) { error.value = message(caught) }
}

async function invite() {
  if (!selected.value) return
  try { const result = await api.inviteMember(selected.value.id, inviteForm.email, inviteForm.role); manualLink.value = result.manualInvitationLink ?? ''; notice.value = result.deliveryQueued ? '邀请邮件已进入可靠发送队列。' : 'SMTP 未启用，请安全发送下方一次性链接。'; inviteForm.email = ''; await loadDetails() }
  catch (caught) { error.value = message(caught) }
}

async function role(member: WorkspaceMember, value: string) { if (!selected.value) return; try { await api.updateMemberRole(selected.value.id, member.userId, value); await loadDetails() } catch (caught) { error.value = message(caught) } }
async function transfer(member: WorkspaceMember) { if (!selected.value || !globalThis.confirm(`将所有权转移给 ${member.displayName}？当前 Owner 会降为 MEMBER。`)) return; try { await api.transferOwnership(selected.value.id, member.userId); await auth.refreshWorkspaces(); await loadDetails() } catch (caught) { error.value = message(caught) } }
async function remove(member: WorkspaceMember) { if (!selected.value || !globalThis.confirm(`移除 ${member.displayName}？`)) return; try { await api.removeMember(selected.value.id, member.userId); await loadDetails() } catch (caught) { error.value = message(caught) } }
async function revoke(invitation: WorkspaceInvitation) { if (!selected.value) return; try { await api.revokeInvitation(selected.value.id, invitation.id); await loadDetails() } catch (caught) { error.value = message(caught) } }
async function leave() {
  if (!selected.value || !globalThis.confirm(`退出 ${selected.value.name}？会话将切换到其他活动 Workspace；没有替代项时会退出登录。`)) return
  try {
    await api.leaveWorkspace(selected.value.id)
    await auth.refreshWorkspaces()
    const next = auth.workspaces.find(value => value.status === 'ACTIVE')
    if (!next) { auth.clear(); await router.push('/login'); return }
    await auth.switchWorkspace(next.id)
    selectedId.value = next.id; notice.value = '已退出并切换到其他 Workspace。'
  } catch (caught) {
    if (axios.isAxiosError(caught) && caught.response?.status === 401) { auth.clear(); await router.push('/login'); return }
    error.value = message(caught)
  }
}
async function archive() { if (!selected.value || !globalThis.confirm(`归档 ${selected.value.name}？归档后不能继续运行任务。`)) return; try { await api.archiveWorkspace(selected.value.id); await auth.refreshWorkspaces(); const next = auth.workspaces.find(value => value.status === 'ACTIVE'); if (next) await switchTo(next.id) } catch (caught) { error.value = message(caught) } }

watch(selectedId, loadDetails)
onMounted(async () => { await auth.refreshWorkspaces(); if (!selectedId.value) selectedId.value = auth.account?.workspaceId ?? auth.workspaces[0]?.id ?? ''; await loadDetails() })
</script>

<template>
  <section>
    <div class="section-heading"><div><span class="eyebrow">P3.1 多租户</span><h2>Workspace 与团队</h2></div><span class="subtle">邀请制 · Owner 治理 · 会话级切换</span></div>
    <p v-if="error" class="stream-error">{{ error }}</p><p v-if="notice" class="success-notice">{{ notice }}</p>
    <div class="workspace-grid">
      <article v-for="workspace in auth.workspaces" :key="workspace.id" class="panel workspace-card" :class="{ active: workspace.id === auth.account?.workspaceId }"><header><div><strong>{{ workspace.name }}</strong><small>{{ workspace.slug }}</small></div><i class="status-pill">{{ workspace.role }}</i></header><p>{{ workspace.description || '暂无描述' }}</p><div class="workspace-actions"><button v-if="workspace.id !== auth.account?.workspaceId && workspace.status === 'ACTIVE'" class="secondary-button" @click="switchTo(workspace.id)">切换到这里</button><button class="text-button" @click="selectedId = workspace.id">管理</button></div></article>
    </div>
    <form class="panel admin-create-form" @submit.prevent="createWorkspace"><div class="admin-form-heading"><strong>创建 Workspace</strong><span>创建者自动成为 Owner</span></div><label>名称<input v-model="createForm.name" maxlength="128" required /></label><label>Slug<input v-model="createForm.slug" pattern="[a-z0-9][a-z0-9-]{2,62}[a-z0-9]" maxlength="64" required /></label><label>描述<input v-model="createForm.description" maxlength="500" /></label><button class="send-button">创建</button></form>
    <template v-if="selected">
      <div class="section-heading settings-subheading"><div><span class="eyebrow">当前管理对象</span><h2>{{ selected.name }}</h2></div><span class="subtle">{{ selected.role }} · {{ selected.status }}</span></div>
      <div v-if="canManage" class="workspace-admin-grid">
        <div>
          <form class="panel settings-form" @submit.prevent="updateWorkspace"><strong>基本信息</strong><label>名称<input v-model="editForm.name" maxlength="128" required /></label><label>描述<textarea v-model="editForm.description" maxlength="500"></textarea></label><button class="secondary-button">保存</button></form>
          <div class="panel"><strong>成员</strong><article v-for="member in members" :key="member.userId" class="member-row"><div><strong>{{ member.displayName }} <small>@{{ member.username }}</small></strong><small>{{ member.email || '未设置邮箱' }} · {{ member.emailVerified ? '已验证' : '未验证' }}</small></div><div class="workspace-actions"><select :value="member.role" @change="role(member, ($event.target as HTMLSelectElement).value)"><option value="OWNER">OWNER</option><option value="MEMBER">MEMBER</option></select><button v-if="member.userId !== auth.account?.userId" class="secondary-button" @click="transfer(member)">转移所有权</button><button v-if="member.userId !== auth.account?.userId" class="text-button" @click="remove(member)">移除</button></div></article></div>
        </div>
        <div>
          <form class="panel settings-form" @submit.prevent="invite"><strong>邀请成员</strong><label>邮箱<input v-model="inviteForm.email" type="email" maxlength="320" required /></label><label>角色<select v-model="inviteForm.role"><option value="MEMBER">MEMBER</option><option value="OWNER">OWNER</option></select></label><button class="send-button">创建邀请</button><a v-if="manualLink" class="manual-link invite-link" :href="manualLink">{{ manualLink }}</a></form>
          <div class="panel"><strong>待处理邀请</strong><article v-for="item in invitations" :key="item.id" class="invitation-row"><div><strong>{{ item.email }}</strong><small>{{ item.role }} · {{ new Date(item.expiresAt).toLocaleString() }} 到期</small></div><button class="text-button" @click="revoke(item)">撤销</button></article><p v-if="!invitations.length" class="subtle">没有待处理邀请。</p></div>
          <div class="panel danger-zone"><strong>危险操作</strong><div class="workspace-actions"><button class="secondary-button" @click="leave">退出 Workspace</button><button class="danger-button" @click="archive">归档 Workspace</button></div></div>
        </div>
      </div>
      <div v-else class="panel"><p>普通成员可查看和切换 Workspace；邀请、成员与所有权操作仅 Owner 可用。</p><button class="secondary-button" @click="leave">退出 Workspace</button></div>
    </template>
  </section>
</template>
