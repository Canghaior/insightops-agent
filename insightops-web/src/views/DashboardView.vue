<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { listProjects } from '@/api/projects'
import { listRuns, type RunSummary } from '@/api/runs'
import { listKnowledgeUploads } from '@/api/uploads'
import RunEvidenceQuality from '@/components/RunEvidenceQuality.vue'

import { useSystemStore } from '@/stores/system'

const systemStore = useSystemStore()
const modelLabel = computed(() => !systemStore.status ? '尚未取得状态'
  : systemStore.status.model.ready ? '已配置，尚未验证外部服务' : '尚未具备生成条件')
const capabilityLabels: Record<string, string> = {
  CONFIGURED_UNVERIFIED: '已配置，待运行验证', DISABLED: '已关闭',
  MISSING_CONFIGURATION: '待配置', DEGRADED: '部分能力', UNKNOWN: '尚未确认',
}

const projectCount = ref<number | null>(null), runCount = ref<number | null>(null), fileCount = ref<number | null>(null)
const recentRuns = ref<RunSummary[]>([]), dataError = ref('')
onMounted(async () => {
  void systemStore.refresh()
  const results = await Promise.allSettled([listProjects(), listRuns(0, 3), listKnowledgeUploads()])
  if (results[0].status === 'fulfilled') projectCount.value = results[0].value.filter(item => item.enabled).length
  if (results[1].status === 'fulfilled') { runCount.value = results[1].value.total; recentRuns.value = results[1].value.items }
  if (results[2].status === 'fulfilled') fileCount.value = results[2].value.filter(item => item.status === 'SUCCEEDED').length
  if (results.some(item => item.status === 'rejected')) dataError.value = '部分数据读取失败，未能读取的统计显示为 —。'
})
</script>

<template>
  <section class="page-grid">
    <article class="hero-card">
      <span class="eyebrow">今日研究入口</span>
      <h2>按需研究，不做无证据判断</h2>
      <p>围绕版本变化、兼容风险和迁移建议，形成带来源证据的技术判断。</p>
      <RouterLink class="primary-action" to="/upgrade-assessment">开始升级评估 →</RouterLink>
    </article>

    <article class="panel system-panel">
      <div class="panel-heading">
        <div>
          <span class="eyebrow">开始之前</span>
          <h3>当前研究能力</h3>
        </div>
        <button class="text-button" :disabled="systemStore.loading" @click="systemStore.refresh">刷新</button>
      </div>
      <p v-if="systemStore.error" role="status">{{ systemStore.error }}</p>
      <p v-else-if="!systemStore.status">正在读取当前环境配置…</p>
      <ul v-else-if="systemStore.status.capabilities?.length" class="capability-list">
        <li v-for="capability in systemStore.status.capabilities" :key="capability.id">
          <div><strong>{{ capability.label }}</strong><span>{{ capabilityLabels[capability.status] ?? '尚未确认' }}</span></div>
          <p>{{ capability.detail }}</p>
        </li>
      </ul>
      <p v-else>当前服务尚未提供能力明细。模型{{ modelLabel }}；不能据此判断采集和投递是否可用。</p>
      <details v-if="systemStore.status" class="capability-diagnostics">
        <summary>查看配置快照</summary>
        <p>本次未调用外部模型，也未检查采集与投递进程；配置存在不代表一次研究必然成功。</p>
        <dl class="status-list">
          <div><dt>服务接口</dt><dd>本次读取成功</dd></div>
          <div><dt>模型配置</dt><dd>{{ modelLabel }}</dd></div>
          <div><dt>当前模型</dt><dd>{{ systemStore.status.model.model }}</dd></div>
          <div><dt>读取时间</dt><dd>{{ new Date(systemStore.status.timestamp).toLocaleString('zh-CN') }}</dd></div>
        </dl>
      </details>
    </article>

    <div class="metric-grid">
      <article class="metric"><span>关注框架</span><strong>{{ projectCount ?? '—' }}</strong><small>当前工作区内的关注</small></article>
      <article class="metric"><span>研究记录</span><strong>{{ runCount ?? '—' }}</strong><small>你的实际研究次数</small></article>
      <article class="metric"><span>可用上传资料</span><strong>{{ fileCount ?? '—' }}</strong><small>已处理且你有权访问</small></article>
    </div>
    <p v-if="dataError" role="status">{{ dataError }}</p>

    <article class="panel full-span">
      <div class="panel-heading"><div><span class="eyebrow">继续上次的研究</span><h3>最近研究</h3></div><RouterLink to="/runs">查看全部</RouterLink></div>
      <ol class="flow-list">
        <li v-for="run in recentRuns" :key="run.id"><div><RouterLink :to="`/runs/${run.id}`">{{ run.question.split('\n')[0]?.slice(0, 100) }}</RouterLink><p>{{ run.status === 'SUCCEEDED' ? '执行完成' : run.status === 'FAILED' ? '研究失败，可查看原因' : '进行中或已暂停' }} · {{ new Date(run.createdAt).toLocaleString('zh-CN') }}</p><RunEvidenceQuality v-if="run.status === 'SUCCEEDED'" :run-id="run.id" compact /></div></li>
      </ol>
      <p v-if="runCount === 0">还没有研究记录。选择框架与版本，发起第一次升级评估。</p>
    </article>
  </section>
</template>

<style scoped>
.capability-list { list-style: none; padding: 0; margin: 0; display: grid; gap: 14px; }
.capability-list li > div { display: flex; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
.capability-list span { font-size: 12px; color: var(--text-secondary, #526077); }
.capability-list p, .capability-diagnostics p { margin: 6px 0 0; font-size: 13px; line-height: 1.6; }
.capability-diagnostics { margin-top: 18px; }
.capability-diagnostics summary { cursor: pointer; }
</style>
