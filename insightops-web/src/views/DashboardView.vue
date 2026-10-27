<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { listProjects } from '@/api/projects'
import { listRuns, type RunSummary } from '@/api/runs'
import { listKnowledgeUploads } from '@/api/uploads'

import { useSystemStore } from '@/stores/system'

const systemStore = useSystemStore()
const modelLabel = computed(() => systemStore.status?.model.ready ? 'DeepSeek 已就绪' : '等待配置 Key')

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
          <span class="eyebrow">系统状态</span>
          <h3>运行准备度</h3>
        </div>
        <button class="text-button" :disabled="systemStore.loading" @click="systemStore.refresh">刷新</button>
      </div>
      <dl class="status-list">
        <div><dt>后端服务</dt><dd :class="{ muted: systemStore.error }">{{ systemStore.error ?? '正常' }}</dd></div>
        <div><dt>模型</dt><dd>{{ modelLabel }}</dd></div>
        <div><dt>当前模型</dt><dd>{{ systemStore.status?.model.model ?? 'deepseek-v4-flash' }}</dd></div>
      </dl>
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
        <li v-for="run in recentRuns" :key="run.id"><div><RouterLink :to="`/runs/${run.id}`">{{ run.question.split('\n')[0]?.slice(0, 100) }}</RouterLink><p>{{ run.status === 'SUCCEEDED' ? '研究完成' : run.status === 'FAILED' ? '研究失败，可查看原因' : '进行中或已暂停' }} · {{ new Date(run.createdAt).toLocaleString('zh-CN') }}</p></div></li>
      </ol>
      <p v-if="runCount === 0">还没有研究记录。选择框架与版本，发起第一次升级评估。</p>
    </article>
  </section>
</template>
