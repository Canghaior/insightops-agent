<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'

import {
  approveToolAction,
  compensateToolAction,
  listApprovals,
  rejectToolAction,
  type AgentToolApproval,
} from '@/api/approvals'

const approvals = ref<AgentToolApproval[]>([])
const loading = ref(true)
const error = ref('')
const processing = ref('')
const pendingCount = computed(() => approvals.value.filter((item) => item.status === 'PENDING').length)

async function load() {
  loading.value = true
  error.value = ''
  try { approvals.value = await listApprovals() }
  catch { error.value = '审批记录加载失败，请稍后重试。' }
  finally { loading.value = false }
}

async function decide(item: AgentToolApproval, action: 'approve' | 'reject' | 'compensate') {
  const messages = {
    approve: `确认执行“${item.summary}”？`,
    reject: `确认拒绝“${item.summary}”？`,
    compensate: `确认撤销该写操作并恢复执行前状态？`,
  }
  if (!globalThis.confirm(messages[action])) return
  const comment = globalThis.prompt('审批备注（可选）')?.trim()
  processing.value = item.id
  error.value = ''
  try {
    const updated = action === 'approve'
      ? await approveToolAction(item.id, comment)
      : action === 'reject'
        ? await rejectToolAction(item.id, comment)
        : await compensateToolAction(item.id, comment)
    approvals.value = approvals.value.map((current) => current.id === item.id ? updated : current)
    globalThis.dispatchEvent(new globalThis.CustomEvent('insightops:approvals-changed'))
  } catch { error.value = '审批操作未完成；记录可能已被处理或已过期。' }
  finally { processing.value = '' }
}

function input(item: AgentToolApproval): Record<string, unknown> {
  try { return JSON.parse(item.requestPayload) as Record<string, unknown> }
  catch { return {} }
}

function statusLabel(status: AgentToolApproval['status']) {
  return ({
    PENDING: '等待审批', EXECUTED: '已执行', REJECTED: '已拒绝', EXPIRED: '已过期',
    FAILED: '执行失败', COMPENSATED: '已补偿',
  })[status]
}

function time(value: string | null) {
  return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '—'
}

onMounted(load)
</script>

<template>
  <section>
    <div class="section-heading">
      <div><span class="eyebrow">Human in the loop</span><h2>操作审批</h2></div>
      <span class="subtle">{{ pendingCount }} 项等待你确认</span>
    </div>
    <div class="panel approval-boundary">
      <strong>模型不能直接执行写操作</strong>
      <p>审批前没有副作用；重复确认只执行一次。已执行的长期记忆写入可在这里补偿并恢复原状态。</p>
    </div>
    <p v-if="error" class="stream-error">{{ error }}</p>
    <div v-if="loading" class="panel conversation-empty"><strong>正在加载审批记录…</strong></div>
    <div v-else class="approval-list">
      <article v-for="item in approvals" :key="item.id" class="panel approval-card">
        <header>
          <div><span class="eyebrow">{{ item.toolName }}</span><h3>{{ item.summary }}</h3></div>
          <span class="approval-status" :class="`is-${item.status.toLowerCase()}`">{{ statusLabel(item.status) }}</span>
        </header>
        <dl>
          <div><dt>记忆名称</dt><dd>{{ input(item).key ?? '—' }}</dd></div>
          <div><dt>分类</dt><dd>{{ input(item).category ?? '—' }}</dd></div>
          <div><dt>请求时间</dt><dd>{{ time(item.createdAt) }}</dd></div>
          <div><dt>审批到期</dt><dd>{{ time(item.expiresAt) }}</dd></div>
        </dl>
        <p class="approval-value">{{ input(item).value ?? '' }}</p>
        <footer>
          <RouterLink class="text-button" :to="`/runs/${item.runId}`">查看 Run {{ item.runId.slice(0, 8) }}</RouterLink>
          <div v-if="item.status === 'PENDING'" class="approval-actions">
            <button class="secondary-button" :disabled="processing === item.id" @click="decide(item, 'reject')">拒绝</button>
            <button class="send-button" :disabled="processing === item.id" @click="decide(item, 'approve')">确认执行</button>
          </div>
          <button v-else-if="item.status === 'EXECUTED'" class="secondary-button" :disabled="processing === item.id" @click="decide(item, 'compensate')">撤销并恢复</button>
        </footer>
      </article>
      <div v-if="!approvals.length" class="panel conversation-empty"><strong>暂无审批记录</strong><p>当 Agent 建议写入长期记忆时，会在这里等待你的确认。</p></div>
    </div>
  </section>
</template>

<style scoped>
.approval-boundary { margin-bottom: 20px; padding: 20px 24px; }
.approval-boundary p { margin: 8px 0 0; color: var(--muted); }
.approval-list { display: grid; gap: 16px; }
.approval-card { padding: 22px 24px; }
.approval-card header, .approval-card footer { display: flex; justify-content: space-between; gap: 18px; align-items: center; }
.approval-card h3 { margin: 6px 0 0; }
.approval-card dl { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; }
.approval-card dt { color: var(--muted); font-size: 13px; }
.approval-card dd { margin: 4px 0 0; overflow-wrap: anywhere; }
.approval-value { padding: 14px; border-radius: 10px; background: rgba(93, 210, 171, .07); white-space: pre-wrap; }
.approval-actions { display: flex; gap: 10px; }
.approval-status { border: 1px solid var(--line); border-radius: 999px; padding: 7px 12px; white-space: nowrap; }
.is-pending { color: #f5c66a; }.is-executed { color: #71ddb6; }.is-rejected,.is-failed,.is-expired { color: #ff8b91; }.is-compensated { color: #9bb5ff; }
@media (max-width: 900px) { .approval-card dl { grid-template-columns: 1fr 1fr; } }
</style>
