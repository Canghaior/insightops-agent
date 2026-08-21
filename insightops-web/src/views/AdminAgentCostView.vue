<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'

import {
  getAgentCostOverview,
  updateAgentCostPolicy,
  type AgentCostLedgerEntry,
  type AgentCostOverview,
  type AgentCostPolicyUpdate,
} from '@/api/agentCost'

const overview = ref<AgentCostOverview | null>(null)
const loading = ref(false)
const saving = ref(false)
const error = ref('')
const notice = ref('')
const form = reactive<AgentCostPolicyUpdate>({
  enabled: true,
  dailyTokenLimit: 500_000,
  dailyCostLimitCny: 20,
  monthlyTokenLimit: 10_000_000,
  monthlyCostLimitCny: 300,
  maxConcurrentRuns: 5,
  warningPercent: 80,
  hardLimitEnabled: true,
})

const dailyTokenPercent = computed(() => percent(
  overview.value?.usage.dailyTokens ?? 0,
  overview.value?.policy.dailyTokenLimit ?? 0,
))
const dailyCostPercent = computed(() => percent(
  overview.value?.usage.dailyCostCny ?? 0,
  overview.value?.policy.dailyCostLimitCny ?? 0,
))
const monthlyTokenPercent = computed(() => percent(
  overview.value?.usage.monthlyTokens ?? 0,
  overview.value?.policy.monthlyTokenLimit ?? 0,
))
const monthlyCostPercent = computed(() => percent(
  overview.value?.usage.monthlyCostCny ?? 0,
  overview.value?.policy.monthlyCostLimitCny ?? 0,
))

function percent(value: number, limit: number): number {
  if (limit <= 0) return 0
  return Math.min(100, Math.round((value / limit) * 100))
}

function syncForm(result: AgentCostOverview) {
  Object.assign(form, {
    enabled: result.policy.enabled,
    dailyTokenLimit: result.policy.dailyTokenLimit,
    dailyCostLimitCny: result.policy.dailyCostLimitCny,
    monthlyTokenLimit: result.policy.monthlyTokenLimit,
    monthlyCostLimitCny: result.policy.monthlyCostLimitCny,
    maxConcurrentRuns: result.policy.maxConcurrentRuns,
    warningPercent: result.policy.warningPercent,
    hardLimitEnabled: result.policy.hardLimitEnabled,
  })
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const result = await getAgentCostOverview()
    overview.value = result
    syncForm(result)
  } catch {
    error.value = '成本治理数据加载失败，请稍后重试。'
  } finally {
    loading.value = false
  }
}

async function save() {
  saving.value = true
  error.value = ''
  notice.value = ''
  try {
    if (form.monthlyTokenLimit < form.dailyTokenLimit
      || form.monthlyCostLimitCny < form.dailyCostLimitCny) {
      throw new Error('月度上限不能低于每日上限。')
    }
    await updateAgentCostPolicy({ ...form })
    notice.value = 'Workspace Agent 成本策略已保存。'
    await load()
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '成本策略保存失败。'
  } finally {
    saving.value = false
  }
}

function formatNumber(value: number): string {
  return new Intl.NumberFormat('zh-CN').format(value)
}

function formatCost(value: number): string {
  return `¥${Number(value).toFixed(6)}`
}

function formatDate(value: string): string {
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit',
  }).format(new Date(value))
}

function ledgerLabel(entry: AgentCostLedgerEntry): string {
  return ({
    RESERVE: '预占', SETTLE: '结算', RELEASE: '释放', REJECT: '拒绝',
  } as Record<string, string>)[entry.entryType] ?? entry.entryType
}

onMounted(load)
</script>

<template>
  <section>
    <div class="section-heading runs-heading">
      <div><span class="eyebrow">P2.1-C · Workspace</span><h2>Agent 成本治理</h2></div>
      <button class="secondary-button" :disabled="loading" @click="load">刷新</button>
    </div>

    <div v-if="error" class="run-error">{{ error }}</div>
    <div v-if="notice" class="success-banner">{{ notice }}</div>

    <template v-if="overview">
      <div class="cost-governance-summary">
        <article><span>今日 Token</span><strong>{{ formatNumber(overview.usage.dailyTokens) }}</strong><small>/ {{ formatNumber(overview.policy.dailyTokenLimit) }}</small><el-progress :percentage="dailyTokenPercent" :show-text="false" /></article>
        <article><span>今日费用</span><strong>{{ formatCost(overview.usage.dailyCostCny) }}</strong><small>/ {{ formatCost(overview.policy.dailyCostLimitCny) }}</small><el-progress :percentage="dailyCostPercent" :show-text="false" /></article>
        <article><span>本月 Token</span><strong>{{ formatNumber(overview.usage.monthlyTokens) }}</strong><small>/ {{ formatNumber(overview.policy.monthlyTokenLimit) }}</small><el-progress :percentage="monthlyTokenPercent" :show-text="false" /></article>
        <article><span>本月费用</span><strong>{{ formatCost(overview.usage.monthlyCostCny) }}</strong><small>/ {{ formatCost(overview.policy.monthlyCostLimitCny) }}</small><el-progress :percentage="monthlyCostPercent" :show-text="false" /></article>
        <article><span>并发预占</span><strong>{{ overview.usage.activeReservations }}</strong><small>/ {{ overview.policy.maxConcurrentRuns }}</small></article>
      </div>

      <div class="cost-governance-grid">
        <article class="form-panel">
          <div class="detail-block-heading"><div><span class="eyebrow">Policy v{{ overview.policy.version }}</span><h3>Workspace 配额策略</h3></div><small>更新 {{ formatDate(overview.policy.updatedAt) }}</small></div>
          <form class="cost-policy-form" @submit.prevent="save">
            <label><span>启用治理</span><input v-model="form.enabled" type="checkbox" /></label>
            <label><span>每日 Token 上限</span><input v-model.number="form.dailyTokenLimit" type="number" min="1" required /></label>
            <label><span>每日费用上限（CNY）</span><input v-model.number="form.dailyCostLimitCny" type="number" min="0.000001" step="0.000001" required /></label>
            <label><span>月度 Token 上限</span><input v-model.number="form.monthlyTokenLimit" type="number" min="1" required /></label>
            <label><span>月度费用上限（CNY）</span><input v-model.number="form.monthlyCostLimitCny" type="number" min="0.000001" step="0.000001" required /></label>
            <label><span>最大并发 Run</span><input v-model.number="form.maxConcurrentRuns" type="number" min="1" max="100" required /></label>
            <label><span>预警阈值（%）</span><input v-model.number="form.warningPercent" type="number" min="1" max="99" required /></label>
            <label><span>达到上限硬拒绝</span><input v-model="form.hardLimitEnabled" type="checkbox" /></label>
            <button class="primary-button" type="submit" :disabled="saving">{{ saving ? '保存中…' : '保存策略' }}</button>
          </form>
        </article>

        <article class="run-table-panel cost-ledger-panel">
          <div class="detail-block-heading"><div><span class="eyebrow">Audit Ledger</span><h3>最近预占与结算</h3></div><small>{{ overview.ledger.length }} 条</small></div>
          <div v-if="overview.ledger.length === 0" class="detail-placeholder">尚无成本流水。</div>
          <div v-for="entry in overview.ledger" :key="entry.id" class="cost-ledger-row">
            <i class="status-pill" :class="`status-${entry.entryType.toLowerCase()}`">{{ ledgerLabel(entry) }}</i>
            <div><strong>Run {{ entry.runId.slice(0, 8) }}</strong><small>{{ entry.reason || '—' }} · {{ formatDate(entry.createdAt) }}</small></div>
            <span>{{ entry.tokenDelta >= 0 ? '+' : '' }}{{ formatNumber(entry.tokenDelta) }} Token<br />{{ entry.costDeltaCny >= 0 ? '+' : '' }}{{ formatCost(entry.costDeltaCny) }}</span>
          </div>
        </article>
      </div>
    </template>
    <div v-else-if="loading" class="run-loading">正在读取 Workspace 配额与成本流水…</div>
  </section>
</template>
