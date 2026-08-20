<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { listProjects, type ProjectWatch } from '@/api/projects'
import { requestIntelligenceAnalysis } from '@/api/admin'
import { useAuthStore } from '@/stores/auth'
import { listUpdates, markAllUpdatesRead, markUpdateRead, type ProjectUpdate, type UpdatePage } from '@/api/updates'

const router=useRouter();const auth=useAuthStore()
const projects=ref<ProjectWatch[]>([])
const page=ref<UpdatePage>({items:[],page:0,size:20,total:0,unreadCount:0})
const projectId=ref('');const eventType=ref('');const riskLevel=ref('');const unreadOnly=ref(false);const matchedOnly=ref(false)
const loading=ref(false);const error=ref('')
const watchedProjects=computed(()=>projects.value.filter(project=>project.enabled))
const typeLabels:Record<string,string>={GITHUB_RELEASE:'Release',GITHUB_ISSUE:'Issue',GITHUB_PULL_REQUEST:'Pull Request',GITHUB_SECURITY_ADVISORY:'安全公告'}

async function load(targetPage=0){loading.value=true;error.value='';try{page.value=await listUpdates({page:targetPage,size:20,projectId:projectId.value||undefined,eventType:eventType.value||undefined,riskLevel:riskLevel.value||undefined,unreadOnly:unreadOnly.value,matchedOnly:matchedOnly.value})}catch{error.value='技术情报加载失败，请稍后重试。'}finally{loading.value=false}}
async function read(update:ProjectUpdate){if(update.read)return;await markUpdateRead(update.eventId);update.read=true;page.value.unreadCount=Math.max(0,page.value.unreadCount-1);globalThis.dispatchEvent(new globalThis.Event('insightops:updates-changed'))}
async function readAll(){await markAllUpdatesRead();await load(page.value.page);globalThis.dispatchEvent(new globalThis.Event('insightops:updates-changed'))}
async function research(update:ProjectUpdate){await read(update);const type=typeLabels[update.eventType]??update.eventType;const question=`请分析 ${update.repositoryOwner}/${update.projectName} 的这条 ${type}：${update.title}。说明主要变化、风险、影响范围和建议行动，并引用官方事件证据。`;await router.push({name:'chat',query:{question}})}
async function requestAnalysis(update:ProjectUpdate){try{await requestIntelligenceAnalysis(update.eventId);await load(page.value.page)}catch{error.value='情报分析任务创建失败，该 Release 可能已在分析中。'}}
function formatDate(value:string){return new Intl.DateTimeFormat('zh-CN',{dateStyle:'medium',timeStyle:'short'}).format(new Date(value))}
function sourceLabel(update:ProjectUpdate){return `官方 ${typeLabels[update.eventType]??'来源'}`}
onMounted(async()=>{try{projects.value=await listProjects();await load()}catch{error.value='技术情报加载失败，请稍后重试。'}})
</script>

<template>
  <section>
    <div class="section-heading updates-heading"><div><span class="eyebrow">持续跟踪</span><h2>技术情报中心</h2><p>统一查看 Release、Issue、Pull Request 和安全公告。</p></div><div class="update-summary"><strong>{{ page.unreadCount }}</strong><span>条未读情报</span></div></div>
    <div class="panel update-controls intelligence-filters">
      <label>关注项目<select v-model="projectId" @change="load(0)"><option value="">全部项目</option><option v-for="project in watchedProjects" :key="project.id" :value="project.id">{{ project.name }}</option></select></label>
      <label>事件类型<select v-model="eventType" @change="load(0)"><option value="">全部类型</option><option v-for="(label,type) in typeLabels" :key="type" :value="type">{{ label }}</option></select></label>
      <label>风险等级<select v-model="riskLevel" @change="load(0)"><option value="">全部风险</option><option value="CRITICAL">CRITICAL</option><option value="HIGH">HIGH</option><option value="MEDIUM">MEDIUM</option><option value="LOW">LOW</option></select></label>
      <label class="check-label"><input v-model="unreadOnly" type="checkbox" @change="load(0)" /> 只看未读</label>
      <label class="check-label"><input v-model="matchedOnly" type="checkbox" @change="load(0)" /> 只看规则命中</label>
      <button class="secondary-button" :disabled="page.unreadCount===0" @click="readAll">全部标为已读</button>
    </div>
    <p v-if="error" class="stream-error">{{ error }}</p><p v-if="loading" class="run-loading">正在加载技术情报…</p>
    <div v-else class="update-list">
      <article v-for="update in page.items" :key="update.eventId" class="update-card" :class="{unread:!update.read}" @click="read(update)">
        <header><div><span class="eyebrow">{{ update.repositoryOwner }}/{{ update.projectName }} · {{ typeLabels[update.eventType] }}</span><h3>{{ update.title }}</h3></div><div class="update-badges"><i v-if="!update.read">未读</i><i v-if="update.matchedRuleCount">规则命中 {{ update.matchedRuleCount }}</i><code>{{ update.versionTag || update.state || `重要度 ${update.importance}` }}</code></div></header>
        <div v-if="update.riskLevel || update.analysisStatus" class="intelligence-preview"><span :class="`risk-${update.riskLevel?.toLowerCase()}`">{{ update.riskLevel || update.analysisStatus }}<template v-if="update.recommendation"> · {{ update.recommendation }}</template></span><p v-if="update.intelligenceSummary">{{ update.intelligenceSummary }}</p></div>
        <p>{{ update.summary }}</p>
        <div v-if="update.labels?.length" class="tag-row"><span v-for="label in update.labels.slice(0,8)" :key="label">{{ label }}</span></div>
        <footer><time>{{ formatDate(update.occurredAt) }}<template v-if="update.authorLogin"> · @{{ update.authorLogin }}</template></time><div><a :href="update.sourceUrl" target="_blank" rel="noreferrer" @click.stop="read(update)">{{ sourceLabel(update) }}</a><button v-if="update.analysisId&&update.analysisStatus==='SUCCEEDED'" class="secondary-button" @click.stop="router.push({name:'intelligence-detail',params:{analysisId:update.analysisId}})">查看完整分析</button><button v-else-if="update.eventType==='GITHUB_RELEASE'&&!update.analysisId&&auth.account?.systemRole==='SYSTEM_ADMIN'" class="secondary-button" @click.stop="requestAnalysis(update)">生成情报分析</button><button class="send-button" @click.stop="research(update)">基于本事件研究</button></div></footer>
      </article>
      <div v-if="!page.items.length" class="conversation-empty"><strong>暂时没有匹配的技术情报</strong><p>调整筛选条件，或等待 Worker 完成下一轮采集。</p></div>
    </div>
    <div v-if="page.total>page.size" class="run-pagination"><span>第 {{ page.page+1 }} 页 · 共 {{ page.total }} 条</span><div><button :disabled="page.page===0" @click="load(page.page-1)">上一页</button><button :disabled="(page.page+1)*page.size>=page.total" @click="load(page.page+1)">下一页</button></div></div>
  </section>
</template>
