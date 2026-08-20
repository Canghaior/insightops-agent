<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { listDigests, listNotifications, markDigestRead, markNotificationRead, type DigestPage, type NotificationItem, type NotificationPage } from '@/api/intelligence'
const router=useRouter()
const digests=ref<DigestPage>({items:[],page:0,size:20,total:0,unreadCount:0})
const notifications=ref<NotificationPage>({items:[],page:0,size:50,total:0,unreadCount:0})
const error=ref('')
async function load(){try{[digests.value,notifications.value]=await Promise.all([listDigests(),listNotifications()])}catch{error.value='摘要和通知加载失败，请稍后重试。'}}
async function readDigest(id:string){await markDigestRead(id);await load();globalThis.dispatchEvent(new globalThis.Event('insightops:notifications-changed'))}
async function openNotice(item:NotificationItem){await markNotificationRead(item.id);item.read=true;globalThis.dispatchEvent(new globalThis.Event('insightops:notifications-changed'));if(item.type==='RULE_MATCH'&&item.sourceUrl){globalThis.open(item.sourceUrl,'_blank','noopener,noreferrer');return}if(['ANALYSIS_READY','HIGH_RISK'].includes(item.type))await router.push({name:'intelligence-detail',params:{analysisId:item.entityId}})}
async function openAnalysis(id:string){await router.push({name:'intelligence-detail',params:{analysisId:id}})}
onMounted(load)
</script>
<template>
  <section>
    <div class="section-heading"><div><span class="eyebrow">站内情报投递</span><h2>情报摘要与通知</h2><p>规则命中通知可以直接打开对应的 GitHub 官方事件。</p></div><span class="subtle">{{ notifications.unreadCount }} 条未读通知 · {{ digests.unreadCount }} 份未读摘要</span></div>
    <p v-if="error" class="stream-error">{{ error }}</p>
    <div class="digest-layout">
      <div><h3 class="subsection-title">每日 / 每周摘要</h3><div class="digest-list"><article v-for="digest in digests.items" :key="digest.id" class="panel digest-card" :class="{unread:!digest.read}" @click="readDigest(digest.id)"><header><div><span class="eyebrow">{{ digest.cadence==='DAILY'?'每日':'每周' }}</span><h3>{{ digest.title }}</h3></div><i v-if="!digest.read">未读</i></header><p>{{ new Date(digest.periodStart).toLocaleDateString() }} — {{ new Date(digest.periodEnd).toLocaleDateString() }} · 高风险 {{ digest.highRiskCount }} 条</p><button v-for="item in digest.items" :key="item.analysisId" class="digest-item" @click.stop="openAnalysis(item.analysisId)"><b>{{ item.projectName }} {{ item.versionTag || '项目事件' }}</b><span>{{ item.oneLineSummary }}</span></button></article><div v-if="!digests.items.length" class="conversation-empty"><strong>尚未生成摘要</strong><p>在账号设置中开启每日或每周摘要；规则命中的事件完成分析后会按“加入摘要”设置汇总。</p></div></div></div>
      <aside><h3 class="subsection-title">通知中心</h3><div class="panel notification-list"><button v-for="item in notifications.items" :key="item.id" :class="{unread:!item.read,critical:item.severity==='CRITICAL'}" @click="openNotice(item)"><i></i><div><span class="eyebrow">{{ item.type }}</span><strong>{{ item.title }}</strong><p>{{ item.body }}</p><time>{{ new Date(item.createdAt).toLocaleString() }}</time></div></button><p v-if="!notifications.items.length" class="subtle">暂无通知。</p></div></aside>
    </div>
  </section>
</template>
