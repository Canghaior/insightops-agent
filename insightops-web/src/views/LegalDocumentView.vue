<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'

import { getPublicBetaStatus, type PublicBetaStatus } from '@/api/publicBeta'

const route = useRoute()
const status = ref<PublicBetaStatus | null>(null)
const kind = computed(() => String(route.params.document))
const title = computed(() => ({ terms: 'InsightOps 用户协议', privacy: 'InsightOps 隐私政策',
  'acceptable-use': 'InsightOps 可接受使用政策' }[kind.value] ?? '法律文件'))
const version = computed(() => kind.value === 'terms' ? status.value?.termsVersion
  : kind.value === 'privacy' ? status.value?.privacyVersion : status.value?.acceptableUseVersion)
onMounted(async () => { try { status.value = await getPublicBetaStatus() } catch { status.value = null } })
</script>

<template>
  <main class="legal-page">
    <article class="legal-card">
      <RouterLink to="/login">← 返回 InsightOps</RouterLink>
      <span class="eyebrow">免费公开 Beta · 版本 {{ version || '2026-08-26' }}</span>
      <h1>{{ title }}</h1>
      <template v-if="kind === 'terms'">
        <h2>1. 服务性质</h2><p>InsightOps 是面向技术研究与情报分析的免费 Beta 服务，由 {{ status?.operatorName || '网站运营者' }} 个人运营。Beta 可能变更、中断或终止，不承诺商业级可用性。</p>
        <h2>2. 注册资格</h2><p>注册人须年满 {{ status?.minimumAge || 14 }} 周岁，提供可验证邮箱并妥善保护账号。不得转让、出租账号或绕过名额与并发限制。</p>
        <h2>3. AI 输出</h2><p>模型输出可能不准确、过时或不完整，不构成法律、医疗、金融或其他专业意见。重要决策必须核验原始来源。</p>
        <h2>4. 用户内容</h2><p>您保留所上传内容的合法权利，并授权本服务仅为提供检索、分析、备份、恢复和安全审计而处理这些内容。请勿上传无权处理的信息。</p>
        <h2>5. 终止与责任边界</h2><p>滥用、攻击、违法或严重影响其他用户时，运营者可限制或停用账号。法律允许范围内，本免费 Beta 不对间接损失或业务中断承担保证责任。</p>
      </template>
      <template v-else-if="kind === 'privacy'">
        <h2>1. 收集的信息</h2><p>服务处理账号资料、邮箱、Workspace 内容、聊天与运行记录、上传文件、Token 与费用统计、安全日志、设备与网络地址的散列指纹，以及协议同意记录。</p>
        <h2>2. 使用目的</h2><p>用于身份验证、提供 Agent 与检索服务、隔离租户、故障恢复、防滥用、安全审计和改进服务；不出售个人信息。</p>
        <h2>3. 存储与第三方</h2><p>主要数据保存在中国大陆的单台服务器和 PostgreSQL 中；邮件由腾讯云 SES 发送，人机验证由 Cloudflare Turnstile 处理。加密备份在 GitHub Actions Artifact 中最多保留 30 天。</p>
        <h2>4. 保存、导出与删除</h2><p>账号存续期间保存业务数据；您可申请数据导出或账号删除。删除设有宽限期，在线数据完成匿名化和用户级清理后，加密备份副本随最长 30 天保留期自然过期；必要的去标识化统计和安全审计可依法保留。</p>
        <h2>5. 联系方式</h2><p>隐私请求请联系 {{ status?.contactEmail || '待运营者公开的联系邮箱' }}。运营者：{{ status?.operatorName || '待配置' }}。</p>
      </template>
      <template v-else>
        <h2>允许的使用</h2><p>可用于合法的技术研究、版本比较、知识检索、报告生成和受控工作流执行。</p>
        <h2>禁止的使用</h2><p>不得实施违法活动、侵犯他人权利、恶意扫描或攻击、绕过安全或用量限制、批量注册、传播恶意代码、处理无权持有的敏感数据，或将 AI 输出冒充确定事实。</p>
        <h2>资源公平使用</h2><p>每个公开 Beta Workspace 同时最多执行 1 个 Run。即使暂不设置月额度，异常流量、自动化滥用或影响稳定性的行为仍可能被限速、暂停或终止。</p>
        <h2>报告问题</h2><p>安全、滥用和内容问题请发送至 {{ status?.contactEmail || '待运营者公开的联系邮箱' }}。</p>
      </template>
      <footer>生效日期：2026-08-26。公开前应由运营者核对名称、邮箱及适用法规。</footer>
    </article>
  </main>
</template>
