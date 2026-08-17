<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { listDigests, listNotifications, markDigestRead, markNotificationRead, type DigestPage, type NotificationPage } from '@/api/intelligence'
const router=useRouter(), digests=ref<DigestPage>({items:[],page:0,size:20,total:0,unreadCount:0}), notifications=ref<NotificationPage>({items:[],page:0,size:50,total:0,unreadCount:0}), error=ref('')
async function load(){try{[digests.value,notifications.value]=await Promise.all([listDigests(),listNotifications()])}catch{error.value='摘要和通知加载失败'}}
async function readDigest(id:string){await markDigestRead(id);await load();globalThis.dispatchEvent(new globalThis.Event('insightops:notifications-changed'))}
async function readNotice(id:string){await markNotificationRead(id);await load();globalThis.dispatchEvent(new globalThis.Event('insightops:notifications-changed'))}
async function openAnalysis(id:string){await router.push({name:'intelligence-detail',params:{analysisId:id}})}
onMounted(load)
</script>
<template>
  <section>
    <div class="section-heading"><div><span class="eyebrow">站内情报投递</span><h2>情报摘要与通知</h2></div><span class="subtle">{{ notifications.unreadCount }} 条未读通知 · {{ digests.unreadCount }} 份未读摘要</span></div><p v-if="error" class="stream-error">{{ error }}</p>
    <div class="digest-layout">
      <div><h3 class="subsection-title">每日 / 每周摘要</h3><div class="digest-list"><article v-for="d in digests.items" :key="d.id" class="panel digest-card" :class="{unread:!d.read}" @click="readDigest(d.id)"><header><div><span class="eyebrow">{{ d.cadence==='DAILY'?'每日':'每周' }}</span><h3>{{ d.title }}</h3></div><i v-if="!d.read">未读</i></header><p>{{ new Date(d.periodStart).toLocaleDateString() }} — {{ new Date(d.periodEnd).toLocaleDateString() }} · 高风险 {{ d.highRiskCount }} 条</p><button v-for="item in d.items" :key="item.analysisId" class="digest-item" @click.stop="openAnalysis(item.analysisId)"><b>{{ item.projectName }} {{ item.versionTag }}</b><span>{{ item.oneLineSummary }}</span></button></article><div v-if="!digests.items.length" class="conversation-empty"><strong>尚未生成摘要</strong><p>可在账号设置中选择每日或每周摘要；摘要只汇总已经完成的情报分析。</p></div></div></div>
      <aside><h3 class="subsection-title">通知中心</h3><div class="panel notification-list"><button v-for="n in notifications.items" :key="n.id" :class="{unread:!n.read,critical:n.severity==='CRITICAL'}" @click="readNotice(n.id)"><i></i><div><strong>{{ n.title }}</strong><p>{{ n.body }}</p><time>{{ new Date(n.createdAt).toLocaleString() }}</time></div></button><p v-if="!notifications.items.length" class="subtle">暂无通知。</p></div></aside>
    </div>
  </section>
</template>
