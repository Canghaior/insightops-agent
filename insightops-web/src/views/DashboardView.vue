<script setup lang="ts">
import { computed, onMounted } from 'vue'

import { useSystemStore } from '@/stores/system'

const systemStore = useSystemStore()
const modelLabel = computed(() => systemStore.status?.model.ready ? 'DeepSeek 已就绪' : '等待配置 Key')

onMounted(() => systemStore.refresh())
</script>

<template>
  <section class="page-grid">
    <article class="hero-card">
      <span class="eyebrow">今日研究入口</span>
      <h2>持续追踪，而不是临时搜索</h2>
      <p>围绕版本变化、兼容风险和迁移建议，形成带来源证据的技术判断。</p>
      <RouterLink class="primary-action" to="/chat">开始一次研究问答 →</RouterLink>
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
      <article class="metric"><span>跟踪项目</span><strong>3</strong><small>P0 固定范围</small></article>
      <article class="metric"><span>研究问题集</span><strong>20</strong><small>用于回归评测</small></article>
      <article class="metric"><span>数据来源</span><strong>1</strong><small>GitHub Releases</small></article>
    </div>

    <article class="panel full-span">
      <div class="panel-heading"><div><span class="eyebrow">P0 主链路</span><h3>第一条可验收闭环</h3></div></div>
      <ol class="flow-list">
        <li><span>01</span><div><strong>按需查询</strong><p>从 GitHub 官方 API 获取三个项目的 Release。</p></div></li>
        <li><span>02</span><div><strong>执行审计</strong><p>保存 Run、Step、Tool Call 与来源引用。</p></div></li>
        <li><span>03</span><div><strong>Agent 研究</strong><p>DeepSeek 调用工具并生成可追溯回答。</p></div></li>
        <li><span>04</span><div><strong>评测</strong><p>用 20 个问题检查正确性和引用质量。</p></div></li>
      </ol>
    </article>
  </section>
</template>
